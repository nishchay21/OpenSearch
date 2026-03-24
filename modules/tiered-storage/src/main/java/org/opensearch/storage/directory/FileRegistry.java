/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.storage.directory;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.storage.directory.FileRegistryEntry.Location;

import java.io.Closeable;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Thread-safe registry tracking per-file location (local/remote/both),
 * remote path mapping, and active read reference counts.
 * <p>
 * Used by cache strategies to decide where to read from and when
 * it's safe to delete local copies after sync to remote.
 * <p>
 * Lifecycle:
 * <ol>
 *   <li>{@link #registerLocal(String, long)} — called after write completes</li>
 *   <li>{@link #markSyncedToRemote(String, String)} — called after upload to remote</li>
 *   <li>{@link #acquireRead(String)} — called before opening a read</li>
 *   <li>{@link #releaseRead(String)} — called when read is closed</li>
 *   <li>{@link #tryDeleteLocal(String, Consumer)} — called to reclaim local space</li>
 * </ol>
 */
public class FileRegistry implements Closeable {

    private static final Logger logger = LogManager.getLogger(FileRegistry.class);

    private final ConcurrentHashMap<String, FileRegistryEntry> entries = new ConcurrentHashMap<>();

    /**
     * Register a file as written locally.
     */
    public FileRegistryEntry registerLocal(String localPath, long size) {
        FileRegistryEntry entry = entries.compute(localPath, (k, existing) -> {
            if (existing != null) {
                existing.upgradeLocation(Location.LOCAL);
                return existing;
            }
            return new FileRegistryEntry(localPath, null, Location.LOCAL, size);
        });
        logger.info("[FileRegistry] registerLocal: path={}, size={}, entry={}", localPath, size, entry);
        return entry;
    }

    /**
     * Mark a file as synced to remote. Sets the remote path and upgrades location.
     */
    public FileRegistryEntry markSyncedToRemote(String localPath, String remotePath) {
        FileRegistryEntry entry = entries.compute(localPath, (k, existing) -> {
            if (existing != null) {
                existing.setRemotePath(remotePath);
                existing.upgradeLocation(Location.REMOTE);
                return existing;
            }
            // File wasn't registered locally (e.g. parquet written by Rust JNI bypassing the directory).
            // Since we're syncing TO remote, the file must have been local — register as BOTH.
            return new FileRegistryEntry(localPath, remotePath, Location.BOTH, 0);
        });
        logger.info("[FileRegistry] markSyncedToRemote: local={}, remote={}, location={}", localPath, remotePath, entry.getLocation());
        return entry;
    }

    /**
     * Register a file known to exist on remote (e.g. discovered during init/recovery).
     */
    public FileRegistryEntry registerRemote(String localPath, String remotePath, long size) {
        FileRegistryEntry entry = entries.compute(localPath, (k, existing) -> {
            if (existing != null) {
                existing.setRemotePath(remotePath);
                existing.upgradeLocation(Location.REMOTE);
                return existing;
            }
            return new FileRegistryEntry(localPath, remotePath, Location.REMOTE, size);
        });
        logger.info("[FileRegistry] registerRemote: local={}, remote={}, size={}", localPath, remotePath, size);
        return entry;
    }

    /**
     * Acquire a read reference. Returns the entry for the caller to determine read source.
     * Returns null if file is not registered.
     */
    public FileRegistryEntry acquireRead(String localPath) {
        FileRegistryEntry entry = entries.get(localPath);
        if (entry != null) {
            int refs = entry.acquireRead();
            logger.debug("[FileRegistry] acquireRead: path={}, activeReads={}, location={}", localPath, refs, entry.getLocation());
        }
        return entry;
    }

    /**
     * Release a read reference. Returns remaining active reads.
     */
    public int releaseRead(String localPath) {
        FileRegistryEntry entry = entries.get(localPath);
        if (entry != null) {
            int remaining = entry.releaseRead();
            logger.debug("[FileRegistry] releaseRead: path={}, activeReads={}", localPath, remaining);
            return remaining;
        }
        return 0;
    }

    /**
     * Try to delete the local copy of a file. Only succeeds if:
     * - File is on remote (REMOTE or BOTH)
     * - No active reads
     * The deleteAction callback performs the actual local file deletion.
     */
    public boolean tryDeleteLocal(String localPath, Consumer<String> deleteAction) {
        FileRegistryEntry entry = entries.get(localPath);
        if (entry == null) {
            logger.debug("[FileRegistry] tryDeleteLocal: path={} — not registered", localPath);
            return false;
        }
        if (!entry.canDeleteLocal()) {
            logger.debug("[FileRegistry] tryDeleteLocal: path={} — cannot delete (location={}, activeReads={})",
                localPath, entry.getLocation(), entry.getActiveReads());
            return false;
        }
        // Safe to delete local copy
        try {
            deleteAction.accept(localPath);
            entry.setLocation(Location.REMOTE);
            logger.info("[FileRegistry] tryDeleteLocal: path={} — local copy deleted, now REMOTE only", localPath);
            return true;
        } catch (Exception e) {
            logger.warn("[FileRegistry] tryDeleteLocal: path={} — delete failed: {}", localPath, e.getMessage());
            return false;
        }
    }

    /**
     * Remove a file from the registry entirely (e.g. segment merge cleanup).
     */
    public FileRegistryEntry remove(String localPath) {
        FileRegistryEntry removed = entries.remove(localPath);
        if (removed != null) {
            logger.info("[FileRegistry] remove: path={}, was={}", localPath, removed);
        }
        return removed;
    }

    /**
     * Get the entry for a file, or null if not registered.
     */
    public FileRegistryEntry get(String localPath) {
        return entries.get(localPath);
    }

    /**
     * Check if a file is registered.
     */
    public boolean contains(String localPath) {
        return entries.containsKey(localPath);
    }

    /**
     * Get the remote path for a file, or null if not known.
     */
    public String getRemotePath(String localPath) {
        FileRegistryEntry entry = entries.get(localPath);
        return entry != null ? entry.getRemotePath() : null;
    }

    /**
     * Get the location of a file, or null if not registered.
     */
    public Location getLocation(String localPath) {
        FileRegistryEntry entry = entries.get(localPath);
        return entry != null ? entry.getLocation() : null;
    }

    public int size() {
        return entries.size();
    }

    /**
     * Log a summary of all tracked files.
     */
    public void logSummary() {
        int local = 0, remote = 0, both = 0;
        int totalActiveReads = 0;
        for (Map.Entry<String, FileRegistryEntry> e : entries.entrySet()) {
            switch (e.getValue().getLocation()) {
                case LOCAL: local++; break;
                case REMOTE: remote++; break;
                case BOTH: both++; break;
            }
            totalActiveReads += e.getValue().getActiveReads();
        }
        logger.info("[FileRegistry] summary: total={}, local={}, remote={}, both={}, activeReads={}",
            entries.size(), local, remote, both, totalActiveReads);
    }

    @Override
    public void close() throws IOException {
        logSummary();
        entries.clear();
    }
}
