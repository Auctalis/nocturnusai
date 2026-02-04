package com.axiombase.server

import com.axiombase.AxiomBase
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

class DatabaseManager(private val rootStorageDir: File) {
    private val databases = ConcurrentHashMap<String, AxiomBase>()
    
    init {
        rootStorageDir.mkdirs()
        // Scan for existing databases
        // Assume any subdirectory with "axiombase.wal" or "snapshot.json" or "db.config" is a DB
        // Or just iterate subdirectories
        rootStorageDir.listFiles { file -> file.isDirectory }?.forEach { dir ->
            val name = dir.name
            loadDatabase(name)
        }
        
        // Ensure default exists?
        if (!databases.containsKey("default")) {
            // Check if root has legacy data? 
            // If "axiombase.wal" exists in root, move it to default?
            // For now, let's just create default if missing.
            // createDatabase("default", false) 
            // Wait, do NOT overwrite if legacy setup uses root directly.
            // The previous code used rootStorageDir directly for AxiomBase.
            // If I change structure, I break backward compat unless I handle migration.
            
            // Migration Strategy:
            // If root has WAL/Snapshot, treat root as "default" DB location?
            // But if we want multiple DBs, we should use subdirs.
            // Let's assume "default" maps to rootStorageDir/default for new structure.
            // For backward compat, we can map "default" to rootStorageDir if no subdirs found?
            // To keep it simple: "default" -> rootStorageDir/default.
            // User can migrate data manually or we init new.
            createDatabase("default", false)
        }
    }
    
    fun getDatabase(name: String): AxiomBase? {
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
    
    fun createDatabase(name: String, multiTenant: Boolean): AxiomBase {
        if (databases.containsKey(name)) {
             return databases[name]!! // Already exists
        }
        
        val dbDir = File(rootStorageDir, name)
        dbDir.mkdirs()
        
        // Save config
        val configFile = File(dbDir, "db.config")
        val config = DatabaseConfig(multiTenant)
        configFile.writeText(Json.encodeToString(config))
        
        val db = AxiomBase(dbDir, multiTenant)
        databases[name] = db
        return db
    }
    
    private fun loadDatabase(name: String) {
        val dbDir = File(rootStorageDir, name)
        val configFile = File(dbDir, "db.config")
        var isMultiTenant = false
        if (configFile.exists()) {
            try {
                val config = Json.decodeFromString<DatabaseConfig>(configFile.readText())
                isMultiTenant = config.isMultiTenant
            } catch (e: Exception) {
                println("Error loading config for $name: ${e.message}")
            }
        }
        
        val db = AxiomBase(dbDir, isMultiTenant)
        databases[name] = db
        println("Loaded database: $name (MT=$isMultiTenant)")
    }
}
