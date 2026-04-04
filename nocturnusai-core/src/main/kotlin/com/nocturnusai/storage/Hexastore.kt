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

package com.nocturnusai.storage

import com.nocturnusai.core.Atom
import com.nocturnusai.core.MergeResult
import com.nocturnusai.core.MergeStrategy
import com.nocturnusai.core.ScopeConflict
import com.nocturnusai.core.ScopeDiff
import com.nocturnusai.core.Term
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.StampedLock

/**
 * Aggregation operations supported by [Hexastore.aggregate].
 */
enum class AggregateOp {
    COUNT, SUM, MIN, MAX, AVG
}

/**
 * A Reasoning Store implementation using Hexastore indexing strategy.
 * Optimized for quads: Subject (args[0]), Predicate (predicate), Object (args[1]), Scope.
 *
 * S = Subject (Term)
 * P = Predicate (String)
 * O = Object (Term)
 * C = Context/Scope (Atom containing scope property)
 *
 * Indices upgraded to Quad depth to support 'scope':
 * 1. SPO: S -> P -> O -> Set<Atom>
 * 2. SOP: S -> O -> P -> Set<Atom>
 * 3. PSO: P -> S -> O -> Set<Atom>
 * 4. POS: P -> O -> S -> Set<Atom>
 * 5. OSP: O -> S -> P -> Set<Atom>
 * 6. OPS: O -> P -> S -> Set<Atom>
 *
 * The leaf is a Set<Atom> because strictly speaking multiple identical atoms (same truth, same scope) 
 * shouldn't exist, but different scopes means different atoms.
 */
class Hexastore {

    // Helper alias for the inner structures. 
    private fun <K, V> newMap(): MutableMap<K, V> = ConcurrentHashMap()
    private fun <T> newSet(): MutableSet<T> = ConcurrentHashMap.newKeySet()

    // 1. SPO: S -> P -> O -> Set<Atom>
    private val spo = newMap<Term, MutableMap<String, MutableMap<Term, MutableSet<Atom>>>>()
    // 2. SOP: S -> O -> P
    private val sop = newMap<Term, MutableMap<Term, MutableMap<String, MutableSet<Atom>>>>()
    // 3. PSO: P -> S -> O
    private val pso = newMap<String, MutableMap<Term, MutableMap<Term, MutableSet<Atom>>>>()
    // 4. POS: P -> O -> S
    private val pos = newMap<String, MutableMap<Term, MutableMap<Term, MutableSet<Atom>>>>()
    // 5. OSP: O -> S -> P
    private val osp = newMap<Term, MutableMap<Term, MutableMap<String, MutableSet<Atom>>>>()
    // 6. OPS: O -> P -> S
    private val ops = newMap<Term, MutableMap<String, MutableMap<Term, MutableSet<Atom>>>>()

    // Fallback store for atoms that don't fit the triple model well (arity != 2)
    // Map Predicate -> Set of Atoms
    private val otherAtoms = newMap<String, MutableSet<Atom>>()

    private val lock = StampedLock()

    private val logger = org.slf4j.LoggerFactory.getLogger(Hexastore::class.java)

    fun add(atom: Atom) {
        val stamp = lock.writeLock()
        try {
            val effectivePredicate = if (atom.truthVal) atom.predicate else "!${atom.predicate}"

            if (atom.args.size == 2) {
                val s = atom.args[0]
                val p = effectivePredicate
                val o = atom.args[1]
                // Remove existing atom first to support metadata upsert
                // (equals/hashCode exclude metadata, so this finds the same logical atom)
                deleteTriple(s, p, o, atom)
                logger.debug("INDEXING {} {} {} SCOPE={}", s, p, o, atom.scope)
                indexTriple(s, p, o, atom)
            } else {
                val set = otherAtoms.getOrPut(effectivePredicate) { newSet() }
                // Remove existing atom first to support metadata upsert
                set.remove(atom)
                set.add(atom)
            }
        } finally {
            lock.unlockWrite(stamp)
        }
    }

    fun delete(atom: Atom) {
        val stamp = lock.writeLock()
        try {
            val effectivePredicate = if (atom.truthVal) atom.predicate else "!${atom.predicate}"

            if (atom.args.size == 2) {
                 val s = atom.args[0]
                 val p = effectivePredicate
                 val o = atom.args[1]
                 deleteTriple(s, p, o, atom)
            } else {
                 otherAtoms[effectivePredicate]?.remove(atom)
            }
        } finally {
            lock.unlockWrite(stamp)
        }
    }

    private fun deleteTriple(s: Term, p: String, o: Term, atom: Atom) {
        removeFromIndex(spo, s, p, o, atom)
        removeFromIndex(sop, s, o, p, atom)
        removeFromIndex(pso, p, s, o, atom)
        removeFromIndex(pos, p, o, s, atom)
        removeFromIndex(osp, o, s, p, atom)
        removeFromIndex(ops, o, p, s, atom)
    }

