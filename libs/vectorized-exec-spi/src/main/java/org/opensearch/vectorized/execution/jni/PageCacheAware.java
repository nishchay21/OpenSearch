/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.vectorized.execution.jni;

/**
 * Marker interface for plugins that can receive a {@link PageCacheProvider}.
 * <p>
 * Implemented by {@code TieredStoragePlugin} so that {@code Node.java} (in the
 * {@code server} module) can inject a page cache provider without needing
 * a compile-time dependency on the {@code modules/tiered-storage} module.
 * <p>
 * {@code Node.java} discovers plugins implementing this interface and calls
 * {@link #setPageCacheProvider(PageCacheProvider)} after the {@link PageCacheProvider}
 * (e.g., {@code DataFusionPlugin}) has been discovered.
 */
public interface PageCacheAware {

    /**
     * Inject the page cache provider.
     * Called by {@code Node.java} during node construction, after the plugin implementing
     * {@link PageCacheProvider} (e.g. {@code DataFusionPlugin}) has been initialized.
     *
     * @param provider the page cache provider, never null when this method is called
     */
    void setPageCacheProvider(PageCacheProvider provider);
}
