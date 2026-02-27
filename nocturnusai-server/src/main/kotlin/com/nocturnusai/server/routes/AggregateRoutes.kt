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

import com.nocturnusai.server.*
import com.nocturnusai.storage.AggregateOp
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// DTOs
// ---------------------------------------------------------------------------

@Serializable
data class AggregateRequest(
    val predicate: String,
    val args: List<String>,
    val operation: String,       // COUNT | SUM | MIN | MAX | AVG
    val argIndex: Int = 0,       // which arg position to aggregate (SUM/MIN/MAX/AVG)
    val scope: String? = null
)

@Serializable
data class AggregateResponse(
    val operation: String,
    val predicate: String,
    val result: Double,
    val matchedFacts: Int,
    val timestamp: String
)

@Serializable
data class BulkAssertRequest(
    val facts: List<FactRequest>
)

@Serializable
data class BulkAssertResponse(
    val asserted: Int,
    val failed: Int,
    val errors: List<String>,
    val timestamp: String
)

@Serializable
data class RetractPatternRequest(
    val predicate: String,
    val args: List<String>,
    val scope: String? = null
)

@Serializable
data class RetractPatternResponse(
    val retracted: Int,
    val atoms: List<AtomResponse>
)

// ---------------------------------------------------------------------------
// Routes
// ---------------------------------------------------------------------------

fun Route.aggregateRoutes(dbManager: DatabaseManager) {

    // POST /aggregate — apply COUNT/SUM/MIN/MAX/AVG over a pattern
    post("/aggregate") {
        try {
            val (db, tenantId) = call.getContext(dbManager)
            val req = call.receive<AggregateRequest>()

            if (req.predicate.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("VALIDATION_ERROR", "Predicate must not be blank")
                )
                return@post
            }

            val op = try {
                AggregateOp.valueOf(req.operation.uppercase())
            } catch (_: IllegalArgumentException) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(
                        "VALIDATION_ERROR",
                        "Unknown operation '${req.operation}'. Allowed: COUNT, SUM, MIN, MAX, AVG"
                    )
                )
                return@post
            }

            val terms = req.args.map { parseTerm(it) }
            val pattern = com.nocturnusai.core.Atom(req.predicate, terms, scope = req.scope)

            // COUNT: use countFacts; others: use aggregateFacts
            val matchedFacts: Int
            val result: Double

            if (op == AggregateOp.COUNT) {
                matchedFacts = db.countFacts(pattern, tenantId, req.scope)
                result = matchedFacts.toDouble()
            } else {
                matchedFacts = db.countFacts(pattern, tenantId, req.scope)
                result = db.aggregateFacts(pattern, req.argIndex, op, tenantId, req.scope)
                    ?: 0.0
            }

            call.respond(
                AggregateResponse(
                    operation = op.name,
                    predicate = req.predicate,
                    result = result,
                    matchedFacts = matchedFacts,
                    timestamp = java.time.Instant.now().toString()
                )
            )
        } catch (e: ValidationException) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("VALIDATION_ERROR", e.message ?: "Validation error")
            )
        } catch (e: DatabaseNotFoundException) {
            call.respond(
                HttpStatusCode.NotFound,
                ErrorResponse("NOT_FOUND", e.message ?: "Not found")
            )
        } catch (e: Exception) {
            call.application.environment.log.error("Aggregate error", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("INTERNAL_ERROR", e.message ?: "Internal server error")
            )
        }
    }

    // POST /assert/facts — bulk assert (plural, non-transactional best-effort)
    post("/assert/facts") {
        try {
            val (db, tenantId) = call.getContext(dbManager)
            val req = call.receive<BulkAssertRequest>()

            if (req.facts.isEmpty()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("VALIDATION_ERROR", "facts array must not be empty")
                )
                return@post
            }

            // Validate all requests first
            for ((index, factReq) in req.facts.withIndex()) {
                try {
                    Validator.validateFactRequest(factReq)
                } catch (e: ValidationException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("VALIDATION_ERROR", "facts[$index]: ${e.message}")
                    )
                    return@post
                }
            }

            val atoms = req.facts.map { factReq ->
                val terms = factReq.args.map { parseTerm(it) }
                val effectiveTruth = if (factReq.negated) false else factReq.truthVal
                com.nocturnusai.core.Atom(
                    predicate = factReq.predicate,
                    args = terms,
                    truthVal = effectiveTruth,
                    scope = factReq.scope,
                    metadata = factReq.metadata,
                    validFrom = factReq.validFrom,
                    validUntil = factReq.validUntil,
                    ttl = factReq.ttl
                )
            }

            val result = db.bulkAssertFacts(atoms, tenantId)

            call.respond(
                BulkAssertResponse(
                    asserted = result.asserted,
                    failed = result.failed,
                    errors = result.errors,
                    timestamp = java.time.Instant.now().toString()
                )
            )
        } catch (e: ValidationException) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("VALIDATION_ERROR", e.message ?: "Validation error")
            )
        } catch (e: DatabaseNotFoundException) {
            call.respond(
                HttpStatusCode.NotFound,
                ErrorResponse("NOT_FOUND", e.message ?: "Not found")
            )
        } catch (e: Exception) {
            call.application.environment.log.error("Bulk assert error", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("INTERNAL_ERROR", e.message ?: "Internal server error")
            )
        }
    }

    // POST /retract/pattern — retract all facts matching a pattern (supports wildcards)
    post("/retract/pattern") {
        try {
            val (db, tenantId) = call.getContext(dbManager)
            val req = call.receive<RetractPatternRequest>()

            if (req.predicate.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("VALIDATION_ERROR", "Predicate must not be blank")
                )
                return@post
            }

            val terms = req.args.map { parseTerm(it) }
            val pattern = com.nocturnusai.core.Atom(req.predicate, terms, scope = req.scope)

            val result = db.retractByPattern(pattern, tenantId, req.scope)

            call.respond(
                RetractPatternResponse(
                    retracted = result.retracted,
                    atoms = result.atoms.map { AtomResponse.from(it) }
                )
            )
        } catch (e: ValidationException) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("VALIDATION_ERROR", e.message ?: "Validation error")
            )
        } catch (e: DatabaseNotFoundException) {
            call.respond(
                HttpStatusCode.NotFound,
                ErrorResponse("NOT_FOUND", e.message ?: "Not found")
            )
        } catch (e: Exception) {
            call.application.environment.log.error("Retract pattern error", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("INTERNAL_ERROR", e.message ?: "Internal server error")
            )
        }
    }
}
