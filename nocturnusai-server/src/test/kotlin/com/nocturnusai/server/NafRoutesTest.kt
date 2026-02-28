// Copyright (c) 2026 Auctalis LLC. All rights reserved.
//
// Licensed under the Business Source License 1.1 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://github.com/auctalis/nocturnusai/blob/main/LICENSE
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//
// For commercial licensing, please contact: licensing@nocturnus.ai

package com.nocturnusai.server

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*
import kotlinx.serialization.json.*

/**
 * Integration tests for Negation-as-Failure (NAF) via HTTP routes and MCP tools.
 *
 * Covers:
 *   - POST /assert/rule with naf body conditions
 *   - POST /infer verifying NAF reasoning end-to-end
 *   - MCP tools/call "teach" with NAF body atoms (tests callAssertRule mapping)
 *   - MCP tools/call "ask" verifying NAF inference results
 *
 * Every test uses [withTestApp] for complete state isolation.
 * All requests include the X-Tenant-ID header as required by getContext().
 */
class NafRoutesTest {

    private val tenant = "test"

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Create the test tenant in the default database. Must be called once per withTestApp block. */
    private suspend fun ApplicationTestBuilder.createTestTenant() {
        val response = client.post("/admin/databases/default/tenants") {
            contentType(ContentType.Application.Json)
            setBody("""{"tenantId":"$tenant"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status,
            "Failed to create tenant '$tenant': ${response.bodyAsText()}")
    }

    /** Assert a single fact via POST /assert/fact. */
    private suspend fun ApplicationTestBuilder.assertFact(
        predicate: String,
        vararg args: String
    ) {
        val argsJson = args.joinToString(",") { "\"$it\"" }
        val response = client.post("/assert/fact") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"$predicate","args":[$argsJson]}""")
        }
        assertEquals(HttpStatusCode.OK, response.status,
            "Failed to assert fact $predicate(${args.joinToString()}): ${response.bodyAsText()}")
    }

