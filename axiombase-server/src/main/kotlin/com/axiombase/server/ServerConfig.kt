package com.axiombase.server

import java.io.File

object ServerConfig {
    val port: Int = System.getenv("PORT")?.toIntOrNull() ?: 9300
    val host: String = System.getenv("HOST") ?: "0.0.0.0"
    val apiKey: String? = System.getenv("API_KEY") // If null, auth disabled (dev mode)
    val storageDir: File = System.getenv("STORAGE_DIR")?.let { File(it) } ?: File("data")
    
    // Encryption at Rest
    val encryptionKey: String? = System.getenv("ENCRYPTION_KEY") // 64 hex chars = 32 bytes AES-256

    // TLS Configuration
    val tlsEnabled: Boolean = System.getenv("TLS_ENABLED")?.toBoolean() ?: false
    val tlsPort: Int = System.getenv("TLS_PORT")?.toIntOrNull() ?: 9443
    val keystorePath: String? = System.getenv("TLS_KEYSTORE_PATH")
    val keystorePassword: String? = System.getenv("TLS_KEYSTORE_PASSWORD")
    val keyAlias: String = System.getenv("TLS_KEY_ALIAS") ?: "axiombase"
    val privateKeyPassword: String? = System.getenv("TLS_KEY_PASSWORD")

    // Replication Config
    val replicationMode: ReplicationMode = System.getenv("REPLICATION_MODE")?.let { ReplicationMode.valueOf(it.uppercase()) } ?: ReplicationMode.LEADER
    val leaderUrl: String? = System.getenv("LEADER_URL")

    // LLM / Extraction Config
    val extractionEnabled: Boolean = System.getenv("EXTRACTION_ENABLED")?.toBoolean() ?: false
}

enum class ReplicationMode {
    LEADER,
    FOLLOWER
}
