// Copyright (c) 2026 Auctalis LLC. All rights reserved.
//
// Licensed under the Business Source License 1.1 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://github.com/auctalis/nocturnusai/blob/main/LICENSE

package com.nocturnusai.server.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LlmConfigTest {

    @Test
    fun `resolveProviderConfig prefers explicit provider`() {
        val resolved = LlmConfig.resolveProviderConfig(
            explicitProvider = "ollama",
            configuredBaseUrl = "http://localhost:11434/v1",
            anthropicKey = "sk-ant-test",
            openaiKey = "sk-test",
            googleKey = "AIza-test",
            ollamaCandidates = listOf("http://localhost:11434/v1"),
            ollamaReachable = { false }
        )

        assertNotNull(resolved)
        assertEquals("ollama", resolved.provider)
        assertEquals("http://localhost:11434/v1", resolved.baseUrl)
    }

    @Test
    fun `resolveProviderConfig falls back to ollama when reachable`() {
        val resolved = LlmConfig.resolveProviderConfig(
            explicitProvider = null,
            configuredBaseUrl = null,
            anthropicKey = null,
            openaiKey = null,
            googleKey = null,
            ollamaCandidates = listOf(
                "http://ollama:11434/v1",
                "http://localhost:11434/v1"
            ),
            ollamaReachable = { url -> url == "http://localhost:11434/v1" }
        )

        assertNotNull(resolved)
        assertEquals("ollama", resolved.provider)
        assertEquals("http://localhost:11434/v1", resolved.baseUrl)
    }

    @Test
    fun `resolveProviderConfig returns null when no provider and ollama unavailable`() {
        val resolved = LlmConfig.resolveProviderConfig(
            explicitProvider = null,
            configuredBaseUrl = null,
            anthropicKey = null,
            openaiKey = null,
            googleKey = null,
            ollamaCandidates = listOf("http://localhost:11434/v1"),
            ollamaReachable = { false }
        )

        assertNull(resolved)
    }

    @Test
    fun `resolveProviderConfig honors explicit none`() {
        val resolved = LlmConfig.resolveProviderConfig(
            explicitProvider = "none",
            configuredBaseUrl = null,
            anthropicKey = null,
            openaiKey = null,
            googleKey = null,
            ollamaCandidates = listOf("http://localhost:11434/v1"),
            ollamaReachable = { true }
        )

        assertNull(resolved)
    }
}
