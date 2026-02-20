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
import com.nocturnusai.server.llm.LlmConfig
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

fun Application.module() {
    install(ContentNegotiation) {
        json()
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
        anyHost()
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
    val factExtractor = if (llmProvider != null) {
        environment.log.info("LLM extraction: provider=${llmProvider.name}, model=${llmProvider.model}, enabled=${ServerConfig.extractionEnabled}, maxFacts=${LlmConfig.extractionMaxFacts}")
        LlmFactExtractor(llmProvider, LlmConfig.extractionMaxFacts)
    } else {
        environment.log.info("LLM extraction: disabled (no provider configured)")
        null
    }
    val ruleExtractor = if (llmProvider != null) {
        LlmRuleExtractor(llmProvider)
    } else {
        null
    }

    // Database Manager
    val dbManager = DatabaseManager(ServerConfig.storageDir, factExtractor, ruleExtractor)
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

    // ── Authentication & Authorization ──────────────────────────────────
    val keyManager = if (ServerConfig.authMode == AuthMode.RBAC) {
        val km = ApiKeyManager(ServerConfig.storageDir)
        environment.log.info("Auth mode: RBAC (${km.listKeys().size} keys loaded)")
        if (!km.hasKeys()) {
            environment.log.warn("No API keys found — POST /auth/bootstrap to create the first admin key")
        }
        km
    } else {
        environment.log.info("Auth mode: ${ServerConfig.authMode.name}")
        null
    }
    AuthInterceptor.install(this, keyManager)

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
        transactionRoutes(dbManager)
        testRoutes(dbManager)
        memoryRoutes(dbManager)
        mcpRoutes(dbManager)
        observabilityRoutes(appMicrometerRegistry, dbManager, ServerConfig.storageDir, llmProvider != null)
        replicationRoutes(dbManager)
        extractionRoutes(dbManager, factExtractor, ruleExtractor, llmProvider)
        synthesisRoutes(dbManager, llmProvider)
    }

    // Start Replication Client if Follower
    if (ServerConfig.replicationMode == ReplicationMode.FOLLOWER) {
        val leader = ServerConfig.leaderUrl
        if (leader != null) {
            // Replicate Default DB
            val db = dbManager.getDatabase("default")
            if (db != null) {
                val client = ReplicationClient(db, leader)
                client.start()
            }
        } else {
            System.err.println("Replication Mode is FOLLOWER but LEADER_URL is missing!")
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
