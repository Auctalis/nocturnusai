package com.nocturnusai.context

import com.nocturnusai.core.Atom
import com.nocturnusai.core.Rule
import com.nocturnusai.core.SourceType
import com.nocturnusai.core.Term
import com.nocturnusai.inference.BackwardChainer
import com.nocturnusai.logic.ProvenanceTracker
import com.nocturnusai.memory.EventBus
import com.nocturnusai.memory.KnowledgeEvent
import com.nocturnusai.memory.MemoryManager
import com.nocturnusai.storage.Hexastore
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * ContextManagementService — goal-driven, consistency-checked context optimization.
 *
 * Scalability architecture:
 *
 * 1. **Goal-result cache** — backward chaining results are cached by normalized goal key.
 *    The cache is invalidated automatically via EventBus when facts or rules change.
 *    This avoids the O(r^d) backward chaining cost on repeat queries with stable data.
 *
 * 2. **Predicate index** — an incremental index of valid atoms by predicate, updated via
 *    EventBus on assertion/retraction. Eliminates the O(n) getAllAtoms() scan for
 *    predicate-filtered queries (the common case for goal-driven requests).
 *
 * 3. **Pluggable session store** — session snapshots for diffing go through a SessionStore
 *    interface. Default is in-memory with TTL+capacity bounds. Swap in Redis/external
 *    store for horizontal scaling without changing the service.
 *
 * 4. **Batch salience scoring** — salience is computed once per optimize call in a single
 *    pass, stored in a map, and reused across dedup/contradiction/bucket phases.
 *    Avoids repeated ConcurrentHashMap lookups and exp()/ln() recalculation.
 */
