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
import kotlinx.serialization.Serializable

// ── A2A Agent Card serializable model ──────────────────────────────────────

@Serializable
data class AgentCard(
    val name: String,
    val description: String,
    val url: String,
    val version: String,
    val documentationUrl: String,
    val provider: AgentProvider,
    val capabilities: AgentCapabilities,
    val authentication: AgentAuthentication,
    val defaultInputModes: List<String>,
    val defaultOutputModes: List<String>,
    val skills: List<AgentSkill>,
    val protocolSupport: AgentProtocolSupport
)

@Serializable
data class AgentProvider(
    val organization: String
)

@Serializable
data class AgentCapabilities(
    val streaming: Boolean,
    val pushNotifications: Boolean,
    val stateTransitionHistory: Boolean
)

@Serializable
data class AgentAuthentication(
    val schemes: List<String>,
    val credentials: AgentAuthCredentials
)

@Serializable
data class AgentAuthCredentials(
    val headerName: String,
    val alternativeHeader: String,
    val bootstrapEndpoint: String,
    val keyManagementEndpoint: String
)

@Serializable
data class AgentSkill(
    val id: String,
    val name: String,
    val description: String,
    val tags: List<String>,
    val examples: List<String>
)

@Serializable
data class AgentProtocolSupport(
    val mcp: McpProtocol,
    val rest: RestProtocol
)

@Serializable
data class McpProtocol(
    val endpoint: String,
    val sseEndpoint: String,
    val protocolVersion: String
)

@Serializable
data class RestProtocol(
    val baseUrl: String,
    val headers: Map<String, String>
)

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

        val agentCard = AgentCard(
            name = "NocturnusAI",
            description = "The knowledge and reasoning backend for AI agents. Tell it facts, teach it rules, ask it questions — and get deterministic, provable answers. Manages agent memory with temporal awareness, relevance scoring, and automatic cleanup.",
            url = baseUrl,
            version = "0.2.4",
            documentationUrl = "$baseUrl/userguide",
            provider = AgentProvider(
                organization = "NocturnusAI"
            ),
            capabilities = AgentCapabilities(
                streaming = true,
                pushNotifications = true,
                stateTransitionHistory = false
            ),
            authentication = AgentAuthentication(
                schemes = listOf("apiKey"),
                credentials = AgentAuthCredentials(
                    headerName = "X-API-Key",
                    alternativeHeader = "Authorization: Bearer <key>",
                    bootstrapEndpoint = "/auth/bootstrap",
                    keyManagementEndpoint = "/auth/keys"
                )
            ),
            defaultInputModes = listOf("application/json"),
            defaultOutputModes = listOf("application/json", "text/event-stream"),
            skills = listOf(
                AgentSkill(
                    id = "tell",
                    name = "Tell",
                    description = "Tell NocturnusAI something it should know. Store facts with optional auto-expiration (TTL).",
                    tags = listOf("knowledge", "store", "facts"),
                    examples = listOf("Tell it that Alice is Bob's parent", "Store a user preference that expires in 1 hour")
                ),
                AgentSkill(
                    id = "teach",
                    name = "Teach",
                    description = "Teach NocturnusAI a rule so it can derive new knowledge automatically. Define if-then relationships between concepts.",
                    tags = listOf("rules", "reasoning", "logic"),
                    examples = listOf("If X is parent of Y and Y is parent of Z, then X is grandparent of Z")
                ),
                AgentSkill(
                    id = "ask",
                    name = "Ask",
                    description = "Ask NocturnusAI a question and get provable answers derived from stored facts and rules. Optionally see the full reasoning chain.",
                    tags = listOf("query", "reasoning", "answers"),
                    examples = listOf("Who are Alice's grandchildren?", "Is Bob authorized to access this resource?")
                ),
                AgentSkill(
                    id = "forget",
                    name = "Forget",
                    description = "Make NocturnusAI forget a fact. Any knowledge that was derived from it is also automatically forgotten.",
                    tags = listOf("retract", "cleanup", "knowledge"),
                    examples = listOf("Forget that Alice is Bob's parent")
                ),
                AgentSkill(
                    id = "context",
                    name = "Get Context",
                    description = "Get the most relevant knowledge for the current reasoning step, ranked by recency, frequency, and priority.",
                    tags = listOf("memory", "context", "relevance"),
                    examples = listOf("Get the 50 most relevant facts for the current conversation")
                ),
                AgentSkill(
                    id = "recall",
                    name = "Recall",
                    description = "Recall what was known at a specific point in time. Time-travel queries for historical reasoning.",
                    tags = listOf("temporal", "history", "recall"),
                    examples = listOf("What was the user's location at 3pm yesterday?")
                ),
                AgentSkill(
                    id = "memory",
                    name = "Memory Management",
                    description = "Compress repeated patterns into summaries and clean up expired/irrelevant knowledge. Essential for long-running sessions.",
                    tags = listOf("memory", "compress", "cleanup"),
                    examples = listOf("Compress repeated user queries into interest summaries", "Clean up low-relevance facts")
                ),
                AgentSkill(
                    id = "predicates",
                    name = "Schema Discovery",
                    description = "Discover the knowledge base schema — list all predicate types with fact/rule counts and arity. Useful for understanding available knowledge before querying.",
                    tags = listOf("schema", "discovery", "predicates"),
                    examples = listOf("What predicates are stored?", "How many facts exist for each relationship type?")
                )
            ),
            protocolSupport = AgentProtocolSupport(
                mcp = McpProtocol(
                    endpoint = "$baseUrl/mcp",
                    sseEndpoint = "$baseUrl/mcp/sse",
                    protocolVersion = "2025-11-25"
                ),
                rest = RestProtocol(
                    baseUrl = baseUrl,
                    headers = mapOf(
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
