use jni::objects::{JByteArray, JClass, JObjectArray, JString};
use jni::sys::{jbyteArray, jint, jlong};
use jni::{JNIEnv};
use crate::custom_cache_manager::CustomCacheManager;
use crate::util::{parse_string_arr};
use crate::cache;
use crate::DataFusionRuntime;
use datafusion::execution::cache::cache_unit::DefaultFilesMetadataCache;
use std::sync::Arc;
use bytes::Bytes;
use vectorized_exec_spi::{log_info, log_error, log_debug};

// Default page cache budgets — overridden by Java settings via createCache()
const DEFAULT_PAGE_CACHE_MEMORY_BYTES: usize = 256 * 1024 * 1024;        // 256 MB L1 memory
const DEFAULT_PAGE_CACHE_DISK_BYTES:   usize = 10 * 1024 * 1024 * 1024;  // 10 GB L2 disk
const DEFAULT_PAGE_CACHE_DIR: &str = "/tmp/foyer-page-cache";

/// Parse the eviction_type string for PAGES cache type.
/// Expected format: "<disk_capacity_bytes>|<disk_dir>"
/// Falls back to defaults if the string is malformed (e.g. plain "LRU" from old Java code).
fn parse_page_cache_params(eviction_str: &str) -> (usize, String) {
    if let Some(sep) = eviction_str.find('|') {
        let disk_bytes_str = &eviction_str[..sep];
        let disk_dir = eviction_str[sep + 1..].to_string();
        if let Ok(disk_bytes) = disk_bytes_str.parse::<usize>() {
            let dir = if disk_dir.is_empty() { DEFAULT_PAGE_CACHE_DIR.to_string() } else { disk_dir };
            return (disk_bytes, dir);
        }
    }
    // Fallback: plain eviction type like "LRU" from legacy config
    log_info!(
        "[FOYER-PAGE-CACHE] eviction_type '{}' is not in '<disk_bytes>|<dir>' format; \
         using defaults: disk={}B, dir={}",
        eviction_str, DEFAULT_PAGE_CACHE_DISK_BYTES, DEFAULT_PAGE_CACHE_DIR
    );
    (DEFAULT_PAGE_CACHE_DISK_BYTES, DEFAULT_PAGE_CACHE_DIR.to_string())
}

/// Create a CustomCacheManager instance
#[no_mangle]
pub extern "system" fn Java_org_opensearch_datafusion_jni_NativeBridge_createCustomCacheManager(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    let manager = CustomCacheManager::new();
    Box::into_raw(Box::new(manager)) as jlong
}

/// Destroy a CustomCacheManager instance
#[no_mangle]
pub extern "system" fn Java_org_opensearch_datafusion_jni_NativeBridge_destroyCustomCacheManager(
    _env: JNIEnv,
    _class: JClass,
    cache_manager_ptr: jlong,
) {
    if cache_manager_ptr != 0 {
        let _ = unsafe { Box::from_raw(cache_manager_ptr as *mut CustomCacheManager) };
        log_info!("[CACHE INFO] CustomCacheManager destroyed");
    }
}

