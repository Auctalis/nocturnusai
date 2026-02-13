package com.axiombase.server

import com.axiombase.TenantNotFoundException
import com.axiombase.server.llm.LlmConfig
import com.axiombase.server.llm.LlmFactExtractor
import com.axiombase.server.llm.LlmRuleExtractor
import com.axiombase.server.routes.*
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

    val appMicrometerRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    install(MicrometerMetrics) {
        registry = appMicrometerRegistry
    }

    install(CallLogging) {
        level = Level.INFO
        format { call ->
            val status = call.response.status()
            val httpMethod = call.request.httpMethod.value
            val uri = call.request.uri
            val requestId = call.response.headers["X-Request-ID"] ?: "-"
            "[$requestId] $httpMethod $uri -> $status"
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

    // Correlation ID interceptor
    intercept(ApplicationCallPipeline.Setup) {
        val requestId = call.request.header("X-Request-ID") ?: UUID.randomUUID().toString()
        call.response.header("X-Request-ID", requestId)
        MDC.put("requestId", requestId)
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

    // Authentication Middleware
    intercept(ApplicationCallPipeline.Call) {
        val apiKey = ServerConfig.apiKey
        if (apiKey != null) {
            val keyContext = call.request.header("X-API-Key")
            val isPublic = call.request.uri == "/health" ||
                call.request.uri == "/health/live" ||
                call.request.uri == "/health/ready" ||
                call.request.uri == "/metrics" ||
                call.request.uri == "/llm.txt" ||
                call.request.uri == "/userguide"

            if (!isPublic && keyContext != apiKey) {
                call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
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
        // Register Routes
        adminRoutes(dbManager)
        logicRoutes(dbManager)
        transactionRoutes(dbManager)
        testRoutes(dbManager)
        observabilityRoutes(appMicrometerRegistry, dbManager, ServerConfig.storageDir)
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
