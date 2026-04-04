package com.nocturnusai.server.routes

import com.nocturnusai.context.*
import com.nocturnusai.extraction.FactExtractor
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
data class GoalSpecDto(
    val predicate: String,
    val args: List<String>,
    val negated: Boolean = false
)

@Serializable
data class RelevanceBucketDto(
    val name: String,
    val predicates: List<String>? = null,
    val weight: Double = 1.0
)

@Serializable
data class OptimizeContextApiRequest(
    val maxFacts: Int? = null,
    val scope: String? = null,
    val predicates: List<String>? = null,
    val goals: List<GoalSpecDto>? = null,
    val relevanceBuckets: List<RelevanceBucketDto>? = null,
    val sessionId: String? = null,
    val autoResolveContradictions: Boolean = true,
    val maxFactsPerPredicate: Int? = null
)

@Serializable
data class ContextDiffApiRequest(
    val sessionId: String,
    val maxFacts: Int? = null,
    val scope: String? = null,
    val predicates: List<String>? = null,
    val goals: List<GoalSpecDto>? = null,
    val relevanceBuckets: List<RelevanceBucketDto>? = null,
    val autoResolveContradictions: Boolean = true,
    val maxFactsPerPredicate: Int? = null
)

@Serializable
data class ContextSummaryApiRequest(
    val scope: String? = null
)

@Serializable
data class ClearSessionApiRequest(
    val sessionId: String
)

@Serializable
data class IngestAndOptimizeApiRequest(
    val text: String,
    val goals: List<GoalSpecDto>? = null,
    val maxFacts: Int? = null,
    val maxFactsPerPredicate: Int? = null,
    val autoResolveContradictions: Boolean = true,
    val sessionId: String? = null,
    val relevanceBuckets: List<RelevanceBucketDto>? = null,
    val scope: String? = null,
    val contextHint: String? = null
)

@Serializable
data class IngestAndOptimizeResponse(
    val extracted: List<ExtractedFactDto>,
    val extractionProvider: String? = null,
    val context: OptimizedContextResponse
)

// --- Response DTOs ---

@Serializable
data class DerivationInfoResponse(
    val rule: String,
    val premises: List<String>
)

@Serializable
data class ContextEntryResponse(
    val predicate: String,
    val args: List<String>,
    val negated: Boolean = false,
    val scope: String? = null,
    val salience: Double,
    val category: String,
    val charCount: Int,
    val provenance: DerivationInfoResponse? = null,
    val createdAt: Long? = null,
    val validFrom: Long? = null,
    val validUntil: Long? = null,
    val metadata: Map<String, JsonElement> = emptyMap()
)

@Serializable
data class BucketStatsResponse(
    val factsIncluded: Int,
    val maxAllocation: Int,
    val minSalience: Double,
    val maxSalience: Double
)

@Serializable
data class ContradictionResponse(
    val predicate: String,
    val args: List<String>,
    val positiveSalience: Double,
    val negativeSalience: Double
)

@Serializable
data class OptimizedContextResponse(
    val windowId: String,
    val entries: List<ContextEntryResponse>,
    val relevantRules: List<String>,
    val totalFactsAvailable: Int,
    val totalFactsIncluded: Int,
    val deduplicationSavings: Int,
    val contradictionsFound: Int,
    val contradictionsResolved: Int,
    val contradictions: List<ContradictionResponse>,
    val bucketStats: Map<String, BucketStatsResponse>,
    val totalCharCount: Int,
    val goalDriven: Boolean,
    val knowledgeGeneration: Long,
    val generatedAt: Long
)

@Serializable
data class RemovedEntryResponse(
    val key: String,
    val predicate: String,
    val args: List<String>,
    val negated: Boolean = false,
    val scope: String? = null
)

@Serializable
data class ContextDiffResponse(
    val previousWindowId: String?,
    val currentWindowId: String,
    val added: List<ContextEntryResponse>,
    val removed: List<RemovedEntryResponse>,
    val unchanged: Int,
    val fullRefreshRecommended: Boolean,
    val reason: String? = null
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
    val factsWithTtl: Int,
    val factsExpiringWithin1h: Int,
    val contradictions: Int,
    val topSalientFacts: List<ContextEntryResponse>,
    val totalCharCount: Int,
    val knowledgeGeneration: Long,
    val generatedAt: Long
)

// --- Mapping helpers ---

