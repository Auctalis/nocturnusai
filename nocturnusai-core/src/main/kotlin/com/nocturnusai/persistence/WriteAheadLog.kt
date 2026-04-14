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
import com.nocturnusai.core.Rule
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.nio.channels.FileChannel
import java.util.zip.CRC32

@Serializable
enum class WalOperation {
    ASSERT,
    RETRACT
}

@Serializable
data class WalBatchItem(
    val op: WalOperation,
    val data: WalData // Can be FactData or RuleData, but not TransactionData (simple recursion check)
)

@Serializable
sealed class WalData {
    @Serializable
    @SerialName("fact")
    data class FactData(val atom: Atom) : WalData()

    @Serializable
    @SerialName("rule")
    data class RuleData(val rule: Rule) : WalData()

    @Serializable
    @SerialName("tx")
    data class TransactionData(val batch: List<WalBatchItem>) : WalData()
}

@Serializable
data class WalEntry(
    val id: Long,
    val op: WalOperation,
    val data: WalData,
    val timestamp: Long,
    val tenantId: String? = null,
    val checksum: Long? = null
)


class WriteAheadLog(private val walFile: File, private val encryption: EncryptionService? = null) {
    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }
    private var stream: FileOutputStream? = null
    private var channel: FileChannel? = null
    private var writer: PrintWriter? = null
    private var lastId: Long = 0

    init {
        walFile.parentFile?.mkdirs()

        if (walFile.exists()) {
             walFile.forEachLine { line ->
                 try {
                     if (line.isNotBlank()) {
                         val jsonLine = decryptLine(line)
                         val entry = json.decodeFromString<WalEntry>(jsonLine)
                         lastId = entry.id
                     }
                 } catch (e: Exception) {
                     // ignore
                 }
             }
        }

        openWriter()
    }

    private fun openWriter() {
        val fos = FileOutputStream(walFile, true)
        stream = fos
        channel = fos.channel
        writer = PrintWriter(OutputStreamWriter(fos, Charsets.UTF_8), false)
    }

    @Synchronized
    fun append(op: WalOperation, data: WalData, tenantId: String? = null) {
        lastId++
        val timestamp = System.currentTimeMillis()
        val crc = computeChecksum(lastId, op, data, timestamp, tenantId)
        val entry = WalEntry(
            id = lastId,
            op = op,
            data = data,
            timestamp = timestamp,
            tenantId = tenantId,
            checksum = crc
        )
        val jsonLine = json.encodeToString(entry)
        val outputLine = if (encryption != null) encryption.encryptString(jsonLine) else jsonLine
        writer?.println(outputLine)
        flush()
    }

    @Synchronized
    fun flush() {
        writer?.flush()
        channel?.force(true)
    }

    fun replay(handler: (WalOperation, WalData, String?) -> Unit) {
        if (!walFile.exists()) return

        walFile.forEachLine { line ->
            try {
                if (line.isNotBlank()) {
                    val jsonLine = decryptLine(line)
                    val entry = json.decodeFromString<WalEntry>(jsonLine)
                    if (!verifyChecksum(entry)) {
                        System.err.println("WAL Replay Warning: checksum mismatch, skipping entry ${entry.id}")
                        return@forEachLine
                    }
                    handler(entry.op, entry.data, entry.tenantId)
                }
            } catch (e: Exception) {
                System.err.println("WAL Replay Warning: skipping bad line: $line")
            }
        }
    }

    fun readFrom(startId: Long): Sequence<WalEntry> {
        if (!walFile.exists()) return emptySequence()

        return sequence {
             walFile.useLines { lines ->
                 lines.forEach { line ->
                     try {
                         if (line.isNotBlank()) {
                             val jsonLine = decryptLine(line)
                             val entry = json.decodeFromString<WalEntry>(jsonLine)
                             if (entry.id >= startId) {
                                 if (!verifyChecksum(entry)) {
                                     System.err.println("WAL Read Warning: checksum mismatch, skipping entry ${entry.id}")
                                 } else {
                                     yield(entry)
                                 }
                             }
                         }
                     } catch (e: Exception) {
                         // skip bad lines
                     }
                 }
             }
        }
    }

    fun close() {
        writer?.close()
        channel?.close()
        stream?.close()
    }

    @Synchronized
    fun clear() {
        writer?.close()
        channel?.close()
        stream?.close()
        if (walFile.exists()) {
            walFile.delete()
        }
        walFile.createNewFile()
        openWriter()
        lastId = 0
    }

    private fun computeChecksum(id: Long, op: WalOperation, data: WalData, timestamp: Long, tenantId: String?): Long {
        val crc = CRC32()
        val content = "$id|$op|${json.encodeToString(data)}|$timestamp|${tenantId ?: ""}"
        crc.update(content.toByteArray(Charsets.UTF_8))
        return crc.value
    }

    private fun verifyChecksum(entry: WalEntry): Boolean {
        if (entry.checksum == null) return true // old entries without checksum pass
        val expected = computeChecksum(entry.id, entry.op, entry.data, entry.timestamp, entry.tenantId)
        return entry.checksum == expected
    }

    private fun decryptLine(line: String): String {
        if (encryption == null) return line
        return try {
            encryption.decryptString(line)
        } catch (e: Exception) {
            // When encryption is configured, a line that fails to decrypt is suspicious:
            // it was either written in a different key (corrupt WAL), or deliberately
            // injected as plaintext by someone with filesystem access. In strict mode
            // (STORAGE_STRICT_DECRYPT=true — default ON when encryption is configured,
            // opt-out with explicit "false") we refuse to accept the fallback and the
            // caller skips the line as corrupt rather than replaying it verbatim.
            if (strictDecrypt) throw e
            line // legacy plaintext line — only in permissive mode
        }
    }

    companion object {
        // Default to STRICT whenever encryption is configured. Operators with a
        // mixed plaintext/encrypted legacy WAL can set STORAGE_STRICT_DECRYPT=false
        // during a one-off migration window.
        private val strictDecrypt: Boolean =
            System.getenv("STORAGE_STRICT_DECRYPT")?.toBoolean() ?: true
    }
}
