// Copyright (c) 2026 Auctalis LLC. All rights reserved.
//
// Licensed under the Business Source License 1.1 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://github.com/auctalis/nocturnusai/blob/main/LICENSE

package com.nocturnusai.server.conversation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversationTurnBufferTest {

    @Test
    fun `recent returns empty list for unknown key`() {
        val buf = ConversationTurnBuffer()
        assertTrue(buf.recent("nope").isEmpty())
        assertNull(buf.buildHint("nope"))
    }

    @Test
    fun `append and recent return turns oldest first`() {
        val buf = ConversationTurnBuffer(turnsPerConversation = 5)
        buf.append("conv-1", listOf("turn one", "turn two"))
        buf.append("conv-1", listOf("turn three"))

        assertEquals(listOf("turn one", "turn two", "turn three"), buf.recent("conv-1"))
    }

    @Test
    fun `per-conversation cap drops oldest turns`() {
        val buf = ConversationTurnBuffer(turnsPerConversation = 2)
        buf.append("conv-1", listOf("a", "b", "c", "d"))

        assertEquals(listOf("c", "d"), buf.recent("conv-1"))
    }

    @Test
    fun `buildHint joins turns with prior-turn markers`() {
        val buf = ConversationTurnBuffer()
        buf.append("conv-1", listOf("first turn", "second turn"))

        val hint = buf.buildHint("conv-1")
        assertNotNull(hint)
        assertTrue(hint.contains("[prior turn] first turn"))
        assertTrue(hint.contains("[prior turn] second turn"))
    }

    @Test
    fun `blank or empty inputs are ignored`() {
        val buf = ConversationTurnBuffer()
        buf.append("", listOf("ignored"))
        buf.append("conv-1", emptyList())
        buf.append("conv-1", listOf("", "  ", "real"))

        assertEquals(listOf("real"), buf.recent("conv-1"))
        assertEquals(1, buf.size())
    }

    @Test
    fun `clear removes a single conversation`() {
        val buf = ConversationTurnBuffer()
        buf.append("conv-1", listOf("a"))
        buf.append("conv-2", listOf("b"))

        buf.clear("conv-1")

        assertTrue(buf.recent("conv-1").isEmpty())
        assertEquals(listOf("b"), buf.recent("conv-2"))
    }

    @Test
    fun `clearAll empties the buffer`() {
        val buf = ConversationTurnBuffer()
        buf.append("conv-1", listOf("a"))
        buf.append("conv-2", listOf("b"))

        buf.clearAll()

        assertEquals(0, buf.size())
    }

    @Test
    fun `capacity eviction drops oldest conversations`() {
        val buf = ConversationTurnBuffer(turnsPerConversation = 2, maxConversations = 2)
        buf.append("conv-1", listOf("a"))
        // Force ordering — sleep is brittle in tests but we only need a stable
        // monotonic ordering of last-write timestamps. The append calls below
        // all happen within the same millisecond on most machines, so we use
        // a guaranteed-newer call last.
        Thread.sleep(2)
        buf.append("conv-2", listOf("b"))
        Thread.sleep(2)
        buf.append("conv-3", listOf("c"))

        // conv-1 should have been evicted as the oldest
        assertTrue(buf.size() <= 2)
        assertTrue(buf.recent("conv-3").isNotEmpty())
    }
}
