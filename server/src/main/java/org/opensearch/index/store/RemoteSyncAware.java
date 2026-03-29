/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.store;

import org.opensearch.common.annotation.ExperimentalApi;
import org.opensearch.index.engine.exec.FileMetadata;

import java.util.Collection;

/**
 * SPI interface for directories that need to be notified about remote sync events.
 * Implemented by directories that maintain a file registry (e.g. TieredCompositeStoreDirectory)
 * so they can track which files exist on remote after upload.
 *
 * @opensearch.experimental
 */
@ExperimentalApi
public interface RemoteSyncAware {

    /**
     * Called after a file has been successfully uploaded to remote store.
     * Implementations update their file registry so the file is known to exist on remote.
     *
     * @param fileName   the local file name that was uploaded
     * @param remotePath the remote path/filename after upload
     */
    void afterSyncToRemote(String fileName, String remotePath);

    /**
     * Called before segment upload begins to register all local files in the file registry.
     * Needed because some formats (e.g. Parquet) write files via native JNI directly to disk,
     * bypassing createOutput(), so the registry doesn't learn about them through the normal write path.
     *
     * @param fileMetadataCollection the files about to be uploaded
     */
    void beforeSyncToRemote(Collection<FileMetadata> fileMetadataCollection);

    /**
     * Ensures the given files are registered in the file registry with their remote paths.
     * Called on warm replicas during segment replication, after the catalog snapshot is applied
     * but before search engine readers attempt to read the files.
     * <p>
     * Only registers files that are not already in the registry. For each unregistered file,
     * resolves the remote path from remote segment metadata and registers it.
     *
     * @param files the files from the replicated catalog snapshot
     */
    default void ensureFilesRegistered(Collection<FileMetadata> files) {}
}
