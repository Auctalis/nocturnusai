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

import java.net.HttpURLConnection
import java.net.URI
import org.slf4j.LoggerFactory

object LlmConfig {
    private val logger = LoggerFactory.getLogger(LlmConfig::class.java)
    const val MISSING_PROVIDER_MESSAGE =
        "No LLM provider configured. Set LLM_PROVIDER, OPENAI_API_KEY, ANTHROPIC_API_KEY, GOOGLE_API_KEY, or run Ollama on localhost:11434."

    // Explicit provider selection (ifBlank guards against Docker Compose empty-string defaults)
    val provider: String? = System.getenv("LLM_PROVIDER")?.ifBlank { null }
    val model: String? = System.getenv("LLM_MODEL")?.ifBlank { null }
    val apiKey: String? = System.getenv("LLM_API_KEY")?.ifBlank { null }
    val baseUrl: String? = System.getenv("LLM_BASE_URL")?.ifBlank { null }

    // Provider-specific keys
    val openaiApiKey: String? = System.getenv("OPENAI_API_KEY")?.ifBlank { null }
    val anthropicApiKey: String? = System.getenv("ANTHROPIC_API_KEY")?.ifBlank { null }
    val googleApiKey: String? = System.getenv("GOOGLE_API_KEY")?.ifBlank { null }

    // LLM behavior
    val temperature: Double = System.getenv("LLM_TEMPERATURE")?.toDoubleOrNull() ?: 0.1

    // Extraction config
    val extractionEnabled: Boolean = System.getenv("EXTRACTION_ENABLED")?.toBoolean() ?: false
    val extractionModel: String? = System.getenv("EXTRACTION_MODEL")
    val extractionMaxFacts: Int = System.getenv("EXTRACTION_MAX_FACTS")?.toIntOrNull() ?: 50

    // Embedding config — separate from completion provider
    // EMBED_PROVIDER: "ollama", "openai", "custom", or "none" to disable
    // EMBED_MODEL: the model name to use for embeddings (e.g. "nomic-embed-text")
    val embedProvider: String? = System.getenv("EMBED_PROVIDER")?.ifBlank { null }
    val embedModel: String? = System.getenv("EMBED_MODEL")?.ifBlank { null }

    internal data class ResolvedProviderConfig(
        val provider: String,
        val baseUrl: String? = null
    )

    fun createProvider(): LlmProvider? {
        val resolved = resolveProviderConfig()
        if (resolved == null) {
            logger.info(MISSING_PROVIDER_MESSAGE)
            return null
        }

        if (provider == null && resolved.provider == "ollama") {
            logger.info("No explicit LLM provider configured. Auto-detected Ollama at ${resolved.baseUrl}.")
        }

        val llmProvider = when (resolved.provider.lowercase()) {
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
                val m = model ?: "granite3.3:8b"
                val url = resolved.baseUrl ?: detectOllamaUrl()
                logger.info("Using Ollama provider with model: $m, baseUrl: $url")
                OpenAiCompatibleProvider(m, null, url)
            }
            "custom" -> {
                val url = resolved.baseUrl
                    ?: throw IllegalStateException("LLM_BASE_URL required for custom provider")
                val m = model ?: throw IllegalStateException("LLM_MODEL required for custom provider")
                val key = apiKey
                logger.info("Using custom OpenAI-compatible provider with model: $m, baseUrl: $url")
                OpenAiCompatibleProvider(m, key, url)
            }
            else -> {
                logger.warn("Unknown LLM provider: ${resolved.provider}")
                null
            }
        }

