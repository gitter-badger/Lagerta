/*
 * Copyright (c) 2017. EPAM Systems
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.activestore.load.workers;

import java.util.Map;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.activestore.load.Generator;
import org.apache.ignite.activestore.load.LoadTestConfig;
import org.apache.ignite.activestore.load.ServerSideEntryProcessor;
import org.apache.ignite.activestore.load.Worker;
import org.apache.ignite.activestore.load.WorkerEntryProcessor;

/**
 * {@link Worker} implementation to test performance of single read operations.
 */
public class ReadWorker extends Worker {
    /** */
    public static final String LOGGER_NAME = "IgniteReadLoadTest";

    /** */
    public ReadWorker(Ignite ignite, LoadTestConfig config, long startPosition, long endPosition,
        Generator keyGenerator, Generator valueGenerator) {
        super(ignite, config, startPosition, endPosition, keyGenerator, valueGenerator);
    }

    /** {@inheritDoc} */
    @Override protected String loggerName() {
        return LOGGER_NAME;
    }

    /** {@inheritDoc} */
    @Override protected WorkerEntryProcessor getEntryProcessor(Ignite ignite, LoadTestConfig config) {
        return new ReadEntryProcessor(ignite, config);
    }

    /**
     * Process a map of entries by getting a value from cache for each entry key.
     */
    public static class ReadEntryProcessor extends ServerSideEntryProcessor {
        public ReadEntryProcessor(Ignite ignite, LoadTestConfig config) {
            super(ignite, config);
        }

        /** {@inheritDoc} */
        @SuppressWarnings("unchecked")
        @Override public void process(IgniteCache cache, Map<?, ?> entries) {
            for (Map.Entry<?, ?> entry : entries.entrySet()) {
                cache.get(entry.getKey());
            }
        }
    }
}
