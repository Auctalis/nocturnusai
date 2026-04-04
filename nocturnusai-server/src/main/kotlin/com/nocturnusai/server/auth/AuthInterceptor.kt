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

package com.nocturnusai.server.auth

import com.nocturnusai.server.ErrorResponse
import com.nocturnusai.server.ServerConfig
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import org.slf4j.LoggerFactory

/**
 * Authentication and authorization interceptor for NocturnusAI.
 *
 * Supports three auth modes:
 *
 * 1. **No auth** (dev mode): API_KEY not set, AUTH_ENABLED not set → all requests allowed
 * 2. **Legacy single key**: API_KEY set, AUTH_ENABLED not set → backward-compatible single-key auth
 * 3. **Full RBAC**: AUTH_ENABLED=true → multi-key auth with roles and scoping
 *
 * In all modes, public endpoints bypass auth.
 */
object AuthInterceptor {
    private val log = LoggerFactory.getLogger(AuthInterceptor::class.java)

    // Rate limit failed API-key validation: 20 attempts/minute, 1-minute lockout.
    // Higher threshold than bootstrap because legitimate clients may retry quickly.
    private val authRateLimiter = RateLimiter(maxAttempts = 20, windowMs = 60_000L, lockoutMs = 60_000L)

    /** Endpoints that never require authentication */
    private val PUBLIC_PATHS = setOf(
        "/health",
        "/health/live",
        "/health/ready",
        "/metrics",
        "/llm.txt",
        "/userguide",
        "/.well-known/agent.json",
        "/auth/status"
    )

    /** Route prefix → required permission mapping */
    private val PERMISSION_MAP: List<Pair<RouteMatch, Permission>> = listOf(
        // Admin operations (most specific first)
        RouteMatch("POST", "/admin/databases/{name}/nuke") to Permission.NUKE,
        RouteMatch("POST", "/admin/databases/{name}/tenants/{id}/nuke") to Permission.NUKE,
        RouteMatch("POST", "/admin/backups") to Permission.BACKUP,
        RouteMatch("POST", "/admin/databases/{name}/tenants") to Permission.TENANT_CREATE,
        RouteMatch("DELETE", "/admin/databases/{name}/tenants/{id}") to Permission.TENANT_DELETE,
        RouteMatch("POST", "/admin/databases") to Permission.DATABASE_CREATE,
        RouteMatch("DELETE", "/admin/databases/{name}") to Permission.DATABASE_DELETE,
        RouteMatch("GET", "/admin/databases") to Permission.DATABASE_LIST,
        RouteMatch("GET", "/admin/databases/{name}/facts") to Permission.FACT_READ,
        RouteMatch("GET", "/admin/databases/{name}/rules") to Permission.RULE_READ,
        RouteMatch("GET", "/admin/databases/{name}/tenants") to Permission.DATABASE_LIST,

        // Key management
        RouteMatch("POST", "/auth/keys") to Permission.KEY_MANAGE,
        RouteMatch("GET", "/auth/keys") to Permission.KEY_MANAGE,
        RouteMatch("GET", "/auth/keys/{id}") to Permission.KEY_MANAGE,
        RouteMatch("PATCH", "/auth/keys/{id}") to Permission.KEY_MANAGE,
        RouteMatch("DELETE", "/auth/keys/{id}") to Permission.KEY_MANAGE,

        // Simplified routes
        RouteMatch("POST", "/tell") to Permission.FACT_WRITE,
        RouteMatch("POST", "/ask") to Permission.INFERENCE,
        RouteMatch("POST", "/teach") to Permission.RULE_WRITE,
        RouteMatch("POST", "/forget") to Permission.FACT_WRITE,

        // Schema discovery
        RouteMatch("GET", "/predicates") to Permission.FACT_READ,

        // Logic routes
        RouteMatch("POST", "/assert/fact") to Permission.FACT_WRITE,
        RouteMatch("POST", "/assert/rule") to Permission.RULE_WRITE,
        RouteMatch("POST", "/assert/template") to Permission.RULE_WRITE,
        RouteMatch("POST", "/infer") to Permission.INFERENCE,
        RouteMatch("POST", "/retract") to Permission.FACT_WRITE,
        RouteMatch("POST", "/execute") to Permission.FACT_WRITE,

        // Memory routes
        RouteMatch("POST", "/memory/query/temporal") to Permission.MEMORY_READ,
        RouteMatch("POST", "/memory/query/salient") to Permission.MEMORY_READ,
        RouteMatch("POST", "/memory/context") to Permission.MEMORY_READ,
        RouteMatch("POST", "/memory/priority") to Permission.MEMORY_WRITE,
        RouteMatch("POST", "/memory/consolidate") to Permission.MEMORY_WRITE,
        RouteMatch("POST", "/memory/decay") to Permission.MEMORY_WRITE,
        RouteMatch("POST", "/memory/compress") to Permission.MEMORY_WRITE,
        RouteMatch("POST", "/memory/cleanup") to Permission.MEMORY_WRITE,
        RouteMatch("GET", "/memory/events") to Permission.MEMORY_READ,

        // Extraction / synthesis
        RouteMatch("POST", "/extract") to Permission.EXTRACT,
        RouteMatch("POST", "/extract/batch") to Permission.EXTRACT,
        RouteMatch("POST", "/synthesize") to Permission.SYNTHESIZE,

        // Transactions
        RouteMatch("POST", "/tx/begin") to Permission.TRANSACTION,
        RouteMatch("POST", "/tx/commit/{id}") to Permission.TRANSACTION,
        RouteMatch("POST", "/tx/rollback/{id}") to Permission.TRANSACTION,

        // MCP
        RouteMatch("POST", "/mcp") to Permission.INFERENCE,
        RouteMatch("GET", "/mcp/sse") to Permission.INFERENCE,

        // Replication
        RouteMatch("GET", "/replication/wal") to Permission.BACKUP
    )

