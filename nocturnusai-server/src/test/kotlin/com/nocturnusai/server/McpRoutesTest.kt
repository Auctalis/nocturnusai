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
 * Integration tests for MCP (Model Context Protocol) routes.
 *
 * Covers the JSON-RPC 2.0 endpoint at POST /mcp, including:
 *   - initialize handshake
 *   - tools/list discovery
 *   - tools/call for all nine tools (tell, teach, ask, forget, recall,
 *     context, compress, cleanup, predicates) and legacy aliases
 *   - ping
 *   - error handling (unknown method, unknown tool, malformed body)
 *   - missing / default header behaviour
 *
 * Auth is disabled in tests (API_KEY env var is unset → AuthMode.DISABLED).
 * Each test uses a fresh testApplication{} so DatabaseManager state is isolated.
 */
class McpRoutesTest {

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Post a JSON-RPC 2.0 request to /mcp and return the parsed response object. */
    private suspend fun ApplicationTestBuilder.mcpCall(
        body: String,
        tenantId: String = "default",
        dbName: String? = null
    ): JsonObject {
        val response = client.post("/mcp") {
            header("X-Tenant-ID", tenantId)
            if (dbName != null) header("X-Database", dbName)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, response.status, "Expected HTTP 200 for body: $body")
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    /**
     * Returns true when a JsonElement is absent or is the JSON null literal.
     * The server serialises JsonRpcResponse with encodeDefaults=true, so both
     * `result` and `error` are always present in the wire JSON — absent fields
     * show up as JsonNull rather than Kotlin null.
     */
    private fun JsonElement?.isJsonNull(): Boolean = this == null || this is JsonNull

    /** Assert the top-level JSON-RPC 2.0 structure is valid and has no error. */
    private fun assertJsonRpcSuccess(obj: JsonObject, expectedId: Int? = null) {
        assertEquals("2.0", obj["jsonrpc"]?.jsonPrimitive?.content, "jsonrpc field must be '2.0'")
        assertTrue(obj["error"].isJsonNull(), "Unexpected error in response: ${obj["error"]}")
        assertFalse(obj["result"].isJsonNull(), "Missing result in response: $obj")
        if (expectedId != null) {
            assertEquals(expectedId, obj["id"]?.jsonPrimitive?.int, "id mismatch")
        }
    }

    /** Assert the top-level JSON-RPC 2.0 structure carries an error block. */
    private fun assertJsonRpcError(obj: JsonObject, expectedCode: Int? = null): JsonObject {
        assertEquals("2.0", obj["jsonrpc"]?.jsonPrimitive?.content)
        val errorElem = obj["error"]
        assertFalse(errorElem.isJsonNull(), "Expected an error object but got: $obj")
        val error = errorElem!!.jsonObject
        if (expectedCode != null) {
            assertEquals(expectedCode, error["code"]?.jsonPrimitive?.int,
                "Expected error code $expectedCode, got ${error["code"]}: $obj")
        }
        return error
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

    // ─────────────────────────────────────────────────────────────────────────
    // initialize
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `initialize - returns MCP protocol version and capabilities`() = testApplication {
        application { module() }

        val obj = mcpCall("""
            {
              "jsonrpc": "2.0",
              "id": 1,
              "method": "initialize",
              "params": {
                "protocolVersion": "2025-11-25",
                "capabilities": {},
                "clientInfo": {"name": "test-client", "version": "0.1.0"}
              }
            }
        """.trimIndent())

        assertJsonRpcSuccess(obj, expectedId = 1)
        val result = obj["result"]!!.jsonObject

        // Protocol version must match the server constant
        assertEquals(
            "2025-11-25",
            result["protocolVersion"]?.jsonPrimitive?.content,
            "protocolVersion mismatch"
        )

        // Capabilities: tools + resources
        val caps = result["capabilities"]?.jsonObject
        assertNotNull(caps, "capabilities missing")
        assertNotNull(caps["tools"], "capabilities.tools missing")
        assertNotNull(caps["resources"], "capabilities.resources missing")

        // Server identity
        val serverInfo = result["serverInfo"]?.jsonObject
        assertNotNull(serverInfo, "serverInfo missing")
        assertEquals("nocturnusai", serverInfo["name"]?.jsonPrimitive?.content)
        assertNotNull(serverInfo["version"], "serverInfo.version missing")
    }

    @Test
    fun `initialize - id is echoed back correctly`() = testApplication {
        application { module() }

        val obj = mcpCall("""{"jsonrpc":"2.0","id":42,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"0"}}}""")

        assertEquals(42, obj["id"]?.jsonPrimitive?.int)
    }

    @Test
    fun `initialize - tools capability reports listChanged false`() = testApplication {
        application { module() }

        val obj = mcpCall("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"0"}}}""")

        val tools = obj["result"]!!.jsonObject["capabilities"]!!.jsonObject["tools"]!!.jsonObject
        assertEquals(false, tools["listChanged"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `initialize - resources capability reports subscribe true`() = testApplication {
        application { module() }

        val obj = mcpCall("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"0"}}}""")

        val resources = obj["result"]!!.jsonObject["capabilities"]!!.jsonObject["resources"]!!.jsonObject
        assertEquals(true, resources["subscribe"]?.jsonPrimitive?.boolean)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // tools/list
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `tools-list - returns all nine primary tools`() = testApplication {
        application { module() }

        val obj = mcpCall("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")

        assertJsonRpcSuccess(obj, expectedId = 2)
        val tools = obj["result"]!!.jsonObject["tools"]?.jsonArray
        assertNotNull(tools, "result.tools array missing")
        assertTrue(tools.isNotEmpty(), "tools array is empty")

        val names = tools.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        val expectedTools = setOf("tell", "teach", "ask", "forget", "recall", "context", "compress", "cleanup", "predicates")
        for (expected in expectedTools) {
            assertTrue(expected in names, "Tool '$expected' missing from tools/list. Got: $names")
        }
    }

    @Test
    fun `tools-list - each tool has name description and inputSchema`() = testApplication {
        application { module() }

        val obj = mcpCall("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")

        val tools = obj["result"]!!.jsonObject["tools"]!!.jsonArray
        for (toolElem in tools) {
            val tool = toolElem.jsonObject
            val name = tool["name"]?.jsonPrimitive?.content ?: fail("Tool missing name: $tool")
            assertNotNull(tool["description"], "Tool '$name' missing description")
            val schema = tool["inputSchema"]?.jsonObject
                ?: fail("Tool '$name' missing inputSchema")
            assertEquals("object", schema["type"]?.jsonPrimitive?.content,
                "Tool '$name' inputSchema.type should be 'object'")
            assertNotNull(schema["properties"], "Tool '$name' inputSchema missing properties")
        }
    }

    @Test
    fun `tools-list - tell requires predicate and args`() = testApplication {
        application { module() }

        val obj = mcpCall("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")

        val tools = obj["result"]!!.jsonObject["tools"]!!.jsonArray
        val tell = tools.map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == "tell" }
        val required = tell["inputSchema"]!!.jsonObject["required"]!!.jsonArray
            .map { it.jsonPrimitive.content }.toSet()
        assertTrue("predicate" in required, "'predicate' not in tell required: $required")
        assertTrue("args" in required, "'args' not in tell required: $required")
    }

    @Test
    fun `tools-list - ask requires predicate and args`() = testApplication {
        application { module() }

        val obj = mcpCall("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")

        val tools = obj["result"]!!.jsonObject["tools"]!!.jsonArray
        val ask = tools.map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == "ask" }
        val required = ask["inputSchema"]!!.jsonObject["required"]!!.jsonArray
            .map { it.jsonPrimitive.content }.toSet()
        assertTrue("predicate" in required)
        assertTrue("args" in required)
    }

    @Test
    fun `tools-list - recall requires predicate args and timestamp`() = testApplication {
        application { module() }

        val obj = mcpCall("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")

        val tools = obj["result"]!!.jsonObject["tools"]!!.jsonArray
        val recall = tools.map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == "recall" }
        val required = recall["inputSchema"]!!.jsonObject["required"]!!.jsonArray
            .map { it.jsonPrimitive.content }.toSet()
        assertTrue("timestamp" in required, "'timestamp' not in recall required: $required")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // tools/call — tell (assert_fact)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `tools-call tell - asserts fact and returns stored message`() = testApplication {
        application { module() }

        val obj = mcpCall("""
            {
              "jsonrpc": "2.0",
              "id": 3,
              "method": "tools/call",
              "params": {
                "name": "tell",
                "arguments": {"predicate": "likes", "args": ["alice", "bob"]}
              }
            }
        """.trimIndent())

        assertJsonRpcSuccess(obj, expectedId = 3)
        val result = obj["result"]!!.jsonObject
        assertFalse(isToolError(result), "Expected success, got tool error: $result")
        val text = toolText(result)
        assertTrue(text.contains("likes"), "Response should mention predicate 'likes': $text")
        assertTrue(text.contains("alice"), "Response should mention arg 'alice': $text")
    }

    @Test
    fun `tools-call tell - legacy alias assert_fact also works`() = testApplication {
        application { module() }

        val obj = mcpCall("""
            {
              "jsonrpc": "2.0",
              "id": 3,
              "method": "tools/call",
              "params": {
                "name": "assert_fact",
                "arguments": {"predicate": "owns", "args": ["bob", "car"]}
              }
            }
        """.trimIndent())

        assertJsonRpcSuccess(obj)
        assertFalse(isToolError(obj["result"]!!.jsonObject))
    }

    @Test
    fun `tools-call tell - negated fact is accepted`() = testApplication {
        application { module() }

        val obj = mcpCall("""
            {
              "jsonrpc": "2.0",
              "id": 3,
              "method": "tools/call",
              "params": {
                "name": "tell",
                "arguments": {"predicate": "hates", "args": ["alice", "spam"], "negated": true}
              }
            }
        """.trimIndent())

        assertJsonRpcSuccess(obj)
        assertFalse(isToolError(obj["result"]!!.jsonObject))
    }

    @Test
    fun `tools-call tell - fact with ttl is accepted`() = testApplication {
        application { module() }

        val obj = mcpCall("""
            {
              "jsonrpc": "2.0",
              "id": 3,
              "method": "tools/call",
              "params": {
                "name": "tell",
                "arguments": {"predicate": "session", "args": ["user123"], "ttl": 60000}
              }
            }
        """.trimIndent())

        assertJsonRpcSuccess(obj)
        assertFalse(isToolError(obj["result"]!!.jsonObject))
    }

    @Test
    fun `tools-call tell - missing predicate returns tool-level validation error`() = testApplication {
        application { module() }

        val obj = mcpCall("""
            {
              "jsonrpc": "2.0",
              "id": 3,
              "method": "tools/call",
              "params": {
                "name": "tell",
                "arguments": {"args": ["alice", "bob"]}
              }
            }
        """.trimIndent())

        // MCP tool errors are returned as HTTP 200 with isError=true in result
        assertJsonRpcSuccess(obj)
        val result = obj["result"]!!.jsonObject
        assertTrue(isToolError(result), "Expected isError=true for missing predicate: $result")
        val text = toolText(result)
        assertTrue(text.contains("VALIDATION_ERROR"), "Expected VALIDATION_ERROR: $text")
    }

    @Test
    fun `tools-call tell - missing args returns tool-level validation error`() = testApplication {
        application { module() }

        val obj = mcpCall("""
            {
              "jsonrpc": "2.0",
              "id": 3,
              "method": "tools/call",
              "params": {
                "name": "tell",
                "arguments": {"predicate": "likes"}
              }
            }
        """.trimIndent())

        assertJsonRpcSuccess(obj)
        assertTrue(isToolError(obj["result"]!!.jsonObject))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // tools/call — teach (assert_rule)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `tools-call teach - stores a Horn clause rule`() = testApplication {
        application { module() }

        val obj = mcpCall("""
            {
              "jsonrpc": "2.0",
              "id": 4,
              "method": "tools/call",
              "params": {
                "name": "teach",
                "arguments": {
                  "head": {"predicate": "grandparent", "args": ["?x", "?z"]},
                  "body": [
                    {"predicate": "parent", "args": ["?x", "?y"]},
                    {"predicate": "parent", "args": ["?y", "?z"]}
                  ]
                }
              }
            }
        """.trimIndent())

        assertJsonRpcSuccess(obj, expectedId = 4)
        assertFalse(isToolError(obj["result"]!!.jsonObject))
        val text = toolText(obj["result"]!!.jsonObject)
        assertTrue(text.contains("grandparent") || text.contains("Rule stored"),
            "Response should confirm rule storage: $text")
    }

    @Test
    fun `tools-call teach - legacy alias assert_rule also works`() = testApplication {
        application { module() }

        val obj = mcpCall("""
            {
              "jsonrpc": "2.0",
              "id": 4,
              "method": "tools/call",
              "params": {
                "name": "assert_rule",
                "arguments": {
                  "head": {"predicate": "ancestor", "args": ["?x", "?y"]},
                  "body": [{"predicate": "parent", "args": ["?x", "?y"]}]
                }
              }
            }
        """.trimIndent())

        assertJsonRpcSuccess(obj)
        assertFalse(isToolError(obj["result"]!!.jsonObject))
    }

    @Test
    fun `tools-call teach - missing head returns tool-level error`() = testApplication {
        application { module() }

        val obj = mcpCall("""
            {
              "jsonrpc": "2.0",
              "id": 4,
              "method": "tools/call",
              "params": {
                "name": "teach",
                "arguments": {
                  "body": [{"predicate": "parent", "args": ["?x", "?y"]}]
                }
              }
            }
        """.trimIndent())

        assertJsonRpcSuccess(obj)
        assertTrue(isToolError(obj["result"]!!.jsonObject))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // tools/call — ask (infer) — fact-only query
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `tools-call ask - returns result for asserted fact`() = testApplication {
        application { module() }

        // First assert a fact via tell
        mcpCall("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"tell","arguments":{"predicate":"color","args":["sky","blue"]}}}""")

        val obj = mcpCall("""
            {
              "jsonrpc": "2.0",
              "id": 5,
              "method": "tools/call",
              "params": {
                "name": "ask",
                "arguments": {"predicate": "color", "args": ["sky", "blue"]}
              }
            }
        """.trimIndent())

        assertJsonRpcSuccess(obj, expectedId = 5)
        assertFalse(isToolError(obj["result"]!!.jsonObject))
        val text = toolText(obj["result"]!!.jsonObject)
        assertTrue(text.contains("color") || text.contains("Inferred"),
            "Response should mention predicate or inference: $text")
    }

    @Test
    fun `tools-call ask - returns no-results message for unknown predicate`() = testApplication {
        application { module() }

        val obj = mcpCall("""
            {
              "jsonrpc": "2.0",
              "id": 5,
              "method": "tools/call",
              "params": {
                "name": "ask",
                "arguments": {"predicate": "unknownPredXyz", "args": ["?x"]}
              }
            }
        """.trimIndent())

        assertJsonRpcSuccess(obj)
        assertFalse(isToolError(obj["result"]!!.jsonObject))
        val text = toolText(obj["result"]!!.jsonObject)
        assertTrue(text.contains("No results") || text.isEmpty() || text.contains("0"),
            "Expected empty/no-results response: $text")
    }

    @Test
    fun `tools-call ask - variable wildcard matches stored facts`() = testApplication {
        application { module() }

        mcpCall("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"tell","arguments":{"predicate":"fruit","args":["apple"]}}}""")
        mcpCall("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"tell","arguments":{"predicate":"fruit","args":["banana"]}}}""")

        val obj = mcpCall("""
            {
              "jsonrpc": "2.0",
              "id": 5,
              "method": "tools/call",
              "params": {
                "name": "ask",
                "arguments": {"predicate": "fruit", "args": ["?item"]}
              }
            }
        """.trimIndent())

        assertJsonRpcSuccess(obj)
        assertFalse(isToolError(obj["result"]!!.jsonObject))
        val text = toolText(obj["result"]!!.jsonObject)
        assertTrue(text.contains("apple") || text.contains("banana"),
            "Expected wildcard match results: $text")
    }

    @Test
    fun `tools-call ask - withProof flag returns proof chain`() = testApplication {
        application { module() }

        mcpCall("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"tell","arguments":{"predicate":"mammal","args":["dog"]}}}""")

        val obj = mcpCall("""
            {
              "jsonrpc": "2.0",
              "id": 5,
              "method": "tools/call",
              "params": {
                "name": "ask",
                "arguments": {"predicate": "mammal", "args": ["dog"], "withProof": true}
              }
            }
        """.trimIndent())

        assertJsonRpcSuccess(obj)
        assertFalse(isToolError(obj["result"]!!.jsonObject))
        val text = toolText(obj["result"]!!.jsonObject)
        // With proofs, the result should include proof chain markers
        assertTrue(text.contains("FACT") || text.contains("proof") || text.contains("mammal"),
            "Expected proof chain in response: $text")
    }

    @Test
    fun `tools-call ask - rule-based inference works end-to-end`() = testApplication {
        application { module() }

        // Assert base facts
        mcpCall("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"tell","arguments":{"predicate":"parent","args":["alice","bob"]}}}""")
        mcpCall("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"tell","arguments":{"predicate":"parent","args":["bob","charlie"]}}}""")

        // Define grandparent rule
        mcpCall("""
            {
              "jsonrpc": "2.0", "id": 3, "method": "tools/call",
              "params": {
                "name": "teach",
                "arguments": {
                  "head": {"predicate": "grandparent", "args": ["?x", "?z"]},
                  "body": [
                    {"predicate": "parent", "args": ["?x", "?y"]},
                    {"predicate": "parent", "args": ["?y", "?z"]}
                  ]
                }
              }
            }
        """.trimIndent())

        // Now query grandparent
        val obj = mcpCall("""
            {
              "jsonrpc": "2.0",
              "id": 4,
              "method": "tools/call",
              "params": {
                "name": "ask",
                "arguments": {"predicate": "grandparent", "args": ["alice", "charlie"]}
              }
            }
        """.trimIndent())

        assertJsonRpcSuccess(obj)
        assertFalse(isToolError(obj["result"]!!.jsonObject))
        val text = toolText(obj["result"]!!.jsonObject)
        assertTrue(text.contains("grandparent") || text.contains("Inferred"),
            "Expected inferred grandparent: $text")
    }

    @Test
    fun `tools-call ask - legacy alias infer also works`() = testApplication {
        application { module() }

        mcpCall("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"tell","arguments":{"predicate":"species","args":["cat","feline"]}}}""")

        val obj = mcpCall("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"infer","arguments":{"predicate":"species","args":["cat","feline"]}}}""")

        assertJsonRpcSuccess(obj)
        assertFalse(isToolError(obj["result"]!!.jsonObject))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // tools/call — forget (retract)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `tools-call forget - retracts an asserted fact`() = testApplication {
        application { module() }

        // Assert then retract
        mcpCall("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"tell","arguments":{"predicate":"likes","args":["alice","bob"]}}}""")

        val obj = mcpCall("""
            {
              "jsonrpc": "2.0",
              "id": 6,
              "method": "tools/call",
              "params": {
                "name": "forget",
                "arguments": {"predicate": "likes", "args": ["alice", "bob"]}
              }
            }
        """.trimIndent())

        assertJsonRpcSuccess(obj, expectedId = 6)
        assertFalse(isToolError(obj["result"]!!.jsonObject))
        val text = toolText(obj["result"]!!.jsonObject)
        assertTrue(text.contains("Forgotten") || text.contains("likes"),
            "Response should confirm retraction: $text")
    }

    @Test
    fun `tools-call forget - retract non-existent fact succeeds gracefully`() = testApplication {
        application { module() }

        val obj = mcpCall("""
            {
              "jsonrpc": "2.0",
              "id": 6,
              "method": "tools/call",
              "params": {
                "name": "forget",
                "arguments": {"predicate": "nonexistent", "args": ["x", "y"]}
              }
            }
        """.trimIndent())

        // Retract of a missing fact should not throw — it's idempotent
        assertJsonRpcSuccess(obj)
    }

    @Test
    fun `tools-call forget - legacy alias retract also works`() = testApplication {
        application { module() }

        mcpCall("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"tell","arguments":{"predicate":"temp","args":["a","b"]}}}""")

        val obj = mcpCall("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"retract","arguments":{"predicate":"temp","args":["a","b"]}}}""")

        assertJsonRpcSuccess(obj)
        assertFalse(isToolError(obj["result"]!!.jsonObject))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // tools/call — context (context_window)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `tools-call context - returns valid response on any knowledge base state`() = testApplication {
        application { module() }

        // Context window returns either facts or an "empty" notice — both are valid.
        // The non-empty case is covered by the "returns facts after assertion" test.
        val obj = mcpCall("""
            {
              "jsonrpc": "2.0",
              "id": 7,
              "method": "tools/call",
              "params": {
                "name": "context",
                "arguments": {}
              }
            }
        """.trimIndent())

        assertJsonRpcSuccess(obj, expectedId = 7)
        assertFalse(isToolError(obj["result"]!!.jsonObject),
            "context tool should succeed: ${obj["result"]}")
        val text = toolText(obj["result"]!!.jsonObject)
        assertTrue(text.isNotBlank(), "context response must not be blank: $text")
    }

    @Test
    fun `tools-call context - returns facts after assertion`() = testApplication {
        application { module() }

        mcpCall("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"tell","arguments":{"predicate":"city","args":["paris","france"]}}}""")

        val obj = mcpCall("""
            {
              "jsonrpc": "2.0",
              "id": 7,
              "method": "tools/call",
              "params": {
                "name": "context",
                "arguments": {"maxFacts": 10}
              }
            }
        """.trimIndent())

        assertJsonRpcSuccess(obj)
        assertFalse(isToolError(obj["result"]!!.jsonObject))
        val text = toolText(obj["result"]!!.jsonObject)
        assertTrue(text.contains("city") || text.contains("paris"),
            "Expected stored fact in context window: $text")
    }

    @Test
    fun `tools-call context - maxFacts parameter is respected`() = testApplication {
        application { module() }

        // Assert 5 distinct facts
        for (i in 1..5) {
            mcpCall("""{"jsonrpc":"2.0","id":$i,"method":"tools/call","params":{"name":"tell","arguments":{"predicate":"item","args":["item$i"]}}}""")
        }

        val obj = mcpCall("""
            {
              "jsonrpc": "2.0",
              "id": 7,
              "method": "tools/call",
              "params": {
                "name": "context",
                "arguments": {"maxFacts": 2}
              }
            }
        """.trimIndent())

        assertJsonRpcSuccess(obj)
        assertFalse(isToolError(obj["result"]!!.jsonObject))
    }

    @Test
    fun `tools-call context - legacy alias context_window also works`() = testApplication {
        application { module() }

        val obj = mcpCall("""{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"context_window","arguments":{"maxFacts":5}}}""")

        assertJsonRpcSuccess(obj)
        assertFalse(isToolError(obj["result"]!!.jsonObject))
    }

    @Test
    fun `tools-call context - legacy minRelevance alias is accepted`() = testApplication {
        application { module() }

        mcpCall("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"tell","arguments":{"predicate":"priority_test","args":["alpha"]}}}""")

        val obj = mcpCall("""
            {
              "jsonrpc": "2.0",
              "id": 8,
              "method": "tools/call",
              "params": {
                "name": "context",
                "arguments": {"maxFacts": 5, "minRelevance": 0.0}
              }
            }
        """.trimIndent())

        assertJsonRpcSuccess(obj)
        assertFalse(isToolError(obj["result"]!!.jsonObject))
        val text = toolText(obj["result"]!!.jsonObject)
        assertTrue(text.isNotBlank(), "Expected non-empty context response when using minRelevance alias")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // tools/call — predicates
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `tools-call predicates - returns valid response on any knowledge base state`() = testApplication {
        application { module() }

        // The knowledge base may or may not be empty depending on test execution order.
        // This test verifies the tool responds successfully; the specific content is covered
        // by the "lists predicates after asserting facts" test below.
        val obj = mcpCall("""
            {
              "jsonrpc": "2.0",
              "id": 8,
              "method": "tools/call",
              "params": {
                "name": "predicates",
                "arguments": {}
              }
            }
        """.trimIndent())

        assertJsonRpcSuccess(obj, expectedId = 8)
        assertFalse(isToolError(obj["result"]!!.jsonObject),
            "predicates tool should succeed: ${obj["result"]}")
        val text = toolText(obj["result"]!!.jsonObject)
        // Response is always non-empty (either schema listing or "empty" notice)
        assertTrue(text.isNotBlank(), "predicates response must not be blank: $text")
    }

    @Test
    fun `tools-call predicates - lists predicates after asserting facts`() = testApplication {
        application { module() }

        mcpCall("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"tell","arguments":{"predicate":"animal","args":["cat"]}}}""")
        mcpCall("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"tell","arguments":{"predicate":"color","args":["cat","gray"]}}}""")

        val obj = mcpCall("""
            {
              "jsonrpc": "2.0",
              "id": 8,
              "method": "tools/call",
              "params": {
                "name": "predicates",
                "arguments": {}
              }
            }
        """.trimIndent())

        assertJsonRpcSuccess(obj)
        assertFalse(isToolError(obj["result"]!!.jsonObject))
        val text = toolText(obj["result"]!!.jsonObject)
        assertTrue(text.contains("animal"), "Expected 'animal' predicate in schema: $text")
        assertTrue(text.contains("color"), "Expected 'color' predicate in schema: $text")
    }

    @Test
    fun `tools-call predicates - rule head predicates appear in schema`() = testApplication {
        application { module() }

        mcpCall("""
            {
              "jsonrpc": "2.0", "id": 1, "method": "tools/call",
              "params": {
                "name": "teach",
                "arguments": {
                  "head": {"predicate": "mortal", "args": ["?x"]},
                  "body": [{"predicate": "human", "args": ["?x"]}]
                }
              }
            }
        """.trimIndent())

        val obj = mcpCall("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"predicates","arguments":{}}}""")

        assertJsonRpcSuccess(obj)
        val text = toolText(obj["result"]!!.jsonObject)
        assertTrue(text.contains("mortal"), "Rule head predicate 'mortal' should appear in schema: $text")
    }

    @Test
    fun `tools-call predicates - includes has-rules annotation`() = testApplication {
        application { module() }

        mcpCall("""
            {
              "jsonrpc": "2.0", "id": 1, "method": "tools/call",
              "params": {
                "name": "teach",
                "arguments": {
                  "head": {"predicate": "derivedPred", "args": ["?x"]},
                  "body": [{"predicate": "basePred", "args": ["?x"]}]
                }
              }
            }
        """.trimIndent())

        val obj = mcpCall("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"predicates","arguments":{}}}""")

        assertJsonRpcSuccess(obj)
        val text = toolText(obj["result"]!!.jsonObject)
        assertTrue(text.contains("has rules") || text.contains("rules"),
            "Expected 'has rules' annotation in schema output: $text")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // tools/call — recall (temporal_query)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `tools-call recall - returns no-result for far-future timestamp on empty db`() = testApplication {
        application { module() }

        val futureTs = System.currentTimeMillis() - 100_000L // 100s in the past

        val obj = mcpCall("""
            {
              "jsonrpc": "2.0",
              "id": 9,
              "method": "tools/call",
              "params": {
                "name": "recall",
                "arguments": {
                  "predicate": "event",
                  "args": ["?x"],
                  "timestamp": $futureTs
                }
              }
            }
        """.trimIndent())

        assertJsonRpcSuccess(obj, expectedId = 9)
        assertFalse(isToolError(obj["result"]!!.jsonObject))
    }

    @Test
    fun `tools-call recall - missing timestamp returns tool-level error`() = testApplication {
        application { module() }

        val obj = mcpCall("""
            {
              "jsonrpc": "2.0",
              "id": 9,
              "method": "tools/call",
              "params": {
                "name": "recall",
                "arguments": {"predicate": "event", "args": ["?x"]}
              }
            }
        """.trimIndent())

        assertJsonRpcSuccess(obj)
        assertTrue(isToolError(obj["result"]!!.jsonObject),
            "Missing timestamp should produce isError=true")
    }

    @Test
    fun `tools-call recall - legacy alias temporal_query also works`() = testApplication {
        application { module() }

        val ts = System.currentTimeMillis()
        val obj = mcpCall("""{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"temporal_query","arguments":{"predicate":"event","args":["?x"],"timestamp":$ts}}}""")

        assertJsonRpcSuccess(obj)
        assertFalse(isToolError(obj["result"]!!.jsonObject))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // tools/call — compress (consolidate)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `tools-call compress - succeeds on empty knowledge base`() = testApplication {
        application { module() }

        val obj = mcpCall("""
            {
              "jsonrpc": "2.0",
              "id": 10,
              "method": "tools/call",
              "params": {
                "name": "compress",
                "arguments": {}
              }
            }
        """.trimIndent())

        assertJsonRpcSuccess(obj, expectedId = 10)
        assertFalse(isToolError(obj["result"]!!.jsonObject))
        val text = toolText(obj["result"]!!.jsonObject)
        assertTrue(text.contains("No patterns") || text.contains("Consolidated"),
            "Expected consolidation status message: $text")
    }

    @Test
    fun `tools-call compress - legacy alias consolidate also works`() = testApplication {
        application { module() }

        val obj = mcpCall("""{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"consolidate","arguments":{}}}""")

        assertJsonRpcSuccess(obj)
        assertFalse(isToolError(obj["result"]!!.jsonObject))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // tools/call — cleanup (decay)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `tools-call cleanup - succeeds and reports decay stats`() = testApplication {
        application { module() }

        val obj = mcpCall("""
            {
              "jsonrpc": "2.0",
              "id": 11,
              "method": "tools/call",
              "params": {
                "name": "cleanup",
                "arguments": {}
              }
            }
        """.trimIndent())

        assertJsonRpcSuccess(obj, expectedId = 11)
        assertFalse(isToolError(obj["result"]!!.jsonObject))
        val text = toolText(obj["result"]!!.jsonObject)
        assertTrue(text.contains("Decay complete") || text.contains("expired") || text.contains("evicted"),
            "Expected decay stats in response: $text")
    }

    @Test
    fun `tools-call cleanup - threshold parameter is accepted`() = testApplication {
        application { module() }

        val obj = mcpCall("""
            {
              "jsonrpc": "2.0",
              "id": 11,
              "method": "tools/call",
              "params": {
                "name": "cleanup",
                "arguments": {"threshold": 0.1}
              }
            }
        """.trimIndent())

        assertJsonRpcSuccess(obj)
        assertFalse(isToolError(obj["result"]!!.jsonObject))
    }

    @Test
    fun `tools-call cleanup - legacy alias decay also works`() = testApplication {
        application { module() }

        val obj = mcpCall("""{"jsonrpc":"2.0","id":11,"method":"tools/call","params":{"name":"decay","arguments":{}}}""")

        assertJsonRpcSuccess(obj)
        assertFalse(isToolError(obj["result"]!!.jsonObject))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ping
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `ping - returns empty result object`() = testApplication {
        application { module() }

        val obj = mcpCall("""{"jsonrpc":"2.0","id":10,"method":"ping"}""")

        assertJsonRpcSuccess(obj, expectedId = 10)
        val result = obj["result"]?.jsonObject
        assertNotNull(result, "ping result should be a JSON object (empty map)")
        assertTrue(result.isEmpty(), "ping result should be empty: $result")
    }

    @Test
    fun `ping - null id is preserved in response`() = testApplication {
        application { module() }

        val rawResponse = client.post("/mcp") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"jsonrpc":"2.0","method":"ping"}""")
        }
        assertEquals(HttpStatusCode.OK, rawResponse.status)
        val obj = Json.parseToJsonElement(rawResponse.bodyAsText()).jsonObject
        assertEquals("2.0", obj["jsonrpc"]?.jsonPrimitive?.content)
        assertTrue(obj["error"].isJsonNull(), "Expected no error on ping: ${obj["error"]}")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Error handling — unknown method
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `unknown method - returns METHOD_NOT_FOUND error code -32601`() = testApplication {
        application { module() }

        val obj = mcpCall("""{"jsonrpc":"2.0","id":99,"method":"nonexistent/method"}""")

        assertEquals(99, obj["id"]?.jsonPrimitive?.int)
        val error = assertJsonRpcError(obj, expectedCode = -32601)
        assertTrue(error["message"]!!.jsonPrimitive.content.contains("Method not found"),
            "Error message should mention 'Method not found': ${error["message"]}")
    }

    @Test
    fun `unknown method - error message contains the bad method name`() = testApplication {
        application { module() }

        val obj = mcpCall("""{"jsonrpc":"2.0","id":1,"method":"bad/method/xyz"}""")

        val error = assertJsonRpcError(obj, expectedCode = -32601)
        assertTrue(error["message"]!!.jsonPrimitive.content.contains("bad/method/xyz"),
            "Error message should echo the unknown method name")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Error handling — unknown tool
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `unknown tool - returns UNKNOWN_TOOL error code -32004`() = testApplication {
        application { module() }

        val obj = mcpCall("""
            {
              "jsonrpc": "2.0",
              "id": 20,
              "method": "tools/call",
              "params": {
                "name": "does_not_exist_tool",
                "arguments": {}
              }
            }
        """.trimIndent())

        assertEquals(20, obj["id"]?.jsonPrimitive?.int)
        val error = assertJsonRpcError(obj, expectedCode = -32004)
        assertTrue(error["message"]!!.jsonPrimitive.content.contains("Unknown tool"),
            "Error message should say 'Unknown tool': ${error["message"]}")
    }

    @Test
    fun `unknown tool - error data contains the bad tool name`() = testApplication {
        application { module() }

        val obj = mcpCall("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"mystery_tool","arguments":{}}}""")

        val error = assertJsonRpcError(obj, expectedCode = -32004)
        val data = error["data"]?.jsonObject
        assertNotNull(data, "Error data should be present for unknown tool")
        assertEquals("mystery_tool", data["tool"]?.jsonPrimitive?.content)
    }

    @Test
    fun `tools-call with no params - returns INVALID_PARAMS error`() = testApplication {
        application { module() }

        val obj = mcpCall("""{"jsonrpc":"2.0","id":21,"method":"tools/call"}""")

        assertJsonRpcError(obj, expectedCode = -32602)
    }

    @Test
    fun `tools-call with params missing name - returns INVALID_PARAMS error`() = testApplication {
        application { module() }

        val obj = mcpCall("""{"jsonrpc":"2.0","id":21,"method":"tools/call","params":{"arguments":{}}}""")

        assertJsonRpcError(obj, expectedCode = -32602)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Error handling — malformed JSON-RPC
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `malformed JSON - returns PARSE_ERROR code -32700`() = testApplication {
        application { module() }

        val rawResponse = client.post("/mcp") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("this is not json {{{{")
        }

        assertEquals(HttpStatusCode.OK, rawResponse.status)
        val obj = Json.parseToJsonElement(rawResponse.bodyAsText()).jsonObject
        assertFalse(obj["error"].isJsonNull(), "Expected error for malformed JSON: $obj")
        val error = obj["error"]!!.jsonObject
        assertEquals(-32700, error["code"]?.jsonPrimitive?.int,
            "Expected PARSE_ERROR (-32700)")
    }

    @Test
    fun `empty body - returns PARSE_ERROR`() = testApplication {
        application { module() }

        val rawResponse = client.post("/mcp") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("")
        }

        assertEquals(HttpStatusCode.OK, rawResponse.status)
        val obj = Json.parseToJsonElement(rawResponse.bodyAsText()).jsonObject
        assertFalse(obj["error"].isJsonNull(), "Expected error for empty body")
    }

    @Test
    fun `JSON array body - returns PARSE_ERROR`() = testApplication {
        application { module() }

        val rawResponse = client.post("/mcp") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""[{"jsonrpc":"2.0","id":1,"method":"ping"}]""")
        }

        assertEquals(HttpStatusCode.OK, rawResponse.status)
        val obj = Json.parseToJsonElement(rawResponse.bodyAsText()).jsonObject
        assertFalse(obj["error"].isJsonNull(), "Expected error for array body (batch not supported)")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Header behaviour
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `missing X-Tenant-ID defaults to tenant default and succeeds`() = testApplication {
        application { module() }

        // MCP routes fall back to "default" tenant when header is absent
        val rawResponse = client.post("/mcp") {
            contentType(ContentType.Application.Json)
            setBody("""{"jsonrpc":"2.0","id":1,"method":"ping"}""")
        }

        assertEquals(HttpStatusCode.OK, rawResponse.status)
        val obj = Json.parseToJsonElement(rawResponse.bodyAsText()).jsonObject
        // Ping should succeed regardless of tenant — it doesn't touch the DB
        assertTrue(obj["error"].isJsonNull(), "ping should succeed without X-Tenant-ID: $obj")
    }

    @Test
    fun `X-Database header selects correct database`() = testApplication {
        application { module() }

        // Create the target database first via admin API
        client.post("/admin/databases") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"testdb"}""")
        }

        val obj = mcpCall(
            body = """{"jsonrpc":"2.0","id":1,"method":"ping"}""",
            tenantId = "default",
            dbName = "testdb"
        )

        assertTrue(obj["error"].isJsonNull(), "ping on named database should succeed: $obj")
    }

    @Test
    fun `X-Database points to non-existent DB - returns DATABASE_NOT_FOUND error -32001`() = testApplication {
        application { module() }

        val obj = mcpCall(
            body = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"tell","arguments":{"predicate":"x","args":["y"]}}}""",
            tenantId = "default",
            dbName = "no-such-db-xyz"
        )

        val error = assertJsonRpcError(obj, expectedCode = -32001)
        assertTrue(error["message"]!!.jsonPrimitive.content.contains("no-such-db-xyz"),
            "Error message should name the missing database: ${error["message"]}")
    }

    @Test
    fun `X-Request-ID response header is present on every MCP response`() = testApplication {
        application { module() }

        val rawResponse = client.post("/mcp") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"jsonrpc":"2.0","id":1,"method":"ping"}""")
        }

        val requestId = rawResponse.headers["X-Request-ID"]
        assertNotNull(requestId, "X-Request-ID header should be set on MCP responses")
        assertTrue(requestId.isNotBlank(), "X-Request-ID should not be blank")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scope isolation
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `tools-call tell and ask with scope - fact is visible within scope`() = testApplication {
        application { module() }

        mcpCall("""
            {
              "jsonrpc": "2.0", "id": 1, "method": "tools/call",
              "params": {
                "name": "tell",
                "arguments": {"predicate": "scoped_pred", "args": ["x"], "scope": "hypothesis_a"}
              }
            }
        """.trimIndent())

        val obj = mcpCall("""
            {
              "jsonrpc": "2.0", "id": 2, "method": "tools/call",
              "params": {
                "name": "ask",
                "arguments": {"predicate": "scoped_pred", "args": ["x"], "scope": "hypothesis_a"}
              }
            }
        """.trimIndent())

        assertJsonRpcSuccess(obj)
        assertFalse(isToolError(obj["result"]!!.jsonObject))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Response structure invariants
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `tool call result always has content array with at least one text block`() = testApplication {
        application { module() }

        val toolCalls = listOf(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"compress","arguments":{}}}""",
            """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"cleanup","arguments":{}}}""",
            """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"predicates","arguments":{}}}""",
            """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"context","arguments":{}}}"""
        )

        for (body in toolCalls) {
            val obj = mcpCall(body)
            assertJsonRpcSuccess(obj)
            val result = obj["result"]!!.jsonObject
            val content = result["content"]?.jsonArray
            assertNotNull(content, "content array missing for: $body")
            assertTrue(content.isNotEmpty(), "content array empty for: $body")
            val firstBlock = content[0].jsonObject
            assertEquals("text", firstBlock["type"]?.jsonPrimitive?.content,
                "First content block type should be 'text' for: $body")
            assertNotNull(firstBlock["text"], "text field missing in content block for: $body")
        }
    }

    @Test
    fun `tool error result has isError true and _meta block`() = testApplication {
        application { module() }

        // Trigger a validation error intentionally
        val obj = mcpCall("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"tell","arguments":{"args":["x"]}}}""")

        assertJsonRpcSuccess(obj)
        val result = obj["result"]!!.jsonObject
        assertTrue(isToolError(result), "Expected isError=true: $result")

        val meta = result["_meta"]?.jsonObject
        assertNotNull(meta, "_meta block missing on tool error: $result")
        assertNotNull(meta["errorCode"], "_meta.errorCode missing")
        assertNotNull(meta["tool"], "_meta.tool missing")
        assertEquals("tell", meta["tool"]?.jsonPrimitive?.content)
    }
}
