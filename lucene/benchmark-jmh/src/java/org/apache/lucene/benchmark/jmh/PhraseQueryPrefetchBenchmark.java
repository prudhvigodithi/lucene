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
import org.apache.lucene.store.FSDirectory;
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
 * Benchmark for PhraseQuery with prefetch optimization.
 *
 * <p>Tests the performance improvement from parallelizing I/O when looking up multiple terms in
 * the term dictionary during phrase matching.
 *
 * <p>Run with: ./gradlew :lucene:benchmark-jmh:jmh -PjmhInclude=PhraseQueryPrefetchBenchmark
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 5)
@Fork(value = 1, jvmArgsAppend = {"-Xmx2g", "-Xms2g"})
public class PhraseQueryPrefetchBenchmark {

  private Directory directory;
  private DirectoryReader reader;
  private IndexSearcher searcher;
  private PhraseQuery[] queries;

  /** Number of terms in the phrase query */
  @Param({"2", "3", "5", "10"})
  public int phraseLength;

  /** Number of documents to index */
  private static final int NUM_DOCS = 100_000;

  /** Sample text for indexing - contains common phrases */
  private static final String[] SAMPLE_TEXTS = {
    "the quick brown fox jumps over the lazy dog near the river bank",
    "apache lucene is a high performance full featured text search engine library",
    "distributed search and analytics engine built on apache lucene",
    "the search engine processes millions of documents every single day",
    "natural language processing and information retrieval systems use lucene",
    "full text search queries can be optimized for better performance",
    "phrase queries match documents containing a particular sequence of terms",
    "term dictionary lookups can benefit from parallel input output operations",
    "block tree data structure stores terms in compressed blocks for efficiency",
    "prefetch hints to the operating system improve cache hit rates significantly"
  };

  @Setup(Level.Trial)
  public void setup() throws Exception {
    // Create temporary directory for index
    Path indexPath = Path.of(System.getProperty("java.io.tmpdir"), "phrase-benchmark-index");
    directory = FSDirectory.open(indexPath);

    // Build index
    IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer());
    config.setRAMBufferSizeMB(256);
    try (IndexWriter writer = new IndexWriter(directory, config)) {
      for (int i = 0; i < NUM_DOCS; i++) {
        Document doc = new Document();
        String text = SAMPLE_TEXTS[i % SAMPLE_TEXTS.length];
        doc.add(new TextField("body", text, Field.Store.NO));
        writer.addDocument(doc);

        // Create multiple segments for realistic scenario
        if (i % 10_000 == 0 && i > 0) {
          writer.commit();
        }
      }
      writer.commit();
    }

    // Open reader and searcher
    reader = DirectoryReader.open(directory);
    searcher = new IndexSearcher(reader);

    // Build phrase queries of different lengths
    queries = buildPhraseQueries();
  }

  private PhraseQuery[] buildPhraseQueries() {
    // Create queries based on phraseLength parameter
    switch (phraseLength) {
      case 2:
        return new PhraseQuery[] {
          new PhraseQuery("body", "quick", "brown"),
          new PhraseQuery("body", "apache", "lucene"),
          new PhraseQuery("body", "search", "engine"),
          new PhraseQuery("body", "full", "text"),
          new PhraseQuery("body", "operating", "system")
        };

      case 3:
        return new PhraseQuery[] {
          new PhraseQuery("body", "quick", "brown", "fox"),
          new PhraseQuery("body", "apache", "lucene", "is"),
          new PhraseQuery("body", "search", "engine", "library"),
          new PhraseQuery("body", "full", "text", "search"),
          new PhraseQuery("body", "term", "dictionary", "lookups")
        };

      case 5:
        return new PhraseQuery[] {
          new PhraseQuery("body", "the", "quick", "brown", "fox", "jumps"),
          new PhraseQuery("body", "apache", "lucene", "is", "a", "high"),
          new PhraseQuery("body", "full", "text", "search", "queries", "can"),
          new PhraseQuery("body", "term", "dictionary", "lookups", "can", "benefit"),
          new PhraseQuery("body", "prefetch", "hints", "to", "the", "operating")
        };

      case 10:
        return new PhraseQuery[] {
          new PhraseQuery(
              "body",
              "the",
              "quick",
              "brown",
              "fox",
              "jumps",
              "over",
              "the",
              "lazy",
              "dog",
              "near"),
          new PhraseQuery(
              "body",
              "apache",
              "lucene",
              "is",
              "a",
              "high",
              "performance",
              "full",
              "featured",
              "text",
              "search"),
          new PhraseQuery(
              "body",
              "distributed",
              "search",
              "and",
              "analytics",
              "engine",
              "built",
              "on",
              "apache",
              "lucene",
              "for"),
          new PhraseQuery(
              "body",
              "phrase",
              "queries",
              "match",
              "documents",
              "containing",
              "a",
              "particular",
              "sequence",
              "of",
              "terms"),
          new PhraseQuery(
              "body",
              "block",
              "tree",
              "data",
              "structure",
              "stores",
              "terms",
              "in",
              "compressed",
              "blocks",
              "for")
        };

      default:
        throw new IllegalArgumentException("Unsupported phrase length: " + phraseLength);
    }
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
   * Benchmark phrase queries. The prefetch optimization parallelizes I/O when looking up multiple
   * terms in the phrase.
   */
  @Benchmark
  public int phraseQuery() throws IOException {
    int totalHits = 0;
    for (PhraseQuery query : queries) {
      TopDocs results = searcher.search(query, 10);
      totalHits += results.totalHits.value();
    }
    return totalHits;
  }
}
