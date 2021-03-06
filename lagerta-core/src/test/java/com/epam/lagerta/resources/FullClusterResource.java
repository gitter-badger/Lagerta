/*
 * Copyright (c) 2017. EPAM Systems.
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

package com.epam.lagerta.resources;

import com.epam.lagerta.cluster.IgniteClusterManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;

public class FullClusterResource implements Resource {
    private static final Logger LOG = LoggerFactory.getLogger(FullClusterResource.class);
    public static final String CONFIG_XML = "com/epam/lagerta/integration/server-config.xml";
    public static final int CLUSTER_SIZE = 2;

    private final DBResource dbResource;
    private final H2DataBaseServer h2DataBaseServer = new H2DataBaseServer();
    private final TemporaryDirectory tmpDir = new TemporaryDirectory();
    private final EmbeddedKafka kafka = new EmbeddedKafka(tmpDir, CLUSTER_SIZE);
    private final IgniteClusterResource cluster = new IgniteClusterResource(CLUSTER_SIZE);

    public FullClusterResource(DBResource dbResource) {
        this.dbResource = dbResource;
    }

    public void setClusterManager(IgniteClusterManager clusterManager) {
        this.cluster.setClusterManager(clusterManager);
    }

    public IgniteClusterResource igniteCluster() {
        return cluster;
    }

    public DBResource getDBResource() {
        return dbResource;
    }

    @Override
    public void setUp() throws Exception {
        setUpResources(tmpDir, kafka, h2DataBaseServer, dbResource, cluster);
    }

    @Override
    public void tearDown() {
        tearDownResources(cluster, kafka, dbResource, h2DataBaseServer, tmpDir);
    }

    public void cleanUpClusters() throws SQLException {
        cluster.clearCluster();
    }

    private static void setUpResources(Resource... resources) throws Exception {
        for (Resource resource : resources) {
            resource.setUp();
        }
    }

    private static void tearDownResources(Resource... resources) {
        for (Resource resource : resources) {
            try {
                resource.tearDown();
            } catch (Exception e) {
                LOG.error("Exception occurred while releasing resources: ", e);
            }
        }
    }
}
