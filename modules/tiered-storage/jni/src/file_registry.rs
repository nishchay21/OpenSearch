/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

use dashmap::DashMap;
use object_store::{ObjectMeta, ObjectStore};
use std::fmt;
use std::sync::Arc;
use std::sync::atomic::{AtomicI64, AtomicU64, Ordering};
use vectorized_exec_spi::{log_debug, log_info};

/// Where a file is known to exist.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FileLocation {
    Local,
    Remote,
    Both,
}

impl fmt::Display for FileLocation {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            FileLocation::Local => write!(f, "LOCAL"),
            FileLocation::Remote => write!(f, "REMOTE"),
            FileLocation::Both => write!(f, "LOCAL+REMOTE"),
        }
    }
}

/// Metadata tracked per file.
#[derive(Debug)]
pub struct FileEntry {
    pub local_path: String,
    pub remote_path: Option<String>,
    /// Which repository this file belongs to (key into `remote_stores`).
    pub repo_key: Option<String>,
    pub location: FileLocation,
    pub active_reads: AtomicI64,
    pub total_reads: AtomicU64,
    pub size: u64,
}

impl FileEntry {
    pub fn is_on_remote(&self) -> bool {
        matches!(self.location, FileLocation::Remote | FileLocation::Both)
    }

    pub fn can_delete_local(&self) -> bool {
        self.is_on_remote() && self.active_reads.load(Ordering::Relaxed) == 0
    }
}

impl fmt::Display for FileEntry {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f, "FileEntry(local={}, remote={}, repo={}, loc={}, active_reads={}, total_reads={}, size={})",
            self.local_path,
            self.remote_path.as_deref().unwrap_or("none"),
            self.repo_key.as_deref().unwrap_or("none"),
            self.location,
            self.active_reads.load(Ordering::Relaxed),
            self.total_reads.load(Ordering::Relaxed),
            self.size,
        )
    }
}

/// Thread-safe registry tracking per-file location, remote path mapping,
/// remote store associations, and active read reference counts.
///
/// Also holds a map of `repo_key → Arc<dyn ObjectStore>` for multi-repo support.
/// Each file entry knows which repo it belongs to via `repo_key`, and the
/// `TieredObjectStore` uses this to dispatch reads to the correct remote store.
///
/// Tracks files pending local deletion in `pending_local_deletes`. When a file
/// is marked REMOTE but can't be deleted immediately (active readers), it's added
/// to the pending set. `sweep_pending_deletes()` retries deletion for all pending files.
#[derive(Debug)]
pub struct FileRegistry {
    entries: DashMap<String, FileEntry>,
    /// Map of repo_key → remote ObjectStore. Populated lazily as new repos are encountered.
    remote_stores: DashMap<String, Arc<dyn ObjectStore>>,
    /// Local file paths (absolute, with leading "/") pending deletion.
    /// Files are added here when marked REMOTE but still have active readers
    /// or when the immediate delete attempt fails.
    pending_local_deletes: DashMap<String, ()>,
}

impl FileRegistry {
    pub fn new() -> Self {
        Self {
            entries: DashMap::new(),
            remote_stores: DashMap::new(),
            pending_local_deletes: DashMap::new(),
        }
    }

    /// Register a remote store for a given repository key. Idempotent.
    pub fn add_remote_store(&self, repo_key: &str, store: Arc<dyn ObjectStore>) {
        use dashmap::mapref::entry::Entry;
        match self.remote_stores.entry(repo_key.to_string()) {
            Entry::Occupied(_) => {
                log_info!("[FileRegistry] add_remote_store: repo_key={} already registered, skipping", repo_key);
            }
            Entry::Vacant(vacant) => {
                log_info!("[FileRegistry] add_remote_store: repo_key={}", repo_key);
                vacant.insert(store);
            }
        }
    }

    /// Get the remote store for a file by looking up its repo_key.
    pub fn get_remote_store_for_file(&self, key: &str) -> Option<Arc<dyn ObjectStore>> {
        let entry = self.entries.get(key)?;
        let repo_key = entry.repo_key.as_ref()?;
        self.remote_stores.get(repo_key).map(|s| Arc::clone(s.value()))
    }

    /// Get the remote store by repo key directly.
    pub fn get_remote_store(&self, repo_key: &str) -> Option<Arc<dyn ObjectStore>> {
        self.remote_stores.get(repo_key).map(|s| Arc::clone(s.value()))
    }

