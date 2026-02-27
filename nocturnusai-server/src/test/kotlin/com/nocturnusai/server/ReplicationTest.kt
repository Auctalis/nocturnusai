// Copyright (c) 2026 Auctalis LLC. All rights reserved.
//
// Licensed under the Business Source License 1.1 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://github.com/auctalis/nocturnusai/blob/main/LICENSE

package com.nocturnusai.server

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import java.nio.file.Files
import kotlin.test.*

// ---------------------------------------------------------------------------
// Testing notes:
//
// ServerConfig reads environment variables at class-load time (object singleton),
// so we cannot toggle REPLICATION_MODE=FOLLOWER inside a unit test without
// forking a JVM.  Instead we test each concern in isolation:
//
//  1. WAL endpoint multi-database behaviour — via testApplication with the real
//     module() (always LEADER mode in tests because no REPLICATION_MODE env var
//     is set by the test runner).
//  2. Follower write-rejection logic — via a minimal Ktor app that installs the
//     interceptor logic directly, extracted into followerModule() below.
//  3. Health endpoint structure — verifying the replication block is present.
//  4. ReplicationClient coroutine scope — unit test, no network needed.
//
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// 1. WAL endpoint — multi-database behaviour (LEADER mode in tests)
// ---------------------------------------------------------------------------

class ReplicationWalEndpointTest {

    @Test
    fun `WAL endpoint returns 200 for default database`() = testApplication {
        application { module() }

        val resp = client.get("/replication/wal") {
            parameter("database", "default")
            parameter("since", "0")
        }
        assertEquals(HttpStatusCode.OK, resp.status,
            "Expected 200 for default database, got ${resp.status}")
    }

    @Test
    fun `WAL endpoint defaults to default database when no database param`() = testApplication {
        application { module() }

        val resp = client.get("/replication/wal") {
            parameter("since", "0")
        }
        assertEquals(HttpStatusCode.OK, resp.status,
            "Expected 200 when database param is absent (should default to 'default'), got ${resp.status}")
    }

    @Test
    fun `WAL endpoint returns 404 for missing database`() = testApplication {
        application { module() }

        val resp = client.get("/replication/wal") {
            parameter("database", "nonexistent-db-xyz")
            parameter("since", "0")
        }
        assertEquals(HttpStatusCode.NotFound, resp.status,
            "Expected 404 for unknown database, got ${resp.status}")
        val body = resp.bodyAsText()
        assertTrue(body.contains("NOT_FOUND"), "Expected NOT_FOUND error code in body: $body")
    }

