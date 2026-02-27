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

package com.nocturnusai.server.observability

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap

/**
 * Central metrics registry for NocturnusAI.
 * All custom counters, timers, and gauges are registered here.
 *
 * Naming convention: nocturnusai_<subsystem>_<metric>_<unit>
 * Tags: database, tenant, operation, provider, status
 */
object Metrics {

    private lateinit var registry: MeterRegistry

    // ── Live gauges (updated by operations) ─────────────────────────────
    val activeDatabases = AtomicInteger(0)
    val activeTransactions = AtomicInteger(0)
    val totalFacts = AtomicLong(0)
    val totalRules = AtomicLong(0)

    fun init(meterRegistry: MeterRegistry) {
        registry = meterRegistry

        // ── Gauges ──────────────────────────────────────────────────────
        Gauge.builder("nocturnusai_databases_active", activeDatabases) { it.toDouble() }
            .description("Number of active databases")
            .register(registry)

        Gauge.builder("nocturnusai_transactions_active", activeTransactions) { it.toDouble() }
            .description("Number of active transactions")
            .register(registry)

        Gauge.builder("nocturnusai_facts_total", totalFacts) { it.toDouble() }
            .description("Total facts across all databases")
            .register(registry)

        Gauge.builder("nocturnusai_rules_total", totalRules) { it.toDouble() }
            .description("Total rules across all databases")
            .register(registry)

        // ── MCP gauges ──────────────────────────────────────────────────
        Gauge.builder("nocturnusai_mcp_sse_subscribers", mcpSseSubscribers) { it.toDouble() }
            .description("Active MCP SSE subscribers")
            .register(registry)

        // ── Replication gauges ──────────────────────────────────────
        Gauge.builder("nocturnusai_replication_lag", replicationLag) { it.toDouble() }
            .description("Replication lag (leader WAL entries ahead of follower)")
            .register(registry)
    }

    // ── Knowledge operations ────────────────────────────────────────────

    fun factAsserted(database: String, tenant: String) {
        Counter.builder("nocturnusai_facts_asserted_total")
            .description("Facts asserted")
            .tag("database", database)
            .tag("tenant", tenant)
            .register(registry).increment()
    }

    fun factRetracted(database: String, tenant: String) {
        Counter.builder("nocturnusai_facts_retracted_total")
            .description("Facts retracted")
            .tag("database", database)
            .tag("tenant", tenant)
            .register(registry).increment()
    }

    fun ruleAsserted(database: String, tenant: String) {
        Counter.builder("nocturnusai_rules_asserted_total")
            .description("Rules asserted")
            .tag("database", database)
            .tag("tenant", tenant)
            .register(registry).increment()
    }

    // ── Query / Inference ───────────────────────────────────────────────

    fun queryTimer(): Timer.Sample {
        return Timer.start(registry)
    }

    fun queryCompleted(sample: Timer.Sample, database: String, resultCount: Int) {
        sample.stop(Timer.builder("nocturnusai_query_duration_seconds")
            .description("Query execution time")
            .tag("database", database)
            .tag("result_count_bucket", resultCountBucket(resultCount))
            .register(registry))
    }

    fun inferenceTimer(): Timer.Sample {
        return Timer.start(registry)
    }

    fun inferenceCompleted(sample: Timer.Sample, database: String, depth: Int) {
        sample.stop(Timer.builder("nocturnusai_inference_duration_seconds")
            .description("Backward chaining inference time")
            .tag("database", database)
            .tag("depth_bucket", depthBucket(depth))
            .register(registry))
    }

    // ── LLM operations ──────────────────────────────────────────────────

    fun llmCallTimer(): Timer.Sample {
        return Timer.start(registry)
    }

    fun llmCallCompleted(sample: Timer.Sample, provider: String, operation: String, status: String) {
        sample.stop(Timer.builder("nocturnusai_llm_call_duration_seconds")
            .description("LLM API call duration")
            .tag("provider", provider)
            .tag("operation", operation)  // extract, synthesize, translate
            .tag("status", status)         // success, error
            .register(registry))
    }

    fun llmTokensUsed(provider: String, operation: String, tokenEstimate: Int) {
        Counter.builder("nocturnusai_llm_tokens_total")
            .description("Estimated LLM tokens used")
            .tag("provider", provider)
            .tag("operation", operation)
            .register(registry).increment(tokenEstimate.toDouble())
    }

