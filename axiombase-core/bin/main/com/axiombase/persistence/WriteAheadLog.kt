package com.axiombase.persistence

import com.axiombase.core.Atom
import com.axiombase.core.Rule
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter

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
    val tenantId: String? = null
)


class WriteAheadLog(private val walFile: File) {
    private val json = Json { 
        ignoreUnknownKeys = true 
        classDiscriminator = "type"
    } 
    private var writer: PrintWriter? = null
    private var lastId: Long = 0

    init {
        walFile.parentFile?.mkdirs()
        
        if (walFile.exists()) {
             walFile.forEachLine { line ->
                 try {
                     if (line.isNotBlank()) {
                         val entry = json.decodeFromString<WalEntry>(line)
                         lastId = entry.id
                     }
                 } catch (e: Exception) {
                     // ignore
                 }
             }
        }
        
        val fileWriter = FileWriter(walFile, true)
        writer = PrintWriter(fileWriter)
    }

    @Synchronized
    fun append(op: WalOperation, data: WalData, tenantId: String? = null) {
        lastId++
        val entry = WalEntry(
            id = lastId,
            op = op,
            data = data,
            timestamp = System.currentTimeMillis(),
            tenantId = tenantId
        )
        val line = json.encodeToString(entry)
        writer?.println(line)
        writer?.flush()
    }

    fun replay(handler: (WalOperation, WalData, String?) -> Unit) {
        if (!walFile.exists()) return
        
        walFile.forEachLine { line ->
            try {
                if (line.isNotBlank()) {
                    val entry = json.decodeFromString<WalEntry>(line)
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
                             val entry = json.decodeFromString<WalEntry>(line)
                             if (entry.id >= startId) {
                                 yield(entry)
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
    }

    @Synchronized
    fun clear() {
        writer?.close()
        if (walFile.exists()) {
            walFile.delete()
        }
        walFile.createNewFile()
        val fileWriter = FileWriter(walFile, true)
        writer = PrintWriter(fileWriter)
        lastId = 0
    }
}
