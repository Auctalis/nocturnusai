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

package com.nocturnusai.server.routes

import com.nocturnusai.context.GoalSpec
import com.nocturnusai.context.OptimizeContextRequest
import com.nocturnusai.context.RelevanceBucket
import com.nocturnusai.memory.ContextFormat
import com.nocturnusai.memory.ContextFormatter
import com.nocturnusai.memory.ContextWindow
import com.nocturnusai.memory.ScoredAtom
import com.nocturnusai.server.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// --- Memory API DTOs ---

@Serializable
data class TemporalQueryRequest(
    val predicate: String,
    val args: List<String>,
    val timestamp: Long, // epoch ms — point-in-time query
    val scope: String? = null
)

@Serializable
data class SalienceQueryRequest(
    val predicate: String,
    val args: List<String>,
    val scope: String? = null,
    val limit: Int = 50,
    val minSalience: Double = 0.0
)

@Serializable
data class ContextWindowRequest(
    // Simple mode
    val maxFacts: Int = 100,
    val minSalience: Double = 0.0,
    val predicates: List<String>? = null,
    val scope: String? = null,
    val format: String? = null,        // "predicate", "natural", "structured"
    val includeRules: Boolean = true,
    // Advanced mode (presence triggers optimizeContext engine)
    val goals: List<GoalSpecDto>? = null,
    val sessionId: String? = null,
    val autoResolveContradictions: Boolean = true,
    val maxFactsPerPredicate: Int? = null,
    val relevanceBuckets: List<RelevanceBucketDto>? = null
)

@Serializable
data class SetPriorityRequest(
    val predicate: String,
    val args: List<String>,
    val truthVal: Boolean = true,
    val scope: String? = null,
    val priority: Double // 0.0 to 1.0
)

@Serializable
data class DecayRequest(
    val threshold: Double? = null // override default eviction threshold
)

// --- Response DTOs ---

@Serializable
data class ScoredAtomResponse(
    val predicate: String,
    val args: List<String>,
    val negated: Boolean = false,
    val scope: String? = null,
    val salience: Double,
    val createdAt: Long? = null,
    val validFrom: Long? = null,
    val validUntil: Long? = null,
    val metadata: Map<String, JsonElement> = emptyMap()
)

@Serializable
data class ContextWindowResponse(
    val facts: List<ScoredAtomResponse>,
    val totalAvailable: Int,
    val windowSize: Int,
    val predicateDistribution: Map<String, Int>,
    val generatedAt: Long,
    val formattedText: String? = null,   // present when format is specified (non-default)
    // Advanced fields (present when optimization engine used)
    val windowId: String? = null,
    val rules: List<String>? = null,
    val contradictionsFound: Int? = null,
    val contradictionsResolved: Int? = null,
    val contradictions: List<ContradictionResponse>? = null,
    val deduplicationSavings: Int? = null,
    val bucketStats: Map<String, BucketStatsResponse>? = null,
    val goalDriven: Boolean = false,
    val knowledgeGeneration: Long? = null
)

@Serializable
data class ConsolidationResponse(
    val factsConsolidated: Int,
    val newFacts: List<AtomResponse>,
    val timestamp: Long
)

@Serializable
data class DecayResponse(
    val expiredCount: Int,
    val evictedCount: Int,
    val removedAtoms: List<AtomResponse>,
    val timestamp: Long
)

// --- Routes ---

