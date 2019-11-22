/*
 * Copyright 2018 Paul Galbraith <paul.d.galbraith@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package edu.uci.ics.crawler4j.frontier;

import java.util.List;

import edu.uci.ics.crawler4j.url.WebURL;

/**
 * @author Paul Galbraith <paul.d.galbraith@gmail.com>
 *
 */
public interface Frontier {

    /**
     * @param url
     * @param docId
     */
    void addUrlAndDocId(String url, int docId) throws FrontierException;

    /**
     * Begin a new transaction. Should be followed up with {@link #commit()} or
     * {@link #reset()} to end the transaction.
     */
    void beginTransaction() throws FrontierException;

    void close() throws FrontierException;

    /**
     * Commit all IO and end the current transaction.
     */
    void commit() throws FrontierException;

    /**
     * Returns the docid of an already seen url.
     *
     * @param url the URL for which the docid is returned.
     * @return the docid of the url if it is seen before. Otherwise -1 is returned.
     */
    int getDocId(String url) throws FrontierException;

    /**
     * @param url
     * @return
     */
    int getNewDocID(String url) throws FrontierException;

    /**
     * @param max
     * @param result
     * @throws InterruptedException
     */
    void getNextURLs(int max, List<WebURL> result) throws FrontierException;

    /**
     * @param url
     * @return
     */
    boolean isSeenBefore(String url) throws FrontierException;

    /**
     * Clear all stored crawl tracking data in preparation for a new crawl.
     */
    void reset() throws FrontierException;

    /**
     * Rollback all IO and end the transaction.
     */
    void rollback() throws FrontierException;

    /**
     * @param url
     */
    void schedule(WebURL url) throws FrontierException;

    /**
     * @param urls
     */
    void scheduleAll(List<WebURL> urls) throws FrontierException;

    /**
     * @param url
     */
    void setProcessed(WebURL url) throws FrontierException;

}
