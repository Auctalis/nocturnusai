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

package com.nocturnusai.transaction

import com.nocturnusai.NocturnusAI
import com.nocturnusai.core.Atom
import com.nocturnusai.core.Rule
import com.nocturnusai.persistence.WalBatchItem
import com.nocturnusai.persistence.WalOperation
import com.nocturnusai.persistence.WalData
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory

class TransactionManager(private val db: NocturnusAI) {
    private val logger = LoggerFactory.getLogger(TransactionManager::class.java)

    // Map of TxId -> List of pending operations
    private val transactions = ConcurrentHashMap<Long, MutableList<WalBatchItem>>()
    // Map of TxId -> TenantId (nullable for single-tenant default)
    private val txTenants = ConcurrentHashMap<Long, String?>()
    // Map of TxId -> creation time
    private val txCreationTimes = ConcurrentHashMap<Long, Long>()
    private val nextTxId = AtomicLong(1)

    val maxActiveTx: Int = System.getProperty("nocturnusai.tx.max.active")?.toIntOrNull() ?: 100
    val txTimeoutMs: Long = System.getProperty("nocturnusai.tx.timeout.ms")?.toLongOrNull() ?: 300_000L // 5 min

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
