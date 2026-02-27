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

package com.nocturnusai.cli

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

class Client(
    private val serverUrl: String,
    var database: String,
    private val apiKey: String?,
    var tenantId: String = "default",
) {
    val server: String get() = serverUrl
    val hasApiKey: Boolean get() = apiKey != null

    private val http = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; prettyPrint = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 300_000   // 5 min — Ollama model loading can be slow
            connectTimeoutMillis = 15_000    // 15s connect
            socketTimeoutMillis = 300_000    // 5 min socket idle
        }
    }

    private suspend fun post(path: String, body: String): String {
        val resp = http.post("$serverUrl$path") {
            contentType(ContentType.Application.Json)
            header("X-Database", database)
            header("X-Tenant-ID", tenantId)
            apiKey?.let { header("X-API-Key", it) }
            setBody(body)
        }
        return resp.bodyAsText()
    }

    private suspend fun get(path: String): String {
        val resp = http.get("$serverUrl$path") {
            header("X-Database", database)
            header("X-Tenant-ID", tenantId)
            apiKey?.let { header("X-API-Key", it) }
        }
        return resp.bodyAsText()
    }

    suspend fun ask(body: String) = post("/ask", body)
    suspend fun tell(body: String) = post("/tell", body)
    suspend fun teach(body: String) = post("/teach", body)
    suspend fun forget(body: String) = post("/forget", body)
    suspend fun execute(command: String) = post("/execute", "\"$command\"")
    suspend fun listFacts() = get("/admin/databases/$database/facts")
    suspend fun listRules() = get("/admin/databases/$database/rules")
    suspend fun listDatabases() = get("/admin/databases")
    suspend fun contextWindow(maxFacts: Int) = post("/memory/context", """{"maxFacts":$maxFacts}""")
    suspend fun compress() = post("/memory/compress", "{}")
    suspend fun cleanup(threshold: Double) = post("/memory/cleanup", """{"threshold":$threshold}""")
    suspend fun health() = get("/health")

    /** POST /extract — send plain text, LLM extracts facts & rules */
    suspend fun extract(text: String, assert: Boolean = true, rules: Boolean = true, context: String? = null): String {
        val ctxField = if (context != null) ""","context":"${context.replace("\"", "\\\"")}"""" else ""
        return post("/extract", """{"text":"${text.replace("\"", "\\\"")}","assert":$assert,"rules":$rules$ctxField}""")
    }

    /** POST /synthesize — natural language question → LLM-powered answer */
    suspend fun synthesize(question: String): String {
        return post("/synthesize", """{"question":"${question.replace("\"", "\\\"")}"}""")
    }

    // ── Auth endpoints ────────────────────────────────────────────────────

    /** GET /auth/status — check auth mode */
    suspend fun authStatus() = get("/auth/status")

    /** POST /auth/bootstrap — create first admin key */
    suspend fun authBootstrap(username: String, password: String, keyName: String? = null): String {
        val nameField = if (keyName != null) ""","keyName":"${keyName.replace("\"", "\\\"")}"""" else ""
        return post("/auth/bootstrap", """{"username":"${username.replace("\"", "\\\"")}","password":"${password.replace("\"", "\\\"")}\"$nameField}""")
    }

    /** POST /auth/keys — create a new API key */
    suspend fun authCreateKey(name: String, role: String, databases: List<String> = emptyList(), tenants: List<String> = emptyList()): String {
        val dbsJson = databases.joinToString(",") { "\"$it\"" }
        val tenantsJson = tenants.joinToString(",") { "\"$it\"" }
        return post("/auth/keys", """{"name":"${name.replace("\"", "\\\"")}","role":"$role","databases":[$dbsJson],"tenants":[$tenantsJson]}""")
    }

    /** GET /auth/keys — list all keys */
    suspend fun authListKeys() = get("/auth/keys")

    /** DELETE /auth/keys/{id} — revoke a key */
    suspend fun authRevokeKey(id: String): String {
        val resp = http.delete("$serverUrl/auth/keys/$id") {
            header("X-Database", database)
            apiKey?.let { header("X-API-Key", it) }
        }
        return resp.bodyAsText()
    }

    /** GET /auth/whoami — show current identity */
    suspend fun authWhoAmI() = get("/auth/whoami")

    fun close() = http.close()
}
