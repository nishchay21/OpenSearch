/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

//! Foyer-backed **disk-only** page cache for Parquet column chunk byte ranges.
//!
//! ## Architecture
//!
//! `FoyerDiskPageCache` wraps Foyer's [`HybridCache`] configured with a zero-byte memory
//! tier so that **all cached entries go directly to the local NVMe disk store**.
//! This avoids heap pressure and lets the OS page cache and DataFusion's own memory
//! management control RAM usage, while still avoiding repeated S3/GCS/Azure fetches
//! for warm Parquet byte ranges.
//!
//! ```text
//! DataFusion.get_range(file, 4096..8192)
//!   └── TieredObjectStore.get_range()
//!         ├── [FOYER-PAGE-CACHE] check L2-disk  → HIT: return bytes (local NVMe I/O)
//!         └── MISS: local NVMe or remote S3/GCS read
//!               └── [FOYER-PAGE-CACHE] PUT → L2-disk (async)
//! ```
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

/// Foyer **disk-only** page cache for Parquet byte ranges.
///
/// Memory tier is disabled (0 bytes) — all entries are stored on the NVMe disk store
/// bounded by `disk_capacity_bytes` in `disk_dir`.
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
    disk_capacity_bytes: usize,
    disk_dir: PathBuf,
}

impl std::fmt::Debug for FoyerDiskPageCache {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "FoyerDiskPageCache(disk={}B, dir={:?})",
            self.disk_capacity_bytes, self.disk_dir)
    }
}

impl FoyerDiskPageCache {
    /// Build the cache synchronously by blocking a Tokio runtime.
    ///
    /// Called once at node startup. The `disk_dir` must be writable; Foyer creates
    /// it if needed. The memory tier is set to 0 — all entries go straight to disk.
    ///
    /// # Arguments
    /// * `disk_capacity_bytes` — disk budget (e.g. 10 GB)
    /// * `disk_dir`            — directory for Foyer disk files (local NVMe path)
    pub fn new(disk_capacity_bytes: usize, disk_dir: impl Into<PathBuf>) -> Self {
        let disk_dir = disk_dir.into();

        log_info!(
            "[FOYER-PAGE-CACHE] initializing disk-only page cache: disk={}B, dir={}",
            disk_capacity_bytes, disk_dir.display()
        );

        // Foyer's HybridCacheBuilder::build() is async — use a temporary Tokio runtime
        // to block on it. We only do this once at startup so the overhead is acceptable.
        let rt = tokio::runtime::Runtime::new()
            .expect("[FOYER-PAGE-CACHE] failed to create bootstrap Tokio runtime");

        let disk_dir_clone = disk_dir.clone();
        let inner: HybridCache<String, CachedBytes> = rt.block_on(async move {
            HybridCacheBuilder::new()
                .with_name("foyer-parquet-page-cache")
                // Memory tier = 0: all entries go directly to the disk store.
                // Foyer requires at least 1 byte for the memory tier internally,
                // so we use a minimal 1-byte value — in practice nothing lives there.
                .memory(1)
                .with_eviction_config(LruConfig { high_priority_pool_ratio: 0.0 })
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
            "[FOYER-PAGE-CACHE] disk-only page cache ready: disk={}B, dir={}",
            disk_capacity_bytes, disk_dir.display()
        );

        // CRITICAL: keep `rt` alive as an Arc field.
        // Foyer spawns background store tasks on this runtime during build().
        // If `rt` is dropped here, those tasks are cancelled → JoinError::Cancelled panic.
        let runtime = Arc::new(rt);

        Self { inner, _runtime: runtime, disk_capacity_bytes, disk_dir }
    }

    // ── Key helpers ────────────────────────────────────────────────

    /// Build the string cache key from a file path and a byte range.
    /// Format: `"<path>:<start>-<end>"`
    pub fn make_key(path: &str, start: usize, end: usize) -> String {
        format!("{}:{}-{}", path, start, end)
    }

    // ── Cache operations ───────────────────────────────────────────

    /// Async lookup. Returns `Some(Bytes)` on disk hit, `None` on miss.
    pub async fn get(&self, path: &str, start: usize, end: usize) -> Option<Bytes> {
        let key = Self::make_key(path, start, end);
        match self.inner.get(&key).await {
            Ok(Some(entry)) => {
                log_debug!(
                    "[FOYER-PAGE-CACHE] HIT (disk): path={}, range={}..{}, key={}",
                    path, start, end, key
                );
                Some(entry.value().clone().into_bytes())
            }
            Ok(None) => {
                log_debug!(
                    "[FOYER-PAGE-CACHE] MISS: path={}, range={}..{}",
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

    /// Remove a single cached entry by its pre-built Foyer key string.
    ///
    /// Used by `TieredObjectStore::evict_file_cache` to perform precise, per-key
    /// eviction when a file is deleted or tiered out.
    pub fn remove_key(&self, key: &str) {
        log_debug!(
            "[FOYER-PAGE-CACHE] remove_key: key={}",
            key
        );
        self.inner.remove(key);
    }

    /// Insert a byte range into the disk cache.
    /// Foyer writes to disk asynchronously in the background.
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
    /// Precise eviction is handled by `TieredObjectStore::evict_file_cache()` which uses
    /// the per-file key index.
    pub fn evict_file(&self, path: &str) {
        log_info!(
            "[FOYER-PAGE-CACHE] evict_file: path={} — use TieredObjectStore.evict_file_cache() \
             for precise key-index eviction.",
            path
        );
    }

    /// Clear the entire disk cache.
    pub async fn clear(&self) {
        log_info!("[FOYER-PAGE-CACHE] clearing all entries from disk cache");
        if let Err(e) = self.inner.clear().await {
            log_error!("[FOYER-PAGE-CACHE] error during clear: {}", e);
        }
    }

    /// Synchronous clear variant for JNI.
    /// Runs on Foyer's own Tokio runtime so the async clear can complete cleanly.
    pub fn clear_blocking(&self) {
        self._runtime.block_on(self.clear());
    }

    /// Returns the configured disk capacity in bytes.
    pub fn disk_capacity_bytes(&self) -> usize {
        self.disk_capacity_bytes
    }

    /// Returns the disk directory.
    pub fn disk_dir(&self) -> &std::path::Path {
        &self.disk_dir
    }
}
