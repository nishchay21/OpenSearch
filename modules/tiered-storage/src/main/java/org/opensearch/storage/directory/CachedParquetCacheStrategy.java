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
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.opensearch.index.engine.exec.FileMetadata;
import org.opensearch.index.store.CompositeRemoteSegmentStoreDirectory;
import org.opensearch.index.store.FormatCacheStrategy;
import org.opensearch.index.store.FormatStoreDirectory;
import org.opensearch.storage.jni.TieredStoreNative;
import org.opensearch.vectorized.execution.jni.PageCacheProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.util.zip.CRC32;

/**
 * Foyer-backed cache strategy for the Parquet format on warm indices.
 * Replaces {@link PassthroughCacheStrategy} for the {@code "parquet"} format name.
 * The key difference is in {@link #openInput}: for REMOTE files, instead of opening a full
 * streaming {@link IndexInput} from {@link CompositeRemoteSegmentStoreDirectory} (which
 * re-fetches the entire file from S3/GCS/Azure on every call), it returns a
 * {@link CachedParquetIndexInput} that serves byte ranges from Foyer (cache miss fetches from
 * remote and populates Foyer). All other operations (FileRegistry routing, ref-counting,
 * file registration, deletion, checksums) are identical to {@link PassthroughCacheStrategy}.
 * For Lucene format files, {@link PassthroughCacheStrategy} is still used unchanged.
 */
public class CachedParquetCacheStrategy implements FormatCacheStrategy {

    private static final Logger logger = LogManager.getLogger(CachedParquetCacheStrategy.class);

    private final String formatName;
    private final CompositeRemoteSegmentStoreDirectory remoteDirectory;
    private final long registryPtr;
    private final String dirPathPrefix;
    /** Page cache — provided by DataFusionPlugin via the PageCacheProvider interface */
    private final PageCacheProvider foyerCache;
    /** Reference to the owning directory for deferred local file deletion */
    private volatile TieredCompositeStoreDirectory owningDirectory;

    public CachedParquetCacheStrategy(
        String formatName,
        CompositeRemoteSegmentStoreDirectory remoteDirectory,
        long registryPtr,
        String dirPathPrefix,
        PageCacheProvider foyerCache
    ) {
        this.formatName = formatName;
        this.remoteDirectory = remoteDirectory;
        this.registryPtr = registryPtr;
        this.dirPathPrefix = dirPathPrefix;
        this.foyerCache = foyerCache;
    }

    /**
     * Set the owning directory reference. Called after construction by
     * {@link TieredCompositeStoreDirectory} so that deferred local file
     * deletion can be triggered when the last reader closes.
     */
    public void setOwningDirectory(TieredCompositeStoreDirectory directory) {
        this.owningDirectory = directory;
    }

    /** Build the full registry key for a file (matches DataFusion/object_store key format). */
    private String registryKey(String fileName) {
        return dirPathPrefix + "/" + fileName;
    }

    /**
     * Build the format-qualified remote file name.
     * {@link CompositeRemoteSegmentStoreDirectory#openInput} uses the FileMetadata
     * delimiter to route to the correct format container.
     */
    private String remoteFileName(String fileName) {
        if (formatName == null || formatName.isEmpty() || "lucene".equals(formatName)) {
            return fileName;
        }
        return fileName + FileMetadata.DELIMITER + formatName;
    }

    @Override
    public String name() {
        return "foyer-parquet(" + formatName + ")";
    }

