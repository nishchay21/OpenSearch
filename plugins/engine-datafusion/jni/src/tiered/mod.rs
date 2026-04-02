/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

//! Tiered storage — TieredObjectStore + RemoteObjectStore + FileRegistry.
//!
//! Lives inside DataFusion's .so so that all memory allocations (GetResult,
//! ObjectMeta, Bytes, Strings) use the same mimalloc heap as DataFusion.
//! This avoids SIGSEGV from cross-.so allocator mismatch when DataFusion
//! drops objects allocated by a different .so's allocator.
//!
//! JNI functions target `TieredStoreNativeBridgeImpl` in DataFusion's
//! classloader. The tiered-storage module calls through the shared
//! `TieredStoreNativeBridge` interface, which dispatches here.

pub mod file_registry;
pub mod foyer_cache;
pub mod foyer_cache_manager;
pub mod remote_object_store;
pub mod store_factory;
pub mod tiered_object_store;

pub use foyer_cache_manager::FoyerCacheManager;

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jint, jlong, jlongArray};
use object_store::ObjectStore;
use std::sync::Arc;
use vectorized_exec_spi::log_info;

use file_registry::FileRegistry;
use tiered_object_store::TieredObjectStore;

// ---------------------------------------------------------------------------
// Helper: reconstruct Arc<FileRegistry> from raw pointer WITHOUT dropping it.
// ---------------------------------------------------------------------------
unsafe fn registry_from_ptr(ptr: jlong) -> Arc<FileRegistry> {
    let raw = ptr as *const FileRegistry;
    Arc::increment_strong_count(raw);
    Arc::from_raw(raw)
}

// ---------------------------------------------------------------------------
// ObjectStore lifecycle
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_org_opensearch_datafusion_jni_TieredStoreNativeBridgeImpl_nativeInitLogger(
    env: JNIEnv,
    _class: JClass,
) {
    vectorized_exec_spi::logger::init_logger_from_env(&env);
}

/// Create a global TieredObjectStore with an optional Foyer page cache.
/// No remote stores yet — added via addRemoteStore.
/// Returns long[3]: [objectStoreDataPtr, objectStoreVtablePtr, registryPtr].
///
/// `disk_cache_bytes == 0` disables the page cache entirely.
#[no_mangle]
pub extern "system" fn Java_org_opensearch_datafusion_jni_TieredStoreNativeBridgeImpl_nativeCreateTieredObjectStore(
    mut env: JNIEnv, _class: JClass,
    disk_cache_bytes: jlong,
    disk_cache_dir: JString,
) -> jlongArray {
    // Build Foyer cache manager inline if cache is configured.
    let foyer: Option<Arc<FoyerCacheManager>> = if disk_cache_bytes > 0 {
        let dir: String = match env.get_string(&disk_cache_dir) {
            Ok(s) => s.into(),
            Err(e) => {
                let _ = env.throw_new("java/lang/IllegalArgumentException",
                    format!("Invalid disk_cache_dir: {:?}", e));
                return std::ptr::null_mut();
            }
        };
        log_info!(
            "[TieredStoreNativeBridge] Foyer page cache enabled: disk={}B, dir={}",
            disk_cache_bytes, dir
        );
        Some(Arc::new(FoyerCacheManager::new(disk_cache_bytes as usize, dir)))
    } else {
        log_info!("[TieredStoreNativeBridge] Foyer page cache disabled (disk_cache_bytes=0)");
        None
    };

    log_info!("[TieredStoreNativeBridge] createTieredObjectStore (foyer={})",
        if foyer.is_some() { "enabled" } else { "disabled" });

    let (tiered_store, registry) = TieredObjectStore::new_with_cache(foyer);
    let store: Arc<dyn ObjectStore> = Arc::new(tiered_store);

    let raw: *const dyn ObjectStore = Arc::into_raw(store);
    let components: [usize; 2] = unsafe { std::mem::transmute(raw) };
    let registry_ptr = Arc::into_raw(registry) as usize;

    let result = match env.new_long_array(3) {
        Ok(arr) => arr,
        Err(e) => {
            let _ = env.throw_new("java/lang/RuntimeException",
                format!("Failed to create array: {:?}", e));
            return std::ptr::null_mut();
        }
    };
    let values = [components[0] as jlong, components[1] as jlong, registry_ptr as jlong];
    if let Err(e) = env.set_long_array_region(&result, 0, &values) {
        let _ = env.throw_new("java/lang/RuntimeException",
            format!("Failed to set array: {:?}", e));
        return std::ptr::null_mut();
    }

    log_info!("[TieredStoreNativeBridge] created: data_ptr={}, vtable_ptr={}, registry_ptr={}",
        components[0], components[1], registry_ptr);
    result.as_raw()
}

