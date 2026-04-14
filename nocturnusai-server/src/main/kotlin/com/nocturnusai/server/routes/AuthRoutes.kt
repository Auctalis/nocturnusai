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

import com.nocturnusai.server.ErrorResponse
import com.nocturnusai.server.ServerConfig
import com.nocturnusai.server.ValidationException
import com.nocturnusai.server.auth.*
import com.nocturnusai.server.auth.ClientIp
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.security.MessageDigest

private val log = LoggerFactory.getLogger("com.nocturnusai.server.routes.AuthRoutes")

// Rate limiter for bootstrap endpoint: 5 attempts per minute, 5-minute lockout
private val bootstrapRateLimiter = RateLimiter(maxAttempts = 5, windowMs = 60_000L, lockoutMs = 300_000L)

fun Route.authRoutes(keyManager: ApiKeyManager?) {

    // ── Bootstrap endpoint ────────────────────────────────────────────────
    // POST /auth/bootstrap
    // Creates the first admin key. Only works when no keys exist yet.
    // Requires NOCTURNUSAI_ADMIN_USER + NOCTURNUSAI_ADMIN_PASS from .env as credentials.
    post("/auth/bootstrap") {
        if (keyManager == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("AUTH_DISABLED", "RBAC auth not enabled. Set AUTH_ENABLED=true in .env"))
            return@post
        }
        if (keyManager.hasKeys()) {
            call.respond(
                HttpStatusCode.Forbidden,
                ErrorResponse("FORBIDDEN", "Bootstrap already completed. Use an admin key to create new keys via POST /auth/keys.")
            )
            return@post
        }

        // Rate-limit by client IP to prevent credential brute-force.
        // Uses trusted-proxy-aware extraction — see ClientIp for rationale.
        val clientIp = ClientIp.of(call)
        when (val rate = bootstrapRateLimiter.check(clientIp)) {
            is RateLimiter.Result.LockedOut -> {
                log.warn("Bootstrap rate limit exceeded from $clientIp — locked out for ${rate.retryAfterSeconds}s")
                call.response.header("Retry-After", rate.retryAfterSeconds.toString())
                call.respond(
                    HttpStatusCode.TooManyRequests,
                    ErrorResponse("RATE_LIMITED", "Too many failed attempts. Try again in ${rate.retryAfterSeconds}s")
                )
                return@post
            }
            RateLimiter.Result.Allowed -> { /* proceed */ }
        }

        val req = call.receive<BootstrapRequest>()

        // Verify admin credentials from environment.
        // Use MessageDigest.isEqual for a constant-time comparison on the password so
        // the length and prefix of the expected secret cannot be inferred from timing.
        val userMatches = MessageDigest.isEqual(
            req.username.toByteArray(Charsets.UTF_8),
            ServerConfig.adminUser.toByteArray(Charsets.UTF_8)
        )
        val passMatches = MessageDigest.isEqual(
            req.password.toByteArray(Charsets.UTF_8),
            ServerConfig.adminPass.toByteArray(Charsets.UTF_8)
        )
        if (!userMatches || !passMatches) {
            log.warn("Bootstrap auth failed (bad credentials) from $clientIp")
            call.respond(
                HttpStatusCode.Unauthorized,
                ErrorResponse("UNAUTHORIZED", "Invalid admin credentials. Check NOCTURNUSAI_ADMIN_USER and NOCTURNUSAI_ADMIN_PASS in .env")
            )
            return@post
        }

        // Success — clear the failure counter
        bootstrapRateLimiter.reset(clientIp)

        val (rawKey, record) = keyManager.createKey(
            name = req.keyName ?: "admin",
            role = Role.ADMIN,
            expiresInDays = ServerConfig.defaultKeyExpiryDays,
            description = "Bootstrap admin key"
        )

        log.info("Bootstrap completed: first admin key created (id=${record.id}, expiresAt=${record.expiresAt})")

        call.respond(HttpStatusCode.Created, CreateKeyResponse(
            id = record.id,
            name = record.name,
            key = rawKey,
            prefix = record.keyPrefix,
            role = "admin",
            databases = record.databases,
            tenants = record.tenants,
            expiresAt = record.expiresAt
        ))
    }

    // ── Auth status ───────────────────────────────────────────────────────
    // GET /auth/status
    // Returns current auth mode and whether bootstrap is needed.
    get("/auth/status") {
        call.respond(AuthStatusResponse(
            mode = ServerConfig.authMode.name.lowercase(),
            bootstrapRequired = keyManager != null && !keyManager.hasKeys(),
            keyCount = keyManager?.listKeys()?.size ?: 0
        ))
    }

    // ── Key management (requires KEY_MANAGE permission / admin role) ─────

    // POST /auth/keys — Create a new API key
    post("/auth/keys") {
        if (keyManager == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("AUTH_DISABLED", "RBAC auth not enabled"))
            return@post
        }
        val principal = call.authPrincipal()
        val req = call.receive<CreateKeyRequest>()

        val role = try {
            Role.valueOf(req.role.uppercase())
        } catch (e: IllegalArgumentException) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("VALIDATION_ERROR", "Invalid role '${req.role}'. Must be: admin, writer, reader")
            )
            return@post
        }

        // Non-admin cannot create admin keys
        if (role == Role.ADMIN && principal?.role != Role.ADMIN) {
            call.respond(
                HttpStatusCode.Forbidden,
                ErrorResponse("FORBIDDEN", "Only admin keys can create other admin keys")
            )
            return@post
        }

        // Validate database/tenant names
        for (db in req.databases) {
            if (db.isBlank() || db.length > 64) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", "Invalid database name: $db"))
                return@post
            }
        }
        for (t in req.tenants) {
            if (t.isBlank() || t.length > 128) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", "Invalid tenant ID: $t"))
                return@post
            }
        }

        val (rawKey, record) = keyManager.createKey(
            name = req.name,
            role = role,
            databases = req.databases,
            tenants = req.tenants,
            expiresInDays = req.expiresInDays ?: ServerConfig.defaultKeyExpiryDays,
            createdBy = principal?.keyId ?: "system",
            description = req.description
        )

        call.respond(HttpStatusCode.Created, CreateKeyResponse(
            id = record.id,
            name = record.name,
            key = rawKey,
            prefix = record.keyPrefix,
            role = role.name.lowercase(),
            databases = record.databases,
            tenants = record.tenants,
            expiresAt = record.expiresAt
        ))
    }

    // GET /auth/keys — List all keys
    get("/auth/keys") {
        if (keyManager == null) { call.respond(HttpStatusCode.BadRequest, ErrorResponse("AUTH_DISABLED", "RBAC auth not enabled")); return@get }
        call.respond(keyManager.listKeys())
    }

    // GET /auth/keys/{id} — Get key details
    get("/auth/keys/{id}") {
        if (keyManager == null) { call.respond(HttpStatusCode.BadRequest, ErrorResponse("AUTH_DISABLED", "RBAC auth not enabled")); return@get }
        val id = call.parameters["id"]
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", "Missing key ID"))
            return@get
        }
        val record = keyManager.getKey(id)
        if (record == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Key not found"))
            return@get
        }
        call.respond(KeyInfoResponse(
            id = record.id,
            name = record.name,
            prefix = record.keyPrefix,
            role = record.role.name.lowercase(),
            databases = record.databases,
            tenants = record.tenants,
            createdAt = record.createdAt,
            lastUsedAt = record.lastUsedAt,
            expiresAt = record.expiresAt,
            enabled = record.enabled,
            description = record.description
        ))
    }

    // PATCH /auth/keys/{id} — Update key properties
    patch("/auth/keys/{id}") {
        if (keyManager == null) { call.respond(HttpStatusCode.BadRequest, ErrorResponse("AUTH_DISABLED", "RBAC auth not enabled")); return@patch }
        val id = call.parameters["id"]
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", "Missing key ID"))
            return@patch
        }
        val req = call.receive<UpdateKeyRequest>()
        val updated = keyManager.updateKey(id, req)
        if (updated == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Key not found"))
            return@patch
        }
        call.respond(KeyInfoResponse(
            id = updated.id,
            name = updated.name,
            prefix = updated.keyPrefix,
            role = updated.role.name.lowercase(),
            databases = updated.databases,
            tenants = updated.tenants,
            createdAt = updated.createdAt,
            lastUsedAt = updated.lastUsedAt,
            expiresAt = updated.expiresAt,
            enabled = updated.enabled,
            description = updated.description
        ))
    }

    // DELETE /auth/keys/{id} — Revoke key
    delete("/auth/keys/{id}") {
        if (keyManager == null) { call.respond(HttpStatusCode.BadRequest, ErrorResponse("AUTH_DISABLED", "RBAC auth not enabled")); return@delete }
        val id = call.parameters["id"]
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", "Missing key ID"))
            return@delete
        }

        // Prevent revoking your own key
        val principal = call.authPrincipal()
        if (principal?.keyId == id) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("VALIDATION_ERROR", "Cannot revoke your own key. Use another admin key.")
            )
            return@delete
        }

        val revoked = keyManager.revokeKey(id)
        if (!revoked) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Key not found"))
            return@delete
        }
        call.respond(mapOf("message" to "Key revoked", "id" to id))
    }

    // GET /auth/whoami — Show current key's identity and permissions
    get("/auth/whoami") {
        val principal = call.authPrincipal()
        if (principal == null) {
            // In dev/legacy mode
            call.respond(WhoAmIResponse(
                mode = ServerConfig.authMode.name.lowercase(),
                keyId = null,
                name = if (ServerConfig.authMode == AuthMode.LEGACY) "legacy-key" else "anonymous",
                role = "admin",
                permissions = Permission.entries.map { it.name.lowercase() },
                databases = emptyList(),
                tenants = emptyList()
            ))
            return@get
        }
        call.respond(WhoAmIResponse(
            mode = "rbac",
            keyId = principal.keyId,
            name = principal.name,
            role = principal.role.name.lowercase(),
            permissions = principal.permissions.map { it.name.lowercase() },
            databases = principal.databases,
            tenants = principal.tenants
        ))
    }
}

// ── DTOs ──────────────────────────────────────────────────────────────────

@Serializable
data class BootstrapRequest(
    val username: String,
    val password: String,
    val keyName: String? = null
)

@Serializable
data class AuthStatusResponse(
    val mode: String,
    val bootstrapRequired: Boolean,
    val keyCount: Int
)

@Serializable
data class WhoAmIResponse(
    val mode: String,
    val keyId: String?,
    val name: String,
    val role: String,
    val permissions: List<String>,
    val databases: List<String>,
    val tenants: List<String>
)