        return llmProvider
    }

    /**
     * Create a provider dedicated to embedding (vectorization).
     * Priority: EMBED_PROVIDER > detect from current provider (if it supports embed) > Ollama local fallback.
     * Use EMBED_PROVIDER=none to disable embeddings entirely.
     */
    fun createEmbedProvider(): LlmProvider? {
        if (embedProvider?.lowercase() == "none") return null

        val resolvedEmbedProvider = embedProvider ?: "ollama"
        val resolvedEmbedModel = embedModel ?: "nomic-embed-text"

        return when (resolvedEmbedProvider.lowercase()) {
            "ollama" -> {
                val url = baseUrl ?: detectOllamaUrl()
                logger.info("Embedding provider: Ollama model=$resolvedEmbedModel at $url")
                OpenAiCompatibleProvider(resolvedEmbedModel, null, url)
            }
            "openai" -> {
                val key = openaiApiKey ?: apiKey
                if (key.isNullOrBlank()) {
                    logger.warn("EMBED_PROVIDER=openai but no API key found, disabling embeddings")
                    return null
                }
                val url = baseUrl ?: "https://api.openai.com/v1"
                logger.info("Embedding provider: OpenAI model=$resolvedEmbedModel at $url")
                OpenAiCompatibleProvider(resolvedEmbedModel, key, url)
            }
            "custom" -> {
                val url = baseUrl ?: run {
                    logger.warn("EMBED_PROVIDER=custom but no LLM_BASE_URL found, disabling embeddings")
                    return null
                }
                logger.info("Embedding provider: custom model=$resolvedEmbedModel at $url")
                OpenAiCompatibleProvider(resolvedEmbedModel, apiKey, url)
            }
            else -> {
                logger.warn("Unknown EMBED_PROVIDER '$resolvedEmbedProvider', defaulting to Ollama")
                OpenAiCompatibleProvider(resolvedEmbedModel, null, detectOllamaUrl())
            }
        }
    }

    /**
     * Auto-detect provider from available API keys.
     * Priority: explicit provider > API keys > custom endpoint > reachable Ollama
     */
    internal fun resolveProviderConfig(
        explicitProvider: String? = provider,
        configuredBaseUrl: String? = baseUrl,
        anthropicKey: String? = anthropicApiKey,
        openaiKey: String? = openaiApiKey,
        googleKey: String? = googleApiKey,
        ollamaCandidates: List<String> = detectOllamaUrls(),
        ollamaReachable: (String) -> Boolean = ::isOllamaReachable
    ): ResolvedProviderConfig? {
        val normalizedProvider = explicitProvider?.lowercase()
        if (normalizedProvider == "none") return null
        if (!normalizedProvider.isNullOrBlank()) {
            return ResolvedProviderConfig(
                provider = normalizedProvider,
                baseUrl = when (normalizedProvider) {
                    "ollama" -> configuredBaseUrl ?: ollamaCandidates.firstOrNull()
                    "custom" -> configuredBaseUrl
                    else -> configuredBaseUrl
                }
            )
        }

        when {
            !anthropicKey.isNullOrBlank() -> return ResolvedProviderConfig("anthropic")
            !openaiKey.isNullOrBlank() -> return ResolvedProviderConfig("openai", configuredBaseUrl)
            !googleKey.isNullOrBlank() -> return ResolvedProviderConfig("google")
            !configuredBaseUrl.isNullOrBlank() -> return ResolvedProviderConfig("custom", configuredBaseUrl)
        }

        val reachableOllamaUrl = ollamaCandidates.firstOrNull(ollamaReachable)
        return if (reachableOllamaUrl != null) {
            ResolvedProviderConfig("ollama", reachableOllamaUrl)
        } else {
            null
        }
    }

    /**
     * Detect Ollama URL based on runtime environment.
     * In Docker (when /proc/1/cgroup contains "docker" or /.dockerenv exists),
     * use the compose service name "ollama". Otherwise use localhost.
     */
    internal fun detectOllamaUrls(): List<String> {
        val candidates = mutableListOf<String>()
        candidates += detectOllamaUrl()
        candidates += "http://localhost:11434/v1"
        candidates += "http://127.0.0.1:11434/v1"
        candidates += "http://host.docker.internal:11434/v1"
        return candidates.distinct()
    }

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

    internal fun isOllamaReachable(v1Url: String): Boolean {
        val base = v1Url.removeSuffix("/")
            .removeSuffix("/v1")
        return try {
            val conn = URI("$base/api/tags").toURL().openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 300
            conn.readTimeout = 300
            conn.instanceFollowRedirects = true
            conn.connect()
            conn.responseCode in 200..299
        } catch (_: Exception) {
            false
        }
    }
}
