/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

//! Factory for creating `Arc<dyn ObjectStore>` from a store type and JSON config.
//!
//! Supports:
//! - `fs`    — local filesystem (for dev/test and fs-type repositories)
//! - `s3`    — Amazon S3 (with optional SSE-KMS, SSE-S3 encryption)
//! - `gcs`   — Google Cloud Storage
//! - `azure` — Azure Blob Storage

use object_store::ObjectStore;
use std::sync::Arc;
use vectorized_exec_spi::log_info;

use super::remote_object_store::RemoteObjectStore;

/// Create an `ObjectStore` from the given store type and JSON configuration.
pub fn create_object_store(store_type: &str, config_json: &str) -> Arc<dyn ObjectStore> {
    let config: serde_json::Value = serde_json::from_str(config_json)
        .unwrap_or_else(|e| panic!("[store_factory] invalid config JSON: {}", e));

    match store_type {
        "fs" => create_fs_store(&config),
        "s3" => create_s3_store(&config),
        "gcs" => create_gcs_store(&config),
        "azure" => create_azure_store(&config),
        _ => panic!("[store_factory] unknown store type: {}", store_type),
    }
}

// ---------------------------------------------------------------------------
// Filesystem store
// ---------------------------------------------------------------------------

fn create_fs_store(config: &serde_json::Value) -> Arc<dyn ObjectStore> {
    let root = config["root"].as_str()
        .expect("[store_factory] fs config missing 'root'");
    log_info!("[store_factory] creating fs store: root={}", root);
    Arc::new(RemoteObjectStore::new(root))
}

// ---------------------------------------------------------------------------
// Amazon S3
// ---------------------------------------------------------------------------

fn create_s3_store(config: &serde_json::Value) -> Arc<dyn ObjectStore> {
    use object_store::aws::AmazonS3Builder;

    let bucket = config["bucket"].as_str()
        .expect("[store_factory] s3 config missing 'bucket'");

    let mut builder = AmazonS3Builder::new()
        .with_bucket_name(bucket);

    if let Some(region) = config["region"].as_str() {
        builder = builder.with_region(region);
    }
    if let Some(endpoint) = config["endpoint"].as_str() {
        builder = builder.with_endpoint(endpoint);
    }
    if let Some(access_key) = config["access_key_id"].as_str() {
        builder = builder.with_access_key_id(access_key);
    }
    if let Some(secret_key) = config["secret_access_key"].as_str() {
        builder = builder.with_secret_access_key(secret_key);
    }
    if let Some(session_token) = config["session_token"].as_str() {
        builder = builder.with_token(session_token);
    }

    // Encryption: SSE-KMS or SSE-S3
    if let Some(encryption) = config["encryption"].as_str() {
        match encryption {
            "aws:kms" | "SSE-KMS" => {
                if let Some(kms_key_id) = config["kms_key_id"].as_str() {
                    builder = builder.with_sse_kms_encryption(kms_key_id);
                }
            }
            "AES256" | "SSE-S3" => {
                // SSE-S3 (AES256) is the default S3 server-side encryption.
                // No explicit builder config needed — S3 handles it automatically.
                log_info!("[store_factory] s3: SSE-S3 (AES256) encryption — using S3 default");
            }
            other => {
                log_info!("[store_factory] s3: unknown encryption type '{}', skipping", other);
            }
        }
    }

    // Allow virtual-hosted-style or path-style
    if config["virtual_hosted_style_request"].as_bool() == Some(true) {
        builder = builder.with_virtual_hosted_style_request(true);
    }

    // Allow unsigned requests (for public buckets)
    if config["unsigned_payload"].as_bool() == Some(true) {
        builder = builder.with_unsigned_payload(true);
    }

    log_info!("[store_factory] creating s3 store: bucket={}, region={}, encryption={}",
        bucket,
        config["region"].as_str().unwrap_or("default"),
        config["encryption"].as_str().unwrap_or("none"));

    Arc::new(builder.build().expect("[store_factory] failed to build S3 store"))
}

// ---------------------------------------------------------------------------
// Google Cloud Storage
// ---------------------------------------------------------------------------

fn create_gcs_store(config: &serde_json::Value) -> Arc<dyn ObjectStore> {
    use object_store::gcp::GoogleCloudStorageBuilder;

    let bucket = config["bucket"].as_str()
        .expect("[store_factory] gcs config missing 'bucket'");

    let mut builder = GoogleCloudStorageBuilder::new()
        .with_bucket_name(bucket);

    if let Some(sa_key) = config["service_account_key"].as_str() {
        builder = builder.with_service_account_key(sa_key);
    }
    if let Some(app_creds) = config["application_credentials"].as_str() {
        builder = builder.with_application_credentials(app_creds);
    }

    log_info!("[store_factory] creating gcs store: bucket={}", bucket);

    Arc::new(builder.build().expect("[store_factory] failed to build GCS store"))
}

// ---------------------------------------------------------------------------
// Azure Blob Storage
// ---------------------------------------------------------------------------

fn create_azure_store(config: &serde_json::Value) -> Arc<dyn ObjectStore> {
    use object_store::azure::MicrosoftAzureBuilder;

    let container = config["container"].as_str()
        .expect("[store_factory] azure config missing 'container'");

    let mut builder = MicrosoftAzureBuilder::new()
        .with_container_name(container);

    if let Some(account) = config["account"].as_str() {
        builder = builder.with_account(account);
    }
    if let Some(access_key) = config["access_key"].as_str() {
        builder = builder.with_access_key(access_key);
    }
    if let Some(sas_token) = config["sas_token"].as_str() {
        // Parse SAS token into key-value pairs
        let pairs: Vec<(String, String)> = sas_token
            .split('&')
            .filter_map(|pair| {
                let mut parts = pair.splitn(2, '=');
                match (parts.next(), parts.next()) {
                    (Some(k), Some(v)) => Some((k.to_string(), v.to_string())),
                    _ => None,
                }
            })
            .collect();
        builder = builder.with_sas_authorization(pairs);
    }
    if let Some(endpoint) = config["endpoint"].as_str() {
        builder = builder.with_endpoint(endpoint.to_string());
    }

    log_info!("[store_factory] creating azure store: container={}, account={}",
        container, config["account"].as_str().unwrap_or("default"));

    Arc::new(builder.build().expect("[store_factory] failed to build Azure store"))
}
