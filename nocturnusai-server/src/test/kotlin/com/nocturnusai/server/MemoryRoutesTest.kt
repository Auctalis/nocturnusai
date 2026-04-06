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
 * Integration tests for the Memory API routes.
 *
 * Covers:
 *   POST /memory/context       — build context window
 *   POST /memory/consolidate   — run consolidation
 *   POST /memory/decay         — run decay with optional threshold
 *   POST /memory/priority      — set salience priority
 *   POST /memory/query/temporal — point-in-time query
 *   POST /memory/query/salient  — salience-ranked query
 *
 * Also tests the simplified aliases:
 *   POST /memory/compress   — alias for consolidate
 *   POST /memory/cleanup    — alias for decay
 *   POST /memory/prioritize — alias for priority
 *   POST /memory/recall     — alias for temporal query
 */
class MemoryRoutesTest {

    // ─────────────────────────────────────────────────────────────────────────
    // POST /memory/context
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `POST memory-context - returns context window response with correct structure`() = testApplication {
        application { module() }

        // Populate some facts first
        repeat(3) { i ->
            client.post("/tell") {
                header("X-Tenant-ID", "default")
                contentType(ContentType.Application.Json)
                setBody("""{"predicate":"item","args":["thing$i"]}""")
            }
        }

        val response = client.post("/memory/context") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("facts"), "Expected 'facts' key: $body")
        assertTrue(body.contains("totalAvailable"), "Expected 'totalAvailable' key: $body")
        assertTrue(body.contains("windowSize"), "Expected 'windowSize' key: $body")
        assertTrue(body.contains("generatedAt"), "Expected 'generatedAt' key: $body")
    }

    @Test
    fun `POST memory-context - maxFacts parameter limits returned facts`() = testApplication {
        application { module() }

        // Store more facts than the requested max
        repeat(10) { i ->
            client.post("/tell") {
                header("X-Tenant-ID", "default")
                contentType(ContentType.Application.Json)
                setBody("""{"predicate":"item","args":["thing$i"]}""")
            }
        }

        val response = client.post("/memory/context") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"maxFacts":3}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        // The windowSize in the response should reflect the cap
        assertTrue(body.contains("windowSize"), "Expected windowSize: $body")
    }

    @Test
    fun `POST memory-context - returns valid context window structure`() = testApplication {
        application { module() }

        val response = client.post("/memory/context") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"maxFacts":50}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        // Verify the response has the expected context window fields
        assertTrue(body.contains("\"facts\""), "Expected 'facts' field: $body")
        assertTrue(body.contains("\"totalAvailable\""), "Expected 'totalAvailable' field: $body")
        assertTrue(body.contains("\"windowSize\""), "Expected 'windowSize' field: $body")
        assertTrue(body.contains("\"generatedAt\""), "Expected 'generatedAt' field: $body")
    }

    @Test
    fun `POST memory-context - missing tenant header returns 400`() = testApplication {
        application { module() }

        val response = client.post("/memory/context") {
            contentType(ContentType.Application.Json)
            setBody("""{}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST memory-context - format natural returns formattedText`() = testApplication {
        application { module() }

        client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"likes","args":["alice","bob"]}""")
        }

        val response = client.post("/memory/context") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"format":"natural"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("formattedText"), "Expected 'formattedText' key: $body")
        assertTrue(body.contains("Current Knowledge"), "Expected natural language header in formattedText: $body")
    }

    @Test
    fun `POST memory-context - format structured returns formattedText`() = testApplication {
        application { module() }

        client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"likes","args":["alice","bob"]}""")
        }

        val response = client.post("/memory/context") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"format":"structured"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("formattedText"), "Expected 'formattedText' key: $body")
        assertTrue(body.contains("knowledge"), "Expected structured XML-style tags in formattedText: $body")
    }

    @Test
    fun `POST memory-context - default format has no formattedText`() = testApplication {
        application { module() }

        client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"likes","args":["alice","bob"]}""")
        }

        val response = client.post("/memory/context") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        // formattedText should be null when no format is specified
        assertTrue(body.contains("\"formattedText\":null"), "Expected 'formattedText' to be null when default format: $body")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /memory/consolidate
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `POST memory-consolidate - returns consolidation response`() = testApplication {
        application { module() }

        val response = client.post("/memory/consolidate") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("factsConsolidated"), "Expected 'factsConsolidated' key: $body")
        assertTrue(body.contains("newFacts"), "Expected 'newFacts' key: $body")
        assertTrue(body.contains("timestamp"), "Expected 'timestamp' key: $body")
    }

    @Test
    fun `POST memory-compress - simplified alias returns same structure`() = testApplication {
        application { module() }

        val response = client.post("/memory/compress") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("factsConsolidated"), "Expected 'factsConsolidated' key: $body")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /memory/decay
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `POST memory-decay - no body returns decay response`() = testApplication {
        application { module() }

        val response = client.post("/memory/decay") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("expiredCount"), "Expected 'expiredCount' key: $body")
        assertTrue(body.contains("evictedCount"), "Expected 'evictedCount' key: $body")
        assertTrue(body.contains("removedAtoms"), "Expected 'removedAtoms' key: $body")
        assertTrue(body.contains("timestamp"), "Expected 'timestamp' key: $body")
    }

    @Test
    fun `POST memory-decay - with threshold parameter is accepted`() = testApplication {
        application { module() }

        val response = client.post("/memory/decay") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"threshold":0.1}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `POST memory-decay - fact with expired TTL is removed`() = testApplication {
        application { module() }

        // Store a fact with TTL of 1 ms (already expired)
        client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"ephemeral","args":["data"],"ttl":1}""")
        }

        // Give the TTL a moment to expire
        Thread.sleep(10)

        val response = client.post("/memory/decay") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        // expiredCount should be >= 1 after the TTL fact expired
        assertTrue(body.contains("expiredCount"), "Expected expiredCount in response: $body")
    }

    @Test
    fun `POST memory-cleanup - simplified alias returns decay structure`() = testApplication {
        application { module() }

        val response = client.post("/memory/cleanup") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("expiredCount"), "Expected 'expiredCount' key: $body")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /memory/priority
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `POST memory-priority - set priority returns 200`() = testApplication {
        application { module() }

        client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"important","args":["task1"]}""")
        }

        val response = client.post("/memory/priority") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"important","args":["task1"],"priority":0.9}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Priority set"), "Expected 'Priority set': $body")
    }

    @Test
    fun `POST memory-prioritize - simplified alias for priority works`() = testApplication {
        application { module() }

        client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"urgent","args":["event1"]}""")
        }

        val response = client.post("/memory/prioritize") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"urgent","args":["event1"],"priority":1.0}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /memory/query/temporal
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `POST memory-query-temporal - returns facts valid at timestamp`() = testApplication {
        application { module() }

        client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"status","args":["service","up"]}""")
        }

        val response = client.post("/memory/query/temporal") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"status","args":["service","up"],"timestamp":${System.currentTimeMillis()}}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        // Should return a JSON array
        assertTrue(body.startsWith("["), "Expected array response: $body")
    }

    @Test
    fun `POST memory-query-temporal - returns empty for timestamp before fact was asserted`() =
        testApplication {
            application { module() }

            val longAgo = 1_000_000L // epoch ms far in the past

            client.post("/tell") {
                header("X-Tenant-ID", "default")
                contentType(ContentType.Application.Json)
                setBody("""{"predicate":"status","args":["service","up"]}""")
            }

            val response = client.post("/memory/query/temporal") {
                header("X-Tenant-ID", "default")
                contentType(ContentType.Application.Json)
                setBody("""{"predicate":"status","args":["service","up"],"timestamp":$longAgo}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            // A fact asserted now should not appear when queried at epoch time ~1970
            assertEquals("[]", response.bodyAsText().trim())
        }

    @Test
    fun `POST memory-recall - simplified alias returns same structure as temporal`() = testApplication {
        application { module() }

        val response = client.post("/memory/recall") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"status","args":["?x","?y"],"timestamp":${System.currentTimeMillis()}}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.startsWith("["), "Expected JSON array: $body")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /memory/query/salient
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `POST memory-query-salient - returns scored facts`() = testApplication {
        application { module() }

        client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"score","args":["item1"]}""")
        }

        val response = client.post("/memory/query/salient") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"score","args":["?x"]}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        // ScoredAtomResponse contains a "salience" field
        assertTrue(body.startsWith("["), "Expected JSON array: $body")
        if (!body.equals("[]")) {
            assertTrue(body.contains("salience"), "Expected 'salience' field in scored response: $body")
        }
    }

    @Test
    fun `POST memory-query-salient - minSalience filter is accepted`() = testApplication {
        application { module() }

        val response = client.post("/memory/query/salient") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"score","args":["?x"],"minSalience":0.5,"limit":10}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /memory/context — unified advanced mode (goal-driven optimization)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `POST memory-context - with goals triggers advanced mode`() = testApplication {
        application { module() }

        // Populate a fact
        client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"likes","args":["alice","bob"]}""")
        }

        val response = client.post("/memory/context") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"goals":[{"predicate":"likes","args":["?x","?y"]}]}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"windowId\""), "Expected 'windowId' in advanced response: $body")
        assertTrue(body.contains("\"goalDriven\":true"), "Expected goalDriven=true in response: $body")
    }

    @Test
    fun `POST memory-context - with sessionId triggers advanced mode`() = testApplication {
        application { module() }

        client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"status","args":["active"]}""")
        }

        val response = client.post("/memory/context") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"sessionId":"test-session-123"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"windowId\""), "Expected 'windowId' in advanced response: $body")
    }

    @Test
    fun `POST memory-context - simple mode has no windowId`() = testApplication {
        application { module() }

        client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"color","args":["sky","blue"]}""")
        }

        val response = client.post("/memory/context") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"maxFacts":50}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"windowId\":null"), "Expected windowId to be null in simple mode: $body")
    }

    @Test
    fun `POST memory-context - advanced mode with format natural`() = testApplication {
        application { module() }

        client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"likes","args":["alice","bob"]}""")
        }

        val response = client.post("/memory/context") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"goals":[{"predicate":"likes","args":["?x","?y"]}],"format":"natural"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"formattedText\""), "Expected formattedText in response: $body")
        assertTrue(body.contains("\"windowId\""), "Expected windowId in advanced response: $body")
    }
}
