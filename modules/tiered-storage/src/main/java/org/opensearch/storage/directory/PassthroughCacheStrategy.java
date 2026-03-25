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

import java.io.IOException;
import java.util.zip.CRC32;

/**
 * Passthrough cache strategy — reads from remote directory, tracks files in the
 * single Rust {@code FileRegistry} via JNI.
 * <p>
 * For the POC, reads go through the {@link CompositeRemoteSegmentStoreDirectory}
 * when the file is on remote, or from the local delegate when the file is local-only
 * (e.g. during upload before the file reaches remote).
 * <p>
 * In production, this would be replaced by format-specific strategies (FileCache for Lucene,
 * foyer for Parquet) that use the same Rust registry for lifecycle management.
 */
public class PassthroughCacheStrategy implements FormatCacheStrategy {

    private static final Logger logger = LogManager.getLogger(PassthroughCacheStrategy.class);

    private final String formatName;
    private final CompositeRemoteSegmentStoreDirectory remoteDirectory;
    private final long registryPtr;
    private final String dirPathPrefix;
    /** Reference to the owning directory for deferred local file deletion. */
    private volatile TieredCompositeStoreDirectory owningDirectory;

    public PassthroughCacheStrategy(
        String formatName,
        CompositeRemoteSegmentStoreDirectory remoteDirectory,
        long registryPtr,
        String dirPathPrefix
    ) {
        this.formatName = formatName;
        this.remoteDirectory = remoteDirectory;
        this.registryPtr = registryPtr;
        this.dirPathPrefix = dirPathPrefix;
    }

    /**
     * Set the owning directory reference. Called after construction by
     * {@link TieredCompositeStoreDirectory} so that deferred local file
     * deletion can be triggered when the last reader closes.
     */
    public void setOwningDirectory(TieredCompositeStoreDirectory directory) {
        this.owningDirectory = directory;
    }

    /**
     * Build the full registry key for a file — matches what DataFusion/object_store uses.
     * e.g. "Users/nishcha/.../parquet/_parquet_file_generation_0.parquet"
     */
    private String registryKey(String fileName) {
        return dirPathPrefix + "/" + fileName;
    }

    /**
     * Build a format-qualified filename for the remote directory.
     * {@link CompositeRemoteSegmentStoreDirectory#openInput(String, IOContext)} creates
     * {@code new FileMetadata(name)} which defaults to "lucene" when there is no
     * {@code ":::"} delimiter. For non-lucene formats (e.g. parquet) we must include
     * the format so the remote directory looks in the correct container.
     */
    private String remoteFileName(String fileName) {
        if (formatName == null || formatName.isEmpty() || "lucene".equals(formatName)) {
            return fileName;
        }
        return fileName + FileMetadata.DELIMITER + formatName;
    }

