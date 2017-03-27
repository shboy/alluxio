/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.master;

import alluxio.Configuration;
import alluxio.Constants;
import alluxio.PropertyKey;
import alluxio.clock.Clock;
import alluxio.exception.PreconditionMessage;
import alluxio.master.journal.AsyncJournalWriter;
import alluxio.master.journal.Journal;
import alluxio.master.journal.JournalOutputStream;
import alluxio.master.journal.JournalReader;
import alluxio.master.journal.JournalTailer;
import alluxio.master.journal.JournalTailerThread;
import alluxio.master.journal.JournalWriter;
import alluxio.master.journal.MutableJournal;
import alluxio.proto.journal.Journal.JournalEntry;
import alluxio.retry.RetryPolicy;
import alluxio.retry.TimeoutRetry;
import alluxio.util.executor.ExecutorServiceFactory;

import com.google.common.base.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import javax.annotation.concurrent.NotThreadSafe;

/**
 * This is the base class for all masters, and contains common functionality. Common functionality
 * mostly consists of journal operations, like initialization, journal tailing when in standby mode,
 * or journal writing when the master is the leader.
 */
@NotThreadSafe // TODO(jiri): make thread-safe (c.f. ALLUXIO-1664)
public abstract class AbstractMaster implements Master {
  private static final Logger LOG = LoggerFactory.getLogger(AbstractMaster.class);
  private static final long INVALID_FLUSH_COUNTER = -1;
  private static final long SHUTDOWN_TIMEOUT_MS = 10 * Constants.SECOND_MS;
  private static final long JOURNAL_FLUSH_RETRY_TIMEOUT_MS =
      Configuration.getLong(PropertyKey.MASTER_JOURNAL_FLUSH_TIMEOUT_MS);

  /** A factory for creating executor services when they are needed. */
  private ExecutorServiceFactory mExecutorServiceFactory = null;
  /** The executor used for running maintenance threads for the master. */
  private ExecutorService mExecutorService = null;
  /** A handler to the journal for this master. */
  private Journal mJournal;
  /** true if this master is in leader mode, and not standby mode. */
  private boolean mIsLeader = false;
  /** The thread that tails the journal when the master is in standby mode. */
  private JournalTailerThread mStandbyJournalTailer = null;
  /** The journal writer for when the master is the leader. */
  private JournalWriter mJournalWriter = null;
  /** The {@link AsyncJournalWriter} for async journal writes. */
  private AsyncJournalWriter mAsyncJournalWriter = null;

  /** The clock to use for determining the time. */
  protected final Clock mClock;

  /**
   * @param journal the journal to use for tracking master operations
   * @param clock the Clock to use for determining the time
   * @param executorServiceFactory a factory for creating the executor service to use for
   *        running maintenance threads
   */
  protected AbstractMaster(Journal journal, Clock clock,
      ExecutorServiceFactory executorServiceFactory) {
    mJournal = Preconditions.checkNotNull(journal);
    mClock = Preconditions.checkNotNull(clock);
    mExecutorServiceFactory = Preconditions.checkNotNull(executorServiceFactory);
  }

  @Override
  public Set<Class<?>> getDependencies() {
    return new HashSet<>();
  }

  @Override
  public void start(boolean isLeader) throws IOException {
    Preconditions.checkState(mExecutorService == null);
    mExecutorService = mExecutorServiceFactory.create();
    mIsLeader = isLeader;
    LOG.info("{}: Starting {} master.", getName(), mIsLeader ? "leader" : "standby");
    if (mIsLeader) {
      Preconditions.checkState(mJournal instanceof MutableJournal);
      mJournalWriter = ((MutableJournal) mJournal).getWriter();

      /**
       *
       * The sequence for dealing with the journal before starting as the leader:
       *
       * 1. Start the catchup tailer to process the latest checkpoint. This step is skipped
       *    during a standby to leader transition.
       * 2. Process all the relevant completed logs, then the incomplete log and marks it as
       *    complete afterwards.
       * 3. Start the journal updater thread that asynchronously polls for the new checkpoint and
       *    updates the journal with the new checkpoint. The updater thread also periodically
       *    garbage collects checkpoints and completed logs.
       * 4. Start serving.
       *
       * Since this method is called before the master RPC server starts serving, there is no
       * concurrent access to the master during these phases.
       */

      // Step 1.
      JournalReader journalReader;
      if (mStandbyJournalTailer != null && mStandbyJournalTailer.getLatestJournalTailer() != null
          && mStandbyJournalTailer.getLatestJournalTailer().isValid()) {
        // This master was previously in standby mode, and processed some of the journal. Re-use the
        // same tailer (still valid) to continue processing any remaining journal entries.
        // LOG.info("{}: finish processing remaining journal entries (standby -> master).",
        //   getName());
        catchupTailer = mStandbyJournalTailer.getLatestJournalTailer();
      } else {
        // This master has not successfully processed any of the journal, so create a fresh tailer
        // to process the entire journal.
        catchupTailer = JournalTailer.Factory.create(this, mJournal);
        if (catchupTailer.checkpointExists()) {
          // LOG.info("{}: process entire journal before becoming leader master.", getName());
          catchupTailer.processJournalCheckpoint(true);
        } else {
          LOG.info("{}: journal checkpoint does not exist, nothing to process.", getName());
          // This implies a fresh restart. Bootstrap the checkpoint.
          long latestSequenceNumber = catchupTailer.getLatestSequenceNumber();
          try (JournalOutputStream checkpointStream =
                   mJournalWriter.getCheckpointOutputStream(latestSequenceNumber)) {
            LOG.info("{}: start writing checkpoint.", getName());
            streamToJournalCheckpoint(checkpointStream);
          }
        }
      }

      // Step 2.
      catchupTailer.processNextJournalLogs();
      catchupTailer.processAndCompleteCurrentLog();

      // Step 3.
      // Start the journal updater thread.

      mAsyncJournalWriter = new AsyncJournalWriter(mJournalWriter);
    } else {
      // This master is in standby mode. Start the journal tailer thread. Since the master is in
      // standby mode, its RPC server is NOT serving. Therefore, the only thread modifying the
      // master is this journal tailer thread (no concurrent access).
      // This thread keeps picking up new completed logs and optionally building new checkpoints.
      mStandbyJournalTailer = new JournalTailerThread(this, mJournal);
      mStandbyJournalTailer.start();
    }
  }