/// Generic cache creation method that handles all cache types
#[no_mangle]
pub extern "system" fn Java_org_opensearch_datafusion_jni_NativeBridge_createCache(
    mut env: JNIEnv,
    _class: JClass,
    cache_manager_ptr: jlong,
    cache_type: JString,
    size_limit: jlong,
    eviction_type: JString,
) -> jlong {
    if cache_manager_ptr == 0 {
        let _ = env.throw_new("java/lang/DataFusionException", "CustomCacheManager pointer is null");
        return 0;
    }

    let cache_type_str: String = match env.get_string(&cache_type) {
        Ok(s) => s.into(),
        Err(e) => {
            let msg = format!("Failed to convert cache_type string: {}", e);
            log_debug!("{}", msg);
            let _ = env.throw_new("java/lang/DataFusionException", &msg);
            return 0;
        }
    };

    let eviction_type_str: String = match env.get_string(&eviction_type) {
        Ok(s) => s.into(),
        Err(e) => {
            let msg = format!("Failed to convert eviction_type string: {}", e);
            log_debug!("{}", msg);
            let _ = env.throw_new("java/lang/DataFusionException", &msg);
            return 0;
        }
    };

    log_info!("[CACHE INFO] Creating cache: type={}, size_limit={}, eviction_type={}",
             cache_type_str, size_limit, eviction_type_str);

    let manager = unsafe { &mut *(cache_manager_ptr as *mut CustomCacheManager) };

    match cache_type_str.as_str() {
        cache::CACHE_TYPE_METADATA => {
            let inner_cache = DefaultFilesMetadataCache::new(size_limit as usize);
            let metadata_cache = Arc::new(cache::MutexFileMetadataCache::new(inner_cache));
            manager.set_file_metadata_cache(metadata_cache);
            log_info!("[CACHE INFO] Successfully created {} cache in CustomCacheManager", cache_type_str);
        }
        cache::CACHE_TYPE_STATS => {
            // Create statistics cache with LRU policy
            let stats_cache = Arc::new(crate::statistics_cache::CustomStatisticsCache::new(
                crate::eviction_policy::PolicyType::Lru,
                size_limit as usize,
                0.8
            ));
            manager.set_statistics_cache(stats_cache);
            log_info!("[CACHE INFO] Successfully created {} cache in CustomCacheManager", cache_type_str);
        }
        cache::CACHE_TYPE_PAGES => {
            // Create Foyer hybrid (memory + disk) page cache — Cache Layer 3.
            // `size_limit` is the L1 memory budget in bytes (e.g. 256 MB).
            // The L2 disk budget and disk directory come from the Java settings
            // PAGE_CACHE_DISK_CAPACITY and PAGE_CACHE_DIR; for this cache creation
            // call they are passed via the eviction_type string as
            // "<disk_bytes>|<disk_dir>".
            // Format: eviction_type_str = "<disk_capacity_bytes>|<disk_dir_path>"
            let (disk_bytes, disk_dir) = parse_page_cache_params(&eviction_type_str);
            log_info!(
                "[FOYER-PAGE-CACHE] creating hybrid page cache: L1-mem={}B, L2-disk={}B, dir={}",
                size_limit, disk_bytes, disk_dir
            );
            let page_cache = Arc::new(crate::foyer_cache::FoyerDiskPageCache::new(
                size_limit as usize,
                disk_bytes,
                disk_dir,
            ));
            manager.set_page_cache(page_cache);
            log_info!(
                "[FOYER-PAGE-CACHE] successfully created Foyer hybrid page cache in CustomCacheManager"
            );
        }
        _ => {
            let msg = format!("Invalid cache type: {}", cache_type_str);
            log_error!("[CACHE ERROR] {}", msg);
            let _ = env.throw_new("java/lang/DataFusionException", &msg);
            return 0;
        }
    }

    0
}

