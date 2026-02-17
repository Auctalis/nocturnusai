package com.nocturnusai.server.routes

import com.nocturnusai.server.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.logicRoutes(dbManager: DatabaseManager) {
    post("/execute") {
        try {
            val (db, tenantId) = call.getContext(dbManager)
            val request = call.receive<ExecuteRequest>()
            call.application.environment.log.info("Endpoint /execute hit. Tenant: $tenantId, Request: $request")
            val result = db.execute(request.command, tenantId)
            call.respond(ExecuteResponse(result))
        } catch (e: ValidationException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", e.message ?: "Validation error"))
        } catch (e: DatabaseNotFoundException) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", e.message ?: "Not found"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("BAD_REQUEST", e.message ?: "Error"))
        }
    }

    // Endpoint 1: Add Knowledge (ASSERT)
    post("/assert/fact") {
        try {
            val (db, tenantId) = call.getContext(dbManager)
            val req = call.receive<FactRequest>()
            Validator.validateFactRequest(req)
            call.application.environment.log.info("Endpoint /assert/fact hit. Tenant: $tenantId, Request: $req")
            val terms = req.args.map { parseTerm(it) }
            val effectiveTruth = if (req.negated) false else req.truthVal

            val atom = com.nocturnusai.core.Atom(
                req.predicate, terms, effectiveTruth, scope = req.scope, metadata = req.metadata,
                validFrom = req.validFrom, validUntil = req.validUntil, ttl = req.ttl
            )

            val txId = call.request.header("X-Transaction-ID")?.toLongOrNull()

            if (txId != null) {
                if (effectiveTruth) {
                    db.transactionManager.assertFact(txId, atom)
                } else {
                    db.transactionManager.retractFact(txId, atom)
                    db.transactionManager.assertFact(txId, atom)
                }
                call.respondText("Fact Buffered in Tx $txId: $atom")
            } else {
                db.assertFact(atom, tenantId)
                call.respondText("Fact Asserted: $atom")
            }
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

    // Endpoint 4: Retract Knowledge (TMS Trigger)
    post("/retract") {
        try {
            val (db, tenantId) = call.getContext(dbManager)
            val req = call.receive<FactRequest>()
            Validator.validateFactRequest(req)
            call.application.environment.log.info("Endpoint /retract hit. Tenant: $tenantId, Request: $req")
            val terms = req.args.map { parseTerm(it) }
            val atom = com.nocturnusai.core.Atom(req.predicate, terms, req.truthVal, scope = req.scope)

            val txId = call.request.header("X-Transaction-ID")?.toLongOrNull()

            if (txId != null) {
                 db.transactionManager.retractFact(txId, atom)
                 call.respondText("Retraction Buffered in Tx $txId: $atom")
            } else {
                 db.retractFact(atom, tenantId)
                 call.respondText("Retracted: $atom")
            }
        } catch (e: ValidationException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", e.message ?: "Validation error"))
        } catch (e: DatabaseNotFoundException) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", e.message ?: "Not found"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("BAD_REQUEST", e.message ?: "Error"))
        }
    }

    // Endpoint 2: Teach Logic (ADD RULE)
    post("/assert/rule") {
        try {
            val (db, tenantId) = call.getContext(dbManager)
            val req = call.receive<RuleRequest>()
            Validator.validateRuleRequest(req)
            call.application.environment.log.info("Endpoint /assert/rule hit. Tenant: $tenantId, Request: $req")

            // Parse Head
            val headTerms = req.head.args.map { parseTerm(it) }
            val headAtom = com.nocturnusai.core.Atom(req.head.predicate, headTerms, truthVal = !req.head.negated, scope = req.head.scope, metadata = req.head.metadata)

            // Parse Body
            val bodyAtoms = req.body.map { atomReq ->
                val terms = atomReq.args.map { parseTerm(it) }
                com.nocturnusai.core.Atom(atomReq.predicate, terms, truthVal = !atomReq.negated, scope = atomReq.scope, metadata = atomReq.metadata)
            }

            // Collect Variables (from Head and ALL Body atoms)
            val allTerms = headTerms + bodyAtoms.flatMap { it.args }
            val variables = allTerms.filterIsInstance<com.nocturnusai.core.Term.Variable>().distinct()

            val rule = com.nocturnusai.core.Rule(variables, headAtom, bodyAtoms, scope = req.scope)

            val txId = call.request.header("X-Transaction-ID")?.toLongOrNull()

            if (txId != null) {
                db.transactionManager.assertRule(txId, rule)
                 call.respondText("Rule Buffered in Tx $txId: $rule")
            } else {
                db.assertRule(rule, tenantId)
                call.respondText("Rule Asserted: $rule")
            }
        } catch (e: ValidationException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", e.message ?: "Validation error"))
        } catch (e: DatabaseNotFoundException) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", e.message ?: "Not found"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("BAD_REQUEST", e.message ?: "Error"))
        }
    }

    // Endpoint 2.5: Apply Template
    post("/assert/template") {
        try {
            val (db, tenantId) = call.getContext(dbManager)
            val req = call.receive<TemplateRequest>()
            call.application.environment.log.info("Endpoint /assert/template hit. Tenant: $tenantId, Request: $req")
            val service = TemplateService()
            val rules = service.generateRule(req)

            val txId = call.request.header("X-Transaction-ID")?.toLongOrNull()

            val buffer = StringBuilder()
            rules.forEach { rule ->
                if (txId != null) {
                    db.transactionManager.assertRule(txId, rule)
                    buffer.append("Rule Buffered in Tx $txId: $rule\n")
                } else {
                    db.assertRule(rule, tenantId)
                    buffer.append("Rule Asserted: $rule\n")
                }
            }
            call.respondText(buffer.toString())
        } catch (e: ValidationException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", e.message ?: "Validation error"))
        } catch (e: DatabaseNotFoundException) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", e.message ?: "Not found"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("BAD_REQUEST", e.message ?: "Error"))
        }
    }

    // Schema discovery: list all predicates in the knowledge base
    get("/predicates") {
        try {
            val (db, tenantId) = call.getContext(dbManager)
            val scope = call.request.queryParameters["scope"]

            // Get all facts and group by predicate
            val allFacts = db.getAllFacts(tenantId, scope).toList()
            val factsByPred = allFacts.groupBy { it.predicate }

            // Get all rules
            val rules = db.getRules(tenantId, scope)
            val rulesByHead = rules.groupBy { it.head.predicate }

            // Build unified predicate list
            val allPredicates = (factsByPred.keys + rulesByHead.keys).distinct().sorted()
            val predicateInfos = allPredicates.map { pred ->
                val facts = factsByPred[pred] ?: emptyList()
                val predRules = rulesByHead[pred] ?: emptyList()
                mapOf(
                    "predicate" to pred,
                    "factCount" to facts.size,
                    "ruleCount" to predRules.size,
                    "arity" to (facts.firstOrNull()?.args?.size ?: predRules.firstOrNull()?.head?.args?.size ?: 0),
                    "hasRules" to predRules.isNotEmpty()
                )
            }

            call.respond(mapOf(
                "predicates" to predicateInfos,
                "totalPredicates" to predicateInfos.size,
                "totalFacts" to allFacts.size,
                "totalRules" to rules.size
            ))
        } catch (e: ValidationException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", e.message ?: "Validation error"))
        } catch (e: DatabaseNotFoundException) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", e.message ?: "Not found"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("INTERNAL_ERROR", e.message ?: "Error"))
        }
    }

    // Endpoint 3: Ask Questions (INFER)
    post("/infer") {
        try {
            val (db, tenantId) = call.getContext(dbManager)
            val req = call.receive<FactRequest>()
            Validator.validateFactRequest(req)
            call.application.environment.log.info("Endpoint /infer hit. Tenant: $tenantId, Request: $req")
            val terms = req.args.map { parseTerm(it) }
            val effectiveTruth = if (req.negated) false else req.truthVal
            val queryAtom = com.nocturnusai.core.Atom(req.predicate, terms, effectiveTruth, scope = req.scope)

            val withProof = call.request.queryParameters["proof"]?.toBooleanStrictOrNull() ?: false

            if (withProof) {
                val proofTrees = db.inferWithProof(queryAtom, tenantId)
                val response = proofTrees.map { ProofTreeResponse.from(it) }.toList()
                call.respond(response)
            } else {
                val results = db.infer(queryAtom, tenantId)
                val response = results.map { AtomResponse.from(it) }.toList()
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
}
