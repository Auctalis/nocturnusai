package com.axiombase

import com.axiombase.core.*
import com.axiombase.inference.ReteEngine
import com.axiombase.inference.Unifier
import com.axiombase.logic.ConsistencyGuard
import com.axiombase.logic.ProvenanceTracker
import com.axiombase.parser.Command
import com.axiombase.parser.Parser
import com.axiombase.parser.Tokenizer
import com.axiombase.storage.Hexastore
import com.axiombase.persistence.WriteAheadLog
import com.axiombase.persistence.WalOperation
import com.axiombase.persistence.WalData
import com.axiombase.persistence.WalBatchItem
import com.axiombase.persistence.SnapshotManager
import com.axiombase.persistence.TenantSnapshotData
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

import com.axiombase.transaction.TransactionManager

class AxiomBase(val storageDir: File = File("data"), val isMultiTenant: Boolean = false) {
    
    private val contexts = ConcurrentHashMap<String, LogicContext>()
    
    private val wal = WriteAheadLog(File(storageDir, "axiombase.wal"))
    private val snapshotManager = SnapshotManager(storageDir)
    
    val transactionManager = TransactionManager(this)

    private fun getContext(tenantId: String? = null): LogicContext {
        val key = if (isMultiTenant) {
            tenantId ?: "default" // Fallback to default if null provided in MT mode? Or error? Letting it be robust.
        } else {
             "default" 
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
                val list = Json.decodeFromString<List<String>>(tenantsFile.readText())
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
            if (isMultiTenant) saveTenants()
        }

        // 2. Replay WAL on startup
        wal.replay { op, data, tenantId ->
            val targetKey = if (isMultiTenant) tenantId ?: "default" else "default"
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
    }


    // Public API
    fun assertFact(fact: Atom, tenantId: String? = null, scope: String? = null) {
        val ctx = getContext(tenantId)
        val limitTenant = if (isMultiTenant) tenantId ?: "default" else null
        
        // If fact itself has a scope, respect it, otherwise apply the passed scope
        val finalFact = if (fact.scope == null && scope != null) fact.copy(scope = scope) else fact
        
        internalAssertFact(ctx, finalFact, logging = true, tenantId = limitTenant)
    }

    private fun internalAssertFact(ctx: LogicContext, fact: Atom, logging: Boolean, tenantId: String?) {
        // 0. Enforce logical consistency (Layer 6) within context AND scope?
        // Contradiction: If I assert A in Scope1, and !A exists in Scope1.
        
        // Unified Storage: Check ctx.store for the opposite fact.
        // We construct the opposite atom (same scope, same args, inverted truthVal)
        val oppositeFact = fact.copy(truthVal = !fact.truthVal)
        
        // Check if opposite exists in the store (Unified Store contains both Pos and Neg)
        // match(oppositeFact) will look for the effective predicate of the opposite fact.
        // e.g. if fact is P, opposite is !P. We look for !P.
        // e.g. if fact is !P, opposite is P. We look for P.
        val oppositeExists = ctx.store.match(oppositeFact, scope = fact.scope).any { 
            it.predicate == oppositeFact.predicate && it.args == oppositeFact.args 
        }
        
        if (oppositeExists) {
            if (!logging) {
                 // Replay warning
                 return
            }
            throw IllegalArgumentException("Contradiction detected: Cannot assert $fact because its negation exists in the same scope.")
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
            wal.append(WalOperation.ASSERT, WalData.FactData(fact), tenantId = tenantId)
        }

        // 3. Add to store
        // Unified Storage: All facts (positive or negative) go to ctx.store
        ctx.store.add(fact)
        ctx.rete.onFactAsserted(fact)
        
        // Legacy compatibility? If we remove negativeStore usage entirely.
        // if (!fact.truthVal) ctx.negativeStore.add(fact) 
        // We stop using negativeStore for active usage.
    }

    fun retractFact(fact: Atom, tenantId: String? = null, scope: String? = null) {
        val ctx = getContext(tenantId)
        val limitTenant = if (isMultiTenant) tenantId ?: "default" else null
        val finalFact = if (fact.scope == null && scope != null) fact.copy(scope = scope) else fact
        internalRetractFact(ctx, finalFact, logging = true, tenantId = limitTenant)
    }

    private fun internalRetractFact(ctx: LogicContext, fact: Atom, logging: Boolean, tenantId: String?) {
        if (logging) {
            wal.append(WalOperation.RETRACT, WalData.FactData(fact), tenantId = tenantId)
        }

        // Unified Retraction
        // If truthVal is true, we might need retracting derived facts (TMS).
        // If truthVal is false, we just remove the negative fact.
        // TMS currently only tracks positive implications?
        // TODO: TMS for negative implications (Modus Tollens) needs dependency tracking update.
        // For now, simple delete.
        
        if (fact.truthVal) {
             val toDelete = ctx.tracker.retract(fact)
             for (deadFact in toDelete) {
                 ctx.store.delete(deadFact)
             }
        }
        ctx.store.delete(fact)
        // ctx.negativeStore.delete(fact) // Legacy
    }

    fun assertRule(rule: Rule, tenantId: String? = null, scope: String? = null) {
        val ctx = getContext(tenantId)
        val limitTenant = if (isMultiTenant) tenantId ?: "default" else null
        val finalRule = if (rule.scope == null && scope != null) rule.copy(scope = scope) else rule
        internalAssertRule(ctx, finalRule, logging = true, tenantId = limitTenant)
    }

    private fun internalAssertRule(ctx: LogicContext, rule: Rule, logging: Boolean, tenantId: String?) {
        if (logging) {
             wal.append(WalOperation.ASSERT, WalData.RuleData(rule), tenantId = tenantId)
        }
        ctx.rules.add(rule)
        ctx.rete.addRule(rule)
    }

    fun query(pattern: Atom, tenantId: String? = null, scope: String? = null): Sequence<Atom> {
        val ctx = getContext(tenantId)
        // If pattern has no scope, and scope arg is provided, use it.
        // match() in Hexastore has been updated to accept scope.
        return ctx.store.match(pattern, scope = scope)
    }
    
    fun infer(pattern: Atom, tenantId: String? = null): Sequence<Atom> {
        val ctx = getContext(tenantId)
        return ctx.backwardChainer.solve(pattern)
    }

    fun getRules(tenantId: String? = null, scope: String? = null): List<Rule> {
        val ctx = getContext(tenantId)
        return if (scope == null) ctx.rules.toList()
        else ctx.rules.filter { it.scope == scope }
    }

    // --- Tenant Management ---
    fun createTenant(tenantId: String) {
        if (!isMultiTenant) throw IllegalStateException("Database is not multi-tenant")
        if (tenants.contains(tenantId)) return // Already exists
        tenants.add(tenantId)
        saveTenants()
        // Initialize context?
        getContext(tenantId) 
    }
    
    fun deleteTenant(tenantId: String) {
        if (!isMultiTenant) throw IllegalStateException("Database is not multi-tenant")
        tenants.remove(tenantId)
        contexts.remove(tenantId)
        saveTenants()
    }
    
    fun getRegisteredTenants(): Set<String> {
        return tenants.toSet() 
    }
    
    private fun saveTenants() {
        try {
            tenantsFile.writeText(Json.encodeToString(tenants.toList()))
        } catch (e: Exception) {
            println("Error saving tenants: ${e.message}")
        }
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
        val limitTenant = if (isMultiTenant) tenantId ?: "default" else null
        
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
    
    fun getWalEntries(startId: Long): Sequence<com.axiombase.persistence.WalEntry> {
        return wal.readFrom(startId)
    }

    @Synchronized
    fun applyReplicationBatch(entries: List<com.axiombase.persistence.WalEntry>) {
        entries.forEach { entry ->
            try {
                // Determine target tenant
                // If MT, use entry.tenantId. If not MT, use default.
                // Note: Entry tenant might be null if it was global or legacy.
                val targetTenant = if (isMultiTenant) entry.tenantId ?: "default" else "default"
                val ctx = getContextByKey(targetTenant)
                
                // Re-apply without logging to WAL (since we are a follower replicating WAL)
                // BUT: We SHOULD log it to our own WAL if we want to be promotable?
                // Standard replication: Followers also write to their WAL but maybe mark it as replicated?
                // For simplicity: Followers write to WAL so they can restart. 
                // However, IDs might descynchronize if we generate new IDs. 
                // Ideally, we write the EXACT SAME entry.
                // Our current WAL.append generates IDs. 
                // TODO: Allow appending with specific ID or just let Follower generate its own local WAL IDs.
                // Let's go with: Follower appends to its WAL (new local IDs).
                // This means if Follower becomes Leader, it has a valid WAL.
                
                // WAIT: If we just call internalAssertFact with logging=true, it will append to WAL.
                // But we need to be careful about infinite loops if we were to support bi-directional.
                // Here we assume Leader -> Follower only.
                
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
                // Log but don't crash follower? 
                System.err.println("Replication Error on entry ${entry.id}: ${e.message}")
            }
        }
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
                        val results = ctx.store.match(cmd.query).toList()
                        output.append("INFER RESULT (${cmd.query}): ${results.size} matches\n")
                        for (res in results) {
                            output.append("  $res\n")
                        }
                    }
                    is Command.Restrict -> {
                        ctx.consistencyGuard.addConstraint(cmd.constraint)
                        output.append("CONSTRAINT ADDED\n")
                    }
                    is Command.Explain -> {
                         // Fix Explain to use context
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
                }
            }
            return output.toString()
        } catch (e: Exception) {
            return "PARSE/EXEC ERROR: ${e.message}"
        }
    }

    fun close() {
        wal.close()
    }
}
