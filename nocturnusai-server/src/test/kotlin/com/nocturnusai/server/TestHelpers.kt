// Copyright (c) 2026 Auctalis LLC. All rights reserved.
//
// Licensed under the Business Source License 1.1 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://github.com/auctalis/nocturnusai/blob/main/LICENSE

package com.nocturnusai.server

import io.ktor.server.testing.*
import java.io.File
import java.nio.file.Files

/**
 * Creates a fresh temporary storage directory, runs the testApplication block
 * with a DatabaseManager backed by that directory, then deletes it on exit.
 *
 * This guarantees each test gets an empty, isolated knowledge base — even when
 * multiple tests run in the same Gradle test task invocation and share the
 * build/test-data directory set by [ServerConfig.storageDir].
 */
fun withTestApp(block: suspend ApplicationTestBuilder.() -> Unit) {
    val tmpDir = Files.createTempDirectory("nocturnusai-test-").toFile()
    try {
        testApplication {
            application {
                moduleWithStorageDir(tmpDir)
            }
            block()
        }
    } finally {
        tmpDir.deleteRecursively()
    }
}
