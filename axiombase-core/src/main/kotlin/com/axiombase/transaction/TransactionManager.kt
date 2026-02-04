package com.axiombase.transaction

import com.axiombase.AxiomBase
import com.axiombase.core.Atom
import com.axiombase.core.Rule
import com.axiombase.persistence.WalBatchItem
import com.axiombase.persistence.WalOperation
import com.axiombase.persistence.WalData
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class TransactionManager(private val db: AxiomBase) {
    // Map of TxId -> List of pending operations
    private val transactions = ConcurrentHashMap<Long, MutableList<WalBatchItem>>()
    // Map of TxId -> TenantId (nullable for single-tenant default)
    private val txTenants = ConcurrentHashMap<Long, String?>()
    private val nextTxId = AtomicLong(1)

    fun begin(tenantId: String? = null): Long {
        val id = nextTxId.getAndIncrement()
        transactions[id] = java.util.Collections.synchronizedList(ArrayList())
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
        
        // Apply batch atomically via AxiomBase
        // AxiomBase.applyBatch(tx, tenantId)
        try {
            db.applyBatch(tx, tenantId)
        } catch (e: Exception) {
            // If commit fails (e.g. contradiction), the transaction is already removed from map.
            // Client should handle the error.
            throw e
        }
    }

    fun rollback(txId: Long) {
        transactions.remove(txId)
        txTenants.remove(txId)
    }
}
