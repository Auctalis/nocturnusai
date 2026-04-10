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

import com.nocturnusai.TenantNotFoundException
import com.nocturnusai.server.auth.ApiKeyManager
import com.nocturnusai.server.auth.AuthInterceptor
import com.nocturnusai.server.auth.AuthMode
import com.nocturnusai.server.conversation.ConversationTurnBuffer
import com.nocturnusai.server.llm.LlmConfig
import com.nocturnusai.server.llm.LlmContextFormatter
import com.nocturnusai.server.llm.LlmFactExtractor
import com.nocturnusai.server.llm.LlmRuleExtractor
import com.nocturnusai.server.observability.LoggingConfig
import com.nocturnusai.server.observability.Metrics
import com.nocturnusai.server.routes.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.metrics.micrometer.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.slf4j.MDC
import org.slf4j.event.Level

fun main() {
    // Configure logging first — before any logger is used
    LoggingConfig.configure()

    val env = applicationEngineEnvironment {
        connector {
            host = ServerConfig.host
            port = ServerConfig.port
        }
        if (ServerConfig.tlsEnabled) {
            val ksPath = ServerConfig.keystorePath
                ?: error("TLS_KEYSTORE_PATH is required when TLS_ENABLED=true")
            val ksPassword = ServerConfig.keystorePassword?.toCharArray()
                ?: error("TLS_KEYSTORE_PASSWORD is required when TLS_ENABLED=true")
            val keyStore = KeyStore.getInstance("PKCS12").apply {
                File(ksPath).inputStream().use { load(it, ksPassword) }
            }
            sslConnector(
                keyStore = keyStore,
                keyAlias = ServerConfig.keyAlias,
                keyStorePassword = { ksPassword },
                privateKeyPassword = { ServerConfig.privateKeyPassword?.toCharArray() ?: ksPassword }
            ) {
                host = ServerConfig.host
                port = ServerConfig.tlsPort
            }
        }
        module(Application::module)
    }

    val server = embeddedServer(Netty, env)

    Runtime.getRuntime().addShutdownHook(Thread {
        server.stop(5000, 30000)
    })

    server.start(wait = true)
}

fun Application.module() = moduleWithStorageDir(ServerConfig.storageDir)

/**
 * Core application module that accepts an explicit storage directory.
 *
 * This is the canonical implementation. [module] delegates here using
 * [ServerConfig.storageDir]. Tests can call this directly with a fresh
 * temporary directory to guarantee per-test isolation.
 */
