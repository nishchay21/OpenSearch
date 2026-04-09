/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

//! [`FoyerCacheManager`] — the single owner of Foyer disk-based page caching for
//! tiered Parquet reads.
//!
//! ## Responsibilities
//!
//! 1. **Cache lifecycle** — constructs and holds the [`FoyerDiskPageCache`].
//! 2. **Key index** — maintains a `DashMap<file_path → Vec<foyer_key>>` so that
//!    all cached byte ranges for a file can be evicted precisely when the file is
//!    deleted or tiered out, without waiting for LRU expiry.
//! 3. **Unified API** — `get`, `put`, `evict_file`, `clear` are the only entry
//!    points; callers do not need to know about Foyer internals.
//!
//! ## Ownership
//!
//! `FoyerCacheManager` is constructed once (via the
//! `nativeCreateFoyerCache` JNI function) and shared as an
//! `Arc<FoyerCacheManager>` into `TieredObjectStore`.
//!
//! ## Log prefix
//!
//! All log lines use `[FOYER-PAGE-CACHE]`.

use std::path::PathBuf;
use std::sync::Arc;

use bytes::Bytes;
use dashmap::DashMap;
use vectorized_exec_spi::{log_debug, log_info};

use super::foyer_cache::FoyerDiskPageCache;

/// Manages a Foyer disk-only page cache for Parquet byte ranges.
///
/// Owns both the [`FoyerDiskPageCache`] and the per-file key index that enables
/// precise eviction when a file is deleted.
#[derive(Debug)]
pub struct FoyerCacheManager {
    /// The underlying Foyer disk cache.
    cache: FoyerDiskPageCache,

    /// Maps normalised file path → list of Foyer key strings for that file.
    ///
    /// Populated on every `put()`. Drained by `evict_file()` so that all ranges
    /// for a deleted file are removed from Foyer precisely — no LRU wait.
    key_index: DashMap<String, Vec<String>>,
}

impl FoyerCacheManager {
    /// Create a new `FoyerCacheManager`.
    ///
    /// # Arguments
    /// * `disk_capacity_bytes` — total disk budget for the Foyer cache (e.g. 10 GB)
    /// * `disk_dir`            — local NVMe directory for Foyer data files
    pub fn new(disk_capacity_bytes: usize, disk_dir: impl Into<PathBuf>) -> Self {
        let cache = FoyerDiskPageCache::new(disk_capacity_bytes, disk_dir);
        log_info!(
            "[FOYER-PAGE-CACHE] FoyerCacheManager created: disk={}B, dir={}",
            cache.disk_capacity_bytes(),
            cache.disk_dir().display()
        );
        Self {
            cache,
            key_index: DashMap::new(),
        }
    }

    // ── Read ──────────────────────────────────────────────────────

    /// Async cache lookup. Returns `Some(Bytes)` on disk hit, `None` on miss.
    pub async fn get(&self, path: &str, start: usize, end: usize) -> Option<Bytes> {
        self.cache.get(path, start, end).await
    }

    // ── Write ─────────────────────────────────────────────────────

    /// Insert a byte range into the cache and record its key in the index.
    pub fn put(&self, path: impl Into<String>, start: usize, end: usize, value: Bytes) {
        let path = path.into();
        let foyer_key = FoyerDiskPageCache::make_key(&path, start, end);
        self.cache.put(path.clone(), start, end, value);
        // Record in key index for later precise eviction
        self.key_index
            .entry(path)
            .or_default()
            .push(foyer_key);
    }

    // ── Eviction ──────────────────────────────────────────────────

    /// Precisely evict all cached byte ranges for `path`.
    ///
    /// Looks up the key index for the file, then calls `remove_key` for each
    /// entry — no LRU wait, no prefix-scan needed.
    pub fn evict_file(&self, path: &str) {
        if let Some((_, keys)) = self.key_index.remove(path) {
            log_info!(
                "[FOYER-PAGE-CACHE] evict_file: path={}, removing {} cached ranges",
                path, keys.len()
            );
            for key in keys {
                self.cache.remove_key(&key);
            }
        } else {
            log_debug!(
                "[FOYER-PAGE-CACHE] evict_file: path={} not in key index (no cached ranges)",
                path
            );
        }
    }

    /// Clear the entire cache (all files, all ranges).
    pub fn clear_blocking(&self) {
        log_info!("[FOYER-PAGE-CACHE] FoyerCacheManager: clearing all entries");
        self.key_index.clear();
        self.cache.clear_blocking();
    }

    // ── Introspection ─────────────────────────────────────────────

    /// Returns the configured disk capacity in bytes.
    pub fn disk_capacity_bytes(&self) -> usize {
        self.cache.disk_capacity_bytes()
    }

    /// Returns the disk directory used by Foyer.
    pub fn disk_dir(&self) -> &std::path::Path {
        self.cache.disk_dir()
    }

    /// Returns the number of files currently tracked in the key index.
    pub fn indexed_file_count(&self) -> usize {
        self.key_index.len()
    }
}
