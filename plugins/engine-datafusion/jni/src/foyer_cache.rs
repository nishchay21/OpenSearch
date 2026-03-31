/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

//! Foyer-backed **hybrid (memory + disk)** page cache for Parquet column chunk byte ranges.
//!
//! ## Architecture
//!
//! `FoyerDiskPageCache` wraps Foyer's [`HybridCache`] which provides two storage tiers:
//! - **L1 (memory)**: hot byte ranges served from RAM — zero I/O
//! - **L2 (disk)**: warm byte ranges spilled to a dedicated directory on NVMe — avoids S3 fetches
//!
//! On a cache miss both tiers are checked. On eviction from L1, entries are spilled to L2
//! automatically by Foyer. On L2 eviction they are dropped.
//!
//! ## Key format
//!
//! Cache key = `"<file_path_without_leading_slash>:<start>-<end>"` (a plain String)
//! Example:   `"data/nodes/0/indices/UUID/0/index/parquet/_parquet_0.parquet:4096-8192"`
//!
//! ## Log prefix
//!
//! All log lines use the `[FOYER-PAGE-CACHE]` prefix so the caching flow can be easily
//! grepped in the OpenSearch logs.

use std::path::PathBuf;
use std::sync::Arc;
use bytes::Bytes;
use serde::{Deserialize, Serialize};
use foyer::{HybridCache, HybridCacheBuilder, DirectFsDeviceOptionsBuilder, LruConfig};
use vectorized_exec_spi::{log_debug, log_info, log_error};

// ────────────────────────────────────────────────────────────────────
// Value wrapper: Bytes does not implement serde, so wrap it.
// ────────────────────────────────────────────────────────────────────

/// Newtype wrapper around [`Bytes`] that implements `serde::Serialize/Deserialize`
/// so it satisfies Foyer's `StorageValue` bound for disk persistence.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct CachedBytes(#[serde(with = "serde_bytes")] Vec<u8>);

impl CachedBytes {
    pub fn from_bytes(b: Bytes) -> Self {
        Self(b.to_vec())
    }
    pub fn into_bytes(self) -> Bytes {
        Bytes::from(self.0)
    }
}

// ────────────────────────────────────────────────────────────────────
// Main cache struct
// ────────────────────────────────────────────────────────────────────

/// Foyer hybrid (memory + disk) page cache for Parquet byte ranges.
///
/// - **L1**: an in-memory LRU bounded by `memory_capacity_bytes`
/// - **L2**: a disk store in `disk_dir` bounded by `disk_capacity_bytes`
///
/// Thread-safe and cheap to clone (inner `HybridCache` is `Arc`-backed).
///
/// **Important**: The `tokio::runtime::Runtime` used to build Foyer must stay alive
/// for the entire lifetime of the `HybridCache`. Foyer spawns background I/O tasks
/// on that runtime during `build().await`; dropping the runtime cancels those tasks,
/// which causes `JoinError::Cancelled` panics in `foyer-storage`. We therefore keep
/// the runtime as an `Arc` field so it is dropped only after the `HybridCache` itself.
#[derive(Clone)]
pub struct FoyerDiskPageCache {
    inner: HybridCache<String, CachedBytes>,
    /// The Tokio runtime that owns Foyer's background tasks.
    /// Must outlive `inner` — Arc ensures it is dropped last.
    _runtime: Arc<tokio::runtime::Runtime>,
    memory_capacity_bytes: usize,
    disk_capacity_bytes: usize,
    disk_dir: PathBuf,
}

impl std::fmt::Debug for FoyerDiskPageCache {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "FoyerDiskPageCache(mem={}B, disk={}B, dir={:?})",
            self.memory_capacity_bytes, self.disk_capacity_bytes, self.disk_dir)
    }
}

