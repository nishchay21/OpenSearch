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
import org.opensearch.index.store.FormatCacheStrategy;
import org.opensearch.index.store.FormatStoreDirectory;

import java.io.IOException;

/**
 * No-op cache strategy — all registry operations (acquire/release, routing, lazy registration)
 * are handled by {@link CachedFormatStoreDirectory}. This strategy simply delegates reads
 * to the underlying directory without any additional caching.
 * <p>
 * In production, format-specific strategies (FileCache for Lucene, foyer for Parquet)
 * would override methods to add caching behavior on top of the registry routing
 * provided by CachedFormatStoreDirectory.
 */
public class PassthroughCacheStrategy implements FormatCacheStrategy {

    private static final Logger logger = LogManager.getLogger(PassthroughCacheStrategy.class);

    private final String formatName;

    public PassthroughCacheStrategy(String formatName) {
        this.formatName = formatName;
    }

    @Override
    public String name() {
        return "passthrough(" + formatName + ")";
    }

    @Override
    public IndexInput openInput(String fileName, IOContext context, FormatStoreDirectory<?> delegate) throws IOException {
        return delegate.openIndexInput(fileName, context);
    }

    @Override
    public void onFileWritten(String fileName, FormatStoreDirectory<?> delegate) throws IOException {
        // no-op — registry registration handled by CachedFormatStoreDirectory
    }

    @Override
    public void onFileDeleted(String fileName) throws IOException {
        // no-op — registry cleanup handled by CachedFormatStoreDirectory
    }

    @Override
    public void close() throws IOException {
        // no-op
    }
}
