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

package org.apache.ignite.activestore.simple;

import org.apache.ignite.cache.CacheAtomicityMode;
import org.apache.ignite.cache.CacheMode;
import org.junit.Before;
import org.junit.Ignore;

/**
 * Atomic and replicated caches with write behind.
 */
@Ignore
public class AtomicReplicatedWriteBehindACSTest extends AtomicWriteBehindACSTest {
    @Before
    public void createCaches() {
        createCache(firstCache, CacheAtomicityMode.ATOMIC, CacheMode.REPLICATED, true, 500);
        createCache(secondCache, CacheAtomicityMode.ATOMIC, CacheMode.REPLICATED, true, 2500);
    }
}