    @Test
    fun `WAL endpoint returns plain text for default database`() = testApplication {
        application { module() }

        // Assert a fact first so there is something in the WAL
        client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"replication_test","args":["wal-check"]}""")
        }

        val resp = client.get("/replication/wal") {
            parameter("database", "default")
            parameter("since", "0")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        // The WAL may be cleared by snapshot; at minimum it must respond without error
        assertNotNull(resp.bodyAsText())
    }

    @Test
    fun `Database list endpoint returns all database names`() = testApplication {
        application { module() }

        val resp = client.get("/replication/wal/databases")
        assertEquals(HttpStatusCode.OK, resp.status,
            "Expected 200 for database list, got ${resp.status}")
        val body = resp.bodyAsText()
        // At minimum the 'default' database should be listed
        assertTrue(body.contains("default"),
            "Expected 'default' in database list: $body")
    }

    @Test
    fun `Snapshot endpoint returns 200 for default database`() = testApplication {
        application { module() }

        val resp = client.get("/replication/snapshot") {
            parameter("database", "default")
        }
        assertEquals(HttpStatusCode.OK, resp.status,
            "Expected 200 for snapshot of default database, got ${resp.status}")
        val body = resp.bodyAsText()
        assertTrue(body.contains("latestWalId"),
            "Expected 'latestWalId' in snapshot response: $body")
        assertTrue(body.contains("snapshot"),
            "Expected 'snapshot' in snapshot response: $body")
    }

    @Test
    fun `Snapshot endpoint returns 404 for missing database`() = testApplication {
        application { module() }

        val resp = client.get("/replication/snapshot") {
            parameter("database", "no-such-db")
        }
        assertEquals(HttpStatusCode.NotFound, resp.status,
            "Expected 404 for missing database snapshot, got ${resp.status}")
    }
}

// ---------------------------------------------------------------------------
// 2. Follower write-rejection
//
// Because ServerConfig.replicationMode is a JVM-level singleton we cannot
// toggle it per-test. We test the interceptor logic by constructing a minimal
// Ktor application that installs the exact same route-blocking code from
// Application.module(). This validates path-matching without requiring a
// FOLLOWER environment variable.
// ---------------------------------------------------------------------------

/**
 * Minimal Ktor app that mimics the follower write-rejection interceptor
 * from Application.module() plus a few stub routes so we can observe behaviour.
 */
private fun Application.followerModule() {
    install(ContentNegotiation) {
        json()
    }

    val writeMethodPrefixes = setOf(
        "/tell", "/teach", "/forget",
        "/assert/", "/retract", "/execute",
        "/tx/", "/memory/consolidate", "/memory/decay", "/memory/priority",
        "/memory/compress", "/memory/cleanup", "/memory/prioritize"
    )
    val writeAdminPaths = setOf("/admin/databases")

    intercept(ApplicationCallPipeline.Plugins) {
        val method = call.request.httpMethod
        val path = call.request.uri.substringBefore('?')

        val isWriteMethod = method == HttpMethod.Post || method == HttpMethod.Put ||
                method == HttpMethod.Delete || method == HttpMethod.Patch

        val isBlockedPath = isWriteMethod && (
            writeMethodPrefixes.any { prefix -> path.startsWith(prefix) } ||
            (writeAdminPaths.any { admin -> path.startsWith(admin) } &&
                (method == HttpMethod.Post || method == HttpMethod.Delete))
        )

        if (isBlockedPath) {
            call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse(
                    code = "FOLLOWER_READ_ONLY",
                    message = "This server is a read-only follower. Send writes to the leader.",
                    details = mapOf("leader" to "http://leader:9300")
                )
            )
            return@intercept finish()
        }
    }

    routing {
        // Stub write endpoints (should be blocked by the interceptor above)
        post("/tell") { call.respondText("should not reach") }
        post("/assert/fact") { call.respondText("should not reach") }
        post("/teach") { call.respondText("should not reach") }
        post("/forget") { call.respondText("should not reach") }
        post("/tx/begin") { call.respondText("should not reach") }
        post("/memory/consolidate") { call.respondText("should not reach") }
        post("/admin/databases") { call.respondText("should not reach") }
        delete("/admin/databases") { call.respondText("should not reach") }

        // Read endpoints — should pass through
        get("/health") { call.respondText("ok") }
        get("/admin/databases") { call.respondText("""["default"]""") }
        post("/ask") { call.respondText("[]") }
    }
}

class FollowerWriteRejectionTest {

    @Test
    fun `Follower rejects POST to tell with 409 FOLLOWER_READ_ONLY`() = testApplication {
        application { followerModule() }

        val resp = client.post("/tell") {
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"x","args":[]}""")
        }
        assertEquals(HttpStatusCode.Conflict, resp.status)
        val body = resp.bodyAsText()
        assertTrue(body.contains("FOLLOWER_READ_ONLY"), "Expected FOLLOWER_READ_ONLY in body: $body")
    }

    @Test
    fun `Follower rejects POST to assert-fact with 409`() = testApplication {
        application { followerModule() }

        val resp = client.post("/assert/fact") {
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"x","args":[]}""")
        }
        assertEquals(HttpStatusCode.Conflict, resp.status)
        val body = resp.bodyAsText()
        assertTrue(body.contains("FOLLOWER_READ_ONLY"), "Expected FOLLOWER_READ_ONLY in body: $body")
    }

    @Test
    fun `Follower rejects POST to teach with 409`() = testApplication {
        application { followerModule() }

        val resp = client.post("/teach") {
            contentType(ContentType.Application.Json)
            setBody("""{"head":{"predicate":"x","args":[]},"body":[]}""")
        }
        assertEquals(HttpStatusCode.Conflict, resp.status)
    }

    @Test
    fun `Follower rejects POST to forget with 409`() = testApplication {
        application { followerModule() }

        val resp = client.post("/forget") {
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"x","args":[]}""")
        }
        assertEquals(HttpStatusCode.Conflict, resp.status)
    }

    @Test
    fun `Follower rejects POST to tx-begin with 409`() = testApplication {
        application { followerModule() }

        val resp = client.post("/tx/begin")
        assertEquals(HttpStatusCode.Conflict, resp.status)
    }

    @Test
    fun `Follower rejects POST to memory-consolidate with 409`() = testApplication {
        application { followerModule() }

        val resp = client.post("/memory/consolidate") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Conflict, resp.status)
    }

    @Test
    fun `Follower rejects POST admin-databases with 409`() = testApplication {
        application { followerModule() }

        val resp = client.post("/admin/databases") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"newdb"}""")
        }
        assertEquals(HttpStatusCode.Conflict, resp.status)
    }

    @Test
    fun `Follower rejects DELETE admin-databases with 409`() = testApplication {
        application { followerModule() }

        val resp = client.delete("/admin/databases")
        assertEquals(HttpStatusCode.Conflict, resp.status)
    }

    @Test
    fun `Follower allows GET health`() = testApplication {
        application { followerModule() }

        val resp = client.get("/health")
        assertEquals(HttpStatusCode.OK, resp.status)
    }

    @Test
    fun `Follower allows GET admin-databases (read)`() = testApplication {
        application { followerModule() }

        val resp = client.get("/admin/databases")
        assertEquals(HttpStatusCode.OK, resp.status)
    }

    @Test
    fun `Follower allows POST ask (read operation)`() = testApplication {
        application { followerModule() }

        val resp = client.post("/ask") {
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"x","args":[]}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
    }

    @Test
    fun `Follower 409 response includes leader URL hint`() = testApplication {
        application { followerModule() }

        val resp = client.post("/tell") {
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"x","args":[]}""")
        }
        assertEquals(HttpStatusCode.Conflict, resp.status)
        val body = resp.bodyAsText()
        // The leader URL should appear in the details map
        assertTrue(body.contains("leader"), "Expected 'leader' key in response body: $body")
    }
}