fun Application.moduleWithStorageDir(storageDir: File) {
    install(ContentNegotiation) {
        json()
    }

    // ── Request body size limit ──────────────────────────────────────────
    // Reject oversized payloads before reading them into memory.
    // Default: 10 MB. Override with MAX_REQUEST_BODY_BYTES env var.
    val maxBodyBytes = System.getenv("MAX_REQUEST_BODY_BYTES")?.toLongOrNull() ?: (10L * 1024 * 1024)
    intercept(ApplicationCallPipeline.Plugins) {
        val contentLength = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
        if (contentLength != null && contentLength > maxBodyBytes) {
            call.respond(
                HttpStatusCode.PayloadTooLarge,
                ErrorResponse("PAYLOAD_TOO_LARGE", "Request body exceeds ${maxBodyBytes / 1024 / 1024} MB limit")
            )
            return@intercept finish()
        }
    }

    install(StatusPages) {
        exception<TenantNotFoundException> { call, cause ->
            call.respond(HttpStatusCode.NotFound, ErrorResponse("TENANT_NOT_FOUND", cause.message ?: "Tenant not found"))
        }
        exception<ValidationException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", cause.message ?: "Validation error"))
        }
        exception<DatabaseNotFoundException> { call, cause ->
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", cause.message ?: "Database not found"))
        }
    }

    // ── Metrics ─────────────────────────────────────────────────────────
    val appMicrometerRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    install(MicrometerMetrics) {
        registry = appMicrometerRegistry
    }
    Metrics.init(appMicrometerRegistry)

    // ── Request logging ─────────────────────────────────────────────────
    install(CallLogging) {
        level = Level.INFO
        format { call ->
            val status = call.response.status()
            val httpMethod = call.request.httpMethod.value
            val uri = call.request.uri
            val requestId = call.response.headers["X-Request-ID"] ?: "-"
            val db = call.request.header("X-Database") ?: "default"
            val tenant = call.request.header("X-Tenant-ID") ?: "-"
            "[$requestId] $httpMethod $uri -> $status [db=$db t=$tenant]"
        }
    }

    install(CORS) {
        // Configure allowed origins via CORS_ALLOWED_ORIGINS env var (comma-separated).
        // Default: localhost origins only. Set to "*" only if you understand the risks.
        val corsOrigins = System.getenv("CORS_ALLOWED_ORIGINS")
        if (corsOrigins == "*") {
            anyHost()
        } else {
            val origins = corsOrigins?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
                ?: listOf("http://localhost:3000", "http://localhost:5173", "http://localhost:8080")
            origins.forEach { allowHost(it.removePrefix("http://").removePrefix("https://"), schemes = listOf("http", "https")) }
        }
        allowHeader(HttpHeaders.ContentType)
        allowHeader("X-Transaction-ID")
        allowHeader("Authorization")
        allowHeader("X-API-Key")
        allowHeader("X-Database")
        allowHeader("X-Tenant-ID")
        allowHeader("X-Request-ID")
        exposeHeader("X-Request-ID")
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)
    }

    // LLM Provider & Fact Extractor
    val llmProvider = try {
        LlmConfig.createProvider()
    } catch (e: Exception) {
        environment.log.warn("Failed to initialize LLM provider: ${e.message}")
        null
    }
    val factExtractor = if (llmProvider != null && ServerConfig.extractionEnabled) {
        environment.log.info("LLM extraction: provider=${llmProvider.name}, model=${llmProvider.model}, enabled=true, maxFacts=${LlmConfig.extractionMaxFacts}")
        LlmFactExtractor(llmProvider, LlmConfig.extractionMaxFacts)
    } else {
        environment.log.info("LLM extraction: disabled (${if (llmProvider == null) "no provider configured" else "EXTRACTION_ENABLED=false"})")
        null
    }
    val ruleExtractor = if (llmProvider != null && ServerConfig.extractionEnabled) {
        LlmRuleExtractor(llmProvider)
    } else {
        null
    }

    // Embedding provider — separate from completion LLM (e.g. Ollama nomic-embed-text)
    val embedProvider = try {
        LlmConfig.createEmbedProvider()
    } catch (e: Exception) {
        environment.log.warn("Failed to initialize embedding provider: ${e.message}")
        null
    }
    val semanticContext = com.nocturnusai.server.core.CachedSemanticContext(embedProvider)
    if (embedProvider != null) {
        environment.log.info("Semantic similarity: enabled (embed provider=${embedProvider.name}, model=${embedProvider.model})")
    } else {
        environment.log.info("Semantic similarity: disabled (no embedding provider configured)")
    }

    // Database Manager
    val dbManager = DatabaseManager(storageDir, factExtractor, ruleExtractor, semanticContext)
    Metrics.activeDatabases.set(dbManager.getDatabaseNames().size)

    // ── MDC enrichment — correlation ID + context per request ────────
    intercept(ApplicationCallPipeline.Setup) {
        val requestId = call.request.header("X-Request-ID") ?: UUID.randomUUID().toString()
        call.response.header("X-Request-ID", requestId)
        MDC.put("requestId", requestId)
        MDC.put("database", call.request.header("X-Database") ?: "default")
        MDC.put("tenantId", call.request.header("X-Tenant-ID") ?: "-")
    }

    // Header validation interceptor
    intercept(ApplicationCallPipeline.Plugins) {
        val dbHeader = call.request.header("X-Database")
        if (dbHeader != null && dbHeader != "default") {
            try {
                Validator.validateDatabaseName(dbHeader)
            } catch (e: ValidationException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", e.message ?: "Invalid X-Database header"))
                return@intercept finish()
            }
        }
        val tenantHeader = call.request.header("X-Tenant-ID")
        if (tenantHeader != null && tenantHeader.isNotBlank()) {
            try {
                Validator.validateTenantId(tenantHeader)
            } catch (e: ValidationException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", e.message ?: "Invalid X-Tenant-ID header"))
                return@intercept finish()
            }
        }
    }

    // ── Security headers ─────────────────────────────────────────────────
    intercept(ApplicationCallPipeline.Call) {
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("X-Frame-Options", "DENY")
        call.response.header("X-XSS-Protection", "1; mode=block")
        if (ServerConfig.tlsEnabled) {
            call.response.header("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
        }
    }

    // ── Authentication & Authorization ──────────────────────────────────
    val keyManager = if (ServerConfig.authMode == AuthMode.RBAC) {
        val km = ApiKeyManager(storageDir)
        environment.log.info("Auth mode: RBAC (${km.listKeys().size} keys loaded)")
        if (!km.hasKeys()) {
            environment.log.warn("No API keys found — POST /auth/bootstrap to create the first admin key")
        }
        if (ServerConfig.usingDefaultAdminCredentials) {
            environment.log.warn("⚠️  Using default admin credentials. Set NOCTURNUSAI_ADMIN_USER and NOCTURNUSAI_ADMIN_PASS before exposing this server.")
        }
        km
    } else {
        environment.log.info("Auth mode: ${ServerConfig.authMode.name}")
        if (ServerConfig.authMode == AuthMode.DISABLED) {
            environment.log.warn("⚠️  Authentication is DISABLED. Set AUTH_ENABLED=true or API_KEY for production use.")
        }
        null
    }
    AuthInterceptor.install(this, keyManager)

    // ── Legal & safety notice ─────────────────────────────────────────────
    environment.log.info("──────────────────────────────────────────────────────────────────")
    environment.log.info("NocturnusAI v${BuildInfo.version} — Logic Server for Agentic AI")
    environment.log.info("Licensed under BSL 1.1 — (c) 2026 Auctalis LLC")
    environment.log.info("")
    environment.log.info("NOTICE: Engine output reflects logical consistency of inference,")
    environment.log.info("not real-world accuracy. Do not use for autonomous high-stakes")
    environment.log.info("decisions without human-in-the-loop verification.")
    environment.log.info("See DISCLAIMER.md — provided AS-IS, no warranty.")
    environment.log.info("──────────────────────────────────────────────────────────────────")

    // ── Production readiness warnings ────────────────────────────────────
    if (!ServerConfig.tlsEnabled && ServerConfig.host != "127.0.0.1" && ServerConfig.host != "localhost") {
        environment.log.warn("⚠️  TLS is disabled and server is bound to ${ServerConfig.host}. " +
            "Traffic is unencrypted. Set TLS_ENABLED=true for production.")
    }
    if (ServerConfig.encryptionKey == null) {
        environment.log.warn("⚠️  Encryption at rest is disabled. " +
            "Set ENCRYPTION_KEY (64 hex chars) to protect stored data. " +
            "Generate with: openssl rand -hex 32")
    }

    // ── Follower write rejection ─────────────────────────────────────────
    // When this node is a read-only follower, reject all mutating endpoints
    // so agents are immediately redirected to the leader rather than causing
    // silent split-brain divergence.
    if (ServerConfig.replicationMode == ReplicationMode.FOLLOWER) {
        val writeMethodPrefixes = setOf(
            "/tell", "/teach", "/forget",
            "/assert/", "/retract", "/execute",
            "/tx/", "/memory/consolidate", "/memory/decay", "/memory/priority",
            "/memory/compress", "/memory/cleanup", "/memory/prioritize"
        )
        val writeAdminPaths = setOf("/admin/databases")

        intercept(ApplicationCallPipeline.Plugins) {
            val method = call.request.httpMethod
            val path = call.request.uri.substringBefore('?')

            val isWriteMethod = method == HttpMethod.Post || method == HttpMethod.Put ||
                    method == HttpMethod.Delete || method == HttpMethod.Patch

            val isBlockedPath = isWriteMethod && (
                writeMethodPrefixes.any { prefix -> path.startsWith(prefix) } ||
                (writeAdminPaths.any { admin -> path.startsWith(admin) } &&
                    (method == HttpMethod.Post || method == HttpMethod.Delete))
            )

            if (isBlockedPath) {
                call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponse(
                        code = "FOLLOWER_READ_ONLY",
                        message = "This server is a read-only follower. Send writes to the leader.",
                        details = mapOf("leader" to (ServerConfig.leaderUrl ?: "unknown"))
                    )
                )
                return@intercept finish()
            }
        }
    }

    // Graceful shutdown: close all databases when application stops
    environment.monitor.subscribe(ApplicationStopping) {
        environment.log.info("Application stopping — closing all databases...")
        dbManager.close()
        MDC.clear()
    }

    routing {
        // Auth routes (bootstrap, key management, whoami)
        authRoutes(keyManager)

        // Simplified developer-friendly routes (primary API surface)
        simplifiedRoutes(dbManager)

        // Full routes (advanced / backward-compatible)
        adminRoutes(dbManager)
        logicRoutes(dbManager)
        aggregateRoutes(dbManager)
        transactionRoutes(dbManager)
        testRoutes(dbManager)
        val llmContextFormatter = if (llmProvider != null) LlmContextFormatter(llmProvider) else null
        val conversationTurnBuffer = ConversationTurnBuffer()
        memoryRoutes(dbManager, llmContextFormatter)
        contextManagementRoutes(dbManager, factExtractor, llmProvider?.name, llmContextFormatter, conversationTurnBuffer)
        scopeRoutes(dbManager)
        mcpRoutes(dbManager)
        observabilityRoutes(appMicrometerRegistry, dbManager, storageDir, llmProvider != null)
        replicationRoutes(dbManager)
        extractionRoutes(dbManager, factExtractor, ruleExtractor, llmProvider)
        synthesisRoutes(dbManager, llmProvider)
    }

    // Start Replication Client if Follower
    if (ServerConfig.replicationMode == ReplicationMode.FOLLOWER) {
        val leader = ServerConfig.leaderUrl
        if (leader != null) {
            val replicationClient = ReplicationClient(dbManager, leader)
            replicationClient.start()

            // Stop the replication client cleanly when the server shuts down
            environment.monitor.subscribe(ApplicationStopping) {
                environment.log.info("Application stopping — stopping replication client...")
                replicationClient.stop()
            }
        } else {
            environment.log.error("REPLICATION_MODE=FOLLOWER but LEADER_URL is not set!")
        }
    }

    // Generate llm.txt on startup
    try {
        val llmText = LlmTxtGenerator.generate(this)
        File("llm.txt").writeText(llmText)
        environment.log.info("Generated llm.txt")
    } catch (e: Exception) {
        environment.log.warn("Failed to generate llm.txt: ${e.message}")
    }
}
