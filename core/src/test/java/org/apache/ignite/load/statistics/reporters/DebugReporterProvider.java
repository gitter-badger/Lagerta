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

package org.apache.ignite.load.statistics.reporters;

import com.codahale.metrics.ConsoleReporter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.ScheduledReporter;
import javax.inject.Inject;
import org.apache.ignite.load.statistics.StatisticsConfig;

/**
 * @author Evgeniy_Ignatiev
 * @since 1/23/2017 1:24 PM
 */
public class DebugReporterProvider implements ReporterProvider {
    private final MetricRegistry registry;
    private final StatisticsConfig config;

    @Inject
    public DebugReporterProvider(MetricRegistry registry, StatisticsConfig config) {
        this.registry = registry;
        this.config = config;
    }

    @Override public ScheduledReporter getIfEnabled() {
        if (!config.isDebugReportingEnabled()) {
            return null;
        }
        return ConsoleReporter.forRegistry(registry)
            .build();
    }
}