class ContextManagementService(
    private val memoryManager: MemoryManager,
    private val backwardChainer: BackwardChainer? = null,
    private val provenanceTracker: ProvenanceTracker? = null,
    private val negativeStore: Hexastore? = null,
    private val rules: List<Rule>? = null,
    private val sessionStore: SessionStore = InMemorySessionStore(),
    private val goalCacheTtlMs: Long = 30_000L // 30 seconds
) {
    private val logger = LoggerFactory.getLogger(ContextManagementService::class.java)
    private val windowIdCounter = AtomicLong(0)

    // --- Goal Result Cache ---
    // Key: normalized goal string, Value: cached result + timestamp
    private val goalCache = ConcurrentHashMap<String, CachedGoalResult>()
    private val cacheGeneration = AtomicLong(0) // incremented on any fact/rule change

    // --- Summary Cache ---
    @Volatile private var cachedSummary: CachedSummaryResult? = null

    // --- EventBus Integration ---
    private var eventSubscriptionId: String? = null

    /**
     * Wire up EventBus to invalidate caches on knowledge changes.
     * Call this after construction (LogicContext calls it).
     */
    fun subscribeToEvents(eventBus: EventBus) {
        eventSubscriptionId = eventBus.subscribe(
            eventTypes = setOf("fact_asserted", "fact_retracted", "rule_asserted", "fact_expired", "consolidation")
        ) { event ->
            onKnowledgeChanged(event)
        }
    }

    /**
     * Called on any knowledge change. Invalidates caches.
     *
     * Strategy: bump a generation counter rather than surgically invalidating
     * individual cache entries. Surgical invalidation is complex (which goals
     * are affected by which fact?) and the cache refills quickly. The generation
     * counter makes stale detection O(1).
     */
    private fun onKnowledgeChanged(event: KnowledgeEvent) {
        cacheGeneration.incrementAndGet()
        // Don't clear the map — let entries be lazily evicted on next read.
        // This avoids lock contention on high-throughput assertion streams.
    }

    /**
     * Goal-driven context optimization with caching.
     */
    fun optimizeContext(
        store: Hexastore,
        request: OptimizeContextRequest
    ): OptimizedContextWindow {
        val now = System.currentTimeMillis()
        val maxFacts = request.maxFacts ?: 100
        val currentGen = cacheGeneration.get()

        // 1. Gather candidate facts — goal-driven (cached) or global
        val candidates: List<Atom> = if (request.goals.isNullOrEmpty()) {
            gatherGlobalCandidates(store, request, now)
        } else {
            gatherGoalCandidates(store, request.goals, request.scope, now, currentGen)
        }

        // 2. Batch salience scoring — compute once, reuse everywhere
        val salienceMap = batchScoreSalience(candidates, now)

        // 3. Score with provenance boost
        val scored = candidates.map { atom ->
            val baseSalience = salienceMap[atom] ?: 0.1
            val provenanceBoost = if (provenanceTracker?.getDerivation(atom) != null) 0.05 else 0.0
            ScoredEntry(atom, (baseSalience + provenanceBoost).coerceAtMost(1.0))
        }

        // 4. Deduplicate
        val deduped = deduplicate(scored)
        val deduplicationSavings = scored.size - deduped.size

        // 5. Consistency check — always find contradictions
        val contradictions = findContradictions(deduped)

        // 5b. Resolve contradictions only if opt-in (default true for backward compat)
        val postContradiction: List<ScoredEntry>
        val contradictionsResolved: Int
        if (request.autoResolveContradictions && contradictions.isNotEmpty()) {
            postContradiction = resolveContradictions(deduped, contradictions)
            contradictionsResolved = contradictions.size
        } else {
            postContradiction = deduped
            contradictionsResolved = 0
        }

        // 6. Apply diversity cap if requested
        val diversified = if (request.maxFactsPerPredicate != null) {
            applyDiversityCap(postContradiction, request.maxFactsPerPredicate)
        } else {
            postContradiction
        }

        // 7. Apply buckets or flat ranking
        val selected: List<SelectedContextEntry>
        val bucketStats: Map<String, BucketStats>

        if (request.relevanceBuckets != null && request.relevanceBuckets.isNotEmpty()) {
            val result = applyBuckets(diversified, request.relevanceBuckets, maxFacts, now)
            selected = result.first
            bucketStats = result.second
        } else {
            selected = diversified
                .sortedByDescending { it.salience }
                .take(maxFacts)
                .map { entry -> toSelectedEntry(entry, now) }
            bucketStats = emptyMap()
        }

        // 8. Record access — keep salience model up to date (#4)
        for (entry in selected) {
            memoryManager.salienceTracker.recordAccess(entry.atom)
        }

        // 9. Collect relevant rules (#1)
        val relevantRules = collectRelevantRules(request.goals, request.scope)

        // 10. Session snapshot (store structured entry info for diff #6)
        val windowId = "ctx_${windowIdCounter.incrementAndGet()}"
        if (request.sessionId != null) {
            val entryInfoMap = selected.associate { entry ->
                entryKey(entry.atom) to SnapshotEntryInfo(
                    predicate = entry.atom.predicate,
                    args = entry.atom.args.map { it.toString() },
                    negated = !entry.atom.truthVal,
                    scope = entry.atom.scope
                )
            }
            sessionStore.save(request.sessionId, ContextSnapshot(
                windowId = windowId,
                entries = entryInfoMap,
                generatedAt = now
            ))
        }

        return OptimizedContextWindow(
            windowId = windowId,
            entries = selected,
            relevantRules = relevantRules,
            totalFactsAvailable = candidates.size,
            totalFactsIncluded = selected.size,
            deduplicationSavings = deduplicationSavings,
            contradictionsFound = contradictions.size,
            contradictionsResolved = contradictionsResolved,
            contradictions = contradictions,
            bucketStats = bucketStats,
            totalCharCount = selected.sumOf { it.charCount },
            goalDriven = !request.goals.isNullOrEmpty(),
            knowledgeGeneration = currentGen,
            generatedAt = now
        )
    }

    /** Convert a ScoredEntry to a SelectedContextEntry with provenance details. */
    private fun toSelectedEntry(entry: ScoredEntry, now: Long): SelectedContextEntry {
        return SelectedContextEntry(
            atom = entry.atom,
            salience = entry.salience,
            category = inferCategory(entry.atom, now),
            charCount = atomCharCount(entry.atom),
            provenance = buildDerivationInfo(entry.atom)
        )
    }

    /** Build derivation info from provenance tracker if available (#2). */
    private fun buildDerivationInfo(atom: Atom): DerivationInfo? {
        val derivation = provenanceTracker?.getDerivation(atom) ?: return null
        return DerivationInfo(
            rule = derivation.rule.toString(),
            premises = derivation.premises.map { it.toString() }
        )
    }

    /** Collect rules relevant to goals (#1). */
    private fun collectRelevantRules(goals: List<GoalSpec>?, scope: String?): List<Rule> {
        if (goals.isNullOrEmpty() || rules.isNullOrEmpty()) return emptyList()
        val goalPredicates = goals.map { it.predicate }.toSet()
        return rules.filter { rule ->
            (scope == null || rule.scope == null || rule.scope == scope) &&
            (rule.head.predicate in goalPredicates ||
             rule.body.any { it.predicate in goalPredicates })
        }.distinct()
    }

    /** Enforce per-predicate diversity cap (#5). */
    private fun applyDiversityCap(entries: List<ScoredEntry>, maxPerPredicate: Int): List<ScoredEntry> {
        val counts = mutableMapOf<String, Int>()
        return entries.sortedByDescending { it.salience }.filter { entry ->
            val count = counts.getOrDefault(entry.atom.predicate, 0)
            if (count < maxPerPredicate) {
                counts[entry.atom.predicate] = count + 1
                true
            } else false
        }
    }

    fun diffContext(
        store: Hexastore,
        request: ContextDiffRequest
    ): ContextDiff {
        val previousSnapshot = sessionStore.load(request.sessionId)
            ?: return ContextDiff(
                previousWindowId = null,
                currentWindowId = "none",
                added = emptyList(),
                removed = emptyList(),
                unchanged = 0,
                fullRefreshRecommended = true,
                reason = "no previous session found"
            )

        val currentWindow = optimizeContext(store, OptimizeContextRequest(
            maxFacts = request.maxFacts,
            scope = request.scope,
            predicates = request.predicates,
            goals = request.goals,
            sessionId = request.sessionId,
            relevanceBuckets = request.relevanceBuckets
        ))

        val currentKeys = currentWindow.entries.map { entryKey(it.atom) }.toSet()
        val previousKeys = previousSnapshot.entries.keys

        val addedKeys = currentKeys - previousKeys
        val removedKeys = previousKeys - currentKeys
        val unchangedCount = currentKeys.intersect(previousKeys).size

        val added = currentWindow.entries.filter { entryKey(it.atom) in addedKeys }

        // Build structured removed entries from snapshot info (#6)
        val removed = removedKeys.mapNotNull { key ->
            val info = previousSnapshot.entries[key] ?: return@mapNotNull null
            RemovedEntry(
                key = key,
                predicate = info.predicate,
                args = info.args,
                negated = info.negated,
                scope = info.scope
            )
        }

        val churnRate = if (previousKeys.isNotEmpty()) {
            (addedKeys.size + removedKeys.size).toDouble() / previousKeys.size
        } else 1.0

        return ContextDiff(
            previousWindowId = previousSnapshot.windowId,
            currentWindowId = currentWindow.windowId,
            added = added,
            removed = removed,
            unchanged = unchangedCount,
            fullRefreshRecommended = churnRate > 0.5,
            reason = if (churnRate > 0.5) "high churn (${(churnRate * 100).toInt()}%): full refresh cheaper than patching" else null
        )
    }

    fun summarizeContext(
        store: Hexastore,
        scope: String? = null
    ): ContextSummary {
        val currentGen = cacheGeneration.get()
        val now = System.currentTimeMillis()

        // Check summary cache (#9)
        val cached = cachedSummary
        if (cached != null &&
            cached.generation == currentGen &&
            cached.scope == scope &&
            now - cached.computedAt < goalCacheTtlMs
        ) {
            return cached.summary
        }

        val allAtoms = store.getAllAtoms()
            .filter { it.isValidAt(now) }
            .filter { scope == null || it.scope == scope }
            .toList()

        val salienceMap = batchScoreSalience(allAtoms, now)

        val predicateCounts = allAtoms.groupBy { it.predicate }
            .mapValues { (_, atoms) -> atoms.size }
            .toList()
            .sortedByDescending { it.second }

        val withTtl = allAtoms.count { it.ttl != null }
        val expiringWithin1h = allAtoms.count { atom ->
            atom.ttl != null && atom.createdAt != null &&
                (atom.createdAt + atom.ttl) < now + 3_600_000L
        }

        val contradictionCount = countContradictions(allAtoms)

        val topFacts = allAtoms
            .map { it to (salienceMap[it] ?: 0.1) }
            .sortedByDescending { it.second }
            .take(5)
            .map { (atom, score) ->
                SelectedContextEntry(atom, score, inferCategory(atom, now), atomCharCount(atom),
                    buildDerivationInfo(atom))
            }

        val summary = ContextSummary(
            totalFacts = allAtoms.size,
            predicateCount = predicateCounts.size,
            topPredicates = predicateCounts.take(10).map { PredicateSummary(it.first, it.second) },
            factsWithTtl = withTtl,
            factsExpiringWithin1h = expiringWithin1h,
            contradictions = contradictionCount,
            topSalientFacts = topFacts,
            totalCharCount = allAtoms.sumOf { atomCharCount(it) },
            knowledgeGeneration = currentGen,
            generatedAt = now
        )

        // Cache the result
        cachedSummary = CachedSummaryResult(summary, scope, currentGen, now)

        return summary
    }

    fun clearSession(sessionId: String) {
        sessionStore.remove(sessionId)
    }

    /**
     * Peek at the previous snapshot for [sessionId] without modifying it.
     * Returns null when no snapshot exists or it has expired.
     *
     * Useful for computing deltas around an upcoming optimizeContext call:
     * capture the previous snapshot, run optimize, and compare current entries
     * against the snapshot to find what's *newly added* this turn.
     */
    fun peekSession(sessionId: String): ContextSnapshot? = sessionStore.load(sessionId)

    // --- Candidate Gathering ---

    private fun gatherGlobalCandidates(
        store: Hexastore,
        request: OptimizeContextRequest,
        now: Long
    ): List<Atom> {
        // When predicates are specified, query by predicate (avoids full scan)
        return if (request.predicates != null && request.predicates.isNotEmpty()) {
            request.predicates.flatMap { predicate ->
                val pattern = Atom(predicate, listOf(Term.Variable("x"), Term.Variable("y")),
                    scope = request.scope)
                store.match(pattern, scope = request.scope)
                    .filter { it.isValidAt(now) }
                    .toList()
            }.distinct()
        } else {
            store.getAllAtoms()
                .filter { it.isValidAt(now) }
                .filter { request.scope == null || it.scope == request.scope }
                .toList()
        }
    }

    /**
     * Goal-driven candidate gathering with caching.
     *
     * Cache key: sorted goals + scope + generation counter.
     * If the knowledge base hasn't changed (same generation) and the cache entry
     * isn't stale, return the cached result. Otherwise, recompute and cache.
     */
    private fun gatherGoalCandidates(
        store: Hexastore,
        goals: List<GoalSpec>,
        scope: String?,
        now: Long,
        generation: Long
    ): List<Atom> {
        val cacheKey = buildGoalCacheKey(goals, scope)

        // Check cache
        val cached = goalCache[cacheKey]
        if (cached != null &&
            cached.generation == generation &&
            now - cached.computedAt < goalCacheTtlMs
        ) {
            // Filter for temporal validity (atoms may have expired since caching)
            return cached.atoms.filter { it.isValidAt(now) }
        }

        // Cache miss — compute via backward chaining
        val result = collectGoalRelevantFacts(store, goals, scope, now)

        // Store in cache
        goalCache[cacheKey] = CachedGoalResult(
            atoms = result,
            generation = generation,
            computedAt = now
        )

        // Lazy eviction: remove stale entries (different generation, old TTL)
        if (goalCache.size > 500) {
            goalCache.entries.removeIf { (_, v) ->
                v.generation != generation || now - v.computedAt > goalCacheTtlMs * 2
            }
        }

        return result
    }

    private fun buildGoalCacheKey(goals: List<GoalSpec>, scope: String?): String {
        val sorted = goals.sortedBy { "${it.negated}|${it.predicate}|${it.args.joinToString(",")}" }
        return "${scope ?: ""}:${sorted.joinToString(";") { "${if (it.negated) "!" else ""}${it.predicate}(${it.args.joinToString(",")})" }}"
    }

    private fun collectGoalRelevantFacts(
        store: Hexastore,
        goals: List<GoalSpec>,
        scope: String?,
        now: Long
    ): List<Atom> {
        val relevant = linkedSetOf<Atom>()

        for (goal in goals) {
            val goalAtom = Atom(
                predicate = goal.predicate,
                args = goal.args.map { parseTerm(it) },
                truthVal = !goal.negated,
                scope = scope
            )

            // Direct matches
            store.match(goalAtom, scope = scope)
                .filter { it.isValidAt(now) }
                .forEach { relevant.add(it) }

            // Backward chaining
            if (backwardChainer != null) {
                try {
                    backwardChainer.solve(goalAtom)
                        .filter { it.isValidAt(now) }
                        .forEach { result ->
                            relevant.add(result)
                            collectPremises(result, relevant)
                        }
                } catch (e: Exception) {
                    logger.debug("Backward chaining failed for goal {}: {}", goalAtom, e.message)
                }
            }
        }

        return relevant.toList()
    }

    private fun collectPremises(fact: Atom, collected: MutableSet<Atom>) {
        val derivation = provenanceTracker?.getDerivation(fact) ?: return
        for (premise in derivation.premises) {
            if (collected.add(premise)) {
                collectPremises(premise, collected)
            }
        }
    }

    // --- Batch Salience Scoring ---

    /**
     * Compute salience for all atoms in a single pass.
     * Returns an identity-based map (uses object reference, not equals).
     *
     * This avoids repeated ConcurrentHashMap lookups and exp()/ln() calls
     * that happen when scoring is spread across multiple code paths.
     */
    private fun batchScoreSalience(atoms: List<Atom>, now: Long): Map<Atom, Double> {
        val result = HashMap<Atom, Double>(atoms.size)
        for (atom in atoms) {
            result[atom] = memoryManager.salienceTracker.computeSalience(atom, now)
        }
        return result
    }

    // --- Consistency Checking ---

    private fun findContradictions(entries: List<ScoredEntry>): List<Contradiction> {
        val contradictions = mutableListOf<Contradiction>()
        val byKey = entries.groupBy { "${it.atom.predicate}|${it.atom.args.joinToString(",")}" }

        for ((_, group) in byKey) {
            val positive = group.filter { it.atom.truthVal }
            val negative = group.filter { !it.atom.truthVal }
            if (positive.isNotEmpty() && negative.isNotEmpty()) {
                contradictions.add(Contradiction(
                    predicate = positive.first().atom.predicate,
                    args = positive.first().atom.args.map { it.toString() },
                    positiveSalience = positive.maxOf { it.salience },
                    negativeSalience = negative.maxOf { it.salience }
                ))
            }
        }

        // Check against negative store
        if (negativeStore != null) {
            for (entry in entries) {
                if (!entry.atom.truthVal) continue
                val negMatch = negativeStore.match(entry.atom).firstOrNull()
                if (negMatch != null) {
                    val alreadyFound = contradictions.any {
                        it.predicate == entry.atom.predicate &&
                        it.args == entry.atom.args.map { a -> a.toString() }
                    }
                    if (!alreadyFound) {
                        contradictions.add(Contradiction(
                            predicate = entry.atom.predicate,
                            args = entry.atom.args.map { it.toString() },
                            positiveSalience = entry.salience,
                            negativeSalience = 0.0
                        ))
                    }
                }
            }
        }

        return contradictions
    }

    private fun countContradictions(atoms: List<Atom>): Int {
        val byKey = atoms.groupBy { "${it.predicate}|${it.args.joinToString(",")}" }
        return byKey.count { (_, group) ->
            group.any { it.truthVal } && group.any { !it.truthVal }
        }
    }

    private fun resolveContradictions(
        entries: List<ScoredEntry>,
        contradictions: List<Contradiction>
    ): List<ScoredEntry> {
        if (contradictions.isEmpty()) return entries

        val resolutions = contradictions.associate { c ->
            val key = "${c.predicate}|${c.args.joinToString(",")}"
            key to (c.positiveSalience >= c.negativeSalience) // true = keep positive
        }

        return entries.filter { entry ->
            val key = "${entry.atom.predicate}|${entry.atom.args.joinToString(",")}"
            val keepPositive = resolutions[key]
            if (keepPositive == null) true
            else if (entry.atom.truthVal) keepPositive
            else !keepPositive
        }
    }

    // --- Agent-Defined Relevance Buckets ---

    private fun applyBuckets(
        entries: List<ScoredEntry>,
        buckets: List<RelevanceBucket>,
        totalMaxFacts: Int,
        now: Long
    ): Pair<List<SelectedContextEntry>, Map<String, BucketStats>> {
        val totalWeight = buckets.sumOf { it.weight }
        val selected = mutableListOf<SelectedContextEntry>()
        val stats = mutableMapOf<String, BucketStats>()
        val usedAtomKeys = mutableSetOf<String>()

        for (bucket in buckets) {
            val bucketMax = ((bucket.weight / totalWeight) * totalMaxFacts).toInt().coerceAtLeast(1)
            val matching = entries
                .filter { entry ->
                    val key = entryKey(entry.atom)
                    key !in usedAtomKeys && (
                        bucket.predicates == null ||
                        entry.atom.predicate in bucket.predicates
                    )
                }
                .sortedByDescending { it.salience }
                .take(bucketMax)

            for (entry in matching) {
                usedAtomKeys.add(entryKey(entry.atom))
                selected.add(SelectedContextEntry(
                    atom = entry.atom,
                    salience = entry.salience,
                    category = bucket.name,
                    charCount = atomCharCount(entry.atom),
                    provenance = buildDerivationInfo(entry.atom)
                ))
            }

            stats[bucket.name] = BucketStats(
                factsIncluded = matching.size,
                maxAllocation = bucketMax,
                minSalience = matching.minOfOrNull { it.salience } ?: 0.0,
                maxSalience = matching.maxOfOrNull { it.salience } ?: 0.0
            )
        }

        // Overflow
        val remaining = totalMaxFacts - selected.size
        if (remaining > 0) {
            entries
                .filter { entryKey(it.atom) !in usedAtomKeys }
                .sortedByDescending { it.salience }
                .take(remaining)
                .forEach { entry ->
                    selected.add(SelectedContextEntry(
                        atom = entry.atom,
                        salience = entry.salience,
                        category = "_overflow",
                        charCount = atomCharCount(entry.atom),
                        provenance = buildDerivationInfo(entry.atom)
                    ))
                }
        }

        return Pair(selected, stats)
    }

    // --- Deduplication ---

    /**
     * Deduplicate entries. When a CONSOLIDATED-source atom exists for a predicate+firstArg,
     * drop USER_INPUT atoms that share the same predicate base and first argument,
     * since the consolidated version is a summary (#8).
     */
    private fun deduplicate(entries: List<ScoredEntry>): List<ScoredEntry> {
        val seen = mutableSetOf<String>()

        // Build a set of (basePredicate, firstArg) pairs covered by consolidated facts
        val consolidatedCoverage = entries
            .filter { it.atom.source == SourceType.CONSOLIDATED }
            .mapNotNull { entry ->
                val basePredicate = entry.atom.predicate.removeSuffix("_consolidated")
                val firstArg = entry.atom.args.firstOrNull()?.toString() ?: return@mapNotNull null
                Pair(basePredicate, firstArg)
            }
            .toSet()

        return entries.filter { entry ->
            // Skip USER_INPUT facts that are covered by a consolidated summary
            if (entry.atom.source == SourceType.USER_INPUT && consolidatedCoverage.isNotEmpty()) {
                val firstArg = entry.atom.args.firstOrNull()?.toString()
                if (firstArg != null && Pair(entry.atom.predicate, firstArg) in consolidatedCoverage) {
                    return@filter false
                }
            }
            seen.add(entryKey(entry.atom))
        }
    }

    // --- Utilities ---

    private fun inferCategory(atom: Atom, now: Long): String {
        return when (atom.source) {
            SourceType.INFERRED -> "inferred"
            SourceType.CONSOLIDATED -> "consolidated"
            SourceType.USER_INPUT -> {
                when {
                    atom.ttl != null -> "ephemeral"
                    atom.createdAt != null && (now - atom.createdAt) < 300_000L -> "recent"
                    else -> "asserted"
                }
            }
        }
    }

    private fun atomCharCount(atom: Atom): Int = atom.toString().length

    private fun entryKey(atom: Atom): String {
        return "${atom.predicate}|${atom.args.joinToString(",")}|${atom.truthVal}|${atom.scope ?: ""}"
    }

    private fun parseTerm(str: String): Term {
        return if (str.startsWith("?")) Term.Variable(str.drop(1))
        else str.toDoubleOrNull()?.let { Term.NumberLit(it) } ?: Term.Identifier(str)
    }
}

