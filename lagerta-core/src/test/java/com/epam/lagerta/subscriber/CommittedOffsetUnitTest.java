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

package com.epam.lagerta.subscriber;

import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.List;

import static org.testng.Assert.assertEquals;

public class CommittedOffsetUnitTest {

    @Test
    public void lastDenseCommitForNotCommittedTransaction() throws Exception {
        CommittedOffset committedOffset = new CommittedOffset();
        List<Long> readOffsets = Arrays.asList(0L, 1L, 2L, 3L);
        readOffsets.forEach(committedOffset::notifyRead);
        committedOffset.compress();
        assertEquals(committedOffset.getLastDenseCommit(), CommittedOffset.INITIAL_COMMIT_ID);
    }

    @Test
    public void lastDenseCommitForSequenceCommittedTransaction() throws Exception {
        CommittedOffset committedOffset = new CommittedOffset();
        List<Long> readOffsets = Arrays.asList(0L, 1L, 2L, 3L);
        List<Long> committedOffsets = Arrays.asList(0L, 1L, 2L, 3L);
        readOffsets.forEach(committedOffset::notifyRead);
        committedOffsets.forEach(committedOffset::notifyCommit);
        committedOffset.compress();
        assertEquals(committedOffset.getLastDenseCommit(), 3L);
    }

    @Test
    public void lastDenseCommitForCommittedTransaction() throws Exception {
        CommittedOffset committedOffset = new CommittedOffset();
        List<Long> readOffsets = Arrays.asList(0L, 1L, 2L, 3L);
        readOffsets.forEach(committedOffset::notifyRead);
        List<Long> committedOffsets = Arrays.asList(3L, 2L);

        committedOffsets.forEach(committedOffset::notifyCommit);
        committedOffset.compress();
        assertEquals(committedOffset.getLastDenseCommit(), CommittedOffset.INITIAL_COMMIT_ID);

        committedOffset.notifyCommit(0L);
        committedOffset.compress();
        assertEquals(committedOffset.getLastDenseCommit(), 0L);

        committedOffset.notifyCommit(1L);
        committedOffset.compress();
        assertEquals(committedOffset.getLastDenseCommit(), 3L);
    }
}