    private fun <K1, K2, K3, V> removeFromIndex(index: MutableMap<K1, MutableMap<K2, MutableMap<K3, MutableSet<V>>>>, k1: K1, k2: K2, k3: K3, v: V) {
        val l2 = index[k1] ?: return
        val l3 = l2[k2] ?: return
        val set = l3[k3] ?: return
        set.remove(v)
        
        // Cleanup empty containers to save memory
        if (set.isEmpty()) {
            l3.remove(k3)
            if (l3.isEmpty()) {
                l2.remove(k2)
                if (l2.isEmpty()) {
                    index.remove(k1)
                }
            }
        }
    }

    private fun indexTriple(s: Term, p: String, o: Term, atom: Atom) {
        addToIndex(spo, s, p, o, atom)
        addToIndex(sop, s, o, p, atom)
        addToIndex(pso, p, s, o, atom)
        addToIndex(pos, p, o, s, atom)
        addToIndex(osp, o, s, p, atom)
        addToIndex(ops, o, p, s, atom)
    }

    private fun <K1, K2, K3, V> addToIndex(index: MutableMap<K1, MutableMap<K2, MutableMap<K3, MutableSet<V>>>>, k1: K1, k2: K2, k3: K3, v: V) {
        index.getOrPut(k1) { newMap() }
             .getOrPut(k2) { newMap() }
             .getOrPut(k3) { newSet() }
             .add(v)
    }

    /**
     * Query matching. Returns a sequence of matching Atoms.
     * Arguments can be null (wildcard).
     * Optional 'scope' argument to filter results.
     */
    fun query(s: Term?, p: String?, o: Term?, scope: String? = null): Sequence<Atom> {
        val results = ArrayList<Atom>()

        // Helper to collect atoms from the leaf sets, optionally filtering by scope
        fun collect(leafSets: Sequence<Set<Atom>>) {
            leafSets.forEach { set ->
                if (scope == null) {
                    results.addAll(set)
                } else {
                    set.forEach { atom ->
                        if (atom.scope == scope) results.add(atom)
                    }
                }
            }
        }
        
        // Helper to walk the tree from a Set of (Key -> Set<Atom>)
        fun collectFromMap(map: Map<out Any, MutableSet<Atom>>?) {
             map?.values?.forEach { set ->
                 if (scope == null) {
                    results.addAll(set)
                } else {
                    set.forEach { atom ->
                        if (atom.scope == scope) results.add(atom)
                    }
                }
             }
        }

        // Generic collection:
        // Key1 known: index[k1] -> Map<K2, Map<K3, Set>>
        // Key1, Key2 known: index[k1][k2] -> Map<K3, Set>
        // Key1, Key2, Key3 known: index[k1][k2][k3] -> Set

        if (s != null && p != null && o != null) {
            // S P O (Scope?)
            // Direct lookup
            val set = spo[s]?.get(p)?.get(o)
            if (set != null) {
                 if (scope == null) results.addAll(set)
                 else set.forEach { if (it.scope == scope) results.add(it) }
            }
        } else if (s != null && p != null) {
            // S P ?
            val map = spo[s]?.get(p) // Map<O, Set>
            collectFromMap(map)
        } else if (s != null && o != null) {
            // S ? O
            val map = sop[s]?.get(o) // Map<P, Set>
            collectFromMap(map)
        } else if (p != null && o != null) {
            // ? P O
            val map = pos[p]?.get(o) // Map<S, Set>
            collectFromMap(map)
        } else if (s != null) {
            // S ? ?
            spo[s]?.values?.forEach { map -> collectFromMap(map) }
        } else if (p != null) {
             // ? P ?
             pso[p]?.values?.forEach { map -> collectFromMap(map) }
        } else if (o != null) {
             // ? ? O
             ops[o]?.values?.forEach { map -> collectFromMap(map) }
        } else {
            // ? ? ? -> ALL
             spo.values.forEach { map1 -> 
                 map1.values.forEach { map2 ->
                     collectFromMap(map2)
                 }
            }
        }
        
        return results.asSequence()
    }
    
    // Generic query for pattern matching (Unifier usage)
    // Now logic layer needs to decide if it wants to pass scope down here or filter later.
    // Ideally we pass it down.
    fun match(pattern: Atom, scope: String? = null): Sequence<Atom> {
        // Try optimistic read first (no lock acquisition when no writes in progress)
        val optimisticStamp = lock.tryOptimisticRead()
        if (optimisticStamp != 0L) {
            val result = matchInternal(pattern, scope)
            if (lock.validate(optimisticStamp)) {
                return result.asSequence()
            }
        }
        // Optimistic read failed (concurrent write); fall back to read lock
        val stamp = lock.readLock()
        try {
            return matchInternal(pattern, scope).asSequence()
        } finally {
            lock.unlockRead(stamp)
        }
    }