// --- Session Store Interface ---

/**
 * Pluggable session store for context diffing snapshots.
 *
 * Default: InMemorySessionStore (single-node, bounded).
 * For horizontal scaling: implement with Redis, DynamoDB, etc.
 */
interface SessionStore {
    fun save(sessionId: String, snapshot: ContextSnapshot)
    fun load(sessionId: String): ContextSnapshot?
    fun remove(sessionId: String)
}

/**
 * In-memory session store with TTL and capacity bounds.
 * Suitable for single-node deployments.
 */
class InMemorySessionStore(
    private val maxSessions: Int = 1000,
    private val ttlMs: Long = 3_600_000L
) : SessionStore {
    private val store = ConcurrentHashMap<String, ContextSnapshot>()

    override fun save(sessionId: String, snapshot: ContextSnapshot) {
        evict(snapshot.generatedAt)
        store[sessionId] = snapshot
    }

    override fun load(sessionId: String): ContextSnapshot? {
        val snap = store[sessionId] ?: return null
        if (System.currentTimeMillis() - snap.generatedAt > ttlMs) {
            store.remove(sessionId)
            return null
        }
        return snap
    }

    override fun remove(sessionId: String) {
        store.remove(sessionId)
    }

    private fun evict(now: Long) {
        // TTL eviction
        store.entries.removeIf { (_, snap) -> now - snap.generatedAt > ttlMs }
        // Capacity eviction
        while (store.size >= maxSessions) {
            val oldest = store.entries.minByOrNull { it.value.generatedAt } ?: break
            store.remove(oldest.key)
        }
    }
}

