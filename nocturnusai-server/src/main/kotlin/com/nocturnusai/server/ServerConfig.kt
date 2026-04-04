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

import com.nocturnusai.server.auth.AuthMode
import java.io.File

object ServerConfig {
    val port: Int = System.getenv("PORT")?.toIntOrNull() ?: 9300
    val host: String = System.getenv("HOST") ?: "0.0.0.0"
    val apiKey: String? = System.getenv("API_KEY")?.ifBlank { null } // Legacy single-key auth
    val storageDir: File = System.getenv("STORAGE_DIR")?.let { File(it) } ?: File("data")

    // Authentication mode
    // AUTH_ENABLED=true → RBAC mode (managed API keys with roles)
    // API_KEY set, AUTH_ENABLED absent → LEGACY mode (single static key)
    // Neither set → DISABLED (dev mode, no auth)
    val authEnabled: Boolean = System.getenv("AUTH_ENABLED")?.toBoolean() ?: false
    val authMode: AuthMode
        get() = when {
            authEnabled -> AuthMode.RBAC
            apiKey != null -> AuthMode.LEGACY
            else -> AuthMode.DISABLED
        }

    // Admin credentials for bootstrap (used when AUTH_ENABLED=true and no keys exist yet)
    // Change these via environment variables before exposing the server to any network.
    val adminUser: String = System.getenv("NOCTURNUSAI_ADMIN_USER") ?: "admin"
    val adminPass: String = System.getenv("NOCTURNUSAI_ADMIN_PASS") ?: "nocturnusai"
    val usingDefaultAdminCredentials: Boolean
        get() = System.getenv("NOCTURNUSAI_ADMIN_USER") == null || System.getenv("NOCTURNUSAI_ADMIN_PASS") == null

    // Default expiry for newly-created API keys (days). null = no expiry.
    // Applies to /auth/bootstrap and /auth/keys unless the caller provides expiresInDays.
    // Recommended: 365 for production.
    val defaultKeyExpiryDays: Int? = System.getenv("API_KEY_DEFAULT_EXPIRY_DAYS")?.toIntOrNull()

    // Encryption at Rest
    val encryptionKey: String? = System.getenv("ENCRYPTION_KEY")?.ifBlank { null } // 64 hex chars = 32 bytes AES-256

    // TLS Configuration
    val tlsEnabled: Boolean = System.getenv("TLS_ENABLED")?.toBoolean() ?: false
    val tlsPort: Int = System.getenv("TLS_PORT")?.toIntOrNull() ?: 9443
    val keystorePath: String? = System.getenv("TLS_KEYSTORE_PATH")
    val keystorePassword: String? = System.getenv("TLS_KEYSTORE_PASSWORD")
    val keyAlias: String = System.getenv("TLS_KEY_ALIAS") ?: "nocturnusai"
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
