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
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.CollectorManager;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.SimpleCollector;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.openjdk.jmh.annotations.*;

/**
 * Benchmark for PointRangeQuery with value-space parallelism.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"-Xmx2g", "-Xms2g"})
public class PointRangeQueryBenchmark {

  @Param({"1000000"})
  int numDocs;

  @Param({"4000", "40000"})  // 4% and 40% selectivity
  int queryRange;

  @Param({"1", "4", "8"})  // Test with different segment counts
  int numSegments;

  private Path tempDir;
  private Directory directory;
  private DirectoryReader reader;
  private IndexSearcher searcherNoIntra;
  private IndexSearcher searcherWithIntra;
  private ExecutorService executor;
  private Query query;

  @Setup(Level.Trial)
  public void setup() throws IOException {
    tempDir = Files.createTempDirectory("pointrange-bench");
    directory = new MMapDirectory(tempDir);

    IndexWriterConfig config = new IndexWriterConfig();
    config.setRAMBufferSizeMB(256);

    // Values from 0 to 100000
    int maxValue = 100000;
    int docsPerSegment = numDocs / numSegments;
    try (IndexWriter writer = new IndexWriter(directory, config)) {
      Random random = new Random(42);
      for (int seg = 0; seg < numSegments; seg++) {
        for (int i = 0; i < docsPerSegment; i++) {
          Document doc = new Document();
          int value = random.nextInt(maxValue);
          doc.add(new IntPoint("price", value));
          writer.addDocument(doc);
        }
        writer.commit();  // Force new segment after each batch
      }
    }

    reader = DirectoryReader.open(directory);
    executor = Executors.newFixedThreadPool(4);

    // Without intra-segment (baseline)
    searcherNoIntra = new IndexSearcher(reader, executor) {
      @Override
      protected LeafSlice[] slices(List<LeafReaderContext> leaves) {
        return IndexSearcher.slices(leaves, 250_000, 5, false);
      }
    };

    // With intra-segment (value-space parallel)
    searcherWithIntra = new IndexSearcher(reader, executor) {
      @Override
      protected LeafSlice[] slices(List<LeafReaderContext> leaves) {
        return IndexSearcher.slices(leaves, 250_000, 5, true);
      }
    };

    // Query for range based on selectivity parameter
    query = IntPoint.newRangeQuery("price", 1000, 1000 + queryRange);
  }

  @TearDown(Level.Trial)
  public void tearDown() throws IOException {
    reader.close();
    directory.close();
    executor.shutdownNow();
    Files.walk(tempDir)
        .sorted((a, b) -> -a.compareTo(b))
        .forEach(p -> {
          try { Files.delete(p); } catch (IOException e) {}
        });
  }

  /** Baseline: without intra-segment */
  @Benchmark
  public int noIntraSegment() throws IOException {
    return searcherNoIntra.search(query, new CountingCollectorManager());
  }

  /** With intra-segment + value-space parallelism */
  @Benchmark
  public int withIntraSegment() throws IOException {
    return searcherWithIntra.search(query, new CountingCollectorManager());
  }

  /** Verify correctness - both should return same count */
  @Benchmark
  public void verifyCorrectness() throws IOException {
    int noIntra = searcherNoIntra.search(query, new CountingCollectorManager());
    int withIntra = searcherWithIntra.search(query, new CountingCollectorManager());
    if (noIntra != withIntra) {
      throw new AssertionError("Count mismatch: noIntra=" + noIntra + " withIntra=" + withIntra);
    }
  }

  static class CountingCollectorManager implements CollectorManager<CountingCollector, Integer> {
    @Override
    public CountingCollector newCollector() {
      return new CountingCollector();
    }

    @Override
    public Integer reduce(Collection<CountingCollector> collectors) {
      int total = 0;
      for (CountingCollector c : collectors) {
        total += c.count;
      }
      return total;
    }
  }

  static class CountingCollector extends SimpleCollector {
    int count = 0;

    @Override
    public void collect(int doc) {
      count++;
    }

    @Override
    public ScoreMode scoreMode() {
      return ScoreMode.COMPLETE_NO_SCORES;
    }
  }
}
