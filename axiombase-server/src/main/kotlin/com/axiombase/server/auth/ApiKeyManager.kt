package com.axiombase.server.auth

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages API keys: creation, validation, persistence, and CRUD.
 *
 * Keys are stored as SHA-256 hashes — the raw key is only returned at creation time.
 * Storage is a simple JSON file in the data directory, loaded at startup.
 */
class ApiKeyManager(private val storageDir: File) {
    private val log = LoggerFactory.getLogger(ApiKeyManager::class.java)
    private val keys = ConcurrentHashMap<String, ApiKeyRecord>() // id -> record
    private val hashIndex = ConcurrentHashMap<String, String>()  // keyHash -> id (for fast lookup)
    private val keyFile = File(storageDir, "api-keys.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val random = SecureRandom()

    init {
        storageDir.mkdirs()
        load()
    }

    /**
     * Create a new API key. Returns the raw key (only time it's available).
     */
    fun createKey(
        name: String,
        role: Role,
        databases: List<String> = emptyList(),
        tenants: List<String> = emptyList(),
        expiresInDays: Int? = null,
        createdBy: String = "system",
        description: String = ""
    ): Pair<String, ApiKeyRecord> {
        val id = UUID.randomUUID().toString()
        val rawKey = generateKey()
        val hash = hashKey(rawKey)
        val prefix = rawKey.take(12) // "axb_" + 8 chars

        val now = System.currentTimeMillis()
        val expiresAt = expiresInDays?.let { now + it * 86_400_000L }

        val record = ApiKeyRecord(
            id = id,
            name = name,
            keyHash = hash,
            keyPrefix = prefix,
            role = role,
            databases = databases,
            tenants = tenants,
            createdAt = now,
            createdBy = createdBy,
            expiresAt = expiresAt,
            description = description
        )

        keys[id] = record
        hashIndex[hash] = id
        save()
        log.info("API key created: name=$name, role=$role, id=$id, prefix=$prefix")
        return Pair(rawKey, record)
    }

    /**
     * Validate a raw API key and return the authenticated principal, or null if invalid.
     */
    fun validate(rawKey: String): AuthPrincipal? {
        val hash = hashKey(rawKey)
        val id = hashIndex[hash] ?: return null
        val record = keys[id] ?: return null

        if (!record.enabled) return null

        // Check expiration
        if (record.expiresAt != null && System.currentTimeMillis() > record.expiresAt) {
            return null
        }

        // Update last used timestamp (async-safe via ConcurrentHashMap)
        keys[id] = record.copy(lastUsedAt = System.currentTimeMillis())

        return AuthPrincipal(
            keyId = record.id,
            name = record.name,
            role = record.role,
            permissions = Permission.forRole(record.role),
            databases = record.databases,
            tenants = record.tenants
        )
    }

    /**
     * List all keys (without hashes — for admin display).
     */
    fun listKeys(): List<KeyInfoResponse> = keys.values.map { it.toInfoResponse() }

    /**
     * Get a single key by ID.
     */
    fun getKey(id: String): ApiKeyRecord? = keys[id]

    /**
     * Update key properties (enable/disable, role change, scope change).
     */
    fun updateKey(id: String, update: UpdateKeyRequest): ApiKeyRecord? {
        val existing = keys[id] ?: return null
        val updated = existing.copy(
            enabled = update.enabled ?: existing.enabled,
            role = update.role?.let { Role.valueOf(it.uppercase()) } ?: existing.role,
            databases = update.databases ?: existing.databases,
            tenants = update.tenants ?: existing.tenants,
            description = update.description ?: existing.description
        )
        keys[id] = updated
        save()
        log.info("API key updated: id=$id, name=${updated.name}")
        return updated
    }

    /**
     * Revoke (delete) a key.
     */
    fun revokeKey(id: String): Boolean {
        val record = keys.remove(id) ?: return false
        hashIndex.remove(record.keyHash)
        save()
        log.info("API key revoked: id=$id, name=${record.name}")
        return true
    }

    /**
     * Check if any keys exist (used for bootstrap detection).
     */
    fun hasKeys(): Boolean = keys.isNotEmpty()

    /**
     * Count keys by role.
     */
    fun countByRole(role: Role): Int = keys.values.count { it.role == role }

    // ── Internal ──────────────────────────────────────────────────────────

    private fun generateKey(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        return "axb_${encoded}"
    }

    internal fun hashKey(rawKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(rawKey.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    @kotlinx.serialization.Serializable
    private data class KeyStore(val keys: List<ApiKeyRecord>)

    private fun save() {
        try {
            val store = KeyStore(keys.values.toList())
            keyFile.writeText(json.encodeToString(store))
        } catch (e: Exception) {
            log.error("Failed to save API keys: ${e.message}", e)
        }
    }

    private fun load() {
        if (!keyFile.exists()) return
        try {
            val store = json.decodeFromString<KeyStore>(keyFile.readText())
            for (record in store.keys) {
                keys[record.id] = record
                hashIndex[record.keyHash] = record.id
            }
            log.info("Loaded ${keys.size} API keys from ${keyFile.absolutePath}")
        } catch (e: Exception) {
            log.error("Failed to load API keys: ${e.message}", e)
        }
    }

    private fun ApiKeyRecord.toInfoResponse() = KeyInfoResponse(
        id = id,
        name = name,
        prefix = keyPrefix,
        role = role.name.lowercase(),
        databases = databases,
        tenants = tenants,
        createdAt = createdAt,
        lastUsedAt = lastUsedAt,
        expiresAt = expiresAt,
        enabled = enabled,
        description = description
    )
}
