/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

//! Foyer-backed caching wrapper around any [`ObjectStore`].
//!
//! [`CachingObjectStore`] intercepts `get_range()` and `get_ranges()` calls —
//! the two methods DataFusion uses to fetch Parquet column chunk byte ranges.
//! All other methods are delegated transparently to the inner store.
//!
//! ## Two-tier read path
//!
//! ```text
//! DataFusion.get_range(file, 4096..8192)
//!   └── CachingObjectStore.get_range()
//!         ├── [FOYER-PAGE-CACHE] check L1-memory → HIT: return bytes (0 I/O)
//!         ├── [FOYER-PAGE-CACHE] check L2-disk   → HIT: return bytes (local NVMe)
//!         └── MISS: inner.get_range() → S3/local read
//!               └── [FOYER-PAGE-CACHE] PUT → L1-memory (async spill to L2-disk)
//! ```
//!
//! ## Log prefix
//!
//! All log lines produced by this module use `[FOYER-PAGE-CACHE]` for easy grepping.

use std::fmt;
use std::ops::Range;
use std::sync::Arc;

use async_trait::async_trait;
use bytes::Bytes;
use futures::stream::BoxStream;
use object_store::{
    path::Path, GetOptions, GetResult, ListResult, MultipartUpload, ObjectMeta, ObjectStore,
    PutMultipartOpts, PutOptions, PutPayload, PutResult,
};
use vectorized_exec_spi::{log_debug, log_info};

use crate::foyer_cache::FoyerDiskPageCache;

/// An [`ObjectStore`] wrapper that caches `get_range` / `get_ranges` results
/// in the Foyer hybrid (memory + disk) page cache.
pub struct CachingObjectStore {
    inner: Arc<dyn ObjectStore>,
    page_cache: Arc<FoyerDiskPageCache>,
}

impl fmt::Debug for CachingObjectStore {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "CachingObjectStore(inner={}, {})",
            self.inner, self.page_cache.disk_dir().display())
    }
}

impl fmt::Display for CachingObjectStore {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "CachingObjectStore({})", self.inner)
    }
}

impl CachingObjectStore {
    /// Wrap `inner` with Foyer-backed page caching.
    pub fn new(inner: Arc<dyn ObjectStore>, page_cache: Arc<FoyerDiskPageCache>) -> Self {
        log_info!(
            "[FOYER-PAGE-CACHE] CachingObjectStore created: inner={}, disk_dir={}, \
             mem_capacity={}B, disk_capacity={}B",
            inner,
            page_cache.disk_dir().display(),
            page_cache.memory_capacity_bytes(),
            page_cache.disk_capacity_bytes()
        );
        Self { inner, page_cache }
    }

    /// Strip the leading `/` so the cache key matches the FileRegistry key format.
    fn cache_path(location: &Path) -> String {
        let s = location.as_ref();
        if s.starts_with('/') { s[1..].to_string() } else { s.to_string() }
    }
}

#[async_trait]
impl ObjectStore for CachingObjectStore {
    // ── Write passthrough ──────────────────────────────────────────

    async fn put(&self, location: &Path, payload: PutPayload) -> object_store::Result<PutResult> {
        self.inner.put(location, payload).await
    }
    async fn put_opts(&self, location: &Path, payload: PutPayload, opts: PutOptions) -> object_store::Result<PutResult> {
        self.inner.put_opts(location, payload, opts).await
    }
    async fn put_multipart(&self, location: &Path) -> object_store::Result<Box<dyn MultipartUpload>> {
        self.inner.put_multipart(location).await
    }
    async fn put_multipart_opts(&self, location: &Path, opts: PutMultipartOpts) -> object_store::Result<Box<dyn MultipartUpload>> {
        self.inner.put_multipart_opts(location, opts).await
    }

    // ── Read passthrough (non-range) ───────────────────────────────

    async fn get(&self, location: &Path) -> object_store::Result<GetResult> {
        self.inner.get(location).await
    }
    async fn get_opts(&self, location: &Path, options: GetOptions) -> object_store::Result<GetResult> {
        self.inner.get_opts(location, options).await
    }
    async fn head(&self, location: &Path) -> object_store::Result<ObjectMeta> {
        self.inner.head(location).await
    }

    // ── Range reads: intercepted by Foyer page cache ───────────────

