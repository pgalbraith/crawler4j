/**
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

package edu.uci.ics.crawler4j.frontier.je;

import java.io.File;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.DiskOrderedCursor;
import com.sleepycat.je.DiskOrderedCursorConfig;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.OperationStatus;
import com.sleepycat.je.Transaction;

import edu.uci.ics.crawler4j.crawler.CrawlConfig;
import edu.uci.ics.crawler4j.crawler.CrawlController;
import edu.uci.ics.crawler4j.frontier.Frontier;
import edu.uci.ics.crawler4j.frontier.FrontierException;
import edu.uci.ics.crawler4j.url.WebURL;

/**
 * @author Yasser Ganjisaffar
 */

public class BerkeleyJeFrontier implements Frontier {
    protected static final Logger logger = LoggerFactory.getLogger(BerkeleyJeFrontier.class);

    private static final String DATABASE_NAME = "PendingURLsDB";
    private final CrawlConfig config;
    private final CrawlController controller;
    protected WorkQueues workQueues;

    protected final Object mutex = new Object();

    protected long scheduledPages;

    protected Counters counters;

    Environment env;

    private DocIDServer docIdServer;

    private File envHome;

    ThreadLocal<Transaction> transactionHolder = new ThreadLocal<>();

    private EnvironmentConfig envConfig;

    public BerkeleyJeFrontier(CrawlConfig config, CrawlController controller) throws FrontierException {
        File folder = new File(config.getCrawlStorageFolder());
        if (!folder.exists()) {
            if (folder.mkdirs()) {
                logger.debug("Created folder: " + folder.getAbsolutePath());
            } else {
                throw new FrontierException(
                    "couldn't create the storage folder: " + folder.getAbsolutePath() +
                    " does it already exist ?");
            }
        }

        envConfig = new EnvironmentConfig();
        envConfig.setAllowCreate(true);
        envConfig.setTransactional(true);
        envConfig.setLocking(true);
        envConfig.setLockTimeout(0, TimeUnit.MILLISECONDS);

        envHome = new File(config.getCrawlStorageFolder() + "/frontier");
        if (!envHome.exists()) {
            if (envHome.mkdir()) {
                logger.debug("Created folder: " + envHome.getAbsolutePath());
            } else {
                throw new FrontierException(
                    "Failed creating the frontier folder: " + envHome.getAbsolutePath());
            }
        }

        env = new Environment(envHome, envConfig);
        docIdServer = new DocIDServer(this, config);

        this.config = config;
        this.controller = controller;
        this.counters = new Counters(this, config);
        workQueues = new WorkQueues(this, DATABASE_NAME);
        if (config.isResumableCrawling()) {
            scheduledPages = counters.getValue(Counters.ReservedCounterNames.SCHEDULED_PAGES);
        } else {
            scheduledPages = 0;
        }
    }

    @Override
    public void scheduleAll(List<WebURL> urls) throws FrontierException {
        int maxPagesToFetch = config.getMaxPagesToFetch();
        synchronized (mutex) {
            int newScheduledPage = 0;
            for (WebURL url : urls) {
                if ((maxPagesToFetch > 0) &&
                    ((scheduledPages + newScheduledPage) >= maxPagesToFetch)) {
                    break;
                }

                try {
                    workQueues.put(url);
                    newScheduledPage++;
                } catch (DatabaseException e) {
                    if (config.isHaltOnError()) {
                        throw new FrontierException("Error while putting the url in the work queue", e);
                    } else {
                        logger.error("Error while putting the url in the work queue", e);
                    }
                }
            }
            if (newScheduledPage > 0) {
                scheduledPages += newScheduledPage;
                counters.increment(Counters.ReservedCounterNames.SCHEDULED_PAGES, newScheduledPage);
            }
        }
        controller.foundMorePages();
    }

    @Override
    public void schedule(WebURL url) throws FrontierException {
        int maxPagesToFetch = config.getMaxPagesToFetch();
        synchronized (mutex) {
            try {
                if (maxPagesToFetch < 0 || scheduledPages < maxPagesToFetch) {
                    workQueues.put(url);
                    scheduledPages++;
                    counters.increment(Counters.ReservedCounterNames.SCHEDULED_PAGES);
                }
            } catch (DatabaseException e) {
                if (config.isHaltOnError()) {
                    throw new FrontierException("Error while putting the url in the work queue", e);
                } else {
                    logger.error("Error while putting the url in the work queue", e);
                }
            }
        }
        controller.foundMorePages();
    }

