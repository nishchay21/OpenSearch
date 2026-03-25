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
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.index.IndexSettings;
import org.opensearch.index.shard.ShardPath;
import org.opensearch.index.engine.exec.FileMetadata;
import org.opensearch.index.store.CompositeRemoteSegmentStoreDirectory;
import org.opensearch.index.store.CompositeStoreDirectory;
import org.opensearch.index.store.FormatCacheStrategy;
import org.opensearch.index.store.FormatStoreDirectory;
import org.opensearch.index.store.RemoteSyncAware;
import org.opensearch.index.store.UploadedSegmentMetadata;
import org.opensearch.plugins.PluginsService;
import org.opensearch.storage.jni.TieredStoreNative;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Extends {@link CompositeStoreDirectory} to wrap every discovered
 * {@link FormatStoreDirectory} with a {@link CachedFormatStoreDirectory}.
 * <p>
 * Only created for optimized+warm indices (gated at the factory level in
 * {@link org.opensearch.index.IndexService}). Hot optimized indices use
 * plain {@link CompositeStoreDirectory} instead.
 * <p>
 * Holds a reference to the global Rust FileRegistry (shared across all shards).
 * The ObjectStore is global — created once by TieredStoragePlugin and registered
 * into DataFusion's runtime. This directory does NOT own or destroy the ObjectStore.
 */
public class TieredCompositeStoreDirectory extends CompositeStoreDirectory implements RemoteSyncAware {

    private static final Logger logger = LogManager.getLogger(TieredCompositeStoreDirectory.class);

    private final long registryPtr;
    private final String remoteBasePath;
    private final String repoKey;
    private final CompositeRemoteSegmentStoreDirectory remoteDirectory;
    private final String localDataPath;

    @SuppressWarnings("unchecked")
    public TieredCompositeStoreDirectory(
        IndexSettings indexSettings,
        PluginsService pluginsService,
        ShardId shardId,
        ShardPath shardPath,
        BiFunction<String, String, FormatCacheStrategy> cacheStrategyFactory,
        long registryPtr,
        String remoteBasePath,
        String repoKey,
        CompositeRemoteSegmentStoreDirectory remoteDirectory
    ) {
        super(indexSettings, pluginsService, shardId, shardPath, logger);

        this.registryPtr = registryPtr;
        this.remoteBasePath = remoteBasePath;
        this.repoKey = repoKey;
        this.remoteDirectory = remoteDirectory;
        this.localDataPath = shardPath.getDataPath().toString();

        // Register "lucene" as an alias for "metadata" so that FileMetadata's default
        // format ("lucene" for plain filenames like segments_N, write.lock) routes to
        // the same LuceneStoreDirectory that handles metadata files.
        FormatStoreDirectory<?> metadataDir = delegatesMap.get("metadata");
        if (metadataDir != null) {
            delegatesMap.put("lucene", metadataDir);
            // LuceneStoreDirectory.listAll() uses getDataFormat().toString() as the format
            // string, which returns the object identity (e.g. DataFormat$LuceneDataFormat@xxx).
            // Register that as an alias so getDirectoryForFormat() can resolve it.
            delegatesMap.put(metadataDir.getDataFormat().toString(), metadataDir);
        }

        // Wrap all delegates from delegatesMap with CachedFormatStoreDirectory.
        // Build both delegatesMap and delegates from a single pass so they stay in sync.
        Map<String, FormatStoreDirectory<?>> origMap = new HashMap<>(delegatesMap);
        delegatesMap.clear();
        delegates.clear();

        // Track already-wrapped originals so aliases (lucene→metadata) share the same cached instance
        Map<FormatStoreDirectory<?>, CachedFormatStoreDirectory<?>> wrappedCache = new HashMap<>();

        // The metadata directory (and its aliases: "lucene", toString) should only be in
        // delegatesMap for routing — NOT in delegates. The delegates list is used by
        // listFileMetadata() for scanning, and the metadata dir at <shard>/index/ contains
        // subdirectories (lucene/, parquet/) that FSDirectory.listAll() returns as entries,
        // causing spurious FileMetadata like ("LuceneDataFormat", "lucene").
        FormatStoreDirectory<?> rawMetadataDir = metadataDir; // captured before wrapping

        for (Map.Entry<String, FormatStoreDirectory<?>> entry : origMap.entrySet()) {
            String key = entry.getKey();
            FormatStoreDirectory<?> orig = entry.getValue();

            CachedFormatStoreDirectory<?> cached = wrappedCache.get(orig);
            if (cached == null) {
                String formatName = orig.getDataFormat().name();
                String dirPathPrefix = stripLeadingSlash(orig.getDirectoryPath().toString());
                FormatCacheStrategy strategy = cacheStrategyFactory.apply(formatName, dirPathPrefix);
                if (strategy instanceof PassthroughCacheStrategy) {
                    ((PassthroughCacheStrategy) strategy).setOwningDirectory(this);
                }
                cached = new CachedFormatStoreDirectory<>(orig, strategy);
                wrappedCache.put(orig, cached);
                // Only add non-metadata directories to delegates (used for listing/scanning)
                if (orig != rawMetadataDir) {
                    delegates.add(cached);
                }
            }
            delegatesMap.put(key, cached);
        }

        logger.info("[TieredCompositeStoreDirectory] created for shard={}, formats={}, registryFiles={}, " +
            "registry_ptr={}, remoteBasePath={}",
            shardId, delegatesMap.keySet(), TieredStoreNative.registryFileCount(registryPtr),
            registryPtr, remoteBasePath);
    }

