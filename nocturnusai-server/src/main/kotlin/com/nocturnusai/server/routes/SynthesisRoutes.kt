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

import com.nocturnusai.core.*
import com.nocturnusai.server.*
import com.nocturnusai.server.llm.LlmAnswerSynthesizer
import com.nocturnusai.server.llm.LlmProvider
import com.nocturnusai.server.llm.LlmQueryTranslator
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.synthesisRoutes(dbManager: DatabaseManager, provider: LlmProvider?) {

    val translator = if (provider != null) LlmQueryTranslator(provider) else null
    val synthesizer = if (provider != null) LlmAnswerSynthesizer(provider) else null

    post("/synthesize") {
        if (translator == null || synthesizer == null || provider == null) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                ErrorResponse(
                    "LLM_NOT_CONFIGURED",
                    "No LLM provider configured. Set OPENAI_API_KEY, ANTHROPIC_API_KEY, or GOOGLE_API_KEY environment variable."
                )
            )
            return@post
        }

        try {
            val (db, tenantId) = call.getContext(dbManager)
            val req = call.receive<SynthesisRequest>()

            if (req.question.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", "Question must not be blank"))
                return@post
            }

            val scope = req.scope

            // 1. Get predicate schema for this tenant (and scope if specified)
            val schema = db.getPredicateSchema(tenantId, scope)
            if (schema.isEmpty()) {
                call.respond(SynthesisResponse(
                    answer = "The knowledge base is empty. No facts have been asserted${if (scope != null) " in scope '$scope'" else ""} yet.",
                    derivation = emptyList(),
                    missingContext = "The knowledge base contains no facts to query${if (scope != null) " within the specified scope" else ""}.",
                    confidence = 0.0f,
                    queriesExecuted = emptyList(),
                    provider = provider.name,
                    model = provider.model
                ))
                return@post
            }

            // 2. Translate NL question to logic query patterns
            val patterns = translator.translate(req.question, schema)
            if (patterns.isEmpty()) {
                call.respond(SynthesisResponse(
                    answer = "Could not translate the question into a knowledge base query.",
                    derivation = emptyList(),
                    missingContext = "The question could not be mapped to any predicates in the knowledge base.",
                    confidence = 0.0f,
                    queriesExecuted = emptyList(),
                    provider = provider.name,
                    model = provider.model
                ))
                return@post
            }

            // 3. Execute queries against the KB
            val allMatchedFacts = mutableListOf<String>()
            val allProofDescriptions = mutableListOf<String>()
            val allRulesUsed = mutableListOf<String>()
            val allDerivationSteps = mutableListOf<DerivationStep>()
            val queriesExecuted = mutableListOf<String>()

            for (pattern in patterns) {
                val terms = pattern.args.map { parseTerm(it) }
                val queryAtom = Atom(pattern.predicate, terms, scope = scope)
                val queryStr = "${pattern.predicate}(${pattern.args.joinToString(", ")})"
                queriesExecuted.add(queryStr)

                // Try direct fact lookup first
                val directResults = db.query(queryAtom, tenantId, scope).toList()
                for (fact in directResults) {
                    val factStr = fact.toString()
                    if (factStr !in allMatchedFacts) {
                        allMatchedFacts.add(factStr)
                        allDerivationSteps.add(DerivationStep(
                            fact = factStr,
                            type = "fact_match"
                        ))
                    }
                }

                // Try backward chaining with proof
                val proofResults = db.inferWithProof(queryAtom, tenantId).toList()
                for (proofTree in proofResults) {
                    val resultStr = proofTree.result.toString()
                    if (resultStr !in allMatchedFacts) {
                        allMatchedFacts.add(resultStr)
                    }

                    // Extract proof description and rules used
                    val (proofDesc, rules, steps) = extractProofInfo(proofTree.proof)
                    allProofDescriptions.addAll(proofDesc)
                    allRulesUsed.addAll(rules)
                    allDerivationSteps.addAll(steps)
                }
            }

            // Deduplicate
            val uniqueRules = allRulesUsed.distinct()
            val uniqueProofs = allProofDescriptions.distinct()

            // 4. Synthesize answer via LLM
            val synthesis = synthesizer.synthesize(
                question = req.question,
                matchedFacts = allMatchedFacts,
                proofDescriptions = uniqueProofs,
                rulesUsed = uniqueRules
            )

            // Merge LLM derivation with our tracked steps
            val finalDerivation = if (allDerivationSteps.isNotEmpty()) {
                allDerivationSteps.distinctBy { it.fact }
            } else {
                synthesis.derivation.map { DerivationStep(fact = it, type = "llm_reported") }
            }

            val confidence = when {
                allMatchedFacts.isEmpty() -> 0.1f
                allDerivationSteps.any { it.type == "rule_application" } -> 0.9f
                else -> 0.95f
            }

            call.respond(SynthesisResponse(
                answer = synthesis.answer,
                derivation = finalDerivation,
                missingContext = synthesis.missingContext,
                confidence = confidence,
                queriesExecuted = queriesExecuted,
                provider = provider.name,
                model = provider.model
            ))
        } catch (e: ValidationException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", e.message ?: "Validation error"))
        } catch (e: DatabaseNotFoundException) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", e.message ?: "Not found"))
        } catch (e: Exception) {
            call.application.environment.log.error("Synthesis failed: ${e.message}", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("SYNTHESIS_ERROR", e.message ?: "Synthesis failed"))
        }
    }
}

private data class ProofInfo(
    val descriptions: List<String>,
    val rulesUsed: List<String>,
    val steps: List<DerivationStep>
)

private fun extractProofInfo(node: ProofNode): ProofInfo {
    val descriptions = mutableListOf<String>()
    val rulesUsed = mutableListOf<String>()
    val steps = mutableListOf<DerivationStep>()

    when (val step = node.step) {
        is ProofStep.FactMatch -> {
            val factStr = step.fact.toString()
            descriptions.add("FACT: $factStr")
            steps.add(DerivationStep(fact = factStr, type = "fact_match"))
        }
        is ProofStep.RuleApplication -> {
            val ruleStr = step.rule.toString()
            descriptions.add("RULE: $ruleStr")
            rulesUsed.add(ruleStr)
            steps.add(DerivationStep(
                fact = node.goal.toString(),
                type = "rule_application",
                rule = ruleStr
            ))
            for (bodyProof in step.bodyProofs) {
                val sub = extractProofInfo(bodyProof)
                descriptions.addAll(sub.descriptions)
                rulesUsed.addAll(sub.rulesUsed)
                steps.addAll(sub.steps)
            }
        }
    }

    return ProofInfo(descriptions, rulesUsed, steps)
}