    fun llmFactsExtracted(count: Int) {
        Counter.builder("nocturnusai_llm_facts_extracted_total")
            .description("Facts extracted via LLM")
            .register(registry).increment(count.toDouble())
    }

    // ── Transactions ────────────────────────────────────────────────────

    fun transactionStarted() {
        Counter.builder("nocturnusai_transactions_started_total")
            .description("Transactions started")
            .register(registry).increment()
        activeTransactions.incrementAndGet()
    }

    fun transactionCompleted(outcome: String) {
        Counter.builder("nocturnusai_transactions_completed_total")
            .description("Transactions completed")
            .tag("outcome", outcome) // commit, rollback, timeout
            .register(registry).increment()
        activeTransactions.decrementAndGet()
    }

    // ── Memory management ───────────────────────────────────────────────

    fun memoryConsolidation(factsConsolidated: Int, newFacts: Int) {
        Counter.builder("nocturnusai_memory_consolidated_total")
            .description("Facts consolidated during compression")
            .register(registry).increment(factsConsolidated.toDouble())
        Counter.builder("nocturnusai_memory_summary_facts_total")
            .description("Summary facts created during compression")
            .register(registry).increment(newFacts.toDouble())
    }

    fun memoryDecay(expired: Int, evicted: Int) {
        Counter.builder("nocturnusai_memory_expired_total")
            .description("Facts expired by TTL")
            .register(registry).increment(expired.toDouble())
        Counter.builder("nocturnusai_memory_evicted_total")
            .description("Facts evicted by low salience")
            .register(registry).increment(evicted.toDouble())
    }

    // ── Replication ──────────────────────────────────────────────────────

    // Overall lag gauge (sum across all databases for simplicity)
    val replicationLag = AtomicLong(0)

    // Per-database last synced WAL ID (for health endpoint)
    private val lastSyncedWalIds = ConcurrentHashMap<String, AtomicLong>()
    private val consecutiveFailures = ConcurrentHashMap<String, AtomicInteger>()

    fun replicationLastSyncedWalId(dbName: String, walId: Long) {
        lastSyncedWalIds.getOrPut(dbName) { AtomicLong(0) }.set(walId)
        consecutiveFailures.getOrPut(dbName) { AtomicInteger(0) }.set(0)
    }

    fun replicationConsecutiveFailures(dbName: String) {
        consecutiveFailures.getOrPut(dbName) { AtomicInteger(0) }.incrementAndGet()
    }

    fun getLastSyncedWalId(dbName: String): Long =
        lastSyncedWalIds[dbName]?.get() ?: 0L

    fun getConsecutiveFailures(dbName: String): Int =
        consecutiveFailures[dbName]?.get() ?: 0

    // ── MCP ──────────────────────────────────────────────────────────────

    val mcpSseSubscribers = AtomicInteger(0)

    fun mcpToolCallTimer(): Timer.Sample {
        return Timer.start(registry)
    }

    fun mcpToolCallCompleted(sample: Timer.Sample, tool: String, status: String) {
        sample.stop(Timer.builder("nocturnusai_mcp_tool_call_duration_seconds")
            .description("MCP tool call duration")
            .tag("tool", tool)
            .tag("status", status) // success, error
            .register(registry))
    }

    fun mcpToolCallError(tool: String, errorType: String) {
        Counter.builder("nocturnusai_mcp_tool_errors_total")
            .description("MCP tool call errors")
            .tag("tool", tool)
            .tag("error_type", errorType) // validation, internal
            .register(registry).increment()
    }

    fun mcpSseConnected() {
        mcpSseSubscribers.incrementAndGet()
        Counter.builder("nocturnusai_mcp_sse_connections_total")
            .description("Total MCP SSE connections")
            .register(registry).increment()
    }

    fun mcpSseDisconnected() {
        mcpSseSubscribers.decrementAndGet()
    }

    // ── Errors ──────────────────────────────────────────────────────────

    fun errorOccurred(endpoint: String, errorType: String) {
        Counter.builder("nocturnusai_errors_total")
            .description("Errors by endpoint and type")
            .tag("endpoint", endpoint)
            .tag("error_type", errorType)
            .register(registry).increment()
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun resultCountBucket(count: Int): String = when {
        count == 0 -> "0"
        count <= 10 -> "1-10"
        count <= 100 -> "11-100"
        else -> "100+"
    }

    private fun depthBucket(depth: Int): String = when {
        depth <= 1 -> "shallow"
        depth <= 5 -> "medium"
        depth <= 20 -> "deep"
        else -> "very_deep"
    }
}