impl FoyerDiskPageCache {
    /// Build the cache synchronously by blocking a Tokio runtime.
    ///
    /// Called once at node startup from `DataFusionRuntimeEnv`.
    /// The `disk_dir` must be writable; Foyer will create the directory if needed.
    ///
    /// # Arguments
    /// * `memory_capacity_bytes` — hot L1 memory budget (e.g. 512 MB)
    /// * `disk_capacity_bytes`   — warm L2 disk budget (e.g. 10 GB)
    /// * `disk_dir`              — directory for Foyer disk files
    pub fn new(memory_capacity_bytes: usize, disk_capacity_bytes: usize, disk_dir: impl Into<PathBuf>) -> Self {
        let disk_dir = disk_dir.into();

        log_info!(
            "[FOYER-PAGE-CACHE] initializing hybrid page cache: memory={}B, disk={}B, dir={}",
            memory_capacity_bytes, disk_capacity_bytes, disk_dir.display()
        );

        // Foyer's HybridCacheBuilder::build() is async — use a temporary Tokio runtime
        // to block on it. We only do this once at startup so the overhead is acceptable.
        let rt = tokio::runtime::Runtime::new()
            .expect("[FOYER-PAGE-CACHE] failed to create bootstrap Tokio runtime");

        let disk_dir_clone = disk_dir.clone();
        let inner: HybridCache<String, CachedBytes> = rt.block_on(async move {
            HybridCacheBuilder::new()
                .with_name("foyer-parquet-page-cache")
                .memory(memory_capacity_bytes)
                .with_eviction_config(LruConfig { high_priority_pool_ratio: 0.1 })
                .storage()
                .with_device_config(
                    DirectFsDeviceOptionsBuilder::new(disk_dir_clone)
                        .with_capacity(disk_capacity_bytes)
                        .build()
                )
                .build()
                .await
                .expect("[FOYER-PAGE-CACHE] failed to build Foyer HybridCache")
        });

        log_info!(
            "[FOYER-PAGE-CACHE] hybrid page cache ready: memory={}B, disk={}B, dir={}",
            memory_capacity_bytes, disk_capacity_bytes, disk_dir.display()
        );

        // CRITICAL: keep `rt` alive as an Arc field.
        // Foyer spawns background store tasks on this runtime during build().
        // If `rt` is dropped here, those tasks are cancelled → JoinError::Cancelled panic.
        let runtime = Arc::new(rt);

        Self { inner, _runtime: runtime, memory_capacity_bytes, disk_capacity_bytes, disk_dir }
    }

    // ── Key helpers ────────────────────────────────────────────────

    /// Build the string cache key from a file path and a byte range.
    /// Format: `"<path>:<start>-<end>"`
    pub fn make_key(path: &str, start: usize, end: usize) -> String {
        format!("{}:{}-{}", path, start, end)
    }

    // ── Cache operations ───────────────────────────────────────────

    /// Async lookup. Returns `Some(Bytes)` on hit (from memory or disk), `None` on miss.
    ///
    /// This is `async` because a disk-tier lookup involves I/O on Foyer's background threads.
    /// Callers that are already inside an async context (e.g. `CachingObjectStore::get_range`)
    /// can simply `.await` this.
    pub async fn get(&self, path: &str, start: usize, end: usize) -> Option<Bytes> {
        let key = Self::make_key(path, start, end);
        match self.inner.get(&key).await {
            Ok(Some(entry)) => {
                log_debug!(
                    "[FOYER-PAGE-CACHE] HIT (L1-mem or L2-disk): path={}, range={}..{}, key={}",
                    path, start, end, key
                );
                Some(entry.value().clone().into_bytes())
            }
            Ok(None) => {
                log_debug!(
                    "[FOYER-PAGE-CACHE] MISS (not in memory or disk): path={}, range={}..{}",
                    path, start, end
                );
                None
            }
            Err(e) => {
                log_error!(
                    "[FOYER-PAGE-CACHE] error reading cache: path={}, range={}..{}, err={}",
                    path, start, end, e
                );
                None
            }
        }
    }

