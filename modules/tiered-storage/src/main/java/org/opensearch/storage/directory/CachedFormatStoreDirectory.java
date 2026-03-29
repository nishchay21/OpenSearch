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
import org.opensearch.index.engine.exec.DataFormat;
import org.opensearch.index.engine.exec.FileMetadata;
import org.opensearch.index.store.CompositeRemoteSegmentStoreDirectory;
import org.opensearch.index.store.FormatCacheStrategy;
import org.opensearch.index.store.FormatStoreDirectory;
import org.opensearch.index.store.UploadedSegmentMetadata;
import org.opensearch.storage.jni.TieredStoreNative;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Decorator that wraps a {@link FormatStoreDirectory} with registry-aware routing.
 * All file operations are routed through the Rust FileRegistry — local reads for LOCAL files,
 * remote reads for REMOTE/BOTH files, with lazy registration for unknown files.
 * <p>
 * The inner {@link FormatCacheStrategy} is called for format-specific caching behavior
 * (e.g. FileCache for Lucene, foyer for Parquet). For passthrough (no caching), the
 * strategy is a simple no-op.
 * <p>
 * This is the building block for {@link TieredCompositeStoreDirectory} — each format gets
 * its own CachedFormatStoreDirectory with registry routing + a format-appropriate cache strategy.
 */
public class CachedFormatStoreDirectory<T extends DataFormat> implements FormatStoreDirectory<T> {

    private static final Logger logger = LogManager.getLogger(CachedFormatStoreDirectory.class);

    private final FormatStoreDirectory<T> inner;
    private final FormatCacheStrategy cacheStrategy;
    private final String formatName;
    private final String dirPathPrefix;
    private final long registryPtr;
    private final CompositeRemoteSegmentStoreDirectory remoteDirectory;
    private volatile TieredCompositeStoreDirectory owningDirectory;

    public CachedFormatStoreDirectory(
        FormatStoreDirectory<T> inner,
        FormatCacheStrategy cacheStrategy,
        long registryPtr,
        CompositeRemoteSegmentStoreDirectory remoteDirectory,
        String dirPathPrefix
    ) {
        this.inner = inner;
        this.cacheStrategy = cacheStrategy;
        this.formatName = inner.getDataFormat().name();
        this.dirPathPrefix = dirPathPrefix;
        this.registryPtr = registryPtr;
        this.remoteDirectory = remoteDirectory;
        logger.info("[CachedFormatStoreDirectory] wrapping format={} with cache={}, registryPtr={}",
            formatName, cacheStrategy.name(), registryPtr);
    }

    public void setOwningDirectory(TieredCompositeStoreDirectory directory) {
        this.owningDirectory = directory;
    }

    // ---- Registry key helpers ----

    private String registryKey(String fileName) {
        return dirPathPrefix + "/" + fileName;
    }

    private String remoteFileName(String fileName) {
        if (formatName == null || formatName.isEmpty() || "lucene".equals(formatName)) {
            return fileName;
        }
        return fileName + FileMetadata.DELIMITER + formatName;
    }

    private boolean tryLazyRegisterFromRemote(String fileName, String registryKey) {
        if (remoteDirectory == null) return false;
        Map<String, UploadedSegmentMetadata> uploaded = remoteDirectory.getSegmentsUploadedToRemoteStore();
        for (UploadedSegmentMetadata meta : uploaded.values()) {
            if (meta.getOriginalFilename().equals(fileName) && meta.getDataFormat().equals(formatName)) {
                String remoteFile = meta.getUploadedFilename();
                String formatSubdir = formatName.toLowerCase();
                String remoteBasePath = owningDirectory != null ? owningDirectory.getRemoteBasePath() : "";
                String fullRemotePath;
                if (remoteBasePath.endsWith("/")) {
                    fullRemotePath = remoteBasePath + formatSubdir + "/" + remoteFile;
                } else {
                    fullRemotePath = remoteBasePath + "/" + formatSubdir + "/" + remoteFile;
                }
                String repoKey = owningDirectory != null ? owningDirectory.getRepoKey() : "";
                TieredStoreNative.registryMarkSyncedToRemote(registryPtr, registryKey, fullRemotePath, repoKey);
                logger.debug("[CachedFormatStoreDirectory] lazy-registered from remote: file={}, format={}, remotePath={}",
                    fileName, formatName, fullRemotePath);
                return true;
            }
        }
        return false;
    }

