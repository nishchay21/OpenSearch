/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.storage.jni;

import org.opensearch.common.util.FeatureFlags;
import org.opensearch.vectorized.execution.jni.SharedNativeLibrary;
import org.opensearch.vectorized.execution.jni.TieredStoreNativeBridge;

/**
 * Tiered storage entry point for native ObjectStore and FileRegistry operations.
 * <p>
 * Delegates to a {@link TieredStoreNativeBridge} implementation registered by
 * the DataFusion plugin via {@link SharedNativeLibrary}. The bridge dispatches
 * calls to native methods in DataFusion's classloader, which owns the
 * {@code .so}.
 */
public final class TieredStoreNative {

    /** File location constants matching Rust {@code FileLocation} enum. */
    public static final int LOCATION_LOCAL = 0;
    public static final int LOCATION_REMOTE = 1;
    public static final int LOCATION_BOTH = 2;
    public static final int LOCATION_NOT_FOUND = -1;

    private TieredStoreNative() {}

    private static volatile TieredStoreNativeBridge cachedBridge;

    private static TieredStoreNativeBridge bridge() {
        TieredStoreNativeBridge b = cachedBridge;
        if (b != null) {
            return b;
        }
        b = SharedNativeLibrary.get(
                TieredStoreNativeBridge.REGISTRY_KEY, TieredStoreNativeBridge.class);
        if (b != null) {
            cachedBridge = b;
        }
        return b;
    }

    /**
     * Returns {@code true} if the native bridge is available.
     */
    public static boolean isAvailable() {
        return FeatureFlags.isEnabled(FeatureFlags.WRITABLE_WARM_INDEX_EXPERIMENTAL_FLAG)
            && bridge() != null;
    }

    /**
     * Ensure the native library is ready. Called lazily before the first
     * native operation. Returns silently if the bridge is not available.
     */
    public static void ensureLoaded() {
        TieredStoreNativeBridge b = bridge();
        if (b == null) {
            return;
        }
        b.initLogger();
    }

    // --- ObjectStore lifecycle ---

    public static long[] createTieredObjectStore() {
        TieredStoreNativeBridge b = bridge();
        if (b == null) {
            return null;
        }
        return b.createTieredObjectStore();
    }

    public static void addRemoteStore(long registryPtr, String repoKey, String storeType, String configJson) {
        TieredStoreNativeBridge b = bridge();
        if (b == null) {
            return;
        }
        b.addRemoteStore(registryPtr, repoKey, storeType, configJson);
    }

    public static void destroyTieredObjectStore(long dataPtr, long vtablePtr) {
        TieredStoreNativeBridge b = bridge();
        if (b == null) {
            return;
        }
        b.destroyTieredObjectStore(dataPtr, vtablePtr);
    }

    public static void destroyFileRegistry(long registryPtr) {
        TieredStoreNativeBridge b = bridge();
        if (b == null) {
            return;
        }
        b.destroyFileRegistry(registryPtr);
    }

    // --- FileRegistry operations ---

    public static void registryMarkSyncedToRemote(long registryPtr, String key, String remotePath, String repoKey) {
        TieredStoreNativeBridge b = bridge();
        if (b == null) {
            return;
        }
        b.registryMarkSyncedToRemote(registryPtr, key, remotePath, repoKey);
    }

    public static void registryRegisterLocal(long registryPtr, String key, long size) {
        TieredStoreNativeBridge b = bridge();
        if (b == null) {
            return;
        }
        b.registryRegisterLocal(registryPtr, key, size);
    }

    public static int registryAcquireRead(long registryPtr, String key) {
        TieredStoreNativeBridge b = bridge();
        if (b == null) {
            return LOCATION_NOT_FOUND;
        }
        return b.registryAcquireRead(registryPtr, key);
    }

    public static int registryReleaseRead(long registryPtr, String key) {
        TieredStoreNativeBridge b = bridge();
        if (b == null) {
            return 0;
        }
        return b.registryReleaseRead(registryPtr, key);
    }

    public static void registryRemove(long registryPtr, String key) {
        TieredStoreNativeBridge b = bridge();
        if (b == null) {
            return;
        }
        b.registryRemove(registryPtr, key);
    }

    public static void registryMarkLocalDeleted(long registryPtr, String key) {
        TieredStoreNativeBridge b = bridge();
        if (b == null) {
            return;
        }
        b.registryMarkLocalDeleted(registryPtr, key);
    }

    public static int registryFileCount(long registryPtr) {
        TieredStoreNativeBridge b = bridge();
        if (b == null) {
            return 0;
        }
        return b.registryFileCount(registryPtr);
    }

    public static void registryLogSummary(long registryPtr) {
        TieredStoreNativeBridge b = bridge();
        if (b == null) {
            return;
        }
        b.registryLogSummary(registryPtr);
    }

    public static int registryGetLocation(long registryPtr, String key) {
        TieredStoreNativeBridge b = bridge();
        if (b == null) {
            return LOCATION_NOT_FOUND;
        }
        return b.registryGetLocation(registryPtr, key);
    }

    public static long registryGetActiveReads(long registryPtr, String key) {
        TieredStoreNativeBridge b = bridge();
        if (b == null) {
            return 0;
        }
        return b.registryGetActiveReads(registryPtr, key);
    }

    public static long registryGetSize(long registryPtr, String key) {
        TieredStoreNativeBridge b = bridge();
        if (b == null) {
            return -1;
        }
        return b.registryGetSize(registryPtr, key);
    }

    // --- Pending local deletes ---

    public static void registryAddPendingDelete(long registryPtr, String localPath) {
        TieredStoreNativeBridge b = bridge();
        if (b == null) {
            return;
        }
        b.registryAddPendingDelete(registryPtr, localPath);
    }

    public static int registrySweepPendingDeletes(long registryPtr) {
        TieredStoreNativeBridge b = bridge();
        if (b == null) {
            return 0;
        }
        return b.registrySweepPendingDeletes(registryPtr);
    }

    public static int registryPendingDeleteCount(long registryPtr) {
        TieredStoreNativeBridge b = bridge();
        if (b == null) {
            return 0;
        }
        return b.registryPendingDeleteCount(registryPtr);
    }
}