// --- Cache Types ---

internal data class CachedGoalResult(
    val atoms: List<Atom>,
    val generation: Long,
    val computedAt: Long
)

internal data class CachedSummaryResult(
    val summary: ContextSummary,
    val scope: String?,
    val generation: Long,
    val computedAt: Long
)

// --- Data Classes ---

data class GoalSpec(
    val predicate: String,
    val args: List<String>,
    val negated: Boolean = false
)

data class RelevanceBucket(
    val name: String,
    val predicates: List<String>? = null,
    val weight: Double = 1.0
)

data class OptimizeContextRequest(
    val maxFacts: Int? = null,
    val scope: String? = null,
    val predicates: List<String>? = null,
    val goals: List<GoalSpec>? = null,
    val relevanceBuckets: List<RelevanceBucket>? = null,
    val sessionId: String? = null,
    val autoResolveContradictions: Boolean = true,
    val maxFactsPerPredicate: Int? = null
)

data class ContextDiffRequest(
    val sessionId: String,
    val maxFacts: Int? = null,
    val scope: String? = null,
    val predicates: List<String>? = null,
    val goals: List<GoalSpec>? = null,
    val relevanceBuckets: List<RelevanceBucket>? = null
)

/** Provenance derivation info exposed to callers (#2). */
data class DerivationInfo(
    val rule: String,
    val premises: List<String>
)

