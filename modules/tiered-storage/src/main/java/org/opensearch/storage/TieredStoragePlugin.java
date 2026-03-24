package org.opensearch.storage;


import org.opensearch.transport.client.Client;
import org.opensearch.action.ActionRequest;
import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.SetOnce;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.inject.AbstractModule;
import org.opensearch.common.inject.Module;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.IndexScopedSettings;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.settings.SettingsFilter;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.common.util.concurrent.OpenSearchExecutors;
import org.opensearch.core.common.io.stream.NamedWriteableRegistry;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.env.Environment;
import org.opensearch.env.NodeEnvironment;
import org.opensearch.core.action.ActionResponse;
import org.opensearch.index.IndexModule;
import org.opensearch.node.Node;
import org.opensearch.plugins.ActionPlugin;
import org.opensearch.plugins.IndexStorePlugin;
import org.opensearch.plugins.Plugin;
import org.opensearch.plugins.TelemetryAwarePlugin;
import org.opensearch.repositories.RepositoriesService;
import org.opensearch.repositories.Repository;
import org.opensearch.script.ScriptService;
import org.opensearch.storage.directory.OSBlockHotDirectoryFactory;
import org.opensearch.threadpool.ExecutorBuilder;
import org.opensearch.threadpool.ScalingExecutorBuilder;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.watcher.ResourceWatcherService;
import org.opensearch.rest.RestController;
import org.opensearch.rest.RestHandler;
import org.opensearch.storage.action.tiering.CancelTieringAction;
import org.opensearch.storage.action.tiering.HotToWarmTierAction;
import org.opensearch.storage.action.tiering.RestCancelTierAction;
import org.opensearch.storage.action.tiering.RestHotToWarmTierAction;
import org.opensearch.storage.action.tiering.RestWarmToHotTierAction;
import org.opensearch.storage.action.tiering.TransportCancelTierAction;
import org.opensearch.storage.action.tiering.TransportHotToWarmTierAction;
import org.opensearch.storage.action.tiering.TransportWarmToHotTierAction;
import org.opensearch.storage.action.tiering.WarmToHotTierAction;
import org.opensearch.storage.action.tiering.status.GetTieringStatusAction;
import org.opensearch.storage.action.tiering.status.ListTieringStatusAction;
import org.opensearch.storage.action.tiering.status.rest.RestGetTieringStatusAction;
import org.opensearch.storage.action.tiering.status.rest.RestListTieringStatusAction;
import org.opensearch.storage.action.tiering.status.transport.TransportGetTieringStatusAction;
import org.opensearch.storage.action.tiering.status.transport.TransportListTieringStatusAction;
import org.opensearch.storage.common.tiering.TieringUtils;
import org.opensearch.storage.directory.TieredCompositeStoreDirectoryFactory;
import org.opensearch.storage.directory.TieredDirectoryFactory;
import org.opensearch.storage.jni.TieredStoreNative;
import org.opensearch.storage.metrics.TierActionMetrics;
import org.opensearch.storage.prefetch.StoredFieldsPrefetch;
import org.opensearch.storage.prefetch.TieredStoragePrefetchSettings;
import org.opensearch.storage.slowlogs.TieredStorageSearchSlowLog;
import org.opensearch.storage.tiering.HotToWarmTieringService;
import org.opensearch.storage.tiering.WarmToHotTieringService;
import org.opensearch.telemetry.metrics.MetricsRegistry;
import org.opensearch.telemetry.tracing.Tracer;
import org.opensearch.index.store.CompositeStoreDirectoryFactory;
import org.opensearch.vectorized.execution.jni.NativeObjectStoreProvider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;import java.util.stream.Stream;
import org.opensearch.common.util.FeatureFlags;

import static org.opensearch.storage.slowlogs.TieredStorageSearchSlowLog.TIERED_STORAGE_SEARCH_SLOWLOG_SETTINGS;

/**
 * Plugin to support writable warm index and other related features.
 * <p>
 * The global {@code TieredObjectStore} is created once (lazily on first shard creation).
 * Per-repository remote stores are added to the shared {@code FileRegistry} as new
 * repositories are encountered. Different indices can point to different repositories.
 */
public class TieredStoragePlugin extends Plugin implements IndexStorePlugin, ActionPlugin, TelemetryAwarePlugin, NativeObjectStoreProvider {

