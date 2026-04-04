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
 * Integration tests for Admin API routes.
 *
 * Covers:
 *   GET    /admin/databases               — list databases
 *   POST   /admin/databases               — create database
 *   DELETE /admin/databases/{name}        — delete database
 *   GET    /admin/databases/{name}/facts  — list facts in database
 *   GET    /admin/databases/{name}/rules  — list rules in database
 *   GET    /admin/databases/{name}/tenants — list tenants
 *   POST   /admin/databases/{name}/tenants — create tenant
 */
class AdminRoutesTest {

    // ─────────────────────────────────────────────────────────────────────────
    // GET /admin/databases
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `GET admin-databases - lists at least the default database`() = testApplication {
        application { module() }

        val response = client.get("/admin/databases")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("default"), "Expected default database in list: $body")
    }

    @Test
    fun `GET admin-databases - returns JSON array`() = testApplication {
        application { module() }

        val response = client.get("/admin/databases")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.startsWith("["), "Expected JSON array: $body")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /admin/databases
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `POST admin-databases - create new database returns 200`() = testApplication {
        application { module() }

        val response = client.post("/admin/databases") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"testdb-create"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("testdb-create"), "Expected db name in response: $body")
    }

    @Test
    fun `POST admin-databases - newly created database appears in list`() = testApplication {
        application { module() }

        client.post("/admin/databases") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"testdb-list"}""")
        }

        val listResponse = client.get("/admin/databases")
        assertEquals(HttpStatusCode.OK, listResponse.status)
        val body = listResponse.bodyAsText()
        assertTrue(body.contains("testdb-list"), "Expected new db in list: $body")
    }

    @Test
    fun `POST admin-databases - create with invalid name returns 400`() = testApplication {
        application { module() }

        // Slash in name violates the safe-name regex
        val response = client.post("/admin/databases") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"bad/name"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("VALIDATION_ERROR"), "Expected VALIDATION_ERROR: $body")
    }

    @Test
    fun `POST admin-databases - blank name returns 400`() = testApplication {
        application { module() }

        val response = client.post("/admin/databases") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":""}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST admin-databases - creating existing database is idempotent`() = testApplication {
        application { module() }

        // Create it twice — second call should return 200 (idempotent per DatabaseManager logic)
        repeat(2) {
            val response = client.post("/admin/databases") {
                contentType(ContentType.Application.Json)
                setBody("""{"name":"idempotent-db"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DELETE /admin/databases/{name}
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `DELETE admin-databases - deletes a non-default database`() = testApplication {
        application { module() }

        // Create then delete
        client.post("/admin/databases") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"deletable-db"}""")
        }

        val response = client.delete("/admin/databases/deletable-db")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("deleted"), "Expected deleted confirmation: $body")
    }

    @Test
    fun `DELETE admin-databases - deleting default database returns 400`() = testApplication {
        application { module() }

        val response = client.delete("/admin/databases/default")

        // DatabaseManager.deleteDatabase() throws IllegalArgumentException for "default"
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("BAD_REQUEST") || body.contains("default"),
            "Expected error for default db: $body")
    }

    @Test
    fun `DELETE admin-databases - deleted database disappears from list`() = testApplication {
        application { module() }

        client.post("/admin/databases") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"to-delete"}""")
        }

        client.delete("/admin/databases/to-delete")

        val listResponse = client.get("/admin/databases")
        val body = listResponse.bodyAsText()
        assertFalse(body.contains("\"to-delete\""), "Deleted db should not appear in list: $body")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /admin/databases/{name}/facts
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `GET admin-databases-facts - returns empty array when no facts stored`() = testApplication {
        application { module() }

        val response = client.get("/admin/databases/default/facts") {
            header("X-Tenant-ID", "default")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.startsWith("["), "Expected JSON array: $body")
    }

    @Test
    fun `GET admin-databases-facts - returns stored facts for that database`() = testApplication {
        application { module() }

        // Store a fact in the default database via /tell
        client.post("/tell") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"admin_test","args":["value1"]}""")
        }

        val response = client.get("/admin/databases/default/facts") {
            header("X-Tenant-ID", "default")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("admin_test"), "Expected stored predicate in admin facts: $body")
    }

    @Test
    fun `GET admin-databases-facts - unknown database returns 404`() = testApplication {
        application { module() }

        val response = client.get("/admin/databases/nonexistent-db/facts") {
            header("X-Tenant-ID", "default")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("NOT_FOUND"), "Expected NOT_FOUND: $body")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /admin/databases/{name}/rules
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `GET admin-databases-rules - returns empty array when no rules stored`() = testApplication {
        application { module() }

        val response = client.get("/admin/databases/default/rules") {
            header("X-Tenant-ID", "default")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.startsWith("["), "Expected JSON array: $body")
    }

    @Test
    fun `GET admin-databases-rules - returns stored rules as strings`() = testApplication {
        application { module() }

        // Store a rule
        client.post("/teach") {
            header("X-Tenant-ID", "default")
            contentType(ContentType.Application.Json)
            setBody("""
            {
              "head": {"predicate":"grandparent","args":["?x","?z"]},
              "body": [
                {"predicate":"parent","args":["?x","?y"]},
                {"predicate":"parent","args":["?y","?z"]}
              ]
            }
            """.trimIndent())
        }

        val response = client.get("/admin/databases/default/rules") {
            header("X-Tenant-ID", "default")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("grandparent") || body.contains("parent"),
            "Expected rule predicates in response: $body")
    }

    @Test
    fun `GET admin-databases-rules - unknown database returns 404`() = testApplication {
        application { module() }

        val response = client.get("/admin/databases/no-such-db/rules")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("NOT_FOUND"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /admin/databases/{name}/tenants  +  GET tenants
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `POST admin-databases-tenants - create new tenant returns 200`() = testApplication {
        application { module() }

        val response = client.post("/admin/databases/default/tenants") {
            contentType(ContentType.Application.Json)
            setBody("""{"tenantId":"tenant-alpha"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("tenant-alpha"), "Expected tenant name in response: $body")
    }

    @Test
    fun `GET admin-databases-tenants - lists registered tenants including default`() = testApplication {
        application { module() }

        val response = client.get("/admin/databases/default/tenants")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("default"), "Expected 'default' tenant in list: $body")
    }

    @Test
    fun `POST admin-databases-tenants - invalid tenant ID returns 400`() = testApplication {
        application { module() }

        // Tenant ID with slash violates safe-name regex
        val response = client.post("/admin/databases/default/tenants") {
            contentType(ContentType.Application.Json)
            setBody("""{"tenantId":"bad/tenant"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("VALIDATION_ERROR"))
    }

    @Test
    fun `POST admin-databases-tenants - unknown database returns 404`() = testApplication {
        application { module() }

        val response = client.post("/admin/databases/no-such-db/tenants") {
            contentType(ContentType.Application.Json)
            setBody("""{"tenantId":"t1"}""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
