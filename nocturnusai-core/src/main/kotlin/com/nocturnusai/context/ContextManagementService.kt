package com.nocturnusai.context

import com.nocturnusai.core.Atom
import com.nocturnusai.memory.MemoryManager
import com.nocturnusai.memory.SalienceTracker
import com.nocturnusai.storage.Hexastore
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * ContextManagementService — token-aware context optimization for AI agents.
 *
 * The problem: Large context windows are expensive. Agents re-send stale, redundant,
 * and low-relevance facts every turn, wasting tokens and degrading reasoning quality.
 *
 * This service solves that by:
 * 1. **Token budgeting** — fits context into a token budget, not just fact count
 * 2. **Category allocation** — distributes budget across categories (core_knowledge, recent_facts, inferred, rules)
 * 3. **Deduplication** — eliminates redundant and subsumed facts
 * 4. **Compression** — groups related facts into compact summaries
 * 5. **Context diffing** — returns only what changed since last window, for incremental updates
 * 6. **Freshness gating** — excludes expired/invalid facts before they consume budget
 * 7. **Importance-weighted allocation** — high-salience facts get priority within each category
 */
class ContextManagementService(
    private val memoryManager: MemoryManager,
    private val defaultTokenBudget: Int = 4000,
    private val avgTokensPerFact: Int = 25
) {
    private val logger = LoggerFactory.getLogger(ContextManagementService::class.java)
    private val sessionWindows = ConcurrentHashMap<String, ContextSnapshot>()
    private val windowIdCounter = AtomicLong(0)

    /**
     * Build an optimized context window within a token budget.
     *
     * This is the primary entry point. Given a store full of facts, it:
     * 1. Filters out expired/invalid facts
     * 2. Scores every fact by salience
     * 3. Categorizes facts (core knowledge, recent, inferred, rules)
     * 4. Deduplicates within categories
     * 5. Allocates token budget across categories
     * 6. Fills each category's allocation with highest-salience facts
     * 7. Returns an optimized window with token estimates and metadata
     */
    fun optimizeContext(
        store: Hexastore,
        request: OptimizeContextRequest
    ): OptimizedContextWindow {
        val now = System.currentTimeMillis()
        val budget = request.tokenBudget ?: defaultTokenBudget
        val categoryWeights = request.categoryWeights ?: DEFAULT_CATEGORY_WEIGHTS

        // 1. Gather all valid facts
        val allAtoms = store.getAllAtoms()
            .filter { it.isValidAt(now) }
            .filter { request.scope == null || it.scope == request.scope }
            .filter { request.predicates == null || it.predicate in request.predicates }
            .toList()

        // 2. Score and categorize
        val scored = allAtoms.map { atom ->
            val salience = memoryManager.salienceTracker.computeSalience(atom, now)
            val category = categorize(atom)
            val tokenEstimate = estimateTokens(atom)
            ScoredContextEntry(atom, salience, category, tokenEstimate)
        }

        // 3. Deduplicate
        val deduped = deduplicate(scored)
        val deduplicationSavings = scored.size - deduped.size

        // 4. Group by category
        val byCategory = deduped.groupBy { it.category }

        // 5. Allocate budget across categories
        val allocations = allocateBudget(budget, categoryWeights, byCategory)

        // 6. Fill each category with top facts within its token allocation
        val selectedEntries = mutableListOf<SelectedContextEntry>()
        val categoryStats = mutableMapOf<String, CategoryStats>()

        for ((category, allocation) in allocations) {
            val candidates = (byCategory[category] ?: emptyList())
                .sortedByDescending { it.salience }

            var tokensUsed = 0
            val selected = mutableListOf<ScoredContextEntry>()

            for (entry in candidates) {
                if (tokensUsed + entry.tokenEstimate > allocation.tokenBudget) continue
                selected.add(entry)
                tokensUsed += entry.tokenEstimate
            }

            for (entry in selected) {
                selectedEntries.add(
                    SelectedContextEntry(
                        atom = entry.atom,
                        salience = entry.salience,
                        category = entry.category,
                        tokenEstimate = entry.tokenEstimate
                    )
                )
            }

            categoryStats[category] = CategoryStats(
                factsAvailable = (byCategory[category] ?: emptyList()).size,
                factsIncluded = selected.size,
                tokensAllocated = allocation.tokenBudget,
                tokensUsed = tokensUsed,
                minSalience = selected.minOfOrNull { it.salience } ?: 0.0,
                maxSalience = selected.maxOfOrNull { it.salience } ?: 0.0
            )
        }

        // 7. Sort final selection by salience (highest first)
        selectedEntries.sortByDescending { it.salience }

        val totalTokensUsed = selectedEntries.sumOf { it.tokenEstimate }

        // 8. Compress related facts if requested
        val compressions = if (request.enableCompression) {
            compressRelatedFacts(selectedEntries)
        } else {
            emptyList()
        }

        val compressionSavings = compressions.sumOf { it.tokensSaved }

        // 9. Create window and snapshot for diffing
        val windowId = "ctx_${windowIdCounter.incrementAndGet()}"

        val window = OptimizedContextWindow(
            windowId = windowId,
            entries = selectedEntries,
            compressions = compressions,
            totalTokenBudget = budget,
            totalTokensUsed = totalTokensUsed - compressionSavings,
            totalFactsAvailable = allAtoms.size,
            totalFactsIncluded = selectedEntries.size,
            deduplicationSavings = deduplicationSavings,
            compressionSavings = compressionSavings,
            categoryStats = categoryStats,
            generatedAt = now
        )

        // Store snapshot for future diffing
        if (request.sessionId != null) {
            sessionWindows[request.sessionId] = ContextSnapshot(
                windowId = windowId,
                entryKeys = selectedEntries.map { entryKey(it.atom) }.toSet(),
                generatedAt = now
            )
        }

        return window
    }

    /**
     * Compute what changed since a previous context window.
     *
     * Returns only the additions, removals, and updates — so the agent
     * can apply an incremental patch instead of re-sending everything.
     * This can save 50-90% of tokens on subsequent turns.
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
                updated = emptyList(),
                unchanged = 0,
                tokensSaved = 0,
                fullRefreshRecommended = true
            )

        // Build current optimized window
        val currentWindow = optimizeContext(store, OptimizeContextRequest(
            tokenBudget = request.tokenBudget,
            scope = request.scope,
            predicates = request.predicates,
            categoryWeights = request.categoryWeights,
            sessionId = request.sessionId,
            enableCompression = request.enableCompression
        ))

        val currentKeys = currentWindow.entries.map { entryKey(it.atom) }.toSet()
        val previousKeys = previousSnapshot.entryKeys

        val addedKeys = currentKeys - previousKeys
        val removedKeys = previousKeys - currentKeys
        val unchangedKeys = currentKeys.intersect(previousKeys)

        val added = currentWindow.entries.filter { entryKey(it.atom) in addedKeys }
        val removed = removedKeys.toList()

        // Estimate tokens saved by diffing vs full refresh
        val fullRefreshTokens = currentWindow.totalTokensUsed
        val diffTokens = added.sumOf { it.tokenEstimate } + removedKeys.size * 5 // ~5 tokens per removal notice
        val tokensSaved = (fullRefreshTokens - diffTokens).coerceAtLeast(0)

        return ContextDiff(
            previousWindowId = previousSnapshot.windowId,
            currentWindowId = currentWindow.windowId,
            added = added,
            removed = removed,
            updated = emptyList(), // Future: detect salience changes
            unchanged = unchangedKeys.size,
            tokensSaved = tokensSaved,
            fullRefreshRecommended = addedKeys.size + removedKeys.size > unchangedKeys.size
        )
    }

    /**
     * Get a context summary — a compact representation of the knowledge base
     * suitable for system prompts or high-level agent briefings.
     */
    fun summarizeContext(
        store: Hexastore,
        scope: String? = null,
        maxTokens: Int = 500
    ): ContextSummary {
        val now = System.currentTimeMillis()
        val allAtoms = store.getAllAtoms()
            .filter { it.isValidAt(now) }
            .filter { scope == null || it.scope == scope }
            .toList()

        // Predicate distribution
        val predicateCounts = allAtoms.groupBy { it.predicate }
            .mapValues { (_, atoms) -> atoms.size }
            .toList()
            .sortedByDescending { it.second }

        // Top predicates by frequency
        val topPredicates = predicateCounts.take(10).map { (pred, count) ->
            PredicateSummary(pred, count)
        }

        // Temporal stats
        val withTtl = allAtoms.count { it.ttl != null }
        val expiringWithin1h = allAtoms.count { atom ->
            atom.ttl != null && atom.createdAt != null &&
                (atom.createdAt + atom.ttl) < now + 3_600_000L
        }

        // Category distribution
        val categories = allAtoms.groupBy { categorize(it) }
            .mapValues { (_, atoms) -> atoms.size }

        // Top salient facts
        val topFacts = allAtoms
            .map { it to memoryManager.salienceTracker.computeSalience(it, now) }
            .sortedByDescending { it.second }
            .take(5)
            .map { (atom, score) ->
                SelectedContextEntry(atom, score, categorize(atom), estimateTokens(atom))
            }

        return ContextSummary(
            totalFacts = allAtoms.size,
            predicateCount = predicateCounts.size,
            topPredicates = topPredicates,
            categoryDistribution = categories,
            factsWithTtl = withTtl,
            factsExpiringWithin1h = expiringWithin1h,
            topSalientFacts = topFacts,
            estimatedTotalTokens = allAtoms.sumOf { estimateTokens(it) },
            generatedAt = now
        )
    }

    /**
     * Clear session state (call when agent session ends).
     */
    fun clearSession(sessionId: String) {
        sessionWindows.remove(sessionId)
    }

    // --- Internal Logic ---

    /**
     * Categorize an atom into one of the context categories.
     * Categories determine budget allocation priority.
     */
    internal fun categorize(atom: Atom): String {
        return when (atom.source) {
            com.nocturnusai.core.SourceType.INFERRED -> CATEGORY_INFERRED
            com.nocturnusai.core.SourceType.CONSOLIDATED -> CATEGORY_CONSOLIDATED
            com.nocturnusai.core.SourceType.USER_INPUT -> {
                // Distinguish core knowledge from recent facts based on age
                val age = if (atom.createdAt != null) {
                    System.currentTimeMillis() - atom.createdAt
                } else {
                    Long.MAX_VALUE
                }
                when {
                    atom.ttl != null -> CATEGORY_EPHEMERAL
                    age < 300_000L -> CATEGORY_RECENT // < 5 minutes old
                    else -> CATEGORY_CORE_KNOWLEDGE
                }
            }
        }
    }

    /**
     * Estimate token count for a fact's string representation.
     * Uses a simple heuristic: ~4 characters per token for structured data.
     */
    internal fun estimateTokens(atom: Atom): Int {
        val predLen = atom.predicate.length
        val argsLen = atom.args.sumOf { it.toString().length + 2 } // +2 for separators
        val metaLen = if (atom.metadata.isNotEmpty()) {
            atom.metadata.entries.sumOf { (k, v) -> k.length + v.toString().length + 4 }
        } else 0
        val totalChars = predLen + argsLen + metaLen + 10 // overhead for formatting
        return (totalChars / 4).coerceAtLeast(5) // minimum 5 tokens per fact
    }

    /**
     * Deduplicate facts — remove entries that are subsumed by or identical to others.
     *
     * Dedup strategies:
     * - Exact duplicates (same predicate + args, different temporal fields)
     * - Subsumption: consolidated facts supersede their raw episodic sources
     */
    internal fun deduplicate(entries: List<ScoredContextEntry>): List<ScoredContextEntry> {
        val seen = mutableSetOf<String>()
        val consolidatedPredicates = entries
            .filter { it.atom.source == com.nocturnusai.core.SourceType.CONSOLIDATED }
            .map { it.atom.predicate.removeSuffix("_consolidated") }
            .toSet()

        return entries.filter { entry ->
            val key = entryKey(entry.atom)

            // Skip raw facts that have been consolidated
            if (entry.atom.source == com.nocturnusai.core.SourceType.USER_INPUT &&
                entry.atom.predicate in consolidatedPredicates
            ) {
                return@filter false
            }

            // Skip exact duplicates
            seen.add(key)
        }
    }

    /**
     * Allocate token budget across categories using weights.
     * Categories with more available facts get proportionally more budget,
     * but weights set the maximum share each category can claim.
     */
    internal fun allocateBudget(
        totalBudget: Int,
        weights: Map<String, Double>,
        available: Map<String, List<ScoredContextEntry>>
    ): Map<String, BudgetAllocation> {
        val allocations = mutableMapOf<String, BudgetAllocation>()

        // First pass: compute weighted shares
        val totalWeight = weights.values.sum()
        val activeCats = weights.keys.filter { (available[it]?.size ?: 0) > 0 }

        if (activeCats.isEmpty()) return allocations

        // Redistribute budget from empty categories to active ones
        val activeWeight = activeCats.sumOf { weights[it] ?: 0.0 }
        val scaleFactor = if (activeWeight > 0) totalWeight / activeWeight else 1.0

        for (cat in activeCats) {
            val weight = (weights[cat] ?: 0.0) * scaleFactor
            val share = (totalBudget * weight / totalWeight).toInt()
            val needed = (available[cat] ?: emptyList()).sumOf { it.tokenEstimate }
            allocations[cat] = BudgetAllocation(
                tokenBudget = min(share, needed),
                weight = weight / totalWeight
            )
        }

        // Second pass: redistribute unused budget to categories that need more
        val usedBudget = allocations.values.sumOf { it.tokenBudget }
        var surplus = totalBudget - usedBudget

        if (surplus > 0) {
            for (cat in activeCats.sortedByDescending { weights[it] ?: 0.0 }) {
                val current = allocations[cat] ?: continue
                val needed = (available[cat] ?: emptyList()).sumOf { it.tokenEstimate }
                val canUse = (needed - current.tokenBudget).coerceAtLeast(0)
                val extra = min(surplus, canUse)
                if (extra > 0) {
                    allocations[cat] = current.copy(tokenBudget = current.tokenBudget + extra)
                    surplus -= extra
                }
            }
        }

        return allocations
    }

    /**
     * Compress related facts into grouped summaries.
     * Example: 5 facts about "likes(alice, X)" → one compressed entry
     * "alice likes: [bob, charlie, dogs, hiking, music]"
     */
    internal fun compressRelatedFacts(entries: List<SelectedContextEntry>): List<Compression> {
        val compressions = mutableListOf<Compression>()

        // Group by predicate + first arg (common pattern: subject-predicate-object)
        val groups = entries
            .filter { it.atom.args.size >= 2 }
            .groupBy { "${it.atom.predicate}|${it.atom.args.first()}" }

        for ((groupKey, group) in groups) {
            if (group.size < 3) continue // Only compress groups of 3+

            val parts = groupKey.split("|", limit = 2)
            val predicate = parts[0]
            val subject = parts.getOrElse(1) { "" }

            val objects = group.map { it.atom.args.drop(1).joinToString(", ") { t -> t.toString() } }
            val compressed = "$predicate($subject, [${objects.joinToString(", ")}])"

            val originalTokens = group.sumOf { it.tokenEstimate }
            val compressedTokens = (compressed.length / 4).coerceAtLeast(5)

            if (compressedTokens < originalTokens) {
                compressions.add(
                    Compression(
                        groupKey = groupKey,
                        predicate = predicate,
                        subject = subject,
                        compressedForm = compressed,
                        originalCount = group.size,
                        originalTokens = originalTokens,
                        compressedTokens = compressedTokens,
                        tokensSaved = originalTokens - compressedTokens
                    )
                )
            }
        }

        return compressions
    }

    private fun entryKey(atom: Atom): String {
        return "${atom.predicate}|${atom.args.joinToString(",")}|${atom.truthVal}|${atom.scope ?: ""}"
    }

    companion object {
        // Category names
        const val CATEGORY_CORE_KNOWLEDGE = "core_knowledge"
        const val CATEGORY_RECENT = "recent"
        const val CATEGORY_INFERRED = "inferred"
        const val CATEGORY_CONSOLIDATED = "consolidated"
        const val CATEGORY_EPHEMERAL = "ephemeral"

        // Default budget allocation weights (must sum to ~1.0)
        val DEFAULT_CATEGORY_WEIGHTS = mapOf(
            CATEGORY_CORE_KNOWLEDGE to 0.35,
            CATEGORY_RECENT to 0.30,
            CATEGORY_INFERRED to 0.15,
            CATEGORY_CONSOLIDATED to 0.10,
            CATEGORY_EPHEMERAL to 0.10
        )
    }
}

