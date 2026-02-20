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

import com.nocturnusai.extraction.ExtractedRule
import com.nocturnusai.extraction.FactExtractor
import com.nocturnusai.extraction.RuleExtractor
import com.nocturnusai.server.*
import com.nocturnusai.server.llm.LlmProvider
import com.nocturnusai.server.observability.Metrics
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.extractionRoutes(dbManager: DatabaseManager, extractor: FactExtractor?, ruleExtractor: RuleExtractor?, provider: LlmProvider?) {

    post("/extract") {
        if (extractor == null || provider == null) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                ErrorResponse(
                    "LLM_NOT_CONFIGURED",
                    "No LLM provider configured. Set OPENAI_API_KEY, ANTHROPIC_API_KEY, or GOOGLE_API_KEY environment variable."
                )
            )
            return@post
        }

        if (!ServerConfig.extractionEnabled) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                ErrorResponse(
                    "EXTRACTION_DISABLED",
                    "Extraction is disabled. Set EXTRACTION_ENABLED=true to enable."
                )
            )
            return@post
        }

        try {
            val (db, tenantId) = call.getContext(dbManager)
            val req = call.receive<ExtractionRequest>()

            if (req.text.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", "Text must not be blank"))
                return@post
            }

            val llmSample = Metrics.llmCallTimer()
            val facts = try {
                val result = extractor.extract(req.text, req.context)
                Metrics.llmCallCompleted(llmSample, provider.name, "extract", "success")
                Metrics.llmFactsExtracted(result.size)
                Metrics.llmTokensUsed(provider.name, "extract", req.text.length / 4) // rough estimate
                result
            } catch (e: Exception) {
                Metrics.llmCallCompleted(llmSample, provider.name, "extract", "error")
                Metrics.errorOccurred("/extract", "llm_error")
                throw e
            }
            var asserted = false

            if (req.assert && facts.isNotEmpty()) {
                for (fact in facts) {
                    val terms = fact.args.map { com.nocturnusai.core.Term.Identifier(it) }
                    val atom = com.nocturnusai.core.Atom(fact.predicate, terms, scope = req.scope)
                    try {
                        db.assertFact(atom, tenantId)
                    } catch (e: Exception) {
                        call.application.environment.log.warn("Failed to assert extracted fact $fact: ${e.message}")
                    }
                }
                asserted = true
            }

            // Pass 2: Rule extraction
            val extractedRules = if (req.rules && ruleExtractor != null && facts.isNotEmpty()) {
                try {
                    val rules = ruleExtractor.extractRules(facts, req.text)
                    if (req.assert && rules.isNotEmpty()) {
                        for (rule in rules) {
                            try {
                                val coreRule = convertExtractedRule(rule, req.scope)
                                db.assertRule(coreRule, tenantId, req.scope)
                            } catch (e: Exception) {
                                call.application.environment.log.warn("Failed to assert extracted rule: ${e.message}")
                            }
                        }
                    }
                    rules
                } catch (e: Exception) {
                    call.application.environment.log.warn("Rule extraction failed: ${e.message}")
                    emptyList()
                }
            } else {
                emptyList()
            }

            call.respond(ExtractionResponse(
                facts = facts.map { ExtractedFactDto(it.predicate, it.args, it.confidence) },
                rules = extractedRules.map { toRuleDto(it) },
                asserted = asserted,
                provider = provider.name,
                model = provider.model
            ))
        } catch (e: ValidationException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", e.message ?: "Validation error"))
        } catch (e: DatabaseNotFoundException) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", e.message ?: "Not found"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("EXTRACTION_ERROR", e.message ?: "Extraction failed"))
        }
    }

    post("/extract/batch") {
        if (extractor == null || provider == null) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                ErrorResponse(
                    "LLM_NOT_CONFIGURED",
                    "No LLM provider configured. Set OPENAI_API_KEY, ANTHROPIC_API_KEY, or GOOGLE_API_KEY environment variable."
                )
            )
            return@post
        }

        if (!ServerConfig.extractionEnabled) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                ErrorResponse(
                    "EXTRACTION_DISABLED",
                    "Extraction is disabled. Set EXTRACTION_ENABLED=true to enable."
                )
            )
            return@post
        }

        try {
            val (db, tenantId) = call.getContext(dbManager)
            val req = call.receive<BatchExtractionRequest>()

            if (req.texts.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", "Texts list must not be empty"))
                return@post
            }

            val results = mutableListOf<BatchExtractionResult>()

            for (text in req.texts) {
                if (text.isBlank()) {
                    results.add(BatchExtractionResult(text = text, facts = emptyList(), asserted = false))
                    continue
                }

                val facts = try {
                    extractor.extract(text, req.context)
                } catch (e: Exception) {
                    call.application.environment.log.warn("Batch extraction failed for text: ${e.message}")
                    results.add(BatchExtractionResult(text = text, facts = emptyList(), asserted = false))
                    continue
                }

                var asserted = false
                if (req.assert && facts.isNotEmpty()) {
                    for (fact in facts) {
                        val terms = fact.args.map { com.nocturnusai.core.Term.Identifier(it) }
                        val atom = com.nocturnusai.core.Atom(fact.predicate, terms, scope = req.scope)
                        try {
                            db.assertFact(atom, tenantId)
                        } catch (e: Exception) {
                            call.application.environment.log.warn("Failed to assert extracted fact $fact: ${e.message}")
                        }
                    }
                    asserted = true
                }

                // Pass 2: Rule extraction for batch
                val extractedRules = if (req.rules && ruleExtractor != null && facts.isNotEmpty()) {
                    try {
                        val rules = ruleExtractor.extractRules(facts, text)
                        if (req.assert && rules.isNotEmpty()) {
                            for (rule in rules) {
                                try {
                                    val coreRule = convertExtractedRule(rule, req.scope)
                                    db.assertRule(coreRule, tenantId, req.scope)
                                } catch (e: Exception) {
                                    call.application.environment.log.warn("Failed to assert extracted rule: ${e.message}")
                                }
                            }
                        }
                        rules
                    } catch (e: Exception) {
                        call.application.environment.log.warn("Batch rule extraction failed: ${e.message}")
                        emptyList()
                    }
                } else {
                    emptyList()
                }

                results.add(BatchExtractionResult(
                    text = text,
                    facts = facts.map { ExtractedFactDto(it.predicate, it.args, it.confidence) },
                    rules = extractedRules.map { toRuleDto(it) },
                    asserted = asserted
                ))
            }

            call.respond(BatchExtractionResponse(
                results = results,
                provider = provider.name,
                model = provider.model
            ))
        } catch (e: ValidationException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", e.message ?: "Validation error"))
        } catch (e: DatabaseNotFoundException) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", e.message ?: "Not found"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("EXTRACTION_ERROR", e.message ?: "Batch extraction failed"))
        }
    }
}

private fun convertExtractedRule(extracted: ExtractedRule, scope: String?): com.nocturnusai.core.Rule {
    val variables = extracted.variables.map { com.nocturnusai.core.Term.Variable(it) }
    val headTerms = extracted.head.args.map { parseTerm(it) }
    val head = com.nocturnusai.core.Atom(extracted.head.predicate, headTerms, truthVal = !extracted.head.negated, scope = scope)
    val body = extracted.body.map { atom ->
        val bodyTerms = atom.args.map { parseTerm(it) }
        com.nocturnusai.core.Atom(atom.predicate, bodyTerms, truthVal = !atom.negated, scope = scope)
    }
    return com.nocturnusai.core.Rule(variables, head, body, scope = scope)
}

private fun toRuleDto(rule: ExtractedRule): ExtractedRuleDto {
    return ExtractedRuleDto(
        head = ExtractedAtomDto(rule.head.predicate, rule.head.args, rule.head.negated),
        body = rule.body.map { ExtractedAtomDto(it.predicate, it.args, it.negated) },
        variables = rule.variables,
        confidence = rule.confidence,
        templateType = rule.templateType
    )
}
