package com.nocturnusai.server.routes

import com.nocturnusai.server.*
import com.nocturnusai.server.observability.Metrics
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Simplified developer-friendly route aliases.
 *
 * These wrap the existing logic/memory endpoints with intuitive verbs:
 *   POST /tell    — Tell NocturnusAI a fact (wraps /assert/fact)
 *   POST /ask     — Ask NocturnusAI a question with full inference (wraps /infer)
 *   POST /query   — Direct pattern match, no inference (reads Hexastore only)
 *   POST /teach   — Teach NocturnusAI a rule (wraps /assert/rule)
 *   POST /forget  — Make NocturnusAI forget something (wraps /retract)
 *
 * Memory aliases:
 *   POST /memory/recall    — Recall facts at a point in time (wraps /memory/query/temporal)
 *   POST /memory/compress  — Compress episodic patterns (wraps /memory/consolidate)
 *   POST /memory/cleanup   — Expire/evict stale facts (wraps /memory/decay)
 *   POST /memory/prioritize — Set relevance priority (wraps /memory/priority)
 *   GET  /memory/stream    — Subscribe to knowledge changes (wraps /memory/events)
 */

// --- Simplified DTOs ---

@Serializable
data class TellRequest(
    val predicate: String,
    val args: List<String>,
    val truthVal: Boolean = true,
    val negated: Boolean = false,
    val scope: String? = null,
    val metadata: Map<String, JsonElement> = emptyMap(),
    val validFrom: Long? = null,
    val validUntil: Long? = null,
    val ttl: Long? = null
)

@Serializable
data class AskRequest(
    val predicate: String,
    val args: List<String>,
    val scope: String? = null,
    val withProof: Boolean = false
)

@Serializable
data class TeachRequest(
    val head: AtomDto,
    val body: List<AtomDto>,
    val scope: String? = null
)

@Serializable
data class QueryRequest(
    val predicate: String,
    val args: List<String>,
    val scope: String? = null
)

@Serializable
data class ForgetRequest(
    val predicate: String,
    val args: List<String>,
    val scope: String? = null
)

// --- Routes ---

