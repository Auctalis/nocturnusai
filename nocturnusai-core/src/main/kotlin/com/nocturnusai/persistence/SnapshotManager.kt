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

package com.nocturnusai.persistence

import com.nocturnusai.core.Atom
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

class SnapshotManager(private val storageDir: File, private val encryption: EncryptionService? = null) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun saveSnapshot(tenantDataMap: Map<String, TenantSnapshotData>) {
        val snapshotData = SnapshotData(
            timestamp = System.currentTimeMillis(),
            tenants = tenantDataMap
        )

        val jsonText = json.encodeToString(snapshotData)
        val outputText = if (encryption != null) encryption.encryptString(jsonText) else jsonText

        val tempFile = File(storageDir, "snapshot.tmp")
        tempFile.writeText(outputText)

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
            val rawText = file.readText()
            val jsonText = if (encryption != null) {
                try { encryption.decryptString(rawText) } catch (_: Exception) { rawText }
            } else { rawText }
            json.decodeFromString<SnapshotData>(jsonText)
        } catch (e: Exception) {
            System.err.println("Failed to load snapshot: ${e.message}")
            return null
        }
    }
}
