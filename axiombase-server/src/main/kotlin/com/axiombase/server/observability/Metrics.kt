package com.axiombase.server.observability

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Central metrics registry for AxiomBase.
 * All custom counters, timers, and gauges are registered here.
 *
 * Naming convention: axiombase_<subsystem>_<metric>_<unit>
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
        Gauge.builder("axiombase_databases_active", activeDatabases) { it.toDouble() }
            .description("Number of active databases")
            .register(registry)

        Gauge.builder("axiombase_transactions_active", activeTransactions) { it.toDouble() }
            .description("Number of active transactions")
            .register(registry)

        Gauge.builder("axiombase_facts_total", totalFacts) { it.toDouble() }
            .description("Total facts across all databases")
            .register(registry)

        Gauge.builder("axiombase_rules_total", totalRules) { it.toDouble() }
            .description("Total rules across all databases")
            .register(registry)
    }

    // ── Knowledge operations ────────────────────────────────────────────

    fun factAsserted(database: String, tenant: String) {
        Counter.builder("axiombase_facts_asserted_total")
            .description("Facts asserted")
            .tag("database", database)
            .tag("tenant", tenant)
            .register(registry).increment()
    }

    fun factRetracted(database: String, tenant: String) {
        Counter.builder("axiombase_facts_retracted_total")
            .description("Facts retracted")
            .tag("database", database)
            .tag("tenant", tenant)
            .register(registry).increment()
    }

    fun ruleAsserted(database: String, tenant: String) {
        Counter.builder("axiombase_rules_asserted_total")
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
        sample.stop(Timer.builder("axiombase_query_duration_seconds")
            .description("Query execution time")
            .tag("database", database)
            .tag("result_count_bucket", resultCountBucket(resultCount))
            .register(registry))
    }

    fun inferenceTimer(): Timer.Sample {
        return Timer.start(registry)
    }

    fun inferenceCompleted(sample: Timer.Sample, database: String, depth: Int) {
        sample.stop(Timer.builder("axiombase_inference_duration_seconds")
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
        sample.stop(Timer.builder("axiombase_llm_call_duration_seconds")
            .description("LLM API call duration")
            .tag("provider", provider)
            .tag("operation", operation)  // extract, synthesize, translate
            .tag("status", status)         // success, error
            .register(registry))
    }

    fun llmTokensUsed(provider: String, operation: String, tokenEstimate: Int) {
        Counter.builder("axiombase_llm_tokens_total")
            .description("Estimated LLM tokens used")
            .tag("provider", provider)
            .tag("operation", operation)
            .register(registry).increment(tokenEstimate.toDouble())
    }

    fun llmFactsExtracted(count: Int) {
        Counter.builder("axiombase_llm_facts_extracted_total")
            .description("Facts extracted via LLM")
            .register(registry).increment(count.toDouble())
    }

    // ── Transactions ────────────────────────────────────────────────────

    fun transactionStarted() {
        Counter.builder("axiombase_transactions_started_total")
            .description("Transactions started")
            .register(registry).increment()
        activeTransactions.incrementAndGet()
    }

    fun transactionCompleted(outcome: String) {
        Counter.builder("axiombase_transactions_completed_total")
            .description("Transactions completed")
            .tag("outcome", outcome) // commit, rollback, timeout
            .register(registry).increment()
        activeTransactions.decrementAndGet()
    }

    // ── Memory management ───────────────────────────────────────────────

    fun memoryConsolidation(factsConsolidated: Int, newFacts: Int) {
        Counter.builder("axiombase_memory_consolidated_total")
            .description("Facts consolidated during compression")
            .register(registry).increment(factsConsolidated.toDouble())
        Counter.builder("axiombase_memory_summary_facts_total")
            .description("Summary facts created during compression")
            .register(registry).increment(newFacts.toDouble())
    }

    fun memoryDecay(expired: Int, evicted: Int) {
        Counter.builder("axiombase_memory_expired_total")
            .description("Facts expired by TTL")
            .register(registry).increment(expired.toDouble())
        Counter.builder("axiombase_memory_evicted_total")
            .description("Facts evicted by low salience")
            .register(registry).increment(evicted.toDouble())
    }

    // ── Errors ──────────────────────────────────────────────────────────

    fun errorOccurred(endpoint: String, errorType: String) {
        Counter.builder("axiombase_errors_total")
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