/// Add a remote store for a repository. Creates the appropriate ObjectStore
/// based on store_type ("fs", "s3", "gcs", "azure") and config JSON.
/// Idempotent — skips if already registered.
#[no_mangle]
pub extern "system" fn Java_org_opensearch_datafusion_jni_TieredStoreNativeBridgeImpl_nativeAddRemoteStore(
    mut env: JNIEnv, _class: JClass, registry_ptr: jlong, repo_key: JString,
    store_type: JString, config_json: JString,
) {
    let registry = unsafe { registry_from_ptr(registry_ptr) };
    let repo_key_str: String = match env.get_string(&repo_key) {
        Ok(s) => s.into(),
        Err(e) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException",
                format!("Invalid repo_key: {:?}", e));
            return;
        }
    };
    let store_type_str: String = match env.get_string(&store_type) {
        Ok(s) => s.into(),
        Err(e) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException",
                format!("Invalid store_type: {:?}", e));
            return;
        }
    };
    let config_json_str: String = match env.get_string(&config_json) {
        Ok(s) => s.into(),
        Err(e) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException",
                format!("Invalid config_json: {:?}", e));
            return;
        }
    };

    log_info!("[TieredStoreNativeBridge] addRemoteStore: repo_key={}, store_type={}", repo_key_str, store_type_str);

    let remote_store = store_factory::create_object_store(&store_type_str, &config_json_str);
    registry.add_remote_store(&repo_key_str, remote_store);
}

#[no_mangle]
pub extern "system" fn Java_org_opensearch_datafusion_jni_TieredStoreNativeBridgeImpl_nativeDestroyTieredObjectStore(
    _env: JNIEnv, _class: JClass, data_ptr: jlong, vtable_ptr: jlong,
) {
    if data_ptr != 0 && vtable_ptr != 0 {
        unsafe {
            let fat_ptr: *const dyn ObjectStore = std::mem::transmute([
                data_ptr as usize,
                vtable_ptr as usize,
            ]);
            let _ = Arc::from_raw(fat_ptr);
        };
        log_info!("[TieredStoreNativeBridge] destroyTieredObjectStore");
    }
}

#[no_mangle]
pub extern "system" fn Java_org_opensearch_datafusion_jni_TieredStoreNativeBridgeImpl_nativeDestroyFileRegistry(
    _env: JNIEnv, _class: JClass, registry_ptr: jlong,
) {
    if registry_ptr != 0 {
        unsafe {
            let _ = Arc::from_raw(registry_ptr as *const FileRegistry);
        };
        log_info!("[TieredStoreNativeBridge] destroyFileRegistry");
    }
}

// ---------------------------------------------------------------------------
// FileRegistry operations
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_org_opensearch_datafusion_jni_TieredStoreNativeBridgeImpl_nativeRegistryMarkSyncedToRemote(
    mut env: JNIEnv, _class: JClass, registry_ptr: jlong, key: JString, remote_path: JString, repo_key: JString,
) {
    let registry = unsafe { registry_from_ptr(registry_ptr) };
    let key_str: String = env.get_string(&key).unwrap().into();
    let remote_str: String = env.get_string(&remote_path).unwrap().into();
    let repo_key_str: String = env.get_string(&repo_key).unwrap().into();
    registry.mark_synced_to_remote(&key_str, &remote_str, &repo_key_str);
}

#[no_mangle]
pub extern "system" fn Java_org_opensearch_datafusion_jni_TieredStoreNativeBridgeImpl_nativeRegistryRegisterLocal(
    mut env: JNIEnv, _class: JClass, registry_ptr: jlong, key: JString, size: jlong,
) {
    let registry = unsafe { registry_from_ptr(registry_ptr) };
    let key_str: String = env.get_string(&key).unwrap().into();
    registry.register_local(&key_str, size as u64);
}

#[no_mangle]
pub extern "system" fn Java_org_opensearch_datafusion_jni_TieredStoreNativeBridgeImpl_nativeRegistryAcquireRead(
    mut env: JNIEnv, _class: JClass, registry_ptr: jlong, key: JString,
) -> jint {
    let registry = unsafe { registry_from_ptr(registry_ptr) };
    let key_str: String = env.get_string(&key).unwrap().into();
    let loc = registry.acquire_read(&key_str);
    match loc {
        file_registry::FileLocation::Local => 0,
        file_registry::FileLocation::Remote => 1,
        file_registry::FileLocation::Both => 2,
    }
}

#[no_mangle]
pub extern "system" fn Java_org_opensearch_datafusion_jni_TieredStoreNativeBridgeImpl_nativeRegistryReleaseRead(
    mut env: JNIEnv, _class: JClass, registry_ptr: jlong, key: JString,
) -> jint {
    let registry = unsafe { registry_from_ptr(registry_ptr) };
    let key_str: String = env.get_string(&key).unwrap().into();
    registry.release_read(&key_str) as jint
}

