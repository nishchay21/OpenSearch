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
import org.opensearch.common.annotation.ExperimentalApi;
import org.opensearch.common.blobstore.BlobPath;
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.index.IndexSettings;
import org.opensearch.index.remote.RemoteStorePathStrategy;
import org.opensearch.index.shard.ShardPath;
import org.opensearch.index.store.CachedCompositeStoreDirectoryFactory;
import org.opensearch.index.store.CompositeRemoteSegmentStoreDirectory;
import org.opensearch.index.store.CompositeStoreDirectory;
import org.opensearch.index.store.remote.filecache.FileCache;
import org.opensearch.plugins.PluginsService;
import org.opensearch.repositories.RepositoriesService;
import org.opensearch.repositories.Repository;
import org.opensearch.repositories.blobstore.BlobStoreRepository;

import java.io.IOException;
import java.util.function.Supplier;

import static org.opensearch.index.remote.RemoteStoreEnums.DataCategory.SEGMENTS;
import static org.opensearch.index.remote.RemoteStoreEnums.DataType.DATA;

/**
 * Factory that creates {@link TieredCompositeStoreDirectory} instances.
 * <p>
 * Uses the global {@code TieredObjectStore} created by {@link org.opensearch.storage.TieredStoragePlugin}
 * and the shared Rust {@code FileRegistry}. Per-shard, it resolves the remote data path
 * from {@link RepositoriesService} (same pattern as
 * {@link org.opensearch.index.store.RemoteSegmentStoreDirectoryFactory}).
 * The remote data path is stored per-shard so {@code afterSyncToRemote} can register
 * the correct remote key in the global registry.
 *
 * @opensearch.experimental
 */
@ExperimentalApi
public class TieredCompositeStoreDirectoryFactory implements CachedCompositeStoreDirectoryFactory {

    private static final Logger logger = LogManager.getLogger(TieredCompositeStoreDirectoryFactory.class);

    private final Supplier<RepositoriesService> repositoriesService;
    private final java.util.function.Function<String, Long> globalRegistryPtrResolver;

    public TieredCompositeStoreDirectoryFactory(
        Supplier<RepositoriesService> repositoriesService,
        java.util.function.Function<String, Long> globalRegistryPtrResolver
    ) {
        this.repositoriesService = repositoriesService;
        this.globalRegistryPtrResolver = globalRegistryPtrResolver;
    }

    @Override
    public CompositeStoreDirectory newDirectory(
        IndexSettings indexSettings,
        ShardId shardId,
        ShardPath shardPath,
        PluginsService pluginsService,
        FileCache fileCache,
        CompositeRemoteSegmentStoreDirectory remoteDirectory
    ) throws IOException {
        // Resolve the remote data path — both the logical blob path (relative to repo root)
        // and the full filesystem path for logging
        String repositoryName = indexSettings.getRemoteStoreRepository();
        String remoteDataBlobPath = resolveRemoteDataBlobPath(repositoryName, indexSettings, shardId);

        // Get the global registry pointer from the plugin (lazily creates ObjectStore on first call)
        long registryPtr = globalRegistryPtrResolver.apply(repositoryName);

        logger.info("[TieredCompositeStoreDirectoryFactory] creating for shard={}, repo={}, remoteDataBlobPath={}, " +
            "globalRegistryPtr={}, fileCache={}, remoteDir={}",
            shardId, repositoryName, remoteDataBlobPath, registryPtr,
            fileCache != null ? "present" : "null",
            remoteDirectory != null ? "present" : "null");

        TieredCompositeStoreDirectory directory = new TieredCompositeStoreDirectory(
            indexSettings,
            pluginsService,
            shardId,
            shardPath,
            (formatName, dirPathPrefix) -> new PassthroughCacheStrategy(formatName, remoteDirectory, registryPtr, dirPathPrefix),
            registryPtr,
            remoteDataBlobPath,
            repositoryName,
            remoteDirectory
        );

        // Populate the Rust FileRegistry from remote metadata — needed on recovery/restart
        // when the in-memory registry is empty but remote metadata has the local→remote mappings.
        directory.populateRegistryFromRemoteMetadata();

        return directory;
    }

    /**
     * Resolves the logical blob path for the remote segment data directory,
     * relative to the repository root. This is the path used in the FileRegistry
     * so TieredObjectStore can find files via the RemoteObjectStore.
     * <p>
     * For example: {@code basePath/indexUUID/shardId/segments/data/}
     */
    private String resolveRemoteDataBlobPath(String repositoryName, IndexSettings indexSettings, ShardId shardId) {
        Repository repository = repositoriesService.get().repository(repositoryName);
        assert repository instanceof BlobStoreRepository : "repository should be instance of BlobStoreRepository";
        BlobStoreRepository blobStoreRepository = (BlobStoreRepository) repository;

        BlobPath repositoryBasePath = blobStoreRepository.basePath();
        String indexUUID = indexSettings.getIndex().getUUID();
        String shardIdStr = String.valueOf(shardId.id());

        RemoteStorePathStrategy pathStrategy = indexSettings.getRemoteStorePathStrategy();
        RemoteStorePathStrategy.ShardDataPathInput dataPathInput = RemoteStorePathStrategy.ShardDataPathInput.builder()
            .basePath(repositoryBasePath)
            .indexUUID(indexUUID)
            .shardId(shardIdStr)
            .dataCategory(SEGMENTS)
            .dataType(DATA)
            .build();

        BlobPath dataPath = pathStrategy.generatePath(dataPathInput);
        // Return the logical blob path (relative to repo root)
        return dataPath.buildAsString();
    }
}
