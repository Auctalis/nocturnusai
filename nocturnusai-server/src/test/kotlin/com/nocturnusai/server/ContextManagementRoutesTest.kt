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
}
