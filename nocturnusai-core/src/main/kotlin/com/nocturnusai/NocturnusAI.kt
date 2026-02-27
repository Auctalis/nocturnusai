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

package com.nocturnusai

import com.nocturnusai.core.*
import com.nocturnusai.inference.BackwardChainer
import com.nocturnusai.inference.ReteEngine
import com.nocturnusai.inference.Unifier
import com.nocturnusai.logic.ConsistencyGuard
import com.nocturnusai.logic.ProvenanceTracker
import com.nocturnusai.parser.Command
import com.nocturnusai.parser.Parser
import com.nocturnusai.parser.Tokenizer
import com.nocturnusai.storage.Hexastore
import com.nocturnusai.persistence.WriteAheadLog
import com.nocturnusai.persistence.WalOperation
import com.nocturnusai.persistence.WalData
import com.nocturnusai.persistence.WalBatchItem
import com.nocturnusai.persistence.EncryptionService
import com.nocturnusai.persistence.SnapshotManager
import com.nocturnusai.persistence.TenantSnapshotData
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*

import com.nocturnusai.extraction.ExtractedFact
import com.nocturnusai.extraction.ExtractedRule
import com.nocturnusai.extraction.FactExtractor
import com.nocturnusai.extraction.RuleExtractor
import com.nocturnusai.memory.*
import com.nocturnusai.storage.AggregateOp
import com.nocturnusai.testing.TestRunner
import com.nocturnusai.transaction.TransactionManager

class TenantNotFoundException(val tenantId: String) : RuntimeException("Tenant '$tenantId' not found")

/** Result of a bulk-assert operation. */
data class BulkResult(
    val asserted: Int,
    val failed: Int,
    val errors: List<String>
)

/** Result of a pattern-based retract operation. */
data class RetractResult(
    val retracted: Int,
    val atoms: List<Atom>
)

