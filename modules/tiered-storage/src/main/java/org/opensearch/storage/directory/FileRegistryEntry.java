/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.storage.directory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-file metadata tracked by {@link FileRegistry}.
 * Tracks where the file lives (local/remote/both), its remote path,
 * active read reference count, and total access count.
 */
public class FileRegistryEntry {

    public enum Location {
        LOCAL,
        REMOTE,
        BOTH;

        @Override
        public String toString() {
            return name();
        }
    }

    private final String localPath;
    private volatile String remotePath;
    private volatile Location location;
    private final AtomicInteger activeReads;
    private final AtomicLong totalReads;
    private final long size;

    public FileRegistryEntry(String localPath, String remotePath, Location location, long size) {
        this.localPath = localPath;
        this.remotePath = remotePath;
        this.location = location;
        this.activeReads = new AtomicInteger(0);
        this.totalReads = new AtomicLong(0);
        this.size = size;
    }

    public String getLocalPath() {
        return localPath;
    }

    public String getRemotePath() {
        return remotePath;
    }

    public void setRemotePath(String remotePath) {
        this.remotePath = remotePath;
    }

    public Location getLocation() {
        return location;
    }

    public void setLocation(Location location) {
        this.location = location;
    }

    /**
     * Upgrade location: LOCAL+REMOTE → BOTH, otherwise set directly.
     */
    public void upgradeLocation(Location from) {
        if (this.location == Location.LOCAL && from == Location.REMOTE) {
            this.location = Location.BOTH;
        } else if (this.location == Location.REMOTE && from == Location.LOCAL) {
            this.location = Location.BOTH;
        } else if (this.location != Location.BOTH) {
            this.location = from;
        }
    }

    public int acquireRead() {
        totalReads.incrementAndGet();
        return activeReads.incrementAndGet();
    }

    public int releaseRead() {
        return activeReads.decrementAndGet();
    }

    public int getActiveReads() {
        return activeReads.get();
    }

    public long getTotalReads() {
        return totalReads.get();
    }

    public long getSize() {
        return size;
    }

    public boolean isOnRemote() {
        return location == Location.REMOTE || location == Location.BOTH;
    }

    public boolean isOnLocal() {
        return location == Location.LOCAL || location == Location.BOTH;
    }

    public boolean canDeleteLocal() {
        return isOnRemote() && activeReads.get() == 0;
    }

    @Override
    public String toString() {
        return "FileRegistryEntry{" +
            "local=" + localPath +
            ", remote=" + remotePath +
            ", location=" + location +
            ", activeReads=" + activeReads.get() +
            ", totalReads=" + totalReads.get() +
            ", size=" + size +
            '}';
    }
}
