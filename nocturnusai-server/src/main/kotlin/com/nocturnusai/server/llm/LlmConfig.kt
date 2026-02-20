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

package com.nocturnusai.server.llm

import org.slf4j.LoggerFactory

object LlmConfig {
    private val logger = LoggerFactory.getLogger(LlmConfig::class.java)

    // Explicit provider selection
    val provider: String? = System.getenv("LLM_PROVIDER")
    val model: String? = System.getenv("LLM_MODEL")
    val apiKey: String? = System.getenv("LLM_API_KEY")
    val baseUrl: String? = System.getenv("LLM_BASE_URL")

    // Provider-specific keys
    val openaiApiKey: String? = System.getenv("OPENAI_API_KEY")
    val anthropicApiKey: String? = System.getenv("ANTHROPIC_API_KEY")
    val googleApiKey: String? = System.getenv("GOOGLE_API_KEY")

    // Extraction config
    val extractionEnabled: Boolean = System.getenv("EXTRACTION_ENABLED")?.toBoolean() ?: false
    val extractionModel: String? = System.getenv("EXTRACTION_MODEL")
    val extractionMaxFacts: Int = System.getenv("EXTRACTION_MAX_FACTS")?.toIntOrNull() ?: 50

    fun createProvider(): LlmProvider? {
        val resolvedProvider = provider ?: detectProvider()
        if (resolvedProvider == null) {
            logger.info("No LLM provider configured. Set OPENAI_API_KEY, ANTHROPIC_API_KEY, GOOGLE_API_KEY, or LLM_BASE_URL.")
            return null
        }

        val llmProvider = when (resolvedProvider.lowercase()) {
            "anthropic" -> {
                val key = anthropicApiKey ?: apiKey
                    ?: throw IllegalStateException("ANTHROPIC_API_KEY or LLM_API_KEY required for Anthropic provider")
                val m = model ?: "claude-sonnet-4-20250514"
                logger.info("Using Anthropic provider with model: $m")
                AnthropicProvider(m, key)
            }
            "openai" -> {
                val key = openaiApiKey ?: apiKey
                    ?: throw IllegalStateException("OPENAI_API_KEY or LLM_API_KEY required for OpenAI provider")
                val m = model ?: "gpt-4o-mini"
                val url = baseUrl ?: "https://api.openai.com/v1"
                logger.info("Using OpenAI provider with model: $m, baseUrl: $url")
                OpenAiCompatibleProvider(m, key, url)
            }
            "google" -> {
                val key = googleApiKey ?: apiKey
                    ?: throw IllegalStateException("GOOGLE_API_KEY or LLM_API_KEY required for Google provider")
                val m = model ?: "gemini-2.0-flash"
                logger.info("Using Google provider with model: $m")
                GoogleProvider(m, key)
            }
            "ollama" -> {
                val m = model ?: "llama3.2"
                // Default varies: Docker uses service name "ollama", local uses localhost
                val url = baseUrl ?: detectOllamaUrl()
                logger.info("Using Ollama provider with model: $m, baseUrl: $url")
                OpenAiCompatibleProvider(m, null, url)
            }
            "custom" -> {
                val url = baseUrl
                    ?: throw IllegalStateException("LLM_BASE_URL required for custom provider")
                val m = model ?: throw IllegalStateException("LLM_MODEL required for custom provider")
                val key = apiKey
                logger.info("Using custom OpenAI-compatible provider with model: $m, baseUrl: $url")
                OpenAiCompatibleProvider(m, key, url)
            }
            else -> {
                logger.warn("Unknown LLM provider: $resolvedProvider")
                null
            }
        }

        return llmProvider
    }

    /**
     * Auto-detect provider from available API keys.
     * Priority: Anthropic > OpenAI > Google > Custom endpoint
     */
    private fun detectProvider(): String? {
        return when {
            !anthropicApiKey.isNullOrBlank() -> "anthropic"
            !openaiApiKey.isNullOrBlank() -> "openai"
            !googleApiKey.isNullOrBlank() -> "google"
            !baseUrl.isNullOrBlank() -> "custom"
            else -> null
        }
    }

    /**
     * Detect Ollama URL based on runtime environment.
     * In Docker (when /proc/1/cgroup contains "docker" or /.dockerenv exists),
     * use the compose service name "ollama". Otherwise use localhost.
     */
    private fun detectOllamaUrl(): String {
        val inDocker = try {
            java.io.File("/.dockerenv").exists() ||
                java.io.File("/proc/1/cgroup").readText().contains("docker")
        } catch (_: Exception) { false }

        return if (inDocker) {
            "http://ollama:11434/v1"
        } else {
            "http://localhost:11434/v1"
        }
    }
}
