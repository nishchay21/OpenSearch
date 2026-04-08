/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

//! ObjectStore implementation backed by a Java BlobContainer via JNI callbacks.
//!
//! Instead of reimplementing S3/GCS/Azure credential management and encryption
//! in Rust, this delegates all remote reads to the existing Java BlobStore
//! infrastructure which already handles credentials, encryption, retries, etc.

use async_trait::async_trait;
use futures::stream::BoxStream;
use jni::objects::{GlobalRef, JByteArray, JValue};
use jni::JavaVM;
use bytes::Bytes;
use object_store::{
    GetOptions, GetResult, GetResultPayload, ListResult, MultipartUpload, ObjectMeta,
    ObjectStore, PutMultipartOptions, PutOptions, PutPayload, PutResult, Result,
    path::Path as ObjectPath,
};
use std::fmt::{self, Display, Formatter};
use std::ops::Range;
use std::sync::Arc;
use vectorized_exec_spi::log_info;

/// ObjectStore backed by a Java BlobContainer.
///
/// All read operations call back to Java via JNI. The Java side handles
/// credentials, encryption, retries, and all cloud-specific logic.
#[derive(Debug)]
pub struct JavaBlobStoreBackend {
    /// JNI global reference to the Java BlobContainer object.
    blob_container: GlobalRef,
    /// Reference to the JVM for attaching threads.
    jvm: Arc<JavaVM>,
}

impl JavaBlobStoreBackend {
    pub fn new(blob_container: GlobalRef, jvm: Arc<JavaVM>) -> Self {
        log_info!("[JavaBlobStoreBackend] created");
        Self { blob_container, jvm }
    }

    /// Read bytes from the Java BlobContainer via JNI.
    fn read_blob(&self, path: &str) -> std::result::Result<Vec<u8>, String> {
        let mut env = self.jvm.attach_current_thread()
            .map_err(|e| format!("Failed to attach JNI thread: {}", e))?;

        let j_path = env.new_string(path)
            .map_err(|e| format!("Failed to create Java string: {}", e))?;

        // Call: InputStream readBlob(String blobName)
        let input_stream = env.call_method(
            self.blob_container.as_obj(),
            "readBlob",
            "(Ljava/lang/String;)Ljava/io/InputStream;",
            &[JValue::Object(&j_path.into())],
        ).map_err(|e| format!("readBlob failed: {}", e))?
        .l().map_err(|e| format!("readBlob return type error: {}", e))?;

        // Call: byte[] InputStream.readAllBytes()
        let bytes_obj = env.call_method(
            &input_stream,
            "readAllBytes",
            "()[B",
            &[],
        ).map_err(|e| format!("readAllBytes failed: {}", e))?
        .l().map_err(|e| format!("readAllBytes return type error: {}", e))?;

        // Close the InputStream
        let _ = env.call_method(&input_stream, "close", "()V", &[]);

        // Convert Java byte[] to Rust Vec<u8>
        let byte_array = JByteArray::from(bytes_obj);
        let bytes = env.convert_byte_array(byte_array)
            .map_err(|e| format!("byte array conversion failed: {}", e))?;

        Ok(bytes)
    }

    /// Get blob size without reading the full file.
    /// Calls BlobContainer.listBlobsByPrefix() to get BlobMetadata with size.
    fn get_blob_size(&self, path: &str) -> std::result::Result<u64, String> {
        let mut env = self.jvm.attach_current_thread()
            .map_err(|e| format!("Failed to attach JNI thread: {}", e))?;

        // Extract blob name from path for prefix matching
        let blob_name = path.rsplit('/').next().unwrap_or(path);
        let j_prefix = env.new_string(blob_name)
            .map_err(|e| format!("Failed to create Java string: {}", e))?;

        // Call: Map<String, BlobMetadata> listBlobsByPrefix(String prefix)
        let map_obj = env.call_method(
            self.blob_container.as_obj(),
            "listBlobsByPrefix",
            "(Ljava/lang/String;)Ljava/util/Map;",
            &[JValue::Object(&j_prefix.into())],
        ).map_err(|e| format!("listBlobsByPrefix failed: {}", e))?
        .l().map_err(|e| format!("listBlobsByPrefix return type error: {}", e))?;

        // Get the values collection
        let values = env.call_method(&map_obj, "values", "()Ljava/util/Collection;", &[])
            .map_err(|e| format!("values() failed: {}", e))?
            .l().map_err(|e| format!("values() return type error: {}", e))?;

        // Get iterator
        let iterator = env.call_method(&values, "iterator", "()Ljava/util/Iterator;", &[])
            .map_err(|e| format!("iterator() failed: {}", e))?
            .l().map_err(|e| format!("iterator() return type error: {}", e))?;

        // Get first element
        let has_next = env.call_method(&iterator, "hasNext", "()Z", &[])
            .map_err(|e| format!("hasNext() failed: {}", e))?
            .z().map_err(|e| format!("hasNext() return type error: {}", e))?;

        if !has_next {
            return Err(format!("Blob not found: {}", path));
        }

        let metadata = env.call_method(&iterator, "next", "()Ljava/lang/Object;", &[])
            .map_err(|e| format!("next() failed: {}", e))?
            .l().map_err(|e| format!("next() return type error: {}", e))?;

        // Call BlobMetadata.length()
        let size = env.call_method(&metadata, "length", "()J", &[])
            .map_err(|e| format!("length() failed: {}", e))?
            .j().map_err(|e| format!("length() return type error: {}", e))?;

        Ok(size as u64)
    }

