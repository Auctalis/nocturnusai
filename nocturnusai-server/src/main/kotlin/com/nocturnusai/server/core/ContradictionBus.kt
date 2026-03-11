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

package com.nocturnusai.server.core

import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * A contradiction event emitted when the engine detects a logical conflict
 * during fact assertion (e.g. asserting `A` when `NOT A` already exists).
 *
 * Part of Item 6: Semantic Provenance & Contradiction Resolution.
 * Contradictions are surfaced as first-class events rather than silent failures.
 */
@Serializable
data class ContradictionEvent(
    /** Unique sequential event ID. */
    val id: String,
    /** Unix epoch milliseconds when the contradiction was detected. */
    val timestamp: Long,
    /** String representation of the fact that was being asserted. */
    val assertedFact: String,
    /** String representation of the existing conflicting fact(s). */
    val conflictingFacts: List<String>,
    /** The database name where this happened. */
    val database: String,
    /** The tenant that triggered the assertion. */
    val tenant: String,
    /** The scope the contradiction occurred in, or "global" if unscoped. */
    val scope: String,
    /** Whether this contradiction was explicitly resolved by the caller. */
    val resolved: Boolean = false
)

/**
 * In-memory event bus for contradiction events.
 * LLMs can poll `GET /contradictions` to discover semantic conflicts
 * and decide how to resolve them (e.g. retract, update, reconcile).
 */
object ContradictionBus {
    private val idCounter = AtomicLong(0)
    private val events = ConcurrentLinkedQueue<ContradictionEvent>()

    fun emit(
        assertedFact: String,
        conflictingFacts: List<String>,
        database: String,
        tenant: String,
        scope: String
    ) {
        val event = ContradictionEvent(
            id = "C-${idCounter.incrementAndGet()}",
            timestamp = System.currentTimeMillis(),
            assertedFact = assertedFact,
            conflictingFacts = conflictingFacts,
            database = database,
            tenant = tenant,
            scope = scope
        )
        events.add(event)
    }

    fun getAll(): List<ContradictionEvent> = events.toList()

    fun getUnresolved(): List<ContradictionEvent> = events.filter { !it.resolved }

    fun resolve(id: String): Boolean {
        // Replace the event with resolved=true (atomic swap via removal + re-add)
        val event = events.find { it.id == id } ?: return false
        events.remove(event)
        events.add(event.copy(resolved = true))
        return true
    }

    fun clear() = events.clear()

    fun count(): Int = events.size
}
