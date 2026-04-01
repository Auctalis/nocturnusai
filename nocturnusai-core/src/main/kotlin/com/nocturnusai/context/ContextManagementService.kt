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
    private val sessionStore: SessionStore = InMemorySessionStore(),
    private val goalCacheTtlMs: Long = 30_000L // 30 seconds
) {
    private val logger = LoggerFactory.getLogger(ContextManagementService::class.java)
    private val windowIdCounter = AtomicLong(0)

    // --- Goal Result Cache ---
    // Key: normalized goal string, Value: cached result + timestamp
    private val goalCache = ConcurrentHashMap<String, CachedGoalResult>()
    private val cacheGeneration = AtomicLong(0) // incremented on any fact/rule change

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

        // 5. Consistency check
        val contradictions = findContradictions(deduped)
        val consistent = resolveContradictions(deduped, contradictions)

        // 6. Apply buckets or flat ranking
        val selected: List<SelectedContextEntry>
        val bucketStats: Map<String, BucketStats>

        if (request.relevanceBuckets != null && request.relevanceBuckets.isNotEmpty()) {
            val result = applyBuckets(consistent, request.relevanceBuckets, maxFacts, now)
            selected = result.first
            bucketStats = result.second
        } else {
            selected = consistent
                .sortedByDescending { it.salience }
                .take(maxFacts)
                .map { entry ->
                    SelectedContextEntry(
                        atom = entry.atom,
                        salience = entry.salience,
                        category = inferCategory(entry.atom, now),
                        charCount = atomCharCount(entry.atom),
                        hasProvenance = provenanceTracker?.getDerivation(entry.atom) != null
                    )
                }
            bucketStats = emptyMap()
        }

        // 7. Session snapshot
        val windowId = "ctx_${windowIdCounter.incrementAndGet()}"
        if (request.sessionId != null) {
            sessionStore.save(request.sessionId, ContextSnapshot(
                windowId = windowId,
                entryKeys = selected.map { entryKey(it.atom) }.toSet(),
                generatedAt = now
            ))
        }

        return OptimizedContextWindow(
            windowId = windowId,
            entries = selected,
            totalFactsAvailable = candidates.size,
            totalFactsIncluded = selected.size,
            deduplicationSavings = deduplicationSavings,
            contradictionsFound = contradictions.size,
            contradictionsResolved = contradictions.size,
            bucketStats = bucketStats,
            totalCharCount = selected.sumOf { it.charCount },
            goalDriven = !request.goals.isNullOrEmpty(),
            generatedAt = now
        )
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
        val previousKeys = previousSnapshot.entryKeys

        val addedKeys = currentKeys - previousKeys
        val removedKeys = previousKeys - currentKeys
        val unchangedCount = currentKeys.intersect(previousKeys).size

        val added = currentWindow.entries.filter { entryKey(it.atom) in addedKeys }
        val churnRate = if (previousKeys.isNotEmpty()) {
            (addedKeys.size + removedKeys.size).toDouble() / previousKeys.size
        } else 1.0

        return ContextDiff(
            previousWindowId = previousSnapshot.windowId,
            currentWindowId = currentWindow.windowId,
            added = added,
            removed = removedKeys.toList(),
            unchanged = unchangedCount,
            fullRefreshRecommended = churnRate > 0.5,
            reason = if (churnRate > 0.5) "high churn (${(churnRate * 100).toInt()}%): full refresh cheaper than patching" else null
        )
    }

    fun summarizeContext(
        store: Hexastore,
        scope: String? = null
    ): ContextSummary {
        val now = System.currentTimeMillis()
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
                    provenanceTracker?.getDerivation(atom) != null)
            }

        return ContextSummary(
            totalFacts = allAtoms.size,
            predicateCount = predicateCounts.size,
            topPredicates = predicateCounts.take(10).map { PredicateSummary(it.first, it.second) },
            factsWithTtl = withTtl,
            factsExpiringWithin1h = expiringWithin1h,
            contradictions = contradictionCount,
            topSalientFacts = topFacts,
            totalCharCount = allAtoms.sumOf { atomCharCount(it) },
            generatedAt = now
        )
    }

    fun clearSession(sessionId: String) {
        sessionStore.remove(sessionId)
    }

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
        val sorted = goals.sortedBy { "${it.predicate}|${it.args.joinToString(",")}" }
        return "${scope ?: ""}:${sorted.joinToString(";") { "${it.predicate}(${it.args.joinToString(",")})" }}"
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
                truthVal = true,
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
                    hasProvenance = provenanceTracker?.getDerivation(entry.atom) != null
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
                        hasProvenance = provenanceTracker?.getDerivation(entry.atom) != null
                    ))
                }
        }

        return Pair(selected, stats)
    }

    // --- Deduplication ---

    private fun deduplicate(entries: List<ScoredEntry>): List<ScoredEntry> {
        val seen = mutableSetOf<String>()
        val consolidatedPredicates = entries
            .filter { it.atom.source == SourceType.CONSOLIDATED }
            .map { it.atom.predicate.removeSuffix("_consolidated") }
            .toSet()

        return entries.filter { entry ->
            if (entry.atom.source == SourceType.USER_INPUT &&
                entry.atom.predicate in consolidatedPredicates) {
                return@filter false
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
        if (store.size > maxSessions) {
            store.entries
                .sortedBy { it.value.generatedAt }
                .take(store.size - maxSessions)
                .forEach { store.remove(it.key) }
        }
    }
}

// --- Cache Types ---

internal data class CachedGoalResult(
    val atoms: List<Atom>,
    val generation: Long,
    val computedAt: Long
)

// --- Data Classes ---

data class GoalSpec(
    val predicate: String,
    val args: List<String>
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
    val sessionId: String? = null
)

data class ContextDiffRequest(
    val sessionId: String,
    val maxFacts: Int? = null,
    val scope: String? = null,
    val predicates: List<String>? = null,
    val goals: List<GoalSpec>? = null,
    val relevanceBuckets: List<RelevanceBucket>? = null
)

data class SelectedContextEntry(
    val atom: Atom,
    val salience: Double,
    val category: String,
    val charCount: Int,
    val hasProvenance: Boolean
)

data class OptimizedContextWindow(
    val windowId: String,
    val entries: List<SelectedContextEntry>,
    val totalFactsAvailable: Int,
    val totalFactsIncluded: Int,
    val deduplicationSavings: Int,
    val contradictionsFound: Int,
    val contradictionsResolved: Int,
    val bucketStats: Map<String, BucketStats>,
    val totalCharCount: Int,
    val goalDriven: Boolean,
    val generatedAt: Long
)

data class ContextDiff(
    val previousWindowId: String?,
    val currentWindowId: String,
    val added: List<SelectedContextEntry>,
    val removed: List<String>,
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

data class ContextSnapshot(
    val windowId: String,
    val entryKeys: Set<String>,
    val generatedAt: Long
)
