/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.datafusion.jni.handle;

import org.opensearch.datafusion.jni.NativeBridge;
import org.opensearch.vectorized.execution.jni.NativeHandle;

/**
 * Type-safe handle for native runtime environment.
 */
public final class GlobalRuntimeHandle extends NativeHandle {

    public GlobalRuntimeHandle(long memoryLimit, long cacheManagerConfigPtr, String spillDir, long spillLimit) {
        super(NativeBridge.createGlobalRuntime(memoryLimit, cacheManagerConfigPtr, spillDir, spillLimit));
    }

    /**
     * Constructor with tiered ObjectStore — registers file:// override for tiered storage reads.
     * @param objStoreDataPtr data component of the native fat pointer
     * @param objStoreVtablePtr vtable component of the native fat pointer
     */
    public GlobalRuntimeHandle(long memoryLimit, long cacheManagerConfigPtr, String spillDir, long spillLimit, long objStoreDataPtr, long objStoreVtablePtr) {
        super(NativeBridge.createGlobalRuntimeWithTieredStore(memoryLimit, cacheManagerConfigPtr, spillDir, spillLimit, objStoreDataPtr, objStoreVtablePtr));
    }

    /**
     * Closes the runtime environment and releases any associated resources.
     */
    @Override
    protected void doClose() {
        NativeBridge.closeGlobalRuntime(ptr);
    }
}