#[no_mangle]
pub extern "system" fn Java_org_opensearch_datafusion_jni_TieredStoreNativeBridgeImpl_nativeRegistryRemove(
    mut env: JNIEnv, _class: JClass, registry_ptr: jlong, key: JString,
) {
    let registry = unsafe { registry_from_ptr(registry_ptr) };
    let key_str: String = env.get_string(&key).unwrap().into();
    registry.remove(&key_str);
}

#[no_mangle]
pub extern "system" fn Java_org_opensearch_datafusion_jni_TieredStoreNativeBridgeImpl_nativeRegistryMarkLocalDeleted(
    mut env: JNIEnv, _class: JClass, registry_ptr: jlong, key: JString,
) {
    let registry = unsafe { registry_from_ptr(registry_ptr) };
    let key_str: String = env.get_string(&key).unwrap().into();
    registry.mark_local_deleted(&key_str);
}

#[no_mangle]
pub extern "system" fn Java_org_opensearch_datafusion_jni_TieredStoreNativeBridgeImpl_nativeRegistryFileCount(
    _env: JNIEnv, _class: JClass, registry_ptr: jlong,
) -> jint {
    let registry = unsafe { registry_from_ptr(registry_ptr) };
    registry.file_count() as jint
}

#[no_mangle]
pub extern "system" fn Java_org_opensearch_datafusion_jni_TieredStoreNativeBridgeImpl_nativeRegistryLogSummary(
    _env: JNIEnv, _class: JClass, registry_ptr: jlong,
) {
    let registry = unsafe { registry_from_ptr(registry_ptr) };
    registry.log_summary();
}

#[no_mangle]
pub extern "system" fn Java_org_opensearch_datafusion_jni_TieredStoreNativeBridgeImpl_nativeRegistryGetLocation(
    mut env: JNIEnv, _class: JClass, registry_ptr: jlong, key: JString,
) -> jint {
    let registry = unsafe { registry_from_ptr(registry_ptr) };
    let key_str: String = env.get_string(&key).unwrap().into();
    match registry.get_location(&key_str) {
        Some(file_registry::FileLocation::Local) => 0,
        Some(file_registry::FileLocation::Remote) => 1,
        Some(file_registry::FileLocation::Both) => 2,
        None => -1,
    }
}

#[no_mangle]
pub extern "system" fn Java_org_opensearch_datafusion_jni_TieredStoreNativeBridgeImpl_nativeRegistryGetActiveReads(
    mut env: JNIEnv, _class: JClass, registry_ptr: jlong, key: JString,
) -> jlong {
    let registry = unsafe { registry_from_ptr(registry_ptr) };
    let key_str: String = env.get_string(&key).unwrap().into();
    registry.get_active_reads(&key_str) as jlong
}

#[no_mangle]
pub extern "system" fn Java_org_opensearch_datafusion_jni_TieredStoreNativeBridgeImpl_nativeRegistryGetSize(
    mut env: JNIEnv, _class: JClass, registry_ptr: jlong, key: JString,
) -> jlong {
    let registry = unsafe { registry_from_ptr(registry_ptr) };
    let key_str: String = env.get_string(&key).unwrap().into();
    registry.get_size(&key_str).map(|s| s as jlong).unwrap_or(-1)
}

#[no_mangle]
pub extern "system" fn Java_org_opensearch_datafusion_jni_TieredStoreNativeBridgeImpl_nativeRegistryAddPendingDelete(
    mut env: JNIEnv, _class: JClass, registry_ptr: jlong, local_path: JString,
) {
    let registry = unsafe { registry_from_ptr(registry_ptr) };
    let path_str: String = env.get_string(&local_path).unwrap().into();
    registry.add_pending_delete(&path_str);
}

#[no_mangle]
pub extern "system" fn Java_org_opensearch_datafusion_jni_TieredStoreNativeBridgeImpl_nativeRegistrySweepPendingDeletes(
    _env: JNIEnv, _class: JClass, registry_ptr: jlong,
) -> jint {
    let registry = unsafe { registry_from_ptr(registry_ptr) };
    registry.sweep_pending_deletes() as jint
}

#[no_mangle]
pub extern "system" fn Java_org_opensearch_datafusion_jni_TieredStoreNativeBridgeImpl_nativeRegistryPendingDeleteCount(
    _env: JNIEnv, _class: JClass, registry_ptr: jlong,
) -> jint {
    let registry = unsafe { registry_from_ptr(registry_ptr) };
    registry.pending_delete_count() as jint
}

