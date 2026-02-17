package com.nocturnusai.memory

import com.nocturnusai.core.Atom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.exp
import kotlin.math.ln

/**
 * Tracks access patterns and computes salience scores for atoms.
 *
 * Salience is a composite score (0.0 to 1.0) based on:
 * - Recency: how recently the fact was accessed (exponential decay)
 * - Frequency: how often the fact has been accessed
 * - Derivation depth: inferred facts get lower base salience than user-asserted facts
 * - Explicit priority: optional user-specified priority boost
 *
 * This is the foundation for agent memory management — determining what context
 * is most relevant for the current reasoning step.
 */
class SalienceTracker(
    private val recencyHalfLifeMs: Long = 3_600_000L, // 1 hour
    private val frequencyWeight: Double = 0.3,
    private val recencyWeight: Double = 0.5,
    private val priorityWeight: Double = 0.2
) {
    data class AccessRecord(
        val lastAccessedAt: AtomicLong = AtomicLong(0),
        val accessCount: AtomicLong = AtomicLong(0),
        val createdAt: Long = System.currentTimeMillis(),
        @Volatile var explicitPriority: Double = 0.5 // 0.0 to 1.0
    )

    private val records = ConcurrentHashMap<AtomKey, AccessRecord>()

    /** Key that matches Atom identity (predicate + args + truthVal + source + scope). */
    data class AtomKey(
        val predicate: String,
        val args: List<String>,
        val truthVal: Boolean,
        val scope: String?
    ) {
        companion object {
            fun from(atom: Atom): AtomKey = AtomKey(
                predicate = atom.predicate,
                args = atom.args.map { it.toString() },
                truthVal = atom.truthVal,
                scope = atom.scope
            )
        }
    }

    /** Record that an atom was accessed (queried, used in inference, etc.). */
    fun recordAccess(atom: Atom) {
        val key = AtomKey.from(atom)
        val record = records.getOrPut(key) { AccessRecord(createdAt = atom.createdAt ?: System.currentTimeMillis()) }
        record.lastAccessedAt.set(System.currentTimeMillis())
        record.accessCount.incrementAndGet()
    }

    /** Record that a new atom was created. */
    fun recordCreation(atom: Atom) {
        val key = AtomKey.from(atom)
        records.getOrPut(key) {
            AccessRecord(
                lastAccessedAt = AtomicLong(System.currentTimeMillis()),
                accessCount = AtomicLong(1),
                createdAt = atom.createdAt ?: System.currentTimeMillis()
            )
        }
    }

    /** Set explicit priority for an atom (agent can boost/demote facts). */
    fun setPriority(atom: Atom, priority: Double) {
        val key = AtomKey.from(atom)
        val record = records.getOrPut(key) { AccessRecord() }
        record.explicitPriority = priority.coerceIn(0.0, 1.0)
    }

    /** Compute salience score for an atom (0.0 to 1.0). */
    fun computeSalience(atom: Atom, now: Long = System.currentTimeMillis()): Double {
        val key = AtomKey.from(atom)
        val record = records[key] ?: return 0.1 // Unknown atoms get low default salience

        // Recency: exponential decay based on time since last access
        val timeSinceAccess = (now - record.lastAccessedAt.get()).coerceAtLeast(0)
        val recencyScore = exp(-0.693 * timeSinceAccess / recencyHalfLifeMs) // 0.693 = ln(2)

        // Frequency: logarithmic scaling of access count
        val count = record.accessCount.get().toDouble()
        val frequencyScore = (ln(count + 1) / ln(100.0)).coerceAtMost(1.0)

        // Priority: explicit user/agent-set priority
        val priorityScore = record.explicitPriority

        return (recencyWeight * recencyScore +
                frequencyWeight * frequencyScore +
                priorityWeight * priorityScore).coerceIn(0.0, 1.0)
    }

    /** Get access statistics for an atom. */
    fun getStats(atom: Atom): AccessRecord? {
        return records[AtomKey.from(atom)]
    }

    /** Remove tracking for an atom (called on retraction). */
    fun remove(atom: Atom) {
        records.remove(AtomKey.from(atom))
    }

    /** Get all atoms sorted by salience (highest first). Returns atom keys with scores. */
    fun getTopByScore(limit: Int = 100, now: Long = System.currentTimeMillis()): List<Pair<AtomKey, Double>> {
        return records.entries
            .map { (key, record) ->
                val timeSinceAccess = (now - record.lastAccessedAt.get()).coerceAtLeast(0)
                val recencyScore = exp(-0.693 * timeSinceAccess / recencyHalfLifeMs)
                val count = record.accessCount.get().toDouble()
                val frequencyScore = (ln(count + 1) / ln(100.0)).coerceAtMost(1.0)
                val score = (recencyWeight * recencyScore +
                        frequencyWeight * frequencyScore +
                        priorityWeight * record.explicitPriority).coerceIn(0.0, 1.0)
                Pair(key, score)
            }
            .sortedByDescending { it.second }
            .take(limit)
    }

    /** Get atoms with salience below a threshold (candidates for eviction). */
    fun getBelowThreshold(threshold: Double, now: Long = System.currentTimeMillis()): List<AtomKey> {
        return records.entries
            .filter { (key, record) ->
                val timeSinceAccess = (now - record.lastAccessedAt.get()).coerceAtLeast(0)
                val recencyScore = exp(-0.693 * timeSinceAccess / recencyHalfLifeMs)
                val count = record.accessCount.get().toDouble()
                val frequencyScore = (ln(count + 1) / ln(100.0)).coerceAtMost(1.0)
                val score = (recencyWeight * recencyScore +
                        frequencyWeight * frequencyScore +
                        priorityWeight * record.explicitPriority).coerceIn(0.0, 1.0)
                score < threshold
            }
            .map { it.key }
    }

    fun clear() {
        records.clear()
    }

    fun size(): Int = records.size
}
