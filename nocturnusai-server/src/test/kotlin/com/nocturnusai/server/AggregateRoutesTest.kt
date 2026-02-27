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
}