class NocturnusAI(
    val storageDir: File = File("data"),
    val isMultiTenant: Boolean = true,
    val dbName: String = "default",
    val encryption: EncryptionService? = null,
    val factExtractor: FactExtractor? = null,
    val ruleExtractor: RuleExtractor? = null,
    /** Database-level default conflict strategy. Individual requests can override via assertFact(). */
    val defaultConflictStrategy: com.nocturnusai.core.ConflictStrategy = com.nocturnusai.core.ConflictStrategy.REJECT
) {

    private val logger = org.slf4j.LoggerFactory.getLogger(NocturnusAI::class.java)

    private val contexts = ConcurrentHashMap<String, LogicContext>()

    private val wal = WriteAheadLog(File(storageDir, "nocturnusai.wal"), encryption)
    private val snapshotManager = SnapshotManager(storageDir, encryption)

    val transactionManager = TransactionManager(this)

    @Volatile var isShuttingDown = false
        private set

    private val snapshotScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val snapshotIntervalMs: Long = System.getProperty("nocturnusai.snapshot.interval.ms")?.toLongOrNull() ?: 300_000L // 5 min

    private fun getContext(tenantId: String? = null): LogicContext {
        val key = tenantId ?: "default"
        if (isMultiTenant && key != "default" && !tenants.contains(key)) {
            throw TenantNotFoundException(key)
        }
        return contexts.getOrPut(key) { LogicContext() }
    }

    // Helper for internal use where we know we want a specific context
    private fun getContextByKey(key: String): LogicContext {
        return contexts.getOrPut(key) { LogicContext() }
    }

    private val tenants = ConcurrentHashMap.newKeySet<String>()
    private val tenantsFile = File(storageDir, "tenants.json")

    init {
        // Load tenants
        if (tenantsFile.exists()) {
            try {
                val rawText = tenantsFile.readText()
                val jsonText = if (encryption != null) {
                    try { encryption.decryptString(rawText) } catch (_: Exception) { rawText }
                } else { rawText }
                val list = Json.decodeFromString<List<String>>(jsonText)
                tenants.addAll(list)
                println("Loaded ${tenants.size} registered tenants.")
            } catch (e: Exception) {
                println("Error loading tenants.json: ${e.message}")
            }
        }

        // 1. Load Snapshot
        val snapshot = snapshotManager.loadSnapshot()
        if (snapshot != null) {
            println("Restoring snapshot...")

            // Legacy Migration
            if (!snapshot.positives.isNullOrEmpty() || !snapshot.negatives.isNullOrEmpty()) {
                val ctx = getContextByKey("default")
                snapshot.positives?.forEach {
                    ctx.store.add(it)
                    ctx.rete.onFactAsserted(it)
                }
                snapshot.negatives?.forEach {
                    ctx.negativeStore.add(it)
                }
                println("Restored legacy snapshot to default context.")
            }

            // Tenants
            snapshot.tenants.forEach { (tid, data) ->
                val ctx = getContextByKey(tid)
                // Register if found in snapshot but not in list (recovery)
                if (isMultiTenant && !tenants.contains(tid)) {
                    tenants.add(tid)
                }

                data.positives.forEach {
                    ctx.store.add(it)
                    ctx.rete.onFactAsserted(it)
                }
                data.negatives.forEach {
                    ctx.negativeStore.add(it)
                }
                println("Restored tenant '$tid': ${data.positives.size} pos, ${data.negatives.size} neg.")
            }
            if (true) saveTenants()
        }

        // 2. Replay WAL on startup
        wal.replay { op, data, tenantId ->
            val targetKey = tenantId ?: "default"
            val ctx = getContextByKey(targetKey)

            when (data) {
                is WalData.FactData -> {
                    when (op) {
                        WalOperation.ASSERT -> internalAssertFact(ctx, data.atom, logging = false, tenantId = targetKey)
                        WalOperation.RETRACT -> internalRetractFact(ctx, data.atom, logging = false, tenantId = targetKey)
                    }
                }
                is WalData.RuleData -> {
                    if (op == WalOperation.ASSERT) {
                        internalAssertRule(ctx, data.rule, logging = false, tenantId = targetKey)
                    }
                }
                is WalData.TransactionData -> {
                    // Replay batch
                    data.batch.forEach { item ->
                        when (val itemData = item.data) {
                            is WalData.FactData -> {
                                when (item.op) {
                                    WalOperation.ASSERT -> internalAssertFact(ctx, itemData.atom, logging = false, tenantId = targetKey)
                                    WalOperation.RETRACT -> internalRetractFact(ctx, itemData.atom, logging = false, tenantId = targetKey)
                                }
                            }
                            is WalData.RuleData -> {
                                if (item.op == WalOperation.ASSERT) {
                                    internalAssertRule(ctx, itemData.rule, logging = false, tenantId = targetKey)
                                }
                            }
                            else -> {}
                        }
                    }
                }
            }
        }

        // 3. Start periodic snapshot coroutine
        if (snapshotIntervalMs > 0) {
            snapshotScope.launch {
                while (isActive) {
                    delay(snapshotIntervalMs)
                    try {
                        logger.info("Auto-snapshot triggered for database '$dbName'")
                        createSnapshot()
                    } catch (e: Exception) {
                        logger.error("Auto-snapshot failed for database '$dbName': ${e.message}")
                    }
                }
            }
        }

        // 4. Register JVM shutdown hook
        Runtime.getRuntime().addShutdownHook(Thread {
            shutdownGracefully()
        })
    }


    // Public API
    fun assertFact(
        fact: Atom,
        tenantId: String? = null,
        scope: String? = null,
        conflictStrategy: com.nocturnusai.core.ConflictStrategy = defaultConflictStrategy
    ) {
        val ctx = getContext(tenantId)
        val limitTenant = tenantId ?: "default"

        // If fact itself has a scope, respect it, otherwise apply the passed scope
        var finalFact = if (fact.scope == null && scope != null) fact.copy(scope = scope) else fact

        // Auto-stamp createdAt if not already set
        if (finalFact.createdAt == null) {
            finalFact = finalFact.copy(createdAt = System.currentTimeMillis())
        }
        // Auto-set validFrom to now if not specified
        if (finalFact.validFrom == null) {
            finalFact = finalFact.copy(validFrom = finalFact.createdAt)
        }

        internalAssertFact(ctx, finalFact, logging = true, tenantId = limitTenant, conflictStrategy = conflictStrategy)
    }

    private fun internalAssertFact(
        ctx: LogicContext,
        fact: Atom,
        logging: Boolean,
        tenantId: String?,
        conflictStrategy: com.nocturnusai.core.ConflictStrategy = defaultConflictStrategy
    ) {
        // 0. Enforce logical consistency within context AND scope.
        // Contradiction: asserting A when !A already exists in the same scope (or vice versa).

        val oppositeFact = fact.copy(truthVal = !fact.truthVal)
        val opposingAtoms = ctx.store.match(oppositeFact, scope = fact.scope)
            .filter { it.predicate == oppositeFact.predicate && it.args == oppositeFact.args }
            .toList()

        if (opposingAtoms.isNotEmpty()) {
            when (conflictStrategy) {
                com.nocturnusai.core.ConflictStrategy.REJECT -> {
                    if (!logging) {
                        // Replay: silently skip contradictions to allow WAL recovery
                        return
                    }
                    throw IllegalArgumentException("Contradiction detected: Cannot assert $fact because its negation exists in the same scope.")
                }
                com.nocturnusai.core.ConflictStrategy.NEWEST_WINS -> {
                    // Retract the existing contradictory fact(s), then assert the new one below
                    for (old in opposingAtoms) {
                        internalRetractFact(ctx, old, logging = logging, tenantId = tenantId)
                    }
                }
                com.nocturnusai.core.ConflictStrategy.CONFIDENCE -> {
                    // Keep the higher-confidence fact; new fact wins on tie or when confidence is null
                    val existingMaxConfidence = opposingAtoms.mapNotNull { it.confidence }.maxOrNull()
                    val newConfidence = fact.confidence
                    if (existingMaxConfidence != null && newConfidence != null && existingMaxConfidence > newConfidence) {
                        // Existing fact has strictly higher confidence — discard the new one
                        return
                    }
                    // Otherwise new fact wins: retract old, assert new below
                    for (old in opposingAtoms) {
                        internalRetractFact(ctx, old, logging = logging, tenantId = tenantId)
                    }
                }
                com.nocturnusai.core.ConflictStrategy.KEEP_BOTH -> {
                    // Skip contradiction check — fall through and assert both
                }
            }
        }

        // 1. Check external constraints
        if (fact.truthVal) {
            try {
                ctx.consistencyGuard.check(fact)
            } catch (e: Exception) {
                if (!logging) return
                throw e
            }
        }

        // 2. Log to WAL
        if (logging) {
            logger.info("OP: ASSERT_FACT [DB: {}, TENANT: {}, SCOPE: {}] {}", dbName, tenantId ?: "default", fact.scope ?: "global", fact)
            wal.append(WalOperation.ASSERT, WalData.FactData(fact), tenantId = tenantId)
        }

        // 3. Add to store
        // Unified Storage: All facts (positive or negative) go to ctx.store
        ctx.store.add(fact)
        ctx.rete.onFactAsserted(fact)

        // 4. Notify memory manager (salience tracking + event bus)
        ctx.memoryManager.onFactAsserted(fact, tenantId)
    }

    fun retractFact(fact: Atom, tenantId: String? = null, scope: String? = null) {
        val ctx = getContext(tenantId)
        val limitTenant = tenantId ?: "default"
        val finalFact = if (fact.scope == null && scope != null) fact.copy(scope = scope) else fact
        internalRetractFact(ctx, finalFact, logging = true, tenantId = limitTenant)
    }

    private fun internalRetractFact(ctx: LogicContext, fact: Atom, logging: Boolean, tenantId: String?) {
        if (logging) {
            logger.info("OP: RETRACT_FACT [DB: {}, TENANT: {}, SCOPE: {}] {}", dbName, tenantId ?: "default", fact.scope ?: "global", fact)
            wal.append(WalOperation.RETRACT, WalData.FactData(fact), tenantId = tenantId)
        }

        if (fact.truthVal) {
             val toDelete = ctx.tracker.retract(fact)
             for (deadFact in toDelete) {
                 ctx.store.delete(deadFact)
             }
        }
        ctx.store.delete(fact)

        // Notify memory manager
        ctx.memoryManager.onFactRetracted(fact, tenantId)
    }

    fun assertRule(rule: Rule, tenantId: String? = null, scope: String? = null) {
        val ctx = getContext(tenantId)
        val limitTenant = tenantId ?: "default"
        val finalRule = if (rule.scope == null && scope != null) rule.copy(scope = scope) else rule
        internalAssertRule(ctx, finalRule, logging = true, tenantId = limitTenant)
    }

    private fun internalAssertRule(ctx: LogicContext, rule: Rule, logging: Boolean, tenantId: String?) {
        if (logging) {
             logger.info("OP: ASSERT_RULE [DB: {}, TENANT: {}, SCOPE: {}] {}", dbName, tenantId ?: "default", rule.scope ?: "global", rule)
             wal.append(WalOperation.ASSERT, WalData.RuleData(rule), tenantId = tenantId)
        }
        ctx.rules.add(rule)
        ctx.rete.addRule(rule)

        // Notify memory manager
        ctx.memoryManager.onRuleAsserted(rule, tenantId)
    }

    fun query(
        pattern: Atom,
        tenantId: String? = null,
        scope: String? = null,
        minConfidence: Double? = null
    ): Sequence<Atom> {
        val ctx = getContext(tenantId)
        val results = ctx.store.match(pattern, scope = scope)
        return if (minConfidence == null) results
        else results.filter { it.confidence == null || it.confidence >= minConfidence }
    }

    // --- Aggregation API ---

    /**
     * Count facts matching [pattern] in [tenantId]'s store, optionally filtered by [scope].
     */
    fun countFacts(pattern: Atom, tenantId: String, scope: String? = null): Int {
        val ctx = getContext(tenantId)
        return ctx.store.count(pattern, scope)
    }

    /**
     * Apply [op] over the numeric value at [argIndex] for all facts matching [pattern]
     * in [tenantId]'s store, optionally filtered by [scope].
     */
    fun aggregateFacts(pattern: Atom, argIndex: Int, op: AggregateOp, tenantId: String, scope: String? = null): Double? {
        val ctx = getContext(tenantId)
        return ctx.store.aggregate(pattern, argIndex, op, scope)
    }

    // --- Bulk Operations API ---

    /**
     * Assert all [atoms] for [tenantId].  Non-all-or-nothing: each atom is attempted
     * independently.  Contradictions and constraint violations are collected as errors
     * rather than aborting the entire batch.
     */
    fun bulkAssertFacts(atoms: List<Atom>, tenantId: String): BulkResult {
        val ctx = getContext(tenantId)
        val limitTenant = tenantId
        var asserted = 0
        var failed = 0
        val errors = mutableListOf<String>()

        for (atom in atoms) {
            var finalAtom = atom
            if (finalAtom.createdAt == null) {
                finalAtom = finalAtom.copy(createdAt = System.currentTimeMillis())
            }
            if (finalAtom.validFrom == null) {
                finalAtom = finalAtom.copy(validFrom = finalAtom.createdAt)
            }
            try {
                internalAssertFact(ctx, finalAtom, logging = true, tenantId = limitTenant)
                asserted++
            } catch (e: Exception) {
                failed++
                errors.add("${atom.predicate}(${atom.args.joinToString(",")}): ${e.message}")
            }
        }

        return BulkResult(asserted, failed, errors)
    }

    /**
     * Retract all facts that match [pattern] in [tenantId]'s store, optionally
     * filtered by [scope].  Returns the count and list of retracted atoms.
     */
    fun retractByPattern(pattern: Atom, tenantId: String, scope: String? = null): RetractResult {
        val ctx = getContext(tenantId)
        val limitTenant = tenantId
        val matches = ctx.store.match(pattern, scope).toList()
        for (atom in matches) {
            internalRetractFact(ctx, atom, logging = true, tenantId = limitTenant)
        }
        return RetractResult(matches.size, matches)
    }

    fun infer(
        pattern: Atom,
        tenantId: String? = null,
        minConfidence: Double? = null
    ): Sequence<Atom> {
        val ctx = getContext(tenantId)
        val results = ctx.backwardChainer.solve(pattern)
        return if (minConfidence == null) results
        else results.filter { it.confidence == null || it.confidence >= minConfidence }
    }

    fun inferWithProof(pattern: Atom, tenantId: String? = null): Sequence<ProofTree> {
        val ctx = getContext(tenantId)
        return ctx.backwardChainer.solveWithProof(pattern)
    }

    private fun formatProofTree(node: ProofNode, indent: String): String {
        val sb = StringBuilder()
        when (val step = node.step) {
            is ProofStep.FactMatch -> {
                sb.append("${indent}FACT: ${step.fact}\n")
            }
            is ProofStep.RuleApplication -> {
                sb.append("${indent}RULE: ${step.rule}\n")
                for (bodyProof in step.bodyProofs) {
                    sb.append(formatProofTree(bodyProof, "$indent  "))
                }
            }
        }
        return sb.toString()
    }

    fun getRules(tenantId: String? = null, scope: String? = null): List<Rule> {
        val ctx = getContext(tenantId)
        return if (scope == null) ctx.rules.toList()
        else ctx.rules.filter { it.scope == scope }
    }

    /** Get all facts in the knowledge base for a tenant, optionally filtered by scope. */
    fun getAllFacts(tenantId: String? = null, scope: String? = null): Sequence<Atom> {
        val ctx = getContext(tenantId)
        val allAtoms = ctx.store.getAllAtoms()
        return if (scope == null) allAtoms
        else allAtoms.filter { it.scope == scope }
    }

    // --- Agent Memory API ---

    /** Query facts that were valid at a specific point in time. */
    fun queryAtTime(pattern: Atom, timestamp: Long, tenantId: String? = null, scope: String? = null): List<Atom> {
        val ctx = getContext(tenantId)
        return ctx.memoryManager.queryAtTime(ctx.store, pattern, timestamp, scope)
    }

    /** Query facts ranked by salience (most relevant first). */
    fun queryWithSalience(
        pattern: Atom,
        tenantId: String? = null,
        scope: String? = null,
        limit: Int = 50,
        minSalience: Double = 0.0
    ): List<ScoredAtom> {
        val ctx = getContext(tenantId)
        return ctx.memoryManager.queryWithSalience(ctx.store, pattern, scope, limit, minSalience)
    }

    /** Build an optimal context window for an agent step. */
    fun buildContextWindow(
        tenantId: String? = null,
        scope: String? = null,
        maxFacts: Int = 100,
        minSalience: Double = 0.0,
        predicates: List<String>? = null
    ): ContextWindow {
        val ctx = getContext(tenantId)
        return ctx.memoryManager.buildContextWindow(ctx.store, scope, maxFacts, minSalience, predicates)
    }

    /** Run memory consolidation (compress episodic patterns into semantic facts). */
    fun runConsolidation(tenantId: String? = null): ConsolidationResult {
        val ctx = getContext(tenantId)
        val limitTenant = tenantId ?: "default"
        return ctx.memoryManager.consolidate(
            store = ctx.store,
            retractConsolidated = { atom -> internalRetractFact(ctx, atom, logging = true, tenantId = limitTenant) },
            assertConsolidated = { atom -> internalAssertFact(ctx, atom, logging = true, tenantId = limitTenant) },
            tenantId = limitTenant
        )
    }

    /** Run decay: expire TTL'd facts, evict low-salience facts if over capacity. */
    fun runDecay(tenantId: String? = null, threshold: Double? = null): DecayResult {
        val ctx = getContext(tenantId)
        val limitTenant = tenantId ?: "default"
        return ctx.memoryManager.runDecay(
            store = ctx.store,
            retractFact = { atom -> internalRetractFact(ctx, atom, logging = true, tenantId = limitTenant) },
            tenantId = limitTenant,
            forceThreshold = threshold
        )
    }

    /** Set explicit salience priority for a fact (agent can boost/demote). */
    fun setSaliencePriority(fact: Atom, priority: Double, tenantId: String? = null) {
        val ctx = getContext(tenantId)
        ctx.memoryManager.salienceTracker.setPriority(fact, priority)
    }

    /** Subscribe to knowledge change events. Returns subscription ID. */
    fun subscribe(
        predicatePattern: String? = null,
        eventTypes: Set<String> = setOf("fact_asserted", "fact_retracted"),
        tenantId: String? = null,
        callback: (KnowledgeEvent) -> Unit
    ): String {
        val ctx = getContext(tenantId)
        return ctx.memoryManager.eventBus.subscribe(predicatePattern, eventTypes, tenantId, callback)
    }

    /** Unsubscribe from knowledge change events. */
    fun unsubscribe(subscriptionId: String, tenantId: String? = null) {
        val ctx = getContext(tenantId)
        ctx.memoryManager.eventBus.unsubscribe(subscriptionId)
    }

    /** Get recent events since a given event ID. */
    fun getEventsSince(sinceEventId: Long, tenantId: String? = null): List<KnowledgeEvent> {
        val ctx = getContext(tenantId)
        return ctx.memoryManager.eventBus.getEventsSince(sinceEventId)
    }

    /** Get the memory manager for a tenant (for direct access to advanced features). */
    fun getMemoryManager(tenantId: String? = null): MemoryManager {
        return getContext(tenantId).memoryManager
    }

    // --- Tenant Management ---
    fun createTenant(tenantId: String) {
        if (tenants.contains(tenantId)) return // Already exists
        tenants.add(tenantId)
        saveTenants()
        getContext(tenantId)
    }

    fun deleteTenant(tenantId: String) {
        tenants.remove(tenantId)
        contexts.remove(tenantId)
        saveTenants()
    }

    fun getRegisteredTenants(): Set<String> {
        return tenants.toSet()
    }

    private fun saveTenants() {
        try {
            val jsonText = Json.encodeToString(tenants.toList())
            val outputText = if (encryption != null) encryption.encryptString(jsonText) else jsonText
            tenantsFile.writeText(outputText)
        } catch (e: Exception) {
            println("Error saving tenants: ${e.message}")
        }
    }

    // --- Nuke Operations ---

    /**
     * Clears all facts and rules for a specific tenant (or default context).
     * Does NOT delete the tenant registration, just wipes its data.
     */
    fun nukeTenant(tenantId: String? = null) {
        val key = tenantId ?: "default"
        logger.warn("NUKE: Clearing all data for tenant '$key' in database '$dbName'")

        // Replace the context with a fresh one
        contexts[key] = LogicContext()

        createSnapshot()
    }

    /**
     * Clears ALL data across ALL tenants in this database.
     * Nuclear option - use with extreme caution.
     */
    fun nukeDatabase() {
        logger.warn("NUKE: Clearing ALL data for database '$dbName' (${contexts.size} tenants)")

        // Clear all contexts
        contexts.clear()

        // Reinitialize default context
        contexts["default"] = LogicContext()

        // Clear WAL and create fresh snapshot
        wal.clear()
        createSnapshot()
    }

    @Synchronized
    fun createSnapshot() {
        // Collect all data
        val map = HashMap<String, TenantSnapshotData>()

        contexts.forEach { (tid, ctx) ->
            map[tid] = TenantSnapshotData(
                positives = ctx.store.getAllAtoms().toList(),
                negatives = ctx.negativeStore.getAllAtoms().toList()
            )
        }

        snapshotManager.saveSnapshot(map)
        wal.clear()
        println("Snapshot created. WAL cleared.")
    }

    @Synchronized
    fun applyBatch(items: List<WalBatchItem>, tenantId: String? = null) {
        if (items.isEmpty()) return
        val ctx = getContext(tenantId)
        val limitTenant = tenantId ?: "default"

        // 1. Validate Phase
        for (item in items) {
             val data = item.data
             if (data is WalData.FactData && item.op == WalOperation.ASSERT) {
                 val fact = data.atom
                 if (fact.truthVal) {
                      val oppositeExists = ctx.negativeStore.match(fact).any { it.predicate == fact.predicate && it.args == fact.args }
                      if (oppositeExists) throw IllegalArgumentException("Transaction Aborted: Contradiction detected for $fact")
                      ctx.consistencyGuard.check(fact)
                 } else {
                      val oppositeExists = ctx.store.match(fact).any { it.predicate == fact.predicate && it.args == fact.args }
                      if (oppositeExists) throw IllegalArgumentException("Transaction Aborted: Contradiction detected for $fact")
                 }
             }
        }

        // 2. WAL Write (Batch)
        wal.append(WalOperation.ASSERT, WalData.TransactionData(items), tenantId = limitTenant)

        // 3. Apply Phase
        for (item in items) {
            val data = item.data
            val op = item.op

            when (data) {
                is WalData.FactData -> {
                    when (op) {
                        WalOperation.ASSERT -> internalAssertFact(ctx, data.atom, logging = false, tenantId = limitTenant)
                        WalOperation.RETRACT -> internalRetractFact(ctx, data.atom, logging = false, tenantId = limitTenant)
                    }
                }
                is WalData.RuleData -> {
                     if (op == WalOperation.ASSERT) {
                         internalAssertRule(ctx, data.rule, logging = false, tenantId = limitTenant)
                     }
                }
                else -> {}
            }
        }
    }

    // --- Replication & Backup ---

    @Synchronized
    fun createBackup(backupDir: File): File {
        if (!backupDir.exists()) backupDir.mkdirs()

        // 1. Force Snapshot
        createSnapshot()

        // 2. Copy Snapshot
        val timestamp = System.currentTimeMillis()
        val destDir = File(backupDir, "backup_$timestamp")
        destDir.mkdirs()

        val snapshotFile = File(storageDir, "snapshot.json")
        if (snapshotFile.exists()) {
            snapshotFile.copyTo(File(destDir, "snapshot.json"))
        }

        val tenantsFile = File(storageDir, "tenants.json")
        if (tenantsFile.exists()) {
            tenantsFile.copyTo(File(destDir, "tenants.json"))
        }

        return destDir
    }

    fun getWalEntries(startId: Long): Sequence<com.nocturnusai.persistence.WalEntry> {
        return wal.readFrom(startId)
    }

    @Synchronized
    fun applyReplicationBatch(entries: List<com.nocturnusai.persistence.WalEntry>) {
        entries.forEach { entry ->
            try {
                val targetTenant = entry.tenantId ?: "default"
                val ctx = getContextByKey(targetTenant)

                val op = entry.op
                val data = entry.data

                when (data) {
                    is WalData.FactData -> {
                        when (op) {
                            WalOperation.ASSERT -> internalAssertFact(ctx, data.atom, logging = true, tenantId = entry.tenantId)
                            WalOperation.RETRACT -> internalRetractFact(ctx, data.atom, logging = true, tenantId = entry.tenantId)
                        }
                    }
                    is WalData.RuleData -> {
                        if (op == WalOperation.ASSERT) {
                             internalAssertRule(ctx, data.rule, logging = true, tenantId = entry.tenantId)
                        }
                    }
                    is WalData.TransactionData -> {
                        // Replay batch
                        data.batch.forEach { item ->
                            when (val itemData = item.data) {
                                is WalData.FactData -> {
                                    when (item.op) {
                                        WalOperation.ASSERT -> internalAssertFact(ctx, itemData.atom, logging = true, tenantId = entry.tenantId)
                                        WalOperation.RETRACT -> internalRetractFact(ctx, itemData.atom, logging = true, tenantId = entry.tenantId)
                                    }
                                }
                                is WalData.RuleData -> {
                                    if (item.op == WalOperation.ASSERT) {
                                        internalAssertRule(ctx, itemData.rule, logging = true, tenantId = entry.tenantId)
                                    }
                                }
                                else -> {}
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                System.err.println("Replication Error on entry ${entry.id}: ${e.message}")
            }
        }
    }

    fun getPredicateSchema(tenantId: String? = null, scope: String? = null): Map<String, List<List<String>>> {
        val ctx = getContext(tenantId)
        val schema = mutableMapOf<String, MutableList<List<String>>>()
        for (atom in ctx.store.getAllAtoms()) {
            if (scope != null && atom.scope != scope) continue
            val examples = schema.getOrPut(atom.predicate) { mutableListOf() }
            if (examples.size < 3) {
                examples.add(atom.args.map { it.toString() })
            }
        }
        return schema
    }

    // Accessor for default store (legacy/tests)
    fun getStore(tenantId: String? = null): Hexastore = getContext(tenantId).store

    fun execute(logiql: String, tenantId: String? = null): String {
        val ctx = getContext(tenantId)
        val tokenizer = Tokenizer(logiql)
        val tokens = tokenizer.tokenize()
        val parser = Parser(tokens)

        try {
            val commands = parser.parse()
            val output = StringBuilder()

            for (cmd in commands) {
                when (cmd) {
                    is Command.AssertFact -> {
                        try {
                            assertFact(cmd.fact, tenantId)
                            output.append("ASSERTED: ${cmd.fact}\n")
                        } catch (e: Exception) {
                            output.append("ERROR: ${e.message}\n")
                        }
                    }
                    is Command.AssertRule -> {
                        assertRule(cmd.rule, tenantId)
                        output.append("RULE ADDED: ${cmd.rule}\n")
                    }
                    is Command.Infer -> {
                        if (cmd.withProof) {
                            val proofTrees = ctx.backwardChainer.solveWithProof(cmd.query).toList()
                            output.append("INFER RESULT (${cmd.query}): ${proofTrees.size} matches\n")
                            for (pt in proofTrees) {
                                output.append("  ${pt.result}\n")
                                output.append(formatProofTree(pt.proof, "    "))
                            }
                        } else {
                            val results = ctx.backwardChainer.solve(cmd.query).toList()
                            output.append("INFER RESULT (${cmd.query}): ${results.size} matches\n")
                            for (res in results) {
                                output.append("  $res\n")
                            }
                        }
                    }
                    is Command.Restrict -> {
                        ctx.consistencyGuard.addConstraint(cmd.constraint)
                        output.append("CONSTRAINT ADDED\n")
                    }
                    is Command.Explain -> {
                        val storedFact = ctx.store.match(cmd.fact).firstOrNull { it == cmd.fact || (it.predicate == cmd.fact.predicate && it.args == cmd.fact.args) }

                        if (storedFact != null) {
                            val derivation = ctx.tracker.getDerivation(storedFact)
                            if (derivation != null) {
                                output.append("EXPLANATION for $storedFact:\n")
                                output.append("  Rule: ${derivation.rule}\n")
                                output.append("  Premises:\n")
                                derivation.premises.forEach { p -> output.append("    $p\n") }
                            } else {
                                output.append("FACT $storedFact is present but has no provenance.\n")
                            }
                        } else {
                            output.append("FACT ${cmd.fact} NOT FOUND.\n")
                        }
                    }
                    is Command.Test -> {
                        val result = TestRunner().run(cmd.testCase)
                        if (result.passed) {
                            output.append("TEST \"${result.name}\": PASSED")
                        } else {
                            output.append("TEST \"${result.name}\": FAILED")
                        }
                        output.append(" (${result.expectationResults.count { it.passed }}/${result.expectationResults.size} expectations, ${result.durationMs}ms)\n")
                        for (er in result.expectationResults) {
                            val status = if (er.passed) "PASS" else "FAIL"
                            output.append("  [$status] ${er.message}\n")
                        }
                    }
                    is Command.Extract -> {
                        if (factExtractor == null) {
                            output.append("ERROR: No LLM provider configured for extraction.\n")
                        } else {
                            try {
                                val facts = runBlocking { factExtractor.extract(cmd.text) }
                                if (cmd.dryRun) {
                                    output.append("Would extract ${facts.size} facts:\n")
                                    for (f in facts) {
                                        output.append("  ${f.predicate}(${f.args.joinToString(", ")})\n")
                                    }
                                } else {
                                    output.append("Extracted ${facts.size} facts:\n")
                                    for (f in facts) {
                                        val terms = f.args.map { Term.Identifier(it) }
                                        val atom = Atom(f.predicate, terms, confidence = f.confidence.toDouble())
                                        try {
                                            assertFact(atom, tenantId)
                                            output.append("  ASSERTED: ${f.predicate}(${f.args.joinToString(", ")})\n")
                                        } catch (e: Exception) {
                                            output.append("  ERROR asserting ${f.predicate}(${f.args.joinToString(", ")}): ${e.message}\n")
                                        }
                                    }
                                }

                                // Pass 2: Rule extraction
                                if (ruleExtractor != null && facts.isNotEmpty()) {
                                    try {
                                        val extractedRules = runBlocking { ruleExtractor.extractRules(facts, cmd.text) }
                                        if (cmd.dryRun) {
                                            output.append("Would extract ${extractedRules.size} rules:\n")
                                            for (r in extractedRules) {
                                                output.append("  ${formatExtractedRule(r)}\n")
                                            }
                                            output.append("(dry run - not asserted)\n")
                                        } else {
                                            output.append("Extracted ${extractedRules.size} rules:\n")
                                            for (r in extractedRules) {
                                                try {
                                                    val rule = convertExtractedRule(r)
                                                    assertRule(rule, tenantId)
                                                    output.append("  RULE ADDED: ${formatExtractedRule(r)}\n")
                                                } catch (e: Exception) {
                                                    output.append("  ERROR asserting rule: ${e.message}\n")
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        output.append("RULE EXTRACTION ERROR: ${e.message}\n")
                                    }
                                }
                            } catch (e: Exception) {
                                output.append("EXTRACTION ERROR: ${e.message}\n")
                            }
                        }
                    }
                }
            }
            return output.toString()
        } catch (e: Exception) {
            return "PARSE/EXEC ERROR: ${e.message}"
        }
    }

    private fun convertExtractedRule(extracted: ExtractedRule, scope: String? = null): Rule {
        val variables = extracted.variables.map { Term.Variable(it) }
        val headTerms = extracted.head.args.map { parseTerm(it) }
        val head = Atom(extracted.head.predicate, headTerms, truthVal = !extracted.head.negated, scope = scope)
        val body = extracted.body.map { atom ->
            val bodyTerms = atom.args.map { parseTerm(it) }
            Atom(atom.predicate, bodyTerms, truthVal = !atom.negated, scope = scope)
        }
        return Rule(variables, head, body, scope = scope)
    }

    private fun parseTerm(str: String): Term {
        return if (str.startsWith("?")) {
            Term.Variable(str.removePrefix("?"))
        } else {
            Term.Identifier(str)
        }
    }

    private fun formatExtractedRule(r: ExtractedRule): String {
        val vars = r.variables.joinToString(", ") { "?$it" }
        val head = "${r.head.predicate}(${r.head.args.joinToString(", ")})"
        val body = r.body.joinToString(" AND ") { "${it.predicate}(${it.args.joinToString(", ")})" }
        return "FORALL $vars { $head <- $body }"
    }

    fun shutdownGracefully() {
        if (isShuttingDown) return
        isShuttingDown = true
        logger.info("Shutting down database '$dbName' gracefully...")

        // 1. Cancel snapshot coroutine
        snapshotScope.cancel()

        // 2. Rollback active transactions
        val activeTx = transactionManager.getActiveTransactions()
        if (activeTx.isNotEmpty()) {
            logger.warn("Rolling back ${activeTx.size} active transactions during shutdown")
            for (txId in activeTx) {
                try {
                    transactionManager.rollback(txId)
                } catch (e: Exception) {
                    logger.error("Failed to rollback tx $txId during shutdown: ${e.message}")
                }
            }
        }

        // 3. Close transaction reaper
        transactionManager.close()

        // 4. Flush WAL
        wal.flush()

        // 5. Create final snapshot
        try {
            createSnapshot()
        } catch (e: Exception) {
            logger.error("Failed to create final snapshot during shutdown: ${e.message}")
        }

        // 6. Shutdown memory managers
        contexts.forEach { (_, ctx) -> ctx.memoryManager.shutdown() }

        // 7. Close WAL
        wal.close()
        logger.info("Database '$dbName' shutdown complete.")
    }

    fun close() {
        shutdownGracefully()
    }
}
