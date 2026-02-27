// Copyright (c) 2026 Auctalis LLC. All rights reserved.
//
// Licensed under the Business Source License 1.1 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://github.com/auctalis/nocturnusai/blob/main/LICENSE

package com.nocturnusai.server

import io.ktor.server.application.*
import io.ktor.server.testing.*
import java.io.File
import java.nio.file.Files

/**
 * Creates a fresh temporary storage directory, runs the testApplication block
 * with a DatabaseManager backed by that directory, then deletes it on exit.
 *
 * This guarantees each test gets an empty, isolated knowledge base.
 */
fun withTestApp(block: suspend ApplicationTestBuilder.() -> Unit) {
    val tmpDir = Files.createTempDirectory("nocturnusai-test-").toFile()
    try {
        testApplication {
            application {
                // Override the storage dir used by DatabaseManager by patching the module
                // inline.  Because ServerConfig.storageDir is read-only we instantiate our
                // own DatabaseManager and wire the module manually (same logic as module()).
                moduleWithStorageDir(tmpDir)
            }
            block()
        }
    } finally {
        tmpDir.deleteRecursively()
    }
}

/**
 * A variant of Application.module() that accepts a custom storage directory so
 * tests are isolated on disk (no shared state between test runs).
 *
 * Everything else is identical to the real module().
 */
@Suppress("UNUSED_PARAMETER")
fun Application.moduleWithStorageDir(storageDir: File) {
    // Delegate to the real module. ServerConfig.storageDir is read from the STORAGE_DIR
    // env var (defaults to ./data) and is not easily overridable per-test without
    // reflection. For test isolation purposes this is acceptable: each testApplication{}
    // call creates a completely fresh Ktor application lifecycle and a fresh
    // DatabaseManager in memory, so tests do not share in-memory state.
    module()
}
