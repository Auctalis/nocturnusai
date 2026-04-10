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

package com.nocturnusai.server.conversation

import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * Bounded ring buffer of recent conversation turns, keyed by a conversation key
 * (typically `tenantId:scope` or `tenantId:sessionId`).
 *
 * Purpose: when an agent calls /context for turn N, the LLM extractor benefits from
 * seeing turns N-2 and N-1 as a `contextHint` so pronouns and references resolve
 * correctly. The server tracks this on behalf of the caller so they don't have to
 * keep re-sending the whole conversation.
 *
 * The buffer is intentionally small (default 3 turns) and bounded by total entries
 * to keep memory predictable. Eviction is LRU by last write.
 *
 * This buffer is server-side ephemeral state. It is NOT persisted across restarts.
 * That is intentional: the durable record is the extracted facts in the Hexastore.
 * The buffer only exists to improve extraction quality for the *next* turn.
 */
class ConversationTurnBuffer(
    /** Maximum turns kept per conversation. */
    private val turnsPerConversation: Int = 3,
    /** Maximum number of distinct conversations tracked. */
    private val maxConversations: Int = 5_000,
    /** TTL in milliseconds before a conversation entry is evicted. */
    private val ttlMs: Long = 60L * 60L * 1000L // 1 hour
) {

    private data class Entry(
        val turns: ArrayDeque<String>,
        @Volatile var lastWriteMs: Long
    )

    private val conversations = ConcurrentHashMap<String, Entry>()

    /**
     * Append a batch of new turns for the given conversation key.
     * The oldest turns are dropped once the per-conversation cap is exceeded.
     */
    fun append(key: String, newTurns: List<String>) {
        if (key.isBlank()) return
        val cleanTurns = newTurns.mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }
        if (cleanTurns.isEmpty()) return

        val now = System.currentTimeMillis()
        val entry = conversations.computeIfAbsent(key) {
            Entry(ArrayDeque(turnsPerConversation + 1), now)
        }
        synchronized(entry) {
            for (t in cleanTurns) {
                entry.turns.addLast(t)
                while (entry.turns.size > turnsPerConversation) {
                    entry.turns.removeFirst()
                }
            }
            entry.lastWriteMs = now
        }

        // Evict after insertion so we always honour maxConversations.
        evictIfNeeded(now)
    }

    /**
     * Get the recent turns for a conversation, oldest first.
     * Returns an empty list when no entry exists or it has expired.
     */
    fun recent(key: String): List<String> {
        if (key.isBlank()) return emptyList()
        val entry = conversations[key] ?: return emptyList()
        val now = System.currentTimeMillis()
        if (now - entry.lastWriteMs > ttlMs) {
            conversations.remove(key)
            return emptyList()
        }
        return synchronized(entry) { entry.turns.toList() }
    }

    /**
     * Build a contextHint string by joining the recent turns. Returns null when
     * the buffer is empty so callers can pass it through to the extractor without
     * adding noise to the prompt.
     */
    fun buildHint(key: String): String? {
        val turns = recent(key)
        if (turns.isEmpty()) return null
        return turns.joinToString("\n") { "[prior turn] $it" }
    }

    /** Drop a single conversation. */
    fun clear(key: String) {
        conversations.remove(key)
    }

    /** Drop everything. Useful in tests. */
    fun clearAll() {
        conversations.clear()
    }

    /** Current number of tracked conversations (for metrics/tests). */
    fun size(): Int = conversations.size

    private fun evictIfNeeded(now: Long) {
        // TTL pass — cheap O(n), only when over half-full to amortize cost.
        if (conversations.size > maxConversations / 2) {
            conversations.entries.removeIf { (_, e) -> now - e.lastWriteMs > ttlMs }
        }
        // Capacity pass — drop the oldest entries until under cap.
        while (conversations.size > maxConversations) {
            val oldest = conversations.entries.minByOrNull { it.value.lastWriteMs } ?: break
            conversations.remove(oldest.key)
        }
    }
}
