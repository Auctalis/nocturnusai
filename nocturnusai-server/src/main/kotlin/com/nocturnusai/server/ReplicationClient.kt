package com.nocturnusai.server

import com.nocturnusai.NocturnusAI
import com.nocturnusai.persistence.WalEntry
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import java.io.File
import io.ktor.http.*

class ReplicationClient(private val db: NocturnusAI, private val leaderUrl: String) {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }
    
    private var lastWalId: Long = 0
    private var isRunning = true
    
    @OptIn(DelicateCoroutinesApi::class)
    fun start() {
        GlobalScope.launch {
            println("Starting Replication Client (Following $leaderUrl)...")
            
            // 1. Initial Sync (if needed)
            // Ideally we check if we have data or if we should pull snapshot.
            // For now, let's assume we pull snapshot if our WAL is empty? 
            // Or maybe we always try to catch up from 0.
            
            // TODO: Smart sync. For now, we just poll WAL from where we left off.
            // If we are starting fresh, we should request snapshot first?
            // Simple approach: Poll WAL. If leader says "too old", we might need snapshot.
            // But for this simplified implementation, let's just assume we start polling.
            
            while (isRunning) {
                try {
                    poll()
                } catch (e: Exception) {
                    System.err.println("Replication Poll Error: ${e.message}")
                }
                delay(1000) // Poll every second
            }
        }
    }
    
    private suspend fun poll() {
        // GET /replication/wal?since=$lastWalId
        val response = client.get("$leaderUrl/replication/wal") {
            parameter("since", lastWalId)
            if (ServerConfig.apiKey != null) {
                header("X-API-Key", ServerConfig.apiKey) 
            }
        }
        
        if (response.status.value == 200) {
            val text = response.bodyAsText()
            val lines: List<String> = text.lines()
            val entries = lines.filter { it.isNotBlank() }
                .map { Json.decodeFromString<WalEntry>(it) }
                .sortedBy { it.id }
            
            if (entries.isNotEmpty()) {
                println("Replicating ${entries.size} entries from Leader...")
                // Apply batch
                db.applyReplicationBatch(entries)
                
                // Update cursor
                lastWalId = entries.maxOf { it.id }
            }
        }
    }
    
    fun stop() {
        isRunning = false
        client.close()
    }
}