    @Override
    public IndexInput openInput(String fileName, IOContext context, FormatStoreDirectory<?> delegate) throws IOException {
        String key = registryKey(fileName);
        // Acquire read reference in the single Rust FileRegistry
        int location = TieredStoreNative.registryAcquireRead(registryPtr, key);

        // If file is LOCAL only or not yet registered (just written, not yet uploaded),
        // read from local delegate. This happens during the upload path.
        if (location == TieredStoreNative.LOCATION_LOCAL || location == TieredStoreNative.LOCATION_NOT_FOUND) {
            logger.debug("[PassthroughCacheStrategy] openInput READ_FROM_LOCAL: format={}, file={}, registryKey={}, location={}",
                formatName, fileName, key, locationName(location));
            try {
                IndexInput localInput = delegate.openIndexInput(fileName, context);
                return new RefCountedIndexInput(localInput, key, registryPtr, owningDirectory);
            } catch (IOException e) {
                TieredStoreNative.registryReleaseRead(registryPtr, key);
                throw e;
            }
        }

        // File is on remote (REMOTE or BOTH) — read from remote
        if (remoteDirectory != null) {
            logger.debug("[PassthroughCacheStrategy] openInput READ_FROM_REMOTE: format={}, file={}, registryKey={}, location={}",
                formatName, fileName, key, locationName(location));
            try {
                IndexInput remoteInput = remoteDirectory.openInput(remoteFileName(fileName), context);
                return new RefCountedIndexInput(remoteInput, key, registryPtr, owningDirectory);
            } catch (IOException e) {
                // Remote read failed — try local fallback
                logger.warn("[PassthroughCacheStrategy] REMOTE_READ_FAILED, trying local: format={}, file={}, error={}",
                    formatName, fileName, e.getMessage());
                try {
                    IndexInput localInput = delegate.openIndexInput(fileName, context);
                    return new RefCountedIndexInput(localInput, key, registryPtr, owningDirectory);
                } catch (IOException localEx) {
                    TieredStoreNative.registryReleaseRead(registryPtr, key);
                    throw e;
                }
            }
        }

        // No remote directory — read from local
        logger.debug("[PassthroughCacheStrategy] openInput READ_FROM_LOCAL (no remote dir): format={}, file={}, registryKey={}",
            formatName, fileName, key);
        try {
            IndexInput localInput = delegate.openIndexInput(fileName, context);
            return new RefCountedIndexInput(localInput, key, registryPtr, owningDirectory);
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
            logger.warn("[PassthroughCacheStrategy] could not get file length for {}, registering with size 0: {}", fileName, e.getMessage());
        }
        TieredStoreNative.registryRegisterLocal(registryPtr, key, size);
        logger.debug("[PassthroughCacheStrategy] onFileWritten: format={}, file={}, registryKey={}, size={}", formatName, fileName, key, size);
    }

    @Override
    public void onFileDeleted(String fileName) throws IOException {
        String key = registryKey(fileName);
        int location = TieredStoreNative.registryGetLocation(registryPtr, key);
        if (location == TieredStoreNative.LOCATION_BOTH) {
            // File is on remote too — keep the registry entry, just mark local gone
            TieredStoreNative.registryMarkLocalDeleted(registryPtr, key);
            logger.debug("[PassthroughCacheStrategy] onFileDeleted (mark local deleted): format={}, file={}, registryKey={}", formatName, fileName, key);
        } else if (location == TieredStoreNative.LOCATION_REMOTE) {
            // Already REMOTE-only (local was evicted after sync) — nothing to do
            logger.debug("[PassthroughCacheStrategy] onFileDeleted (already remote-only, no-op): format={}, file={}, registryKey={}", formatName, fileName, key);
        } else {
            // File is local-only or not found — remove entirely
            TieredStoreNative.registryRemove(registryPtr, key);
            logger.debug("[PassthroughCacheStrategy] onFileDeleted (removed): format={}, file={}, registryKey={}", formatName, fileName, key);
        }
    }

    @Override
    public String name() {
        return "passthrough(" + formatName + ")";
    }

    @Override
    public long fileLength(String fileName, FormatStoreDirectory<?> delegate) throws IOException {
        // 1. Check registry first
        String key = registryKey(fileName);
        long size = TieredStoreNative.registryGetSize(registryPtr, key);
        if (size >= 0) {
            logger.debug("[PassthroughCacheStrategy] fileLength from registry: format={}, file={}, size={}", formatName, fileName, size);
            return size;
        }

        // 2. Try local
        try {
            long localSize = delegate.fileLength(fileName);
            logger.debug("[PassthroughCacheStrategy] fileLength from local: format={}, file={}, size={}", formatName, fileName, localSize);
            return localSize;
        } catch (IOException e) {
            // Local failed — try remote
        }

        // 3. Fall back to remote
        if (remoteDirectory != null) {
            try {
                long remoteSize = remoteDirectory.fileLength(remoteFileName(fileName));
                logger.info("[PassthroughCacheStrategy] fileLength from remote: format={}, file={}, size={}", formatName, fileName, remoteSize);
                return remoteSize;
            } catch (IOException e) {
                throw new IOException("fileLength failed for " + fileName + " — not in registry, local, or remote", e);
            }
        }

        throw new IOException("fileLength failed for " + fileName + " — not in registry or local, no remote directory");
    }

