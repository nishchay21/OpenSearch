/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.vectorized.execution.jni;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for sharing native library state across plugin classloaders.
 * <p>
 * In OpenSearch's plugin architecture each plugin gets its own
 * {@link ClassLoader}. This class lives in the shared SPI on the server
 * classloader and provides a meeting point for plugins that need to
 * share native functionality across classloader boundaries.
 * <p>
 * <b>Thread safety:</b> All methods are safe for concurrent use.
 */
public final class SharedNativeLibrary {

    private static final ConcurrentHashMap<String, Object> registry = new ConcurrentHashMap<>();

    private SharedNativeLibrary() {}

    /**
     * Register a service implementation that can be retrieved by other plugins.
     *
     * @param key   logical name for the service
     * @param value the service implementation
     */
    public static void register(String key, Object value) {
        registry.put(key, value);
    }

    /**
     * Retrieve a registered service.
     *
     * @param key  logical name for the service
     * @param type expected type
     * @return the service, or {@code null} if not registered
     */
    @SuppressWarnings("unchecked")
    public static <T> T get(String key, Class<T> type) {
        Object value = registry.get(key);
        if (value == null) {
            return null;
        }
        return type.cast(value);
    }

    /**
     * Check if a service has been registered.
     *
     * @param key logical name for the service
     * @return {@code true} if registered
     */
    public static boolean isRegistered(String key) {
        return registry.containsKey(key);
    }
}
