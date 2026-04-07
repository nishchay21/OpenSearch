/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

use async_trait::async_trait;
use futures::stream::BoxStream;
use object_store::{
    GetOptions, GetResult, ListResult, MultipartUpload, ObjectMeta, ObjectStore,
    PutMultipartOptions, PutOptions, PutPayload, PutResult, Result,
    local::LocalFileSystem, path::Path as ObjectPath,
};
use std::fmt::{self, Display, Formatter};
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use vectorized_exec_spi::log_info;

#[derive(Debug)]
pub struct RemoteObjectStore {
    inner: Arc<LocalFileSystem>,
    remote_base: String,
    read_count: AtomicU64,
}

impl RemoteObjectStore {
    pub fn new(remote_base_dir: &str) -> Self {
        log_info!("[RemoteObjectStore] created with base_dir={}", remote_base_dir);
        if let Err(e) = std::fs::create_dir_all(remote_base_dir) {
            log_info!("[RemoteObjectStore] create_dir_all for {} failed (may already exist): {}", remote_base_dir, e);
        }
        Self {
            inner: Arc::new(
                LocalFileSystem::new_with_prefix(remote_base_dir)
                    .expect("Failed to create remote FS for RemoteObjectStore")
            ),
            remote_base: remote_base_dir.to_string(),
            read_count: AtomicU64::new(0),
        }
    }
}

impl Display for RemoteObjectStore {
    fn fmt(&self, f: &mut Formatter<'_>) -> fmt::Result {
        write!(f, "RemoteObjectStore(base={}, reads={})", self.remote_base, self.read_count.load(Ordering::Relaxed))
    }
}

#[async_trait]
impl ObjectStore for RemoteObjectStore {
    async fn put_opts(&self, location: &ObjectPath, payload: PutPayload, opts: PutOptions) -> Result<PutResult> {
        self.inner.put_opts(location, payload, opts).await
    }
    async fn put_multipart_opts(&self, location: &ObjectPath, opts: PutMultipartOptions) -> Result<Box<dyn MultipartUpload>> {
        self.inner.put_multipart_opts(location, opts).await
    }
    async fn get_opts(&self, location: &ObjectPath, options: GetOptions) -> Result<GetResult> {
        let count = self.read_count.fetch_add(1, Ordering::Relaxed) + 1;
        log_info!(
            "[RemoteObjectStore] READ_FROM_REMOTE #{} file={} remote_base={}",
            count, location, self.remote_base
        );
        let result = self.inner.get_opts(location, options).await;
        match &result {
            Ok(r) => log_info!(
                "[RemoteObjectStore] READ_FROM_REMOTE #{} file={} SUCCESS bytes={}",
                count, location, r.meta.size
            ),
            Err(e) => log_info!(
                "[RemoteObjectStore] READ_FROM_REMOTE #{} file={} FAILED error={}",
                count, location, e
            ),
        }
        result
    }
    async fn head(&self, location: &ObjectPath) -> Result<ObjectMeta> {
        log_info!("[RemoteObjectStore] HEAD_FROM_REMOTE file={} remote_base={}", location, self.remote_base);
        self.inner.head(location).await
    }
    async fn delete(&self, location: &ObjectPath) -> Result<()> {
        self.inner.delete(location).await
    }
    fn list(&self, prefix: Option<&ObjectPath>) -> BoxStream<'static, Result<ObjectMeta>> {
        self.inner.list(prefix)
    }
    async fn list_with_delimiter(&self, prefix: Option<&ObjectPath>) -> Result<ListResult> {
        self.inner.list_with_delimiter(prefix).await
    }
    async fn copy(&self, from: &ObjectPath, to: &ObjectPath) -> Result<()> {
        self.inner.copy(from, to).await
    }
    async fn copy_if_not_exists(&self, from: &ObjectPath, to: &ObjectPath) -> Result<()> {
        self.inner.copy_if_not_exists(from, to).await
    }
}
