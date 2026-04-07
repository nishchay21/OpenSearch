/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.vectorized.execution.jni;

/**
 * Contract for tiered storage native operations: ObjectStore lifecycle
 * and FileRegistry management.
 * <p>
 * Implemented by the plugin that owns the native library (DataFusion).
 * The implementation is discovered via {@link java.util.ServiceLoader}
 * and registered in {@link SharedNativeLibrary} under {@link #REGISTRY_KEY}.
 * <p>
 * This interface lives in the shared SPI so both plugins can reference
 * it without classloader visibility issues.
 */
public interface TieredStoreNativeBridge {

    String REGISTRY_KEY = "tiered_store_native_bridge";

    // -----------------------------------------------------------------------
    // ObjectStore lifecycle
    // -----------------------------------------------------------------------

    void initLogger();

    /**
     * Create a global {@code TieredObjectStore} with no remote stores.
     * Remote stores are added later via {@link #addRemoteStore}.
     *
     * @return {@code long[3]}: {@code [objectStoreDataPtr, objectStoreVtablePtr, registryPtr]}
     */
    long[] createTieredObjectStore();

    /**
     * Add a remote store for a repository. Creates the appropriate ObjectStore
     * based on store type and configuration. Idempotent.
     *
     * @param registryPtr the FileRegistry pointer
     * @param repoKey     logical repository name (e.g. "my-s3-repo")
     * @param storeType   one of "fs", "s3", "gcs", "azure"
     * @param configJson  JSON string with type-specific configuration
     */
    void addRemoteStore(long registryPtr, String repoKey, String storeType, String configJson);

    void destroyTieredObjectStore(long dataPtr, long vtablePtr);

    void destroyFileRegistry(long registryPtr);

    // -----------------------------------------------------------------------
    // FileRegistry
    // -----------------------------------------------------------------------

    void registryMarkSyncedToRemote(long registryPtr, String key, String remotePath, String repoKey);

    void registryRegisterLocal(long registryPtr, String key, long size);

    /** @return {@code 0}=LOCAL, {@code 1}=REMOTE, {@code 2}=BOTH */
    int registryAcquireRead(long registryPtr, String key);

    int registryReleaseRead(long registryPtr, String key);

    void registryRemove(long registryPtr, String key);

    void registryMarkLocalDeleted(long registryPtr, String key);

    int registryFileCount(long registryPtr);

    void registryLogSummary(long registryPtr);

    /** @return {@code 0}=LOCAL, {@code 1}=REMOTE, {@code 2}=BOTH, {@code -1}=not found */
    int registryGetLocation(long registryPtr, String key);

    /** @return active read count for the given file, or 0 if not found */
    long registryGetActiveReads(long registryPtr, String key);

    /** @return file size in bytes, or -1 if not found in registry */
    long registryGetSize(long registryPtr, String key);


    // -----------------------------------------------------------------------
    // Pending local deletes
    // -----------------------------------------------------------------------

    /**
     * Add a local file path to the pending deletion set.
     * Called when a file is marked REMOTE but can't be deleted immediately
     * (active readers) or when the immediate delete attempt fails.
     *
     * @param registryPtr the FileRegistry pointer
     * @param localPath   absolute local file path (with leading "/")
     */
    void registryAddPendingDelete(long registryPtr, String localPath);

    /**
     * Sweep all pending local deletes. For each pending file that is REMOTE-only
     * with 0 active reads, attempts to delete the local file.
     *
     * @return number of files successfully deleted
     */
    int registrySweepPendingDeletes(long registryPtr);

    /** @return number of files currently pending local deletion */
    int registryPendingDeleteCount(long registryPtr);

}
