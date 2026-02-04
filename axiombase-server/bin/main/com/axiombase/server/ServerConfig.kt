package com.axiombase.server

import java.io.File

object ServerConfig {
    val port: Int = System.getenv("PORT")?.toIntOrNull() ?: 9300
    val host: String = System.getenv("HOST") ?: "0.0.0.0"
    val apiKey: String? = System.getenv("API_KEY") // If null, auth disabled (dev mode)
    val storageDir: File = System.getenv("STORAGE_DIR")?.let { File(it) } ?: File("data")
    
    // Replication Config
    val replicationMode: ReplicationMode = System.getenv("REPLICATION_MODE")?.let { ReplicationMode.valueOf(it.uppercase()) } ?: ReplicationMode.LEADER
    val leaderUrl: String? = System.getenv("LEADER_URL")
}

enum class ReplicationMode {
    LEADER,
    FOLLOWER
}
