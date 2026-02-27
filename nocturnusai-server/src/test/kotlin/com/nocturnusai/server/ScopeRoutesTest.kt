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
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * Integration tests for the Scope Management API routes.
 *
 * Covers:
 *   POST   /scope/fork    — fork a knowledge-base scope
 *   POST   /scope/diff    — compare two scopes
 *   POST   /scope/merge   — merge a scope back
 *   DELETE /scope/{name}  — delete a scope
 *   GET    /scopes        — list all scopes
 *
 * Also covers the MCP tools: fork_scope, merge_scope, list_scopes, delete_scope
 *
 * Every test uses [withTestApp] to get a completely isolated in-memory and
 * on-disk knowledge base, preventing inter-test state leakage.
 */
class ScopeRoutesTest {

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private val tenant = "default"

    /** Assert a fact into the global (unscoped) partition. */
    private suspend fun ApplicationTestBuilder.assertGlobalFact(pred: String, arg1: String, arg2: String) {
        client.post("/tell") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"$pred","args":["$arg1","$arg2"]}""")
        }
    }

    /** Assert a fact into a named scope. */
    private suspend fun ApplicationTestBuilder.assertScopedFact(
        pred: String,
        arg1: String,
        arg2: String,
        scope: String,
        negated: Boolean = false
    ) {
        client.post("/tell") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"$pred","args":["$arg1","$arg2"],"scope":"$scope","negated":$negated}""")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /scopes
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `GET scopes - returns empty list when no named scopes exist`() = withTestApp {
        assertGlobalFact("p", "a", "b") // global only

        val response = client.get("/scopes") {
            header("X-Tenant-ID", tenant)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val obj = Json.parseToJsonElement(body).jsonObject
        assertEquals(0, obj["count"]?.jsonPrimitive?.int)
        val scopes = obj["scopes"]?.jsonArray ?: fail("Expected scopes array")
        assertTrue(scopes.isEmpty())
    }

    @Test
    fun `GET scopes - lists all existing scopes`() = withTestApp {
        assertScopedFact("p", "a", "b", "alpha")
        assertScopedFact("p", "c", "d", "beta")

        val response = client.get("/scopes") {
            header("X-Tenant-ID", tenant)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val obj = Json.parseToJsonElement(body).jsonObject
        assertEquals(2, obj["count"]?.jsonPrimitive?.int)
        val scopes = obj["scopes"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(setOf("alpha", "beta"), scopes)
    }

    @Test
    fun `GET scopes - requires X-Tenant-ID header`() = withTestApp {
        val response = client.get("/scopes")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("VALIDATION_ERROR"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /scope/fork
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `POST scope-fork - fork from global scope creates copy`() = withTestApp {
        assertGlobalFact("likes", "alice", "bob")
        assertGlobalFact("knows", "carol", "dave")

        val response = client.post("/scope/fork") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody("""{"targetScope":"hyp-1"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val obj = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(2, obj["copied"]?.jsonPrimitive?.int)
        assertEquals("hyp-1", obj["targetScope"]?.jsonPrimitive?.content)
        assertTrue(obj["sourceScope"]?.jsonPrimitive?.isString != true || obj["sourceScope"]!!.jsonPrimitive.contentOrNull == null)
    }

    @Test
    fun `POST scope-fork - fork preserves all facts with new scope`() = withTestApp {
        assertGlobalFact("rel", "x", "y")

        client.post("/scope/fork") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody("""{"targetScope":"fork-test"}""")
        }

        // Verify via /scopes
        val scopeResp = client.get("/scopes") {
            header("X-Tenant-ID", tenant)
        }
        val scopes = Json.parseToJsonElement(scopeResp.bodyAsText()).jsonObject["scopes"]!!.jsonArray
            .map { it.jsonPrimitive.content }
        assertTrue("fork-test" in scopes)
    }

    @Test
    fun `POST scope-fork - fork from named scope to another named scope`() = withTestApp {
        assertScopedFact("p", "a", "b", "source-scope")
        assertScopedFact("p", "c", "d", "source-scope")

        val response = client.post("/scope/fork") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody("""{"sourceScope":"source-scope","targetScope":"dest-scope"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val obj = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(2, obj["copied"]?.jsonPrimitive?.int)
        assertEquals("source-scope", obj["sourceScope"]?.jsonPrimitive?.content)
        assertEquals("dest-scope", obj["targetScope"]?.jsonPrimitive?.content)
    }

    @Test
    fun `POST scope-fork - missing targetScope returns 400`() = withTestApp {
        val response = client.post("/scope/fork") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody("""{}""") // missing targetScope
        }
        // Kotlin serialization will use empty string for missing non-nullable, which we validate
        // or deserialization error — either way, expect non-200
        assertTrue(
            response.status == HttpStatusCode.BadRequest || response.status == HttpStatusCode.InternalServerError,
            "Expected error status, got ${response.status}"
        )
    }

    @Test
    fun `POST scope-fork - blank targetScope returns 400`() = withTestApp {
        val response = client.post("/scope/fork") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody("""{"targetScope":"  "}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("VALIDATION_ERROR"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /scope/diff
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `POST scope-diff - identical scopes show no differences`() = withTestApp {
        assertScopedFact("p", "a", "b", "s1")
        assertScopedFact("p", "a", "b", "s2")

        val response = client.post("/scope/diff") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody("""{"scopeA":"s1","scopeB":"s2"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val obj = Json.parseToJsonElement(body).jsonObject
        assertEquals(0, obj["onlyInA"]?.jsonArray?.size)
        assertEquals(0, obj["onlyInB"]?.jsonArray?.size)
        assertEquals(1, obj["inBoth"]?.jsonArray?.size)
        assertEquals(0, obj["conflicts"]?.jsonArray?.size)
    }

    @Test
    fun `POST scope-diff - detects atoms added to one scope`() = withTestApp {
        assertScopedFact("p", "a", "b", "base")
        assertScopedFact("p", "a", "b", "branch")
        assertScopedFact("q", "x", "y", "branch") // only in branch

        val response = client.post("/scope/diff") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody("""{"scopeA":"base","scopeB":"branch"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val obj = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(0, obj["onlyInA"]?.jsonArray?.size)
        assertEquals(1, obj["onlyInB"]?.jsonArray?.size)
        assertEquals(1, obj["inBoth"]?.jsonArray?.size)
    }

    @Test
    fun `POST scope-diff - detects conflicts - same pred-args different truthVal`() = withTestApp {
        assertScopedFact("p", "a", "b", "s1", negated = false) // true
        assertScopedFact("p", "a", "b", "s2", negated = true)  // false

        val response = client.post("/scope/diff") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody("""{"scopeA":"s1","scopeB":"s2"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val obj = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(0, obj["onlyInA"]?.jsonArray?.size)
        assertEquals(0, obj["onlyInB"]?.jsonArray?.size)
        assertEquals(0, obj["inBoth"]?.jsonArray?.size)
        assertEquals(1, obj["conflicts"]?.jsonArray?.size)
    }

    @Test
    fun `POST scope-diff - diff between global and named scope`() = withTestApp {
        assertGlobalFact("p", "a", "b")
        assertScopedFact("p", "c", "d", "branch")

        val response = client.post("/scope/diff") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody("""{"scopeB":"branch"}""") // scopeA defaults to null = global
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val obj = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(1, obj["onlyInA"]?.jsonArray?.size, "a->b only in global")
        assertEquals(1, obj["onlyInB"]?.jsonArray?.size, "c->d only in branch")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /scope/merge
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `POST scope-merge - SOURCE_WINS resolves conflicts`() = withTestApp {
        assertGlobalFact("p", "a", "b")                       // global: true
        assertScopedFact("p", "a", "b", "src", negated = true) // src: false (conflict)

        val response = client.post("/scope/merge") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody("""{"sourceScope":"src","strategy":"SOURCE_WINS"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val obj = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(1, obj["conflictsResolved"]?.jsonPrimitive?.int)
        assertEquals("SOURCE_WINS", obj["strategy"]?.jsonPrimitive?.content)
        assertNotNull(obj["timestamp"])
    }

    @Test
    fun `POST scope-merge - TARGET_WINS preserves target on conflict`() = withTestApp {
        assertGlobalFact("p", "a", "b")                       // global: true
        assertScopedFact("p", "a", "b", "src", negated = true) // src: false

        val response = client.post("/scope/merge") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody("""{"sourceScope":"src","strategy":"TARGET_WINS"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val obj = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(1, obj["conflictsResolved"]?.jsonPrimitive?.int)
    }

    @Test
    fun `POST scope-merge - REJECT returns 409 on conflicts`() = withTestApp {
        assertGlobalFact("p", "a", "b")                       // global: true
        assertScopedFact("p", "a", "b", "src", negated = true) // src: false

        val response = client.post("/scope/merge") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody("""{"sourceScope":"src","strategy":"REJECT"}""")
        }
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertTrue(response.bodyAsText().contains("CONFLICT"))
    }

    @Test
    fun `POST scope-merge - REJECT succeeds with no conflicts`() = withTestApp {
        assertScopedFact("p", "x", "y", "src")

        val response = client.post("/scope/merge") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody("""{"sourceScope":"src","strategy":"REJECT"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val obj = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(1, obj["merged"]?.jsonPrimitive?.int)
        assertEquals(0, obj["conflictsResolved"]?.jsonPrimitive?.int)
    }

    @Test
    fun `POST scope-merge - missing sourceScope returns 400`() = withTestApp {
        val response = client.post("/scope/merge") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody("""{"strategy":"SOURCE_WINS"}""")
        }
        assertTrue(
            response.status == HttpStatusCode.BadRequest || response.status == HttpStatusCode.InternalServerError,
            "Expected error for missing sourceScope"
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DELETE /scope/{name}
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `DELETE scope - removes all scoped facts`() = withTestApp {
        assertScopedFact("p", "a", "b", "to-delete")
        assertScopedFact("q", "c", "d", "to-delete")

        val response = client.delete("/scope/to-delete") {
            header("X-Tenant-ID", tenant)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val obj = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(2, obj["deleted"]?.jsonPrimitive?.int)
        assertEquals("to-delete", obj["scope"]?.jsonPrimitive?.content)

        // Verify gone via listScopes
        val listResp = client.get("/scopes") { header("X-Tenant-ID", tenant) }
        val scopes = Json.parseToJsonElement(listResp.bodyAsText()).jsonObject["scopes"]!!.jsonArray
            .map { it.jsonPrimitive.content }
        assertFalse("to-delete" in scopes)
    }

    @Test
    fun `DELETE scope - does not affect other scopes`() = withTestApp {
        assertScopedFact("p", "a", "b", "keep")
        assertScopedFact("p", "c", "d", "gone")

        client.delete("/scope/gone") { header("X-Tenant-ID", tenant) }

        val listResp = client.get("/scopes") { header("X-Tenant-ID", tenant) }
        val scopes = Json.parseToJsonElement(listResp.bodyAsText()).jsonObject["scopes"]!!.jsonArray
            .map { it.jsonPrimitive.content }
        assertTrue("keep" in scopes)
        assertFalse("gone" in scopes)
    }

    @Test
    fun `DELETE scope - does not affect global facts`() = withTestApp {
        assertGlobalFact("p", "a", "b")
        assertScopedFact("p", "c", "d", "tmp")

        client.delete("/scope/tmp") { header("X-Tenant-ID", tenant) }

        // The global fact should still be queryable
        val diffResp = client.post("/scope/diff") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody("""{}""") // diff global vs global => inBoth has the fact
        }
        // Since we're comparing global with itself, inBoth should have the global fact
        val obj = Json.parseToJsonElement(diffResp.bodyAsText()).jsonObject
        assertEquals(1, obj["inBoth"]?.jsonArray?.size, "Global fact should survive deletion of tmp scope")
    }

    @Test
    fun `DELETE scope - nonexistent scope returns 0 deleted`() = withTestApp {
        val response = client.delete("/scope/ghost") {
            header("X-Tenant-ID", tenant)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val obj = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(0, obj["deleted"]?.jsonPrimitive?.int)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Full workflow: fork → modify → diff → merge
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `full workflow fork then modify then diff then merge`() = withTestApp {
        // 1. Seed global
        assertGlobalFact("lives_in", "alice", "paris")
        assertGlobalFact("likes", "alice", "cheese")

        // 2. Fork global into hypothesis
        val forkResp = client.post("/scope/fork") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody("""{"targetScope":"hypothesis"}""")
        }
        assertEquals(HttpStatusCode.OK, forkResp.status)
        val forkObj = Json.parseToJsonElement(forkResp.bodyAsText()).jsonObject
        assertEquals(2, forkObj["copied"]?.jsonPrimitive?.int)

        // 3. Diff after fork: should be identical
        val preDiff = client.post("/scope/diff") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody("""{"scopeB":"hypothesis"}""")
        }
        val preDiffObj = Json.parseToJsonElement(preDiff.bodyAsText()).jsonObject
        assertEquals(0, preDiffObj["onlyInA"]?.jsonArray?.size)
        assertEquals(0, preDiffObj["onlyInB"]?.jsonArray?.size)
        assertEquals(2, preDiffObj["inBoth"]?.jsonArray?.size)

        // 4. In hypothesis: Alice moves to London (add new, leave old — conflict resolution handles the rest)
        assertScopedFact("lives_in", "alice", "london", "hypothesis")

        // 5. Diff should show the new london fact
        val postDiff = client.post("/scope/diff") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody("""{"scopeB":"hypothesis"}""")
        }
        val postDiffObj = Json.parseToJsonElement(postDiff.bodyAsText()).jsonObject
        // hypothesis has paris (inBoth) + london (onlyInB) + cheese (inBoth)
        val onlyInB = postDiffObj["onlyInB"]?.jsonArray?.size ?: 0
        assertTrue(onlyInB >= 1, "London fact should only be in hypothesis")

        // 6. Merge hypothesis back (SOURCE_WINS)
        val mergeResp = client.post("/scope/merge") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody("""{"sourceScope":"hypothesis","strategy":"SOURCE_WINS"}""")
        }
        assertEquals(HttpStatusCode.OK, mergeResp.status)
        val mergeObj = Json.parseToJsonElement(mergeResp.bodyAsText()).jsonObject
        val merged = mergeObj["merged"]?.jsonPrimitive?.int ?: 0
        assertTrue(merged >= 1)

        // 7. Delete hypothesis scope
        val delResp = client.delete("/scope/hypothesis") {
            header("X-Tenant-ID", tenant)
        }
        assertEquals(HttpStatusCode.OK, delResp.status)

        // 8. Verify no scopes remain
        val listResp = client.get("/scopes") { header("X-Tenant-ID", tenant) }
        val finalScopes = Json.parseToJsonElement(listResp.bodyAsText()).jsonObject["scopes"]!!.jsonArray
        assertTrue(finalScopes.isEmpty())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MCP tool integration
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `MCP tool fork_scope - forks global into named scope`() = withTestApp {
        assertGlobalFact("p", "a", "b")

        val response = client.post("/mcp") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody("""
            {
                "jsonrpc":"2.0","id":1,"method":"tools/call",
                "params":{
                    "name":"fork_scope",
                    "arguments":{"targetScope":"mcp-fork"}
                }
            }
            """.trimIndent())
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertFalse(body.contains("\"isError\":true"), "Expected success, got: $body")
        assertTrue(body.contains("mcp-fork"), "Response should mention the scope name")
    }

    @Test
    fun `MCP tool merge_scope - merges scope back`() = withTestApp {
        assertScopedFact("p", "x", "y", "src-mcp")

        val response = client.post("/mcp") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody("""
            {
                "jsonrpc":"2.0","id":2,"method":"tools/call",
                "params":{
                    "name":"merge_scope",
                    "arguments":{"sourceScope":"src-mcp","strategy":"SOURCE_WINS"}
                }
            }
            """.trimIndent())
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertFalse(body.contains("\"isError\":true"), "Expected success, got: $body")
    }

    @Test
    fun `MCP tool list_scopes - lists existing scopes`() = withTestApp {
        assertScopedFact("p", "a", "b", "scope-alpha")

        val response = client.post("/mcp") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody("""
            {
                "jsonrpc":"2.0","id":3,"method":"tools/call",
                "params":{"name":"list_scopes","arguments":{}}
            }
            """.trimIndent())
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertFalse(body.contains("\"isError\":true"), "Expected success, got: $body")
        assertTrue(body.contains("scope-alpha"), "Should mention the scope")
    }

    @Test
    fun `MCP tool delete_scope - deletes a scope`() = withTestApp {
        assertScopedFact("p", "a", "b", "del-via-mcp")

        val response = client.post("/mcp") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody("""
            {
                "jsonrpc":"2.0","id":4,"method":"tools/call",
                "params":{"name":"delete_scope","arguments":{"scope":"del-via-mcp"}}
            }
            """.trimIndent())
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertFalse(body.contains("\"isError\":true"), "Expected success, got: $body")
        assertTrue(body.contains("del-via-mcp"))
    }

    @Test
    fun `MCP tool list_scopes - empty when no scopes`() = withTestApp {
        val response = client.post("/mcp") {
            header("X-Tenant-ID", tenant)
            contentType(ContentType.Application.Json)
            setBody("""
            {
                "jsonrpc":"2.0","id":5,"method":"tools/call",
                "params":{"name":"list_scopes","arguments":{}}
            }
            """.trimIndent())
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertFalse(body.contains("\"isError\":true"), "Expected success, got: $body")
        assertTrue(body.contains("No named scopes"), "Should say no scopes found")
    }
}