    /**
     * Opens an IndexInput for reading a Parquet file.
     * LOCAL files are served from disk; REMOTE files return a {@link CachedParquetIndexInput}
     * that serves byte ranges from Foyer (cache miss fetches from remote and populates cache).
     * FileRegistry ref-counting is maintained in both paths (same as PassthroughCacheStrategy).
     */
    @Override
    public IndexInput openInput(String fileName, IOContext context, FormatStoreDirectory<?> delegate)
        throws IOException {

        String key = registryKey(fileName);
        // Acquire read reference in the FileRegistry — prevents local eviction while reading
        int location = TieredStoreNative.registryAcquireRead(registryPtr, key);

        // --- LOCAL or UNREGISTERED: read directly from disk ---
        if (location == TieredStoreNative.LOCATION_LOCAL
            || location == TieredStoreNative.LOCATION_NOT_FOUND) {
            logger.debug("[CachedParquetCacheStrategy] openInput LOCAL: format={}, file={}, key={}, loc={}",
                formatName, fileName, key, locationName(location));
            try {
                IndexInput localInput = delegate.openIndexInput(fileName, context);
                // Wrap in RefCountedIndexInput for safe eviction (same as PassthroughCacheStrategy)
                return new PassthroughCacheStrategy.RefCountedIndexInputPublic(
                    localInput, key, registryPtr, owningDirectory
                );
            } catch (IOException e) {
                TieredStoreNative.registryReleaseRead(registryPtr, key);
                throw e;
            }
        }

        // --- REMOTE or BOTH: serve via Foyer page cache ---
        if (remoteDirectory != null) {
            logger.debug("[CachedParquetCacheStrategy] openInput REMOTE (Foyer): format={}, file={}, key={}",
                formatName, fileName, key);
            try {
                // Resolve file length: try registry first (O(1)), fall back to remote metadata
                long fileLen = TieredStoreNative.registryGetSize(registryPtr, key);
                if (fileLen < 0) {
                    fileLen = remoteDirectory.fileLength(remoteFileName(fileName));
                }

                return new CachedParquetIndexInput(
                    "CachedParquet(" + fileName + ")",
                    remoteFileName(fileName),
                    key,
                    fileLen,
                    foyerCache,
                    remoteDirectory,
                    registryPtr,
                    owningDirectory
                );
            } catch (IOException e) {
                // Remote failed — fall back to local
                logger.warn("[CachedParquetCacheStrategy] remote open failed for {}, trying local: {}",
                    fileName, e.getMessage());
                try {
                    IndexInput localInput = delegate.openIndexInput(fileName, context);
                    return new PassthroughCacheStrategy.RefCountedIndexInputPublic(
                        localInput, key, registryPtr, owningDirectory
                    );
                } catch (IOException localEx) {
                    TieredStoreNative.registryReleaseRead(registryPtr, key);
                    throw e;
                }
            }
        }

        // No remote directory — fall back to local
        logger.debug("[CachedParquetCacheStrategy] openInput LOCAL fallback (no remote dir): format={}, file={}",
            formatName, fileName);
        try {
            IndexInput localInput = delegate.openIndexInput(fileName, context);
            return new PassthroughCacheStrategy.RefCountedIndexInputPublic(
                localInput, key, registryPtr, owningDirectory
            );
        } catch (IOException e) {
            TieredStoreNative.registryReleaseRead(registryPtr, key);
            throw e;
        }
    }

    @Override
    public void onFileWritten(String fileName, FormatStoreDirectory<?> delegate) throws IOException {
        String key = registryKey(fileName);
        long size = 0;
        try {
            size = delegate.fileLength(fileName);
        } catch (IOException e) {
            logger.warn("[CachedParquetCacheStrategy] could not get size for {}: {}", fileName, e.getMessage());
        }
        TieredStoreNative.registryRegisterLocal(registryPtr, key, size);
        logger.debug("[CachedParquetCacheStrategy] onFileWritten: format={}, file={}, key={}, size={}",
            formatName, fileName, key, size);
    }

    @Override
    public void onFileDeleted(String fileName) throws IOException {
        String key = registryKey(fileName);
        int location = TieredStoreNative.registryGetLocation(registryPtr, key);
        if (location == TieredStoreNative.LOCATION_BOTH) {
            TieredStoreNative.registryMarkLocalDeleted(registryPtr, key);
            logger.debug("[CachedParquetCacheStrategy] onFileDeleted (mark local deleted): key={}", key);
        } else if (location == TieredStoreNative.LOCATION_REMOTE) {
            logger.debug("[CachedParquetCacheStrategy] onFileDeleted (already remote-only): key={}", key);
        } else {
            TieredStoreNative.registryRemove(registryPtr, key);
            logger.debug("[CachedParquetCacheStrategy] onFileDeleted (removed from registry): key={}", key);
        }
        // Also evict from Foyer page cache — stale bytes must not be served
        foyerCache.evictFile(key);
    }

