/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.testing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.distributed.UpgradeableCluster;

/**
 * The base class for all CassandraTestContext implementations
 */
public abstract class AbstractCassandraTestContext implements AutoCloseable
{
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractCassandraTestContext.class);

    public final SimpleCassandraVersion version;
    protected UpgradeableCluster cluster;

    public CassandraIntegrationTest annotation;

    public AbstractCassandraTestContext(SimpleCassandraVersion version,
                                        UpgradeableCluster cluster,
                                        CassandraIntegrationTest annotation)
    {
        this.version = version;
        this.cluster = cluster;
        this.annotation = annotation;
    }

    public AbstractCassandraTestContext(SimpleCassandraVersion version,
                                        CassandraIntegrationTest annotation)
    {
        this.version = version;
        this.annotation = annotation;
    }

    public UpgradeableCluster cluster()
    {
        return cluster;
    }

    @Override
    public void close()
    {
        if (cluster != null)
        {
            try
            {
                cluster.close();
            }
            catch (Exception ex)
            {
                // Because different classloaders or different jars may be used to load this class, we can't
                // actually catch the specific ShutdownException.
                if (ex.getClass().getCanonicalName() == "org.apache.cassandra.distributed.shared.ShutdownException")
                {
                    LOGGER.warn("Encountered shutdown exception which closing the cluster", ex);
                }
                else
                {
                    throw ex;
                }
            }
        }
    }
}
