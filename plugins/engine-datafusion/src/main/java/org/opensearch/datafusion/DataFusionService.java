/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.datafusion;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.lifecycle.AbstractLifecycleComponent;
import org.opensearch.datafusion.core.DataFusionRuntimeEnv;
import org.opensearch.datafusion.jni.NativeBridge;
import org.opensearch.datafusion.search.cache.CacheManager;
import org.opensearch.plugins.spi.vectorized.DataFormat;
import org.opensearch.plugins.spi.vectorized.DataSourceCodec;
import org.opensearch.vectorized.execution.jni.NativeObjectStoreProvider;

import java.util.Map;

/**
 * Service for managing DataFusion contexts and operations - essentially like SearchService
 */
public class DataFusionService extends AbstractLifecycleComponent {

    private static final Logger logger = LogManager.getLogger(DataFusionService.class);

    private final DataSourceRegistry dataSourceRegistry;
    private final DataFusionRuntimeEnv runtimeEnv;
    private final ClusterService clusterService;
    private volatile NativeObjectStoreProvider nativeObjectStoreProvider;
    private volatile boolean objectStoreRegistered = false;


    /**
     * Creates a new DataFusion service instance.
     */
    public DataFusionService(Map<DataFormat, DataSourceCodec> dataSourceCodecs, ClusterService clusterService, String spill_dir) {
        this.dataSourceRegistry = new DataSourceRegistry(dataSourceCodecs);
        this.clusterService = clusterService;

        // to verify jni
        String version = NativeBridge.getVersionInfo();
        this.runtimeEnv = new DataFusionRuntimeEnv(clusterService, spill_dir);
    }

    /**
     * Set the native object store provider for lazy registration.
     * The ObjectStore will be registered into the runtime when first needed
     * (after RepositoriesService is available).
     */
    public void setNativeObjectStoreProvider(NativeObjectStoreProvider provider) {
        this.nativeObjectStoreProvider = provider;
        logger.info("[DataFusionService] NativeObjectStoreProvider set — ObjectStore will be registered lazily");
    }

    /**
     * Register the TieredObjectStore into the DataFusion runtime.
     * Called only for warm+optimized indices where reads may need to go through
     * the tiered storage path. Hot indices skip this entirely.
     */
    public synchronized void ensureObjectStoreRegistered() {
        if (objectStoreRegistered || nativeObjectStoreProvider == null) {
            return;
        }
        long dataPtr = nativeObjectStoreProvider.getNativeObjectStorePointer();
        long vtablePtr = nativeObjectStoreProvider.getNativeObjectStoreVtablePointer();
        if (dataPtr != 0 && vtablePtr != 0) {
            NativeBridge.registerObjectStore(runtimeEnv.getPointer(), dataPtr, vtablePtr);
            objectStoreRegistered = true;
            logger.info("[DataFusionService] TieredObjectStore registered into runtime: data_ptr={}, vtable_ptr={}", dataPtr, vtablePtr);
        }
    }

    @Override
    protected void doStart() {
        logger.info("Starting DataFusion service");
        try {
            // Initialize the data source registry
            // Test that at least one data source is available
            if (!dataSourceRegistry.hasCodecs()) {
                logger.warn("No data sources available");
            } else {
                logger.info(
                    "DataFusion service started successfully with {} data sources: {}",
                    dataSourceRegistry.getCodecNames().size(),
                    dataSourceRegistry.getCodecNames()
                );

            }
        } catch (Exception e) {
            logger.error("Failed to start DataFusion service", e);
            throw new RuntimeException("Failed to initialize DataFusion service", e);
        }
    }

    @Override
    protected void doStop() {
        logger.info("Stopping DataFusion service");
        // Clear the Foyer page cache BEFORE shutting down the Tokio runtime.
        // If the runtime is shut down first, Foyer's background store tasks get
        // JoinError::Cancelled and foyer-storage panics at store.rs:151.
        // Calling clear() (which calls cache.close().await) drains Foyer's async
        // tasks cleanly while the runtime is still alive.
        if (runtimeEnv != null) {
            long runtimePtr = runtimeEnv.getPointer();
            if (runtimePtr != 0) {
                try {
                    org.opensearch.datafusion.jni.NativeBridge.cacheManagerClear(runtimePtr);
                    logger.info("[FOYER-PAGE-CACHE] page cache cleared before runtime shutdown");
                } catch (Exception e) {
                    logger.warn("[FOYER-PAGE-CACHE] error clearing page cache on shutdown: {}", e.getMessage());
                }
            }
        }
        runtimeEnv.close();
        logger.info("DataFusion service stopped");
    }

    @Override
    protected void doClose() {
        doStop();
    }


    public long getRuntimePointer() {
        return runtimeEnv.getPointer();
    }

    /**
     * Get version information from available codecs
     * @return JSON version string
     */
    public String getVersion() {
        StringBuilder version = new StringBuilder();
        version.append("{\"codecs\":[");

        boolean first = true;
        for (DataFormat engineName : this.dataSourceRegistry.getCodecNames()) {
            if (!first) {
                version.append(",");
            }
            version.append("{\"name\":\"").append(engineName).append("\"}");
            first = false;
        }

        version.append("]}");
        return version.toString();
    }

    public CacheManager getCacheManager() {
        return runtimeEnv.getCacheManager();
    }

    /**
     * Returns the configured disk capacity for the format (page) cache disk tier in bytes.
     * Reads {@code datafusion.page.cache.disk.capacity} from the cluster settings.
     * Used by {@code DiskBudgetManager} for startup validation and stats reporting.
     *
     * @return configured disk capacity in bytes, or {@code 0} if the format cache is disabled
     */
    public long getFormatCacheDiskCapacityBytes() {
        try {
            return clusterService.getClusterSettings()
                .get(org.opensearch.datafusion.search.cache.CacheSettings.PAGE_CACHE_DISK_CAPACITY)
                .getBytes();
        } catch (Exception e) {
            return 0L;
        }
    }
}