  @Override
  public void stop() throws IOException {
    LOG.info("{}: Stopping {} master.", getName(), mIsLeader ? "leader" : "standby");
    if (mIsLeader) {
      // Stop this leader master.
      if (mJournalWriter != null) {
        mJournalWriter.close();
        mJournalWriter = null;
      }
    } else {
      if (mStandbyJournalTailer != null) {
        // Stop and wait for the journal tailer thread.
        mStandbyJournalTailer.shutdownAndJoin();
      }
    }
    // Shut down the executor service, interrupting any running threads.
    if (mExecutorService != null) {
      try {
        mExecutorService.shutdownNow();
        String awaitFailureMessage =
            "waiting for {} executor service to shut down. Daemons may still be running";
        try {
          if (!mExecutorService.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            LOG.warn("Timed out " + awaitFailureMessage, this.getClass().getSimpleName());
          }
        } catch (InterruptedException e) {
          LOG.warn("Interrupted while " + awaitFailureMessage, this.getClass().getSimpleName());
        }
      } finally {
        mExecutorService = null;
      }
    }
  }

  @Override
  public void transitionToLeader() {
    mJournal = Journal.Factory.create(mJournal.getLocation(), true);
  }

  /**
   * Writes a {@link JournalEntry} to the journal. Does NOT flush the journal.
   *
   * @param entry the {@link JournalEntry} to write to the journal
   */
  protected void writeJournalEntry(JournalEntry entry) {
    Preconditions.checkNotNull(mJournalWriter, "Cannot write entry: journal writer is null.");
    try {
      mJournalWriter.write(entry);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Flushes the journal.
   */
  protected void flushJournal() {
    Preconditions.checkNotNull(mJournalWriter, "Cannot flush journal: journal writer is null.");
    try {
      mJournalWriter.flush();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Appends a {@link JournalEntry} for writing to the journal.
   *
   * @param entry the {@link JournalEntry}
   * @param journalContext the journal context
   */
  protected void appendJournalEntry(JournalEntry entry, JournalContext journalContext) {
    Preconditions.checkNotNull(mAsyncJournalWriter, PreconditionMessage.ASYNC_JOURNAL_WRITER_NULL);
    journalContext.setFlushCounter(mAsyncJournalWriter.appendEntry(entry));
  }

  /**
   * Waits for the flush counter to be flushed to the journal. If the counter is
   * {@link #INVALID_FLUSH_COUNTER}, this is a noop.
   *
   * @param journalContext the journal context
   */
  private void waitForJournalFlush(JournalContext journalContext) {
    if (journalContext.getFlushCounter() == INVALID_FLUSH_COUNTER) {
      // Check this before the precondition.
      return;
    }
    Preconditions.checkNotNull(mAsyncJournalWriter, PreconditionMessage.ASYNC_JOURNAL_WRITER_NULL);

    RetryPolicy retry = new TimeoutRetry(JOURNAL_FLUSH_RETRY_TIMEOUT_MS, Constants.SECOND_MS);
    while (retry.attemptRetry()) {
      try {
        mAsyncJournalWriter.flush(journalContext.getFlushCounter());
        return;
      } catch (IOException e) {
        LOG.warn("Journal flush failed. retrying...", e);
      }
    }
    LOG.error(
        "Journal flush failed after {} attempts. Terminating process to prevent inconsistency.",
        retry.getRetryCount());
    if (Configuration.getBoolean(PropertyKey.TEST_MODE)) {
      throw new RuntimeException("Journal flush failed after " + retry.getRetryCount()
          + " attempts. Terminating process to prevent inconsistency.");
    }
    System.exit(-1);
  }

  /**
   * @return the {@link ExecutorService} for this master
   */
  protected ExecutorService getExecutorService() {
    return mExecutorService;
  }

  /**
   * @return new instance of {@link JournalContext}
   */
  protected JournalContext createJournalContext() {
    return new JournalContext();
  }

  /**
   * Context for storing journaling information.
   */
  @NotThreadSafe
  public final class JournalContext implements AutoCloseable {
    private long mFlushCounter;

    private JournalContext() {
      mFlushCounter = INVALID_FLUSH_COUNTER;
    }

    private long getFlushCounter() {
      return mFlushCounter;
    }

    private void setFlushCounter(long counter) {
      mFlushCounter = counter;
    }

    @Override
    public void close() {
      waitForJournalFlush(this);
    }
  }
}