    /// Fetch a single byte range.
    /// Checks Foyer L1 (memory) then L2 (disk) before falling through to the inner store.
    async fn get_range(&self, location: &Path, range: Range<u64>) -> object_store::Result<Bytes> {
        let path_str = Self::cache_path(location);
        let start = range.start as usize;
        let end   = range.end   as usize;

        // L1+L2 lookup (async — disk I/O is async in Foyer)
        if let Some(cached) = self.page_cache.get(&path_str, start, end).await {
            log_info!(
                "[FOYER-PAGE-CACHE] get_range HIT: path={}, range={}..{}, size={}B",
                path_str, start, end, cached.len()
            );
            return Ok(cached);
        }

        // L1+L2 miss — fetch from inner store (local NVMe or S3/GCS/Azure)
        log_info!(
            "[FOYER-PAGE-CACHE] get_range MISS → inner store: path={}, range={}..{}",
            path_str, start, end
        );
        let bytes = self.inner.get_range(location, range).await?;

        // Populate cache (insert to L1; Foyer spills to L2 asynchronously)
        log_info!(
            "[FOYER-PAGE-CACHE] get_range PUT: path={}, range={}..{}, size={}B",
            path_str, start, end, bytes.len()
        );
        self.page_cache.put(path_str, start, end, bytes.clone());

        Ok(bytes)
    }

    /// Fetch multiple byte ranges in one call.
    /// Each range is looked up individually so partial cache hits are exploited.
    async fn get_ranges(&self, location: &Path, ranges: &[Range<u64>]) -> object_store::Result<Vec<Bytes>> {
        let path_str = Self::cache_path(location);

        let mut results: Vec<Option<Bytes>> = vec![None; ranges.len()];
        let mut miss_indices: Vec<usize> = Vec::new();

        // Check each range in the cache
        for (i, range) in ranges.iter().enumerate() {
            let start = range.start as usize;
            let end   = range.end   as usize;
            if let Some(cached) = self.page_cache.get(&path_str, start, end).await {
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
            log_info!(
                "[FOYER-PAGE-CACHE] get_ranges ALL HIT: path={}, {} ranges",
                path_str, ranges.len()
            );
            return Ok(results.into_iter().map(|b| b.unwrap()).collect());
        }

        // Fetch only the missing ranges from the inner store
        log_info!(
            "[FOYER-PAGE-CACHE] get_ranges PARTIAL MISS: path={}, {}/{} ranges need fetch",
            path_str, miss_indices.len(), ranges.len()
        );
        let miss_ranges: Vec<Range<u64>> = miss_indices.iter().map(|&i| ranges[i].clone()).collect();
        let fetched = self.inner.get_ranges(location, &miss_ranges).await?;

        for (miss_idx, fetched_bytes) in miss_indices.iter().zip(fetched.into_iter()) {
            let range = &ranges[*miss_idx];
            let start = range.start as usize;
            let end   = range.end   as usize;
            log_info!(
                "[FOYER-PAGE-CACHE] get_ranges PUT: path={}, range={}..{}, size={}B",
                path_str, start, end, fetched_bytes.len()
            );
            self.page_cache.put(path_str.clone(), start, end, fetched_bytes.clone());
            results[*miss_idx] = Some(fetched_bytes);
        }

        Ok(results.into_iter().map(|b| b.unwrap()).collect())
    }

    // ── Directory / listing — object_store 0.12 API ────────────────

    async fn delete(&self, location: &Path) -> object_store::Result<()> {
        self.inner.delete(location).await
    }

    fn list(&self, prefix: Option<&Path>) -> BoxStream<'static, object_store::Result<ObjectMeta>> {
        self.inner.list(prefix)
    }

    async fn list_with_delimiter(&self, prefix: Option<&Path>) -> object_store::Result<ListResult> {
        self.inner.list_with_delimiter(prefix).await
    }

    fn list_with_offset(&self, prefix: Option<&Path>, offset: &Path) -> BoxStream<'static, object_store::Result<ObjectMeta>> {
        self.inner.list_with_offset(prefix, offset)
    }

    async fn copy(&self, from: &Path, to: &Path) -> object_store::Result<()> {
        self.inner.copy(from, to).await
    }
    async fn rename(&self, from: &Path, to: &Path) -> object_store::Result<()> {
        self.inner.rename(from, to).await
    }
    async fn copy_if_not_exists(&self, from: &Path, to: &Path) -> object_store::Result<()> {
        self.inner.copy_if_not_exists(from, to).await
    }
    async fn rename_if_not_exists(&self, from: &Path, to: &Path) -> object_store::Result<()> {
        self.inner.rename_if_not_exists(from, to).await
    }
}