    pub fn register_local(&self, key: &str, size: u64) {
        if let Some(mut entry) = self.entries.get_mut(key) {
            Self::upgrade_location(&mut entry.location, &FileLocation::Local);
        } else {
            log_debug!("[FileRegistry] register_local: path={}, size={}", key, size);
            self.entries.insert(key.to_string(), FileEntry {
                local_path: key.to_string(),
                remote_path: None,
                repo_key: None,
                location: FileLocation::Local,
                active_reads: AtomicI64::new(0),
                total_reads: AtomicU64::new(0),
                size,
            });
        }
    }

    pub fn mark_synced_to_remote(&self, key: &str, remote_path: &str, repo_key: &str) {
        if let Some(mut entry) = self.entries.get_mut(key) {
            entry.remote_path = Some(remote_path.to_string());
            entry.repo_key = Some(repo_key.to_string());
            Self::upgrade_location(&mut entry.location, &FileLocation::Remote);
            log_debug!("[FileRegistry] mark_synced_to_remote: path={}, remote={}, repo={}, loc={}",
                key, remote_path, repo_key, entry.location);
        } else {
            log_debug!("[FileRegistry] mark_synced_to_remote (new): path={}, remote={}, repo={}", key, remote_path, repo_key);
            self.entries.insert(key.to_string(), FileEntry {
                local_path: key.to_string(),
                remote_path: Some(remote_path.to_string()),
                repo_key: Some(repo_key.to_string()),
                location: FileLocation::Remote,
                active_reads: AtomicI64::new(0),
                total_reads: AtomicU64::new(0),
                size: 0,
            });
        }
    }

    pub fn register(&self, key: &str, from: FileLocation, size: u64) {
        if let Some(mut entry) = self.entries.get_mut(key) {
            Self::upgrade_location(&mut entry.location, &from);
        } else {
            log_info!("[FileRegistry] register: path={}, loc={}, size={}", key, from, size);
            self.entries.insert(key.to_string(), FileEntry {
                local_path: key.to_string(),
                remote_path: None,
                repo_key: None,
                location: from,
                active_reads: AtomicI64::new(0),
                total_reads: AtomicU64::new(0),
                size,
            });
        }
    }

    pub fn register_from_meta(&self, meta: &ObjectMeta, from: FileLocation) {
        let key = meta.location.as_ref().to_string();
        self.register(&key, from, meta.size as u64);
    }

    pub fn acquire_read(&self, key: &str) -> FileLocation {
        let entry = self.entries.entry(key.to_string()).or_insert_with(|| {
            log_info!("[FileRegistry] acquire_read (auto-register REMOTE): path={}", key);
            FileEntry {
                local_path: key.to_string(),
                remote_path: None,
                repo_key: None,
                location: FileLocation::Remote,
                active_reads: AtomicI64::new(0),
                total_reads: AtomicU64::new(0),
                size: 0,
            }
        });
        entry.total_reads.fetch_add(1, Ordering::Relaxed);
        let refs = entry.active_reads.fetch_add(1, Ordering::Relaxed) + 1;
        log_debug!("[FileRegistry] acquire_read: path={}, active_reads={}, loc={}", key, refs, entry.location);
        entry.location.clone()
    }

    pub fn release_read(&self, key: &str) -> i64 {
        if let Some(entry) = self.entries.get(key) {
            let remaining = entry.active_reads.fetch_sub(1, Ordering::Relaxed) - 1;
            if remaining < 0 {
                // Clamp to 0 — don't let it go negative
                entry.active_reads.store(0, Ordering::Relaxed);
                log_info!("[FileRegistry] release_read WARNING: clamped negative active_reads to 0 for path={}", key);
                return 0;
            }
            log_debug!("[FileRegistry] release_read: path={}, active_reads={}", key, remaining);
            remaining
        } else {
            log_info!("[FileRegistry] release_read WARNING: unknown file path={}", key);
            0
        }
    }

    pub fn can_delete_local(&self, key: &str) -> bool {
        self.entries.get(key).map(|e| e.can_delete_local()).unwrap_or(false)
    }

    pub fn mark_local_deleted(&self, key: &str) {
        if let Some(mut entry) = self.entries.get_mut(key) {
            entry.location = FileLocation::Remote;
            log_info!("[FileRegistry] mark_local_deleted: path={}, now REMOTE only", key);
        }
    }

    pub fn remove(&self, key: &str) {
        if let Some((_, entry)) = self.entries.remove(key) {
            log_info!("[FileRegistry] remove: path={}, was={}", key, entry);
        }
    }

    pub fn get_remote_path(&self, key: &str) -> Option<String> {
        self.entries.get(key).and_then(|e| e.remote_path.clone())
    }

