/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

//! [`TieredObjectStore`] — a single `ObjectStore` implementation that combines:
//!
//! 1. **Foyer disk cache** (optional, via [`FoyerCacheManager`]): intercepts
//!    `get_range`/`get_ranges` to serve cached byte ranges from local NVMe
//!    before hitting any storage backend.
//! 2. **Local NVMe dispatch**: files whose [`FileRegistry`] state is `Local` or
//!    `Both` are read from the local filesystem.
//! 3. **Remote store dispatch**: files whose state is `Remote` or `Both` are
//!    fetched from the appropriate S3/GCS/Azure store via the registry.
//!
//! ## Cache ownership
//!
//! The cache is owned by a [`FoyerCacheManager`] (which holds both the Foyer
//! `HybridCache` and the per-file key index for precise eviction). `TieredObjectStore`
//! holds an `Option<Arc<FoyerCacheManager>>` so cache-disabled deployments have
//! zero overhead.
//!
//! ## Log prefixes
//!
//! Page-cache log lines use `[FOYER-PAGE-CACHE]`; routing lines use
//! `[TieredObjectStore]`.

use async_trait::async_trait;
use bytes::Bytes;
use futures::stream::BoxStream;
use futures::StreamExt;
use object_store::{
    GetOptions, GetResult, ListResult, MultipartUpload, ObjectMeta, ObjectStore,
    PutMultipartOptions, PutOptions, PutPayload, PutResult, Result,
    local::LocalFileSystem, path::Path as ObjectPath,
};
use std::fmt::{self, Display, Formatter};
use std::ops::Range;
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use vectorized_exec_spi::log_info;

use super::file_registry::{FileLocation, FileRegistry};
use super::foyer_cache_manager::FoyerCacheManager;

/// Global tiered object store registered with DataFusion for the `file://` scheme.
///
/// Dispatches reads to local filesystem or the appropriate remote store based on
/// per-file state in the [`FileRegistry`]. When a [`FoyerCacheManager`] is provided,
/// all `get_range`/`get_ranges` calls are intercepted and served from the disk cache
/// before hitting local NVMe or remote storage.
#[derive(Debug)]
pub struct TieredObjectStore {
    local_fs: Arc<LocalFileSystem>,
    registry: Arc<FileRegistry>,
    remote_reads: AtomicU64,
    passthrough_reads: AtomicU64,

    /// Optional Foyer disk page cache manager.
    /// When `Some`, `get_range`/`get_ranges` check the cache before any I/O.
    foyer: Option<Arc<FoyerCacheManager>>,
}

impl TieredObjectStore {
    /// Create a new TieredObjectStore without a page cache.
    pub fn new() -> (Self, Arc<FileRegistry>) {
        Self::new_with_cache(None)
    }

    /// Create a new TieredObjectStore with an optional Foyer cache manager.
    pub fn new_with_cache(foyer: Option<Arc<FoyerCacheManager>>) -> (Self, Arc<FileRegistry>) {
        log_info!(
            "[TieredObjectStore] created (foyer_cache={})",
            if foyer.is_some() { "enabled" } else { "disabled" }
        );
        let registry = Arc::new(FileRegistry::new());
        let local_fs = LocalFileSystem::new_with_prefix("/")
            .expect("Failed to create LocalFileSystem with root prefix");
        let store = Self {
            local_fs: Arc::new(local_fs),
            registry: Arc::clone(&registry),
            remote_reads: AtomicU64::new(0),
            passthrough_reads: AtomicU64::new(0),
            foyer,
        };
        (store, registry)
    }

    // ── Path normalisation ─────────────────────────────────────────

    /// Strip leading `/` to produce the Foyer cache key prefix.
    fn cache_path(location: &ObjectPath) -> String {
        let s = location.as_ref();
        if s.starts_with('/') { s[1..].to_string() } else { s.to_string() }
    }
}

