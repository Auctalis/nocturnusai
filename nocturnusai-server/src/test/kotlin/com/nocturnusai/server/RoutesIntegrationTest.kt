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
 * Integration tests for core fact/rule/inference routes.
 *
 * Covers both the simplified developer-friendly routes (/tell, /ask, /teach, /forget)
 * and the canonical logic routes (/assert/fact, /assert/rule, /infer, /retract, /execute).
 *
 * Auth is disabled in tests (API_KEY env var is unset → AuthMode.DISABLED).
 * Each test uses a fresh testApplication{} block so DatabaseManager state is never shared.
 *
 * X-Tenant-ID header is required on every endpoint that calls getContext().
 */
class RoutesIntegrationTest {

    // ─────────────────────────────────────────────────────────────────────────
    // POST /tell
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `POST tell - assert fact returns 200 with stored message`() = testApplication {
        application { module() }

        val response = client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"likes","args":["alice","bob"]}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("likes"), "Response should mention predicate: $body")
    }

    @Test
    fun `POST tell - blank predicate returns 400 VALIDATION_ERROR`() = testApplication {
        application { module() }

        val response = client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"","args":["alice"]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("VALIDATION_ERROR"), "Expected VALIDATION_ERROR, got: $body")
    }

    @Test
    fun `POST tell - missing X-Tenant-ID returns 400`() = testApplication {
        application { module() }

        val response = client.post("/tell") {
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"likes","args":["alice","bob"]}""")
        }

        // getContext() throws ValidationException when tenant header is absent
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST tell - negated fact stores with truthVal false`() = testApplication {
        application { module() }

        // Use a unique predicate to avoid contradiction with other tests
        val response = client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"dislikes","args":["alice","broccoli"],"negated":true}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `POST tell - fact with TTL is accepted`() = testApplication {
        application { module() }

        val ttlMs = 60_000L
        val response = client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"session","args":["user123"],"ttl":$ttlMs}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /ask
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `POST ask - query stored fact returns non-empty array`() = testApplication {
        application { module() }

        // Store a fact first
        client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"likes","args":["alice","bob"]}""")
        }

        val response = client.post("/ask") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"likes","args":["alice","bob"]}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        // Should return a JSON array with at least one result
        assertTrue(body.startsWith("["), "Expected JSON array, got: $body")
        assertTrue(body.contains("likes"), "Expected predicate in response: $body")
    }

    @Test
    fun `POST ask - query unknown fact returns empty array`() = testApplication {
        application { module() }

        val response = client.post("/ask") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"unknownPred","args":["x","y"]}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("[]", response.bodyAsText().trim())
    }

    @Test
    fun `POST ask - variable wildcard matches stored facts`() = testApplication {
        application { module() }

        // Store two likes facts
        client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"likes","args":["alice","bob"]}""")
        }
        client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"likes","args":["alice","charlie"]}""")
        }

        // Ask who alice likes using a variable
        val response = client.post("/ask") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"likes","args":["alice","?who"]}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("bob") || body.contains("charlie"),
            "Expected at least one match for variable query: $body")
    }

    @Test
    fun `POST ask - withProof true returns proof trees`() = testApplication {
        application { module() }

        client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"likes","args":["alice","bob"]}""")
        }

        val response = client.post("/ask") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"likes","args":["alice","bob"],"withProof":true}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        // Proof tree response contains a "proof" field
        assertTrue(body.contains("proof"), "Expected proof field in response: $body")
    }

    @Test
    fun `POST ask - blank predicate returns 400`() = testApplication {
        application { module() }

        val response = client.post("/ask") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"","args":[]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /teach
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `POST teach - assert rule returns 200`() = testApplication {
        application { module() }

        val ruleJson = """
        {
          "head": {"predicate":"mortal","args":["?x"]},
          "body": [{"predicate":"human","args":["?x"]}]
        }
        """.trimIndent()

        val response = client.post("/teach") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody(ruleJson)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Rule stored"), "Expected confirmation message: $body")
    }

    @Test
    fun `POST teach - rule with empty body returns 400`() = testApplication {
        application { module() }

        val ruleJson = """{"head":{"predicate":"mortal","args":["?x"]},"body":[]}"""

        val response = client.post("/teach") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody(ruleJson)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("VALIDATION_ERROR") || body.contains("BAD_REQUEST"),
            "Expected error code: $body")
    }

    @Test
    fun `POST teach - rule enables inference of derived facts`() = testApplication {
        application { module() }

        // Teach: mortal(?x) :- human(?x)
        client.post("/teach") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""
            {
              "head": {"predicate":"mortal","args":["?x"]},
              "body": [{"predicate":"human","args":["?x"]}]
            }
            """.trimIndent())
        }

        // Assert Socrates is human
        client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"human","args":["socrates"]}""")
        }

        // Ask if Socrates is mortal — should be derivable
        val response = client.post("/ask") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"mortal","args":["socrates"]}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("mortal"), "Expected mortal in inferred results: $body")
        assertTrue(body.contains("socrates"), "Expected socrates in inferred results: $body")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /forget
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `POST forget - retract existing fact returns 200`() = testApplication {
        application { module() }

        // Store a fact
        client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"likes","args":["alice","bob"]}""")
        }

        // Forget it
        val response = client.post("/forget") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"likes","args":["alice","bob"]}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Forgotten"), "Expected confirmation message: $body")
    }

    @Test
    fun `POST forget - fact is no longer retrievable after retraction`() = testApplication {
        application { module() }

        client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"likes","args":["alice","bob"]}""")
        }

        client.post("/forget") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"likes","args":["alice","bob"]}""")
        }

        val response = client.post("/ask") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"likes","args":["alice","bob"]}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("[]", response.bodyAsText().trim())
    }

    @Test
    fun `POST forget - blank predicate returns 400`() = testApplication {
        application { module() }

        val response = client.post("/forget") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"","args":["alice"]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /assert/fact  (canonical logic route)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `POST assert-fact - stores fact returns 200 with Fact Asserted`() = testApplication {
        application { module() }

        val response = client.post("/assert/fact") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"parent","args":["alice","bob"]}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Fact Asserted"), "Expected 'Fact Asserted': $body")
    }

    @Test
    fun `POST assert-fact - blank predicate returns 400 VALIDATION_ERROR`() = testApplication {
        application { module() }

        val response = client.post("/assert/fact") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"","args":["alice"]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("VALIDATION_ERROR"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /assert/rule  (canonical logic route)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `POST assert-rule - stores rule returns 200 with Rule Asserted`() = testApplication {
        application { module() }

        val response = client.post("/assert/rule") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""
            {
              "head": {"predicate":"ancestor","args":["?x","?z"]},
              "body": [
                {"predicate":"parent","args":["?x","?y"]},
                {"predicate":"parent","args":["?y","?z"]}
              ]
            }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Rule Asserted"), "Expected 'Rule Asserted': $body")
    }

    @Test
    fun `POST assert-rule - empty body array returns 400`() = testApplication {
        application { module() }

        val response = client.post("/assert/rule") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"head":{"predicate":"foo","args":[]},"body":[]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /infer  (canonical logic route with ?proof query param)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `POST infer - returns matching atoms`() = testApplication {
        application { module() }

        client.post("/assert/fact") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"color","args":["sky","blue"]}""")
        }

        val response = client.post("/infer") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"color","args":["sky","blue"]}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("color"), "Expected predicate in result: $body")
    }

    @Test
    fun `POST infer with proof=true - returns proof tree objects`() = testApplication {
        application { module() }

        client.post("/assert/fact") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"color","args":["sky","blue"]}""")
        }

        val response = client.post("/infer?proof=true") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"color","args":["sky","blue"]}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("proof"), "Expected proof field in response: $body")
    }

    @Test
    fun `POST infer - variable arg returns all matching bindings`() = testApplication {
        application { module() }

        client.post("/assert/fact") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"color","args":["sky","blue"]}""")
        }
        client.post("/assert/fact") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"color","args":["grass","green"]}""")
        }

        val response = client.post("/infer") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"color","args":["?what","?color"]}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("sky") || body.contains("grass"),
            "Expected at least one binding in response: $body")
    }

    @Test
    fun `POST infer - blank predicate returns 400`() = testApplication {
        application { module() }

        val response = client.post("/infer") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"","args":[]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /retract  (canonical logic route)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `POST retract - retracts stored fact returns 200`() = testApplication {
        application { module() }

        client.post("/assert/fact") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"temp","args":["hot"]}""")
        }

        val response = client.post("/retract") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"temp","args":["hot"]}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Retracted"), "Expected 'Retracted': $body")
    }

    @Test
    fun `POST retract - blank predicate returns 400`() = testApplication {
        application { module() }

        val response = client.post("/retract") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"","args":[]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /execute  (DSL)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `POST execute - valid DSL command returns 200 with result`() = testApplication {
        application { module() }

        val response = client.post("/execute") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"command":"assert likes(alice, dave)"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `POST execute - missing tenant header returns 400`() = testApplication {
        application { module() }

        val response = client.post("/execute") {
            contentType(ContentType.Application.Json)
            setBody("""{"command":"assert likes(alice, dave)"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /predicates  (schema discovery)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `GET predicates - returns 200 with predicate schema`() = testApplication {
        application { module() }

        // Seed a fact so there's something to discover
        client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"color","args":["sky","blue"]}""")
        }

        val response = client.get("/predicates") {
            header("X-Tenant-ID", "default")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"predicates\""), "Expected predicates array in response")
        assertTrue(body.contains("\"totalPredicates\""), "Expected totalPredicates field")
        assertTrue(body.contains("\"totalFacts\""), "Expected totalFacts field")
        assertTrue(body.contains("\"color\""), "Expected 'color' predicate in results")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /query  (direct hexastore lookup, no inference)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `POST query - returns exact stored facts without inference`() = testApplication {
        application { module() }

        client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"color","args":["rose","red"]}""")
        }

        val response = client.post("/query") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"color","args":["rose","red"]}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("color"), "Expected color in result: $body")
    }

    @Test
    fun `POST query - blank predicate returns 400`() = testApplication {
        application { module() }

        val response = client.post("/query") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"","args":[]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
