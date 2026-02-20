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

import com.nocturnusai.NocturnusAI
import com.nocturnusai.extraction.FactExtractor
import com.nocturnusai.extraction.RuleExtractor
import com.nocturnusai.persistence.EncryptionService
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

@Serializable
data class DatabaseConfig(
    val isMultiTenant: Boolean = false
)

class DatabaseManager(
    private val rootStorageDir: File,
    private val factExtractor: FactExtractor? = null,
    private val ruleExtractor: RuleExtractor? = null
) {
    private val databases = ConcurrentHashMap<String, NocturnusAI>()
    private val encryption: EncryptionService? = ServerConfig.encryptionKey?.let { EncryptionService(it) }
    
    init {
        rootStorageDir.mkdirs()
        // Scan for existing databases
        // Assume any subdirectory with "nocturnusai.wal" or "snapshot.json" or "db.config" is a DB
        // Or just iterate subdirectories
        rootStorageDir.listFiles { file -> file.isDirectory }?.forEach { dir ->
            val name = dir.name
            loadDatabase(name)
        }
        
        // Ensure default exists?
        if (!databases.containsKey("default")) {
            // Check if root has legacy data? 
            // If "nocturnusai.wal" exists in root, move it to default?
            // For now, let's just create default if missing.
            // createDatabase("default", false) 
            // Wait, do NOT overwrite if legacy setup uses root directly.
            // The previous code used rootStorageDir directly for NocturnusAI.
            // If I change structure, I break backward compat unless I handle migration.
            
            // Migration Strategy:
            // If root has WAL/Snapshot, treat root as "default" DB location?
            // But if we want multiple DBs, we should use subdirs.
            // Let's assume "default" maps to rootStorageDir/default for new structure.
            // For backward compat, we can map "default" to rootStorageDir if no subdirs found?
            // To keep it simple: "default" -> rootStorageDir/default.
            // User can migrate data manually or we init new.
            createDatabase("default", true)
        }
    }
    
    fun getDatabase(name: String): NocturnusAI? {
        return databases[name]
    }
    
    @Serializable
    data class DbInfo(val name: String, val isMultiTenant: Boolean)

    fun getDatabases(): List<DbInfo> {
        return databases.map { (name, db) -> 
            DbInfo(name, db.isMultiTenant) 
        }
    }
    
    fun getDatabaseNames(): Set<String> {
        return databases.keys
    }
    
    fun createDatabase(name: String, @Suppress("UNUSED_PARAMETER") multiTenant: Boolean = true): NocturnusAI {
        if (databases.containsKey(name)) {
             return databases[name]!! // Already exists
        }

        val dbDir = File(rootStorageDir, name)
        dbDir.mkdirs()

        // Save config
        val configFile = File(dbDir, "db.config")
        val config = DatabaseConfig(true)
        configFile.writeText(Json.encodeToString(config))
        
        val db = NocturnusAI(dbDir, true, dbName = name, encryption = encryption, factExtractor = factExtractor, ruleExtractor = ruleExtractor)
        if (!db.getRegisteredTenants().contains("default")) {
            db.createTenant("default")
        }
        databases[name] = db
        return db
    }

    fun deleteDatabase(name: String) {
        if (!databases.containsKey(name)) return
        if (name == "default") throw IllegalArgumentException("Cannot delete default database")
        
        // Remove from map
        databases.remove(name)
        
        // Delete directory
        val dbDir = File(rootStorageDir, name)
        dbDir.deleteRecursively()
    }
    
    fun close() {
        databases.forEach { (name, db) ->
            try {
                db.shutdownGracefully()
            } catch (e: Exception) {
                println("Error shutting down database '$name': ${e.message}")
            }
        }
    }

    private fun loadDatabase(name: String) {
        val dbDir = File(rootStorageDir, name)
        val configFile = File(dbDir, "db.config")
        // var isMultiTenant = false 
        // Always force true
        val isMultiTenant = true
        
        // We still read config just to migrate or ensure it's valid JSON if needed?
        // But we ignore the value.
         if (configFile.exists()) {
            try {
                // Just read to validate? Or update?
                // val config = Json.decodeFromString<DatabaseConfig>(configFile.readText())
            } catch (e: Exception) {
                println("Error loading config for $name: ${e.message}")
            }
        }
        
        val db = NocturnusAI(dbDir, isMultiTenant, dbName = name, encryption = encryption, factExtractor = factExtractor, ruleExtractor = ruleExtractor)
        if (isMultiTenant && !db.getRegisteredTenants().contains("default")) {
            db.createTenant("default")
        }
        databases[name] = db
        println("Loaded database: $name (MT=$isMultiTenant)")
    }
}
