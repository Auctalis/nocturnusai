package com.axiombase

import com.axiombase.core.*
import com.axiombase.inference.BackwardChainer
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
import com.axiombase.persistence.EncryptionService
import com.axiombase.persistence.SnapshotManager
import com.axiombase.persistence.TenantSnapshotData
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*

import com.axiombase.extraction.ExtractedFact
import com.axiombase.extraction.ExtractedRule
import com.axiombase.extraction.FactExtractor
import com.axiombase.extraction.RuleExtractor
import com.axiombase.testing.TestRunner
import com.axiombase.transaction.TransactionManager

class TenantNotFoundException(val tenantId: String) : RuntimeException("Tenant '$tenantId' not found")

class AxiomBase(
    val storageDir: File = File("data"),
    val isMultiTenant: Boolean = true,
    val dbName: String = "default",
    val encryption: EncryptionService? = null,
    val factExtractor: FactExtractor? = null,
    val ruleExtractor: RuleExtractor? = null
) {

    private val logger = org.slf4j.LoggerFactory.getLogger(AxiomBase::class.java)

    private val contexts = ConcurrentHashMap<String, LogicContext>()

    private val wal = WriteAheadLog(File(storageDir, "axiombase.wal"), encryption)
    private val snapshotManager = SnapshotManager(storageDir, encryption)

    val transactionManager = TransactionManager(this)

    @Volatile var isShuttingDown = false
        private set

    private val snapshotScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val snapshotIntervalMs: Long = System.getProperty("axiombase.snapshot.interval.ms")?.toLongOrNull() ?: 300_000L // 5 min

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
    fun assertFact(fact: Atom, tenantId: String? = null, scope: String? = null) {
        val ctx = getContext(tenantId)
        val limitTenant = tenantId ?: "default"

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
            logger.info("OP: ASSERT_FACT [DB: {}, TENANT: {}, SCOPE: {}] {}", dbName, tenantId ?: "default", fact.scope ?: "global", fact)
            wal.append(WalOperation.ASSERT, WalData.FactData(fact), tenantId = tenantId)
        }

        // 3. Add to store
        // Unified Storage: All facts (positive or negative) go to ctx.store
        ctx.store.add(fact)
        ctx.rete.onFactAsserted(fact)
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
    }

    fun query(pattern: Atom, tenantId: String? = null, scope: String? = null): Sequence<Atom> {
        val ctx = getContext(tenantId)
        return ctx.store.match(pattern, scope = scope)
    }

    fun infer(pattern: Atom, tenantId: String? = null): Sequence<Atom> {
        val ctx = getContext(tenantId)
        return ctx.backwardChainer.solve(pattern)
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

    fun getWalEntries(startId: Long): Sequence<com.axiombase.persistence.WalEntry> {
        return wal.readFrom(startId)
    }

    @Synchronized
    fun applyReplicationBatch(entries: List<com.axiombase.persistence.WalEntry>) {
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
                                        val atom = Atom(f.predicate, terms)
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

        // 6. Close WAL
        wal.close()
        logger.info("Database '$dbName' shutdown complete.")
    }

    fun close() {
        shutdownGracefully()
    }
}
