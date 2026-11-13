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
package org.apache.lucene.codecs.lucene103.blocktree;

import java.util.Locale;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.ExitableDirectoryReader;
import org.apache.lucene.index.ExitableDirectoryReader.ExitingReaderException;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.QueryTimeout;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.index.AssertingDirectoryReader;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.tests.util.TestUtil;
import org.apache.lucene.util.automaton.CompiledAutomaton;
import org.apache.lucene.util.automaton.RegExp;

/**
 * Verifies that block tree's {@link Terms#intersect(CompiledAutomaton,
 * org.apache.lucene.util.BytesRef, QueryTimeout)} checks the timeout while it scans the terms
 * dictionary inside a single {@link TermsEnum#next()} call, not only between returned terms.
 */
public class TestIntersectTermsEnumTimeout extends LuceneTestCase {

  private static final String FIELD = "f";
  private static final int NUM_TERMS = 20_000;

  /** Counts polls and starts returning true at poll number {@code exitAtPoll} (1-based). */
  private static class CountingQueryTimeout implements QueryTimeout {
    final int exitAtPoll;
    int polls;

    CountingQueryTimeout(int exitAtPoll) {
      this.exitAtPoll = exitAtPoll;
    }

    @Override
    public boolean shouldExit() {
      return ++polls >= exitAtPoll;
    }

    @Override
    public String toString() {
      return "CountingQueryTimeout(polls=" + polls + ")";
    }
  }

  private Directory dir;
  private DirectoryReader reader;

  @Override
  public void setUp() throws Exception {
    super.setUp();
    dir = newDirectory();
    // Use the default (block tree) postings format: other formats may implement intersect
    // differently and are not covered by this test.
    IndexWriterConfig iwc =
        newIndexWriterConfig()
            .setCodec(TestUtil.alwaysPostingsFormat(TestUtil.getDefaultPostingsFormat()));
    try (IndexWriter w = new IndexWriter(dir, iwc)) {
      for (int i = 0; i < NUM_TERMS; i++) {
        Document doc = new Document();
        doc.add(new StringField(FIELD, String.format(Locale.ROOT, "%010d", i), Field.Store.NO));
        w.addDocument(doc);
      }
      w.forceMerge(1);
    }
    reader = DirectoryReader.open(dir);
    if (random().nextBoolean()) {
      // Check that the timeout also gets through Terms wrappers.
      reader = new AssertingDirectoryReader(reader);
    }
  }

  @Override
  public void tearDown() throws Exception {
    reader.close();
    dir.close();
    super.tearDown();
  }

  /**
   * The leading ".*" means block tree cannot skip any block, and the "q999" suffix matches no term,
   * so the first next() call visits every term and returns null.
   */
  private static CompiledAutomaton matchNothingAutomaton() {
    CompiledAutomaton compiled = new CompiledAutomaton(new RegExp(".*q999").toAutomaton());
    assertEquals(CompiledAutomaton.AUTOMATON_TYPE.NORMAL, compiled.type);
    return compiled;
  }

  public void testTimeoutDuringSingleNextThrows() throws Exception {
    // Poll 1 comes from the ExitableTermsEnum constructor. The ExitableTermsEnum does not poll
    // again before the first next() call reaches the codec, so polls 2 and 3 can only come from
    // inside the scan, after 512 and 1024 scan steps.
    CountingQueryTimeout timeout = new CountingQueryTimeout(3);
    LeafReader leaf = getOnlyLeafReader(ExitableDirectoryReader.wrap(reader, timeout));
    TermsEnum te = leaf.terms(FIELD).intersect(matchNothingAutomaton(), null);
    assertEquals(1, timeout.polls);
    expectThrows(ExitingReaderException.class, te::next);
    assertEquals(3, timeout.polls);
  }

  public void testNeverExitingTimeoutIsPolledDuringScan() throws Exception {
    CountingQueryTimeout timeout = new CountingQueryTimeout(Integer.MAX_VALUE);
    LeafReader leaf = getOnlyLeafReader(ExitableDirectoryReader.wrap(reader, timeout));
    TermsEnum te = leaf.terms(FIELD).intersect(matchNothingAutomaton(), null);
    assertNull(te.next());
    // One poll from the constructor, the rest from inside the single next() call.
    assertTrue("polls=" + timeout.polls, timeout.polls > NUM_TERMS / 1024);
  }

  public void testCallerChoosesException() throws Exception {
    class CustomException extends RuntimeException {}
    QueryTimeout timeout =
        new QueryTimeout() {
          @Override
          public boolean shouldExit() {
            return true;
          }

          @Override
          public void throwIfShouldExit() {
            throw new CustomException();
          }
        };
    Terms terms = getOnlyLeafReader(reader).terms(FIELD);
    TermsEnum te = terms.intersect(matchNothingAutomaton(), null, timeout);
    expectThrows(CustomException.class, te::next);
  }

  public void testNullTimeout() throws Exception {
    Terms terms = getOnlyLeafReader(reader).terms(FIELD);
    assertNull(terms.intersect(matchNothingAutomaton(), null, null).next());
  }
}