// ---------------------------------------------------------------------------
// 3. Health endpoint — replication block is present (LEADER mode in tests)
// ---------------------------------------------------------------------------

class HealthReplicationInfoTest {

    @Test
    fun `Health endpoint includes replication block`() = testApplication {
        application { module() }

        val resp = client.get("/health")
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.bodyAsText()
        assertTrue(body.contains("replication"),
            "Health response should contain a 'replication' block: $body")
    }

    @Test
    fun `Health endpoint shows LEADER mode in replication block`() = testApplication {
        application { module() }

        val resp = client.get("/health")
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.bodyAsText()
        // In LEADER mode (default during tests — no REPLICATION_MODE env var set)
        assertTrue(body.contains("LEADER"),
            "Health replication block should show LEADER mode: $body")
    }
}

// ---------------------------------------------------------------------------
// 4. ReplicationClient — uses proper CoroutineScope, not GlobalScope
// ---------------------------------------------------------------------------

class ReplicationClientScopeTest {

    @Test
    fun `ReplicationClient stop() does not throw and completes cleanly`() {
        val tmpDir = Files.createTempDirectory("replication-scope-test-").toFile()
        try {
            val dbManager = DatabaseManager(tmpDir)

            // Point at a port that is guaranteed to refuse connections quickly.
            // We do NOT call start() here to avoid the background polling loop in tests;
            // we only verify that stop() itself is safe to call.
            val replicationClient = ReplicationClient(dbManager, "http://127.0.0.1:19999")

            // stop() must cancel the scope and close the HTTP client without throwing
            assertDoesNotFail { replicationClient.stop() }
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `ReplicationClient stop() is idempotent`() {
        val tmpDir = Files.createTempDirectory("replication-idempotent-test-").toFile()
        try {
            val dbManager = DatabaseManager(tmpDir)
            val replicationClient = ReplicationClient(dbManager, "http://127.0.0.1:19999")

            // Calling stop() multiple times must not throw
            assertDoesNotFail { replicationClient.stop() }
            assertDoesNotFail { replicationClient.stop() }
        } finally {
            tmpDir.deleteRecursively()
        }
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Asserts that the given block completes without throwing any exception. */
private fun assertDoesNotFail(block: () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        fail("Expected no exception but got: ${e::class.simpleName}: ${e.message}")
    }
}