impl Display for TieredObjectStore {
    fn fmt(&self, f: &mut Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "TieredObjectStore(remote_reads={}, passthrough={}, tracked_files={}, remote_stores={}, foyer_cache={})",
            self.remote_reads.load(Ordering::Relaxed),
            self.passthrough_reads.load(Ordering::Relaxed),
            self.registry.file_count(),
            self.registry.remote_store_count(),
            if self.foyer.is_some() { "enabled" } else { "disabled" },
        )
    }
}

#[async_trait]
impl ObjectStore for TieredObjectStore {
    async fn put_opts(
        &self, location: &ObjectPath, payload: PutPayload, opts: PutOptions,
    ) -> Result<PutResult> {
        let path_str = location.as_ref();
        let size = payload.content_length() as u64;
        log_info!("[TieredObjectStore] put_opts WRITE_LOCAL: file={}, size={}", path_str, size);
        let result = self.local_fs.put_opts(location, payload, opts).await;
        if result.is_ok() {
            self.registry.register_local(path_str, size);
        }
        result
    }

    async fn put_multipart_opts(
        &self, location: &ObjectPath, opts: PutMultipartOptions,
    ) -> Result<Box<dyn MultipartUpload>> {
        log_info!("[TieredObjectStore] put_multipart_opts: file={}", location);
        self.local_fs.put_multipart_opts(location, opts).await
    }

    async fn get_opts(&self, location: &ObjectPath, options: GetOptions) -> Result<GetResult> {
        let path_str = location.as_ref();
        let remote_info = self.registry.get_remote_path(path_str)
            .and_then(|rp| self.registry.get_remote_store_for_file(path_str).map(|s| (rp, s)));

        if let Some((remote_path, remote_store)) = remote_info {
            let count = self.remote_reads.fetch_add(1, Ordering::Relaxed) + 1;
            let loc = self.registry.get_location(path_str).unwrap_or(FileLocation::Local);
            let repo_key = self.registry.get_repo_key(path_str).unwrap_or_default();
            log_info!(
                "[TieredObjectStore] get_opts #{} DISPATCHING_TO_REMOTE location={} remote_path={} repo={} registry_state={}",
                count, location, remote_path, repo_key, loc
            );
            let remote_location = ObjectPath::from(remote_path.clone());
            let result = remote_store.get_opts(&remote_location, options).await;
            match &result {
                Ok(r)  => log_info!("[TieredObjectStore] get_opts #{} REMOTE_READ_SUCCESS remote_path={} bytes={}", count, remote_path, r.meta.size),
                Err(e) => log_info!("[TieredObjectStore] get_opts #{} REMOTE_READ_FAILED remote_path={} error={}", count, remote_path, e),
            }
            result
        } else {
            let count = self.passthrough_reads.fetch_add(1, Ordering::Relaxed) + 1;
            log_info!("[TieredObjectStore] get_opts #{} READ_FROM_LOCAL: {}", count, location);
            self.local_fs.get_opts(location, options).await
        }
    }

    // ── Range reads: intercepted by FoyerCacheManager (if configured) ─────

    async fn get_range(&self, location: &ObjectPath, range: Range<u64>) -> Result<Bytes> {
        let path_str = Self::cache_path(location);
        let start = range.start as usize;
        let end   = range.end   as usize;

        if let Some(foyer) = &self.foyer {
            if let Some(cached) = foyer.get(&path_str, start, end).await {
                log_info!(
                    "[FOYER-PAGE-CACHE] get_range HIT: path={}, range={}..{}, size={}B",
                    path_str, start, end, cached.len()
                );
                return Ok(cached);
            }
            log_info!(
                "[FOYER-PAGE-CACHE] get_range MISS → backing store: path={}, range={}..{}",
                path_str, start, end
            );
            let bytes = self.backing_get_range(location, range).await?;
            log_info!(
                "[FOYER-PAGE-CACHE] get_range PUT: path={}, range={}..{}, size={}B",
                path_str, start, end, bytes.len()
            );
            foyer.put(path_str, start, end, bytes.clone());
            Ok(bytes)
        } else {
            self.backing_get_range(location, range).await
        }
    }