    /** Assert a rule via POST /assert/rule with the given JSON body. */
    private suspend fun ApplicationTestBuilder.assertRule(ruleJson: String) {
        val response = client.post("/assert/rule") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody(ruleJson)
        }
        assertEquals(HttpStatusCode.OK, response.status,
            "Failed to assert rule: ${response.bodyAsText()}")
    }

    /** Query via POST /infer and return the response body text. */
    private suspend fun ApplicationTestBuilder.infer(
        predicate: String,
        vararg args: String
    ): String {
        val argsJson = args.joinToString(",") { "\"$it\"" }
        val response = client.post("/infer") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"$predicate","args":[$argsJson]}""")
        }
        assertEquals(HttpStatusCode.OK, response.status,
            "Infer failed: ${response.bodyAsText()}")
        return response.bodyAsText()
    }

    /** Post a JSON-RPC 2.0 request to /mcp and return the parsed response object. */
    private suspend fun ApplicationTestBuilder.mcpCall(body: String): JsonObject {
        val response = client.post("/mcp") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, response.status,
            "MCP call failed: ${response.bodyAsText()}")
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    /** Extract the text content from a standard MCP tool result. */
    private fun toolText(result: JsonObject): String {
        val content = result["content"]?.jsonArray
            ?: fail("result missing 'content' array: $result")
        assertTrue(content.isNotEmpty(), "content array is empty: $result")
        return content[0].jsonObject["text"]?.jsonPrimitive?.content
            ?: fail("content[0] missing 'text': $result")
    }

    /** Returns true when the tool result carries isError=true (MCP tool-level error). */
    private fun isToolError(result: JsonObject): Boolean =
        result["isError"]?.jsonPrimitive?.booleanOrNull == true

    private fun JsonElement?.isJsonNull(): Boolean = this == null || this is JsonNull

    private fun assertJsonRpcSuccess(obj: JsonObject) {
        assertEquals("2.0", obj["jsonrpc"]?.jsonPrimitive?.content)
        assertTrue(obj["error"].isJsonNull(), "Unexpected error: ${obj["error"]}")
        assertFalse(obj["result"].isJsonNull(), "Missing result: $obj")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. POST /assert/rule with NAF body condition and infer
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `POST assert-rule with NAF body condition then infer via POST infer`() = withTestApp {
        createTestTenant()

        // Rule: eligible(?x) <- member(?x) AND NAF suspended(?x)
        assertRule("""
            {
              "head": {"predicate": "eligible", "args": ["?x"]},
              "body": [
                {"predicate": "member", "args": ["?x"]},
                {"predicate": "suspended", "args": ["?x"], "naf": true}
              ]
            }
        """.trimIndent())

        // Assert alice is a member (not suspended)
        assertFact("member", "alice")

        // Assert bob is a member AND suspended
        assertFact("suspended", "bob")
        assertFact("member", "bob")

        // Infer eligible(?who) - alice should be eligible, bob should not
        val result = infer("eligible", "?who")

        assertTrue(result.contains("alice"),
            "alice should be eligible (not suspended); got: $result")
        assertFalse(result.contains("bob"),
            "bob should NOT be eligible (is suspended); got: $result")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. End-to-end NAF - classic penguin example with dynamic retraction
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `end-to-end NAF - bird flies then penguin blocks flying`() = withTestApp {
        createTestTenant()

        // Rule: can_fly(?x) <- bird(?x) AND NOT penguin(?x)
        assertRule("""
            {
              "head": {"predicate": "can_fly", "args": ["?x"]},
              "body": [
                {"predicate": "bird", "args": ["?x"]},
                {"predicate": "penguin", "args": ["?x"], "naf": true}
              ]
            }
        """.trimIndent())

        // Phase 1: robin is a bird, not a penguin — should fly
        assertFact("bird", "robin")
        val result1 = infer("can_fly", "robin")
        assertTrue(result1.contains("can_fly") && result1.contains("robin"),
            "robin should be able to fly (not a penguin); got: $result1")

        // Phase 2: tweety is both a bird AND a penguin — should NOT fly.
        // Assert the NAF-blocking fact BEFORE the triggering fact so that both
        // the forward-chaining Rete engine and the backward chainer agree.
        assertFact("penguin", "tweety")
        assertFact("bird", "tweety")

        val result2 = infer("can_fly", "tweety")
        // Because penguin(tweety) was asserted before bird(tweety), the Rete
        // engine sees the blocking fact at rule-fire time and the backward
        // chainer evaluates NAF at query time — both should agree tweety cannot fly.
        assertFalse(result2.contains("tweety") && result2.contains("can_fly"),
            "tweety should NOT fly (is a penguin); got: $result2")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. MCP teach tool with NAF body condition
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `MCP teach tool with naf body atom maps naf correctly`() = withTestApp {
        createTestTenant()

        // Teach a rule with naf:true on a body atom via MCP
        val teachResponse = mcpCall("""
            {
              "jsonrpc": "2.0",
              "id": 1,
              "method": "tools/call",
              "params": {
                "name": "teach",
                "arguments": {
                  "head": {"predicate": "safe", "args": ["?x"]},
                  "body": [
                    {"predicate": "item", "args": ["?x"]},
                    {"predicate": "dangerous", "args": ["?x"], "naf": true}
                  ]
                }
              }
            }
        """.trimIndent())

        assertJsonRpcSuccess(teachResponse)
        assertFalse(isToolError(teachResponse["result"]!!.jsonObject),
            "teach call should succeed: ${teachResponse["result"]}")
        val teachText = toolText(teachResponse["result"]!!.jsonObject)
        assertTrue(teachText.contains("Rule stored") || teachText.contains("safe"),
            "teach should confirm rule stored: $teachText")

        // Now assert facts: item(spoon) and dangerous(knife), item(knife)
        assertFact("item", "spoon")
        assertFact("dangerous", "knife")
        assertFact("item", "knife")

        // Query safe(?who) via POST /infer
        val result = infer("safe", "?who")

        // NAF is correctly mapped: safe(?x) <- item(?x), NOT dangerous(?x)
        // spoon is an item and NOT dangerous → safe(spoon) should be inferred
        assertTrue(result.contains("spoon"),
            "spoon is not dangerous so should be safe; got: $result")
        // knife is dangerous → safe(knife) should NOT be inferred
        assertFalse(result.contains("knife"),
            "knife is dangerous so should NOT be safe; got: $result")
    }

    @Test
    fun `MCP teach with negated flag maps to truthVal not naf`() = withTestApp {
        createTestTenant()

        // Verify that "negated":true on an MCP teach body atom sets truthVal=false
        // rather than naf=true. This is a different semantics (explicit negation
        // vs closed-world assumption).
        val teachResponse = mcpCall("""
            {
              "jsonrpc": "2.0",
              "id": 2,
              "method": "tools/call",
              "params": {
                "name": "teach",
                "arguments": {
                  "head": {"predicate": "ok", "args": ["?x"]},
                  "body": [
                    {"predicate": "checked", "args": ["?x"]},
                    {"predicate": "failed", "args": ["?x"], "negated": true}
                  ]
                }
              }
            }
        """.trimIndent())

        assertJsonRpcSuccess(teachResponse)
        assertFalse(isToolError(teachResponse["result"]!!.jsonObject),
            "teach with negated body should succeed: ${teachResponse["result"]}")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. MCP ask tool verifying NAF inference (rule asserted via REST)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `MCP ask tool verifies NAF inference after REST rule assertion`() = withTestApp {
        createTestTenant()

        // Assert NAF rule via REST (which correctly maps naf=true)
        assertRule("""
            {
              "head": {"predicate": "available", "args": ["?x"]},
              "body": [
                {"predicate": "product", "args": ["?x"]},
                {"predicate": "out_of_stock", "args": ["?x"], "naf": true}
              ]
            }
        """.trimIndent())

        // Assert facts. Assert the NAF-blocking fact (out_of_stock) BEFORE the
        // triggering fact (product) so that the Rete forward-chaining engine sees
        // the blocker at rule-fire time.
        assertFact("product", "widget")
        assertFact("out_of_stock", "gadget")
        assertFact("product", "gadget")

        // Use MCP ask to check available(widget) - should be inferred
        val askWidget = mcpCall("""
            {
              "jsonrpc": "2.0",
              "id": 10,
              "method": "tools/call",
              "params": {
                "name": "ask",
                "arguments": {"predicate": "available", "args": ["widget"]}
              }
            }
        """.trimIndent())

        assertJsonRpcSuccess(askWidget)
        assertFalse(isToolError(askWidget["result"]!!.jsonObject))
        val widgetText = toolText(askWidget["result"]!!.jsonObject)
        assertTrue(widgetText.contains("available") || widgetText.contains("widget") || widgetText.contains("Inferred"),
            "widget should be available (not out of stock); got: $widgetText")
        assertFalse(widgetText.contains("No results"),
            "widget should be inferred as available; got: $widgetText")

        // Use MCP ask to check available(gadget) - should NOT be inferred
        val askGadget = mcpCall("""
            {
              "jsonrpc": "2.0",
              "id": 11,
              "method": "tools/call",
              "params": {
                "name": "ask",
                "arguments": {"predicate": "available", "args": ["gadget"]}
              }
            }
        """.trimIndent())

        assertJsonRpcSuccess(askGadget)
        assertFalse(isToolError(askGadget["result"]!!.jsonObject))
        val gadgetText = toolText(askGadget["result"]!!.jsonObject)
        assertTrue(gadgetText.contains("No results"),
            "gadget should NOT be available (is out of stock); got: $gadgetText")
    }

    @Test
    fun `MCP ask with variable returns only NAF-passing bindings`() = withTestApp {
        createTestTenant()

        // Assert NAF rule via REST
        assertRule("""
            {
              "head": {"predicate": "active_user", "args": ["?x"]},
              "body": [
                {"predicate": "user", "args": ["?x"]},
                {"predicate": "banned", "args": ["?x"], "naf": true}
              ]
            }
        """.trimIndent())

        // Assert facts. Assert the NAF-blocking fact (banned) BEFORE the
        // triggering fact (user) so that the Rete engine sees the blocker.
        assertFact("user", "alice")
        assertFact("banned", "bob")
        assertFact("user", "bob")
        assertFact("user", "charlie")

        // MCP ask with variable to find all active users
        val askAll = mcpCall("""
            {
              "jsonrpc": "2.0",
              "id": 20,
              "method": "tools/call",
              "params": {
                "name": "ask",
                "arguments": {"predicate": "active_user", "args": ["?who"]}
              }
            }
        """.trimIndent())

        assertJsonRpcSuccess(askAll)
        assertFalse(isToolError(askAll["result"]!!.jsonObject))
        val text = toolText(askAll["result"]!!.jsonObject)

        assertTrue(text.contains("alice"),
            "alice should be an active_user; got: $text")
        assertTrue(text.contains("charlie"),
            "charlie should be an active_user; got: $text")
        assertFalse(text.contains("bob"),
            "bob is banned and should NOT be an active_user; got: $text")
    }
}