    private static final org.apache.logging.log4j.Logger logger = org.apache.logging.log4j.LogManager.getLogger(TieredStoragePlugin.class);

    public static final String HOT_BLOCK_EAGER_FETCH_INDEX_TYPE = "hot_block_eager_fetch";
    private static final String REMOTE_DOWNLOAD = "remote_download";
    private final SetOnce<ThreadPool> threadpool = new SetOnce<>();
    public static final String TIERED_COMPOSITE_INDEX_TYPE = "tiered-storage";
    private TieredStoragePrefetchSettings tieredStoragePrefetchSettings;
    private TierActionMetrics tierActionMetrics;

    private volatile Supplier<RepositoriesService> repositoriesServiceSupplier;

    // Global native ObjectStore — created lazily on first warm shard creation
    private volatile long globalObjStoreDataPtr;
    private volatile long globalObjStoreVtablePtr;
    private volatile long globalRegistryPtr;

    /** Tracks which repositories have already had their remote store registered. */
    private final Set<String> registeredRepos = new HashSet<>();

    private final List<Setting<?>> tieredStorageSettings = Stream.concat(
        Stream.of(
            TieringUtils.H2W_MAX_CONCURRENT_TIEIRNG_REQUESTS,
            TieringUtils.W2H_MAX_CONCURRENT_TIEIRNG_REQUESTS,
            TieringUtils.JVM_USAGE_TIERING_THRESHOLD_PERCENT,
            TieringUtils.FILECACHE_ACTIVE_USAGE_TIERING_THRESHOLD_PERCENT,
            TieredStoragePrefetchSettings.READ_AHEAD_BLOCK_COUNT,
            TieredStoragePrefetchSettings.STORED_FIELDS_PREFETCH_ENABLED_SETTING
        ),
        TIERED_STORAGE_SEARCH_SLOWLOG_SETTINGS.stream()
    ).toList();

    @Override
    public Map<String, IndexStorePlugin.DirectoryFactory> getDirectoryFactories() {
        return Collections.emptyMap();
    }

    @Override
    public Map<String, IndexStorePlugin.CompositeDirectoryFactory> getCompositeDirectoryFactories() {
        final Map<String, IndexStorePlugin.CompositeDirectoryFactory> registry = new HashMap<>();
        registry.put(HOT_BLOCK_EAGER_FETCH_INDEX_TYPE, new OSBlockHotDirectoryFactory(() -> threadpool.get()));
        registry.put(TIERED_COMPOSITE_INDEX_TYPE, new TieredDirectoryFactory(getPrefetchSettingsSupplier()));
        return registry;
    }

    @Override
    public Map<String, CompositeStoreDirectoryFactory> getCompositeStoreDirectoryFactories() {
        return Collections.emptyMap();
    }

    @Override
    public Map<String, org.opensearch.index.store.CachedCompositeStoreDirectoryFactory> getCachedCompositeStoreDirectoryFactories() {
        return Map.of(TIERED_COMPOSITE_INDEX_TYPE, new TieredCompositeStoreDirectoryFactory(
            () -> repositoriesServiceSupplier.get(),
            (repoName) -> {
                ensureGlobalObjectStoreCreated();
                ensureRemoteStoreForRepo(repoName);
                return globalRegistryPtr;
            }
        ));
    }

    @Override
    public List<Setting<?>> getSettings() {
        return tieredStorageSettings;
    }

    public List<ExecutorBuilder<?>> getExecutorBuilders(Settings settings) {
        final int allocatedProcessors = OpenSearchExecutors.allocatedProcessors(settings);
        ExecutorBuilder<?> executorBuilder = new ScalingExecutorBuilder(
                REMOTE_DOWNLOAD,
                1,
                ThreadPool.twiceAllocatedProcessors(allocatedProcessors),
                TimeValue.timeValueMinutes(5)
        );
        return List.of(executorBuilder);
    }

    public ThreadPool getThreadpool() {
        return threadpool.get();
    }

    public Supplier<TieredStoragePrefetchSettings> getPrefetchSettingsSupplier() {
        return () -> this.tieredStoragePrefetchSettings;
    }