    @Override
    public long calculateChecksum(String fileName, FormatStoreDirectory<?> delegate) throws IOException {
        String key = registryKey(fileName);
        int location = TieredStoreNative.registryGetLocation(registryPtr, key);

        // LOCAL or BOTH — try local first (faster), fall back to remote if file missing/unreadable
        if (location == TieredStoreNative.LOCATION_LOCAL || location == TieredStoreNative.LOCATION_BOTH) {
            try {
                return delegate.calculateChecksum(fileName);
            } catch (IOException e) {
                logger.debug("[PassthroughCacheStrategy] calculateChecksum local failed (location={}), trying remote: format={}, file={}",
                    locationName(location), formatName, fileName);
            }
        }

        // REMOTE, BOTH-with-local-failure, or NOT_FOUND — try remote
        if (remoteDirectory != null) {
            try (IndexInput input = remoteDirectory.openInput(remoteFileName(fileName), IOContext.READONCE)) {
                long checksum = computeCrc32(input);
                logger.debug("[PassthroughCacheStrategy] calculateChecksum from remote: format={}, file={}, location={}", formatName, fileName, locationName(location));
                return checksum;
            } catch (IOException e) {
                // For NOT_FOUND: remote also failed — try local as last resort (file may exist but not yet registered)
                if (location == TieredStoreNative.LOCATION_NOT_FOUND) {
                    logger.debug("[PassthroughCacheStrategy] calculateChecksum remote failed for NOT_FOUND, trying local: format={}, file={}", formatName, fileName);
                    try {
                        return delegate.calculateChecksum(fileName);
                    } catch (IOException localEx) {
                        throw new IOException("calculateChecksum failed for " + fileName + " — not found in registry, remote, or local", e);
                    }
                }
                throw new IOException("calculateChecksum failed for " + fileName + " — remote read failed (location=" + locationName(location) + ")", e);
            }
        }

        // No remote directory — try local as fallback (NOT_FOUND or REMOTE without remote dir)
        if (location == TieredStoreNative.LOCATION_NOT_FOUND) {
            try {
                return delegate.calculateChecksum(fileName);
            } catch (IOException e) {
                throw new IOException("calculateChecksum failed for " + fileName + " — not in registry or local, no remote directory", e);
            }
        }

        throw new IOException("calculateChecksum failed for " + fileName + " — location=" + locationName(location) + ", no remote directory");
    }

    @Override
    public String calculateUploadChecksum(String fileName, FormatStoreDirectory<?> delegate) throws IOException {
        String key = registryKey(fileName);
        int location = TieredStoreNative.registryGetLocation(registryPtr, key);

        // LOCAL or BOTH — try local first (faster), fall back to remote if file missing/unreadable
        if (location == TieredStoreNative.LOCATION_LOCAL || location == TieredStoreNative.LOCATION_BOTH) {
            try {
                return delegate.calculateUploadChecksum(fileName);
            } catch (IOException e) {
                logger.debug("[PassthroughCacheStrategy] calculateUploadChecksum local failed (location={}), trying remote: format={}, file={}",
                    locationName(location), formatName, fileName);
            }
        }

        // REMOTE, BOTH-with-local-failure, or NOT_FOUND — try remote
        if (remoteDirectory != null) {
            try (IndexInput input = remoteDirectory.openInput(remoteFileName(fileName), IOContext.READONCE)) {
                long checksum = computeCrc32(input);
                logger.debug("[PassthroughCacheStrategy] calculateUploadChecksum from remote: format={}, file={}, location={}", formatName, fileName, locationName(location));
                return Long.toString(checksum);
            } catch (IOException e) {
                // For NOT_FOUND: remote also failed — try local as last resort
                if (location == TieredStoreNative.LOCATION_NOT_FOUND) {
                    logger.debug("[PassthroughCacheStrategy] calculateUploadChecksum remote failed for NOT_FOUND, trying local: format={}, file={}", formatName, fileName);
                    try {
                        return delegate.calculateUploadChecksum(fileName);
                    } catch (IOException localEx) {
                        throw new IOException("calculateUploadChecksum failed for " + fileName + " — not found in registry, remote, or local", e);
                    }
                }
                throw new IOException("calculateUploadChecksum failed for " + fileName + " — remote read failed (location=" + locationName(location) + ")", e);
            }
        }

        // No remote directory — try local as fallback
        if (location == TieredStoreNative.LOCATION_NOT_FOUND) {
            try {
                return delegate.calculateUploadChecksum(fileName);
            } catch (IOException e) {
                throw new IOException("calculateUploadChecksum failed for " + fileName + " — not in registry or local, no remote directory", e);
            }
        }

        throw new IOException("calculateUploadChecksum failed for " + fileName + " — location=" + locationName(location) + ", no remote directory");
    }