    public long getRegistryPtr() {
        return registryPtr;
    }

    /**
     * Override listFileMetadata to include files from both local disk AND remote store.
     * The base implementation only scans local disk via delegates, which misses files
     * that are remote-only (e.g., parquet files on warm nodes that were never copied locally).
     * This is critical for peer recovery: the target's metadata must include all files
     * so the diff marks remote files as "identical" and avoids unnecessary transfer.
     */
    @Override
    public FileMetadata[] listFileMetadata() throws IOException {
        // Start with local files from disk (base implementation)
        Set<FileMetadata> allFiles = new HashSet<>(Arrays.asList(super.listFileMetadata()));

        // Add files known to remote store that may not be on local disk
        if (remoteDirectory != null) {
            Map<String, UploadedSegmentMetadata> uploaded = remoteDirectory.getSegmentsUploadedToRemoteStore();
            for (UploadedSegmentMetadata meta : uploaded.values()) {
                String fileName = meta.getOriginalFilename();
                String dataFormat = meta.getDataFormat();
                FileMetadata fm = new FileMetadata(dataFormat, fileName);
                allFiles.add(fm);
            }
        }

        return allFiles.toArray(new FileMetadata[0]);
    }

    public String getRemoteBasePath() {
        return remoteBasePath;
    }

