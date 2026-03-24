/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.plugins;

import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.core.common.io.stream.NamedWriteableRegistry;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.env.Environment;
import org.opensearch.env.NodeEnvironment;
import org.opensearch.index.IndexSettings;
import org.opensearch.index.engine.SearchExecEngine;
import org.opensearch.index.engine.exec.FileMetadata;
import org.opensearch.index.shard.ShardPath;
import org.opensearch.repositories.RepositoriesService;
import org.opensearch.script.ScriptService;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.client.Client;
import org.opensearch.plugins.spi.vectorized.DataFormat;
import org.opensearch.plugins.spi.vectorized.DataSourceCodec;
import org.opensearch.vectorized.execution.jni.NativeObjectStoreProvider;
import org.opensearch.watcher.ResourceWatcherService;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public interface SearchEnginePlugin extends SearchPlugin{

    /**
     * Make dataSourceCodecs available for the DataSourceAwarePlugin(s)
     */
    default Collection<Object> createComponents(
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
        Map<DataFormat, DataSourceCodec> dataSourceCodecs
    ) {
        return createComponents(client, clusterService, threadPool, resourceWatcherService,
            scriptService, xContentRegistry, environment, nodeEnvironment, namedWriteableRegistry,
            indexNameExpressionResolver, repositoriesServiceSupplier, dataSourceCodecs, null);
    }

    /**
     * Create components with optional native object store provider from tiered storage.
     */
    default Collection<Object> createComponents(
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
        Map<DataFormat, DataSourceCodec> dataSourceCodecs,
        NativeObjectStoreProvider nativeObjectStoreProvider
    ) {
        return Collections.emptyList();
    }

    List<DataFormat> getSupportedFormats();

    SearchExecEngine<?,?,?,?> createEngine(DataFormat dataFormat, Collection<FileMetadata> formatCatalogSnapshot, ShardPath shardPath) throws IOException;

    /**
     * Create engine with index settings awareness. Engines can inspect the settings
     * to determine their behavior (e.g. warm indices may register an object store
     * for remote reads, optimized indices may use different caching strategies).
     */
    default SearchExecEngine<?,?,?,?> createEngine(DataFormat dataFormat, Collection<FileMetadata> formatCatalogSnapshot, ShardPath shardPath, IndexSettings indexSettings) throws IOException {
        return createEngine(dataFormat, formatCatalogSnapshot, shardPath);
    }
}