    @Override
    public synchronized void getNextURLs(int max, List<WebURL> result) throws FrontierException {
        try {
            List<WebURL> curResults = workQueues.get(max);
            result.addAll(curResults);
        } catch (DatabaseException e) {
            if (config.isHaltOnError()) {
                throw new FrontierException("Error while getting next urls", e);
            } else {
                logger.error("Error while getting next urls", e);
            }
        }
    }

    @Override
    public void setProcessed(WebURL webURL) {
        counters.increment(Counters.ReservedCounterNames.PROCESSED_PAGES);
    }

    public long getQueueLength() {
        return workQueues.getLength();
    }

    public long getNumberOfProcessedPages() {
        return counters.getValue(Counters.ReservedCounterNames.PROCESSED_PAGES);
    }

    public long getNumberOfScheduledPages() {
        return counters.getValue(Counters.ReservedCounterNames.SCHEDULED_PAGES);
    }

    @Override
    public void close() throws FrontierException {
        try {
            workQueues.close();
            counters.close();
            docIdServer.close();
            env.close();
        } catch (DatabaseException e) {
            throw new FrontierException(e);
        }
    }

    /* (non-Javadoc)
     * @see edu.uci.ics.crawler4j.frontier.Frontier#reset()
     */
    @Override
    public void reset() throws FrontierException {
        try {
            workQueues.process(clearDb);
            counters.process(clearDb);
            docIdServer.process(clearDb);
            scheduledPages = 0;
        } catch (DatabaseException e) {
            throw new FrontierException(e);
        }
    }

    public Consumer<Database> clearDb = db -> {
        if (db == null) {
            return;
        }

        Transaction txn = null;
        if (envConfig.getTransactional()) {
            txn = env.beginTransaction(null, null);
        }

        DatabaseEntry key = new DatabaseEntry();
        DatabaseEntry data = new DatabaseEntry();

        try (DiskOrderedCursor cursor = db.openCursor(new DiskOrderedCursorConfig().setKeysOnly(true))) {
            while (cursor.getNext(key, data, null) == OperationStatus.SUCCESS) {
                db.delete(txn, key);
            }
        }

        if (envConfig.getTransactional()) {
            txn.commit();
        }
    };

    /* (non-Javadoc)
     * @see edu.uci.ics.crawler4j.frontier.Frontier#getDocId(java.lang.String)
     */
    @Override
    public int getDocId(String url) {
        return docIdServer.getDocId(url);
    }

    /* (non-Javadoc)
     * @see edu.uci.ics.crawler4j.frontier.Frontier#getNewDocID(java.lang.String)
     */
    @Override
    public int getNewDocID(String url) {
        return docIdServer.getNewDocID(url);
    }

    /* (non-Javadoc)
     * @see edu.uci.ics.crawler4j.frontier.Frontier#addUrlAndDocId(java.lang.String, int)
     */
    @Override
    public void addUrlAndDocId(String url, int docId) {
        docIdServer.addUrlAndDocId(url, docId);
    }

    /* (non-Javadoc)
     * @see edu.uci.ics.crawler4j.frontier.Frontier#isSeenBefore(java.lang.String)
     */
    @Override
    public boolean isSeenBefore(String url) {
        return docIdServer.isSeenBefore(url);
    }

    /**
     * @return transaction bound to the current thread
     */
    public Transaction getTransaction() {
        return transactionHolder.get();
    }

    /* (non-Javadoc)
     * @see edu.uci.ics.crawler4j.frontier.Frontier#beginTransaction()
     */
    @Override
    public void beginTransaction() {
        assert getTransaction() == null;
        transactionHolder.set(env.beginTransaction(null, null));
    }

    /* (non-Javadoc)
     * @see edu.uci.ics.crawler4j.frontier.Frontier#commit()
     */
    @Override
    public void commit() {
        getTransaction().commit();
        transactionHolder.set(null);
    }

    /* (non-Javadoc)
     * @see edu.uci.ics.crawler4j.frontier.Frontier#rollback()
     */
    @Override
    public void rollback() {
        getTransaction().abort();
        transactionHolder.set(null);
    }

}
