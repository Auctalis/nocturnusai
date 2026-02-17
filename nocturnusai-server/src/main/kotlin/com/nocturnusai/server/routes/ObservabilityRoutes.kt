package com.nocturnusai.server.routes

import com.nocturnusai.server.DatabaseManager
import com.nocturnusai.server.HealthChecker
import com.nocturnusai.server.LlmTxtGenerator
import io.micrometer.prometheus.PrometheusMeterRegistry
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File

fun Route.observabilityRoutes(appMicrometerRegistry: PrometheusMeterRegistry, dbManager: DatabaseManager, storageDir: File, llmConfigured: Boolean = false) {
    get("/metrics") {
         call.respondText(appMicrometerRegistry.scrape())
    }

    get("/health") {
        val healthStatus = HealthChecker.check(dbManager, storageDir, llmConfigured)
        val statusCode = if (healthStatus.status == "unhealthy") HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK
        call.respond(statusCode, healthStatus)
    }

    get("/health/live") {
        call.respondText("OK")
    }

    get("/health/ready") {
        val healthStatus = HealthChecker.check(dbManager, storageDir, llmConfigured)
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
        val scheme = if (com.nocturnusai.server.ServerConfig.tlsEnabled) "https" else "http"
        val baseUrl = "$scheme://$host:$port"

        val agentCard = mapOf(
            "name" to "NocturnusAI",
            "description" to "The knowledge and reasoning backend for AI agents. Tell it facts, teach it rules, ask it questions — and get deterministic, provable answers. Manages agent memory with temporal awareness, relevance scoring, and automatic cleanup.",
            "url" to baseUrl,
            "version" to "2.0.0",
            "documentationUrl" to "$baseUrl/userguide",
            "provider" to mapOf(
                "organization" to "NocturnusAI"
            ),
            "capabilities" to mapOf(
                "streaming" to true,
                "pushNotifications" to true,
                "stateTransitionHistory" to false
            ),
            "authentication" to mapOf(
                "schemes" to listOf("apiKey"),
                "credentials" to mapOf(
                    "headerName" to "X-API-Key",
                    "alternativeHeader" to "Authorization: Bearer <key>",
                    "bootstrapEndpoint" to "/auth/bootstrap",
                    "keyManagementEndpoint" to "/auth/keys"
                )
            ),
            "defaultInputModes" to listOf("application/json"),
            "defaultOutputModes" to listOf("application/json", "text/event-stream"),
            "skills" to listOf(
                mapOf(
                    "id" to "tell",
                    "name" to "Tell",
                    "description" to "Tell NocturnusAI something it should know. Store facts with optional auto-expiration (TTL).",
                    "tags" to listOf("knowledge", "store", "facts"),
                    "examples" to listOf("Tell it that Alice is Bob's parent", "Store a user preference that expires in 1 hour")
                ),
                mapOf(
                    "id" to "teach",
                    "name" to "Teach",
                    "description" to "Teach NocturnusAI a rule so it can derive new knowledge automatically. Define if-then relationships between concepts.",
                    "tags" to listOf("rules", "reasoning", "logic"),
                    "examples" to listOf("If X is parent of Y and Y is parent of Z, then X is grandparent of Z")
                ),
                mapOf(
                    "id" to "ask",
                    "name" to "Ask",
                    "description" to "Ask NocturnusAI a question and get provable answers derived from stored facts and rules. Optionally see the full reasoning chain.",
                    "tags" to listOf("query", "reasoning", "answers"),
                    "examples" to listOf("Who are Alice's grandchildren?", "Is Bob authorized to access this resource?")
                ),
                mapOf(
                    "id" to "forget",
                    "name" to "Forget",
                    "description" to "Make NocturnusAI forget a fact. Any knowledge that was derived from it is also automatically forgotten.",
                    "tags" to listOf("retract", "cleanup", "knowledge"),
                    "examples" to listOf("Forget that Alice is Bob's parent")
                ),
                mapOf(
                    "id" to "context",
                    "name" to "Get Context",
                    "description" to "Get the most relevant knowledge for the current reasoning step, ranked by recency, frequency, and priority.",
                    "tags" to listOf("memory", "context", "relevance"),
                    "examples" to listOf("Get the 50 most relevant facts for the current conversation")
                ),
                mapOf(
                    "id" to "recall",
                    "name" to "Recall",
                    "description" to "Recall what was known at a specific point in time. Time-travel queries for historical reasoning.",
                    "tags" to listOf("temporal", "history", "recall"),
                    "examples" to listOf("What was the user's location at 3pm yesterday?")
                ),
                mapOf(
                    "id" to "memory",
                    "name" to "Memory Management",
                    "description" to "Compress repeated patterns into summaries and clean up expired/irrelevant knowledge. Essential for long-running sessions.",
                    "tags" to listOf("memory", "compress", "cleanup"),
                    "examples" to listOf("Compress repeated user queries into interest summaries", "Clean up low-relevance facts")
                ),
                mapOf(
                    "id" to "predicates",
                    "name" to "Schema Discovery",
                    "description" to "Discover the knowledge base schema — list all predicate types with fact/rule counts and arity. Useful for understanding available knowledge before querying.",
                    "tags" to listOf("schema", "discovery", "predicates"),
                    "examples" to listOf("What predicates are stored?", "How many facts exist for each relationship type?")
                )
            ),
            "protocolSupport" to mapOf(
                "mcp" to mapOf(
                    "endpoint" to "$baseUrl/mcp",
                    "sseEndpoint" to "$baseUrl/mcp/sse",
                    "protocolVersion" to "2025-11-25"
                ),
                "rest" to mapOf(
                    "baseUrl" to baseUrl,
                    "headers" to mapOf(
                        "X-Database" to "Database name (default: 'default')",
                        "X-Tenant-ID" to "Tenant ID for multi-tenancy",
                        "X-API-Key" to "API key for authentication"
                    )
                )
            )
        )
        call.respond(agentCard)
    }
}
