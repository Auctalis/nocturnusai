package com.axiombase.storage

import com.axiombase.core.Atom
import com.axiombase.core.Term
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

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

    private val lock = ReentrantReadWriteLock()

    fun add(atom: Atom) {
        lock.write {
            val effectivePredicate = if (atom.truthVal) atom.predicate else "!${atom.predicate}"
            
            if (atom.args.size == 2) {
                val s = atom.args[0]
                val p = effectivePredicate
                val o = atom.args[1]
                java.io.File("/tmp/hexastore_debug.log").appendText("DEBUG: INDEXING $s $p $o SCOPE=${atom.scope}\n")
                indexTriple(s, p, o, atom)
            } else {
                otherAtoms.getOrPut(effectivePredicate) { newSet() }.add(atom)
            }
        }
    }

    fun delete(atom: Atom) {
        lock.write {
            val effectivePredicate = if (atom.truthVal) atom.predicate else "!${atom.predicate}"
            
            if (atom.args.size == 2) {
                 val s = atom.args[0]
                 val p = effectivePredicate
                 val o = atom.args[1]
                 deleteTriple(s, p, o, atom)
            } else {
                 otherAtoms[effectivePredicate]?.remove(atom)
            }
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
        return lock.read {
             if (pattern.args.size == 2) {
                 val s = if (pattern.args[0] is Term.Variable) null else pattern.args[0]
                 val o = if (pattern.args[1] is Term.Variable) null else pattern.args[1]
                 val p = if (pattern.truthVal) pattern.predicate else "!${pattern.predicate}"
                 
                 // If pattern has a scope defined, use it? Or use argument?
                 // The pattern object itself might have scope=null (wildcard in pattern?) or scope="Global".
                 // argument 'scope' overrides?
                 // Let's use argument 'scope' if provided, else if pattern.scope is set use that?
                 val effectiveScope = scope ?: pattern.scope
                 
                 query(s, p, o, effectiveScope).toList().asSequence() 
             } else {
                 val effectiveP = if (pattern.truthVal) pattern.predicate else "!${pattern.predicate}"
                 val candidates = otherAtoms[effectiveP] ?: return@read emptySequence()
                 
                 candidates.filter { candidate ->
                     // Check Scope
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
                 }.toList().asSequence()
             }
        }
    }
    
    /**
     * Retrieves all stored atoms.
     */
    fun getAllAtoms(): Sequence<Atom> {
        lock.read {
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
        }
    }
}
