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

package com.nocturnusai.core

import com.nocturnusai.context.ContextManagementService
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
class LogicContext(private val semanticContext: SemanticContext = DummySemanticContext) {
    val store = Hexastore() // Positive Store
    val negativeStore = Hexastore() // Negative Store (Explicit NOT)

    val tracker = ProvenanceTracker()
    val rules = CopyOnWriteArrayList<Rule>()

    val rete = ReteEngine(store, tracker)
    val backwardChainer = BackwardChainer(store, rules, semanticContext = semanticContext)
    val consistencyGuard = ConsistencyGuard(store)

    /** Agent memory manager for temporal queries, salience, consolidation, decay. */
    val memoryManager = MemoryManager()

    /** Context management service — goal-driven, consistency-checked context optimization. */
    val contextManager = ContextManagementService(
        memoryManager = memoryManager,
        backwardChainer = backwardChainer,
        provenanceTracker = tracker,
        negativeStore = negativeStore,
        rules = rules
    )

    init {
        // Wire up EventBus → cache invalidation for context manager
        contextManager.subscribeToEvents(memoryManager.eventBus)
    }
}