    async fn get_ranges(&self, location: &ObjectPath, ranges: &[Range<u64>]) -> Result<Vec<Bytes>> {
        let path_str = Self::cache_path(location);

        if let Some(foyer) = &self.foyer {
            let mut results: Vec<Option<Bytes>> = vec![None; ranges.len()];
            let mut miss_indices: Vec<usize> = Vec::new();

            for (i, range) in ranges.iter().enumerate() {
                let start = range.start as usize;
                let end   = range.end   as usize;
                if let Some(cached) = foyer.get(&path_str, start, end).await {
                    log_info!(
                        "[FOYER-PAGE-CACHE] get_ranges HIT [{}/{}]: path={}, range={}..{}, size={}B",
                        i + 1, ranges.len(), path_str, start, end, cached.len()
                    );
                    results[i] = Some(cached);
                } else {
                    miss_indices.push(i);
                }
            }

            if miss_indices.is_empty() {
                log_info!("[FOYER-PAGE-CACHE] get_ranges ALL HIT: path={}, {} ranges", path_str, ranges.len());
                return Ok(results.into_iter().map(|b| b.unwrap()).collect());
            }

            log_info!(
                "[FOYER-PAGE-CACHE] get_ranges PARTIAL MISS: path={}, {}/{} ranges need fetch",
                path_str, miss_indices.len(), ranges.len()
            );
            let miss_ranges: Vec<Range<u64>> = miss_indices.iter().map(|&i| ranges[i].clone()).collect();
            let fetched = self.backing_get_ranges(location, &miss_ranges).await?;

            for (miss_idx, fetched_bytes) in miss_indices.iter().zip(fetched.into_iter()) {
                let range = &ranges[*miss_idx];
                let start = range.start as usize;
                let end   = range.end   as usize;
                log_info!(
                    "[FOYER-PAGE-CACHE] get_ranges PUT: path={}, range={}..{}, size={}B",
                    path_str, start, end, fetched_bytes.len()
                );
                foyer.put(path_str.clone(), start, end, fetched_bytes.clone());
                results[*miss_idx] = Some(fetched_bytes);
            }

            Ok(results.into_iter().map(|b| b.unwrap()).collect())
        } else {
            self.backing_get_ranges(location, ranges).await
        }
    }

    async fn head(&self, location: &ObjectPath) -> Result<ObjectMeta> {
        let path_str = location.as_ref();
        match self.local_fs.head(location).await {
            Ok(meta) => {
                self.registry.register(path_str, FileLocation::Local, meta.size as u64);
                let loc = self.registry.get_location(path_str).unwrap_or(FileLocation::Local);
                log_info!("[TieredObjectStore] head: {} location={}", location, loc);
                Ok(meta)
            }
            Err(local_err) => {
                let remote_info = self.registry.get_remote_path(path_str)
                    .and_then(|rp| self.registry.get_remote_store_for_file(path_str).map(|s| (rp, s)));
                if let Some((remote_path, remote_store)) = remote_info {
                    let remote_loc = ObjectPath::from(remote_path);
                    match remote_store.head(&remote_loc).await {
                        Ok(mut meta) => {
                            self.registry.register(path_str, FileLocation::Remote, meta.size as u64);
                            log_info!("[TieredObjectStore] head: {} location=REMOTE (via registry)", location);
                            meta.location = location.clone();
                            Ok(meta)
                        }
                        Err(remote_err) => {
                            log_info!("[TieredObjectStore] head: {} NOT_FOUND (local and remote)", location);
                            Err(remote_err)
                        }
                    }
                } else {
                    log_info!("[TieredObjectStore] head: {} NOT_FOUND (local only, no remote store)", location);
                    Err(local_err)
                }
            }
        }
    }

    async fn delete(&self, location: &ObjectPath) -> Result<()> {
        let path_str = location.as_ref();
        let cache_path = Self::cache_path(location);

        // Evict Foyer cache entries for this file before deleting from disk.
        if let Some(foyer) = &self.foyer {
            foyer.evict_file(&cache_path);
        }

        if self.registry.can_delete_local(path_str) {
            log_info!("[TieredObjectStore] delete: {} (on remote, no active reads — safe to delete local)", path_str);
            let result = self.local_fs.delete(location).await;
            if result.is_ok() {
                self.registry.mark_local_deleted(path_str);
            }
            return result;
        }
        log_info!("[TieredObjectStore] delete: {}", location);
        let result = self.local_fs.delete(location).await;
        if result.is_ok() {
            self.registry.remove(path_str);
        }
        result
    }

