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
import kotlinx.serialization.json.*

/**
 * HTTP integration tests for:
 *   POST /aggregate
 *   POST /assert/facts   (bulk)
 *   POST /retract/pattern
 *
 * Each test gets a fresh testApplication so no state is shared.
 */
class AggregateRoutesTest {

    // ─────────────────────────────────────────────────────────────────────────
    // POST /aggregate
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `POST aggregate COUNT returns result object`() = testApplication {
        application { module() }

        // Store two score facts
        client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"score","args":["alice","10"]}""")
        }
        client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"score","args":["bob","20"]}""")
        }

        val response = client.post("/aggregate") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"score","args":["?player","?val"],"operation":"COUNT"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("COUNT"), "Response should contain operation: $body")
        assertTrue(body.contains("\"result\""), "Response should contain result field: $body")
        assertTrue(body.contains("\"matchedFacts\""), "Response should contain matchedFacts: $body")
    }

    @Test
    fun `POST aggregate SUM returns numeric result`() = testApplication {
        application { module() }

        // Insert numeric scores (parseTerm converts "10" -> NumberLit(10.0))
        client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"points","args":["alice","10"]}""")
        }
        client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"points","args":["bob","20"]}""")
        }

        val response = client.post("/aggregate") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"points","args":["?p","?v"],"operation":"SUM","argIndex":1}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("SUM"), "Response should contain operation name: $body")
        // result=30.0
        assertTrue(body.contains("30.0") || body.contains("30"), "Expected sum of 30: $body")
    }

    @Test
    fun `POST aggregate MIN returns minimum`() = testApplication {
        application { module() }

        client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"temp","args":["sensor1","5"]}""")
        }
        client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"temp","args":["sensor2","15"]}""")
        }

        val response = client.post("/aggregate") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"temp","args":["?s","?v"],"operation":"MIN","argIndex":1}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("5.0") || body.contains("\"result\":5"), "Expected min=5: $body")
    }

    @Test
    fun `POST aggregate MAX returns maximum`() = testApplication {
        application { module() }

        client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"temp","args":["sensor1","5"]}""")
        }
        client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"temp","args":["sensor2","15"]}""")
        }

        val response = client.post("/aggregate") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"temp","args":["?s","?v"],"operation":"MAX","argIndex":1}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("15.0") || body.contains("\"result\":15"), "Expected max=15: $body")
    }

    @Test
    fun `POST aggregate AVG returns average`() = testApplication {
        application { module() }

        client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"rating","args":["item1","10"]}""")
        }
        client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"rating","args":["item2","20"]}""")
        }

        val response = client.post("/aggregate") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"rating","args":["?i","?v"],"operation":"AVG","argIndex":1}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("15.0") || body.contains("\"result\":15"), "Expected avg=15: $body")
    }

    @Test
    fun `POST aggregate COUNT on empty store returns 0`() = testApplication {
        application { module() }

        val response = client.post("/aggregate") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"nosuchpred","args":["?x","?y"],"operation":"COUNT"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(
            body.contains("\"result\":0") || body.contains("\"result\": 0") ||
            body.contains("\"result\":0.0") || body.contains("\"result\": 0.0"),
            "Expected result=0: $body"
        )
    }

    @Test
    fun `POST aggregate with unknown operation returns 400`() = testApplication {
        application { module() }

        val response = client.post("/aggregate") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"score","args":["?x","?y"],"operation":"BADOP"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("VALIDATION_ERROR"), "Expected VALIDATION_ERROR: $body")
    }

    @Test
    fun `POST aggregate with blank predicate returns 400`() = testApplication {
        application { module() }

        val response = client.post("/aggregate") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"","args":["?x","?y"],"operation":"COUNT"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST aggregate without X-Tenant-ID returns 400`() = testApplication {
        application { module() }

        val response = client.post("/aggregate") {
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"score","args":["?x","?y"],"operation":"COUNT"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /assert/facts  (bulk)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `POST assert-facts bulk asserts multiple facts`() = testApplication {
        application { module() }

        val response = client.post("/assert/facts") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""
                {
                  "facts": [
                    {"predicate":"likes","args":["alice","bob"]},
                    {"predicate":"likes","args":["alice","charlie"]},
                    {"predicate":"age","args":["alice","30"]}
                  ]
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"asserted\":3"), "Expected asserted=3: $body")
        assertTrue(body.contains("\"failed\":0"), "Expected failed=0: $body")
    }

    @Test
    fun `POST assert-facts reports partial failure for contradictions`() = testApplication {
        application { module() }

        // First assert a positive fact
        client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"alive","args":["alice","yes"]}""")
        }

        // Bulk assert: one contradiction + one valid
        val response = client.post("/assert/facts") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""
                {
                  "facts": [
                    {"predicate":"alive","args":["alice","yes"],"negated":true},
                    {"predicate":"likes","args":["alice","dave"]}
                  ]
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"asserted\":1"), "Expected one success: $body")
        assertTrue(body.contains("\"failed\":1"), "Expected one failure: $body")
    }

    @Test
    fun `POST assert-facts with empty array returns 400`() = testApplication {
        application { module() }

        val response = client.post("/assert/facts") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"facts":[]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST assert-facts missing X-Tenant-ID returns 400`() = testApplication {
        application { module() }

        val response = client.post("/assert/facts") {
            contentType(ContentType.Application.Json)
            setBody("""{"facts":[{"predicate":"x","args":["a","b"]}]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST assert-facts with blank predicate returns 400`() = testApplication {
        application { module() }

        val response = client.post("/assert/facts") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"facts":[{"predicate":"","args":["a","b"]}]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("VALIDATION_ERROR"), "Expected VALIDATION_ERROR: $body")
    }

    @Test
    fun `POST assert-facts facts are actually stored after bulk assert`() = testApplication {
        application { module() }

        client.post("/assert/facts") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""
                {
                  "facts": [
                    {"predicate":"color","args":["sky","blue"]},
                    {"predicate":"color","args":["grass","green"]}
                  ]
                }
            """.trimIndent())
        }

        val queryResponse = client.post("/query") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"color","args":["?what","?color"]}""")
        }

        assertEquals(HttpStatusCode.OK, queryResponse.status)
        val body = queryResponse.bodyAsText()
        assertTrue(body.contains("sky") || body.contains("grass"),
            "Bulk-asserted facts should be queryable: $body")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /retract/pattern
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `POST retract-pattern removes matching facts`() = testApplication {
        application { module() }

        // Store two likes(alice, ?) facts
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
        // Bob's like should survive
        client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"likes","args":["bob","dave"]}""")
        }

        val response = client.post("/retract/pattern") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"likes","args":["alice","?x"]}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"retracted\":2"), "Expected retracted=2: $body")
    }

    @Test
    fun `POST retract-pattern with no matches returns retracted=0`() = testApplication {
        application { module() }

        val response = client.post("/retract/pattern") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"nonexistent","args":["?x","?y"]}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(
            body.contains("\"retracted\":0"),
            "Expected retracted=0: $body"
        )
    }

    @Test
    fun `POST retract-pattern full wildcard removes all matching predicate facts`() = testApplication {
        application { module() }

        repeat(3) { i ->
            client.post("/tell") {
                header("X-Tenant-ID", "default")
                contentType(ContentType.Application.Json)
                setBody("""{"predicate":"event","args":["item$i","value$i"]}""")
            }
        }

        val response = client.post("/retract/pattern") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"event","args":["?x","?y"]}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"retracted\":3"), "Expected retracted=3: $body")
    }

    @Test
    fun `POST retract-pattern - facts are gone after retraction`() = testApplication {
        application { module() }

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

        client.post("/retract/pattern") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"likes","args":["alice","?x"]}""")
        }

        val queryResponse = client.post("/query") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"likes","args":["alice","?x"]}""")
        }

        assertEquals(HttpStatusCode.OK, queryResponse.status)
        assertEquals("[]", queryResponse.bodyAsText().trim())
    }

    @Test
    fun `POST retract-pattern with blank predicate returns 400`() = testApplication {
        application { module() }

        val response = client.post("/retract/pattern") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"","args":["?x","?y"]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST retract-pattern missing X-Tenant-ID returns 400`() = testApplication {
        application { module() }

        val response = client.post("/retract/pattern") {
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"likes","args":["?x","?y"]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /aggregate — scope filtering
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `POST aggregate with scope parameter only aggregates within that scope`() = testApplication {
        application { module() }

        // Assert facts in scope "scopeAggA"
        client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"xscore","args":["alice","10"],"scope":"scopeAggA"}""")
        }
        client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"xscore","args":["bob","20"],"scope":"scopeAggA"}""")
        }
        // Assert a fact in scope "scopeAggB" — should NOT be included
        client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"xscore","args":["carol","99"],"scope":"scopeAggB"}""")
        }

        val response = client.post("/aggregate") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"xscore","args":["?player","?val"],"operation":"COUNT","scope":"scopeAggA"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(
            body.contains("\"matchedFacts\":2"),
            "Expected matchedFacts=2 (only scopeAggA scope): $body"
        )
    }

    @Test
    fun `POST aggregate SUM with no numeric values returns result 0`() = testApplication {
        application { module() }

        // Assert facts whose second arg is a non-numeric string (Identifier, not NumberLit)
        client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"tag","args":["alice","cool"]}""")
        }
        client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"tag","args":["bob","smart"]}""")
        }

        val response = client.post("/aggregate") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"tag","args":["?who","?val"],"operation":"SUM","argIndex":1}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        // When no numeric values are found, aggregateFacts returns null, route maps to 0.0
        assertTrue(
            body.contains("\"result\":0.0") || body.contains("\"result\": 0.0"),
            "Expected result=0.0 when no numeric args exist: $body"
        )
    }

    @Test
    fun `POST aggregate with invalid operation string returns 400 VALIDATION_ERROR`() = testApplication {
        application { module() }

        val response = client.post("/aggregate") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"score","args":["?x","?y"],"operation":"MULTIPLY"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("VALIDATION_ERROR"), "Expected VALIDATION_ERROR: $body")
        assertTrue(body.contains("MULTIPLY"), "Error should mention the invalid operation: $body")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /assert/facts — scope on individual facts
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `POST assert-facts with scope on individual facts are queryable in their scopes`() = testApplication {
        application { module() }

        val response = client.post("/assert/facts") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""
                {
                  "facts": [
                    {"predicate":"bulkscopedpts","args":["alice","100"],"scope":"bsA"},
                    {"predicate":"bulkscopedpts","args":["bob","200"],"scope":"bsB"},
                    {"predicate":"bulkscopedpts","args":["carol","300"],"scope":"bsA"}
                  ]
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"asserted\":3"), "Expected asserted=3: $body")

        // Query bsA scope — should find alice and carol
        val q1 = client.post("/query") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"bulkscopedpts","args":["?who","?val"],"scope":"bsA"}""")
        }
        val q1Body = q1.bodyAsText()
        assertTrue(q1Body.contains("alice"), "bsA should contain alice: $q1Body")
        assertTrue(q1Body.contains("carol"), "bsA should contain carol: $q1Body")
        assertFalse(q1Body.contains("bob"), "bsA should NOT contain bob: $q1Body")

        // Query bsB scope — should find only bob
        val q2 = client.post("/query") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"bulkscopedpts","args":["?who","?val"],"scope":"bsB"}""")
        }
        val q2Body = q2.bodyAsText()
        assertTrue(q2Body.contains("bob"), "bsB should contain bob: $q2Body")
        assertFalse(q2Body.contains("alice"), "bsB should NOT contain alice: $q2Body")
    }

    @Test
    fun `POST assert-facts with empty facts array returns 400 VALIDATION_ERROR`() = testApplication {
        application { module() }

        val response = client.post("/assert/facts") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"facts":[]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertTrue(
            body.contains("VALIDATION_ERROR") || body.contains("empty"),
            "Expected validation error for empty facts array: $body"
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /retract/pattern — scope and no-match scenarios
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `POST retract-pattern with scope only retracts within that scope`() = testApplication {
        application { module() }

        // Assert facts in two scopes
        client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"item","args":["sword","sharp"],"scope":"inv1"}""")
        }
        client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"item","args":["shield","sturdy"],"scope":"inv1"}""")
        }
        client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"item","args":["potion","healing"],"scope":"inv2"}""")
        }

        // Retract all items in inv1 only
        val response = client.post("/retract/pattern") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"item","args":["?x","?y"],"scope":"inv1"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"retracted\":2"), "Expected retracted=2: $body")

        // Verify inv2 item is still present
        val q = client.post("/query") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"item","args":["?x","?y"],"scope":"inv2"}""")
        }
        val qBody = q.bodyAsText()
        assertTrue(qBody.contains("potion"), "inv2 item should survive: $qBody")
    }

    @Test
    fun `POST retract-pattern with no matches returns retracted 0`() = testApplication {
        application { module() }

        // Store some facts with a different predicate
        client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"color","args":["sky","blue"]}""")
        }

        val response = client.post("/retract/pattern") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"shape","args":["?x","?y"]}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"retracted\":0"), "Expected retracted=0: $body")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MCP tools/call — aggregate, bulk_assert, retract_pattern
    // ─────────────────────────────────────────────────────────────────────────

    /** Post a JSON-RPC 2.0 request to /mcp and return the parsed response object. */
    private suspend fun ApplicationTestBuilder.mcpCall(
        body: String,
        tenantId: String = "default"
    ): JsonObject {
        val response = client.post("/mcp") {
            header("X-Tenant-ID", tenantId)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, response.status, "Expected HTTP 200 for MCP call")
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    /** Extract the text content from a standard MCP tool result. */
    private fun toolText(result: JsonObject): String {
        val content = result["content"]?.jsonArray ?: error("result missing 'content' array: $result")
        return content[0].jsonObject["text"]?.jsonPrimitive?.content
            ?: error("content[0] missing 'text': $result")
    }

    /** Returns true when the tool result carries isError=true. */
    private fun isToolError(result: JsonObject): Boolean =
        result["isError"]?.jsonPrimitive?.booleanOrNull == true

    @Test
    fun `MCP aggregate tool with scope argument filters by scope`() = testApplication {
        application { module() }

        // Assert scoped facts via MCP tell (unique predicate to avoid data dir contamination)
        mcpCall("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"tell","arguments":{"predicate":"mcpscored","args":["alice","10"],"scope":"mcpScopeA"}}}""")
        mcpCall("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"tell","arguments":{"predicate":"mcpscored","args":["bob","20"],"scope":"mcpScopeA"}}}""")
        mcpCall("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"tell","arguments":{"predicate":"mcpscored","args":["carol","99"],"scope":"mcpScopeB"}}}""")

        // Aggregate COUNT with scope=mcpScopeA
        val obj = mcpCall("""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"aggregate","arguments":{"predicate":"mcpscored","args":["?x","?y"],"operation":"COUNT","scope":"mcpScopeA"}}}""")

        assertEquals("2.0", obj["jsonrpc"]?.jsonPrimitive?.content)
        assertFalse(obj["error"]?.let { it !is JsonNull } ?: false, "Unexpected error: ${obj["error"]}")
        val resultElem = obj["result"]
        assertFalse(resultElem == null || resultElem is JsonNull, "Missing result in response: $obj")
        val result = resultElem!!.jsonObject
        assertFalse(isToolError(result), "Should not be a tool error: $result")
        val text = toolText(result)
        assertTrue(text.contains("2"), "Expected count of 2 for mcpScopeA scope: $text")
    }

    @Test
    fun `MCP bulk_assert tool with negated facts stores truthVal false`() = testApplication {
        application { module() }

        val obj = mcpCall("""
            {
              "jsonrpc": "2.0",
              "id": 1,
              "method": "tools/call",
              "params": {
                "name": "bulk_assert",
                "arguments": {
                  "facts": [
                    {"predicate":"mcpvital","args":["zombie"],"negated":true},
                    {"predicate":"mcpvital","args":["human"],"negated":false}
                  ]
                }
              }
            }
        """.trimIndent())

        assertEquals("2.0", obj["jsonrpc"]?.jsonPrimitive?.content)
        assertFalse(obj["error"]?.let { it !is JsonNull } ?: false, "Unexpected error: ${obj["error"]}")
        val resultElem = obj["result"]
        assertFalse(resultElem == null || resultElem is JsonNull, "Missing result in response: $obj")
        val result = resultElem!!.jsonObject
        assertFalse(isToolError(result), "Should not be a tool error: $result")
        val text = toolText(result)
        assertTrue(text.contains("2 stored"), "Expected 2 stored facts: $text")
        assertTrue(text.contains("0 failed"), "Expected 0 failures: $text")

        // Verify the negated fact is stored — query for the predicate and check
        val queryObj = mcpCall("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"ask","arguments":{"predicate":"mcpvital","args":["zombie"]}}}""")
        assertFalse(queryObj["error"]?.let { it !is JsonNull } ?: false, "Unexpected query error: ${queryObj["error"]}")
        val queryResultElem = queryObj["result"]
        assertFalse(queryResultElem == null || queryResultElem is JsonNull, "Missing query result: $queryObj")
        val queryResult = queryResultElem!!.jsonObject
        val queryText = toolText(queryResult)
        // The negated fact should NOT be inferred as true
        assertTrue(
            queryText.contains("No") || queryText.contains("no") || queryText.contains("0"),
            "Negated fact should not be positively inferred: $queryText"
        )
    }

    @Test
    fun `MCP retract_pattern tool with no matches returns appropriate message`() = testApplication {
        application { module() }

        val obj = mcpCall("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"retract_pattern","arguments":{"predicate":"mcpzzznonexist","args":["?x","?y"]}}}""")

        assertEquals("2.0", obj["jsonrpc"]?.jsonPrimitive?.content)
        assertFalse(obj["error"]?.let { it !is JsonNull } ?: false, "Unexpected error: ${obj["error"]}")
        val resultElem = obj["result"]
        assertFalse(resultElem == null || resultElem is JsonNull, "Missing result in response: $obj")
        val result = resultElem!!.jsonObject
        assertFalse(isToolError(result), "Should not be a tool error: $result")
        val text = toolText(result)
        assertTrue(
            text.contains("No facts matched"),
            "Expected 'No facts matched' message: $text"
        )
    }
}
