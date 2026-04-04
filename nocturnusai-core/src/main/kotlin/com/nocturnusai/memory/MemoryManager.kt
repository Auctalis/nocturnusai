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

package com.nocturnusai.memory

import com.nocturnusai.core.Atom
import com.nocturnusai.core.SourceType
import com.nocturnusai.core.Term
import com.nocturnusai.storage.Hexastore
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * MemoryManager — the agent-facing memory lifecycle controller.
 *
 * This is the core differentiator for NocturnusAI as an agentic AI context server.
 * It handles the full memory lifecycle that agents need:
 *
 * 1. **Temporal queries**: "What was true at time T?"
 * 2. **Salience-ranked retrieval**: "What are the most relevant facts right now?"
 * 3. **Consolidation**: Compressing repeated episodic patterns into semantic facts
 * 4. **Decay & forgetting**: TTL expiration, salience-based eviction
 * 5. **Context windows**: Retrieving an optimal subset of knowledge for an agent step
 */
class MemoryManager(
    val salienceTracker: SalienceTracker = SalienceTracker(),
    val eventBus: EventBus = EventBus(),
    private val evictionThreshold: Double = 0.05,
    private val maxFactsPerTenant: Int = 100_000,
    private val consolidationMinCount: Int = 5
) {
    private val logger = LoggerFactory.getLogger(MemoryManager::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Track predicate access patterns for consolidation
    private val predicateAccessCounts = ConcurrentHashMap<String, Long>()

    /**
     * Query facts valid at a specific point in time.
     * Filters out atoms whose validFrom/validUntil/TTL excludes the given timestamp.
     */
    fun queryAtTime(store: Hexastore, pattern: Atom, timestamp: Long, atomScope: String? = null): List<Atom> {
        return store.match(pattern, scope = atomScope)
            .filter { it.isValidAt(timestamp) }
            .toList()
    }

    /**
     * Query facts with salience-ranked results.
     * Returns atoms sorted by salience score (highest first), optionally limited.
     */
    fun queryWithSalience(
        store: Hexastore,
        pattern: Atom,
        atomScope: String? = null,
        limit: Int = 50,
        minSalience: Double = 0.0
    ): List<ScoredAtom> {
        val now = System.currentTimeMillis()
        return store.match(pattern, scope = atomScope)
            .filter { it.isValidAt(now) }
            .map { atom ->
                salienceTracker.recordAccess(atom)
                ScoredAtom(atom, salienceTracker.computeSalience(atom, now))
            }
            .filter { it.salience >= minSalience }
            .sortedByDescending { it.salience }
            .take(limit)
            .toList()
    }

    /**
     * Build an optimal context window for an agent.
     * Returns the top-N most salient facts across all predicates,
     * respecting temporal validity and optional scope filtering.
     */
    fun buildContextWindow(
        store: Hexastore,
        atomScope: String? = null,
        maxFacts: Int = 100,
        minSalience: Double = 0.0,
        predicates: List<String>? = null // null = all predicates
    ): ContextWindow {
        val now = System.currentTimeMillis()
        val allAtoms = store.getAllAtoms()
            .filter { it.isValidAt(now) }
            .filter { atomScope == null || it.scope == atomScope }
            .filter { predicates == null || it.predicate in predicates }

        val scored = allAtoms.map { atom ->
            ScoredAtom(atom, salienceTracker.computeSalience(atom, now))
        }.filter { it.salience >= minSalience }
            .sortedByDescending { it.salience }
            .take(maxFacts)
            .toList()

        val byPredicate = scored.groupBy { it.atom.predicate }
            .mapValues { (_, atoms) -> atoms.size }

        return ContextWindow(
            facts = scored,
            totalAvailable = store.getAllAtoms().count(),
            windowSize = scored.size,
            predicateDistribution = byPredicate,
            generatedAt = now
        )
    }

    /**
     * Run consolidation: detect repeated episodic patterns and compress
     * them into semantic facts.
     *
     * Example: If "user_asked(agent, topic_X)" appears 10+ times,
     * consolidate into "user_interested_in(agent, topic_X)" with source=CONSOLIDATED.
     */
    @Suppress("UNUSED_PARAMETER")
    fun consolidate(
        store: Hexastore,
        retractConsolidated: (Atom) -> Unit,
        assertConsolidated: (Atom) -> Unit,
        tenantId: String? = null
    ): ConsolidationResult {
        val now = System.currentTimeMillis()
        val allAtoms = store.getAllAtoms().filter { it.isValidAt(now) }.toList()

        // Group by predicate + arg pattern (ignoring specific values for variable positions)
        val groups = allAtoms.groupBy { "${it.predicate}/${it.args.size}" }
        var consolidated = 0
        val newFacts = mutableListOf<Atom>()

        for ((_, atoms) in groups) {
            if (atoms.size < consolidationMinCount) continue

            // Check if these are repetitive (same predicate, varying args)
            val predicate = atoms.first().predicate

            // Count argument patterns: group by first arg (often the subject/agent)
            val byFirstArg = atoms.groupBy { it.args.firstOrNull()?.toString() ?: "" }

            for ((firstArg, group) in byFirstArg) {
                if (group.size < consolidationMinCount) continue
                if (firstArg.isBlank()) continue

                // Create a consolidated fact
                val consolidatedAtom = Atom(
                    predicate = "${predicate}_consolidated",
                    args = listOf(
                        Term.Identifier(firstArg),
                        Term.NumberLit(group.size.toDouble())
                    ),
                    truthVal = true,
                    source = SourceType.CONSOLIDATED,
                    scope = group.first().scope,
                    createdAt = now,
                    validFrom = now
                )

                // Only consolidate if we haven't already
                val existing = store.match(consolidatedAtom).toList()
                if (existing.isEmpty()) {
                    assertConsolidated(consolidatedAtom)
                    newFacts.add(consolidatedAtom)
                    consolidated++

                    eventBus.publish(
                        KnowledgeEvent.ConsolidationOccurred(
                            consolidatedFact = consolidatedAtom,
                            sourceCount = group.size,
                            tenantId = tenantId
                        )
                    )
                }
            }
        }

        return ConsolidationResult(
            factsConsolidated = consolidated,
            newFacts = newFacts,
            timestamp = now
        )
    }

    /**
     * Run decay: find and expire facts that have fallen below the eviction threshold
     * or have exceeded their TTL.
     */
    fun runDecay(
        store: Hexastore,
        retractFact: (Atom) -> Unit,
        tenantId: String? = null,
        forceThreshold: Double? = null
    ): DecayResult {
        val now = System.currentTimeMillis()
        val threshold = forceThreshold ?: evictionThreshold
        var expired = 0
        var evicted = 0
        val removedAtoms = mutableListOf<Atom>()

        // 1. TTL/validUntil expiration
        val allAtoms = store.getAllAtoms().toList()
        for (atom in allAtoms) {
            if (atom.isExpired(now)) {
                retractFact(atom)
                salienceTracker.remove(atom)
                removedAtoms.add(atom)
                expired++
                eventBus.publish(KnowledgeEvent.FactExpired(atom, tenantId))
            }
        }

        // 2. Salience-based eviction (only if we're over capacity)
        val remaining = store.getAllAtoms().count()
        if (remaining > maxFactsPerTenant) {
            val lowSalience = salienceTracker.getBelowThreshold(threshold, now)
            val toEvict = (remaining - maxFactsPerTenant).coerceAtMost(lowSalience.size)

            for (key in lowSalience.take(toEvict)) {
                // Find the actual atom in the store
                val pattern = Atom(
                    predicate = key.predicate,
                    args = key.args.map { Term.Identifier(it) },
                    truthVal = key.truthVal,
                    scope = key.scope
                )
                val found = store.match(pattern).firstOrNull()
                if (found != null) {
                    retractFact(found)
                    salienceTracker.remove(found)
                    removedAtoms.add(found)
                    evicted++
                }
            }
        }

        return DecayResult(
            expiredCount = expired,
            evictedCount = evicted,
            removedAtoms = removedAtoms,
            timestamp = now
        )
    }

    /** Record a fact assertion for salience and event tracking. */
    fun onFactAsserted(atom: Atom, tenantId: String?) {
        salienceTracker.recordCreation(atom)
        eventBus.publish(KnowledgeEvent.FactAsserted(atom, tenantId))
    }

    /** Record a fact retraction for salience and event tracking. */
    fun onFactRetracted(atom: Atom, tenantId: String?) {
        salienceTracker.remove(atom)
        eventBus.publish(KnowledgeEvent.FactRetracted(atom, tenantId))
    }

    /** Record a rule assertion for event tracking. */
    fun onRuleAsserted(rule: com.nocturnusai.core.Rule, tenantId: String?) {
        eventBus.publish(KnowledgeEvent.RuleAsserted(rule, tenantId))
    }

    fun shutdown() {
        scope.cancel()
    }

    fun clear() {
        salienceTracker.clear()
        eventBus.clear()
        predicateAccessCounts.clear()
    }
}

data class ScoredAtom(
    val atom: Atom,
    val salience: Double
)

data class ContextWindow(
    val facts: List<ScoredAtom>,
    val totalAvailable: Int,
    val windowSize: Int,
    val predicateDistribution: Map<String, Int>,
    val generatedAt: Long
)

data class ConsolidationResult(
    val factsConsolidated: Int,
    val newFacts: List<Atom>,
    val timestamp: Long
)

data class DecayResult(
    val expiredCount: Int,
    val evictedCount: Int,
    val removedAtoms: List<Atom>,
    val timestamp: Long
)
