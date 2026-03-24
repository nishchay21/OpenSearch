/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.storage.slowlogs;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.util.RamUsageEstimator;
import org.opensearch.common.metrics.CounterMetric;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Query metric service for maintaining the metric collectors in single place. Singleton implementation.
 */
public class TieredStorageQueryMetricService {

    private static final long BASE_RAM_BYTES_USED = RamUsageEstimator.shallowSizeOfInstance(TieredStorageQueryMetricService.class);

    /**
     * logger
     */
    private static final Logger logger = LogManager.getLogger(TieredStorageQueryMetricService.class);

    /**
     * Single class instance
     */
    private static final TieredStorageQueryMetricService INSTANCE = new TieredStorageQueryMetricService();

    /**
     * Map of thread ID to active collector, providing a way to retrieve the currently active metric collector on a given thread.
     * The same thread can create multiple collectors over the lifetime of a given query, both for the same query or for other queries.
     * However, only one collector will be active for a given thread at a given time, inactive metric collectors for which the query
     * is still running are tracked in taskIdToFetchPhaseCollectorMap and taskIdToQueryPhaseCollectorMap
     */
    protected final ConcurrentMap<Long, TieredStoragePerQueryMetric> metricCollectors = new ConcurrentHashMap<>();

    /**
     * Map of task id + shard id to set of collectors, providing a way to look up all collectors for a given task/shard. We need both
     * as the same parent task may have multiple shards on the same node. For concurrent segment search there will be multiple collectors
     * per task/shard combination as each slice (thread) creates its own collector. The same thread can process multiple slices for the same
     * or for different queries so it does not come into the picture here.
     */
    protected final ConcurrentMap<String, Set<TieredStoragePerQueryMetric>> taskIdToQueryPhaseCollectorMap = new ConcurrentHashMap<>();
    protected final ConcurrentMap<String, Set<TieredStoragePerQueryMetric>> taskIdToFetchPhaseCollectorMap = new ConcurrentHashMap<>();

    private final PrefetchStatsHolder prefetchStats = new PrefetchStatsHolder();

    /**
     * Maximum number of metric collectors for single warm node
     */
    private static final int MAX_PER_QUERY_COLLECTOR_SIZE = 1000;

    /**
     * Private constructor to keep class Singleton
     */
    private TieredStorageQueryMetricService() {}

    /**
     * Method for returning static instance of class
     * @return singleton instance for class
     */
    public static TieredStorageQueryMetricService getInstance() {
        return INSTANCE;
    }

    /**
     * Returns per query metric collector for this thread. Caller needs to ensure that the collector
     * was initialized and added earlier, otherwise dummy collector is returned which is no op
     * @param threadId threadId for which the collector requested
     * @return instance of TieredStoragePerQueryMetric
     */
    public TieredStoragePerQueryMetric getMetricCollector(final long threadId) {
        return metricCollectors.getOrDefault(threadId, TieredStoragePerQueryMetricDummy.getInstance());
    }

    /**
     * Adds metric collector for requested thread Id which can be used later in other components using
     * getMetricCollector function. To prevent too much memory consumption, there is hard limit on the
     * number of metric collectors per OpenSearch node
     * @param threadId threadId for which collector to be added
     * @param metricCollector metric collector object for holding the metrics
     */
    public void addMetricCollector(final long threadId, final TieredStoragePerQueryMetric metricCollector, boolean isQueryPhase) {
        // TODO if possible add thread id in collector
        if (metricCollectors.size() >= MAX_PER_QUERY_COLLECTOR_SIZE ||
            taskIdToQueryPhaseCollectorMap.values().stream().mapToInt(Set::size).sum() >= MAX_PER_QUERY_COLLECTOR_SIZE ||
            taskIdToFetchPhaseCollectorMap.values().stream().mapToInt(Set::size).sum() >= MAX_PER_QUERY_COLLECTOR_SIZE ) {
            logger.error(String.format("Number of metric collectors already equals maximum size of %s. Skipping",
                MAX_PER_QUERY_COLLECTOR_SIZE));
        } else {
            // The same threadId will not be used concurrently, so below is safe
            metricCollectors.put(threadId, metricCollector);
            // Multiple threads can be working on the same shard at the same time though, so below needs to be atomic
            if (isQueryPhase) {
                taskIdToQueryPhaseCollectorMap.compute(metricCollector.getParentTaskId() + metricCollector.getShardId(),
                    (id, collectors) -> {
                        Set<TieredStoragePerQueryMetric> newCollectors = (collectors == null) ? new HashSet<>() : collectors;
                        newCollectors.add(metricCollector);
                        return newCollectors;
                    });
            } else {
                taskIdToFetchPhaseCollectorMap.compute(metricCollector.getParentTaskId() + metricCollector.getShardId(),
                    (id, collectors) -> {
                        Set<TieredStoragePerQueryMetric> newCollectors = (collectors == null) ? new HashSet<>() : collectors;
                        newCollectors.add(metricCollector);
                        return newCollectors;
                    });
            }

        }
    }

