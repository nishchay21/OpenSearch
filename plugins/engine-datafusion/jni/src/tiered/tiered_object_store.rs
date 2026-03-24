/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

use async_trait::async_trait;
use futures::stream::BoxStream;
use futures::StreamExt;
use object_store::{
    GetOptions, GetResult, ListResult, MultipartUpload, ObjectMeta, ObjectStore,
    PutMultipartOptions, PutOptions, PutPayload, PutResult, Result,
    local::LocalFileSystem, path::Path as ObjectPath,
};
use std::fmt::{self, Display, Formatter};
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use vectorized_exec_spi::log_info;

use super::file_registry::{FileLocation, FileRegistry};

/// Global tiered object store registered with DataFusion for the `file://` scheme.
///
/// Dispatches reads to local filesystem or the appropriate remote store based on
/// per-file state in the [`FileRegistry`]. Supports multiple remote repositories —
/// each file entry knows which repo it belongs to, and the registry holds a map
/// of `repo_key → Arc<dyn ObjectStore>`.
#[derive(Debug)]
pub struct TieredObjectStore {
    local_fs: Arc<LocalFileSystem>,
    registry: Arc<FileRegistry>,
    remote_reads: AtomicU64,
    passthrough_reads: AtomicU64,
}

impl TieredObjectStore {
    /// Create a new TieredObjectStore. Remote stores are added later via
    /// [`FileRegistry::add_remote_store`] as new repositories are encountered.
    pub fn new() -> (Self, Arc<FileRegistry>) {
        log_info!("[TieredObjectStore] created");
        let registry = Arc::new(FileRegistry::new());
        let local_fs = LocalFileSystem::new_with_prefix("/")
            .expect("Failed to create LocalFileSystem with root prefix");
        let store = Self {
            local_fs: Arc::new(local_fs),
            registry: Arc::clone(&registry),
            remote_reads: AtomicU64::new(0),
            passthrough_reads: AtomicU64::new(0),
        };
        (store, registry)
    }
}

impl Display for TieredObjectStore {
    fn fmt(&self, f: &mut Formatter<'_>) -> fmt::Result {
        write!(
            f, "TieredObjectStore(remote_reads={}, passthrough={}, tracked_files={}, remote_stores={})",
            self.remote_reads.load(Ordering::Relaxed),
            self.passthrough_reads.load(Ordering::Relaxed),
            self.registry.file_count(),
            self.registry.remote_store_count(),
        )
    }
}

#[async_trait]
impl ObjectStore for TieredObjectStore {
    async fn put_opts(&self, location: &ObjectPath, payload: PutPayload, opts: PutOptions) -> Result<PutResult> {
        let path_str = location.as_ref();
        let size = payload.content_length() as u64;
        log_info!("[TieredObjectStore] put_opts WRITE_LOCAL: file={}, size={}", path_str, size);

        let result = self.local_fs.put_opts(location, payload, opts).await;
        if result.is_ok() {
            self.registry.register_local(path_str, size);
        }
        result
    }

    async fn put_multipart_opts(&self, location: &ObjectPath, opts: PutMultipartOptions) -> Result<Box<dyn MultipartUpload>> {
        log_info!("[TieredObjectStore] put_multipart_opts: file={}", location);
        self.local_fs.put_multipart_opts(location, opts).await
    }

    async fn get_opts(&self, location: &ObjectPath, options: GetOptions) -> Result<GetResult> {
        let path_str = location.as_ref();

        // Check if this file has a remote path and a corresponding remote store
        let remote_info = self.registry.get_remote_path(path_str)
            .and_then(|rp| {
                self.registry.get_remote_store_for_file(path_str)
                    .map(|store| (rp, store))
            });

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
                Ok(r) => log_info!(
                    "[TieredObjectStore] get_opts #{} REMOTE_READ_SUCCESS remote_path={} bytes={}",
                    count, remote_path, r.meta.size
                ),
                Err(e) => log_info!(
                    "[TieredObjectStore] get_opts #{} REMOTE_READ_FAILED remote_path={} error={}",
                    count, remote_path, e
                ),
            }
            result
        } else {
            let count = self.passthrough_reads.fetch_add(1, Ordering::Relaxed) + 1;
            log_info!(
                "[TieredObjectStore] get_opts #{} READ_FROM_LOCAL (no remote path/store in registry): {}",
                count, location
            );
            self.local_fs.get_opts(location, options).await
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
                // Try remote if we have a remote store for this file
                let remote_info = self.registry.get_remote_path(path_str)
                    .and_then(|rp| {
                        self.registry.get_remote_store_for_file(path_str)
                            .map(|store| (rp, store))
                    });

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
