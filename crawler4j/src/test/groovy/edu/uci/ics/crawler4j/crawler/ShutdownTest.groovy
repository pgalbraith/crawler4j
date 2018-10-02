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

package edu.uci.ics.crawler4j.crawler

import static com.github.tomakehurst.wiremock.client.WireMock.*
import static edu.uci.ics.crawler4j.crawler.CrawlController.*

import java.util.concurrent.atomic.*

import org.junit.*
import org.junit.rules.*

import com.github.tomakehurst.wiremock.common.FileSource
import com.github.tomakehurst.wiremock.core.*
import com.github.tomakehurst.wiremock.extension.*
import com.github.tomakehurst.wiremock.http.Request
import com.github.tomakehurst.wiremock.http.Response
import com.github.tomakehurst.wiremock.junit.*

import edu.uci.ics.crawler4j.fetcher.*
import edu.uci.ics.crawler4j.robotstxt.*
import edu.uci.ics.crawler4j.url.*
import spock.lang.*

/**
 * Test ability to cleanly shutdown a crawl in mid-process.
 *   
 * @author Paul Galbraith <paul.d.galbraith@gmail.com>
 */
class ShutdownTest extends Specification {
	
	@Shared transformer = new ResponseTransformer() {
		def page = 0
        public String getName() { return "infinite-website" }
        public Response transform(Request request, Response response, FileSource files, Parameters parameters) {
            return Response.Builder.like(response).but().body(
                "Link 1: http://localhost:8080/page/" + page++ + "\n" +
                "Link 2: http://localhost:8080/page/" + page++ + "\n"
            ).build()
        }
    }
        
    @Rule TemporaryFolder folder = new TemporaryFolder()
    @Rule WireMockRule wireMock = new WireMockRule(new WireMockConfiguration().extensions(transformer))
    
    def "shutdown crawl-in-process cleanly"() {
        
        given: "basic crawler setup that throws all unexpected exceptions (haltOnError)"
            def config = new CrawlConfig(crawlStorageFolder: folder.root, haltOnError: true, allowSingleLevelDomain: true, politenessDelay: 50)
            def fetcher = new PageFetcher(config)
            def robots = new RobotstxtServer(new RobotstxtConfig(enabled: false), fetcher)
            def controller = new CrawlController(config, fetcher, robots)
            def factory = new WebCrawlerFactory() {
                public WebCrawler newInstance() {
                    return new WebCrawler() {
                        public void visit(Page page) { logger.info page.webURL.url }
                    }
                }
            }
            
        and: "mock website with infinite web pages"
            // response body will be generated by "infinite-website" response transformer
            givenThat(get(urlMatching("/.*")).willReturn(aResponse().withHeader("Content-Type", "text/plain; charset=utf-8")))  
            
        when: "start crawl and shut it down before crawling is finished"
            controller.addSeed "http://localhost:8080/page/0"
            controller.start factory, 4, false
            sleep 1
            controller.shutdown()
            controller.waitUntilFinish()
        
        then: "no unchecked exceptions should occur"
            noExceptionThrown()
            
    }
    
}
