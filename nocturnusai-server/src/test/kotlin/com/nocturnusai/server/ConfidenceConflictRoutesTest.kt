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
 * HTTP and MCP integration tests for confidence scores and conflict resolution.
 *
 * Covers:
 *   POST /assert/fact with confidence and conflictStrategy fields
 *   POST /infer with minConfidence query parameter and body field
 *   MCP tool "tell" with confidence and conflictStrategy arguments
 *   MCP tool "ask" with minConfidence argument
 *   POST /admin/databases with defaultConflictStrategy
 *
 * Every test uses [withTestApp] to get a completely isolated in-memory and
 * on-disk knowledge base, preventing inter-test state leakage.
 */
class ConfidenceConflictRoutesTest {

    private val tenant = "test"

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Register the "test" tenant in the default database before use. */
    private suspend fun ApplicationTestBuilder.createTestTenant() {
        client.post("/admin/databases/default/tenants") {
            contentType(ContentType.Application.Json)
            setBody("""{"tenantId":"$tenant"}""")
        }
    }

    /** Register the "test" tenant in a named database. */
    private suspend fun ApplicationTestBuilder.createTestTenantInDb(dbName: String) {
        client.post("/admin/databases/$dbName/tenants") {
            contentType(ContentType.Application.Json)
            setBody("""{"tenantId":"$tenant"}""")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /assert/fact with confidence
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `POST assert-fact with confidence stores fact and confidence is returned by infer`() = withTestApp {
        createTestTenant()

        val assertResp = client.post("/assert/fact") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"likes","args":["alice","bob"],"confidence":0.85}""")
        }
        assertEquals(HttpStatusCode.OK, assertResp.status)
        assertTrue(assertResp.bodyAsText().contains("Fact Asserted"))

        val inferResp = client.post("/infer") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"likes","args":["alice","bob"]}""")
        }
        assertEquals(HttpStatusCode.OK, inferResp.status)
        val body = inferResp.bodyAsText()
        assertTrue(body.contains("\"confidence\""), "Response should include confidence field: $body")
        assertTrue(body.contains("0.85"), "Response should include confidence value 0.85: $body")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /assert/fact with conflictStrategy NEWEST_WINS
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `POST assert-fact with NEWEST_WINS replaces contradictory fact`() = withTestApp {
        createTestTenant()

        // Assert positive fact
        client.post("/assert/fact") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"alive","args":["alice","yes"]}""")
        }

        // Assert contradictory (negated) fact with NEWEST_WINS
        val resp = client.post("/assert/fact") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"alive","args":["alice","yes"],"negated":true,"conflictStrategy":"NEWEST_WINS"}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status, "NEWEST_WINS should accept contradictory fact")
        assertTrue(resp.bodyAsText().contains("Fact Asserted"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /assert/fact with conflictStrategy REJECT
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `POST assert-fact with REJECT returns 409 on contradictory fact`() = withTestApp {
        createTestTenant()

        // Assert positive fact
        client.post("/assert/fact") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"alive","args":["bob","yes"]}""")
        }

        // Assert contradictory (negated) fact with REJECT strategy
        val resp = client.post("/assert/fact") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"alive","args":["bob","yes"],"negated":true,"conflictStrategy":"REJECT"}""")
        }
        assertEquals(HttpStatusCode.Conflict, resp.status, "REJECT should return 409 Conflict")
        val body = resp.bodyAsText()
        assertTrue(body.contains("CONFLICT"), "Response should contain CONFLICT error code: $body")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /assert/fact with conflictStrategy CONFIDENCE
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `POST assert-fact with CONFIDENCE replaces lower confidence fact`() = withTestApp {
        createTestTenant()

        // Assert fact with low confidence
        client.post("/assert/fact") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"status","args":["server","healthy"],"confidence":0.3}""")
        }

        // Assert contradictory fact with higher confidence and CONFIDENCE strategy
        val resp = client.post("/assert/fact") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"status","args":["server","healthy"],"negated":true,"confidence":0.9,"conflictStrategy":"CONFIDENCE"}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status, "CONFIDENCE strategy should accept higher-confidence fact")

        // Verify the higher-confidence (negated) version is what we get back
        val inferResp = client.post("/infer") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"status","args":["server","healthy"]}""")
        }
        val body = inferResp.bodyAsText()
        // The negated fact replaced the original, so querying for truthVal=true should return empty
        // or the result should reflect the negated version with 0.9 confidence
        assertTrue(body.contains("0.9") || body == "[]", "Higher confidence fact should win: $body")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /infer with minConfidence query parameter
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `POST infer with minConfidence query parameter filters low confidence facts`() = withTestApp {
        createTestTenant()

        // Assert facts with different confidences
        client.post("/assert/fact") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"color","args":["sky","blue"],"confidence":0.9}""")
        }
        client.post("/assert/fact") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"color","args":["grass","green"],"confidence":0.3}""")
        }

        // Query with minConfidence=0.5 via query parameter
        val resp = client.post("/infer") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            url { parameters.append("minConfidence", "0.5") }
            setBody("""{"predicate":"color","args":["?x","?y"]}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.bodyAsText()
        assertTrue(body.contains("sky"), "High confidence fact should be included: $body")
        assertFalse(body.contains("grass"), "Low confidence fact should be filtered out: $body")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /infer with minConfidence in body (via confidence field on FactRequest)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `POST infer with minConfidence in body filters low confidence facts`() = withTestApp {
        createTestTenant()

        // Assert facts with different confidences
        client.post("/assert/fact") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"taste","args":["lemon","sour"],"confidence":0.95}""")
        }
        client.post("/assert/fact") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"taste","args":["sugar","sweet"],"confidence":0.2}""")
        }

        // Query with confidence in body (used as minConfidence)
        val resp = client.post("/infer") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"taste","args":["?x","?y"],"confidence":0.5}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.bodyAsText()
        assertTrue(body.contains("lemon"), "High confidence fact should be included: $body")
        assertFalse(body.contains("sugar"), "Low confidence fact should be filtered out: $body")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MCP tool "tell" with confidence argument
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `MCP tell tool with confidence stores fact with confidence`() = withTestApp {
        createTestTenant()

        val jsonRpc = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"tell","arguments":{"predicate":"knows","args":["alice","bob"],"confidence":0.9}}}"""

        val resp = client.post("/mcp") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody(jsonRpc)
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.bodyAsText()
        assertFalse(body.contains("\"isError\":true"), "Expected success, got: $body")
        assertTrue(body.contains("Stored"), "Response should confirm storage: $body")

        // Verify via /infer that the confidence is stored
        val inferResp = client.post("/infer") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"knows","args":["alice","bob"]}""")
        }
        val inferBody = inferResp.bodyAsText()
        assertTrue(inferBody.contains("0.9"), "Confidence should be stored and returned: $inferBody")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MCP tool "tell" with conflictStrategy argument
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `MCP tell tool with NEWEST_WINS conflictStrategy replaces contradictory fact`() = withTestApp {
        createTestTenant()

        // First, assert a positive fact via MCP
        val tell1 = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"tell","arguments":{"predicate":"open","args":["door","front"]}}}"""
        client.post("/mcp") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody(tell1)
        }

        // Now assert contradictory fact with NEWEST_WINS
        val tell2 = """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"tell","arguments":{"predicate":"open","args":["door","front"],"negated":true,"conflictStrategy":"NEWEST_WINS"}}}"""
        val resp = client.post("/mcp") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody(tell2)
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.bodyAsText()
        assertFalse(body.contains("\"isError\":true"), "NEWEST_WINS should succeed, got: $body")
        assertTrue(body.contains("Stored"), "Response should confirm storage: $body")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MCP tool "tell" with invalid conflictStrategy
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `MCP tell tool with invalid conflictStrategy returns error`() = withTestApp {
        createTestTenant()

        val jsonRpc = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"tell","arguments":{"predicate":"test","args":["a","b"],"conflictStrategy":"INVALID_STRATEGY"}}}"""

        val resp = client.post("/mcp") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody(jsonRpc)
        }
        assertEquals(HttpStatusCode.OK, resp.status) // MCP always returns 200 with error in body
        val body = resp.bodyAsText()
        assertTrue(body.contains("\"isError\":true"), "Should be an error response: $body")
        assertTrue(
            body.contains("Invalid conflictStrategy") || body.contains("VALIDATION_ERROR"),
            "Should mention invalid strategy: $body"
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MCP tool "ask" with minConfidence argument
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `MCP ask tool with minConfidence filters low confidence facts`() = withTestApp {
        createTestTenant()

        // Store facts with different confidences
        client.post("/tell") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"temp","args":["sensor1","hot"],"confidence":0.95}""")
        }
        client.post("/tell") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"temp","args":["sensor2","cold"],"confidence":0.1}""")
        }

        // Ask via MCP with minConfidence
        val jsonRpc = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"ask","arguments":{"predicate":"temp","args":["?s","?v"],"minConfidence":0.5}}}"""
        val resp = client.post("/mcp") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody(jsonRpc)
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.bodyAsText()
        assertFalse(body.contains("\"isError\":true"), "Expected success, got: $body")
        assertTrue(body.contains("sensor1"), "High confidence fact should be included: $body")
        assertFalse(body.contains("sensor2"), "Low confidence fact should be filtered: $body")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /admin/databases with defaultConflictStrategy
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `POST admin-databases with NEWEST_WINS default allows contradictory facts`() = withTestApp {
        // Create a new database with NEWEST_WINS as default
        val createResp = client.post("/admin/databases") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"confdb","defaultConflictStrategy":"NEWEST_WINS"}""")
        }
        assertEquals(HttpStatusCode.OK, createResp.status)
        val createBody = createResp.bodyAsText()
        assertTrue(createBody.contains("NEWEST_WINS"), "Should confirm NEWEST_WINS strategy: $createBody")

        // Register the test tenant in the new database
        createTestTenantInDb("confdb")

        // Assert a fact into the new database
        client.post("/assert/fact") {
            header("X-Database", "confdb")
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"state","args":["light","on"]}""")
        }

        // Assert contradictory fact WITHOUT specifying conflictStrategy
        // (should use database default NEWEST_WINS)
        val resp = client.post("/assert/fact") {
            header("X-Database", "confdb")
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"state","args":["light","on"],"negated":true}""")
        }
        assertEquals(
            HttpStatusCode.OK, resp.status,
            "Database default NEWEST_WINS should allow contradictory fact without explicit strategy"
        )
    }
}
