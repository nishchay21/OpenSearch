/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

//! Shared helpers for building per-query `RuntimeEnv` instances.
//!
//! Every query needs a fresh `RuntimeEnv` that inherits the global caches
//! (file-metadata, file-statistics) and object-store registry, but uses a
//! query-specific list-files cache pre-populated with the shard's parquet
//! file listing. This module consolidates that logic so callers don't
//! duplicate the ~20-line `RuntimeEnvBuilder::from_runtime_env(...)` block.

use std::sync::Arc;

use datafusion::common::DataFusionError;
use datafusion::datasource::listing::ListingTableUrl;
use datafusion::execution::cache::cache_manager::{CacheManagerConfig, CachedFileList};
use datafusion::execution::cache::{CacheAccessor, DefaultListFilesCache};
use datafusion::execution::memory_pool::MemoryPool;
use datafusion::execution::runtime_env::{RuntimeEnv, RuntimeEnvBuilder};
use object_store::ObjectMeta;

use crate::api::DataFusionRuntime;

/// Builds a per-query `RuntimeEnv` from the global `DataFusionRuntime`.
///
/// The returned env shares the global file-metadata and file-statistics caches
/// but has its own list-files cache pre-populated with the given `object_metas`.
/// An optional per-query `MemoryPool` can be overlaid (the pool typically wraps
/// the global pool so global limits remain enforced).
///
/// # Arguments
/// * `runtime` — Global runtime holding the shared `RuntimeEnv`.
/// * `table_path` — Listing table URL used as the cache key for pre-population.
/// * `object_metas` — File listing to seed the per-query list-files cache.
/// * `memory_pool` — Optional per-query memory pool to install.
///
/// # Errors
/// Returns `DataFusionError` if the underlying `RuntimeEnvBuilder::build()` fails.
pub fn build_query_runtime_env(
    runtime: &DataFusionRuntime,
    table_path: &ListingTableUrl,
    object_metas: &[ObjectMeta],
    memory_pool: Option<Arc<dyn MemoryPool>>,
) -> Result<Arc<RuntimeEnv>, DataFusionError> {
    // Pre-populate the list-files cache so DataFusion skips the object-store
    // list call and uses the snapshot passed from Java.
    let list_file_cache = Arc::new(DefaultListFilesCache::default());
    let table_scoped_path = datafusion::execution::cache::TableScopedPath {
        table: None,
        path: table_path.prefix().clone(),
    };
    list_file_cache.put(&table_scoped_path, CachedFileList::new(object_metas.to_vec()));

    let mut builder = RuntimeEnvBuilder::from_runtime_env(&runtime.runtime_env)
        .with_object_store_registry(Arc::new(
            datafusion::execution::object_store::DefaultObjectStoreRegistry::new(),
        ))
        .with_cache_manager(
            CacheManagerConfig::default()
                .with_list_files_cache(Some(list_file_cache))
                .with_file_metadata_cache(Some(
                    runtime.runtime_env.cache_manager.get_file_metadata_cache(),
                ))
                .with_metadata_cache_limit(
                    runtime.runtime_env.cache_manager.get_metadata_cache_limit(),
                )
                .with_files_statistics_cache(
                    runtime.runtime_env.cache_manager.get_file_statistic_cache(),
                ),
        );

    if let Some(pool) = memory_pool {
        builder = builder.with_memory_pool(pool);
    }

    let runtime_env = builder.build()?;
    Ok(Arc::from(runtime_env))
}
