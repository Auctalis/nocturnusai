package com.nocturnusai.core

import com.nocturnusai.inference.ReteEngine
import com.nocturnusai.inference.BackwardChainer
import com.nocturnusai.logic.ConsistencyGuard
import com.nocturnusai.logic.ProvenanceTracker
import com.nocturnusai.memory.MemoryManager
import com.nocturnusai.storage.Hexastore
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
