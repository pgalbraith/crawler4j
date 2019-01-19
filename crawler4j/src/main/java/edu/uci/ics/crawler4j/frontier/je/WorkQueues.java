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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sleepycat.je.Cursor;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.LockMode;
import com.sleepycat.je.OperationStatus;
import com.sleepycat.je.Transaction;

import edu.uci.ics.crawler4j.url.WebURL;
import edu.uci.ics.crawler4j.util.Util;

/**
 * @author Yasser Ganjisaffar
 */
public class WorkQueues {
    private final Logger logger = LoggerFactory.getLogger(WorkQueues.class);

    private final Database urlsDB;

    private final WebURLTupleBinding webURLBinding;

    private final BerkeleyJeFrontier frontier;

    public WorkQueues(BerkeleyJeFrontier frontier, String dbName) {
        this.frontier = frontier;
        DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.setAllowCreate(true);
        dbConfig.setTransactional(true);
        dbConfig.setDeferredWrite(false);
        urlsDB = frontier.env.openDatabase(null, dbName, dbConfig);
        webURLBinding = new WebURLTupleBinding();
    }

    protected Cursor openCursor(Transaction txn) {
        return urlsDB.openCursor(txn, null);
    }

    public List<WebURL> get(int max) {
        List<WebURL> results = new ArrayList<>(max);
        DatabaseEntry key = new DatabaseEntry();
        DatabaseEntry value = new DatabaseEntry();
        try (Cursor cursor = openCursor(frontier.getTransaction())) {
            OperationStatus result = cursor.getFirst(key, value, LockMode.RMW);
            int matches = 0;
            while ((matches < max) && (result == OperationStatus.SUCCESS)) {
                if (value.getData().length > 0) {
                    results.add(webURLBinding.entryToObject(value));
                    matches++;
                }
                cursor.delete();
                result = cursor.getNext(key, value, LockMode.RMW);
            }
        }
        return results;
    }

    /*
     * The key that is used for storing URLs determines the order
     * they are crawled. Lower key values results in earlier crawling.
     * Here our keys are 6 bytes. The first byte comes from the URL priority.
     * The second byte comes from depth of crawl at which this URL is first found.
     * The rest of the 4 bytes come from the docid of the URL. As a result,
     * URLs with lower priority numbers will be crawled earlier. If priority
     * numbers are the same, those found at lower depths will be crawled earlier.
     * If depth is also equal, those found earlier (therefore, smaller docid) will
     * be crawled earlier.
     */
    protected static DatabaseEntry getDatabaseEntryKey(WebURL url) {
        byte[] keyData = new byte[6];
        keyData[0] = url.getPriority();
        keyData[1] = ((url.getDepth() > Byte.MAX_VALUE) ? Byte.MAX_VALUE : (byte) url.getDepth());
        Util.putIntInByteArray(url.getDocid(), keyData, 2);
        return new DatabaseEntry(keyData);
    }

    public void put(WebURL url) {
        DatabaseEntry value = new DatabaseEntry();
        webURLBinding.objectToEntry(url, value);
        urlsDB.put(frontier.getTransaction(), getDatabaseEntryKey(url), value);
    }

    public long getLength() {
        return urlsDB.count();
    }

    public void close() {
        urlsDB.close();
    }

    /**
     * @param consumer
     */
    public void process(Consumer<Database> consumer) {
        consumer.accept(urlsDB);
    }
}