package com.axiombase.server.routes

import com.axiombase.server.DatabaseManager
import com.axiombase.server.HealthChecker
import com.axiombase.server.LlmTxtGenerator
import io.micrometer.prometheus.PrometheusMeterRegistry
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File

fun Route.observabilityRoutes(appMicrometerRegistry: PrometheusMeterRegistry, dbManager: DatabaseManager, storageDir: File) {
    get("/metrics") {
         call.application.environment.log.info("Endpoint /metrics hit")
         call.respondText(appMicrometerRegistry.scrape())
    }

    get("/health") {
        call.application.environment.log.debug("Endpoint /health hit")
        val healthStatus = HealthChecker.check(dbManager, storageDir)
        val statusCode = if (healthStatus.status == "unhealthy") HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK
        call.respond(statusCode, healthStatus)
    }

    get("/health/live") {
        call.respondText("OK")
    }

    get("/health/ready") {
        val healthStatus = HealthChecker.check(dbManager, storageDir)
        val statusCode = if (healthStatus.status == "unhealthy") HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK
        call.respond(statusCode, healthStatus)
    }

    get("/llm.txt") {
        call.application.environment.log.info("Endpoint /llm.txt hit")
        call.respondText(LlmTxtGenerator.generate(application), contentType = ContentType.Text.Plain)
    }

    get("/userguide") {
        call.application.environment.log.info("Endpoint /userguide hit")
        val content = Thread.currentThread().contextClassLoader.getResourceAsStream("USERGUIDE.md")
            ?.bufferedReader()?.readText()
        if (content != null) {
            call.respondText(content, contentType = ContentType.Text.Plain)
        } else {
            call.respondText("USERGUIDE.md not found", status = HttpStatusCode.NotFound)
        }
    }

    // A2A Agent Card — Agent2Agent Protocol discovery endpoint
    get("/.well-known/agent.json") {
        call.application.environment.log.info("Endpoint /.well-known/agent.json hit")
        val host = call.request.host()
        val port = call.request.port()
        val scheme = if (com.axiombase.server.ServerConfig.tlsEnabled) "https" else "http"
        val baseUrl = "$scheme://$host:$port"

        val agentCard = mapOf(
            "name" to "AxiomBase",
            "description" to "Logic-based inference engine and agent context server. Provides deterministic multi-step reasoning, truth maintenance, temporal knowledge management, salience-ranked retrieval, and memory lifecycle management for AI agents.",
            "url" to baseUrl,
            "version" to "1.0.0",
            "documentationUrl" to "$baseUrl/userguide",
            "provider" to mapOf(
                "organization" to "AxiomBase"
            ),
            "capabilities" to mapOf(
                "streaming" to true,
                "pushNotifications" to true,
                "stateTransitionHistory" to false
            ),
            "authentication" to mapOf(
                "schemes" to listOf("apiKey")
            ),
            "defaultInputModes" to listOf("application/json"),
            "defaultOutputModes" to listOf("application/json", "text/event-stream"),
            "skills" to listOf(
                mapOf(
                    "id" to "assert_fact",
                    "name" to "Assert Fact",
                    "description" to "Assert a fact into the knowledge base with optional temporal bounds (validFrom, validUntil, ttl).",
                    "tags" to listOf("knowledge", "facts", "memory"),
                    "examples" to listOf("Assert that Alice is Bob's parent", "Store user preference with 1-hour TTL")
                ),
                mapOf(
                    "id" to "assert_rule",
                    "name" to "Assert Rule",
                    "description" to "Assert a logical rule (Horn clause) for multi-step deductive inference.",
                    "tags" to listOf("logic", "rules", "inference"),
                    "examples" to listOf("If X is parent of Y and Y is parent of Z, then X is grandparent of Z")
                ),
                mapOf(
                    "id" to "infer",
                    "name" to "Logical Inference",
                    "description" to "Run backward-chaining SLD resolution to derive conclusions from facts and rules. Returns provable results with optional proof trees.",
                    "tags" to listOf("reasoning", "inference", "logic"),
                    "examples" to listOf("Who are Alice's grandchildren?", "Is Bob authorized to access this resource?")
                ),
                mapOf(
                    "id" to "context_window",
                    "name" to "Context Window",
                    "description" to "Get salience-ranked facts for optimal agent context. Returns the most relevant knowledge based on recency, access frequency, and priority.",
                    "tags" to listOf("memory", "context", "salience"),
                    "examples" to listOf("Get the 50 most relevant facts for the current conversation")
                ),
                mapOf(
                    "id" to "temporal_query",
                    "name" to "Temporal Query",
                    "description" to "Query facts valid at a specific point in time. Supports historical reasoning.",
                    "tags" to listOf("temporal", "history", "time"),
                    "examples" to listOf("What was the user's location at 3pm yesterday?")
                ),
                mapOf(
                    "id" to "memory_management",
                    "name" to "Memory Management",
                    "description" to "Run consolidation (compress episodic patterns) and decay (expire stale facts). Essential for long-running agent sessions.",
                    "tags" to listOf("memory", "lifecycle", "management"),
                    "examples" to listOf("Consolidate repeated user queries into interest facts", "Evict low-relevance facts")
                )
            )
        )
        call.respond(agentCard)
    }
}