    @Override
    public long fileLength(String fileName, FormatStoreDirectory<?> delegate) throws IOException {
        String key = registryKey(fileName);
        long size = TieredStoreNative.registryGetSize(registryPtr, key);
        if (size >= 0) return size;

        try {
            return delegate.fileLength(fileName);
        } catch (IOException e) {
            // fall through to remote
        }

        if (remoteDirectory != null) {
            return remoteDirectory.fileLength(remoteFileName(fileName));
        }
        throw new IOException("fileLength failed for " + fileName + " — not in registry, local, or remote");
    }

    @Override
    public long calculateChecksum(String fileName, FormatStoreDirectory<?> delegate) throws IOException {
        String key = registryKey(fileName);
        int location = TieredStoreNative.registryGetLocation(registryPtr, key);

        // For LOCAL or BOTH: try local first ONLY if the file actually exists on disk.
        // On a warm node receiving shards via peer recovery, the registry may report BOTH
        // (populated from remote metadata) but the file is not yet present locally —
        // in that case, calling delegate.calculateChecksum() would log a misleading ERROR.
        if (location == TieredStoreNative.LOCATION_LOCAL || location == TieredStoreNative.LOCATION_BOTH) {
            if (Files.exists(delegate.getDirectoryPath().resolve(fileName))) {
                try { return delegate.calculateChecksum(fileName); } catch (IOException ignored) {}
            } else {
                logger.debug("[CachedParquetCacheStrategy] calculateChecksum: skipping local (file not on disk): key={}, loc={}",
                    key, locationName(location));
            }
        }
        // REMOTE, BOTH-with-local-failure/missing, or NOT_FOUND: try remote
        if (remoteDirectory != null) {
            try (IndexInput input = remoteDirectory.openInput(remoteFileName(fileName), IOContext.READONCE)) {
                return computeCrc32(input);
            }
        }
        // Last resort: try local (handles NOT_FOUND case where file may still exist)
        return delegate.calculateChecksum(fileName);
    }

    @Override
    public String calculateUploadChecksum(String fileName, FormatStoreDirectory<?> delegate)
        throws IOException {
        String key = registryKey(fileName);
        int location = TieredStoreNative.registryGetLocation(registryPtr, key);

        // For LOCAL or BOTH: try local first ONLY if the file actually exists on disk.
        if (location == TieredStoreNative.LOCATION_LOCAL || location == TieredStoreNative.LOCATION_BOTH) {
            if (Files.exists(delegate.getDirectoryPath().resolve(fileName))) {
                try { return delegate.calculateUploadChecksum(fileName); } catch (IOException ignored) {}
            } else {
                logger.debug("[CachedParquetCacheStrategy] calculateUploadChecksum: skipping local (file not on disk): key={}, loc={}",
                    key, locationName(location));
            }
        }
        // REMOTE, BOTH-with-local-failure/missing, or NOT_FOUND: try remote
        if (remoteDirectory != null) {
            try (IndexInput input = remoteDirectory.openInput(remoteFileName(fileName), IOContext.READONCE)) {
                return Long.toString(computeCrc32(input));
            }
        }
        // Last resort: try local
        return delegate.calculateUploadChecksum(fileName);
    }

    @Override
    public void close() throws IOException {
        // no-op — registry is owned by TieredCompositeStoreDirectory
    }

    private static long computeCrc32(IndexInput input) throws IOException {
        CRC32 crc32 = new CRC32();
        byte[] buffer = new byte[8192];
        long remaining = input.length();
        while (remaining > 0) {
            int toRead = (int) Math.min(buffer.length, remaining);
            input.readBytes(buffer, 0, toRead);
            crc32.update(buffer, 0, toRead);
            remaining -= toRead;
        }
        return crc32.getValue();
    }

    private static String locationName(int loc) {
        switch (loc) {
            case TieredStoreNative.LOCATION_LOCAL:  return "LOCAL";
            case TieredStoreNative.LOCATION_REMOTE: return "REMOTE";
            case TieredStoreNative.LOCATION_BOTH:   return "BOTH";
            default:                                 return "UNREGISTERED";
        }
    }
}
