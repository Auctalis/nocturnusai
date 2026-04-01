package com.nocturnusai.context

import com.nocturnusai.core.Atom
import com.nocturnusai.core.Rule
import com.nocturnusai.core.SourceType
import com.nocturnusai.core.Term
import com.nocturnusai.inference.BackwardChainer
import com.nocturnusai.logic.ProvenanceTracker
import com.nocturnusai.memory.MemoryManager
import com.nocturnusai.storage.Hexastore
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * ContextManagementService — goal-driven, consistency-checked context optimization.
 *
 * Unlike the v1 that applied arbitrary category buckets, this version is built
 * around two hard insights:
 *
 * 1. **The agent knows what it needs.** Instead of us guessing categories, the agent
 *    passes a goal (or set of goals) and we use backward chaining to find facts
 *    *actually relevant to that goal*. Facts unreachable from the goal don't enter
 *    the context at all.
 *
 * 2. **Fact count is the honest budget unit.** Token estimation from character counts
 *    is unreliable (BPE tokenizers don't respect predicate boundaries). We budget by
 *    fact count, and report the character count as advisory metadata — not as a first-
 *    class budget constraint.
 *
 * Additional capabilities:
 * - **Consistency checking**: detects contradictions (P and NOT P) before they enter context
 * - **Provenance-aware ranking**: facts with known derivation chains rank higher than orphans
 * - **Deduplication**: consolidated facts subsume their raw sources
 * - **Session diffing**: incremental updates with bounded session storage (TTL + max sessions)
 * - **Agent-defined relevance buckets**: the caller defines categories and weights, not us
 */
class ContextManagementService(
    private val memoryManager: MemoryManager,
    private val backwardChainer: BackwardChainer? = null,
    private val provenanceTracker: ProvenanceTracker? = null,
    private val negativeStore: Hexastore? = null,
    private val maxSessions: Int = 1000,
    private val sessionTtlMs: Long = 3_600_000L // 1 hour
) {
    private val logger = LoggerFactory.getLogger(ContextManagementService::class.java)
    private val sessionWindows = ConcurrentHashMap<String, ContextSnapshot>()
    private val windowIdCounter = AtomicLong(0)

    /**
     * Goal-driven context: "Given these goals, what facts do I actually need?"
     *
     * Uses backward chaining to find facts reachable from each goal, then ranks
     * by salience within that relevant set. Facts not reachable from any goal
     * are excluded entirely — this is the primary token-saving mechanism.
     *
     * If no goals are provided, falls back to salience-ranked retrieval across
     * all valid facts (equivalent to the existing /memory/context behavior,
     * but with consistency checking and dedup).
     */
    fun optimizeContext(
        store: Hexastore,
        request: OptimizeContextRequest
    ): OptimizedContextWindow {
        val now = System.currentTimeMillis()
        val maxFacts = request.maxFacts ?: 100

        // 1. Gather candidate facts — goal-driven or global
        val candidates: List<Atom> = if (request.goals.isNullOrEmpty()) {
            // No goals: fall back to all valid facts (filtered)
            store.getAllAtoms()
                .filter { it.isValidAt(now) }
                .filter { request.scope == null || it.scope == request.scope }
                .filter { request.predicates == null || it.predicate in request.predicates }
                .toList()
        } else {
            // Goal-driven: use backward chaining to find relevant facts
            collectGoalRelevantFacts(store, request.goals, request.scope, now)
        }

        // 2. Score by salience
        val scored = candidates.map { atom ->
            val salience = memoryManager.salienceTracker.computeSalience(atom, now)
            val provenanceBoost = if (provenanceTracker?.getDerivation(atom) != null) 0.05 else 0.0
            ScoredEntry(atom, (salience + provenanceBoost).coerceAtMost(1.0))
        }

        // 3. Deduplicate
        val deduped = deduplicate(scored)
        val deduplicationSavings = scored.size - deduped.size

        // 4. Consistency check — flag contradictions
        val contradictions = findContradictions(deduped, store)

        // 5. Remove contradicted facts (keep the higher-salience version)
        val consistent = resolveContradictions(deduped, contradictions)

        // 6. Apply agent-defined relevance buckets (if provided) or flat ranking
        val selected: List<SelectedContextEntry>
        val bucketStats: Map<String, BucketStats>

        if (request.relevanceBuckets != null && request.relevanceBuckets.isNotEmpty()) {
            val result = applyBuckets(consistent, request.relevanceBuckets, maxFacts)
            selected = result.first
            bucketStats = result.second
        } else {
            // Flat ranking: just take top N by salience
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

        // 7. Session snapshot for diffing
        val windowId = "ctx_${windowIdCounter.incrementAndGet()}"
        if (request.sessionId != null) {
            evictStaleSessions(now)
            sessionWindows[request.sessionId] = ContextSnapshot(
                windowId = windowId,
                entryKeys = selected.map { entryKey(it.atom) }.toSet(),
                generatedAt = now
            )
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

    /**
     * Incremental diff: what changed since the last window for this session?
     *
     * Session state is bounded by maxSessions and sessionTtlMs to prevent leaks.
     */
    fun diffContext(
        store: Hexastore,
        request: ContextDiffRequest
    ): ContextDiff {
        val previousSnapshot = sessionWindows[request.sessionId]
            ?: return ContextDiff(
                previousWindowId = null,
                currentWindowId = "none",
                added = emptyList(),
                removed = emptyList(),
                unchanged = 0,
                fullRefreshRecommended = true,
                reason = "no previous session found"
            )

        // Build current window
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

    /**
     * Compact knowledge base summary for system prompts.
     */
    fun summarizeContext(
        store: Hexastore,
        scope: String? = null
    ): ContextSummary {
        val now = System.currentTimeMillis()
        val allAtoms = store.getAllAtoms()
            .filter { it.isValidAt(now) }
            .filter { scope == null || it.scope == scope }
            .toList()

        val predicateCounts = allAtoms.groupBy { it.predicate }
            .mapValues { (_, atoms) -> atoms.size }
            .toList()
            .sortedByDescending { it.second }

        val withTtl = allAtoms.count { it.ttl != null }
        val expiringWithin1h = allAtoms.count { atom ->
            atom.ttl != null && atom.createdAt != null &&
                (atom.createdAt + atom.ttl) < now + 3_600_000L
        }

        val contradictionPairs = findContradictionsGlobal(allAtoms, store)

        val topFacts = allAtoms
            .map { it to memoryManager.salienceTracker.computeSalience(it, now) }
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
            contradictions = contradictionPairs,
            topSalientFacts = topFacts,
            totalCharCount = allAtoms.sumOf { atomCharCount(it) },
            generatedAt = now
        )
    }

    fun clearSession(sessionId: String) {
        sessionWindows.remove(sessionId)
    }

    // --- Goal-Driven Relevance ---

    /**
     * Use backward chaining to discover which facts are reachable from the given goals.
     * This is the key differentiator: instead of returning "top facts globally," we
     * return "facts the agent actually needs to reason about its current goals."
     */
    private fun collectGoalRelevantFacts(
        store: Hexastore,
        goals: List<GoalSpec>,
        scope: String?,
        now: Long
    ): List<Atom> {
        val relevant = linkedSetOf<Atom>() // preserve insertion order, dedup

        for (goal in goals) {
            val goalAtom = Atom(
                predicate = goal.predicate,
                args = goal.args.map { parseTerm(it) },
                truthVal = true,
                scope = scope
            )

            // Direct matches from store
            store.match(goalAtom, scope = scope)
                .filter { it.isValidAt(now) }
                .forEach { relevant.add(it) }

            // Backward chaining: find facts reachable via rules
            if (backwardChainer != null) {
                try {
                    backwardChainer.solve(goalAtom)
                        .filter { it.isValidAt(now) }
                        .forEach { result ->
                            relevant.add(result)
                            // Also add premises that support this result
                            collectPremises(result, relevant)
                        }
                } catch (e: Exception) {
                    logger.debug("Backward chaining failed for goal {}: {}", goalAtom, e.message)
                }
            }
        }

        return relevant.toList()
    }

    /**
     * Walk the provenance chain to include supporting premises.
     * If mortal(socrates) was derived from human(socrates) + a rule,
     * the agent needs human(socrates) in context too.
     */
    private fun collectPremises(fact: Atom, collected: MutableSet<Atom>) {
        val derivation = provenanceTracker?.getDerivation(fact) ?: return
        for (premise in derivation.premises) {
            if (collected.add(premise)) {
                collectPremises(premise, collected) // recursive: walk full derivation chain
            }
        }
    }

    // --- Consistency Checking ---

    /**
     * Find facts where both P(args) and NOT P(args) appear in the candidate set
     * or where the candidate set conflicts with the negative store.
     */
    private fun findContradictions(
        entries: List<ScoredEntry>,
        store: Hexastore
    ): List<Contradiction> {
        val contradictions = mutableListOf<Contradiction>()
        val byKey = entries.groupBy { "${it.atom.predicate}|${it.atom.args.joinToString(",")}" }

        for ((key, group) in byKey) {
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
                    val existing = contradictions.any {
                        it.predicate == entry.atom.predicate &&
                        it.args == entry.atom.args.map { a -> a.toString() }
                    }
                    if (!existing) {
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

    /**
     * Lightweight global contradiction scan for summaries.
     */
    private fun findContradictionsGlobal(atoms: List<Atom>, store: Hexastore): Int {
        val byKey = atoms.groupBy { "${it.predicate}|${it.args.joinToString(",")}" }
        var count = 0
        for ((_, group) in byKey) {
            val hasPos = group.any { it.truthVal }
            val hasNeg = group.any { !it.truthVal }
            if (hasPos && hasNeg) count++
        }
        return count
    }

    /**
     * Resolve contradictions by keeping the higher-salience side.
     */
    private fun resolveContradictions(
        entries: List<ScoredEntry>,
        contradictions: List<Contradiction>
    ): List<ScoredEntry> {
        if (contradictions.isEmpty()) return entries

        val contradictedKeys = contradictions.map { c ->
            val keepPositive = c.positiveSalience >= c.negativeSalience
            Triple("${c.predicate}|${c.args.joinToString(",")}", keepPositive, !keepPositive)
        }

        return entries.filter { entry ->
            val key = "${entry.atom.predicate}|${entry.atom.args.joinToString(",")}"
            val rule = contradictedKeys.find { it.first == key }
            if (rule == null) {
                true // not contradicted
            } else {
                // Keep the side with higher salience
                if (entry.atom.truthVal) rule.second else rule.third
            }
        }
    }

    // --- Agent-Defined Relevance Buckets ---

    /**
     * The agent defines its own buckets with predicates and weight.
     * We fill each bucket proportionally, then use remaining budget for overflow.
     *
     * This replaces the hardcoded category system. The agent knows its domain:
     * - A cooking agent: buckets for "ingredients", "steps", "preferences"
     * - A code agent: buckets for "functions", "types", "errors"
     * - A planning agent: buckets for "goals", "constraints", "resources"
     */
    private fun applyBuckets(
        entries: List<ScoredEntry>,
        buckets: List<RelevanceBucket>,
        totalMaxFacts: Int
    ): Pair<List<SelectedContextEntry>, Map<String, BucketStats>> {
        val now = System.currentTimeMillis()
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
                val key = entryKey(entry.atom)
                usedAtomKeys.add(key)
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

        // Fill remaining budget with unclaimed facts
        val remaining = totalMaxFacts - selected.size
        if (remaining > 0) {
            val overflow = entries
                .filter { entryKey(it.atom) !in usedAtomKeys }
                .sortedByDescending { it.salience }
                .take(remaining)

            for (entry in overflow) {
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

    /**
     * Remove exact duplicates and facts subsumed by consolidated versions.
     * Does NOT attempt semantic dedup (that requires embeddings we don't have).
     */
    private fun deduplicate(entries: List<ScoredEntry>): List<ScoredEntry> {
        val seen = mutableSetOf<String>()
        val consolidatedPredicates = entries
            .filter { it.atom.source == SourceType.CONSOLIDATED }
            .map { it.atom.predicate.removeSuffix("_consolidated") }
            .toSet()

        return entries.filter { entry ->
            // Skip raw facts that have been consolidated
            if (entry.atom.source == SourceType.USER_INPUT &&
                entry.atom.predicate in consolidatedPredicates) {
                return@filter false
            }
            // Skip exact duplicates
            seen.add(entryKey(entry.atom))
        }
    }

    // --- Session Management ---

    /**
     * Evict sessions older than TTL or when over capacity.
     * Prevents unbounded memory growth.
     */
    private fun evictStaleSessions(now: Long) {
        // TTL eviction
        sessionWindows.entries.removeIf { (_, snap) ->
            now - snap.generatedAt > sessionTtlMs
        }
        // Capacity eviction: remove oldest if over limit
        if (sessionWindows.size > maxSessions) {
            val toRemove = sessionWindows.entries
                .sortedBy { it.value.generatedAt }
                .take(sessionWindows.size - maxSessions)
            for (entry in toRemove) {
                sessionWindows.remove(entry.key)
            }
        }
    }

    // --- Utilities ---

    /**
     * Infer a category label for display purposes only.
     * This is descriptive metadata, NOT a budget allocation mechanism.
     */
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

    /**
     * Character count of a fact's string representation.
     * Reported as advisory metadata — NOT used for budget decisions.
     * The caller (SDK/agent) can apply their own tokenizer if they want token counts.
     */
    private fun atomCharCount(atom: Atom): Int {
        return atom.toString().length
    }

    private fun entryKey(atom: Atom): String {
        return "${atom.predicate}|${atom.args.joinToString(",")}|${atom.truthVal}|${atom.scope ?: ""}"
    }

    private fun parseTerm(str: String): Term {
        return if (str.startsWith("?")) {
            Term.Variable(str.drop(1))
        } else {
            str.toDoubleOrNull()?.let { Term.NumberLit(it) } ?: Term.Identifier(str)
        }
    }
}

// --- Data Classes ---

data class GoalSpec(
    val predicate: String,
    val args: List<String>
)

data class RelevanceBucket(
    val name: String,
    val predicates: List<String>? = null, // null = match all
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

data class PredicateSummary(
    val predicate: String,
    val count: Int
)

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

// Internal types
internal data class ScoredEntry(
    val atom: Atom,
    val salience: Double
)

internal data class ContextSnapshot(
    val windowId: String,
    val entryKeys: Set<String>,
    val generatedAt: Long
)