private fun DerivationInfo.toResponse() = DerivationInfoResponse(
    rule = rule,
    premises = premises
)

private fun SelectedContextEntry.toResponse() = ContextEntryResponse(
    predicate = atom.predicate,
    args = atom.args.map { it.toString() },
    negated = !atom.truthVal,
    scope = atom.scope,
    salience = salience,
    category = category,
    charCount = charCount,
    provenance = provenance?.toResponse(),
    createdAt = atom.createdAt,
    validFrom = atom.validFrom,
    validUntil = atom.validUntil,
    metadata = atom.metadata
)

private fun GoalSpecDto.toDomain() = GoalSpec(predicate, args, negated)

private fun RelevanceBucketDto.toDomain() = RelevanceBucket(name, predicates, weight)

private fun BucketStats.toResponse() = BucketStatsResponse(
    factsIncluded = factsIncluded,
    maxAllocation = maxAllocation,
    minSalience = minSalience,
    maxSalience = maxSalience
)

private fun Contradiction.toResponse() = ContradictionResponse(
    predicate = predicate,
    args = args,
    positiveSalience = positiveSalience,
    negativeSalience = negativeSalience
)

private fun RemovedEntry.toResponse() = RemovedEntryResponse(
    key = key,
    predicate = predicate,
    args = args,
    negated = negated,
    scope = scope
)

// --- Routes ---