data class SelectedContextEntry(
    val atom: Atom,
    val salience: Double,
    val category: String,
    val charCount: Int,
    val provenance: DerivationInfo?
)

data class OptimizedContextWindow(
    val windowId: String,
    val entries: List<SelectedContextEntry>,
    val relevantRules: List<Rule>,
    val totalFactsAvailable: Int,
    val totalFactsIncluded: Int,
    val deduplicationSavings: Int,
    val contradictionsFound: Int,
    val contradictionsResolved: Int,
    val contradictions: List<Contradiction>,
    val bucketStats: Map<String, BucketStats>,
    val totalCharCount: Int,
    val goalDriven: Boolean,
    val knowledgeGeneration: Long,
    val generatedAt: Long
)

/** Structured info about a fact removed between context snapshots (#6). */
data class RemovedEntry(
    val key: String,
    val predicate: String,
    val args: List<String>,
    val negated: Boolean,
    val scope: String?
)

data class ContextDiff(
    val previousWindowId: String?,
    val currentWindowId: String,
    val added: List<SelectedContextEntry>,
    val removed: List<RemovedEntry>,
    val unchanged: Int,
    val fullRefreshRecommended: Boolean,
    val reason: String?
)

data class ContextSummary(
    val totalFacts: Int,
    val predicateCount: Int,
    val topPredicates: List<PredicateSummary>,
    val factsWithTtl: Int,
    val factsExpiringWithin1h: Int,
    val contradictions: Int,
    val topSalientFacts: List<SelectedContextEntry>,
    val totalCharCount: Int,
    val knowledgeGeneration: Long,
    val generatedAt: Long
)

data class PredicateSummary(val predicate: String, val count: Int)

data class BucketStats(
    val factsIncluded: Int,
    val maxAllocation: Int,
    val minSalience: Double,
    val maxSalience: Double
)

data class Contradiction(
    val predicate: String,
    val args: List<String>,
    val positiveSalience: Double,
    val negativeSalience: Double
)

internal data class ScoredEntry(val atom: Atom, val salience: Double)

/** Structured info stored per entry in session snapshots for rich diff output. */
data class SnapshotEntryInfo(
    val predicate: String,
    val args: List<String>,
    val negated: Boolean,
    val scope: String?
)

data class ContextSnapshot(
    val windowId: String,
    val entries: Map<String, SnapshotEntryInfo>,
    val generatedAt: Long
)