fun Route.memoryRoutes(dbManager: DatabaseManager, llmContextFormatter: com.nocturnusai.server.llm.LlmContextFormatter? = null) {

    // Temporal query: "What was true at time T?"
    post("/memory/query/temporal") {
        try {
            val (db, tenantId) = call.getContext(dbManager)
            val req = call.receive<TemporalQueryRequest>()
            val terms = req.args.map { parseTerm(it) }
            val pattern = com.nocturnusai.core.Atom(req.predicate, terms, scope = req.scope)

            val results = db.queryAtTime(pattern, req.timestamp, tenantId, req.scope)
            val response = results.map { AtomResponse.from(it) }
            call.respond(response)
        } catch (e: ValidationException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", e.message ?: "Validation error"))
        } catch (e: DatabaseNotFoundException) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", e.message ?: "Not found"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("BAD_REQUEST", e.message ?: "Error"))
        }
    }

    // Salience-ranked query: "What are the most relevant facts matching this pattern?"
    post("/memory/query/salient") {
        try {
            val (db, tenantId) = call.getContext(dbManager)
            val req = call.receive<SalienceQueryRequest>()
            val terms = req.args.map { parseTerm(it) }
            val pattern = com.nocturnusai.core.Atom(req.predicate, terms, scope = req.scope)

            val results = db.queryWithSalience(pattern, tenantId, req.scope, req.limit, req.minSalience)
            val response = results.map { scored ->
                ScoredAtomResponse(
                    predicate = scored.atom.predicate,
                    args = scored.atom.args.map { it.toString() },
                    negated = !scored.atom.truthVal,
                    scope = scored.atom.scope,
                    salience = scored.salience,
                    createdAt = scored.atom.createdAt,
                    validFrom = scored.atom.validFrom,
                    validUntil = scored.atom.validUntil,
                    metadata = scored.atom.metadata
                )
            }
            call.respond(response)
        } catch (e: ValidationException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", e.message ?: "Validation error"))
        } catch (e: DatabaseNotFoundException) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", e.message ?: "Not found"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("BAD_REQUEST", e.message ?: "Error"))
        }
    }

    // Context window: "Give me the optimal set of facts for my next reasoning step"
    // Unified endpoint: simple salience-ranked path OR advanced goal-driven optimization.
    // Advanced mode is triggered when goals, sessionId, relevanceBuckets, or maxFactsPerPredicate are present.
    post("/memory/context") {
        try {
            val (db, tenantId) = call.getContext(dbManager)
            val req = call.receive<ContextWindowRequest>()

            val isAdvanced = req.goals != null || req.sessionId != null ||
                    req.relevanceBuckets != null || req.maxFactsPerPredicate != null

            if (isAdvanced) {
                // Delegate to optimization engine
                val result = db.optimizeContext(
                    request = OptimizeContextRequest(
                        maxFacts = req.maxFacts,
                        scope = req.scope,
                        predicates = req.predicates,
                        goals = req.goals?.map { GoalSpec(it.predicate, it.args, it.negated) },
                        relevanceBuckets = req.relevanceBuckets?.map { RelevanceBucket(it.name, it.predicates, it.weight) },
                        sessionId = req.sessionId,
                        autoResolveContradictions = req.autoResolveContradictions,
                        maxFactsPerPredicate = req.maxFactsPerPredicate
                    ),
                    tenantId = tenantId
                )

                // Build formatted text if requested
                val contextFormat = when (req.format?.lowercase()) {
                    "natural" -> ContextFormat.NATURAL
                    "structured" -> ContextFormat.STRUCTURED
                    else -> null
                }
                val formattedText = if (contextFormat != null) {
                    val scoredAtoms = result.entries.map { entry ->
                        ScoredAtom(entry.atom, entry.salience)
                    }
                    val rules = if (req.includeRules) db.getRules(tenantId, req.scope) else emptyList()
                    if (contextFormat == ContextFormat.NATURAL && llmContextFormatter != null) {
                        llmContextFormatter.format(scoredAtoms, rules)
                    } else {
                        val window = ContextWindow(
                            facts = scoredAtoms,
                            totalAvailable = result.totalFactsAvailable,
                            windowSize = result.totalFactsIncluded,
                            predicateDistribution = scoredAtoms.groupBy { it.atom.predicate }.mapValues { it.value.size },
                            generatedAt = result.generatedAt
                        )
                        ContextFormatter.format(window, rules, contextFormat)
                    }
                } else null

                val response = ContextWindowResponse(
                    facts = result.entries.map { entry ->
                        ScoredAtomResponse(
                            predicate = entry.atom.predicate,
                            args = entry.atom.args.map { it.toString() },
                            negated = !entry.atom.truthVal,
                            scope = entry.atom.scope,
                            salience = entry.salience,
                            createdAt = entry.atom.createdAt,
                            validFrom = entry.atom.validFrom,
                            validUntil = entry.atom.validUntil,
                            metadata = entry.atom.metadata
                        )
                    },
                    totalAvailable = result.totalFactsAvailable,
                    windowSize = result.totalFactsIncluded,
                    predicateDistribution = result.entries.groupBy { it.atom.predicate }.mapValues { (_, v) -> v.size },
                    generatedAt = result.generatedAt,
                    formattedText = formattedText,
                    windowId = result.windowId,
                    rules = result.relevantRules.map { it.toString() },
                    contradictionsFound = result.contradictionsFound,
                    contradictionsResolved = result.contradictionsResolved,
                    contradictions = result.contradictions.map { ContradictionResponse(it.predicate, it.args, it.positiveSalience, it.negativeSalience) },
                    deduplicationSavings = result.deduplicationSavings,
                    bucketStats = result.bucketStats.mapValues { (_, v) -> BucketStatsResponse(v.factsIncluded, v.maxAllocation, v.minSalience, v.maxSalience) },
                    goalDriven = result.goalDriven,
                    knowledgeGeneration = result.knowledgeGeneration
                )
                call.respond(response)
            } else {
                // Simple salience-ranked path (existing behavior)
                val window = db.buildContextWindow(tenantId, req.scope, req.maxFacts, req.minSalience, req.predicates)

                // Generate formatted text if a non-default format is requested
                val formattedText = if (req.format != null && req.format.lowercase() != "predicate") {
                    val contextFormat = when (req.format.lowercase()) {
                        "natural" -> ContextFormat.NATURAL
                        "structured" -> ContextFormat.STRUCTURED
                        else -> ContextFormat.PREDICATE
                    }
                    val rules = if (req.includeRules) db.getRules(tenantId, req.scope) else emptyList()
                    if (contextFormat == ContextFormat.NATURAL && llmContextFormatter != null) {
                        llmContextFormatter.format(window.facts, rules)
                    } else {
                        ContextFormatter.format(window, rules, contextFormat)
                    }
                } else null

                val response = ContextWindowResponse(
                    facts = window.facts.map { scored ->
                        ScoredAtomResponse(
                            predicate = scored.atom.predicate,
                            args = scored.atom.args.map { it.toString() },
                            negated = !scored.atom.truthVal,
                            scope = scored.atom.scope,
                            salience = scored.salience,
                            createdAt = scored.atom.createdAt,
                            validFrom = scored.atom.validFrom,
                            validUntil = scored.atom.validUntil,
                            metadata = scored.atom.metadata
                        )
                    },
                    totalAvailable = window.totalAvailable,
                    windowSize = window.windowSize,
                    predicateDistribution = window.predicateDistribution,
                    generatedAt = window.generatedAt,
                    formattedText = formattedText
                )
                call.respond(response)
            }
        } catch (e: ValidationException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", e.message ?: "Validation error"))
        } catch (e: DatabaseNotFoundException) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", e.message ?: "Not found"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("BAD_REQUEST", e.message ?: "Error"))
        }
    }

    // Set salience priority for a fact
    post("/memory/priority") {
        try {
            val (db, tenantId) = call.getContext(dbManager)
            val req = call.receive<SetPriorityRequest>()
            if (req.priority < 0.0 || req.priority > 1.0) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", "Priority must be between 0.0 and 1.0, got ${req.priority}"))
                return@post
            }
            val terms = req.args.map { parseTerm(it) }
            val fact = com.nocturnusai.core.Atom(req.predicate, terms, req.truthVal, scope = req.scope)

            db.setSaliencePriority(fact, req.priority, tenantId)
            call.respondText("Priority set: ${req.priority} for $fact")
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("BAD_REQUEST", e.message ?: "Error"))
        }
    }

    // Run consolidation
    post("/memory/consolidate") {
        try {
            val (db, tenantId) = call.getContext(dbManager)
            val result = db.runConsolidation(tenantId)
            call.respond(ConsolidationResponse(
                factsConsolidated = result.factsConsolidated,
                newFacts = result.newFacts.map { AtomResponse.from(it) },
                timestamp = result.timestamp
            ))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("BAD_REQUEST", e.message ?: "Error"))
        }
    }

    // Run decay (expire TTL'd facts, evict low-salience)
    post("/memory/decay") {
        try {
            val (db, tenantId) = call.getContext(dbManager)
            val req = try { call.receive<DecayRequest>() } catch (_: Exception) { DecayRequest() }
            val result = db.runDecay(tenantId, req.threshold)
            call.respond(DecayResponse(
                expiredCount = result.expiredCount,
                evictedCount = result.evictedCount,
                removedAtoms = result.removedAtoms.map { AtomResponse.from(it) },
                timestamp = result.timestamp
            ))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("BAD_REQUEST", e.message ?: "Error"))
        }
    }

    // SSE event stream: subscribe to knowledge changes
    get("/memory/events") {
        try {
            val dbName = call.request.header("X-Database") ?: "default"
            val tenantId = call.request.header("X-Tenant-ID")?.takeIf { it.isNotBlank() }
                ?: throw ValidationException("X-Tenant-ID header is required")
            val db = dbManager.getDatabase(dbName) ?: throw DatabaseNotFoundException(dbName)

            val predicatePattern = call.request.queryParameters["predicate"]
            val eventTypesParam = call.request.queryParameters["events"]
            val eventTypes = eventTypesParam?.split(",")?.toSet()
                ?: setOf("fact_asserted", "fact_retracted", "rule_asserted", "fact_expired", "consolidation")
            val sinceId = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L

            call.response.cacheControl(CacheControl.NoCache(null))
            call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                // First, send any missed events
                val missedEvents = db.getEventsSince(sinceId, tenantId)
                for (event in missedEvents) {
                    write("data: ${serializeEvent(event)}\n\n")
                }
                flush()

                // Then subscribe for new events
                val subId = db.subscribe(predicatePattern, eventTypes, tenantId) { event ->
                    try {
                        write("data: ${serializeEvent(event)}\n\n")
                        flush()
                    } catch (_: Exception) {
                        // Client disconnected
                    }
                }

                try {
                    // Keep connection open — Ktor will close when client disconnects
                    while (true) {
                        // Send keepalive every 30 seconds
                        write(": keepalive\n\n")
                        flush()
                        kotlinx.coroutines.delay(30_000)
                    }
                } finally {
                    db.unsubscribe(subId, tenantId)
                }
            }
        } catch (e: ValidationException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", e.message ?: "Validation error"))
        } catch (e: DatabaseNotFoundException) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", e.message ?: "Not found"))
        }
    }
}