    /// Read a range of bytes from the Java BlobContainer via JNI.
    fn read_blob_range(&self, path: &str, offset: u64, length: u64) -> std::result::Result<Vec<u8>, String> {
        let mut env = self.jvm.attach_current_thread()
            .map_err(|e| format!("Failed to attach JNI thread: {}", e))?;

        let j_path = env.new_string(path)
            .map_err(|e| format!("Failed to create Java string: {}", e))?;

        // Call: InputStream readBlob(String blobName, long position, long length)
        let input_stream = env.call_method(
            self.blob_container.as_obj(),
            "readBlob",
            "(Ljava/lang/String;JJ)Ljava/io/InputStream;",
            &[
                JValue::Object(&j_path.into()),
                JValue::Long(offset as i64),
                JValue::Long(length as i64),
            ],
        ).map_err(|e| format!("readBlob range failed: {}", e))?
        .l().map_err(|e| format!("readBlob range return type error: {}", e))?;

        let bytes_obj = env.call_method(
            &input_stream,
            "readAllBytes",
            "()[B",
            &[],
        ).map_err(|e| format!("readAllBytes failed: {}", e))?
        .l().map_err(|e| format!("readAllBytes return type error: {}", e))?;

        let _ = env.call_method(&input_stream, "close", "()V", &[]);

        let byte_array = JByteArray::from(bytes_obj);
        let bytes = env.convert_byte_array(byte_array)
            .map_err(|e| format!("byte array conversion failed: {}", e))?;

        Ok(bytes)
    }
}

impl Display for JavaBlobStoreBackend {
    fn fmt(&self, f: &mut Formatter<'_>) -> fmt::Result {
        write!(f, "JavaBlobStoreBackend")
    }
}

#[async_trait]
impl ObjectStore for JavaBlobStoreBackend {
    async fn put_opts(&self, _location: &ObjectPath, _payload: PutPayload, _opts: PutOptions) -> Result<PutResult> {
        Err(object_store::Error::NotSupported {
            source: "JavaBlobStoreBackend is read-only".into(),
        })
    }

    async fn put_multipart_opts(&self, _location: &ObjectPath, _opts: PutMultipartOptions) -> Result<Box<dyn MultipartUpload>> {
        Err(object_store::Error::NotSupported {
            source: "JavaBlobStoreBackend is read-only".into(),
        })
    }

    async fn get_opts(&self, location: &ObjectPath, options: GetOptions) -> Result<GetResult> {
        let path_str = location.as_ref();
        log_info!("[JavaBlobStoreBackend] get_opts: path={}, range={:?}", path_str, options.range);

        let (bytes, total_size) = if let Some(range) = &options.range {
            // Range read — fetch only the requested byte range
            let (offset, length) = match range {
                object_store::GetRange::Bounded(r) => (r.start as u64, (r.end - r.start) as u64),
                object_store::GetRange::Offset(o) => {
                    // Read from offset to end — need full file first to know size
                    let full = self.read_blob(path_str).map_err(|e| {
                        object_store::Error::Generic { store: "JavaBlobStoreBackend", source: e.into() }
                    })?;
                    let total = full.len();
                    let start = *o as usize;
                    let sliced = full[start..].to_vec();
                    return Ok(GetResult {
                        payload: GetResultPayload::Stream(
                            Box::pin(futures::stream::once(async { Ok(bytes::Bytes::from(sliced)) }))
                        ),
                        meta: ObjectMeta {
                            location: location.clone(),
                            last_modified: chrono::Utc::now(),
                            size: total as u64,
                            e_tag: None,
                            version: None,
                        },
                        range: std::ops::Range { start: start as u64, end: total as u64 },
                        attributes: Default::default(),
                    });
                }
                object_store::GetRange::Suffix(s) => {
                    let full = self.read_blob(path_str).map_err(|e| {
                        object_store::Error::Generic { store: "JavaBlobStoreBackend", source: e.into() }
                    })?;
                    let total = full.len();
                    let start = total - (*s as usize);
                    let sliced = full[start..].to_vec();
                    return Ok(GetResult {
                        payload: GetResultPayload::Stream(
                            Box::pin(futures::stream::once(async { Ok(bytes::Bytes::from(sliced)) }))
                        ),
                        meta: ObjectMeta {
                            location: location.clone(),
                            last_modified: chrono::Utc::now(),
                            size: total as u64,
                            e_tag: None,
                            version: None,
                        },
                        range: std::ops::Range { start: start as u64, end: total as u64 },
                        attributes: Default::default(),
                    });
                }
            };
            let range_bytes = self.read_blob_range(path_str, offset, length).map_err(|e| {
                object_store::Error::Generic { store: "JavaBlobStoreBackend", source: e.into() }
            })?;
            let total = offset + length; // approximate
            (range_bytes, total as usize)
        } else {
            // Full file read
            let full = self.read_blob(path_str).map_err(|e| {
                log_info!("[JavaBlobStoreBackend] get_opts FAILED: path={}, error={}", path_str, e);
                object_store::Error::Generic { store: "JavaBlobStoreBackend", source: e.into() }
            })?;
            let size = full.len();
            (full, size)
        };

        log_info!("[JavaBlobStoreBackend] get_opts SUCCESS: path={}, bytes={}", path_str, bytes.len());

        let data = bytes::Bytes::from(bytes);
        let data_len = data.len();
        let meta = ObjectMeta {
            location: location.clone(),
            last_modified: chrono::Utc::now(),
            size: total_size as u64,
            e_tag: None,
            version: None,
        };

        Ok(GetResult {
            payload: GetResultPayload::Stream(
                Box::pin(futures::stream::once(async { Ok(data) }))
            ),
            meta,
            range: std::ops::Range { start: 0, end: data_len as u64 },
            attributes: Default::default(),
        })
    }