    /**
     * Install the auth interceptor on the application pipeline.
     */
    fun install(app: Application, keyManager: ApiKeyManager?) {
        app.intercept(ApplicationCallPipeline.Call) {
            // Public endpoints always pass
            val path = call.request.uri.split("?").first()
            if (path in PUBLIC_PATHS) return@intercept

            val authMode = ServerConfig.authMode

            when (authMode) {
                AuthMode.DISABLED -> {
                    // Dev mode — no auth
                    return@intercept
                }

                AuthMode.LEGACY -> {
                    // Backward-compatible single API_KEY check
                    val expected = ServerConfig.apiKey
                    val provided = extractKey(call)
                    if (provided != expected) {
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            ErrorResponse("UNAUTHORIZED", "Invalid or missing API key")
                        )
                        return@intercept finish()
                    }
                    return@intercept
                }

                AuthMode.RBAC -> {
                    if (keyManager == null) {
                        log.error("AUTH_ENABLED=true but ApiKeyManager not initialized")
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            ErrorResponse("AUTH_ERROR", "Authentication system not available")
                        )
                        return@intercept finish()
                    }

                    // Bootstrap: if no keys exist yet, allow the bootstrap endpoint
                    if (path == "/auth/bootstrap" && call.request.httpMethod == HttpMethod.Post) {
                        if (!keyManager.hasKeys()) {
                            return@intercept // allow bootstrap
                        }
                        call.respond(
                            HttpStatusCode.Forbidden,
                            ErrorResponse("FORBIDDEN", "Bootstrap already completed. Use an admin key to create new keys.")
                        )
                        return@intercept finish()
                    }

                    // Check rate limit before any key work
                    val clientIp = call.request.local.remoteHost
                    when (val rate = authRateLimiter.check(clientIp)) {
                        is RateLimiter.Result.LockedOut -> {
                            log.warn("Auth rate limit exceeded from $clientIp — locked out for ${rate.retryAfterSeconds}s")
                            call.response.header("Retry-After", rate.retryAfterSeconds.toString())
                            call.respond(
                                HttpStatusCode.TooManyRequests,
                                ErrorResponse("RATE_LIMITED", "Too many failed auth attempts. Try again in ${rate.retryAfterSeconds}s")
                            )
                            return@intercept finish()
                        }
                        RateLimiter.Result.Allowed -> { /* proceed */ }
                    }

                    // Require key
                    val rawKey = extractKey(call)
                    if (rawKey == null) {
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            ErrorResponse("UNAUTHORIZED", "API key required. Provide via X-API-Key header or Authorization: Bearer <key>")
                        )
                        return@intercept finish()
                    }

                    // Validate key
                    val principal = keyManager.validate(rawKey)
                    if (principal == null) {
                        log.warn("Auth failed: invalid/expired key from $clientIp (prefix=${rawKey.take(8)}...)")
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            ErrorResponse("UNAUTHORIZED", "Invalid, disabled, or expired API key")
                        )
                        return@intercept finish()
                    }

