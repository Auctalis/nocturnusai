package com.axiombase.core

import com.axiombase.inference.ReteEngine
import com.axiombase.inference.BackwardChainer
import com.axiombase.logic.ConsistencyGuard
import com.axiombase.logic.ProvenanceTracker
import com.axiombase.memory.MemoryManager
import com.axiombase.storage.Hexastore
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Encapsulates the logic and storage state for a single logical scope.
 * This can represent a standard single-database instance or a single tenant.
 */
class LogicContext {
    val store = Hexastore() // Positive Store
    val negativeStore = Hexastore() // Negative Store (Explicit NOT)

    val tracker = ProvenanceTracker()
    val rules = CopyOnWriteArrayList<Rule>()

    val rete = ReteEngine(store, tracker)
    val backwardChainer = BackwardChainer(store, rules)
    val consistencyGuard = ConsistencyGuard(store)

    /** Agent memory manager for temporal queries, salience, consolidation, decay. */
    val memoryManager = MemoryManager()
}
