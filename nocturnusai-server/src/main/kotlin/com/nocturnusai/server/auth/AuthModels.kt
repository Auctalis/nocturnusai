package com.nocturnusai.server.auth

import kotlinx.serialization.Serializable

/**
 * Roles define the level of access an API key has.
 *
 * ADMIN  — full access: manage databases, tenants, keys, nuke, backup
 * WRITER — read + write: assert/retract facts, create rules, run inference, memory ops
 * READER — read only: query facts, run inference, read memory
 */
enum class Role {
    ADMIN,
    WRITER,
    READER
}

/**
 * Fine-grained permissions that can be checked at route level.
 */
enum class Permission {
    // Data operations
    FACT_READ,
    FACT_WRITE,
    RULE_READ,
    RULE_WRITE,
    INFERENCE,
    MEMORY_READ,
    MEMORY_WRITE,

    // Extraction / synthesis (LLM)
    EXTRACT,
    SYNTHESIZE,

    // Admin operations
    DATABASE_CREATE,
    DATABASE_DELETE,
    DATABASE_LIST,
    TENANT_CREATE,
    TENANT_DELETE,
    NUKE,
    BACKUP,

    // Key management
    KEY_MANAGE,

    // Transaction
    TRANSACTION;

    companion object {
        /** Permissions granted by each role */
        fun forRole(role: Role): Set<Permission> = when (role) {
            Role.ADMIN -> entries.toSet()
            Role.WRITER -> setOf(
                FACT_READ, FACT_WRITE,
                RULE_READ, RULE_WRITE,
                INFERENCE,
                MEMORY_READ, MEMORY_WRITE,
                EXTRACT, SYNTHESIZE,
                DATABASE_LIST,
                TRANSACTION
            )
            Role.READER -> setOf(
                FACT_READ,
                RULE_READ,
                INFERENCE,
                MEMORY_READ,
                SYNTHESIZE,
                DATABASE_LIST
            )
        }
    }
}

/**
 * A stored API key with its metadata.
 */
@Serializable
data class ApiKeyRecord(
    val id: String,
    val name: String,
    val keyHash: String,
    val keyPrefix: String,       // first 8 chars for identification (e.g., "axb_1a2b...")
    val role: Role,
    val databases: List<String>, // empty = all databases; ["mydb"] = only mydb
    val tenants: List<String>,   // empty = all tenants; ["t1"] = only t1
    val createdAt: Long,
    val createdBy: String,       // id of the key that created this one
    val lastUsedAt: Long? = null,
    val expiresAt: Long? = null,
    val enabled: Boolean = true,
    val description: String = ""
)

/**
 * The authenticated principal attached to a request after auth succeeds.
 */
data class AuthPrincipal(
    val keyId: String,
    val name: String,
    val role: Role,
    val permissions: Set<Permission>,
    val databases: List<String>,  // scoped databases (empty = all)
    val tenants: List<String>     // scoped tenants (empty = all)
) {
    fun hasPermission(permission: Permission): Boolean = permission in permissions

    fun canAccessDatabase(dbName: String): Boolean =
        databases.isEmpty() || dbName in databases

    fun canAccessTenant(tenantId: String): Boolean =
        tenants.isEmpty() || tenantId in tenants
}

// ── Request/Response DTOs for key management ──────────────────────────

@Serializable
data class CreateKeyRequest(
    val name: String,
    val role: String,                 // "admin", "writer", "reader"
    val databases: List<String> = emptyList(),
    val tenants: List<String> = emptyList(),
    val expiresInDays: Int? = null,
    val description: String = ""
)

@Serializable
data class CreateKeyResponse(
    val id: String,
    val name: String,
    val key: String,                  // the raw key — shown only once
    val prefix: String,
    val role: String,
    val databases: List<String>,
    val tenants: List<String>,
    val expiresAt: Long?
)

@Serializable
data class KeyInfoResponse(
    val id: String,
    val name: String,
    val prefix: String,
    val role: String,
    val databases: List<String>,
    val tenants: List<String>,
    val createdAt: Long,
    val lastUsedAt: Long?,
    val expiresAt: Long?,
    val enabled: Boolean,
    val description: String
)

@Serializable
data class UpdateKeyRequest(
    val enabled: Boolean? = null,
    val role: String? = null,
    val databases: List<String>? = null,
    val tenants: List<String>? = null,
    val description: String? = null
)
