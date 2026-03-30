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
import org.opensearch.vectorized.execution.jni.PageCacheProvider;

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
    /**
     * Supplier for the page cache provider.
     * <p>
     * Using a Supplier (rather than holding the provider directly) is critical: in Node.java,
     * {@code getCachedCompositeStoreDirectoryFactories()} is called at line ~973, BEFORE
     * {@code setPageCacheProvider()} is called at line ~1191. By capturing it as a Supplier,
     * the actual provider is resolved lazily at shard creation time (the first call to
     * {@code newDirectory()}), by which point the provider has already been set.
     */
    private final Supplier<PageCacheProvider> pageCacheProviderSupplier;

    /**
     * Constructor without Foyer cache (backward compatible).
     * Uses PassthroughCacheStrategy for all formats.
     */
    public TieredCompositeStoreDirectoryFactory(
        Supplier<RepositoriesService> repositoriesService,
        java.util.function.Function<String, Long> globalRegistryPtrResolver
    ) {
        this(repositoriesService, globalRegistryPtrResolver, () -> null);
    }

    /**
     * Constructor with lazy page cache provider supplier.
     * <p>
     * The supplier is called per-shard at {@code newDirectory()} time (not at factory construction
     * time), so it correctly observes the provider value that was set after factory creation.
     * When the supplier returns non-null for a shard's parquet format, {@link CachedParquetCacheStrategy}
     * is used; otherwise {@link PassthroughCacheStrategy} is used.
     */
    public TieredCompositeStoreDirectoryFactory(
        Supplier<RepositoriesService> repositoriesService,
        java.util.function.Function<String, Long> globalRegistryPtrResolver,
        Supplier<PageCacheProvider> pageCacheProviderSupplier
    ) {
        this.repositoriesService = repositoriesService;
        this.globalRegistryPtrResolver = globalRegistryPtrResolver;
        this.pageCacheProviderSupplier = pageCacheProviderSupplier;
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

        // Cache strategy factory:
        //   "parquet" + Foyer available  → CachedParquetCacheStrategy (byte-range caching via Foyer)
        //   "parquet" + no Foyer         → PassthroughCacheStrategy  (full remote read each time)
        //   "lucene" / "metadata" / etc  → PassthroughCacheStrategy  (FieldCache will replace later)
        // Resolved HERE (at shard creation time), not at factory construction time.
        // This is why pageCacheProviderSupplier is a Supplier — the provider is set in Node.java
        // AFTER getCachedCompositeStoreDirectoryFactories() is called.
        final PageCacheProvider pageCache = this.pageCacheProviderSupplier.get();
        TieredCompositeStoreDirectory directory = new TieredCompositeStoreDirectory(
            indexSettings,
            pluginsService,
            shardId,
            shardPath,
            (formatName, dirPathPrefix) -> {
                if ("parquet".equals(formatName) && pageCache != null) {
                    logger.debug("[TieredCompositeStoreDirectoryFactory] using CachedParquetCacheStrategy for format=parquet, shard={}", shardId);
                    return new CachedParquetCacheStrategy(formatName, remoteDirectory, registryPtr, dirPathPrefix, pageCache);
                }
                return new PassthroughCacheStrategy(formatName, remoteDirectory, registryPtr, dirPathPrefix);
            },
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
