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
import org.opensearch.index.store.CompositeRemoteSegmentStoreDirectory;
import org.opensearch.storage.jni.TieredStoreNative;
import org.opensearch.vectorized.execution.jni.PageCacheProvider;

import java.io.IOException;

/**
 * Lucene {@link IndexInput} backed by the Foyer in-memory page cache.
 * <p>
 * Used by {@link CachedParquetCacheStrategy} for REMOTE Parquet files.
 * On every {@code readBytes()} call it translates the current file pointer
 * and length into a byte range and serves it from Foyer (fetching from
 * {@code remoteDirectory} on cache miss).
 * <p>
 * On {@code close()}, releases the FileRegistry read reference — same safety
 * contract as {@link PassthroughCacheStrategy.RefCountedIndexInput}.
 */
public class CachedParquetIndexInput extends IndexInput {

    private static final Logger logger = LogManager.getLogger(CachedParquetIndexInput.class);

    /** Remote file name (format-qualified, e.g. "_parquet_0.parquet:::parquet") */
    private final String remoteFileName;
    /** FileRegistry key (local path without leading "/") */
    private final String registryKey;
    /** Total file length in bytes */
    private final long fileLength;
    /** Page cache provider — calls back into DataFusionPlugin via JNI */
    private final PageCacheProvider foyerCache;
    /** Remote directory for fetching bytes on cache miss */
    private final CompositeRemoteSegmentStoreDirectory remoteDirectory;
    /** FileRegistry pointer for ref-counting */
    private final long registryPtr;
    /** Owning directory for deferred eviction after last reader closes */
    private final TieredCompositeStoreDirectory owningDirectory;

    /** Current virtual file pointer */
    private long filePointer = 0L;
    /** Whether this input has been closed */
    private boolean closed = false;

    public CachedParquetIndexInput(
        String resourceDescription,
        String remoteFileName,
        String registryKey,
        long fileLength,
        PageCacheProvider foyerCache,
        CompositeRemoteSegmentStoreDirectory remoteDirectory,
        long registryPtr,
        TieredCompositeStoreDirectory owningDirectory
    ) {
        super(resourceDescription);
        this.remoteFileName = remoteFileName;
        this.registryKey = registryKey;
        this.fileLength = fileLength;
        this.foyerCache = foyerCache;
        this.remoteDirectory = remoteDirectory;
        this.registryPtr = registryPtr;
        this.owningDirectory = owningDirectory;
    }

    @Override
    public byte readByte() throws IOException {
        byte[] buf = new byte[1];
        readBytes(buf, 0, 1);
        return buf[0];
    }

    @Override
    public void readBytes(byte[] b, int offset, int len) throws IOException {
        if (closed) throw new IOException("IndexInput is closed: " + toString());
        if (filePointer + len > fileLength) {
            throw new IOException(
                "Read past EOF: filePointer=" + filePointer + ", len=" + len +
                ", fileLength=" + fileLength + ", file=" + remoteFileName
            );
        }

        int start = (int) filePointer;
        int end = start + len;

        // 1. Try Foyer cache first
        byte[] cached = foyerCache.getPageRange(registryKey, start, end);
        if (cached != null) {
            logger.debug("[CachedParquetIndexInput] cache HIT: key={}, range={}..{}", registryKey, start, end);
            System.arraycopy(cached, 0, b, offset, len);
            filePointer += len;
            return;
        }

        // 2. Cache miss — fetch from remote directory
        logger.debug("[CachedParquetIndexInput] cache MISS: key={}, range={}..{} — fetching from remote",
            registryKey, start, end);

        byte[] fetched = fetchRangeFromRemote(start, len);

        // 3. Populate Foyer for future reads
        foyerCache.putPageRange(registryKey, start, end, fetched);

        System.arraycopy(fetched, 0, b, offset, len);
        filePointer += len;
    }