    @Override
    public void afterSyncToRemote(String fileName, String remotePath) {
        FileMetadata fileMetadata = new FileMetadata(remotePath);
        String actualRemoteFilename = null;
        if (remoteDirectory != null) {
            actualRemoteFilename = remoteDirectory.getExistingRemoteFilename(fileMetadata);
        }

        if (actualRemoteFilename == null) {
            logger.warn("[TieredCompositeStoreDirectory] afterSyncToRemote: could not resolve remote filename " +
                "for file={}, remotePath={} — using raw remotePath as fallback", fileName, remotePath);
            actualRemoteFilename = remotePath;
        }

        String formatName = fileMetadata.dataFormat();
        String formatSubdir = formatName.toLowerCase();
        String fullRemotePath;
        if (remoteBasePath.endsWith("/")) {
            fullRemotePath = remoteBasePath + formatSubdir + "/" + actualRemoteFilename;
        } else {
            fullRemotePath = remoteBasePath + "/" + formatSubdir + "/" + actualRemoteFilename;
        }

        FormatStoreDirectory<?> formatDir = delegatesMap.get(formatName);
        String registryKey;
        if (formatDir != null) {
            String dirPath = stripLeadingSlash(formatDir.getDirectoryPath().toString());
            registryKey = dirPath + "/" + fileName;
        } else {
            registryKey = stripLeadingSlash(localDataPath) + "/" + formatName + "/" + fileName;
        }

        logger.info("[TieredCompositeStoreDirectory] afterSyncToRemote: file={}, registryKey={}, " +
            "resolvedRemoteFilename={}, fullRemotePath={}",
            fileName, registryKey, actualRemoteFilename, fullRemotePath);
        TieredStoreNative.registryMarkSyncedToRemote(registryPtr, registryKey, fullRemotePath, repoKey);

        // Local eviction: BOTH → REMOTE, then delete local if no active readers
        TieredStoreNative.registryMarkLocalDeleted(registryPtr, registryKey);

        long activeReads = TieredStoreNative.registryGetActiveReads(registryPtr, registryKey);
        if (activeReads == 0) {
            boolean deleted = deleteLocalFile(formatDir, formatName, fileName, registryKey);
            if (!deleted) {
                TieredStoreNative.registryAddPendingDelete(registryPtr, "/" + registryKey);
            }
        } else {
            TieredStoreNative.registryAddPendingDelete(registryPtr, "/" + registryKey);
            logger.info("[TieredCompositeStoreDirectory] afterSyncToRemote: deferring local delete, " +
                "activeReads={}, file={}, registryKey={}", activeReads, fileName, registryKey);
        }

        // Opportunistic sweep
        int pending = TieredStoreNative.registryPendingDeleteCount(registryPtr);
        if (pending > 0) {
            int swept = TieredStoreNative.registrySweepPendingDeletes(registryPtr);
            if (swept > 0) {
                logger.info("[TieredCompositeStoreDirectory] afterSyncToRemote sweep: deleted={}, remaining={}",
                    swept, TieredStoreNative.registryPendingDeleteCount(registryPtr));
            }
        }
    }

    @Override
    public void beforeSyncToRemote(Collection<FileMetadata> fileMetadataCollection) {
        for (FileMetadata fm : fileMetadataCollection) {
            String fileName = fm.file();
            String formatName = fm.dataFormat();

            FormatStoreDirectory<?> formatDir = delegatesMap.get(formatName);
            String registryKey;
            if (formatDir != null) {
                String dirPath = stripLeadingSlash(formatDir.getDirectoryPath().toString());
                registryKey = dirPath + "/" + fileName;
            } else {
                registryKey = stripLeadingSlash(localDataPath) + "/" + formatName + "/" + fileName;
            }

            int loc = TieredStoreNative.registryGetLocation(registryPtr, registryKey);
            if (loc == TieredStoreNative.LOCATION_NOT_FOUND) {
                long size = 0;
                try {
                    size = fileLength(fm);
                } catch (Exception e) {
                    logger.debug("[TieredCompositeStoreDirectory] could not get size for {}: {}", registryKey, e.getMessage());
                }
                TieredStoreNative.registryRegisterLocal(registryPtr, registryKey, size);
                logger.info("[TieredCompositeStoreDirectory] beforeSyncToRemote registered: registryKey={}, size={}", registryKey, size);
            }
        }
    }

