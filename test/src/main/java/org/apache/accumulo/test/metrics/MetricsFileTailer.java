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
package org.apache.accumulo.test.metrics;

import java.io.File;
import java.io.RandomAccessFile;
import java.net.URL;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class allows testing of the publishing to the hadoop metrics system by processing a file for
 * metric records (written as a line.) The file should be configured using the hadoop metrics
 * properties as a file based sink with the prefix that is provided on instantiation of the
 * instance.
 *
 * This class will simulate tail-ing a file and is intended to be run in a separate thread. When the
 * underlying file has data written, the value returned by getLastUpdate will change, and the last
 * line can be retrieved with getLast().
 */
public class MetricsFileTailer implements Runnable, AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(MetricsFileTailer.class);

  private static final int BUFFER_SIZE = 4;

  private final String metricsPrefix;

  private final Lock lock = new ReentrantLock();
  private final AtomicBoolean running = new AtomicBoolean(Boolean.TRUE);

  private final AtomicLong lastUpdate = new AtomicLong(0);
  private final long startTime = System.nanoTime();

  private int lineCounter = 0;
  private final String[] lineBuffer = new String[BUFFER_SIZE];

  private final String metricsFilename;

  /**
   * Create an instance that will tail a metrics file. The filename / path is determined by the
   * hadoop-metrics-accumulo.properties sink configuration for the metrics prefix that is provided.
   *
   * @param metricsPrefix
   *          the prefix in the metrics configuration.
   */
  public MetricsFileTailer(final String metricsPrefix) {

    this.metricsPrefix = metricsPrefix;

    Configuration sub = loadMetricsConfig();

    // dump received configuration keys received.
    if (log.isTraceEnabled()) {
      // required for commons configuration - version 1.6
      @SuppressWarnings("unchecked")
      Iterator<String> keys = sub.getKeys();
      while (keys.hasNext()) {
        log.trace("configuration key:{}", keys.next());
      }
    }

    if (sub.containsKey("filename")) {
      metricsFilename = sub.getString("filename");
    } else {
      metricsFilename = "";
    }

  }

  /**
   * Create an instance by specifying a file directly instead of using the metrics configuration -
   * mainly for testing.
   *
   * @param metricsPrefix
   *          generally can be ignored.
   * @param filename
   *          the path / file to be monitored.
   */
  MetricsFileTailer(final String metricsPrefix, final String filename) {
    this.metricsPrefix = metricsPrefix;
    metricsFilename = filename;
  }

  /**
   * Look for the accumulo metrics configuration file on the classpath and return the subset for the
   * http sink.
   *
   * @return a configuration with http sink properties.
   */
  private Configuration loadMetricsConfig() {
    try {

      final URL propUrl =
          getClass().getClassLoader().getResource(MetricsTestSinkProperties.METRICS_PROP_FILENAME);

      if (propUrl == null) {
        throw new IllegalStateException(
            "Could not find " + MetricsTestSinkProperties.METRICS_PROP_FILENAME + " on classpath");
      }

      String filename = propUrl.getFile();

      Configuration config = new PropertiesConfiguration(filename);

      final Configuration sub = config.subset(metricsPrefix);

      if (log.isTraceEnabled()) {
        log.trace("Config {}", config);
        // required for commons configuration - version 1.6
        @SuppressWarnings("unchecked")
        Iterator<String> iterator = sub.getKeys();
        while (iterator.hasNext()) {
          String key = iterator.next();
          log.trace("'{}'='{}'", key, sub.getProperty(key));
        }
      }

      return sub;

    } catch (ConfigurationException ex) {
      throw new IllegalStateException(
          String.format("Could not find configuration file '%s' on classpath",
              MetricsTestSinkProperties.METRICS_PROP_FILENAME));
    }
  }

  /**
   * Creates a marker value that changes each time a new line is detected. Clients can use this to
   * determine if a call to getLast() will return a new value.
   *
   * @return a marker value set when a line is available.
   */
  public long getLastUpdate() {
    return lastUpdate.get();
  }

  /**
   * Get the last line seen in the file.
   *
   * @return the last line from the file.
   */
  public String getLast() {
    lock.lock();
    try {

      int last = (lineCounter % BUFFER_SIZE) - 1;
      if (last < 0) {
        last = BUFFER_SIZE - 1;
      }
      return lineBuffer[last];
    } finally {
      lock.unlock();
    }
  }

  /**
   * A loop that polls for changes and when the file changes, put the last line in a buffer that can
   * be retrieved by clients using getLast().
   */
  @Override
  public void run() {

    long filePos = 0;

    File f = new File(metricsFilename);

    while (running.get()) {

      try {
        Thread.sleep(5_000);
      } catch (InterruptedException ex) {
        running.set(Boolean.FALSE);
        Thread.currentThread().interrupt();
        return;
      }

      long len = f.length();

      try {

        // file truncated? reset position
        if (len < filePos) {
          filePos = 0;
          lock.lock();
          try {
            for (int i = 0; i < BUFFER_SIZE; i++) {
              lineBuffer[i] = "";
            }
            lineCounter = 0;
          } finally {
            lock.unlock();
          }
        }

        if (len > filePos) {
          // File must have had something added to it!
          RandomAccessFile raf = new RandomAccessFile(f, "r");
          raf.seek(filePos);
          String line;
          lock.lock();
          try {
            while ((line = raf.readLine()) != null) {
              lineBuffer[lineCounter++ % BUFFER_SIZE] = line;
            }

            lastUpdate.set(System.nanoTime() - startTime);

          } finally {
            lock.unlock();
          }
          filePos = raf.getFilePointer();
          raf.close();
        }
      } catch (Exception ex) {
        log.info("Error processing metrics file {}", metricsFilename, ex);
      }
    }
  }

  @Override
  public void close() {
    running.set(Boolean.FALSE);
  }
}