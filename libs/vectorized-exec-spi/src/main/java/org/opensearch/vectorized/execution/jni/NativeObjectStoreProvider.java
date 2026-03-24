/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.vectorized.execution.jni;

/**
 * Provider for a native ObjectStore.
 * Implemented by plugins (e.g. TieredStoragePlugin) that create a native ObjectStore
 * to be registered into the execution runtime.
 *
 * The native ObjectStore is represented as a fat pointer (data + vtable) to
 * Arc&lt;dyn ObjectStore&gt;. Both components are needed to reconstruct the trait object.
 */
public interface NativeObjectStoreProvider {

    /**
     * Returns the data component of the native fat pointer to Arc&lt;dyn ObjectStore&gt;.
     * @return data pointer, or 0 if not initialized
     */
    long getNativeObjectStorePointer();

    /**
     * Returns the vtable component of the native fat pointer to Arc&lt;dyn ObjectStore&gt;.
     * @return vtable pointer, or 0 if not initialized
     */
    long getNativeObjectStoreVtablePointer();

    /**
     * Returns the remote base directory used by this object store.
     */
    String getRemoteBaseDir();
}