    private fun matchInternal(pattern: Atom, scope: String?): List<Atom> {
        return if (pattern.args.size == 2) {
            val s = if (pattern.args[0] is Term.Variable) null else pattern.args[0]
            val o = if (pattern.args[1] is Term.Variable) null else pattern.args[1]
            val p = if (pattern.truthVal) pattern.predicate else "!${pattern.predicate}"
            val effectiveScope = scope ?: pattern.scope
            query(s, p, o, effectiveScope).toList()
        } else {
            val effectiveP = if (pattern.truthVal) pattern.predicate else "!${pattern.predicate}"
            val candidates = otherAtoms[effectiveP] ?: return emptyList()
            candidates.filter { candidate ->
                if (scope != null && candidate.scope != scope) return@filter false
                if (pattern.scope != null && candidate.scope != pattern.scope) return@filter false
                if (candidate.args.size != pattern.args.size) return@filter false
                for (i in candidate.args.indices) {
                    val patTerm = pattern.args[i]
                    val candTerm = candidate.args[i]
                    if (patTerm !is Term.Variable && patTerm != candTerm) {
                        return@filter false
                    }
                }
                true
            }.toList()
        }
    }
    
    /**
     * Retrieves all stored atoms.
     */
    fun getAllAtoms(): Sequence<Atom> {
        val stamp = lock.readLock()
        try {
            val all = ArrayList<Atom>()
            spo.values.forEach { m1 ->
                m1.values.forEach { m2 ->
                    m2.values.forEach { set ->
                        all.addAll(set)
                    }
                }
            }
            all.addAll(otherAtoms.values.flatten())
            return all.asSequence()
        } finally {
            lock.unlockRead(stamp)
        }
    }

    /**
     * Counts atoms matching [pattern], optionally filtered by [scope].
     * Uses the existing [match] infrastructure — no full materialisation beyond
     * what match already does.
     */
    fun count(pattern: Atom, scope: String? = null): Int {
        return match(pattern, scope).count()
    }

