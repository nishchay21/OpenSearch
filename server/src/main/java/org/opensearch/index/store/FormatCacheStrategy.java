/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.store;

import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.opensearch.common.annotation.ExperimentalApi;

import java.io.Closeable;
import java.io.IOException;

/**
 * Per-format cache strategy for cached format store directories.
 * Mirrors {@link FormatStoreDirectory} operations so that every file operation
 * can be intercepted for warm indices where local files may be evicted.
 * <p>
 * Default implementations delegate to the underlying directory.
 * Implementations override methods to provide registry-aware or remote-aware behavior.
 *
 * @opensearch.experimental
 */
@ExperimentalApi
public interface FormatCacheStrategy extends Closeable {

    /**
     * Returns the name of this strategy for logging.
     */
    String name();

    /**
     * Opens an IndexInput — may return a cached/remote IndexInput or delegate to the underlying directory.
     */
    IndexInput openInput(String fileName, IOContext context, FormatStoreDirectory<?> delegate) throws IOException;

    /**
     * Returns the file length. May serve from registry or remote when local file is evicted.
     */
    default long fileLength(String fileName, FormatStoreDirectory<?> delegate) throws IOException {
        return delegate.fileLength(fileName);
    }

    /**
     * Calculates the checksum. May serve from remote when local file is evicted.
     */
    default long calculateChecksum(String fileName, FormatStoreDirectory<?> delegate) throws IOException {
        return delegate.calculateChecksum(fileName);
    }

    /**
     * Calculates the upload checksum. May serve from remote when local file is evicted.
     */
    default String calculateUploadChecksum(String fileName, FormatStoreDirectory<?> delegate) throws IOException {
        return delegate.calculateUploadChecksum(fileName);
    }

    /**
     * Called after a file is written — may register in cache/registry.
     */
    void onFileWritten(String fileName, FormatStoreDirectory<?> delegate) throws IOException;

    /**
     * Called when a file is deleted — evict from cache/registry.
     */
    void onFileDeleted(String fileName) throws IOException;
}
