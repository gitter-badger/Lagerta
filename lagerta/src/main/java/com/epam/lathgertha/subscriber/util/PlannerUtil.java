/*
 *  Copyright 2017 EPAM Systems.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.epam.lathgertha.subscriber.util;

import com.epam.lathgertha.subscriber.ConsumerTxScope;
import com.epam.lathgertha.subscriber.lead.CommittedTransactions;
import com.epam.lathgertha.subscriber.lead.ReadTransactions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

public final class PlannerUtil {
    private static final Function<UUID, Map<String, Set<?>>> CLAIMED = key -> new HashMap<>();
    private static final Function<UUID, List<Long>> READY = key -> new ArrayList<>();
    private static final Function<String, Set<?>> NEW_HASH_SET = key -> new HashSet();

    private PlannerUtil() {
    }

    public static Map<UUID, List<Long>> plan(
            ReadTransactions read,
            CommittedTransactions committed,
            Set<Long> inProgress) {

        Set<Entry<String, List>> blocked = new HashSet<>();
        Map<UUID, Map<String, Set<?>>> claimed = new HashMap<>();
        Map<UUID, List<Long>> plan = new HashMap<>();

        for (ConsumerTxScope info : read) {
            long id = info.getTransactionId();
            if (!committed.contains(id)) {
                List<Entry<String, List>> scope = info.getScope();
                if (inProgress.contains(id) || scope.stream().anyMatch(blocked::contains)) {
                    blocked.addAll(scope);
                } else {
                    UUID consumerId = info.getConsumerId();
                    if (isIntersectedWithClaimed(consumerId, scope, claimed)) {
                        blocked.addAll(scope);
                    } else {
                        Map<String, Set<?>> claimedScope = claimed.computeIfAbsent(consumerId, CLAIMED);
                        scope.forEach(addToClaimed(claimedScope));
                        plan.computeIfAbsent(consumerId, READY).add(id);
                    }
                }
            }
        }
        return plan;
    }

    @SuppressWarnings("unchecked")
    private static Consumer<Entry<String, List>> addToClaimed(Map<String, Set<?>> claimedScope) {
        return entry -> claimedScope
                .computeIfAbsent(entry.getKey(), NEW_HASH_SET)
                .addAll(entry.getValue());
    }

    private static boolean isIntersectedWithClaimed(
            UUID consumerId,
            List<Entry<String, List>> scope,
            Map<UUID, Map<String, Set<?>>> claimed) {
        return claimed.entrySet().stream()
                .filter(entry -> !entry.getKey().equals(consumerId))
                .map(Entry::getValue)
                .anyMatch(map -> isIntersected(map, scope));
    }

    @SuppressWarnings("unchecked")
    private static boolean isIntersected(Map<String, Set<?>> map, List<Entry<String, List>> scope) {
        return scope.stream().anyMatch(entry -> Optional.ofNullable(map.get(entry.getKey()))
                .map(claimedSet -> !Collections.disjoint(entry.getValue(), claimedSet))
                .orElse(false));
    }
}