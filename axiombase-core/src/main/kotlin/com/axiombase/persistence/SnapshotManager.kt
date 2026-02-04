package com.axiombase.persistence

import com.axiombase.core.Atom
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import java.io.File

@Serializable
data class TenantSnapshotData(
    val positives: List<Atom>,
    val negatives: List<Atom>
)

@Serializable
data class SnapshotData(
    val timestamp: Long,
    val tenants: Map<String, TenantSnapshotData> = emptyMap(),
    // Legacy fields for migration
    val positives: List<Atom>? = null,
    val negatives: List<Atom>? = null
)

class SnapshotManager(private val storageDir: File) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun saveSnapshot(tenantDataMap: Map<String, TenantSnapshotData>) {
        val snapshotData = SnapshotData(
            timestamp = System.currentTimeMillis(),
            tenants = tenantDataMap
        )
        
        val tempFile = File(storageDir, "snapshot.tmp")
        tempFile.writeText(json.encodeToString(snapshotData))
        
        val finalFile = File(storageDir, "snapshot.json")
        if (finalFile.exists()) {
             val backup = File(storageDir, "snapshot.bak")
             if (backup.exists()) backup.delete()
             finalFile.renameTo(backup)
        }
        tempFile.renameTo(finalFile)
    }
    
    fun loadSnapshot(): SnapshotData? {
        val file = File(storageDir, "snapshot.json")
        if (!file.exists()) return null
        return try {
            json.decodeFromString<SnapshotData>(file.readText())
        } catch (e: Exception) {
            System.err.println("Failed to load snapshot: ${e.message}")
            return null
        }
    }
}
