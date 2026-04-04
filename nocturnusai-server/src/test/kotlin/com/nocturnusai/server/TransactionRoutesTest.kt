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
import io.ktor.server.testing.*
import kotlin.test.*

/**
 * Integration tests for ACID transaction routes.
 *
 * Covers:
 *   POST /tx/begin              — begin a transaction, returns numeric ID
 *   POST /tx/commit/{id}        — commit, makes buffered operations visible
 *   POST /tx/rollback/{id}      — rollback, discards buffered operations
 *   POST /tell (X-Transaction-ID) — buffer a fact inside an open transaction
 */
class TransactionRoutesTest {

    // ─────────────────────────────────────────────────────────────────────────
    // POST /tx/begin
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `POST tx-begin - returns a numeric transaction ID`() = testApplication {
        application { module() }

        val response = client.post("/tx/begin") {
            header("X-Tenant-ID", "default")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText().trim()
        assertNotNull(body.toLongOrNull(), "Expected numeric transaction ID, got: $body")
    }

    @Test
    fun `POST tx-begin - missing tenant header returns 400`() = testApplication {
        application { module() }

        val response = client.post("/tx/begin")

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST tx-begin - each call returns a distinct ID`() = testApplication {
        application { module() }

        val id1 = client.post("/tx/begin") {
            header("X-Tenant-ID", "default")
        }.bodyAsText().trim().toLong()

        val id2 = client.post("/tx/begin") {
            header("X-Tenant-ID", "default")
        }.bodyAsText().trim().toLong()

        assertNotEquals(id1, id2, "Each transaction begin should return a unique ID")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /tx/begin + POST /tx/commit/{id}
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `POST tx commit - after begin returns 200 with Committed message`() = testApplication {
        application { module() }

        val txId = client.post("/tx/begin") {
            header("X-Tenant-ID", "default")
        }.bodyAsText().trim()

        val response = client.post("/tx/commit/$txId") {
            header("X-Tenant-ID", "default")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Committed"), "Expected 'Committed': $body")
    }

    @Test
    fun `POST tx commit - fact buffered in transaction is visible after commit`() = testApplication {
        application { module() }

        val txId = client.post("/tx/begin") {
            header("X-Tenant-ID", "default")
        }.bodyAsText().trim()

        // Buffer a fact inside the transaction
        client.post("/tell") {
            header("X-Tenant-ID", "default")
            header("X-Transaction-ID", txId)
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"txtest","args":["committed-value"]}""")
        }

        // Fact should NOT be visible yet (not committed)
        val beforeCommit = client.post("/ask") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"txtest","args":["committed-value"]}""")
        }
        assertEquals("[]", beforeCommit.bodyAsText().trim(),
            "Fact should not be visible before commit")

        // Commit
        client.post("/tx/commit/$txId") {
            header("X-Tenant-ID", "default")
        }

        // Fact SHOULD be visible now
        val afterCommit = client.post("/ask") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"txtest","args":["committed-value"]}""")
        }

        assertEquals(HttpStatusCode.OK, afterCommit.status)
        val body = afterCommit.bodyAsText()
        assertTrue(body.contains("txtest"), "Expected fact visible after commit: $body")
    }

    @Test
    fun `POST tx commit - invalid transaction ID returns 400`() = testApplication {
        application { module() }

        val response = client.post("/tx/commit/not-a-number") {
            header("X-Tenant-ID", "default")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("VALIDATION_ERROR"), "Expected VALIDATION_ERROR: $body")
    }

    @Test
    fun `POST tx commit - unknown transaction ID returns error`() = testApplication {
        application { module() }

        // 999999 is not a real transaction
        val response = client.post("/tx/commit/999999") {
            header("X-Tenant-ID", "default")
        }

        // Should be 409 Conflict or 500 depending on whether TransactionManager throws
        // IllegalArgumentException or a different exception
        assertTrue(
            response.status == HttpStatusCode.Conflict ||
                response.status == HttpStatusCode.InternalServerError ||
                response.status == HttpStatusCode.BadRequest,
            "Expected error status for unknown tx, got: ${response.status}"
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /tx/begin + POST /tx/rollback/{id}
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `POST tx rollback - after begin returns 200 with Rolled back message`() = testApplication {
        application { module() }

        val txId = client.post("/tx/begin") {
            header("X-Tenant-ID", "default")
        }.bodyAsText().trim()

        val response = client.post("/tx/rollback/$txId") {
            header("X-Tenant-ID", "default")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Rolled back") || body.contains("rolled back"),
            "Expected rollback confirmation: $body")
    }

    @Test
    fun `POST tx rollback - fact buffered in transaction is NOT visible after rollback`() =
        testApplication {
            application { module() }

            val txId = client.post("/tx/begin") {
                header("X-Tenant-ID", "default")
            }.bodyAsText().trim()

            // Buffer a fact inside the transaction
            client.post("/tell") {
                header("X-Tenant-ID", "default")
                header("X-Transaction-ID", txId)
                contentType(ContentType.Application.Json)
                setBody("""{"predicate":"txrollback","args":["should-not-appear"]}""")
            }

            // Rollback the transaction
            client.post("/tx/rollback/$txId") {
                header("X-Tenant-ID", "default")
            }

            // Fact should NOT be visible after rollback
            val response = client.post("/ask") {
                header("X-Tenant-ID", "default")
                contentType(ContentType.Application.Json)
                setBody("""{"predicate":"txrollback","args":["should-not-appear"]}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("[]", response.bodyAsText().trim(),
                "Rolled-back fact should not be visible")
        }

    @Test
    fun `POST tx rollback - invalid transaction ID returns 400`() = testApplication {
        application { module() }

        val response = client.post("/tx/rollback/not-a-number") {
            header("X-Tenant-ID", "default")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("VALIDATION_ERROR"), "Expected VALIDATION_ERROR: $body")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Transaction + rule buffering
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `POST teach with X-Transaction-ID - rule is buffered and committed`() = testApplication {
        application { module() }

        val txId = client.post("/tx/begin") {
            header("X-Tenant-ID", "default")
        }.bodyAsText().trim()

        // Buffer a rule in the transaction
        client.post("/teach") {
            header("X-Tenant-ID", "default")
            header("X-Transaction-ID", txId)
            contentType(ContentType.Application.Json)
            setBody("""
            {
              "head": {"predicate":"tx_derived","args":["?x"]},
              "body": [{"predicate":"tx_base","args":["?x"]}]
            }
            """.trimIndent())
        }

        // Also buffer a fact
        client.post("/tell") {
            header("X-Tenant-ID", "default")
            header("X-Transaction-ID", txId)
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"tx_base","args":["alpha"]}""")
        }

        // Commit both
        client.post("/tx/commit/$txId") {
            header("X-Tenant-ID", "default")
        }

        // Now inference over the rule should work
        val response = client.post("/ask") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"tx_derived","args":["alpha"]}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("tx_derived"), "Expected derived fact after tx commit: $body")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Transaction + /assert/fact  (canonical route with X-Transaction-ID)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `POST assert-fact with X-Transaction-ID - buffers fact with Buffered message`() =
        testApplication {
            application { module() }

            val txId = client.post("/tx/begin") {
                header("X-Tenant-ID", "default")
            }.bodyAsText().trim()

            val response = client.post("/assert/fact") {
                header("X-Tenant-ID", "default")
                header("X-Transaction-ID", txId)
                contentType(ContentType.Application.Json)
                setBody("""{"predicate":"buffered","args":["item1"]}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("Buffered") || body.contains("Tx"),
                "Expected buffering confirmation: $body")

            // Clean up
            client.post("/tx/rollback/$txId") {
                header("X-Tenant-ID", "default")
            }
        }
}