fun Route.simplifiedRoutes(dbManager: DatabaseManager) {

    // TELL — "Tell NocturnusAI something it should know"
    post("/tell") {
        try {
            val (db, tenantId) = call.getContext(dbManager)
            val req = call.receive<TellRequest>()
            Validator.validateFactRequest(FactRequest(req.predicate, req.args, req.truthVal, req.negated, req.scope, req.metadata))
            call.application.environment.log.info("Endpoint /tell hit. Tenant: $tenantId")
            val terms = req.args.map { parseTerm(it) }
            val effectiveTruth = if (req.negated) false else req.truthVal

            val atom = com.nocturnusai.core.Atom(
                req.predicate, terms, effectiveTruth, scope = req.scope, metadata = req.metadata,
                validFrom = req.validFrom, validUntil = req.validUntil, ttl = req.ttl
            )

            val txId = call.request.header("X-Transaction-ID")?.toLongOrNull()

            val dbName = call.request.header("X-Database") ?: "default"
            if (txId != null) {
                if (effectiveTruth) {
                    db.transactionManager.assertFact(txId, atom)
                } else {
                    db.transactionManager.retractFact(txId, atom)
                    db.transactionManager.assertFact(txId, atom)
                }
                call.respondText("Stored in Tx $txId: $atom")
            } else {
                db.assertFact(atom, tenantId)
                call.respondText("Stored: $atom")
            }
            Metrics.factAsserted(dbName, tenantId)
        } catch (e: ValidationException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", e.message ?: "Validation error"))
        } catch (e: DatabaseNotFoundException) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", e.message ?: "Not found"))
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("CONFLICT", e.message ?: "Contradiction"))
        } catch (e: IllegalStateException) {
            call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("TOO_MANY_REQUESTS", e.message ?: "Too many requests"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("INTERNAL_ERROR", e.message ?: "Error"))
        }
    }

    // ASK — "Ask NocturnusAI a question"
    post("/ask") {
        try {
            val (db, tenantId) = call.getContext(dbManager)
            val req = call.receive<AskRequest>()
            Validator.validateFactRequest(FactRequest(req.predicate, req.args))
            call.application.environment.log.info("Endpoint /ask hit. Tenant: $tenantId")
            val dbName = call.request.header("X-Database") ?: "default"
            val sample = Metrics.inferenceTimer()
            val terms = req.args.map { parseTerm(it) }
            val queryAtom = com.nocturnusai.core.Atom(req.predicate, terms, scope = req.scope)

            if (req.withProof) {
                val proofTrees = db.inferWithProof(queryAtom, tenantId)
                val response = proofTrees.map { ProofTreeResponse.from(it) }.toList()
                Metrics.inferenceCompleted(sample, dbName, response.size)
                call.respond(response)
            } else {
                val results = db.infer(queryAtom, tenantId)
                val response = results.map { AtomResponse.from(it) }.toList()
                Metrics.inferenceCompleted(sample, dbName, response.size)
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

    // QUERY — "Directly match stored facts (no inference, faster than /ask)"
    post("/query") {
        try {
            val (db, tenantId) = call.getContext(dbManager)
            val req = call.receive<QueryRequest>()
            Validator.validateFactRequest(FactRequest(req.predicate, req.args))
            call.application.environment.log.info("Endpoint /query hit. Tenant: $tenantId")
            val terms = req.args.map { parseTerm(it) }
            val pattern = com.nocturnusai.core.Atom(req.predicate, terms, scope = req.scope)
            val results = db.query(pattern, tenantId, req.scope).toList()
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

    // TEACH — "Teach NocturnusAI a rule"
    post("/teach") {
        try {
            val (db, tenantId) = call.getContext(dbManager)
            val req = call.receive<TeachRequest>()
            Validator.validateRuleRequest(RuleRequest(req.head, req.body, req.scope))
            call.application.environment.log.info("Endpoint /teach hit. Tenant: $tenantId")

            val headTerms = req.head.args.map { parseTerm(it) }
            val headAtom = com.nocturnusai.core.Atom(req.head.predicate, headTerms, truthVal = !req.head.negated, scope = req.head.scope, metadata = req.head.metadata)

            val bodyAtoms = req.body.map { atomReq ->
                val terms = atomReq.args.map { parseTerm(it) }
                com.nocturnusai.core.Atom(atomReq.predicate, terms, truthVal = !atomReq.negated, scope = atomReq.scope, metadata = atomReq.metadata)
            }

            val allTerms = headTerms + bodyAtoms.flatMap { it.args }
            val variables = allTerms.filterIsInstance<com.nocturnusai.core.Term.Variable>().distinct()
            val rule = com.nocturnusai.core.Rule(variables, headAtom, bodyAtoms, scope = req.scope)

            val txId = call.request.header("X-Transaction-ID")?.toLongOrNull()

            val dbName = call.request.header("X-Database") ?: "default"
            if (txId != null) {
                db.transactionManager.assertRule(txId, rule)
                call.respondText("Rule stored in Tx $txId: $rule")
            } else {
                db.assertRule(rule, tenantId)
                call.respondText("Rule stored: $rule")
            }
            Metrics.ruleAsserted(dbName, tenantId)
        } catch (e: ValidationException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", e.message ?: "Validation error"))
        } catch (e: DatabaseNotFoundException) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", e.message ?: "Not found"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("BAD_REQUEST", e.message ?: "Error"))
        }
    }

    // FORGET — "Make NocturnusAI forget something (and everything derived from it)"
    post("/forget") {
        try {
            val (db, tenantId) = call.getContext(dbManager)
            val req = call.receive<ForgetRequest>()
            Validator.validateFactRequest(FactRequest(req.predicate, req.args))
            call.application.environment.log.info("Endpoint /forget hit. Tenant: $tenantId")
            val terms = req.args.map { parseTerm(it) }
            val atom = com.nocturnusai.core.Atom(req.predicate, terms, scope = req.scope)

            val txId = call.request.header("X-Transaction-ID")?.toLongOrNull()

            val dbName = call.request.header("X-Database") ?: "default"
            if (txId != null) {
                db.transactionManager.retractFact(txId, atom)
                call.respondText("Forgotten in Tx $txId: $atom")
            } else {
                db.retractFact(atom, tenantId)
                call.respondText("Forgotten: $atom (and any knowledge derived from it)")
            }
            Metrics.factRetracted(dbName, tenantId)
        } catch (e: ValidationException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", e.message ?: "Validation error"))
        } catch (e: DatabaseNotFoundException) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", e.message ?: "Not found"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("BAD_REQUEST", e.message ?: "Error"))
        }
    }

    // --- Memory aliases ---

    // RECALL — "What was true at a specific time?"
    post("/memory/recall") {
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

    // COMPRESS — "Compress repeated patterns into summaries"
    post("/memory/compress") {
        try {
            val (db, tenantId) = call.getContext(dbManager)
            val result = db.runConsolidation(tenantId)
            Metrics.memoryConsolidation(result.factsConsolidated, result.newFacts.size)
            call.respond(ConsolidationResponse(
                factsConsolidated = result.factsConsolidated,
                newFacts = result.newFacts.map { AtomResponse.from(it) },
                timestamp = result.timestamp
            ))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("BAD_REQUEST", e.message ?: "Error"))
        }
    }

    // CLEANUP — "Expire old and irrelevant knowledge"
    post("/memory/cleanup") {
        try {
            val (db, tenantId) = call.getContext(dbManager)
            val req = try { call.receive<DecayRequest>() } catch (_: Exception) { DecayRequest() }
            val result = db.runDecay(tenantId, req.threshold)
            Metrics.memoryDecay(result.expiredCount, result.evictedCount)
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

    // PRIORITIZE — "This knowledge matters more"
    post("/memory/prioritize") {
        try {
            val (db, tenantId) = call.getContext(dbManager)
            val req = call.receive<SetPriorityRequest>()
            val terms = req.args.map { parseTerm(it) }
            val fact = com.nocturnusai.core.Atom(req.predicate, terms, req.truthVal, scope = req.scope)

            db.setSaliencePriority(fact, req.priority, tenantId)
            call.respondText("Priority set: ${req.priority} for $fact")
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("BAD_REQUEST", e.message ?: "Error"))
        }
    }

    // STREAM — "Subscribe to knowledge changes in real-time"
    get("/memory/stream") {
        try {
            val dbName = call.request.header("X-Database") ?: "default"
            val tenantId = call.request.header("X-Tenant-ID")?.takeIf { it.isNotBlank() }
                ?: throw ValidationException("X-Tenant-ID header is required")
            val db = dbManager.getDatabase(dbName) ?: throw DatabaseNotFoundException(dbName)

            val predicatePattern = call.request.queryParameters["predicate"]
            val eventTypesParam = call.request.queryParameters["events"]
            val eventTypes = eventTypesParam?.split(",")?.toSet()
                ?: setOf("told", "forgotten", "rule_taught", "expired", "compressed",
                    // Also accept legacy names
                    "fact_asserted", "fact_retracted", "rule_asserted", "fact_expired", "consolidation")
            // Map simplified names to internal names
            val mappedTypes = eventTypes.map { type ->
                when (type) {
                    "told" -> "fact_asserted"
                    "forgotten" -> "fact_retracted"
                    "rule_taught" -> "rule_asserted"
                    "expired" -> "fact_expired"
                    "compressed" -> "consolidation"
                    else -> type
                }
            }.toSet()

            val sinceId = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L

            call.response.cacheControl(CacheControl.NoCache(null))
            call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                val missedEvents = db.getEventsSince(sinceId, tenantId)
                for (event in missedEvents) {
                    write("data: ${serializeSimplifiedEvent(event)}\n\n")
                }
                flush()

                val subId = db.subscribe(predicatePattern, mappedTypes, tenantId) { event ->
                    try {
                        write("data: ${serializeSimplifiedEvent(event)}\n\n")
                        flush()
                    } catch (_: Exception) {}
                }

                try {
                    while (true) {
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

private fun serializeSimplifiedEvent(event: com.nocturnusai.memory.KnowledgeEvent): String {
    val json = kotlinx.serialization.json.Json { encodeDefaults = true }
    return when (event) {
        is com.nocturnusai.memory.KnowledgeEvent.FactAsserted -> {
            """{"type":"told","eventId":${event.eventId},"timestamp":${event.timestamp},"atom":${json.encodeToString(kotlinx.serialization.serializer(), AtomResponse.from(event.atom))}}"""
        }
        is com.nocturnusai.memory.KnowledgeEvent.FactRetracted -> {
            """{"type":"forgotten","eventId":${event.eventId},"timestamp":${event.timestamp},"atom":${json.encodeToString(kotlinx.serialization.serializer(), AtomResponse.from(event.atom))}}"""
        }
        is com.nocturnusai.memory.KnowledgeEvent.RuleAsserted -> {
            """{"type":"rule_taught","eventId":${event.eventId},"timestamp":${event.timestamp},"rule":"${event.rule}"}"""
        }
        is com.nocturnusai.memory.KnowledgeEvent.FactExpired -> {
            """{"type":"expired","eventId":${event.eventId},"timestamp":${event.timestamp},"atom":${json.encodeToString(kotlinx.serialization.serializer(), AtomResponse.from(event.atom))}}"""
        }
        is com.nocturnusai.memory.KnowledgeEvent.ConsolidationOccurred -> {
            """{"type":"compressed","eventId":${event.eventId},"timestamp":${event.timestamp},"sourceCount":${event.sourceCount},"fact":${json.encodeToString(kotlinx.serialization.serializer(), AtomResponse.from(event.consolidatedFact))}}"""
        }
    }
}
