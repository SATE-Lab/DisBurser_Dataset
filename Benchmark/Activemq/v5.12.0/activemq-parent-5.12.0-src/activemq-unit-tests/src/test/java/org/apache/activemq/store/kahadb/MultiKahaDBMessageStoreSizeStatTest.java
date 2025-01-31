/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.store.kahadb;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.broker.region.Destination;
import org.apache.activemq.store.AbstractMessageStoreSizeStatTest;
import org.apache.commons.io.FileUtils;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This test checks that KahaDB properly sets the new storeMessageSize
 * statistic.
 *
 * AMQ-5748
 *
 */
public class MultiKahaDBMessageStoreSizeStatTest extends
        AbstractMessageStoreSizeStatTest {
    protected static final Logger LOG = LoggerFactory
            .getLogger(MultiKahaDBMessageStoreSizeStatTest.class);

    File dataFileDir = new File("target/test-amq-5748/stat-datadb");

    @Override
    protected void setUpBroker(boolean clearDataDir) throws Exception {
        if (clearDataDir && dataFileDir.exists())
            FileUtils.cleanDirectory(dataFileDir);
        super.setUpBroker(clearDataDir);
    }

    @Override
    protected void initPersistence(BrokerService brokerService)
            throws IOException {
        broker.setPersistent(true);

        //setup multi-kaha adapter
        MultiKahaDBPersistenceAdapter persistenceAdapter = new MultiKahaDBPersistenceAdapter();
        persistenceAdapter.setDirectory(dataFileDir);

        KahaDBPersistenceAdapter kahaStore = new KahaDBPersistenceAdapter();
        kahaStore.setJournalMaxFileLength(1024 * 512);

        //set up a store per destination
        FilteredKahaDBPersistenceAdapter filtered = new FilteredKahaDBPersistenceAdapter();
        filtered.setPersistenceAdapter(kahaStore);
        filtered.setPerDestination(true);
        List<FilteredKahaDBPersistenceAdapter> stores = new ArrayList<>();
        stores.add(filtered);

        persistenceAdapter.setFilteredPersistenceAdapters(stores);
        broker.setPersistenceAdapter(persistenceAdapter);
    }

    /**
     * Test that the the counter restores size and works after restart and more
     * messages are published
     *
     * @throws Exception
     */
    @Test
    public void testMessageSizeAfterRestartAndPublish() throws Exception {

        Destination dest = publishTestQueueMessages(200);

        // verify the count and size
        verifyStats(dest, 200, 200 * messageSize);

        // stop, restart broker and publish more messages
        stopBroker();
        this.setUpBroker(false);
        dest = publishTestQueueMessages(200);

        // verify the count and size
        verifyStats(dest, 400, 400 * messageSize);

    }

    @Test
    public void testMessageSizeAfterRestartAndPublishMultiQueue() throws Exception {

        Destination dest = publishTestQueueMessages(200);

        // verify the count and size
        verifyStats(dest, 200, 200 * messageSize);
        assertTrue(broker.getPersistenceAdapter().size() > 200 * messageSize);

        Destination dest2 = publishTestQueueMessages(200, "test.queue2");

        // verify the count and size
        verifyStats(dest2, 200, 200 * messageSize);
        assertTrue(broker.getPersistenceAdapter().size() > 400 * messageSize);

        // stop, restart broker and publish more messages
        stopBroker();
        this.setUpBroker(false);
        dest = publishTestQueueMessages(200);
        dest2 = publishTestQueueMessages(200, "test.queue2");

        // verify the count and size after publishing messages
        verifyStats(dest, 400, 400 * messageSize);
        verifyStats(dest2, 400, 400 * messageSize);

        System.out.println(broker.getPersistenceAdapter().size());
        assertTrue(broker.getPersistenceAdapter().size() > 800 * messageSize);
        assertTrue(broker.getPersistenceAdapter().size() >=
                (dest.getMessageStore().getMessageSize() + dest2.getMessageStore().getMessageSize()));

    }

}