    /**
     * Applies an aggregation [op] over the numeric value at [argIndex] for every
     * atom matching [pattern].
     *
     * - Non-[Term.NumberLit] values at [argIndex] are silently skipped.
     * - For [AggregateOp.COUNT] the [argIndex] is ignored and the total match
     *   count is returned as a [Double].
     * - Returns `null` when no matching atoms exist (or no numeric values for
     *   SUM/MIN/MAX/AVG after skipping non-numerics), except COUNT which returns 0.0.
     */
    fun aggregate(pattern: Atom, argIndex: Int, op: AggregateOp, scope: String? = null): Double? {
        val matches = match(pattern, scope).toList()

        if (op == AggregateOp.COUNT) {
            return matches.size.toDouble()
        }

        if (matches.isEmpty()) return null

        val numbers = matches.mapNotNull { atom ->
            val term = atom.args.getOrNull(argIndex)
            (term as? Term.NumberLit)?.value
        }

        if (numbers.isEmpty()) return null

        return when (op) {
            AggregateOp.SUM -> numbers.sum()
            AggregateOp.MIN -> numbers.min()
            AggregateOp.MAX -> numbers.max()
            AggregateOp.AVG -> numbers.average()
            AggregateOp.COUNT -> numbers.size.toDouble() // unreachable — handled above
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scope management
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Return all distinct scope values currently present in the store.
     * Null (global) scope is never included in this set.
     */
    fun listScopes(): Set<String> {
        val stamp = lock.readLock()
        try {
            val scopes = HashSet<String>()
            spo.values.forEach { m1 ->
                m1.values.forEach { m2 ->
                    m2.values.forEach { set ->
                        set.forEach { atom -> atom.scope?.let { scopes.add(it) } }
                    }
                }
            }
            otherAtoms.values.forEach { set ->
                set.forEach { atom -> atom.scope?.let { scopes.add(it) } }
            }
            return scopes
        } finally {
            lock.unlockRead(stamp)
        }
    }

    /**
     * Copy every atom whose scope matches [sourceScope] into [targetScope].
     *
     * [sourceScope] == null means the global (unscoped) partition.
     * Returns the number of atoms copied.
     *
     * If [targetScope] already contains atoms for the same logical positions those
     * existing atoms are overwritten (upsert semantics — identical to [add]).
     */
    fun forkScope(sourceScope: String?, targetScope: String): Int {
        // Collect source atoms under a read lock, then write each copy.
        val sourceAtoms: List<Atom>
        val stamp = lock.readLock()
        try {
            sourceAtoms = collectByScope(sourceScope)
        } finally {
            lock.unlockRead(stamp)
        }

        var count = 0
        for (atom in sourceAtoms) {
            val copy = atom.copy(scope = targetScope)
            add(copy)
            count++
        }
        return count
    }

    /**
     * Compare [scopeA] and [scopeB] and return a [ScopeDiff] describing what
     * is different between the two.  Null scope means the global partition.
     */
    fun diffScopes(scopeA: String?, scopeB: String?): ScopeDiff {
        val atomsA: List<Atom>
        val atomsB: List<Atom>
        val stamp = lock.readLock()
        try {
            atomsA = collectByScope(scopeA)
            atomsB = collectByScope(scopeB)
        } finally {
            lock.unlockRead(stamp)
        }

        // Key = (predicate, args) for position-based comparison
        data class Key(val predicate: String, val args: List<Term>)

        val mapA = atomsA.associateBy { Key(it.predicate, it.args) }
        val mapB = atomsB.associateBy { Key(it.predicate, it.args) }

        val onlyInA = mutableListOf<Atom>()
        val onlyInB = mutableListOf<Atom>()
        val inBoth  = mutableListOf<Atom>()
        val conflicts = mutableListOf<ScopeConflict>()

        for ((key, atomA) in mapA) {
            val atomB = mapB[key]
            when {
                atomB == null -> onlyInA.add(atomA)
                atomA.truthVal == atomB.truthVal -> inBoth.add(atomA)
                else -> conflicts.add(ScopeConflict(key.predicate, key.args, atomA, atomB))
            }
        }
        for ((key, atomB) in mapB) {
            if (key !in mapA) onlyInB.add(atomB)
        }

        return ScopeDiff(onlyInA, onlyInB, inBoth, conflicts)
    }

    /**
     * Merge atoms from [sourceScope] into [targetScope] using the given [strategy].
     *
     * Returns a [MergeResult] describing the outcome.
     * Throws [IllegalStateException] when strategy is [MergeStrategy.REJECT] and
     * any conflicts are detected.
     */
    fun mergeScope(
        sourceScope: String,
        targetScope: String?,
        strategy: MergeStrategy = MergeStrategy.SOURCE_WINS
    ): MergeResult {
        val diff = diffScopes(sourceScope, targetScope)

        if (strategy == MergeStrategy.REJECT && diff.conflicts.isNotEmpty()) {
            throw IllegalStateException(
                "Merge rejected: ${diff.conflicts.size} conflict(s) detected between " +
                "scope '$sourceScope' and scope '${targetScope ?: "<global>"}'"
            )
        }

        var merged = 0
        var conflictsResolved = 0

        // Copy atoms that only exist in source → they can always be added
        for (atom in diff.onlyInA) {
            val copy = atom.copy(scope = targetScope)
            add(copy)
            merged++
        }

        // Handle conflicts
        for (conflict in diff.conflicts) {
            when (strategy) {
                MergeStrategy.SOURCE_WINS -> {
                    // Remove the target version, then add the source version
                    val targetAtom = conflict.inB.copy(scope = targetScope)
                    delete(targetAtom)
                    val sourceAtom = conflict.inA.copy(scope = targetScope)
                    add(sourceAtom)
                    merged++
                    conflictsResolved++
                }
                MergeStrategy.TARGET_WINS -> {
                    // Keep the target as-is — nothing to do
                    conflictsResolved++
                }
                MergeStrategy.KEEP_BOTH -> {
                    // Add the source version on top of the existing target version
                    val sourceAtom = conflict.inA.copy(scope = targetScope)
                    add(sourceAtom)
                    merged++
                    conflictsResolved++
                }
                MergeStrategy.REJECT -> {
                    // Already handled above — should never reach here
                }
            }
        }

        return MergeResult(
            merged = merged,
            conflictsResolved = conflictsResolved,
            strategy = strategy,
            timestamp = Instant.now().toString()
        )
    }

    /**
     * Delete ALL atoms belonging to [scope].
     * Returns the number of atoms deleted.
     */
    fun deleteScope(scope: String): Int {
        val toDelete: List<Atom>
        val stamp = lock.readLock()
        try {
            toDelete = collectByScope(scope)
        } finally {
            lock.unlockRead(stamp)
        }

        for (atom in toDelete) {
            delete(atom)
        }
        return toDelete.size
    }

    // Internal helper: collect all atoms for a given scope (called under read lock).
    private fun collectByScope(scope: String?): List<Atom> {
        val result = ArrayList<Atom>()
        spo.values.forEach { m1 ->
            m1.values.forEach { m2 ->
                m2.values.forEach { set ->
                    set.forEach { atom ->
                        if (atom.scope == scope) result.add(atom)
                    }
                }
            }
        }
        otherAtoms.values.forEach { set ->
            set.forEach { atom ->
                if (atom.scope == scope) result.add(atom)
            }
        }
        return result
    }
}
