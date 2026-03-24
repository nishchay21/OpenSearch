/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.storage.slowlogs;

import org.apache.lucene.util.Accountable;
import org.apache.lucene.util.RamUsageEstimator;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.core.xcontent.ToXContent;
import org.opensearch.core.xcontent.ToXContentObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Implementation for collecting tiered storage metrics at per query level
 */
public class TieredStoragePerQueryMetricImpl implements TieredStoragePerQueryMetric, ToXContentObject {

    private static final long BASE_RAM_BYTES_USED = RamUsageEstimator.shallowSizeOfInstance(
        TieredStoragePerQueryMetricImpl.class);
    private static final long FC_BASE_RAM_BYTES_USED = RamUsageEstimator.shallowSizeOfInstance(FileCacheStat.class);
    private static final long PREFETCH_BASE_RAM_BYTES_USED =
        RamUsageEstimator.shallowSizeOfInstance(PrefetchStat.class);
    private static final long READ_AHEAD_BASE_RAM_BYTES_USED =
        RamUsageEstimator.shallowSizeOfInstance(ReadAheadStat.class);

    // File Cache stats will include hit/miss for both block and full file
    protected final Map<String, FileCacheStat> fileCacheStats;

    protected final Map<String, PrefetchStat> prefetchStats;
    protected final Map<String, Long> prefetchFiles;
    protected final Map<String, ReadAheadStat> readAheadStats;
    protected final Map<String, Long> readAheadFiles;

    protected long effectiveBytes;
    protected long hits;
    protected long miss;
    private final String parentTaskId;
    private final String shardId;
    private static final long BYTES_IN_MB = 1024 * 1024;
    private final long startTime;
    private long endTime;

    public TieredStoragePerQueryMetricImpl(String parentTaskId, String shardId) {
        this.parentTaskId = parentTaskId;
        this.shardId = shardId;
        this.fileCacheStats = new HashMap<>();
        this.prefetchStats = new HashMap<>();
        this.prefetchFiles = new HashMap<>();
        this.readAheadStats = new HashMap<>();
        this.readAheadFiles = new HashMap<>();
        this.effectiveBytes = 0L;
        this.hits = 0L;
        this.miss = 0L;
        this.startTime = System.currentTimeMillis();
        this.endTime = 0L;
    }

    private FileBlock getFileBlock(String blockFileName) {
        String[] fileParts = blockFileName.split("[.]", -1);
        String fileName = fileParts[0];
        String [] blocks = fileParts[1].split("_", -1);
        fileName = fileName + blocks[0];
        if (fileParts.length == 2 && blocks.length == 3) {
            // ignore the 4th part which is the block extension
            return new FileBlock(fileName, Integer.parseInt(blocks[2]));
        } else {
            assert false : "getFileBlock called with invalid block name, possibly without the extension";
            return new FileBlock(blockFileName, -1);
        }
    }

    @Override
    public void recordFileAccess(String blockFileName, boolean hit) {
        final FileBlock fileBlock = getFileBlock(blockFileName);
        FileCacheStat fileCacheStat = this.fileCacheStats.get(fileBlock.fileName);
        if (fileCacheStat == null) {
            fileCacheStat = new FileCacheStat();
            this.fileCacheStats.put(fileBlock.fileName, fileCacheStat);
        }
        if (hit) {
            fileCacheStat.hits++;
            this.hits++;
            fileCacheStat.hitBlocks.add(fileBlock.blockId);
        } else {
            fileCacheStat.miss++;
            this.miss++;
            fileCacheStat.missBlocks.add(fileBlock.blockId);
        }
    }

    @Override
    public void recordPrefetch(String fileName, int blockId) {
        if (!this.prefetchFiles.containsKey(fileName)) {
            this.prefetchFiles.put(fileName, System.currentTimeMillis());
            this.prefetchStats.put(fileName, new PrefetchStat());
        }
        this.prefetchStats.get(fileName).prefetchBlocks.add(blockId);
    }

    @Override
    public void recordReadAhead(String fileName, int blockId) {
        if (!this.readAheadFiles.containsKey(fileName)) {
            this.readAheadFiles.put(fileName, System.currentTimeMillis());
            this.readAheadStats.put(fileName, new ReadAheadStat());
        }
        this.readAheadStats.get(fileName).readAheadBlocks.add(blockId);
    }

    @Override
    public long ramBytesUsed() {
        long size = BASE_RAM_BYTES_USED;
        // While this is not completely accurate, it serves as
        // good approximation for tracking any memory leaks
        size += RamUsageEstimator.sizeOf(fileCacheStats.values().toArray(new FileCacheStat[0]));
        return size;
    }

    @Override
    public void recordEndTime() {
        this.endTime = System.currentTimeMillis();
    }


    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.field("parentTask", parentTaskId);
        builder.field("shardId", shardId);

        // Summary section
        builder.startObject("summary");
        builder.field("fileCache", String.format("%d hits out of %d total", this.hits, this.hits + this.miss));
        builder.field("prefetchFiles", this.prefetchFiles);
        builder.field("readAheadFiles", this.readAheadFiles);
        builder.endObject();

