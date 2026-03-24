/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.storage.slowlogs;

import org.apache.lucene.util.Accountable;

/**
 * Interface that needs to be implemented by any per query metric collector
 */
public interface TieredStoragePerQueryMetric extends Accountable {

    void recordFileAccess(String blockFileName, boolean hit);

    void recordPrefetch(String fileName, int blockId);

    void recordReadAhead (String fileName, int blockId);

    void recordEndTime();

    String getParentTaskId();

    String getShardId();
}
