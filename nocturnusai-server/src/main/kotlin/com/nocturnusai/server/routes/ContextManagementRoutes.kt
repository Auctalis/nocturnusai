package com.nocturnusai.server.routes

import com.nocturnusai.context.*
import com.nocturnusai.server.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// --- Request DTOs ---

@Serializable
data class OptimizeContextApiRequest(
    val tokenBudget: Int? = null,
    val scope: String? = null,
    val predicates: List<String>? = null,
    val categoryWeights: Map<String, Double>? = null,
    val sessionId: String? = null,
    val enableCompression: Boolean = false,
    val minSalience: Double = 0.0
)

@Serializable
data class ContextDiffApiRequest(
    val sessionId: String,
    val tokenBudget: Int? = null,
    val scope: String? = null,
    val predicates: List<String>? = null,
    val categoryWeights: Map<String, Double>? = null,
    val enableCompression: Boolean = false
)

@Serializable
data class ContextSummaryApiRequest(
    val scope: String? = null,
    val maxTokens: Int = 500
)

@Serializable
data class ClearSessionApiRequest(
    val sessionId: String
)

// --- Response DTOs ---

@Serializable
data class ContextEntryResponse(
    val predicate: String,
    val args: List<String>,
    val negated: Boolean = false,
    val scope: String? = null,
    val salience: Double,
    val category: String,
    val tokenEstimate: Int,
    val createdAt: Long? = null,
    val validFrom: Long? = null,
    val validUntil: Long? = null,
    val metadata: Map<String, JsonElement> = emptyMap()
)

@Serializable
data class CompressionResponse(
    val groupKey: String,
    val predicate: String,
    val subject: String,
    val compressedForm: String,
    val originalCount: Int,
    val originalTokens: Int,
    val compressedTokens: Int,
    val tokensSaved: Int
)

@Serializable
data class CategoryStatsResponse(
    val factsAvailable: Int,
    val factsIncluded: Int,
    val tokensAllocated: Int,
    val tokensUsed: Int,
    val minSalience: Double,
    val maxSalience: Double
)

@Serializable
data class OptimizedContextResponse(
    val windowId: String,
    val entries: List<ContextEntryResponse>,
    val compressions: List<CompressionResponse>,
    val totalTokenBudget: Int,
    val totalTokensUsed: Int,
    val totalFactsAvailable: Int,
    val totalFactsIncluded: Int,
    val deduplicationSavings: Int,
    val compressionSavings: Int,
    val categoryStats: Map<String, CategoryStatsResponse>,
    val generatedAt: Long
)

@Serializable
data class ContextDiffResponse(
    val previousWindowId: String?,
    val currentWindowId: String,
    val added: List<ContextEntryResponse>,
    val removed: List<String>,
    val updated: List<ContextEntryResponse>,
    val unchanged: Int,
    val tokensSaved: Int,
    val fullRefreshRecommended: Boolean
)

@Serializable
data class PredicateSummaryResponse(
    val predicate: String,
    val count: Int
)

@Serializable
data class ContextSummaryResponse(
    val totalFacts: Int,
    val predicateCount: Int,
    val topPredicates: List<PredicateSummaryResponse>,
    val categoryDistribution: Map<String, Int>,
    val factsWithTtl: Int,
    val factsExpiringWithin1h: Int,
    val topSalientFacts: List<ContextEntryResponse>,
    val estimatedTotalTokens: Int,
    val generatedAt: Long
)

// --- Mapping helpers ---

private fun SelectedContextEntry.toResponse() = ContextEntryResponse(
    predicate = atom.predicate,
    args = atom.args.map { it.toString() },
    negated = !atom.truthVal,
    scope = atom.scope,
    salience = salience,
    category = category,
    tokenEstimate = tokenEstimate,
    createdAt = atom.createdAt,
    validFrom = atom.validFrom,
    validUntil = atom.validUntil,
    metadata = atom.metadata
)

private fun Compression.toResponse() = CompressionResponse(
    groupKey = groupKey,
    predicate = predicate,
    subject = subject,
    compressedForm = compressedForm,
    originalCount = originalCount,
    originalTokens = originalTokens,
    compressedTokens = compressedTokens,
    tokensSaved = tokensSaved
)

private fun CategoryStats.toResponse() = CategoryStatsResponse(
    factsAvailable = factsAvailable,
    factsIncluded = factsIncluded,
    tokensAllocated = tokensAllocated,
    tokensUsed = tokensUsed,
    minSalience = minSalience,
    maxSalience = maxSalience
)

