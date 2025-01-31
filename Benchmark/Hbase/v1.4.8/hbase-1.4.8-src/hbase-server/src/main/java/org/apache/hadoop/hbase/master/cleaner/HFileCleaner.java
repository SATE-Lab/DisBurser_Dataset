/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.master.cleaner;

import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.Stoppable;
import org.apache.hadoop.hbase.classification.InterfaceAudience;
import org.apache.hadoop.hbase.conf.ConfigurationObserver;
import org.apache.hadoop.hbase.io.HFileLink;
import org.apache.hadoop.hbase.regionserver.StoreFileInfo;
import org.apache.hadoop.hbase.util.StealJobQueue;

/**
 * This Chore, every time it runs, will clear the HFiles in the hfile archive
 * folder that are deletable for each HFile cleaner in the chain.
 */
@InterfaceAudience.Private
public class HFileCleaner extends CleanerChore<BaseHFileCleanerDelegate> implements
    ConfigurationObserver {

  public static final String MASTER_HFILE_CLEANER_PLUGINS = "hbase.master.hfilecleaner.plugins";

  public HFileCleaner(final int period, final Stoppable stopper, Configuration conf, FileSystem fs,
      Path directory) {
    this(period, stopper, conf, fs, directory, null);
  }

  // Configuration key for large/small throttle point
  public final static String HFILE_DELETE_THROTTLE_THRESHOLD =
      "hbase.regionserver.thread.hfilecleaner.throttle";
  public final static int DEFAULT_HFILE_DELETE_THROTTLE_THRESHOLD = 64 * 1024 * 1024;// 64M

  // Configuration key for large queue initial size
  public final static String LARGE_HFILE_QUEUE_INIT_SIZE =
      "hbase.regionserver.hfilecleaner.large.queue.size";
  public final static int DEFAULT_LARGE_HFILE_QUEUE_INIT_SIZE = 10240;

  // Configuration key for small queue initial size
  public final static String SMALL_HFILE_QUEUE_INIT_SIZE =
      "hbase.regionserver.hfilecleaner.small.queue.size";
  public final static int DEFAULT_SMALL_HFILE_QUEUE_INIT_SIZE = 10240;

  // Configuration key for large file delete thread number
  public final static String LARGE_HFILE_DELETE_THREAD_NUMBER =
      "hbase.regionserver.hfilecleaner.large.thread.count";
  public final static int DEFAULT_LARGE_HFILE_DELETE_THREAD_NUMBER = 1;

  // Configuration key for small file delete thread number
  public final static String SMALL_HFILE_DELETE_THREAD_NUMBER =
      "hbase.regionserver.hfilecleaner.small.thread.count";
  public final static int DEFAULT_SMALL_HFILE_DELETE_THREAD_NUMBER = 1;

  public static final String HFILE_DELETE_THREAD_TIMEOUT_MSEC =
      "hbase.regionserver.hfilecleaner.thread.timeout.msec";
  @VisibleForTesting
  static final long DEFAULT_HFILE_DELETE_THREAD_TIMEOUT_MSEC = 60 * 1000L;

  public static final String HFILE_DELETE_THREAD_CHECK_INTERVAL_MSEC =
      "hbase.regionserver.hfilecleaner.thread.check.interval.msec";
  @VisibleForTesting
  static final long DEFAULT_HFILE_DELETE_THREAD_CHECK_INTERVAL_MSEC = 1000L;

  private static final Log LOG = LogFactory.getLog(HFileCleaner.class);

  StealJobQueue<HFileDeleteTask> largeFileQueue;
  BlockingQueue<HFileDeleteTask> smallFileQueue;
  private int throttlePoint;
  private int largeQueueInitSize;
  private int smallQueueInitSize;
  private int largeFileDeleteThreadNumber;
  private int smallFileDeleteThreadNumber;
  private long cleanerThreadTimeoutMsec;
  private long cleanerThreadCheckIntervalMsec;
  private List<Thread> threads = new ArrayList<Thread>();
  private boolean running;

  private AtomicLong deletedLargeFiles = new AtomicLong();
  private AtomicLong deletedSmallFiles = new AtomicLong();

  /**
   * @param period the period of time to sleep between each run
   * @param stopper the stopper
   * @param conf configuration to use
   * @param fs handle to the FS
   * @param directory directory to be cleaned
   * @param params params could be used in subclass of BaseHFileCleanerDelegate
   */
  public HFileCleaner(final int period, final Stoppable stopper, Configuration conf, FileSystem fs,
                      Path directory, Map<String, Object> params) {
    super("HFileCleaner", period, stopper, conf, fs,
      directory, MASTER_HFILE_CLEANER_PLUGINS, params);
    throttlePoint =
        conf.getInt(HFILE_DELETE_THROTTLE_THRESHOLD, DEFAULT_HFILE_DELETE_THROTTLE_THRESHOLD);
    largeQueueInitSize =
        conf.getInt(LARGE_HFILE_QUEUE_INIT_SIZE, DEFAULT_LARGE_HFILE_QUEUE_INIT_SIZE);
    smallQueueInitSize =
        conf.getInt(SMALL_HFILE_QUEUE_INIT_SIZE, DEFAULT_SMALL_HFILE_QUEUE_INIT_SIZE);
    largeFileQueue = new StealJobQueue<>(largeQueueInitSize, smallQueueInitSize);
    smallFileQueue = largeFileQueue.getStealFromQueue();
    largeFileDeleteThreadNumber =
        conf.getInt(LARGE_HFILE_DELETE_THREAD_NUMBER, DEFAULT_LARGE_HFILE_DELETE_THREAD_NUMBER);
    smallFileDeleteThreadNumber =
        conf.getInt(SMALL_HFILE_DELETE_THREAD_NUMBER, DEFAULT_SMALL_HFILE_DELETE_THREAD_NUMBER);
    cleanerThreadTimeoutMsec =
        conf.getLong(HFILE_DELETE_THREAD_TIMEOUT_MSEC, DEFAULT_HFILE_DELETE_THREAD_TIMEOUT_MSEC);
    cleanerThreadCheckIntervalMsec =
        conf.getLong(HFILE_DELETE_THREAD_CHECK_INTERVAL_MSEC,
            DEFAULT_HFILE_DELETE_THREAD_CHECK_INTERVAL_MSEC);
    startHFileDeleteThreads();
  }

  @Override
  protected boolean validate(Path file) {
    if (HFileLink.isBackReferencesDir(file) || HFileLink.isBackReferencesDir(file.getParent())) {
      return true;
    }
    return StoreFileInfo.validateStoreFileName(file.getName());
  }

  /**
   * Exposed for TESTING!
   */
  public List<BaseHFileCleanerDelegate> getDelegatesForTesting() {
    return this.cleanersChain;
  }

  @Override
  public int deleteFiles(Iterable<FileStatus> filesToDelete) {
    int deletedFiles = 0;
    List<HFileDeleteTask> tasks = new ArrayList<HFileDeleteTask>();
    // construct delete tasks and add into relative queue
    for (FileStatus file : filesToDelete) {
      HFileDeleteTask task = deleteFile(file);
      if (task != null) {
        tasks.add(task);
      }
    }
    // wait for each submitted task to finish
    for (HFileDeleteTask task : tasks) {
      if (task.getResult(cleanerThreadCheckIntervalMsec)) {
        deletedFiles++;
      }
    }
    return deletedFiles;
  }

  /**
   * Construct an {@link HFileDeleteTask} for each file to delete and add into the correct queue
   * @param file the file to delete
   * @return HFileDeleteTask to track progress
   */
  private HFileDeleteTask deleteFile(FileStatus file) {
    HFileDeleteTask task = new HFileDeleteTask(file, cleanerThreadTimeoutMsec);
    boolean enqueued = dispatch(task);
    return enqueued ? task : null;
  }

  private boolean dispatch(HFileDeleteTask task) {
    if (task.fileLength >= this.throttlePoint) {
      if (!this.largeFileQueue.offer(task)) {
        // should never arrive here as long as we use PriorityQueue
        if (LOG.isTraceEnabled()) {
          LOG.trace("Large file deletion queue is full");
        }
        return false;
      }
    } else {
      if (!this.smallFileQueue.offer(task)) {
        // should never arrive here as long as we use PriorityQueue
        if (LOG.isTraceEnabled()) {
          LOG.trace("Small file deletion queue is full");
        }
        return false;
      }
    }
    return true;
  }

  @Override
  public void cleanup() {
    super.cleanup();
    stopHFileDeleteThreads();
  }

  /**
   * Start threads for hfile deletion
   */
  private void startHFileDeleteThreads() {
    final String n = Thread.currentThread().getName();
    running = true;
    // start thread for large file deletion
    for (int i = 0; i < largeFileDeleteThreadNumber; i++) {
      Thread large = new Thread() {
        @Override
        public void run() {
          consumerLoop(largeFileQueue);
        }
      };
      large.setDaemon(true);
      large.setName(n + "-HFileCleaner.large." + i + "-" + System.currentTimeMillis());
      large.start();
      LOG.debug("Starting hfile cleaner for large files: " + large.getName());
      threads.add(large);
    }

    // start thread for small file deletion
    for (int i = 0; i < smallFileDeleteThreadNumber; i++) {
      Thread small = new Thread() {
        @Override
        public void run() {
          consumerLoop(smallFileQueue);
        }
      };
      small.setDaemon(true);
      small.setName(n + "-HFileCleaner.small." + i + "-" + System.currentTimeMillis());
      small.start();
      LOG.debug("Starting hfile cleaner for small files: " + small.getName());
      threads.add(small);
    }
  }

  protected void consumerLoop(BlockingQueue<HFileDeleteTask> queue) {
    try {
      while (running) {
        HFileDeleteTask task = null;
        try {
          task = queue.take();
        } catch (InterruptedException e) {
          if (LOG.isDebugEnabled()) {
            LOG.debug("Interrupted while trying to take a task from queue", e);
          }
          break;
        }
        if (task != null) {
          if (LOG.isDebugEnabled()) {
            LOG.debug("Removing: " + task.filePath + " from archive");
          }
          boolean succeed;
          try {
            succeed = this.fs.delete(task.filePath, false);
          } catch (IOException e) {
            LOG.warn("Failed to delete file " + task.filePath, e);
            succeed = false;
          }
          task.setResult(succeed);
          if (succeed) {
            countDeletedFiles(task.fileLength >= throttlePoint, queue == largeFileQueue);
          }
        }
      }
    } finally {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Exit thread: " + Thread.currentThread());
      }
    }
  }

  // Currently only for testing purpose
  private void countDeletedFiles(boolean isLargeFile, boolean fromLargeQueue) {
    if (isLargeFile) {
      if (deletedLargeFiles.get() == Long.MAX_VALUE) {
        LOG.info("Deleted more than Long.MAX_VALUE large files, reset counter to 0");
        deletedLargeFiles.set(0L);
      }
      deletedLargeFiles.incrementAndGet();
    } else {
      if (deletedSmallFiles.get() == Long.MAX_VALUE) {
        LOG.info("Deleted more than Long.MAX_VALUE small files, reset counter to 0");
        deletedSmallFiles.set(0L);
      }
      if (fromLargeQueue && LOG.isTraceEnabled()) {
        LOG.trace("Stolen a small file deletion task in large file thread");
      }
      deletedSmallFiles.incrementAndGet();
    }
  }

  /**
   * Stop threads for hfile deletion
   */
  private void stopHFileDeleteThreads() {
    running = false;
    if (LOG.isDebugEnabled()) {
      LOG.debug("Stopping file delete threads");
    }
    for(Thread thread: threads){
      thread.interrupt();
    }
  }

  static class HFileDeleteTask implements Comparable<HFileDeleteTask> {

    boolean done = false;
    boolean result;
    final Path filePath;
    final long fileLength;
    final long timeoutMsec;

    public HFileDeleteTask(FileStatus file, long timeoutMsec) {
      this.filePath = file.getPath();
      this.fileLength = file.getLen();
      this.timeoutMsec = timeoutMsec;
    }

    public synchronized void setResult(boolean result) {
      this.done = true;
      this.result = result;
      notify();
    }

    public synchronized boolean getResult(long waitIfNotFinished) {
      long waitTimeMsec = 0;
      try {
        while (!done) {
          long startTimeNanos = System.nanoTime();
          wait(waitIfNotFinished);
          waitTimeMsec += TimeUnit.MILLISECONDS.convert(System.nanoTime() - startTimeNanos,
              TimeUnit.NANOSECONDS);
          if (done) {
            return this.result;
          }
          if (waitTimeMsec > timeoutMsec) {
            LOG.warn("Wait more than " + timeoutMsec + " ms for deleting " + this.filePath
                + ", exit...");
            return false;
          }
        }
      } catch (InterruptedException e) {
        LOG.warn("Interrupted while waiting for result of deleting " + filePath
            + ", will return false", e);
        return false;
      }
      return this.result;
    }

    @Override
    public int compareTo(HFileDeleteTask o) {
      long sub = this.fileLength - o.fileLength;
      // smaller value with higher priority in PriorityQueue, and we intent to delete the larger
      // file first.
      return (sub > 0) ? -1 : (sub < 0 ? 1 : 0);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || !(o instanceof HFileDeleteTask)) {
        return false;
      }
      HFileDeleteTask otherTask = (HFileDeleteTask) o;
      return this.filePath.equals(otherTask.filePath) && (this.fileLength == otherTask.fileLength);
    }

    @Override
    public int hashCode() {
      return filePath.hashCode();
    }
  }

  @VisibleForTesting
  public List<Thread> getCleanerThreads() {
    return threads;
  }

  @VisibleForTesting
  public long getNumOfDeletedLargeFiles() {
    return deletedLargeFiles.get();
  }

  @VisibleForTesting
  public long getNumOfDeletedSmallFiles() {
    return deletedSmallFiles.get();
  }

  @VisibleForTesting
  public long getLargeQueueInitSize() {
    return largeQueueInitSize;
  }

  @VisibleForTesting
  public long getSmallQueueInitSize() {
    return smallQueueInitSize;
  }

  @VisibleForTesting
  public long getThrottlePoint() {
    return throttlePoint;
  }

  @VisibleForTesting
  long getCleanerThreadTimeoutMsec() {
    return cleanerThreadTimeoutMsec;
  }

  @VisibleForTesting
  long getCleanerThreadCheckIntervalMsec() {
    return cleanerThreadCheckIntervalMsec;
  }

  @Override
  public void onConfigurationChange(Configuration conf) {
    if (!checkAndUpdateConfigurations(conf)) {
      LOG.debug("Update configuration triggered but nothing changed for this cleaner");
      return;
    }
    stopHFileDeleteThreads();
    // record the left over tasks
    List<HFileDeleteTask> leftOverTasks =
        new ArrayList<>(largeFileQueue.size() + smallFileQueue.size());
    for (HFileDeleteTask task : largeFileQueue) {
      leftOverTasks.add(task);
    }
    for (HFileDeleteTask task : smallFileQueue) {
      leftOverTasks.add(task);
    }
    largeFileQueue = new StealJobQueue<>(largeQueueInitSize, smallQueueInitSize);
    smallFileQueue = largeFileQueue.getStealFromQueue();
    threads.clear();
    startHFileDeleteThreads();
    // re-dispatch the left over tasks
    for (HFileDeleteTask task : leftOverTasks) {
      dispatch(task);
    }
  }

  /**
   * Check new configuration and update settings if value changed
   * @param conf The new configuration
   * @return true if any configuration for HFileCleaner changes, false if no change
   */
  private boolean checkAndUpdateConfigurations(Configuration conf) {
    boolean updated = false;
    int throttlePoint =
        conf.getInt(HFILE_DELETE_THROTTLE_THRESHOLD, DEFAULT_HFILE_DELETE_THROTTLE_THRESHOLD);
    if (throttlePoint != this.throttlePoint) {
      LOG.debug("Updating throttle point, from " + this.throttlePoint + " to " + throttlePoint);
      this.throttlePoint = throttlePoint;
      updated = true;
    }
    int largeQueueInitSize =
        conf.getInt(LARGE_HFILE_QUEUE_INIT_SIZE, DEFAULT_LARGE_HFILE_QUEUE_INIT_SIZE);
    if (largeQueueInitSize != this.largeQueueInitSize) {
      LOG.debug("Updating largeQueueInitSize, from " + this.largeQueueInitSize + " to "
          + largeQueueInitSize);
      this.largeQueueInitSize = largeQueueInitSize;
      updated = true;
    }
    int smallQueueInitSize =
        conf.getInt(SMALL_HFILE_QUEUE_INIT_SIZE, DEFAULT_SMALL_HFILE_QUEUE_INIT_SIZE);
    if (smallQueueInitSize != this.smallQueueInitSize) {
      LOG.debug("Updating smallQueueInitSize, from " + this.smallQueueInitSize + " to "
          + smallQueueInitSize);
      this.smallQueueInitSize = smallQueueInitSize;
      updated = true;
    }
    int largeFileDeleteThreadNumber =
        conf.getInt(LARGE_HFILE_DELETE_THREAD_NUMBER, DEFAULT_LARGE_HFILE_DELETE_THREAD_NUMBER);
    if (largeFileDeleteThreadNumber != this.largeFileDeleteThreadNumber) {
      LOG.debug("Updating largeFileDeleteThreadNumber, from " + this.largeFileDeleteThreadNumber
          + " to " + largeFileDeleteThreadNumber);
      this.largeFileDeleteThreadNumber = largeFileDeleteThreadNumber;
      updated = true;
    }
    int smallFileDeleteThreadNumber =
        conf.getInt(SMALL_HFILE_DELETE_THREAD_NUMBER, DEFAULT_SMALL_HFILE_DELETE_THREAD_NUMBER);
    if (smallFileDeleteThreadNumber != this.smallFileDeleteThreadNumber) {
      LOG.debug("Updating smallFileDeleteThreadNumber, from " + this.smallFileDeleteThreadNumber
          + " to " + smallFileDeleteThreadNumber);
      this.smallFileDeleteThreadNumber = smallFileDeleteThreadNumber;
      updated = true;
    }
    long cleanerThreadTimeoutMsec =
        conf.getLong(HFILE_DELETE_THREAD_TIMEOUT_MSEC, DEFAULT_HFILE_DELETE_THREAD_TIMEOUT_MSEC);
    if (cleanerThreadTimeoutMsec != this.cleanerThreadTimeoutMsec) {
      this.cleanerThreadTimeoutMsec = cleanerThreadTimeoutMsec;
      updated = true;
    }
    long cleanerThreadCheckIntervalMsec =
        conf.getLong(HFILE_DELETE_THREAD_CHECK_INTERVAL_MSEC,
            DEFAULT_HFILE_DELETE_THREAD_CHECK_INTERVAL_MSEC);
    if (cleanerThreadCheckIntervalMsec != this.cleanerThreadCheckIntervalMsec) {
      this.cleanerThreadCheckIntervalMsec = cleanerThreadCheckIntervalMsec;
      updated = true;
    }
    return updated;
  }
}