    /**
     * Removes the metric collector added earlier using addMetricCollector function
     * @param threadId threadId for which collector needs to be removed
     * @return instance of TieredStoragePerQueryMetric removed from collector map
     */
    public TieredStoragePerQueryMetric removeMetricCollector(final long threadId) {
        // Do not update taskIdToCollectorMap here as the query may not be complete
        // For safety, use getOrDefault here
        metricCollectors.getOrDefault(threadId, TieredStoragePerQueryMetricDummy.getInstance()).recordEndTime();
        return metricCollectors.remove(threadId);
    }

    public Set<TieredStoragePerQueryMetric> removeMetricCollectors(String parentTaskId, String shardId, boolean isQueryPhase) {
        final Set<TieredStoragePerQueryMetric> collectors;
        if (isQueryPhase) {
            collectors = taskIdToQueryPhaseCollectorMap.remove(parentTaskId + shardId);
        } else {
            collectors = taskIdToFetchPhaseCollectorMap.remove(parentTaskId + shardId);
        }
        // Slice Execution hooks will not be triggered in the case of a cache hit, however query phase hooks will always be triggered
        if (collectors == null) {
            return Collections.emptySet();
        }
        return collectors;
    }

    /**
     * Package private for testing
     * @return taskIdToCollectorMap
     */
    Map<String, Set<TieredStoragePerQueryMetric>> getTaskIdToCollectorMap(boolean isQueryPhase) {
        return isQueryPhase ? taskIdToQueryPhaseCollectorMap : taskIdToFetchPhaseCollectorMap;
    }

    /**
     * Package private for testing
     * @return metricCollectors
     */
    Map<Long, TieredStoragePerQueryMetric> getMetricCollectors() {
        return metricCollectors;
    }

    /**
     * Returns estimated memory consumption of tiered storage query metric collector
     * This helps with easy monitoring for tracking any memory leaks
     * @return ram bytes usage for this collector instance
     */
    public long ramBytesUsed() {
        long size = BASE_RAM_BYTES_USED;
        // While this is not completely accurate, it serves as good approximation for tracking any memory leaks
        // Each collector in metricCollectors will also be referenced in taskIdToCollectorMap, however the opposite is not true.
        // Therefore, we use taskIdToCollectorMap to estimate ram usage.
        for (Set<TieredStoragePerQueryMetric> collectors : taskIdToQueryPhaseCollectorMap.values()) {
            size += RamUsageEstimator.sizeOf(collectors.toArray(new TieredStoragePerQueryMetric[0]));
        }
        for (Set<TieredStoragePerQueryMetric> collectors : taskIdToFetchPhaseCollectorMap.values()) {
            size += RamUsageEstimator.sizeOf(collectors.toArray(new TieredStoragePerQueryMetric[0]));
        }
        return size;
    }

    public void recordStoredFieldsPrefetch(boolean success) {
        if (success) {
            prefetchStats.storedFieldsPrefetchSuccess.inc();
        } else {
            prefetchStats.storedFieldsPrefetchFailure.inc();
        }
    }

    public void recordDocValuesPrefetch(boolean success) {
        if (success) {
            prefetchStats.docValuesPrefetchSuccess.inc();
        } else {
            prefetchStats.docValuesPrefetchFailure.inc();
        }
    }

    // TODO has to emit as part of node stats
    public PrefetchStats getPrefetchStats() {
        return this.prefetchStats.getStats();
    }

    /**
     * Keeping this dummy metric collector helps keep code clean by preventing
     * unnecessary null checks
     */
    static class TieredStoragePerQueryMetricDummy implements TieredStoragePerQueryMetric {
        private static final long BASE_RAM_BYTES_USED = RamUsageEstimator.shallowSizeOfInstance
            (TieredStoragePerQueryMetricDummy.class);
        private static final TieredStoragePerQueryMetricDummy INSTANCE = new TieredStoragePerQueryMetricDummy();

        public static TieredStoragePerQueryMetricDummy getInstance() {
            return INSTANCE;
        }

        private TieredStoragePerQueryMetricDummy() {}

        @Override
        public void recordFileAccess(String blockFileName, boolean hit) {
            // Do nothing
        }

        @Override
        public void recordEndTime() { }

        @Override
        public void recordPrefetch(String fileName, int blockId) {}

        @Override
        public void recordReadAhead(String fileName, int blockId) {}

        @Override
        public String getParentTaskId() {
            return "DummyParentTaskId";
        }

        @Override
        public String getShardId() {
            return "DummyShardId";
        }

        @Override
        public long ramBytesUsed() {
            return BASE_RAM_BYTES_USED;
        }
    }

    public static final class PrefetchStatsHolder {
        final CounterMetric storedFieldsPrefetchSuccess = new CounterMetric();
        final CounterMetric storedFieldsPrefetchFailure = new CounterMetric();
        final CounterMetric docValuesPrefetchSuccess = new CounterMetric();
        final CounterMetric docValuesPrefetchFailure = new CounterMetric();


        PrefetchStats getStats() {
            return new PrefetchStats(
                storedFieldsPrefetchSuccess.count(), storedFieldsPrefetchFailure.count(), docValuesPrefetchSuccess.count(), docValuesPrefetchFailure.count()
            );
        }
    }
}