    @Override
    public List<ActionHandler<? extends ActionRequest, ? extends ActionResponse>> getActions() {
        if (FeatureFlags.isEnabled(FeatureFlags.WRITABLE_WARM_INDEX_EXPERIMENTAL_FLAG)) {
            return List.of(
                new ActionHandler<>(HotToWarmTierAction.INSTANCE, TransportHotToWarmTierAction.class),
                new ActionHandler<>(WarmToHotTierAction.INSTANCE, TransportWarmToHotTierAction.class),
                new ActionHandler<>(CancelTieringAction.INSTANCE, TransportCancelTierAction.class),
                new ActionHandler<>(ListTieringStatusAction.INSTANCE, TransportListTieringStatusAction.class),
                new ActionHandler<>(GetTieringStatusAction.INSTANCE, TransportGetTieringStatusAction.class));
        } else {
            return List.of();
        }
    }

    @Override
    public List<RestHandler> getRestHandlers(
        Settings settings, RestController restController,
        ClusterSettings clusterSettings,
        IndexScopedSettings indexScopedSettings, SettingsFilter settingsFilter,
        IndexNameExpressionResolver indexNameExpressionResolver,
        Supplier<DiscoveryNodes> nodesInCluster
    ) {
        if (FeatureFlags.isEnabled(FeatureFlags.WRITABLE_WARM_INDEX_EXPERIMENTAL_FLAG)) {
            return List.of(
                new RestHotToWarmTierAction(),
                new RestWarmToHotTierAction(),
                new RestCancelTierAction(),
                new RestGetTieringStatusAction(),
                new RestListTieringStatusAction());
        } else {
            return List.of();
        }
    }

    @Override
    public Collection<Object> createComponents(
        Client client,
        ClusterService clusterService,
        ThreadPool threadPool,
        ResourceWatcherService resourceWatcherService,
        ScriptService scriptService,
        NamedXContentRegistry xContentRegistry,
        Environment environment,
        NodeEnvironment nodeEnvironment,
        NamedWriteableRegistry namedWriteableRegistry,
        IndexNameExpressionResolver indexNameExpressionResolver,
        Supplier<RepositoriesService> repositoriesServiceSupplier,
        Tracer tracer,
        MetricsRegistry metricsRegistry
    ) {
        this.tierActionMetrics = new TierActionMetrics(metricsRegistry);
        this.tieredStoragePrefetchSettings = new TieredStoragePrefetchSettings(clusterService.getClusterSettings());
        this.threadpool.set(threadPool);
        this.repositoriesServiceSupplier = repositoriesServiceSupplier;

        logger.info("[TieredStoragePlugin] initialized — global TieredObjectStore will be created on first shard creation");
        return Collections.emptyList();
    }

    /**
     * Lazily create the global TieredObjectStore (no remote stores).
     * Remote stores are added per-repo via {@link #ensureRemoteStoreForRepo}.
     * Thread-safe via synchronized.
     */
    private synchronized void ensureGlobalObjectStoreCreated() {
        if (globalObjStoreDataPtr != 0) {
            return;
        }

        TieredStoreNative.ensureLoaded();

        long[] ptrs = TieredStoreNative.createTieredObjectStore();
        this.globalObjStoreDataPtr = ptrs[0];
        this.globalObjStoreVtablePtr = ptrs[1];
        this.globalRegistryPtr = ptrs[2];

        logger.info("[TieredStoragePlugin] global TieredObjectStore created: data_ptr={}, vtable_ptr={}, registry_ptr={}",
            globalObjStoreDataPtr, globalObjStoreVtablePtr, globalRegistryPtr);
    }

    /**
     * Ensure a remote store is registered in the FileRegistry for the given repository.
     * Resolves the repo config and calls addRemoteStore with the appropriate store type
     * and configuration JSON. Idempotent — skips if already registered.
     *
     * @param repositoryName the repository name from index settings
     */
    private synchronized void ensureRemoteStoreForRepo(String repositoryName) {
        if (registeredRepos.contains(repositoryName)) {
            return;
        }

        RepositoriesService repoService = repositoriesServiceSupplier.get();
        Repository repo = repoService.repository(repositoryName);
        String storeType = resolveStoreType(repo);
        String configJson = buildStoreConfig(repositoryName, repo, storeType);

        TieredStoreNative.addRemoteStore(globalRegistryPtr, repositoryName, storeType, configJson);
        registeredRepos.add(repositoryName);

        logger.info("[TieredStoragePlugin] registered remote store: repo={}, type={}", repositoryName, storeType);
    }

