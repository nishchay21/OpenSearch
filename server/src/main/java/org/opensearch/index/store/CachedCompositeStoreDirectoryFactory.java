/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.store;

import org.opensearch.common.annotation.ExperimentalApi;
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.index.IndexSettings;
import org.opensearch.index.shard.ShardPath;
import org.opensearch.index.store.remote.filecache.FileCache;
import org.opensearch.plugins.PluginsService;

import java.io.IOException;

/**
 * Factory interface for creating cached CompositeStoreDirectory instances.
 * Unlike {@link CompositeStoreDirectoryFactory}, this receives {@link FileCache}
 * and {@link CompositeRemoteSegmentStoreDirectory} at shard creation time so
 * implementations can wire per-format cache strategies.
 * <p>
 * The remote directory is needed for Lucene cache miss fetches — when a file
 * is not in the local cache, the strategy fetches it from remote store.
 *
 * @opensearch.experimental
 */
@ExperimentalApi
@FunctionalInterface
public interface CachedCompositeStoreDirectoryFactory {

    /**
     * Creates a new CompositeStoreDirectory with per-format caching.
     *
     * @param indexSettings    the shard's index settings
     * @param shardId          the shard identifier
     * @param shardPath        the path the shard is using
     * @param pluginsService   for discovering DataFormat plugins
     * @param fileCache        node-level file cache (for Lucene format caching)
     * @param remoteDirectory  the composite remote segment store directory for cache miss fetches
     * @return a new CompositeStoreDirectory with cached format directories
     * @throws IOException if directory creation fails
     */
    CompositeStoreDirectory newDirectory(
        IndexSettings indexSettings,
        ShardId shardId,
        ShardPath shardPath,
        PluginsService pluginsService,
        FileCache fileCache,
        CompositeRemoteSegmentStoreDirectory remoteDirectory
    ) throws IOException;
}
