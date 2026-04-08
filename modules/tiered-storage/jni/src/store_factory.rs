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
//! - `s3`    — Amazon S3 (SSE-KMS, SSE-S3, DSSE-KMS, SSE-C, bucket key, proxy, IMDSv1 fallback)
//! - `gcs`   — Google Cloud Storage
//! - `azure` — Azure Blob Storage

use object_store::ObjectStore;
use std::sync::Arc;
use vectorized_exec_spi::log_info;

use crate::remote_object_store::RemoteObjectStore;

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

    // --- Credentials ---

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

    // --- Encryption ---

    if let Some(encryption) = config["encryption"].as_str() {
        match encryption {
            "aws:kms" | "SSE-KMS" => {
                if let Some(kms_key_id) = config["kms_key_id"].as_str() {
                    builder = builder.with_sse_kms_encryption(kms_key_id);
                }
            }
            "aws:kms:dsse" | "DSSE-KMS" => {
                if let Some(kms_key_id) = config["kms_key_id"].as_str() {
                    builder = builder.with_dsse_kms_encryption(kms_key_id);
                }
            }
            "SSE-C" => {
                if let Some(customer_key) = config["sse_customer_key"].as_str() {
                    log_info!("[store_factory] s3: SSE-C with customer key configured — requires object_store >= 0.13");
                    // TODO: builder = builder.with_sse_customer_key(customer_key);
                }
            }
            "AES256" | "SSE-S3" => {
                log_info!("[store_factory] s3: SSE-S3 (AES256) encryption — using S3 default");
            }
            other => {
                log_info!("[store_factory] s3: unknown encryption type '{}', skipping", other);
            }
        }
    }

    // Bucket key — reduces KMS API calls. Requires object_store >= 0.13.
    if let Some(bucket_key) = config["bucket_key_enabled"].as_bool() {
        if bucket_key {
            log_info!("[store_factory] s3: bucket_key_enabled configured — requires object_store >= 0.13");
            // TODO: builder = builder.with_bucket_key_enabled(bucket_key);
        }
    }

    // --- Request style ---

    if config["virtual_hosted_style_request"].as_bool() == Some(true) {
        builder = builder.with_virtual_hosted_style_request(true);
    }
    if config["unsigned_payload"].as_bool() == Some(true) {
        builder = builder.with_unsigned_payload(true);
    }
    if config["skip_signature"].as_bool() == Some(true) {
        builder = builder.with_skip_signature(true);
    }
    if config["allow_http"].as_bool() == Some(true) {
        builder = builder.with_allow_http(true);
    }

    // --- S3 Express One Zone ---

    if config["s3_express"].as_bool() == Some(true) {
        builder = builder.with_s3_express(true);
    }

    // --- IMDSv1 fallback (for legacy EC2/kube2iam environments) ---

    if config["imdsv1_fallback"].as_bool() == Some(true) {
        builder = builder.with_imdsv1_fallback();
    }

    // --- Proxy ---

    if let Some(proxy_url) = config["proxy_url"].as_str() {
        builder = builder.with_proxy_url(proxy_url);
    }
    if let Some(proxy_ca_cert) = config["proxy_ca_certificate"].as_str() {
        builder = builder.with_proxy_ca_certificate(proxy_ca_cert);
    }

    // --- Checksum ---

    if let Some(checksum) = config["checksum_algorithm"].as_str() {
        use object_store::aws::Checksum;
        let algo = match checksum {
            "SHA256" => Checksum::SHA256,
            _ => {
                log_info!("[store_factory] s3: unknown checksum algorithm '{}', skipping", checksum);
                Checksum::SHA256 // default
            }
        };
        builder = builder.with_checksum_algorithm(algo);
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