    /// Synchronous get — blocks on Foyer's own runtime.
    ///
    /// Use this from JNI callbacks that cannot be `async`.
    pub fn get_blocking(&self, path: &str, start: usize, end: usize) -> Option<Bytes> {
        self._runtime.block_on(self.get(path, start, end))
    }

    /// Insert a byte range. The insert goes to memory (L1) synchronously; Foyer spills
    /// to disk (L2) asynchronously in the background.
    pub fn put(&self, path: impl Into<String>, start: usize, end: usize, value: Bytes) {
        let path = path.into();
        let key = Self::make_key(&path, start, end);
        let size = value.len();
        log_debug!(
            "[FOYER-PAGE-CACHE] PUT: path={}, range={}..{}, size={}B, key={}",
            path, start, end, size, key
        );
        self.inner.insert(key, CachedBytes::from_bytes(value));
    }

    /// Evict all cached byte ranges for a given file path.
    ///
    /// Called when a Parquet file is deleted (merged/compacted/tiered out).
    /// Because Foyer does not support prefix-based removal, this is a no-op with a log warning.
    /// The entry will expire naturally via LRU eviction; stale reads are prevented by the
    /// FileRegistry (which marks the file as deleted before any caller can open it).
    ///
    /// A production improvement would be to track per-file keys in a `DashMap` and evict them
    /// individually — the `FoyerDiskPageCacheWithIndex` (not implemented yet) would do this.
    pub fn evict_file(&self, path: &str) {
        log_info!(
            "[FOYER-PAGE-CACHE] evict_file: path={} — Foyer does not support prefix eviction; \
             entry will be evicted by LRU. FileRegistry guards against stale reads.",
            path
        );
        // Foyer's remove() takes a full key, not a prefix.
        // Without a key index we cannot enumerate all ranges for a file.
        // This is safe because PassthroughCacheStrategy / FoyerParquetCacheStrategy
        // always checks the FileRegistry location before opening an IndexInput.
    }

    /// Clear the entire cache (both memory and disk tiers).
    pub async fn clear(&self) {
        log_info!("[FOYER-PAGE-CACHE] clearing all entries from memory and disk");
        if let Err(e) = self.inner.clear().await {
            log_error!("[FOYER-PAGE-CACHE] error during clear: {}", e);
        }
    }

    /// Synchronous clear variant for JNI.
    /// Runs on Foyer's own Tokio runtime so the async clear can complete cleanly.
    pub fn clear_blocking(&self) {
        self._runtime.block_on(self.clear());
    }

    /// Returns the in-memory L1 usage in bytes.
    pub fn memory_usage_bytes(&self) -> usize {
        self.inner.memory().usage()
    }

    /// Returns the configured memory L1 capacity.
    pub fn memory_capacity_bytes(&self) -> usize {
        self.memory_capacity_bytes
    }

    /// Returns the cumulative bytes written to the Foyer L2 disk tier since cache creation.
    ///
    /// Note: Foyer 0.11.5 does not expose a "current occupancy" metric for the disk tier.
    /// `DeviceStats::write_bytes` is the closest available proxy — it counts total bytes
    /// flushed to disk and is monotonically increasing. It overestimates current usage
    /// (evicted bytes are not subtracted) but is accurate enough for disk budget monitoring.
    ///
    /// Used by `DiskBudgetManager` via `foyerDiskUsageBytes()` JNI to populate
    /// `disk_budget.format_cache.used_in_bytes` in `_nodes/stats`.
    pub fn disk_usage_bytes(&self) -> usize {
        use std::sync::atomic::Ordering;
        self.inner.stats().write_bytes.load(Ordering::Relaxed)
    }

    /// Returns the configured disk L2 capacity.
    pub fn disk_capacity_bytes(&self) -> usize {
        self.disk_capacity_bytes
    }

    /// Returns the disk directory.
    pub fn disk_dir(&self) -> &std::path::Path {
        &self.disk_dir
    }
}