    /**
     * Determine the store type from the repository implementation.
     */
    private String resolveStoreType(Repository repo) {
        String repoType = repo.getMetadata().type();
        switch (repoType) {
            case "fs":
                return "fs";
            case "s3":
                return "s3";
            case "gcs":
                return "gcs";
            case "azure":
                return "azure";
            default:
                throw new IllegalStateException(
                    "[TieredStoragePlugin] Unsupported repository type: " + repoType +
                    ". Supported types: fs, s3, gcs, azure"
                );
        }
    }

    /**
     * Build the JSON configuration for the remote object store based on repository type and settings.
     * <p>
     * For cloud stores (S3, GCS, Azure), extracts bucket/container, prefix, region, endpoint,
     * and encryption settings from the repository metadata. Credentials are resolved by the
     * object_store crate's default credential chain (env vars, instance metadata, IAM roles).
     * Explicit credentials from repo settings are passed when available.
     */
    private String buildStoreConfig(String repositoryName, Repository repo, String storeType) {
        org.opensearch.common.settings.Settings repoSettings = repo.getMetadata().settings();

        switch (storeType) {
            case "fs":
                return buildFsConfig(repo);
            case "s3":
                return buildS3Config(repoSettings);
            case "gcs":
                return buildGcsConfig(repoSettings);
            case "azure":
                return buildAzureConfig(repoSettings);
            default:
                throw new IllegalStateException("Unknown store type: " + storeType);
        }
    }

    private String buildFsConfig(Repository repo) {
        if (repo instanceof org.opensearch.repositories.blobstore.BlobStoreRepository) {
            org.opensearch.repositories.blobstore.BlobStoreRepository blobStoreRepo =
                (org.opensearch.repositories.blobstore.BlobStoreRepository) repo;
            org.opensearch.common.blobstore.BlobStore blobStore = blobStoreRepo.blobStore();
            if (blobStore instanceof org.opensearch.common.blobstore.fs.FsBlobStore) {
                String path = ((org.opensearch.common.blobstore.fs.FsBlobStore) blobStore).path().toString();
                try {
                    path = java.nio.file.Path.of(path).toRealPath().toString();
                } catch (java.io.IOException e) {
                    logger.warn("[TieredStoragePlugin] could not resolve real path for {}: {}", path, e.getMessage());
                }
                return "{\"root\":\"" + escapeJson(path) + "\"}";
            }
        }
        throw new IllegalStateException("[TieredStoragePlugin] fs repository is not backed by FsBlobStore");
    }

    private String buildS3Config(org.opensearch.common.settings.Settings settings) {
        StringBuilder json = new StringBuilder("{");
        appendJsonField(json, "bucket", settings.get("bucket"), true);
        appendJsonFieldIfPresent(json, "prefix", settings.get("base_path"));
        appendJsonFieldIfPresent(json, "region", settings.get("region"));
        appendJsonFieldIfPresent(json, "endpoint", settings.get("endpoint"));

        // Credentials — pass if explicitly set in repo settings
        appendJsonFieldIfPresent(json, "access_key_id", settings.get("access_key"));
        appendJsonFieldIfPresent(json, "secret_access_key", settings.get("secret_key"));

        // Encryption
        String encType = settings.get("server_side_encryption_type");
        if (encType != null && !encType.isEmpty() && !"bucket_default".equals(encType)) {
            appendJsonFieldIfPresent(json, "encryption", encType);
            appendJsonFieldIfPresent(json, "kms_key_id", settings.get("server_side_encryption_kms_key_id"));
        }

        json.append("}");
        return json.toString();
    }

    private String buildGcsConfig(org.opensearch.common.settings.Settings settings) {
        StringBuilder json = new StringBuilder("{");
        appendJsonField(json, "bucket", settings.get("bucket"), true);
        appendJsonFieldIfPresent(json, "prefix", settings.get("base_path"));
        appendJsonFieldIfPresent(json, "application_credentials", settings.get("application_name"));
        json.append("}");
        return json.toString();
    }

    private String buildAzureConfig(org.opensearch.common.settings.Settings settings) {
        StringBuilder json = new StringBuilder("{");
        appendJsonField(json, "container", settings.get("container"), true);
        appendJsonFieldIfPresent(json, "prefix", settings.get("base_path"));
        appendJsonFieldIfPresent(json, "account", settings.get("account"));
        appendJsonFieldIfPresent(json, "endpoint", settings.get("endpoint_suffix"));
        json.append("}");
        return json.toString();
    }