fun Route.contextManagementRoutes(dbManager: DatabaseManager, extractor: FactExtractor? = null, extractionProvider: String? = null) {

    // Goal-driven context optimization
    post("/context/optimize") {
        try {
            val (db, tenantId) = call.getContext(dbManager)
            val req = call.receive<OptimizeContextApiRequest>()

            val result = db.optimizeContext(
                request = OptimizeContextRequest(
                    maxFacts = req.maxFacts,
                    scope = req.scope,
                    predicates = req.predicates,
                    goals = req.goals?.map { it.toDomain() },
                    relevanceBuckets = req.relevanceBuckets?.map { it.toDomain() },
                    sessionId = req.sessionId,
                    autoResolveContradictions = req.autoResolveContradictions,
                    maxFactsPerPredicate = req.maxFactsPerPredicate
                ),
                tenantId = tenantId
            )

            call.respond(OptimizedContextResponse(
                windowId = result.windowId,
                entries = result.entries.map { it.toResponse() },
                relevantRules = result.relevantRules.map { it.toString() },
                totalFactsAvailable = result.totalFactsAvailable,
                totalFactsIncluded = result.totalFactsIncluded,
                deduplicationSavings = result.deduplicationSavings,
                contradictionsFound = result.contradictionsFound,
                contradictionsResolved = result.contradictionsResolved,
                contradictions = result.contradictions.map { it.toResponse() },
                bucketStats = result.bucketStats.mapValues { (_, v) -> v.toResponse() },
                totalCharCount = result.totalCharCount,
                goalDriven = result.goalDriven,
                knowledgeGeneration = result.knowledgeGeneration,
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

    // Incremental diff
    post("/context/diff") {
        try {
            val (db, tenantId) = call.getContext(dbManager)
            val req = call.receive<ContextDiffApiRequest>()

            val result = db.diffContext(
                request = ContextDiffRequest(
                    sessionId = req.sessionId,
                    maxFacts = req.maxFacts,
                    scope = req.scope,
                    predicates = req.predicates,
                    goals = req.goals?.map { it.toDomain() },
                    relevanceBuckets = req.relevanceBuckets?.map { it.toDomain() }
                ),
                tenantId = tenantId
            )

            call.respond(ContextDiffResponse(
                previousWindowId = result.previousWindowId,
                currentWindowId = result.currentWindowId,
                added = result.added.map { it.toResponse() },
                removed = result.removed.map { it.toResponse() },
                unchanged = result.unchanged,
                fullRefreshRecommended = result.fullRefreshRecommended,
                reason = result.reason
            ))
        } catch (e: ValidationException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", e.message ?: "Validation error"))
        } catch (e: DatabaseNotFoundException) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", e.message ?: "Not found"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("BAD_REQUEST", e.message ?: "Error"))
        }
    }

    // Knowledge base summary
    post("/context/summary") {
        try {
            val (db, tenantId) = call.getContext(dbManager)
            val req = try { call.receive<ContextSummaryApiRequest>() } catch (_: Exception) { ContextSummaryApiRequest() }

            val result = db.summarizeContext(tenantId, req.scope)

            call.respond(ContextSummaryResponse(
                totalFacts = result.totalFacts,
                predicateCount = result.predicateCount,
                topPredicates = result.topPredicates.map { PredicateSummaryResponse(it.predicate, it.count) },
                factsWithTtl = result.factsWithTtl,
                factsExpiringWithin1h = result.factsExpiringWithin1h,
                contradictions = result.contradictions,
                topSalientFacts = result.topSalientFacts.map { it.toResponse() },
                totalCharCount = result.totalCharCount,
                knowledgeGeneration = result.knowledgeGeneration,
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

    // Clear session
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

    // Ingest: extract facts from text, assert them, then return optimized context
    post("/context/ingest") {
        try {
            val (db, tenantId) = call.getContext(dbManager)
            val req = call.receive<IngestAndOptimizeApiRequest>()

            if (req.text.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", "Text must not be blank"))
                return@post
            }

            // Step 1: Extract facts from text
            val extractedFacts = if (extractor != null) {
                try {
                    val facts = extractor.extract(req.text, req.contextHint)
                    // Assert extracted facts
                    for (fact in facts) {
                        val terms = fact.args.map { com.nocturnusai.core.Term.Identifier(it) }
                        val atom = com.nocturnusai.core.Atom(fact.predicate, terms, scope = req.scope)
                        try { db.assertFact(atom, tenantId) } catch (_: Exception) { }
                    }
                    facts
                } catch (e: Exception) {
                    call.application.environment.log.warn("Extraction failed in ingest: ${e.message}")
                    emptyList()
                }
            } else {
                // No extractor — try parsing as predicate syntax lines
                val facts = mutableListOf<com.nocturnusai.extraction.ExtractedFact>()
                for (line in req.text.lines()) {
                    val trimmed = line.trim()
                    if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("//")) continue
                    val match = Regex("""^(\w+)\((.+)\)$""").find(trimmed) ?: continue
                    val predicate = match.groupValues[1]
                    val args = match.groupValues[2].split(",").map { it.trim().trim('"', '\'') }
                    val terms = args.map { com.nocturnusai.core.Term.Identifier(it) }
                    val atom = com.nocturnusai.core.Atom(predicate, terms, scope = req.scope)
                    try {
                        db.assertFact(atom, tenantId)
                        facts.add(com.nocturnusai.extraction.ExtractedFact(predicate, args, 1.0f))
                    } catch (_: Exception) { }
                }
                facts
            }

            // Step 2: Optimize context
            val result = db.optimizeContext(
                request = OptimizeContextRequest(
                    maxFacts = req.maxFacts,
                    scope = req.scope,
                    goals = req.goals?.map { it.toDomain() },
                    relevanceBuckets = req.relevanceBuckets?.map { it.toDomain() },
                    sessionId = req.sessionId,
                    autoResolveContradictions = req.autoResolveContradictions,
                    maxFactsPerPredicate = req.maxFactsPerPredicate
                ),
                tenantId = tenantId
            )

            call.respond(IngestAndOptimizeResponse(
                extracted = extractedFacts.map { ExtractedFactDto(it.predicate, it.args, it.confidence) },
                extractionProvider = extractionProvider,
                context = OptimizedContextResponse(
                    windowId = result.windowId,
                    entries = result.entries.map { it.toResponse() },
                    relevantRules = result.relevantRules.map { it.toString() },
                    totalFactsAvailable = result.totalFactsAvailable,
                    totalFactsIncluded = result.totalFactsIncluded,
                    deduplicationSavings = result.deduplicationSavings,
                    contradictionsFound = result.contradictionsFound,
                    contradictionsResolved = result.contradictionsResolved,
                    contradictions = result.contradictions.map { it.toResponse() },
                    bucketStats = result.bucketStats.mapValues { (_, v) -> v.toResponse() },
                    totalCharCount = result.totalCharCount,
                    goalDriven = result.goalDriven,
                    knowledgeGeneration = result.knowledgeGeneration,
                    generatedAt = result.generatedAt
                )
            ))
        } catch (e: ValidationException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", e.message ?: "Validation error"))
        } catch (e: DatabaseNotFoundException) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", e.message ?: "Not found"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("INGEST_ERROR", e.message ?: "Ingestion failed"))
        }
    }
}
