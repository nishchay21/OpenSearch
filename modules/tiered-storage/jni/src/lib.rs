/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

//! Tiered storage JNI crate — DEPRECATED.
//!
//! TieredObjectStore, FileRegistry, RemoteObjectStore, and all JNI functions
//! have been moved into DataFusion's .so (plugins/engine-datafusion/jni/src/tiered/)
//! to avoid cross-.so allocator mismatch (SIGSEGV in mi_free_generic_mt).
//!
//! This crate is kept as a placeholder. It can be removed once the build
//! system no longer references it.