private fun serializeEvent(event: com.nocturnusai.memory.KnowledgeEvent): String {
    val json = kotlinx.serialization.json.Json { encodeDefaults = true }
    return when (event) {
        is com.nocturnusai.memory.KnowledgeEvent.FactAsserted -> {
            """{"type":"fact_asserted","eventId":${event.eventId},"timestamp":${event.timestamp},"atom":${json.encodeToString(kotlinx.serialization.serializer(), AtomResponse.from(event.atom))}}"""
        }
        is com.nocturnusai.memory.KnowledgeEvent.FactRetracted -> {
            """{"type":"fact_retracted","eventId":${event.eventId},"timestamp":${event.timestamp},"atom":${json.encodeToString(kotlinx.serialization.serializer(), AtomResponse.from(event.atom))}}"""
        }
        is com.nocturnusai.memory.KnowledgeEvent.RuleAsserted -> {
            """{"type":"rule_asserted","eventId":${event.eventId},"timestamp":${event.timestamp},"rule":"${event.rule}"}"""
        }
        is com.nocturnusai.memory.KnowledgeEvent.FactExpired -> {
            """{"type":"fact_expired","eventId":${event.eventId},"timestamp":${event.timestamp},"atom":${json.encodeToString(kotlinx.serialization.serializer(), AtomResponse.from(event.atom))}}"""
        }
        is com.nocturnusai.memory.KnowledgeEvent.ConsolidationOccurred -> {
            """{"type":"consolidation","eventId":${event.eventId},"timestamp":${event.timestamp},"sourceCount":${event.sourceCount},"fact":${json.encodeToString(kotlinx.serialization.serializer(), AtomResponse.from(event.consolidatedFact))}}"""
        }
    }
}
