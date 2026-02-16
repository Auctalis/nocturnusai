package com.axiombase.cli

import io.ktor.client.*
import io.ktor.client.engine.cio.*
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
    var tenantId: String? = null,
) {
    val server: String get() = serverUrl

    private val http = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; prettyPrint = true })
        }
    }

    private suspend fun post(path: String, body: String): String {
        val resp = http.post("$serverUrl$path") {
            contentType(ContentType.Application.Json)
            header("X-Database", database)
            tenantId?.let { header("X-Tenant-ID", it) }
            apiKey?.let { header("X-API-Key", it) }
            setBody(body)
        }
        return resp.bodyAsText()
    }

    private suspend fun get(path: String): String {
        val resp = http.get("$serverUrl$path") {
            header("X-Database", database)
            tenantId?.let { header("X-Tenant-ID", it) }
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

    fun close() = http.close()
}