    /// Get remote path and remote store in a single lookup (avoids two DashMap lookups).
    pub fn get_remote_info(&self, key: &str) -> Option<(String, Arc<dyn ObjectStore>)> {
        let entry = self.entries.get(key)?;
        let remote_path = entry.remote_path.as_ref()?.clone();
        let repo_key = entry.repo_key.as_ref()?;
        let store = self.remote_stores.get(repo_key).map(|s| Arc::clone(s.value()))?;
        Some((remote_path, store))
    }

    pub fn get_location(&self, key: &str) -> Option<FileLocation> {
        self.entries.get(key).map(|e| e.location.clone())
    }
    pub fn get_size(&self, key: &str) -> Option<u64> {
        self.entries.get(key).map(|e| e.size)
    }


    pub fn get_repo_key(&self, key: &str) -> Option<String> {
        self.entries.get(key).and_then(|e| e.repo_key.clone())
    }

    pub fn get_active_reads(&self, key: &str) -> i64 {
        self.entries.get(key)
            .map(|e| e.active_reads.load(Ordering::Relaxed))
            .unwrap_or(0)
    }

    pub fn file_count(&self) -> usize {
        self.entries.len()
    }

    pub fn remote_store_count(&self) -> usize {
        self.remote_stores.len()
    }

    /// Add a local file path to the pending deletion set.
    /// Called when a file is marked REMOTE but can't be deleted immediately.
    /// The path should be the absolute local path (with leading "/").
    pub fn add_pending_delete(&self, local_path: &str) {
        log_info!("[FileRegistry] add_pending_delete: path={}", local_path);
        self.pending_local_deletes.insert(local_path.to_string(), ());
    }

    /// Sweep all pending local deletes. For each pending file:
    /// - If the file is REMOTE-only with 0 active reads, try to delete it.
    /// - If deletion succeeds, remove from pending set.
    /// - If the file no longer exists on disk, remove from pending set.
    /// Returns the number of files successfully deleted.
    pub fn sweep_pending_deletes(&self) -> u32 {
        let mut deleted = 0u32;
        self.pending_local_deletes.retain(|local_path, _| {
            let registry_key = if local_path.starts_with('/') {
                &local_path[1..]
            } else {
                local_path.as_str()
            };

            let can_delete = self.entries.get(registry_key)
                .map(|e| {
                    e.location == FileLocation::Remote
                        && e.active_reads.load(Ordering::Relaxed) == 0
                })
                .unwrap_or(true);

            if !can_delete {
                return true; // keep in pending
            }

            let path = std::path::Path::new(local_path.as_str());
            if !path.exists() {
                return false; // remove from pending
            }

            match std::fs::remove_file(path) {
                Ok(()) => {
                    log_info!("[FileRegistry] sweep_pending_deletes: deleted {}", local_path);
                    deleted += 1;
                    false // remove from pending
                }
                Err(e) => {
                    log_info!("[FileRegistry] sweep_pending_deletes: failed to delete {}: {}", local_path, e);
                    true // keep in pending
                }
            }
        });

        if deleted > 0 {
            log_info!("[FileRegistry] sweep_pending_deletes: deleted {} files, {} still pending",
                deleted, self.pending_local_deletes.len());
        }
        deleted
    }

    pub fn pending_delete_count(&self) -> usize {
        self.pending_local_deletes.len()
    }

    pub fn log_summary(&self) {
        let mut local = 0u32;
        let mut remote = 0u32;
        let mut both = 0u32;
        let mut total_active: i64 = 0;
        for entry in self.entries.iter() {
            match entry.value().location {
                FileLocation::Local => local += 1,
                FileLocation::Remote => remote += 1,
                FileLocation::Both => both += 1,
            }
            total_active += entry.value().active_reads.load(Ordering::Relaxed);
        }
        log_info!(
            "[FileRegistry] summary: total={}, local={}, remote={}, both={}, active_reads={}, remote_stores={}, pending_deletes={}",
            self.entries.len(), local, remote, both, total_active, self.remote_stores.len(), self.pending_local_deletes.len()
        );
    }

    fn upgrade_location(current: &mut FileLocation, from: &FileLocation) {
        let new_loc = match (&*current, from) {
            (FileLocation::Local, FileLocation::Remote) => FileLocation::Both,
            (FileLocation::Remote, FileLocation::Local) => FileLocation::Both,
            _ => return,
        };
        log_info!("[FileRegistry] location upgraded: {} -> {}", current, new_loc);
        *current = new_loc;
    }
}
