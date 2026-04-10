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
 * Smoke tests for the context-management REST surface.
 *
 * These endpoints are the public "turns in, smaller context out" workflow used
 * by the docs and SDKs:
 *   POST /context
 *   POST /context/optimize
 *   POST /context/diff
 *   POST /context/summary
 *   POST /context/session/clear
 *   POST /context/ingest
 */
class ContextManagementRoutesTest {

    @Test
    fun `POST context - reduces predicate-like turns into facts`() = testApplication {
        application { module() }

        val response = client.post("/context") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "turns": ["human(socrates)", "likes(alice, bob)"],
                  "maxFacts": 10
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"facts\""), "Expected facts in response: $body")
        assertTrue(body.contains("human") || body.contains("likes"), "Expected extracted predicates in response: $body")
    }

    @Test
    fun `POST context-optimize - returns optimized window`() = testApplication {
        application { module() }

        client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"eligible_for_sla","args":["acme_corp"]}""")
        }

        val response = client.post("/context/optimize") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"sessionId":"ticket-42","maxFacts":10}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"windowId\""), "Expected windowId in response: $body")
        assertTrue(body.contains("\"entries\""), "Expected entries in response: $body")
    }

    @Test
    fun `POST context-diff - returns incremental changes for saved session`() = testApplication {
        application { module() }

        client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"eligible_for_sla","args":["acme_corp"]}""")
        }

        client.post("/context/optimize") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"sessionId":"ticket-42","maxFacts":10}""")
        }

        client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"renewal_due","args":["acme_corp","next_month"]}""")
        }

        val response = client.post("/context/diff") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"sessionId":"ticket-42","maxFacts":10}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"previousWindowId\""), "Expected previousWindowId in response: $body")
        assertTrue(body.contains("\"currentWindowId\""), "Expected currentWindowId in response: $body")
        assertTrue(body.contains("\"added\""), "Expected added list in response: $body")
    }

    @Test
    fun `POST context-summary - returns aggregate context metrics`() = testApplication {
        application { module() }

        client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"customer","args":["acme_corp"]}""")
        }

        val response = client.post("/context/summary") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"totalFacts\""), "Expected totalFacts in response: $body")
        assertTrue(body.contains("\"topPredicates\""), "Expected topPredicates in response: $body")
    }

    @Test
    fun `POST context-session-clear - clears stored snapshot`() = testApplication {
        application { module() }

        client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"customer","args":["acme_corp"]}""")
        }

        client.post("/context/optimize") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"sessionId":"ticket-42","maxFacts":10}""")
        }

        val response = client.post("/context/session/clear") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"sessionId":"ticket-42"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("ticket-42"))
    }

    @Test
    fun `POST context-ingest - parses predicate text and returns optimized context`() = testApplication {
        application { module() }

        val response = client.post("/context/ingest") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "text": "customer(acme_corp)\npriority(high)",
                  "maxFacts": 10,
                  "sessionId": "ingest-42"
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"extracted\""), "Expected extracted facts in response: $body")
        assertTrue(body.contains("\"context\""), "Expected optimized context in response: $body")
    }

    // ─────────────────────────────────────────────────────────────────────
    // Conversation tracking: scope partitioning + sessionId snapshotting
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `POST context - scope partitions facts under conversation key`() = withTestApp {
        // Conversation A — facts under scope "conv-a"
        client.post("/context") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "turns": ["customer(acme_corp)", "priority(high)"],
                  "scope": "conv-a",
                  "sessionId": "conv-a",
                  "maxFacts": 10
                }
                """.trimIndent()
            )
        }

        // Conversation B — different scope, different facts
        client.post("/context") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "turns": ["customer(globex)", "priority(low)"],
                  "scope": "conv-b",
                  "sessionId": "conv-b",
                  "maxFacts": 10
                }
                """.trimIndent()
            )
        }

        // Both scopes should be listed
        val scopes = client.get("/scopes") { header("X-Tenant-ID", "default") }
        assertEquals(HttpStatusCode.OK, scopes.status)
        val scopesBody = scopes.bodyAsText()
        assertTrue(scopesBody.contains("conv-a"), "Expected conv-a in scopes: $scopesBody")
        assertTrue(scopesBody.contains("conv-b"), "Expected conv-b in scopes: $scopesBody")
    }

    @Test
    fun `POST context - second turn with same sessionId echoes sessionId in response`() = withTestApp {
        // Turn 1
        val first = client.post("/context") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "turns": ["customer(acme_corp)"],
                  "scope": "support-thread-99",
                  "sessionId": "support-thread-99",
                  "maxFacts": 10
                }
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.OK, first.status)
        val firstBody = first.bodyAsText()
        assertTrue(firstBody.contains("\"sessionId\":\"support-thread-99\""),
            "Expected sessionId echo in first response: $firstBody")

        // Turn 2 — adds a new fact under same conversation
        val second = client.post("/context") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "turns": ["priority(critical)"],
                  "scope": "support-thread-99",
                  "sessionId": "support-thread-99",
                  "maxFacts": 10
                }
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.OK, second.status)
        val secondBody = second.bodyAsText()
        assertTrue(secondBody.contains("\"sessionId\":\"support-thread-99\""),
            "Expected sessionId echo in second response: $secondBody")
        // briefingDelta is null when no LLM provider is configured — but the field
        // must still be present (or absent — kotlinx-serialization may omit nulls).
        // The important assertion is that the call succeeded and the snapshot
        // machinery did not blow up on the second turn.
        assertTrue(secondBody.contains("\"facts\""))
    }

    @Test
    fun `DELETE scope - removes conversation facts`() = withTestApp {
        client.post("/context") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "turns": ["customer(acme_corp)"],
                  "scope": "to-be-deleted",
                  "sessionId": "to-be-deleted"
                }
                """.trimIndent()
            )
        }

        val deleted = client.delete("/scope/to-be-deleted") {
            header("X-Tenant-ID", "default")
        }
        assertEquals(HttpStatusCode.OK, deleted.status)
        val body = deleted.bodyAsText()
        assertTrue(body.contains("to-be-deleted"))
        // At least one atom should have been removed
        assertTrue(
            Regex("\"deleted\"\\s*:\\s*([1-9][0-9]*)").containsMatchIn(body),
            "Expected non-zero deleted count: $body"
        )
    }
}
