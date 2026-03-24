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
import org.opensearch.index.store.FormatCacheStrategy;
import org.opensearch.index.store.FormatStoreDirectory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Collection;

/**
 * Decorator that wraps a {@link FormatStoreDirectory} with a {@link FormatCacheStrategy}.
 * All file operations delegate to the inner directory; read/write paths go through the cache strategy.
 * <p>
 * This is the building block for {@link TieredCompositeStoreDirectory} — each format gets
 * its own CachedFormatStoreDirectory with a format-appropriate cache strategy.
 */
public class CachedFormatStoreDirectory<T extends DataFormat> implements FormatStoreDirectory<T> {

    private static final Logger logger = LogManager.getLogger(CachedFormatStoreDirectory.class);

    private final FormatStoreDirectory<T> inner;
    private final FormatCacheStrategy cacheStrategy;

    public CachedFormatStoreDirectory(FormatStoreDirectory<T> inner, FormatCacheStrategy cacheStrategy) {
        this.inner = inner;
        this.cacheStrategy = cacheStrategy;
        logger.info("[CachedFormatStoreDirectory] wrapping format={} with cache={}",
            inner.getDataFormat().name(), cacheStrategy.name());
    }

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

    @Override
    public void deleteFile(String name) throws IOException {
        cacheStrategy.onFileDeleted(name);
        inner.deleteFile(name);
    }

    @Override
    public long fileLength(String name) throws IOException {
        return cacheStrategy.fileLength(name, inner);
    }

    @Override
    public OutputStream createOutput(String name) throws IOException {
        OutputStream out = inner.createOutput(name);
        // Wrap to notify cache after write completes
        return new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                out.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                out.write(b, off, len);
            }

            @Override
            public void close() throws IOException {
                out.close();
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
        logger.info("[CachedFormatStoreDirectory] openIndexInput: format={}, file={}, strategy={}",
            inner.getDataFormat().name(), name, cacheStrategy.name());
        return cacheStrategy.openInput(name, context, inner);
    }

    @Override
    public void sync(Collection<String> names) throws IOException {
        inner.sync(names);
    }

    @Override
    public void rename(String source, String dest) throws IOException {
        cacheStrategy.onFileDeleted(source);
        inner.rename(source, dest);
        cacheStrategy.onFileWritten(dest, inner);
    }

    @Override
    public long calculateChecksum(String fileName) throws IOException {
        return cacheStrategy.calculateChecksum(fileName, inner);
    }

    @Override
    public String calculateUploadChecksum(String fileName) throws IOException {
        return cacheStrategy.calculateUploadChecksum(fileName, inner);
    }

    @Override
    public void close() throws IOException {
        cacheStrategy.close();
        inner.close();
    }
}