                    // Successful auth — clear failure counter
                    authRateLimiter.reset(clientIp)

                    // Check permission for this route
                    val method = call.request.httpMethod.value
                    val requiredPermission = findPermission(method, path)
                    if (requiredPermission != null && !principal.hasPermission(requiredPermission)) {
                        call.respond(
                            HttpStatusCode.Forbidden,
                            ErrorResponse(
                                "FORBIDDEN",
                                "Key '${principal.name}' (role=${principal.role.name.lowercase()}) lacks permission: ${requiredPermission.name}"
                            )
                        )
                        return@intercept finish()
                    }

                    // Check database scope
                    val dbName = call.request.header("X-Database") ?: "default"
                    if (!principal.canAccessDatabase(dbName)) {
                        call.respond(
                            HttpStatusCode.Forbidden,
                            ErrorResponse(
                                "FORBIDDEN",
                                "Key '${principal.name}' is not authorized for database '$dbName'"
                            )
                        )
                        return@intercept finish()
                    }

                    // Check tenant scope
                    val tenantId = call.request.header("X-Tenant-ID")
                    if (tenantId != null && !principal.canAccessTenant(tenantId)) {
                        call.respond(
                            HttpStatusCode.Forbidden,
                            ErrorResponse(
                                "FORBIDDEN",
                                "Key '${principal.name}' is not authorized for tenant '$tenantId'"
                            )
                        )
                        return@intercept finish()
                    }

                    // Attach principal to call attributes for downstream use
                    call.attributes.put(AuthPrincipalKey, principal)
                }
            }
        }
    }

    /**
     * Extract API key from X-API-Key header or Authorization: Bearer header.
     */
    private fun extractKey(call: ApplicationCall): String? {
        // Try X-API-Key header first
        call.request.header("X-API-Key")?.let { return it }

        // Try Authorization: Bearer <key>
        val auth = call.request.header("Authorization") ?: return null
        return if (auth.startsWith("Bearer ", ignoreCase = true)) {
            auth.removePrefix("Bearer ").removePrefix("bearer ").trim()
        } else {
            null
        }
    }

    /**
     * Find the required permission for a given HTTP method + path.
     */
    private fun findPermission(method: String, path: String): Permission? {
        for ((route, permission) in PERMISSION_MAP) {
            if (route.matches(method, path)) return permission
        }
        return null // unknown routes pass through (public or handled by Ktor 404)
    }
}

/**
 * Simple route matcher that supports path parameters like {name}.
 */
data class RouteMatch(val method: String, val pattern: String) {
    private val segments = pattern.split("/")

    fun matches(httpMethod: String, path: String): Boolean {
        if (method != httpMethod) return false
        val pathSegments = path.split("/")
        if (segments.size != pathSegments.size) return false
        return segments.zip(pathSegments).all { (pattern, actual) ->
            pattern.startsWith("{") && pattern.endsWith("}") || pattern == actual
        }
    }
}

/** Ktor attribute key for storing the authenticated principal on a request */
val AuthPrincipalKey = io.ktor.util.AttributeKey<AuthPrincipal>("AuthPrincipal")

/** Extension to get the auth principal from a call (null in dev/legacy mode) */
fun ApplicationCall.authPrincipal(): AuthPrincipal? =
    attributes.getOrNull(AuthPrincipalKey)

/**
 * Auth modes:
 * - DISABLED: no authentication (dev mode, no API_KEY set)
 * - LEGACY: single static API_KEY (backward compatible)
 * - RBAC: full role-based access control with managed API keys
 */
enum class AuthMode {
    DISABLED,
    LEGACY,
    RBAC
}