    /**
     * Populate the Rust FileRegistry from remote metadata on recovery/restart.
     */
    public void populateRegistryFromRemoteMetadata() {
        if (remoteDirectory == null) {
            logger.info("[TieredCompositeStoreDirectory] no remote directory — skipping registry population");
            return;
        }

        Map<String, UploadedSegmentMetadata> uploaded = remoteDirectory.getSegmentsUploadedToRemoteStore();
        if (uploaded.isEmpty()) {
            logger.info("[TieredCompositeStoreDirectory] no segments in remote metadata — fresh index or no uploads yet");
            return;
        }

        int registered = 0;
        for (Map.Entry<String, UploadedSegmentMetadata> entry : uploaded.entrySet()) {
            UploadedSegmentMetadata meta = entry.getValue();
            String originalFile = meta.getOriginalFilename();
            String remoteFile = meta.getUploadedFilename();
            String dataFormat = meta.getDataFormat();
            long size = meta.getLength();

            FormatStoreDirectory<?> formatDir = delegatesMap.get(dataFormat);
            String registryKey;
            if (formatDir != null) {
                registryKey = stripLeadingSlash(formatDir.getDirectoryPath().toString()) + "/" + originalFile;
            } else {
                registryKey = stripLeadingSlash(localDataPath) + "/" + dataFormat + "/" + originalFile;
            }

            String formatSubdir = dataFormat.toLowerCase();
            String fullRemotePath;
            if (remoteBasePath.endsWith("/")) {
                fullRemotePath = remoteBasePath + formatSubdir + "/" + remoteFile;
            } else {
                fullRemotePath = remoteBasePath + "/" + formatSubdir + "/" + remoteFile;
            }

            // Check if the file actually exists locally — on warm nodes files may have been
            // evicted or never copied. Only register as LOCAL if the file is on disk.
            boolean localExists = java.nio.file.Files.exists(java.nio.file.Path.of("/" + registryKey));
            if (localExists) {
                TieredStoreNative.registryRegisterLocal(registryPtr, registryKey, size);
            }
            TieredStoreNative.registryMarkSyncedToRemote(registryPtr, registryKey, fullRemotePath, repoKey);
            registered++;

            logger.debug("[TieredCompositeStoreDirectory] recovery registered: registryKey={}, remotePath={}, format={}, size={}, localExists={}",
                registryKey, fullRemotePath, dataFormat, size, localExists);
        }

        logger.info("[TieredCompositeStoreDirectory] populated registry from remote metadata: {} files registered, remoteBasePath={}",
            registered, remoteBasePath);
    }

    @Override
    public void close() throws IOException {
        logger.info("[TieredCompositeStoreDirectory] closing shard directory, remoteBasePath={}", remoteBasePath);
        super.close();
    }

    /**
     * Try to delete a local file after the last reader releases its reference.
     * Called from PassthroughCacheStrategy's RefCountedIndexInput when
     * active_reads drops to 0 and the file is REMOTE-only.
     *
     * @param registryKey the registry key (path without leading "/")
     */
    public void tryDeleteLocalFileAfterLastRead(String registryKey) {
        int location = TieredStoreNative.registryGetLocation(registryPtr, registryKey);
        if (location != TieredStoreNative.LOCATION_REMOTE) {
            return;
        }
        long activeReads = TieredStoreNative.registryGetActiveReads(registryPtr, registryKey);
        if (activeReads > 0) {
            return;
        }

        java.nio.file.Path localPath = java.nio.file.Path.of("/" + registryKey);
        try {
            if (java.nio.file.Files.deleteIfExists(localPath)) {
                logger.info("[TieredCompositeStoreDirectory] deferred local delete succeeded: registryKey={}", registryKey);
            }
            TieredStoreNative.registryAddPendingDelete(registryPtr, "/" + registryKey);
        } catch (IOException e) {
            logger.warn("[TieredCompositeStoreDirectory] deferred local delete failed: registryKey={}, error={}",
                registryKey, e.getMessage());
        }
    }

    private boolean deleteLocalFile(FormatStoreDirectory<?> formatDir, String formatName, String fileName, String registryKey) {
        try {
            if (formatDir != null) {
                formatDir.deleteFile(fileName);
            } else {
                java.nio.file.Path localPath = java.nio.file.Path.of("/" + registryKey);
                java.nio.file.Files.deleteIfExists(localPath);
            }
            logger.info("[TieredCompositeStoreDirectory] local file deleted after sync: file={}, registryKey={}", fileName, registryKey);
            return true;
        } catch (IOException e) {
            logger.warn("[TieredCompositeStoreDirectory] failed to delete local file after sync: file={}, registryKey={}, error={}",
                fileName, registryKey, e.getMessage());
            return false;
        }
    }

    private static String stripLeadingSlash(String path) {
        return path.startsWith("/") ? path.substring(1) : path;
    }
}