// --- Data Classes ---

data class OptimizeContextRequest(
    val tokenBudget: Int? = null,
    val scope: String? = null,
    val predicates: List<String>? = null,
    val categoryWeights: Map<String, Double>? = null,
    val sessionId: String? = null,
    val enableCompression: Boolean = false,
    val minSalience: Double = 0.0
)

data class ContextDiffRequest(
    val sessionId: String,
    val tokenBudget: Int? = null,
    val scope: String? = null,
    val predicates: List<String>? = null,
    val categoryWeights: Map<String, Double>? = null,
    val enableCompression: Boolean = false
)

data class SelectedContextEntry(
    val atom: Atom,
    val salience: Double,
    val category: String,
    val tokenEstimate: Int
)

data class OptimizedContextWindow(
    val windowId: String,
    val entries: List<SelectedContextEntry>,
    val compressions: List<Compression>,
    val totalTokenBudget: Int,
    val totalTokensUsed: Int,
    val totalFactsAvailable: Int,
    val totalFactsIncluded: Int,
    val deduplicationSavings: Int,
    val compressionSavings: Int,
    val categoryStats: Map<String, CategoryStats>,
    val generatedAt: Long
)

data class ContextDiff(
    val previousWindowId: String?,
    val currentWindowId: String,
    val added: List<SelectedContextEntry>,
    val removed: List<String>, // entry keys
    val updated: List<SelectedContextEntry>,
    val unchanged: Int,
    val tokensSaved: Int,
    val fullRefreshRecommended: Boolean
)

