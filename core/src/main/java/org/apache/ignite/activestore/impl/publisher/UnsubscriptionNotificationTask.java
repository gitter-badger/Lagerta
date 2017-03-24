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

package org.apache.ignite.activestore.impl.publisher;

import java.util.UUID;

/**
 * @author Evgeniy_Ignatiev
 * @since 2/1/2017 6:46 PM
 */
public class UnsubscriptionNotificationTask implements Runnable {
    private final Commander commander;
    private final UUID replicaId;

    public UnsubscriptionNotificationTask(Commander commander, UUID replicaId) {
        this.commander = commander;
        this.replicaId = replicaId;
    }

    @Override public void run() {
        commander.processNodeUnsubscribedFromReplicaNotification(replicaId);
    }
}