// --- Routes ---

fun Route.contextManagementRoutes(dbManager: DatabaseManager) {

    // Optimize context: build a token-budgeted, deduplicated, category-allocated context window
    post("/context/optimize") {
        try {
            val (db, tenantId) = call.getContext(dbManager)
            val req = call.receive<OptimizeContextApiRequest>()

            val result = db.optimizeContext(
                request = OptimizeContextRequest(
                    tokenBudget = req.tokenBudget,
                    scope = req.scope,
                    predicates = req.predicates,
                    categoryWeights = req.categoryWeights,
                    sessionId = req.sessionId,
                    enableCompression = req.enableCompression,
                    minSalience = req.minSalience
                ),
                tenantId = tenantId
            )

            call.respond(OptimizedContextResponse(
                windowId = result.windowId,
                entries = result.entries.map { it.toResponse() },
                compressions = result.compressions.map { it.toResponse() },
                totalTokenBudget = result.totalTokenBudget,
                totalTokensUsed = result.totalTokensUsed,
                totalFactsAvailable = result.totalFactsAvailable,
                totalFactsIncluded = result.totalFactsIncluded,
                deduplicationSavings = result.deduplicationSavings,
                compressionSavings = result.compressionSavings,
                categoryStats = result.categoryStats.mapValues { (_, v) -> v.toResponse() },
                generatedAt = result.generatedAt
            ))
        } catch (e: ValidationException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", e.message ?: "Validation error"))
        } catch (e: DatabaseNotFoundException) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", e.message ?: "Not found"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("BAD_REQUEST", e.message ?: "Error"))
        }
    }

    // Context diff: get only what changed since last window (incremental updates)
    post("/context/diff") {
        try {
            val (db, tenantId) = call.getContext(dbManager)
            val req = call.receive<ContextDiffApiRequest>()

            val result = db.diffContext(
                request = ContextDiffRequest(
                    sessionId = req.sessionId,
                    tokenBudget = req.tokenBudget,
                    scope = req.scope,
                    predicates = req.predicates,
                    categoryWeights = req.categoryWeights,
                    enableCompression = req.enableCompression
                ),
                tenantId = tenantId
            )

            call.respond(ContextDiffResponse(
                previousWindowId = result.previousWindowId,
                currentWindowId = result.currentWindowId,
                added = result.added.map { it.toResponse() },
                removed = result.removed,
                updated = result.updated.map { it.toResponse() },
                unchanged = result.unchanged,
                tokensSaved = result.tokensSaved,
                fullRefreshRecommended = result.fullRefreshRecommended
            ))
        } catch (e: ValidationException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", e.message ?: "Validation error"))
        } catch (e: DatabaseNotFoundException) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", e.message ?: "Not found"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("BAD_REQUEST", e.message ?: "Error"))
        }
    }

    // Context summary: compact overview of the knowledge base
    post("/context/summary") {
        try {
            val (db, tenantId) = call.getContext(dbManager)
            val req = try { call.receive<ContextSummaryApiRequest>() } catch (_: Exception) { ContextSummaryApiRequest() }

            val result = db.summarizeContext(tenantId, req.scope, req.maxTokens)

            call.respond(ContextSummaryResponse(
                totalFacts = result.totalFacts,
                predicateCount = result.predicateCount,
                topPredicates = result.topPredicates.map { PredicateSummaryResponse(it.predicate, it.count) },
                categoryDistribution = result.categoryDistribution,
                factsWithTtl = result.factsWithTtl,
                factsExpiringWithin1h = result.factsExpiringWithin1h,
                topSalientFacts = result.topSalientFacts.map { it.toResponse() },
                estimatedTotalTokens = result.estimatedTotalTokens,
                generatedAt = result.generatedAt
            ))
        } catch (e: ValidationException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", e.message ?: "Validation error"))
        } catch (e: DatabaseNotFoundException) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", e.message ?: "Not found"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("BAD_REQUEST", e.message ?: "Error"))
        }
    }

    // Clear session: clean up diffing state when agent session ends
    post("/context/session/clear") {
        try {
            val (db, tenantId) = call.getContext(dbManager)
            val req = call.receive<ClearSessionApiRequest>()

            db.clearContextSession(req.sessionId, tenantId)
            call.respondText("Session '${req.sessionId}' cleared")
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("BAD_REQUEST", e.message ?: "Error"))
        }
    }
}