    fn list(&self, prefix: Option<&ObjectPath>) -> BoxStream<'static, Result<ObjectMeta>> {
        log_info!("[TieredObjectStore] list: prefix={:?}", prefix);
        let local_fs = self.local_fs.clone();
        let registry = self.registry.clone();
        let prefix_owned = prefix.cloned();
        let stream = async_stream::stream! {
            let mut local_stream = local_fs.list(prefix_owned.as_ref());
            while let Some(result) = local_stream.next().await {
                match result {
                    Ok(meta) => {
                        let key = meta.location.as_ref().to_string();
                        registry.register_from_meta(&meta, FileLocation::Local);
                        let loc = registry.get_location(&key).unwrap_or(FileLocation::Local);
                        log_info!("[TieredObjectStore] list: {} location={} size={}", key, loc, meta.size);
                        yield Ok(meta);
                    }
                    Err(e) => yield Err(e),
                }
            }
            registry.log_summary();
        };
        Box::pin(stream)
    }

    async fn list_with_delimiter(&self, prefix: Option<&ObjectPath>) -> Result<ListResult> {
        self.local_fs.list_with_delimiter(prefix).await
    }

    async fn copy(&self, from: &ObjectPath, to: &ObjectPath) -> Result<()> {
        self.local_fs.copy(from, to).await
    }

    async fn copy_if_not_exists(&self, from: &ObjectPath, to: &ObjectPath) -> Result<()> {
        self.local_fs.copy_if_not_exists(from, to).await
    }
}

// ── Private backing-store helpers (bypass cache) ──────────────────────────────

impl TieredObjectStore {
    async fn backing_get_range(&self, location: &ObjectPath, range: Range<u64>) -> Result<Bytes> {
        let path_str = location.as_ref();
        let remote_info = self.registry.get_remote_path(path_str)
            .and_then(|rp| self.registry.get_remote_store_for_file(path_str).map(|s| (rp, s)));

        if let Some((remote_path, remote_store)) = remote_info {
            let count = self.remote_reads.fetch_add(1, Ordering::Relaxed) + 1;
            log_info!(
                "[TieredObjectStore] get_range #{} REMOTE: path={}, remote={}, range={}..{}",
                count, path_str, remote_path, range.start, range.end
            );
            remote_store.get_range(&ObjectPath::from(remote_path), range).await
        } else {
            let count = self.passthrough_reads.fetch_add(1, Ordering::Relaxed) + 1;
            log_info!(
                "[TieredObjectStore] get_range #{} LOCAL: path={}, range={}..{}",
                count, path_str, range.start, range.end
            );
            self.local_fs.get_range(location, range).await
        }
    }

    async fn backing_get_ranges(&self, location: &ObjectPath, ranges: &[Range<u64>]) -> Result<Vec<Bytes>> {
        let path_str = location.as_ref();
        let remote_info = self.registry.get_remote_path(path_str)
            .and_then(|rp| self.registry.get_remote_store_for_file(path_str).map(|s| (rp, s)));

        if let Some((remote_path, remote_store)) = remote_info {
            let count = self.remote_reads.fetch_add(1, Ordering::Relaxed) + 1;
            log_info!(
                "[TieredObjectStore] get_ranges #{} REMOTE: path={}, remote={}, {} ranges",
                count, path_str, remote_path, ranges.len()
            );
            remote_store.get_ranges(&ObjectPath::from(remote_path), ranges).await
        } else {
            let count = self.passthrough_reads.fetch_add(1, Ordering::Relaxed) + 1;
            log_info!(
                "[TieredObjectStore] get_ranges #{} LOCAL: path={}, {} ranges",
                count, path_str, ranges.len()
            );
            self.local_fs.get_ranges(location, ranges).await
        }
    }
}