    /**
     * Fetch a byte range from the remote directory.
     * Opens a temporary IndexInput at the remote path, seeks to {@code start}, reads {@code len} bytes.
     */
    private byte[] fetchRangeFromRemote(int start, int len) throws IOException {
        try (IndexInput remoteInput = remoteDirectory.openInput(remoteFileName, IOContext.READONCE)) {
            remoteInput.seek(start);
            byte[] buf = new byte[len];
            remoteInput.readBytes(buf, 0, len);
            return buf;
        }
    }

    @Override
    public void seek(long pos) throws IOException {
        if (pos < 0 || pos > fileLength) {
            throw new IOException("Seek out of bounds: pos=" + pos + ", fileLength=" + fileLength);
        }
        filePointer = pos;
    }

    @Override
    public long getFilePointer() {
        return filePointer;
    }

    @Override
    public long length() {
        return fileLength;
    }

    @Override
    public IndexInput slice(String sliceDescription, long offset, long length) throws IOException {
        // Create a sliced view — delegates reads through this input
        return new SlicedFoyerIndexInput(sliceDescription, this, offset, length);
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            // Release FileRegistry read reference
            long remaining = TieredStoreNative.registryReleaseRead(registryPtr, registryKey);
            // If last reader and file is REMOTE-only, trigger deferred local delete
            if (remaining == 0 && owningDirectory != null) {
                owningDirectory.tryDeleteLocalFileAfterLastRead(registryKey);
            }
        }
    }

    @Override
    public IndexInput clone() {
        CachedParquetIndexInput cloned = (CachedParquetIndexInput) super.clone();
        // Each clone has its own file pointer (already handled by super.clone())
        // Increment registry read ref for the clone
        TieredStoreNative.registryAcquireRead(registryPtr, registryKey);
        return cloned;
    }

    // -------------------------------------------------------------------------
    // Inner class: slice support
    // -------------------------------------------------------------------------

    /**
     * Sliced view of a {@link CachedParquetIndexInput}.
     * Reads are delegated to the parent input with offset adjustment.
     */
    static class SlicedFoyerIndexInput extends IndexInput {

        private final CachedParquetIndexInput parent;
        private final long sliceOffset;
        private final long sliceLength;
        private long localPointer = 0L;

        SlicedFoyerIndexInput(
            String resourceDescription,
            CachedParquetIndexInput parent,
            long offset,
            long length
        ) {
            super(resourceDescription);
            this.parent = parent;
            this.sliceOffset = offset;
            this.sliceLength = length;
        }

        @Override
        public byte readByte() throws IOException {
            byte[] buf = new byte[1];
            readBytes(buf, 0, 1);
            return buf[0];
        }

        @Override
        public void readBytes(byte[] b, int offset, int len) throws IOException {
            if (localPointer + len > sliceLength) {
                throw new IOException("Read past slice end");
            }
            long absoluteStart = sliceOffset + localPointer;
            int start = (int) absoluteStart;
            int end = start + len;

            // Try Foyer cache via parent
            byte[] cached = parent.foyerCache.getPageRange(parent.registryKey, start, end);
            if (cached != null) {
                System.arraycopy(cached, 0, b, offset, len);
                localPointer += len;
                return;
            }

            // Fetch from remote
            byte[] fetched = parent.fetchRangeFromRemote(start, len);
            parent.foyerCache.putPageRange(parent.registryKey, start, end, fetched);
            System.arraycopy(fetched, 0, b, offset, len);
            localPointer += len;
        }

        @Override
        public void seek(long pos) throws IOException {
            if (pos < 0 || pos > sliceLength) throw new IOException("Seek out of slice bounds");
            localPointer = pos;
        }

        @Override
        public long getFilePointer() { return localPointer; }

        @Override
        public long length() { return sliceLength; }

        @Override
        public IndexInput slice(String desc, long offset, long length) throws IOException {
            return new SlicedFoyerIndexInput(desc, parent, sliceOffset + offset, length);
        }

        @Override
        public void close() throws IOException {
            // Slice does not own the ref-count; parent does
        }
    }
}