    /** Append a required JSON field. */
    private static void appendJsonField(StringBuilder json, String key, String value, boolean required) {
        if (value == null || value.isEmpty()) {
            if (required) {
                throw new IllegalStateException("[TieredStoragePlugin] missing required config: " + key);
            }
            return;
        }
        if (json.length() > 1) {
            json.append(",");
        }
        json.append("\"").append(key).append("\":\"").append(escapeJson(value)).append("\"");
    }

    /** Append an optional JSON field if the value is non-null and non-empty. */
    private static void appendJsonFieldIfPresent(StringBuilder json, String key, String value) {
        appendJsonField(json, key, value, false);
    }

    /** Minimal JSON string escaping. */
    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public void onIndexModule(IndexModule indexModule) {
        if (FeatureFlags.isEnabled(FeatureFlags.WRITABLE_WARM_INDEX_EXPERIMENTAL_FLAG)) {
            indexModule.addSearchOperationListener(new TieredStorageSearchSlowLog(indexModule.getIndexSettings()));
            indexModule.addSearchOperationListener(new StoredFieldsPrefetch(getPrefetchSettingsSupplier()));
        }
    }

    @Override
    public Collection<Module> createGuiceModules() {
        List<Module> modules = new ArrayList<>();
        if (FeatureFlags.isEnabled(FeatureFlags.WRITABLE_WARM_INDEX_EXPERIMENTAL_FLAG)) {
            modules.add(new AbstractModule() {
                @Override
                protected void configure() {
                    bind(HotToWarmTieringService.class).asEagerSingleton();
                    bind(WarmToHotTieringService.class).asEagerSingleton();
                    bind(TierActionMetrics.class).toInstance(tierActionMetrics);
                }
            });
        }
        return Collections.unmodifiableCollection(modules);
    }

    // --- NativeObjectStoreProvider implementation ---

    @Override
    public long getNativeObjectStorePointer() {
        ensureGlobalObjectStoreCreated();
        if (globalObjStoreDataPtr == 0) {
            throw new IllegalStateException("[TieredStoragePlugin] Global ObjectStore could not be created.");
        }
        return globalObjStoreDataPtr;
    }

    @Override
    public long getNativeObjectStoreVtablePointer() {
        ensureGlobalObjectStoreCreated();
        if (globalObjStoreVtablePtr == 0) {
            throw new IllegalStateException("[TieredStoragePlugin] Global ObjectStore could not be created.");
        }
        return globalObjStoreVtablePtr;
    }

    @Override
    public String getRemoteBaseDir() {
        throw new UnsupportedOperationException(
            "[TieredStoragePlugin] getRemoteBaseDir() is no longer supported with multi-repo. " +
            "Use per-shard repository resolution instead."
        );
    }

    @Override
    public void close() {
        if (globalRegistryPtr != 0) {
            // Final sweep before shutdown
            int pending = TieredStoreNative.registryPendingDeleteCount(globalRegistryPtr);
            if (pending > 0) {
                int deleted = TieredStoreNative.registrySweepPendingDeletes(globalRegistryPtr);
                logger.info("[TieredStoragePlugin] final sweep on close: deleted={}, remaining={}",
                    deleted, TieredStoreNative.registryPendingDeleteCount(globalRegistryPtr));
            }
            logger.info("[TieredStoragePlugin] destroying global FileRegistry, ptr={}", globalRegistryPtr);
            TieredStoreNative.registryLogSummary(globalRegistryPtr);
            TieredStoreNative.destroyFileRegistry(globalRegistryPtr);
            globalRegistryPtr = 0;
        }
        if (globalObjStoreDataPtr != 0 && globalObjStoreVtablePtr != 0) {
            logger.info("[TieredStoragePlugin] destroying global TieredObjectStore, data_ptr={}, vtable_ptr={}",
                globalObjStoreDataPtr, globalObjStoreVtablePtr);
            TieredStoreNative.destroyTieredObjectStore(globalObjStoreDataPtr, globalObjStoreVtablePtr);
            globalObjStoreDataPtr = 0;
            globalObjStoreVtablePtr = 0;
        }
    }
}
