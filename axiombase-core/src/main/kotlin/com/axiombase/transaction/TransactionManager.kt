package com.axiombase.transaction

import com.axiombase.AxiomBase
import com.axiombase.core.Atom
import com.axiombase.core.Rule
import com.axiombase.persistence.WalBatchItem
import com.axiombase.persistence.WalOperation
import com.axiombase.persistence.WalData
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory

class TransactionManager(private val db: AxiomBase) {
    private val logger = LoggerFactory.getLogger(TransactionManager::class.java)

    // Map of TxId -> List of pending operations
    private val transactions = ConcurrentHashMap<Long, MutableList<WalBatchItem>>()
    // Map of TxId -> TenantId (nullable for single-tenant default)
    private val txTenants = ConcurrentHashMap<Long, String?>()
    // Map of TxId -> creation time
    private val txCreationTimes = ConcurrentHashMap<Long, Long>()
    private val nextTxId = AtomicLong(1)

    val maxActiveTx: Int = System.getProperty("axiombase.tx.max.active")?.toIntOrNull() ?: 100
    val txTimeoutMs: Long = System.getProperty("axiombase.tx.timeout.ms")?.toLongOrNull() ?: 300_000L // 5 min

    private val reaperScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        reaperScope.launch {
            while (isActive) {
                delay(60_000) // every 60s
                reapStaleTx()
            }
        }
    }

    fun begin(tenantId: String? = null): Long {
        if (transactions.size >= maxActiveTx) {
            throw IllegalStateException("Too many active transactions (limit: $maxActiveTx)")
        }
        val id = nextTxId.getAndIncrement()
        transactions[id] = java.util.Collections.synchronizedList(ArrayList())
        txCreationTimes[id] = System.currentTimeMillis()
        if (tenantId != null) {
            txTenants[id] = tenantId
        }
        return id
    }

    fun assertFact(txId: Long, fact: Atom) {
        val tx = transactions[txId] ?: throw IllegalArgumentException("Transaction $txId not found or active")
        tx.add(WalBatchItem(WalOperation.ASSERT, WalData.FactData(fact)))
    }

    fun retractFact(txId: Long, fact: Atom) {
        val tx = transactions[txId] ?: throw IllegalArgumentException("Transaction $txId not found or active")
        tx.add(WalBatchItem(WalOperation.RETRACT, WalData.FactData(fact)))
    }

    fun assertRule(txId: Long, rule: Rule) {
        val tx = transactions[txId] ?: throw IllegalArgumentException("Transaction $txId not found or active")
        tx.add(WalBatchItem(WalOperation.ASSERT, WalData.RuleData(rule)))
    }

    fun commit(txId: Long) {
        val tx = transactions.remove(txId) ?: throw IllegalArgumentException("Transaction $txId not found or active")
        val tenantId = txTenants.remove(txId)
        txCreationTimes.remove(txId)

        db.applyBatch(tx, tenantId)
    }

    fun rollback(txId: Long) {
        transactions.remove(txId)
        txTenants.remove(txId)
        txCreationTimes.remove(txId)
    }

    fun getActiveTransactions(): Set<Long> {
        return transactions.keys.toSet()
    }

    fun getActiveTransactionCount(): Int {
        return transactions.size
    }

    private fun reapStaleTx() {
        val now = System.currentTimeMillis()
        val stale = txCreationTimes.entries.filter { (_, created) ->
            now - created > txTimeoutMs
        }.map { it.key }

        for (txId in stale) {
            logger.warn("Transaction $txId timed out after ${txTimeoutMs}ms — rolling back")
            try {
                rollback(txId)
            } catch (e: Exception) {
                logger.error("Failed to rollback stale transaction $txId: ${e.message}")
            }
        }
    }

    fun close() {
        reaperScope.cancel()
    }
}
