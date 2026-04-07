/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

//! Tiered storage crate — TieredObjectStore, FileRegistry, RemoteObjectStore,
//! store factory, and JNI bridge functions.

pub mod file_registry;
pub mod remote_object_store;
pub mod store_factory;
pub mod tiered_object_store;
pub mod jni_bridge;