        // Details section
        builder.startObject("details");

        // File cache details
        builder.startObject("fileCache");
        for (Map.Entry<String, FileCacheStat> entry : this.fileCacheStats.entrySet()) {
            builder.startObject(entry.getKey());
            entry.getValue().toXContent(builder, params);
            builder.endObject();
        }
        builder.endObject();

        // Prefetch details
        builder.startObject("prefetch");
        for (Map.Entry<String, PrefetchStat> entry : this.prefetchStats.entrySet()) {
            builder.startObject(entry.getKey());
            entry.getValue().toXContent(builder, params);
            builder.endObject();
        }
        builder.endObject();

        // ReadAhead details
        builder.startObject("readAhead");
        for (Map.Entry<String, ReadAheadStat> entry : this.readAheadStats.entrySet()) {
            builder.startObject(entry.getKey());
            entry.getValue().toXContent(builder, params);
            builder.endObject();
        }
        builder.endObject();

        builder.endObject(); // end details

        // Timestamps section
        builder.startObject("timestamps");
        builder.field("startTime", this.startTime);
        builder.field("endTime", this.endTime);
        builder.endObject();

        builder.endObject();
        return builder;
    }

    @Override
    public String toString() {
        try {
            XContentBuilder builder = XContentFactory.jsonBuilder();
            toXContent(builder, ToXContent.EMPTY_PARAMS);
            return builder.toString();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String getParentTaskId() {
        return parentTaskId;
    }

    @Override
    public String getShardId() {
        return shardId;
    }

    private long getSetSize(Set<Integer> set) {
        // While this is not completely accurate, it serves as
        // good approximation for tracking any memory leaks
        long size = RamUsageEstimator.shallowSizeOf(set);
        size += set.size() * RamUsageEstimator.NUM_BYTES_OBJECT_REF;
        size += set.size() * Integer.BYTES;
        return size;
    }

    private class FileBlock {
        final String fileName;
        final int blockId;
        public FileBlock(String fileName, int blockId) {
            this.fileName = fileName;
            this.blockId = blockId;
        }
    }

    protected class FileCacheStat implements Accountable, ToXContent {
        public long hits;
        public long miss;
        public Set<Integer> hitBlocks;
        public Set<Integer> missBlocks;

        public FileCacheStat() {
            this.hits = 0L;
            this.miss = 0L;
            this.hitBlocks = new HashSet<>();
            this.missBlocks = new HashSet<>();
        }

        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.field("hits", this.hits);
            builder.field("miss", this.miss);
            builder.field("total", this.hits + this.miss);

            if (!hitBlocks.isEmpty() || !missBlocks.isEmpty()) {
                builder.startObject("blockDetails");
                builder.field("hitBlockCount", this.hitBlocks.size());
                builder.field("hitBlocks", this.hitBlocks);
                builder.field("missBlockCount", this.missBlocks.size());
                builder.field("missBlocks", this.missBlocks);
                builder.endObject();
            }

            return builder;
        }

        public String toString() {
            // Full file case
            if (hitBlocks.isEmpty() && missBlocks.isEmpty()) {
                return String.format("%d hits out of %d total", this.hits, this.hits + this.miss);
            } else {
                return String.format("%d hits out of %d total, %d distinct hit blocks - %s, %d distinct miss blocks - %s",
                    this.hits, this.hits +
                        this.miss, this.hitBlocks.size(), this.hitBlocks, this.missBlocks.size(), this.missBlocks);
            }
        }

        @Override
        public long ramBytesUsed() {
            long size = FC_BASE_RAM_BYTES_USED;
            size += getSetSize(hitBlocks);
            size += getSetSize(missBlocks);
            return size;
        }
    }

    protected class ReadAheadStat implements Accountable, ToXContent {
        public Set<Integer> readAheadBlocks;

        public ReadAheadStat() {
            this.readAheadBlocks = new HashSet<>();
        }

        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.field("blockCount", this.readAheadBlocks.size());
            builder.field("blocks", this.readAheadBlocks);
            return builder;
        }

        public String toString() {
            return String.format("%d distinct submitted blocks - %s,", this.readAheadBlocks.size(), this.readAheadBlocks);
        }

        @Override
        public long ramBytesUsed() {
            long size = READ_AHEAD_BASE_RAM_BYTES_USED;
            size += getSetSize(readAheadBlocks);
            return size;
        }
    }

    protected class PrefetchStat implements Accountable, ToXContent {
        public Set<Integer> prefetchBlocks;

        public PrefetchStat() {
            this.prefetchBlocks = new HashSet<>();
        }

        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.field("blockCount", this.prefetchBlocks.size());
            builder.field("blocks", this.prefetchBlocks);
            return builder;
        }

        public String toString() {
            return String.format("%d distinct submitted blocks - %s", this.prefetchBlocks.size(), this.prefetchBlocks);
        }

        @Override
        public long ramBytesUsed() {
            long size = PREFETCH_BASE_RAM_BYTES_USED;
            size += getSetSize(prefetchBlocks);
            return size;
        }
    }
}
