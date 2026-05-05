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
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.PhraseQuery;
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
 * Cold-cache benchmark for PhraseQuery prefetch optimization.
 *
 * <p>This benchmark is designed to measure prefetch benefits under realistic conditions:
 *
 * <ul>
 *   <li>Large index (1M docs, ~500MB-1GB) that doesn't fit entirely in OS cache
 *   <li>Random term selection to avoid sequential cache hits
 *   <li>MMapDirectory for realistic I/O patterns
 *   <li>Multiple forks for statistical significance
 * </ul>
 *
 * <p><b>To measure cold-cache performance:</b>
 *
 * <pre>
 * On macOS:
 *   sudo purge
 *   ./gradlew :lucene:benchmark-jmh:jmh -PjmhInclude=PhraseQueryColdCacheBenchmark
 *
 * On Linux:
 *   sync &amp;&amp; echo 3 | sudo tee /proc/sys/vm/drop_caches
 *   ./gradlew :lucene:benchmark-jmh:jmh -PjmhInclude=PhraseQueryColdCacheBenchmark
 * </pre>
 *
 * <p>Run with: ./gradlew :lucene:benchmark-jmh:jmh
 * -PjmhInclude=PhraseQueryColdCacheBenchmark -PjmhFork=3
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 5)
@Measurement(iterations = 5, time = 10)
@Fork(value = 3, jvmArgsAppend = {"-Xmx4g", "-Xms4g"})
public class PhraseQueryColdCacheBenchmark {

  private Directory directory;
  private DirectoryReader reader;
  private IndexSearcher searcher;
  private PhraseQuery[] randomQueries;
  private Random random;

  /** Number of terms in the phrase query */
  @Param({"2", "3", "5", "10"})
  public int phraseLength;

  /** Number of documents to index - creates ~500MB-1GB index */
  private static final int NUM_DOCS = 1_000_000;

  /** Number of random queries to generate and cycle through */
  private static final int NUM_QUERIES = 100;

  /** Vocabulary of terms to build random phrases from */
  private static final String[] VOCABULARY = {
    // Common words that create diverse phrase combinations
    "the",
    "quick",
    "brown",
    "fox",
    "jumps",
    "over",
    "lazy",
    "dog",
    "apache",
    "lucene",
    "search",
    "engine",
    "library",
    "performance",
    "optimization",
    "distributed",
    "analytics",
    "index",
    "query",
    "document",
    "field",
    "term",
    "dictionary",
    "postings",
    "positions",
    "phrase",
    "match",
    "score",
    "rank",
    "result",
    "text",
    "full",
    "partial",
    "exact",
    "fuzzy",
    "wildcard",
    "prefix",
    "suffix",
    "boolean",
    "filter",
    "sort",
    "facet",
    "aggregate",
    "highlight",
    "suggest",
    "autocomplete",
    "spell",
    "check",
    "language",
    "analyzer",
    "tokenizer",
    "token",
    "stemmer",
    "lemma",
    "synonym",
    "stopword",
    "ngram",
    "shingle",
    "pattern",
    "regex",
    "parse",
    "process",
    "pipeline",
    "segment",
    "merge",
    "commit",
    "refresh",
    "flush",
    "optimize",
    "compact",
    "buffer",
    "memory",
    "cache",
    "disk",
    "storage",
    "network",
    "cluster",
    "shard",
    "replica",
    "partition",
    "balance",
    "allocate",
    "migrate",
    "snapshot",
    "restore",
    "backup",
    "recovery",
    "resilience",
    "availability",
    "durability",
    "consistency",
    "isolation",
    "transaction",
    "concurrency",
    "parallel",
    "thread",
    "pool",
    "executor"
  };

  @Setup(Level.Trial)
  public void setup() throws Exception {
    random = new Random(42); // Fixed seed for reproducibility

    // Create index in temporary directory
    Path indexPath =
        Path.of(System.getProperty("java.io.tmpdir"), "phrase-cold-cache-benchmark-index");

    // Use MMapDirectory for realistic I/O patterns
    directory = MMapDirectory.open(indexPath);

    // Build large index
    System.out.println("Building index with " + NUM_DOCS + " documents...");
    IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer());
    config.setRAMBufferSizeMB(512);
    config.setMaxBufferedDocs(10000);

    try (IndexWriter writer = new IndexWriter(directory, config)) {
      for (int i = 0; i < NUM_DOCS; i++) {
        Document doc = new Document();
        // Generate random text with vocabulary terms
        String text = generateRandomText(50); // ~50 words per doc
        doc.add(new TextField("body", text, Field.Store.NO));
        writer.addDocument(doc);

        // Force multiple segments for realistic scenario
        if (i % 100_000 == 0 && i > 0) {
          writer.commit();
          System.out.println("Indexed " + i + " documents...");
        }
      }
      writer.commit();
      writer.forceMerge(10); // Keep ~10 segments
    }

    // Open reader and searcher
    reader = DirectoryReader.open(directory);
    searcher = new IndexSearcher(reader);

    // Build random phrase queries
    randomQueries = buildRandomPhraseQueries();

    System.out.println("Index ready:");
    System.out.println("  - Docs: " + reader.numDocs());
    System.out.println("  - Segments: " + reader.leaves().size());
    System.out.println("  - Queries: " + randomQueries.length);
    System.out.println("\nNOTE: For cold-cache testing, drop OS cache before each fork:");
    System.out.println("  macOS: sudo purge");
    System.out.println("  Linux: sync && echo 3 | sudo tee /proc/sys/vm/drop_caches");
  }

  /** Generate random text from vocabulary */
  private String generateRandomText(int numWords) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < numWords; i++) {
      if (i > 0) sb.append(" ");
      sb.append(VOCABULARY[random.nextInt(VOCABULARY.length)]);
    }
    return sb.toString();
  }

  /** Build random phrase queries of specified length */
  private PhraseQuery[] buildRandomPhraseQueries() {
    PhraseQuery[] queries = new PhraseQuery[NUM_QUERIES];
    Random queryRandom = new Random(123); // Different seed for query generation

    for (int i = 0; i < NUM_QUERIES; i++) {
      String[] terms = new String[phraseLength];
      for (int j = 0; j < phraseLength; j++) {
        terms[j] = VOCABULARY[queryRandom.nextInt(VOCABULARY.length)];
      }
      queries[i] = new PhraseQuery("body", terms);
    }
    return queries;
  }

  @TearDown(Level.Trial)
  public void teardown() throws Exception {
    if (reader != null) {
      reader.close();
    }
    if (directory != null) {
      directory.close();
    }
  }

  /**
   * Benchmark phrase queries with random term selection. This measures the prefetch optimization's
   * impact on I/O parallelization.
   */
  @Benchmark
  public int phraseQuery() throws IOException {
    // Cycle through random queries to avoid cache locality
    int queryIndex = random.nextInt(randomQueries.length);
    PhraseQuery query = randomQueries[queryIndex];

    TopDocs results = searcher.search(query, 10);
    return (int) results.totalHits.value();
  }
}