data class ContextSummary(
    val totalFacts: Int,
    val predicateCount: Int,
    val topPredicates: List<PredicateSummary>,
    val categoryDistribution: Map<String, Int>,
    val factsWithTtl: Int,
    val factsExpiringWithin1h: Int,
    val topSalientFacts: List<SelectedContextEntry>,
    val estimatedTotalTokens: Int,
    val generatedAt: Long
)

data class PredicateSummary(
    val predicate: String,
    val count: Int
)

data class CategoryStats(
    val factsAvailable: Int,
    val factsIncluded: Int,
    val tokensAllocated: Int,
    val tokensUsed: Int,
    val minSalience: Double,
    val maxSalience: Double
)

data class Compression(
    val groupKey: String,
    val predicate: String,
    val subject: String,
    val compressedForm: String,
    val originalCount: Int,
    val originalTokens: Int,
    val compressedTokens: Int,
    val tokensSaved: Int
)

// Internal types
internal data class ScoredContextEntry(
    val atom: Atom,
    val salience: Double,
    val category: String,
    val tokenEstimate: Int
)

internal data class BudgetAllocation(
    val tokenBudget: Int,
    val weight: Double
)

internal data class ContextSnapshot(
    val windowId: String,
    val entryKeys: Set<String>,
    val generatedAt: Long
)