    /**
     * Compute CRC32 over the full contents of an IndexInput.
     * Same algorithm as GenericStoreDirectory.calculateGenericChecksum.
     */
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

    @Override
    public void close() throws IOException {
        // no-op — registry is owned by TieredCompositeStoreDirectory
    }

    private static String locationName(int loc) {
        switch (loc) {
            case TieredStoreNative.LOCATION_LOCAL: return "LOCAL";
            case TieredStoreNative.LOCATION_REMOTE: return "REMOTE";
            case TieredStoreNative.LOCATION_BOTH: return "BOTH";
            default: return "UNREGISTERED";
        }
    }

    /**
     * Wrapper that releases the read reference in the Rust FileRegistry when closed.
     * When the last reader closes (active_reads → 0) and the file is REMOTE-only,
     * triggers deferred local file deletion via the owning directory.
     */
    private static class RefCountedIndexInput extends IndexInput {

        private IndexInput delegate;
        private final String fileName;
        private final long registryPtr;
        private final TieredCompositeStoreDirectory owningDirectory;
        private boolean closed = false;

        RefCountedIndexInput(IndexInput delegate, String fileName, long registryPtr,
                             TieredCompositeStoreDirectory owningDirectory) {
            super("RefCountedIndexInput(" + delegate.toString() + ")");
            this.delegate = delegate;
            this.fileName = fileName;
            this.registryPtr = registryPtr;
            this.owningDirectory = owningDirectory;
        }

        @Override
        public void close() throws IOException {
            if (!closed) {
                closed = true;
                long remaining = TieredStoreNative.registryReleaseRead(registryPtr, fileName);
                delegate.close();
                // If this was the last reader and the file is REMOTE-only,
                // try to delete the local file now.
                if (remaining == 0 && owningDirectory != null) {
                    owningDirectory.tryDeleteLocalFileAfterLastRead(fileName);
                }
            }
        }

        @Override
        public long getFilePointer() { return delegate.getFilePointer(); }

        @Override
        public void seek(long pos) throws IOException { delegate.seek(pos); }

        @Override
        public long length() { return delegate.length(); }

        @Override
        public IndexInput slice(String sliceDescription, long offset, long length) throws IOException {
            return delegate.slice(sliceDescription, offset, length);
        }

        @Override
        public byte readByte() throws IOException { return delegate.readByte(); }

        @Override
        public void readBytes(byte[] b, int offset, int len) throws IOException {
            delegate.readBytes(b, offset, len);
        }

        @Override
        public IndexInput clone() {
            RefCountedIndexInput cloned = (RefCountedIndexInput) super.clone();
            cloned.delegate = delegate.clone();
            cloned.closed = false;
            TieredStoreNative.registryAcquireRead(registryPtr, fileName);
            return cloned;
        }
    }
}