#[no_mangle]
pub extern "system" fn Java_org_opensearch_datafusion_jni_NativeBridge_cacheManagerAddFiles(
    mut env: JNIEnv,
    _class: JClass,
    runtime_env_ptr: jlong,
    files: JObjectArray,
) {
    if runtime_env_ptr == 0 {
        let _ = env.throw_new("java/lang/NullPointerException", "Cache manager pointer is null");
        return;
    }

    let runtime_env = unsafe { &*(runtime_env_ptr as *const DataFusionRuntime) };

    match &runtime_env.custom_cache_manager {
        Some(manager) => {
            let file_paths: Vec<String> = match parse_string_arr(&mut env, files) {
                Ok(paths) => paths,
                Err(e) => {
                    let msg = format!("Failed to parse file paths array: {}", e);
                    log_debug!("[CACHE ERROR] {}", msg);
                    let _ = env.throw_new("org/opensearch/datafusion/DataFusionException", &msg);
                    return;
                }
            };

            match manager.add_files(&file_paths) {
                Ok(results) => {
                    let mut failed_files = Vec::new();
                    for (file_path, success) in results {
                        if !success {
                            failed_files.push(file_path);
                        }
                    }

                    if !failed_files.is_empty() {
                        let msg = format!("Failed to add {} files to cache: {:?}", failed_files.len(), failed_files);
                        log_debug!("[CACHE ERROR] {}", msg);
                    }
                }
                Err(e) => {
                    let msg = format!("Failed to add files to cache: {}", e);
                    log_error!("[CACHE ERROR] {}", msg);
                    let _ = env.throw_new("org/opensearch/datafusion/DataFusionException", &msg);
                }
            }
        }
        None => {
            let msg = "No custom cache manager available";
            log_debug!("[CACHE ERROR] {}", msg);
            let _ = env.throw_new("org/opensearch/datafusion/DataFusionException", msg);
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_org_opensearch_datafusion_jni_NativeBridge_cacheManagerRemoveFiles(
    mut env: JNIEnv,
    _class: JClass,
    runtime_env_ptr: jlong,
    files: JObjectArray,
) {
    if runtime_env_ptr == 0 {
        let _ = env.throw_new("java/lang/NullPointerException", "Cache manager pointer is null");
        return;
    }

    let runtime_env = unsafe { &*(runtime_env_ptr as *const DataFusionRuntime) };

    let file_paths: Vec<String> = match parse_string_arr(&mut env, files) {
        Ok(paths) => paths,
        Err(e) => {
            let msg = format!("Failed to parse file paths array: {}", e);
            log_debug!("[CACHE ERROR] {}", msg);
            let _ = env.throw_new("org/opensearch/datafusion/DataFusionException", &msg);
            return;
        }
    };

    match &runtime_env.custom_cache_manager {
        Some(manager) => {
            match manager.remove_files(&file_paths) {
                Ok(results) => {
                    let mut failed_files = Vec::new();
                    for (file_path, removed) in results {
                        if !removed {
                            failed_files.push(file_path);
                        }
                    }

                    if !failed_files.is_empty() {
                        let msg = format!("Failed to remove {} files from cache: {:?}", failed_files.len(), failed_files);
                        log_debug!("[CACHE ERROR] {}", msg);
                    }
                }
                Err(e) => {
                    let msg = format!("Failed to remove files from cache: {}", e);
                    log_error!("[CACHE ERROR] {}", msg);
                    let _ = env.throw_new("org/opensearch/datafusion/DataFusionException", &msg);
                }
            }
        }
        None => {
            let msg = "No custom cache manager available";
            log_debug!("[CACHE ERROR] {}", msg);
            let _ = env.throw_new("org/opensearch/datafusion/DataFusionException", msg);
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_org_opensearch_datafusion_jni_NativeBridge_cacheManagerClear(
    mut env: JNIEnv,
    _class: JClass,
    runtime_env_ptr: jlong,
) {
    if runtime_env_ptr == 0 {
        let _ = env.throw_new("java/lang/NullPointerException", "Cache manager pointer is null");
        return;
    }

    let runtime_env = unsafe { &*(runtime_env_ptr as *const DataFusionRuntime) };

    match &runtime_env.custom_cache_manager {
        Some(manager) => {
            manager.clear_all();
            log_info!("[CACHE INFO] Successfully cleared all caches");
        }
        None => {
            let msg = "No custom cache manager available";
            log_debug!("[CACHE ERROR] {}", msg);
            let _ = env.throw_new("org/opensearch/datafusion/DataFusionException", msg);
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_org_opensearch_datafusion_jni_NativeBridge_cacheManagerUpdateSizeLimitForCacheType(
    mut env: JNIEnv,
    _class: JClass,
    runtime_env_ptr: jlong,
    cache_type: JString,
    new_size_limit: jlong,
) -> bool {
    if runtime_env_ptr == 0 {
        let _ = env.throw_new("java/lang/NullPointerException", "Cache manager pointer is null");
        return false;
    }

    let runtime_env = unsafe { &*(runtime_env_ptr as *const DataFusionRuntime) };

    let cache_type: String = match env.get_string(&cache_type) {
        Ok(s) => s.into(),
        Err(e) => {
            let msg = format!("Failed to convert cache type string: {}", e);
            log_error!("[CACHE ERROR] {}", msg);
            let _ = env.throw_new("org/opensearch/datafusion/DataFusionException", &msg);
            return false;
        }
    };

    match &runtime_env.custom_cache_manager {
        Some(manager) => {
            match cache_type.as_str() {
                cache::CACHE_TYPE_METADATA => {
                    manager.update_metadata_cache_limit(new_size_limit as usize);
                    true
                }
                _ => {
                    let msg = format!("Unknown cache type: {}", cache_type);
                    log_error!("[CACHE ERROR] {}", msg);
                    let _ = env.throw_new("org/opensearch/datafusion/DataFusionException", &msg);
                    false
                }
            }
        }
        None => {
            let msg = "No custom cache manager available";
            log_debug!("[CACHE ERROR] {}", msg);
            let _ = env.throw_new("org/opensearch/datafusion/DataFusionException", msg);
            false
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_org_opensearch_datafusion_jni_NativeBridge_cacheManagerGetMemoryConsumedForCacheType(
    mut env: JNIEnv,
    _class: JClass,
    runtime_env_ptr: jlong,
    cache_type: JString,
) -> jlong {
    if runtime_env_ptr == 0 {
        let _ = env.throw_new("java/lang/NullPointerException", "Cache manager pointer is null");
        return 0;
    }

    let runtime_env = unsafe { &*(runtime_env_ptr as *const DataFusionRuntime) };

    let cache_type: String = match env.get_string(&cache_type) {
        Ok(s) => s.into(),
        Err(e) => {
            let msg = format!("Failed to convert cache type string: {}", e);
            log_error!("[CACHE ERROR] {}", msg);
            let _ = env.throw_new("org/opensearch/datafusion/DataFusionException", &msg);
            return 0;
        }
    };

    match &runtime_env.custom_cache_manager {
        Some(manager) => {
            match manager.get_memory_consumed_by_type(&cache_type) {
                Ok(size) => size as jlong,
                Err(e) => {
                    let msg = format!("Failed to get memory consumed for cache type {}: {}", cache_type, e);
                    log_debug!("[CACHE ERROR] {}", msg);
                    let _ = env.throw_new("org/opensearch/datafusion/DataFusionException", &msg);
                    0
                }
            }
        }
        None => {
            let msg = "No custom cache manager available";
            log_debug!("[CACHE ERROR] {}", msg);
            let _ = env.throw_new("org/opensearch/datafusion/DataFusionException", msg);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_org_opensearch_datafusion_jni_NativeBridge_cacheManagerGetTotalMemoryConsumed(
    mut env: JNIEnv,
    _class: JClass,
    runtime_env_ptr: jlong,
) -> jlong {
    if runtime_env_ptr == 0 {
        let _ = env.throw_new("java/lang/NullPointerException", "Cache manager pointer is null");
        return 0;
    }

    let runtime_env = unsafe { &*(runtime_env_ptr as *const DataFusionRuntime) };

    match &runtime_env.custom_cache_manager {
        Some(manager) => {
            manager.get_total_memory_consumed() as jlong
        }
        None => {
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_org_opensearch_datafusion_jni_NativeBridge_cacheManagerClearByCacheType(
    mut env: JNIEnv,
    _class: JClass,
    runtime_env_ptr: jlong,
    cache_type: JString,
) {
    if runtime_env_ptr == 0 {
        let _ = env.throw_new("java/lang/NullPointerException", "Cache manager pointer is null");
        return;
    }

    let runtime_env = unsafe { &*(runtime_env_ptr as *const DataFusionRuntime) };

    let cache_type: String = match env.get_string(&cache_type) {
        Ok(s) => s.into(),
        Err(e) => {
            let msg = format!("Failed to convert cache type string: {}", e);
            log_debug!("{}", msg);
            let _ = env.throw_new("org/opensearch/datafusion/DataFusionException", &msg);
            return;
        }
    };

    match &runtime_env.custom_cache_manager {
        Some(manager) => {
            match manager.clear_cache_type(&cache_type) {
                Ok(_) => {
                    log_info!("[CACHE INFO] Cache Type: {} cleared", cache_type);
                }
                Err(e) => {
                    log_error!("[CACHE ERROR] {}", e);
                    let _ = env.throw_new("org/opensearch/datafusion/DataFusionException", &e);
                }
            }
        }
        None => {
            let msg = "No custom cache manager available";
            log_debug!("[CACHE ERROR] {}", msg);
            let _ = env.throw_new("org/opensearch/datafusion/DataFusionException", msg);
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_org_opensearch_datafusion_jni_NativeBridge_cacheManagerGetItemByCacheType(
    mut env: JNIEnv,
    _class: JClass,
    runtime_env_ptr: jlong,
    cache_type: JString,
    file_path: JString,
) -> bool {
    if runtime_env_ptr == 0 {
        let _ = env.throw_new("java/lang/NullPointerException", "Cache manager pointer is null");
        return false;
    }

    let runtime_env = unsafe { &*(runtime_env_ptr as *const DataFusionRuntime) };

    let cache_type: String = match env.get_string(&cache_type) {
        Ok(s) => s.into(),
        Err(e) => {
            let msg = format!("Failed to convert cache type string: {}", e);
            log_debug!("{}", msg);
            let _ = env.throw_new("org/opensearch/datafusion/DataFusionException", &msg);
            return false;
        }
    };

    let file_path: String = match env.get_string(&file_path) {
        Ok(s) => s.into(),
        Err(e) => {
            let msg = format!("Failed to convert file path string: {}", e);
            log_error!("{}", msg);
            let _ = env.throw_new("org/opensearch/datafusion/DataFusionException", &msg);
            return false;
        }
    };

    match &runtime_env.custom_cache_manager {
        Some(manager) => {
            manager.contains_file_by_type(&file_path, &cache_type)
        }
        None => {
            let msg = "No custom cache manager available";
            log_debug!("{}", msg);
            let _ = env.throw_new("org/opensearch/datafusion/DataFusionException", msg);
            false
        }
    }
}

// ============================================================================
// Foyer page cache JNI operations (Layer 3: Parquet byte range cache)
// Called by DataFusionPlugin.FoyerCacheProvider implementation to serve
// PassthroughCacheStrategy → FoyerParquetCacheStrategy in the tiered-storage module.
// ============================================================================

/// Look up a cached byte range for a Parquet file.
/// Returns the cached bytes as a Java byte[], or null on cache miss.
#[no_mangle]
pub extern "system" fn Java_org_opensearch_datafusion_jni_NativeBridge_foyerPageCacheGet(
    mut env: JNIEnv,
    _class: JClass,
    runtime_ptr: jlong,
    path: JString,
    start: jint,
    end: jint,
) -> jbyteArray {
    if runtime_ptr == 0 {
        return std::ptr::null_mut();
    }

    let runtime = unsafe { &*(runtime_ptr as *const DataFusionRuntime) };
    let path_str: String = match env.get_string(&path) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let page_cache = match runtime.custom_cache_manager.as_ref().and_then(|m| m.get_page_cache()) {
        Some(c) => c,
        None => return std::ptr::null_mut(),
    };

    // FoyerDiskPageCache.get() is async (disk I/O). Use get_blocking() since JNI is synchronous.
    match page_cache.get_blocking(&path_str, start as usize, end as usize) {
        Some(bytes) => {
            log_debug!(
                "[FOYER-PAGE-CACHE] JNI get HIT: path={}, range={}..{}, size={}B",
                path_str, start, end, bytes.len()
            );
            match env.byte_array_from_slice(&bytes) {
                Ok(arr) => arr.into_raw(),
                Err(e) => {
                    log_debug!("[FOYER-PAGE-CACHE] JNI get: failed to create Java byte[]: {}", e);
                    std::ptr::null_mut()
                }
            }
        }
        None => {
            log_debug!(
                "[FOYER-PAGE-CACHE] JNI get MISS: path={}, range={}..{}",
                path_str, start, end
            );
            std::ptr::null_mut()
        }
    }
}

/// Store a byte range for a Parquet file in the Foyer page cache.
#[no_mangle]
pub extern "system" fn Java_org_opensearch_datafusion_jni_NativeBridge_foyerPageCachePut(
    mut env: JNIEnv,
    _class: JClass,
    runtime_ptr: jlong,
    path: JString,
    start: jint,
    end: jint,
    data: JByteArray,
) {
    if runtime_ptr == 0 {
        return;
    }

    let runtime = unsafe { &*(runtime_ptr as *const DataFusionRuntime) };
    let path_str: String = match env.get_string(&path) {
        Ok(s) => s.into(),
        Err(e) => {
            log_debug!("[FoyerCache] foyerPageCachePut: failed to convert path: {}", e);
            return;
        }
    };

    let page_cache = match runtime.custom_cache_manager.as_ref().and_then(|m| m.get_page_cache()) {
        Some(c) => c,
        None => return,
    };

    let bytes_vec: Vec<u8> = match env.convert_byte_array(data) {
        Ok(v) => v,
        Err(e) => {
            log_debug!("[FoyerCache] foyerPageCachePut: failed to convert byte array: {}", e);
            return;
        }
    };

    page_cache.put(path_str, start as usize, end as usize, Bytes::from(bytes_vec));
}

/// Evict all cached byte ranges for a given Parquet file.
/// Called when a file is deleted (merged/compacted/tiered out).
#[no_mangle]
pub extern "system" fn Java_org_opensearch_datafusion_jni_NativeBridge_foyerPageCacheEvictFile(
    mut env: JNIEnv,
    _class: JClass,
    runtime_ptr: jlong,
    path: JString,
) {
    if runtime_ptr == 0 {
        return;
    }

    let runtime = unsafe { &*(runtime_ptr as *const DataFusionRuntime) };
    let path_str: String = match env.get_string(&path) {
        Ok(s) => s.into(),
        Err(e) => {
            log_debug!("[FoyerCache] foyerPageCacheEvictFile: failed to convert path: {}", e);
            return;
        }
    };

    if let Some(page_cache) = runtime.custom_cache_manager.as_ref().and_then(|m| m.get_page_cache()) {
        page_cache.evict_file(&path_str);
        log_debug!("[FoyerCache] evicted file from page cache: {}", path_str);
    }
}