    async fn get_range(&self, location: &ObjectPath, range: Range<u64>) -> Result<Bytes> {
        let path_str = location.as_ref();
        let offset = range.start;
        let length = range.end - range.start;
        log_info!("[JavaBlobStoreBackend] get_range: path={}, offset={}, length={}", path_str, offset, length);

        let bytes = self.read_blob_range(path_str, offset, length).map_err(|e| {
            object_store::Error::Generic { store: "JavaBlobStoreBackend", source: e.into() }
        })?;

        Ok(bytes::Bytes::from(bytes))
    }

    async fn get_ranges(&self, location: &ObjectPath, ranges: &[Range<u64>]) -> Result<Vec<Bytes>> {
        let path_str = location.as_ref();
        log_info!("[JavaBlobStoreBackend] get_ranges: path={}, num_ranges={}", path_str, ranges.len());

        let mut results = Vec::with_capacity(ranges.len());
        for range in ranges {
            let bytes = self.read_blob_range(path_str, range.start, range.end - range.start)
                .map_err(|e| {
                    object_store::Error::Generic { store: "JavaBlobStoreBackend", source: e.into() }
                })?;
            results.push(bytes::Bytes::from(bytes));
        }
        Ok(results)
    }

    async fn head(&self, location: &ObjectPath) -> Result<ObjectMeta> {
        let path_str = location.as_ref();
        log_info!("[JavaBlobStoreBackend] head: path={}", path_str);

        // Extract the blob name (last segment) and prefix (parent path)
        let blob_name = path_str.rsplit('/').next().unwrap_or(path_str);
        let prefix = if path_str.contains('/') {
            &path_str[..path_str.len() - blob_name.len()]
        } else {
            ""
        };

        // Try to get size via listBlobsByPrefix to avoid reading the full file
        let size = self.get_blob_size(path_str).unwrap_or_else(|_| {
            // Fallback: read full file to get size
            self.read_blob(path_str).map(|b| b.len() as u64).unwrap_or(0)
        });

        Ok(ObjectMeta {
            location: location.clone(),
            last_modified: chrono::Utc::now(),
            size,
            e_tag: None,
            version: None,
        })
    }

    async fn delete(&self, _location: &ObjectPath) -> Result<()> {
        Err(object_store::Error::NotSupported {
            source: "JavaBlobStoreBackend is read-only".into(),
        })
    }

    fn list(&self, _prefix: Option<&ObjectPath>) -> BoxStream<'static, Result<ObjectMeta>> {
        Box::pin(futures::stream::empty())
    }

    async fn list_with_delimiter(&self, _prefix: Option<&ObjectPath>) -> Result<ListResult> {
        Ok(ListResult {
            common_prefixes: vec![],
            objects: vec![],
        })
    }

    async fn copy(&self, _from: &ObjectPath, _to: &ObjectPath) -> Result<()> {
        Err(object_store::Error::NotSupported {
            source: "JavaBlobStoreBackend is read-only".into(),
        })
    }

    async fn copy_if_not_exists(&self, _from: &ObjectPath, _to: &ObjectPath) -> Result<()> {
        Err(object_store::Error::NotSupported {
            source: "JavaBlobStoreBackend is read-only".into(),
        })
    }
}
