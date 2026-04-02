/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.datafusion.jni;

import org.opensearch.vectorized.execution.jni.TieredStoreNativeBridge;

/**
 * DataFusion-side implementation of {@link TieredStoreNativeBridge}.
 * <p>
 * Native methods are declared here so they are resolved by DataFusion's
 * classloader, which owns the {@code .so}. The tiered-storage module
 * calls through the {@link TieredStoreNativeBridge} interface, which
 * dispatches to these native methods.
 */
public final class TieredStoreNativeBridgeImpl implements TieredStoreNativeBridge {

    @Override
    public void initLogger() {
        nativeInitLogger();
    }

    @Override
    public long[] createTieredObjectStore(long diskCacheBytes, String diskCacheDir) {
        return nativeCreateTieredObjectStore(diskCacheBytes, diskCacheDir);
    }

    @Override
    public void addRemoteStore(long registryPtr, String repoKey, String storeType, String configJson) {
        nativeAddRemoteStore(registryPtr, repoKey, storeType, configJson);
    }

    @Override
    public void destroyTieredObjectStore(long dataPtr, long vtablePtr) {
        nativeDestroyTieredObjectStore(dataPtr, vtablePtr);
    }

    @Override
    public void destroyFileRegistry(long registryPtr) {
        nativeDestroyFileRegistry(registryPtr);
    }

    @Override
    public void registryMarkSyncedToRemote(long registryPtr, String key, String remotePath, String repoKey) {
        nativeRegistryMarkSyncedToRemote(registryPtr, key, remotePath, repoKey);
    }

    @Override
    public void registryRegisterLocal(long registryPtr, String key, long size) {
        nativeRegistryRegisterLocal(registryPtr, key, size);
    }

    @Override
    public int registryAcquireRead(long registryPtr, String key) {
        return nativeRegistryAcquireRead(registryPtr, key);
    }

    @Override
    public int registryReleaseRead(long registryPtr, String key) {
        return nativeRegistryReleaseRead(registryPtr, key);
    }

    @Override
    public void registryRemove(long registryPtr, String key) {
        nativeRegistryRemove(registryPtr, key);
    }

    @Override
    public void registryMarkLocalDeleted(long registryPtr, String key) {
        nativeRegistryMarkLocalDeleted(registryPtr, key);
    }

    @Override
    public int registryFileCount(long registryPtr) {
        return nativeRegistryFileCount(registryPtr);
    }

    @Override
    public void registryLogSummary(long registryPtr) {
        nativeRegistryLogSummary(registryPtr);
    }

    @Override
    public int registryGetLocation(long registryPtr, String key) {
        return nativeRegistryGetLocation(registryPtr, key);
    }

    @Override
    public long registryGetActiveReads(long registryPtr, String key) {
        return nativeRegistryGetActiveReads(registryPtr, key);
    }

    @Override
    public long registryGetSize(long registryPtr, String key) {
        return nativeRegistryGetSize(registryPtr, key);
    }

    @Override
    public void registryAddPendingDelete(long registryPtr, String localPath) {
        nativeRegistryAddPendingDelete(registryPtr, localPath);
    }

    @Override
    public int registrySweepPendingDeletes(long registryPtr) {
        return nativeRegistrySweepPendingDeletes(registryPtr);
    }

    @Override
    public int registryPendingDeleteCount(long registryPtr) {
        return nativeRegistryPendingDeleteCount(registryPtr);
    }

    // --- Native method declarations (resolved from DataFusion's .so) ---

    private static native void nativeInitLogger();
    private static native long[] nativeCreateTieredObjectStore(long diskCacheBytes, String diskCacheDir);
    private static native void nativeAddRemoteStore(long registryPtr, String repoKey, String storeType, String configJson);
    private static native void nativeDestroyTieredObjectStore(long dataPtr, long vtablePtr);
    private static native void nativeDestroyFileRegistry(long registryPtr);
    private static native void nativeRegistryMarkSyncedToRemote(long registryPtr, String key, String remotePath, String repoKey);
    private static native void nativeRegistryRegisterLocal(long registryPtr, String key, long size);
    private static native int nativeRegistryAcquireRead(long registryPtr, String key);
    private static native int nativeRegistryReleaseRead(long registryPtr, String key);
    private static native void nativeRegistryRemove(long registryPtr, String key);
    private static native void nativeRegistryMarkLocalDeleted(long registryPtr, String key);
    private static native int nativeRegistryFileCount(long registryPtr);
    private static native void nativeRegistryLogSummary(long registryPtr);
    private static native int nativeRegistryGetLocation(long registryPtr, String key);
    private static native long nativeRegistryGetActiveReads(long registryPtr, String key);
    private static native long nativeRegistryGetSize(long registryPtr, String key);
    private static native void nativeRegistryAddPendingDelete(long registryPtr, String localPath);
    private static native int nativeRegistrySweepPendingDeletes(long registryPtr);
    private static native int nativeRegistryPendingDeleteCount(long registryPtr);
}
