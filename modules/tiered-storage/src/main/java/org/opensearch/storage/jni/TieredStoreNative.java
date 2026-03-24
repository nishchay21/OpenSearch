/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.storage.jni;

import org.opensearch.vectorized.execution.jni.SharedNativeLibrary;
import org.opensearch.vectorized.execution.jni.TieredStoreNativeBridge;

/**
 * Tiered storage entry point for native ObjectStore and FileRegistry operations.
 * <p>
 * Delegates to a {@link TieredStoreNativeBridge} implementation registered by
 * the DataFusion plugin via {@link SharedNativeLibrary}. The bridge dispatches
 * calls to native methods in DataFusion's classloader, which owns the {@code .so}.
 */
public final class TieredStoreNative {

    /** File location constants matching Rust {@code FileLocation} enum. */
    public static final int LOCATION_LOCAL = 0;
    public static final int LOCATION_REMOTE = 1;
    public static final int LOCATION_BOTH = 2;
    public static final int LOCATION_NOT_FOUND = -1;

    private static volatile TieredStoreNativeBridge bridge;

    private TieredStoreNative() {}

    private static TieredStoreNativeBridge bridge() {
        TieredStoreNativeBridge b = bridge;
        if (b != null) {
            return b;
        }
        // Lazy lookup from the shared registry
        b = SharedNativeLibrary.get(TieredStoreNativeBridge.REGISTRY_KEY, TieredStoreNativeBridge.class);
        if (b == null) {
            throw new IllegalStateException(
                "[TieredStoreNative] Native bridge not available. "
                    + "The DataFusion plugin must be installed and initialized before tiered storage can be used."
            );
        }
        bridge = b;
        return b;
    }

    /**
     * Ensure the native library is ready. Called lazily before the first
     * native operation.
     *
     * @throws IllegalStateException if the DataFusion plugin has not registered the bridge
     */
    public static void ensureLoaded() {
        bridge().initLogger();
    }

    // --- ObjectStore lifecycle ---

    public static long[] createTieredObjectStore() {
        return bridge().createTieredObjectStore();
    }

    public static void addRemoteStore(long registryPtr, String repoKey, String storeType, String configJson) {
        bridge().addRemoteStore(registryPtr, repoKey, storeType, configJson);
    }

    public static void destroyTieredObjectStore(long dataPtr, long vtablePtr) {
        bridge().destroyTieredObjectStore(dataPtr, vtablePtr);
    }

    public static void destroyFileRegistry(long registryPtr) {
        bridge().destroyFileRegistry(registryPtr);
    }

    // --- FileRegistry operations ---

    public static void registryMarkSyncedToRemote(long registryPtr, String key, String remotePath, String repoKey) {
        bridge().registryMarkSyncedToRemote(registryPtr, key, remotePath, repoKey);
    }

    public static void registryRegisterLocal(long registryPtr, String key, long size) {
        bridge().registryRegisterLocal(registryPtr, key, size);
    }

    public static int registryAcquireRead(long registryPtr, String key) {
        return bridge().registryAcquireRead(registryPtr, key);
    }

    public static int registryReleaseRead(long registryPtr, String key) {
        return bridge().registryReleaseRead(registryPtr, key);
    }

    public static void registryRemove(long registryPtr, String key) {
        bridge().registryRemove(registryPtr, key);
    }

    public static void registryMarkLocalDeleted(long registryPtr, String key) {
        bridge().registryMarkLocalDeleted(registryPtr, key);
    }

    public static int registryFileCount(long registryPtr) {
        return bridge().registryFileCount(registryPtr);
    }

    public static void registryLogSummary(long registryPtr) {
        bridge().registryLogSummary(registryPtr);
    }

    public static int registryGetLocation(long registryPtr, String key) {
        return bridge().registryGetLocation(registryPtr, key);
    }

    public static long registryGetActiveReads(long registryPtr, String key) {
        return bridge().registryGetActiveReads(registryPtr, key);
    }

    public static long registryGetSize(long registryPtr, String key) {
        return bridge().registryGetSize(registryPtr, key);
    }

    // --- Pending local deletes ---

    public static void registryAddPendingDelete(long registryPtr, String localPath) {
        bridge().registryAddPendingDelete(registryPtr, localPath);
    }

    public static int registrySweepPendingDeletes(long registryPtr) {
        return bridge().registrySweepPendingDeletes(registryPtr);
    }

    public static int registryPendingDeleteCount(long registryPtr) {
        return bridge().registryPendingDeleteCount(registryPtr);
    }
}
