/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.benchmark.jmh;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MultiPhraseQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Benchmark for MultiPhraseQuery with batched TermStates.buildBatch() optimization.
 *
 * <p>This benchmark tests the performance improvement from parallelizing TermStates.build() calls
 * across multiple synonym terms in MultiPhraseQuery. The optimization batches all term lookups
 * across all segments, firing all I/O prefetch hints before resolving any of them.
 *
 * <p>Example scenario: Query for "quick|fast [brown|dark] fox" - OLD: Sequential build() for each
 * term (quick, fast, brown, dark, fox) - Each build() parallelizes across segments - NEW:
 * buildBatch() for all 5 terms at once - Fires all (5 terms × N segments) prefetch hints - Then
 * resolves all lookups together
 *
 * <p>Expected improvement: ~10-30% on cold cache with synonym-heavy queries
 *
 * <p>To run cold-cache benchmark on macOS: 1. Build: ./gradlew :lucene:benchmark-jmh:shadowJar 2.
 * Run with cache purging between forks: java -jar
 * lucene/benchmark-jmh/build/benchmarks/lucene-benchmark-jmh-11.0.0-SNAPSHOT.jar
 * MultiPhraseQueryBatchBenchmark -f 3 -wi 2 -i 3 -p synonymsPerPosition=2,3,5 3. Before each fork,
 * run: sudo purge
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 5)
@Measurement(iterations = 3, time = 10)
@Fork(value = 1, jvmArgsAppend = {"-Xmx4g", "-Xms4g"})
@State(Scope.Benchmark)
public class MultiPhraseQueryBatchBenchmark {

  @Param({"2", "3", "5"})
  int synonymsPerPosition;

  private Directory directory;
  private DirectoryReader reader;
  private IndexSearcher searcher;
  private List<MultiPhraseQuery> queries;

  // Synonym sets for creating realistic MultiPhraseQuery tests
  private static final String[][] SYNONYM_GROUPS = {
    {"quick", "fast", "rapid", "swift", "speedy"},
    {"brown", "dark", "tan", "amber", "bronze"},
    {"fox", "foxes", "vixen", "reynard"},
    {"jumps", "leaps", "bounds", "springs", "vaults"},
    {"over", "across", "above"},
    {"lazy", "idle", "sluggish", "lethargic", "indolent"},
    {"dog", "hound", "canine", "pooch", "pup"}
  };

  @Setup(Level.Trial)
  public void setup() throws IOException {
    // Create temp directory for index
    Path indexPath = Files.createTempDirectory("multi-phrase-bench");
    directory = new MMapDirectory(indexPath);

    // Build index with 1M documents
    System.out.println("Building index with 1000000 documents...");
    IndexWriterConfig config = new IndexWriterConfig();
    config.setCommitOnClose(true);

    Random random = new Random(42);
    try (IndexWriter writer = new IndexWriter(directory, config)) {
      for (int i = 0; i < 1000000; i++) {
        Document doc = new Document();

        // Create text with various synonym combinations
        StringBuilder text = new StringBuilder();
        for (String[] synonymGroup : SYNONYM_GROUPS) {
          String synonym = synonymGroup[random.nextInt(synonymGroup.length)];
          text.append(synonym).append(" ");
        }

        // Add some random filler text
        for (int j = 0; j < 20; j++) {
          text.append("word").append(random.nextInt(1000)).append(" ");
        }

        doc.add(new TextField("body", text.toString(), Field.Store.NO));
        writer.addDocument(doc);

        if ((i + 1) % 100000 == 0) {
          System.out.println("Indexed " + (i + 1) + " documents...");
          writer.commit(); // Create segments
        }
      }
    }

    reader = DirectoryReader.open(directory);
    searcher = new IndexSearcher(reader);

    System.out.println(
        "Index ready:\n  - Docs: "
            + reader.numDocs()
            + "\n  - Segments: "
            + reader.leaves().size()
            + "\n  - Queries: 100");
    System.out.println(
        "\nNOTE: For cold-cache testing, drop OS cache before each fork:\n"
            + "  macOS: sudo purge\n"
            + "  Linux: sync && echo 3 | sudo tee /proc/sys/vm/drop_caches");

    // Create 100 MultiPhraseQuery instances with varying synonym counts
    queries = new ArrayList<>();
    random = new Random(42);

    for (int q = 0; q < 100; q++) {
      MultiPhraseQuery.Builder builder = new MultiPhraseQuery.Builder();

      // Add 3 positions with synonyms
      for (int pos = 0; pos < 3; pos++) {
        String[] synonymGroup = SYNONYM_GROUPS[pos % SYNONYM_GROUPS.length];

        // Add N synonyms at this position based on parameter
        Term[] synonyms = new Term[Math.min(synonymsPerPosition, synonymGroup.length)];
        for (int s = 0; s < synonyms.length; s++) {
          synonyms[s] = new Term("body", synonymGroup[s]);
        }
        builder.add(synonyms, pos);
      }

      queries.add(builder.build());
    }
  }

  @TearDown(Level.Trial)
  public void teardown() throws IOException {
    if (reader != null) {
      reader.close();
    }
    if (directory != null) {
      directory.close();
    }
  }

  @Benchmark
  public TopDocs multiPhraseQuery() throws IOException {
    // Execute all queries and return the last result
    TopDocs result = null;
    for (MultiPhraseQuery query : queries) {
      result = searcher.search(query, 10);
    }
    return result;
  }
}