    private static String locationName(int loc) {
        switch (loc) {
            case TieredStoreNative.LOCATION_LOCAL: return "LOCAL";
            case TieredStoreNative.LOCATION_REMOTE: return "REMOTE";
            case TieredStoreNative.LOCATION_BOTH: return "BOTH";
            default: return "UNREGISTERED";
        }
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

    // ---- FormatStoreDirectory delegation ----

    @Override
    public T getDataFormat() {
        return inner.getDataFormat();
    }

    @Override
    public Path getDirectoryPath() {
        return inner.getDirectoryPath();
    }

    @Override
    public void initialize() throws IOException {
        inner.initialize();
    }

    @Override
    public void cleanup() throws IOException {
        inner.cleanup();
    }

    @Override
    public FileMetadata[] listAll() throws IOException {
        return inner.listAll();
    }

    // ---- Registry-aware file operations ----

    @Override
    public void deleteFile(String name) throws IOException {
        String key = registryKey(name);
        int location = TieredStoreNative.registryGetLocation(registryPtr, key);
        if (location == TieredStoreNative.LOCATION_BOTH) {
            TieredStoreNative.registryMarkLocalDeleted(registryPtr, key);
            logger.debug("[CachedFormatStoreDirectory] deleteFile (mark local deleted): format={}, file={}", formatName, name);
        } else if (location == TieredStoreNative.LOCATION_REMOTE) {
            // File is remote-only — nothing to delete locally
            logger.debug("[CachedFormatStoreDirectory] deleteFile (already remote-only, no-op): format={}, file={}", formatName, name);
            return;
        } else {
            TieredStoreNative.registryRemove(registryPtr, key);
            logger.debug("[CachedFormatStoreDirectory] deleteFile (removed): format={}, file={}", formatName, name);
        }
        cacheStrategy.onFileDeleted(name);
        try {
            inner.deleteFile(name);
        } catch (java.nio.file.NoSuchFileException | java.io.FileNotFoundException e) {
            // File already gone from local disk (evicted or never existed) — safe to ignore
            logger.debug("[CachedFormatStoreDirectory] deleteFile local file not found (already evicted): format={}, file={}", formatName, name);
        }
    }

    @Override
    public long fileLength(String name) throws IOException {
        // 1. Check registry first
        String key = registryKey(name);
        long size = TieredStoreNative.registryGetSize(registryPtr, key);
        if (size >= 0) {
            return size;
        }

        // 2. Try local
        try {
            return inner.fileLength(name);
        } catch (IOException e) {
            // Local failed — try remote
        }

        // 3. Fall back to remote
        if (remoteDirectory != null) {
            try {
                return remoteDirectory.fileLength(remoteFileName(name));
            } catch (IOException e) {
                throw new IOException("fileLength failed for " + name + " — not in registry, local, or remote", e);
            }
        }

        throw new IOException("fileLength failed for " + name + " — not in registry or local, no remote directory");
    }

    @Override
    public OutputStream createOutput(String name) throws IOException {
        OutputStream out = inner.createOutput(name);
        return new OutputStream() {
            @Override
            public void write(int b) throws IOException { out.write(b); }

            @Override
            public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); }

            @Override
            public void flush() throws IOException { out.flush(); }

            @Override
            public void close() throws IOException {
                out.close();
                // Register in FileRegistry
                String key = registryKey(name);
                long size = 0;
                try {
                    size = inner.fileLength(name);
                } catch (IOException e) {
                    logger.warn("[CachedFormatStoreDirectory] could not get file length for {}: {}", name, e.getMessage());
                }
                TieredStoreNative.registryRegisterLocal(registryPtr, key, size);
                logger.debug("[CachedFormatStoreDirectory] onFileWritten: format={}, file={}, size={}", formatName, name, size);
                cacheStrategy.onFileWritten(name, inner);
            }
        };
    }

    @Override
    public InputStream openInput(String name) throws IOException {
        return inner.openInput(name);
    }

    @Override
    public IndexInput openIndexInput(String name, IOContext context) throws IOException {
        String key = registryKey(name);
        int location = TieredStoreNative.registryAcquireRead(registryPtr, key);

        // LOCAL — read from local delegate
        if (location == TieredStoreNative.LOCATION_LOCAL) {
            logger.debug("[CachedFormatStoreDirectory] openInput LOCAL: format={}, file={}", formatName, name);
            try {
                IndexInput localInput = inner.openIndexInput(name, context);
                return new RefCountedIndexInput(localInput, key, registryPtr, owningDirectory);
            } catch (IOException e) {
                TieredStoreNative.registryReleaseRead(registryPtr, key);
                throw e;
            }
        }

        // NOT_FOUND — try lazy registration, then route
        if (location == TieredStoreNative.LOCATION_NOT_FOUND) {
            TieredStoreNative.registryReleaseRead(registryPtr, key);
            if (tryLazyRegisterFromRemote(name, key)) {
                location = TieredStoreNative.registryAcquireRead(registryPtr, key);
                // Fall through to remote read below
            } else {
                // Not in remote metadata — try local (just written, not yet uploaded)
                location = TieredStoreNative.registryAcquireRead(registryPtr, key);
                logger.debug("[CachedFormatStoreDirectory] openInput LOCAL (not in remote): format={}, file={}", formatName, name);
                try {
                    IndexInput localInput = inner.openIndexInput(name, context);
                    return new RefCountedIndexInput(localInput, key, registryPtr, owningDirectory);
                } catch (IOException e) {
                    TieredStoreNative.registryReleaseRead(registryPtr, key);
                    throw e;
                }
            }
        }

        // REMOTE or BOTH — read from remote
        if (remoteDirectory != null) {
            logger.debug("[CachedFormatStoreDirectory] openInput REMOTE: format={}, file={}, location={}",
                formatName, name, locationName(location));
            try {
                IndexInput remoteInput = remoteDirectory.openInput(remoteFileName(name), context);
                return new RefCountedIndexInput(remoteInput, key, registryPtr, owningDirectory);
            } catch (IOException e) {
                logger.warn("[CachedFormatStoreDirectory] REMOTE_READ_FAILED, trying local: format={}, file={}, error={}",
                    formatName, name, e.getMessage());
                try {
                    IndexInput localInput = inner.openIndexInput(name, context);
                    return new RefCountedIndexInput(localInput, key, registryPtr, owningDirectory);
                } catch (IOException localEx) {
                    TieredStoreNative.registryReleaseRead(registryPtr, key);
                    throw e;
                }
            }
        }

        // No remote directory — read from local
        logger.debug("[CachedFormatStoreDirectory] openInput LOCAL (no remote): format={}, file={}", formatName, name);
        try {
            IndexInput localInput = inner.openIndexInput(name, context);
            return new RefCountedIndexInput(localInput, key, registryPtr, owningDirectory);
        } catch (IOException e) {
            TieredStoreNative.registryReleaseRead(registryPtr, key);
            throw e;
        }
    }

    @Override
    public void sync(Collection<String> names) throws IOException {
        inner.sync(names);
    }

    @Override
    public void rename(String source, String dest) throws IOException {
        String srcKey = registryKey(source);
        String dstKey = registryKey(dest);
        TieredStoreNative.registryRemove(registryPtr, srcKey);
        inner.rename(source, dest);
        long size = 0;
        try { size = inner.fileLength(dest); } catch (IOException ignored) {}
        TieredStoreNative.registryRegisterLocal(registryPtr, dstKey, size);
    }

    @Override
    public long calculateChecksum(String fileName) throws IOException {
        String key = registryKey(fileName);
        int location = TieredStoreNative.registryGetLocation(registryPtr, key);

        // LOCAL or BOTH — try local first
        if (location == TieredStoreNative.LOCATION_LOCAL || location == TieredStoreNative.LOCATION_BOTH) {
            try {
                return inner.calculateChecksum(fileName);
            } catch (IOException e) {
                logger.debug("[CachedFormatStoreDirectory] calculateChecksum local failed ({}), trying remote: format={}, file={}",
                    locationName(location), formatName, fileName);
            }
        }

        // REMOTE, BOTH-with-local-failure, or NOT_FOUND — try remote
        if (remoteDirectory != null) {
            try (IndexInput input = remoteDirectory.openInput(remoteFileName(fileName), IOContext.READONCE)) {
                return computeCrc32(input);
            } catch (IOException e) {
                if (location == TieredStoreNative.LOCATION_NOT_FOUND) {
                    try {
                        return inner.calculateChecksum(fileName);
                    } catch (IOException localEx) {
                        throw new IOException("calculateChecksum failed for " + fileName + " — not found anywhere", e);
                    }
                }
                throw new IOException("calculateChecksum failed for " + fileName + " (location=" + locationName(location) + ")", e);
            }
        }

        // No remote — try local as last resort
        if (location == TieredStoreNative.LOCATION_NOT_FOUND) {
            return inner.calculateChecksum(fileName);
        }
        throw new IOException("calculateChecksum failed for " + fileName + " — location=" + locationName(location) + ", no remote");
    }

    @Override
    public String calculateUploadChecksum(String fileName) throws IOException {
        String key = registryKey(fileName);
        int location = TieredStoreNative.registryGetLocation(registryPtr, key);

        if (location == TieredStoreNative.LOCATION_LOCAL || location == TieredStoreNative.LOCATION_BOTH) {
            try {
                return inner.calculateUploadChecksum(fileName);
            } catch (IOException e) {
                logger.debug("[CachedFormatStoreDirectory] calculateUploadChecksum local failed ({}), trying remote: format={}, file={}",
                    locationName(location), formatName, fileName);
            }
        }

        if (remoteDirectory != null) {
            try (IndexInput input = remoteDirectory.openInput(remoteFileName(fileName), IOContext.READONCE)) {
                return Long.toString(computeCrc32(input));
            } catch (IOException e) {
                if (location == TieredStoreNative.LOCATION_NOT_FOUND) {
                    try {
                        return inner.calculateUploadChecksum(fileName);
                    } catch (IOException localEx) {
                        throw new IOException("calculateUploadChecksum failed for " + fileName + " — not found anywhere", e);
                    }
                }
                throw new IOException("calculateUploadChecksum failed for " + fileName + " (location=" + locationName(location) + ")", e);
            }
        }

        if (location == TieredStoreNative.LOCATION_NOT_FOUND) {
            return inner.calculateUploadChecksum(fileName);
        }
        throw new IOException("calculateUploadChecksum failed for " + fileName + " — location=" + locationName(location) + ", no remote");
    }

    @Override
    public void close() throws IOException {
        cacheStrategy.close();
        inner.close();
    }

    // ---- RefCountedIndexInput ----

    private static class RefCountedIndexInput extends IndexInput {

        private IndexInput delegate;
        private final String registryKey;
        private final long registryPtr;
        private final TieredCompositeStoreDirectory owningDirectory;
        private volatile boolean closed = false;

        RefCountedIndexInput(IndexInput delegate, String registryKey, long registryPtr,
                             TieredCompositeStoreDirectory owningDirectory) {
            super("RefCountedIndexInput(" + delegate.toString() + ")");
            this.delegate = delegate;
            this.registryKey = registryKey;
            this.registryPtr = registryPtr;
            this.owningDirectory = owningDirectory;
        }

        @Override
        public void close() throws IOException {
            if (!closed) {
                closed = true;
                try {
                    delegate.close();
                } finally {
                    long remaining = TieredStoreNative.registryReleaseRead(registryPtr, registryKey);
                    if (remaining == 0 && owningDirectory != null) {
                        owningDirectory.tryDeleteLocalFileAfterLastRead(registryKey);
                    }
                }
            }
        }

        @Override public long getFilePointer() { return delegate.getFilePointer(); }
        @Override public void seek(long pos) throws IOException { delegate.seek(pos); }
        @Override public long length() { return delegate.length(); }
        @Override public IndexInput slice(String sliceDescription, long offset, long length) throws IOException {
            return delegate.slice(sliceDescription, offset, length);
        }
        @Override public byte readByte() throws IOException { return delegate.readByte(); }
        @Override public void readBytes(byte[] b, int offset, int len) throws IOException {
            delegate.readBytes(b, offset, len);
        }

        @Override
        public IndexInput clone() {
            RefCountedIndexInput cloned = (RefCountedIndexInput) super.clone();
            cloned.delegate = delegate.clone();
            cloned.closed = false;
            TieredStoreNative.registryAcquireRead(registryPtr, registryKey);
            return cloned;
        }
    }
}
