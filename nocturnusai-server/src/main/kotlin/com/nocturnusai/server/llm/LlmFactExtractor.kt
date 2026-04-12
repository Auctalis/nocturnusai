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

package com.nocturnusai.server.llm

import com.nocturnusai.extraction.ExtractedAtom
import com.nocturnusai.extraction.ExtractedFact
import com.nocturnusai.extraction.ExtractedRule
import com.nocturnusai.extraction.FactExtractor
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

data class ExtractionResult(
    val facts: List<ExtractedFact>,
    val rules: List<ExtractedRule>
)

class LlmFactExtractor(
    private val provider: LlmProvider,
    private val maxFacts: Int = 20
) : FactExtractor {

    /** Last extraction result including rules. */
    @Volatile
    var lastExtractionResult: ExtractionResult? = null
        private set

    private val logger = LoggerFactory.getLogger(LlmFactExtractor::class.java)

    /** Lenient JSON parser that tolerates common LLM output quirks. */
    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** Strip trailing commas before ] and } that LLMs commonly produce. */
    private fun sanitizeJson(json: String): String =
        json.replace(Regex(",\\s*]"), "]").replace(Regex(",\\s*}"), "}")

    private val systemPrompt = """
You are a thorough fact and rule extraction engine. Extract EVERY distinct piece of information from the text. Each unique claim, event, quote, number, date, person, place, and causal relationship gets its own fact. Do not skip anything — but never state the same information twice.

Predicates (use ONLY these):

IsA, HasRole, AffiliatedWith, Did, Said, Threatened, Proposed, Rejected, Demanded, Blocked, Approved, CausedBy, Opposes, Supports, Mediates, Involves, HasValue, HasCount, HasStatus, HasName, OccurredOn, Deadline, ScheduledFor, Duration, LocatedIn, LocatedAt

Also extract RULES for conditional logic ("if X then Y", threats with conditions, cause-and-effect).

Example input: "Sen. Rosen, age 55, blocked the DHS bill. The vote was 52-48. Democrats demand ICE reforms."
Example output:
{"facts": [
  {"predicate": "HasRole", "args": ["Rosen", "Senator"], "confidence": 1.0},
  {"predicate": "HasValue", "args": ["Rosen", "Age 55"], "confidence": 1.0},
  {"predicate": "Blocked", "args": ["Rosen", "DHS bill"], "confidence": 1.0},
  {"predicate": "HasCount", "args": ["Vote result", "52-48"], "confidence": 1.0},
  {"predicate": "Demanded", "args": ["Democrats", "ICE reforms"], "confidence": 1.0}
], "rules": []}

Rules:
- Use ONLY the predicates listed. Do NOT invent new ones.
- Arguments in Title Case with spaces: "President Trump", "Strait of Hormuz". NOT snake_case.
- Extract ALL details: every name, number, date, quote, weapon, vehicle, mediator, deadline.
- Each fact = 2-3 arguments. Use the 3rd for context when helpful.
- Confidence: 1.0 for explicit, 0.8 for analyst opinion or implication.
- Return ONLY valid JSON, no other text.

{"facts": [...], "rules": [...]}
""".trimIndent()

    override suspend fun extract(text: String, context: String?): List<ExtractedFact> {
        val userMessage = buildString {
            if (context != null) {
                append("Context: $context\n\n")
            }
            append("Text: $text")
        }

        val messages = listOf(
            LlmMessage("system", systemPrompt),
            LlmMessage("user", userMessage)
        )

        val response = try {
            provider.complete(messages)
        } catch (e: Exception) {
            logger.error("LLM extraction failed: ${e.message}")
            throw RuntimeException("LLM extraction failed: ${e.message}", e)
        }

        val result = parseResponseFull(response)
        lastExtractionResult = ExtractionResult(
            facts = deduplicateExtracted(result.facts),
            rules = result.rules
        )
        return lastExtractionResult!!.facts
    }

    /**
     * Post-extraction deduplication: remove facts that share the same predicate
     * and first argument (keeping the one with the most arguments/detail), and
     * collapse facts whose arguments are subsets of each other.
     */
    private fun deduplicateExtracted(facts: List<ExtractedFact>): List<ExtractedFact> {
        if (facts.size <= 1) return facts

        val result = mutableListOf<ExtractedFact>()
        val used = BooleanArray(facts.size)

        // Sort by number of args descending so the most detailed version is kept first
        val indexed = facts.withIndex().sortedByDescending { it.value.args.size }

        for ((i, fact) in indexed) {
            if (used[i]) continue
            result.add(fact)
            used[i] = true

            // Mark any less-detailed duplicate as used
            for ((j, other) in indexed) {
                if (used[j] || i == j) continue
                if (isDuplicate(fact, other)) {
                    used[j] = true
                    logger.debug("Dedup: dropping {} (subsumed by {})", other, fact)
                }
            }
        }

        return result
    }

    /** Two facts are duplicates if they share the same predicate and first arg,
     *  or if the shorter fact's args are a subset of the longer one's args. */
    private fun isDuplicate(kept: ExtractedFact, candidate: ExtractedFact): Boolean {
        if (kept.predicate != candidate.predicate) return false
        // Same predicate + same first arg = duplicate
        val kFirst = kept.args.firstOrNull()?.lowercase()
        val cFirst = candidate.args.firstOrNull()?.lowercase()
        if (kFirst != null && kFirst == cFirst) return true
        // Args of candidate are a subset of kept
        val kSet = kept.args.map { it.lowercase() }.toSet()
        val cSet = candidate.args.map { it.lowercase() }.toSet()
        return cSet.all { it in kSet }
    }

    private fun parseResponseFull(response: String): ExtractionResult {
        val rawJson = extractJson(response)
        val jsonStr = sanitizeJson(rawJson)

        val element = try {
            lenientJson.parseToJsonElement(jsonStr)
        } catch (e: Exception) {
            logger.error("Failed to parse LLM response as JSON: $response")
            throw RuntimeException("LLM returned invalid JSON: ${e.message}")
        }

        // Handle both formats: {"facts": [...], "rules": [...]} or bare [...]
        return when (element) {
            is JsonObject -> {
                val factsArray = element["facts"]?.jsonArray ?: JsonArray(emptyList())
                val rulesArray = element["rules"]?.jsonArray ?: JsonArray(emptyList())
                ExtractionResult(
                    facts = parseFactsArray(factsArray),
                    rules = parseRulesArray(rulesArray)
                )
            }
            is JsonArray -> ExtractionResult(facts = parseFactsArray(element), rules = emptyList())
            else -> throw RuntimeException("LLM returned unexpected JSON type")
        }
    }

    private fun parseFactsArray(jsonArray: JsonArray): List<ExtractedFact> {
        val facts = mutableListOf<ExtractedFact>()
        for (element in jsonArray) {
            if (facts.size >= maxFacts) break
            try {
                val obj = element.jsonObject
                val predicate = obj["predicate"]?.jsonPrimitive?.content ?: continue
                val argsElement = obj["args"]?.jsonArray ?: continue
                val args = argsElement.mapNotNull { coerceArgToString(it) }
                val confidence = obj["confidence"]?.jsonPrimitive?.floatOrNull ?: 1.0f
                if (predicate.isBlank() || args.isEmpty() || args.size > 3) continue
                if (confidence < 0f || confidence > 1f) continue
                facts.add(ExtractedFact(predicate, args, confidence.coerceIn(0f, 1f)))
            } catch (e: Exception) {
                logger.warn("Skipping malformed fact: ${e.message}")
            }
        }
        return facts
    }

    /**
     * Coerce a JSON element inside an args array to a plain string.
     * Handles: primitives (string/number/bool), and objects with
     * "name" or "value" keys (which some LLMs produce instead of strings).
     */
    private fun coerceArgToString(element: JsonElement): String? = when (element) {
        is JsonPrimitive -> element.content
        is JsonObject -> element["name"]?.jsonPrimitive?.content
            ?: element["value"]?.jsonPrimitive?.content
            ?: element["content"]?.jsonPrimitive?.content
        else -> null
    }

    private fun parseRulesArray(jsonArray: JsonArray): List<ExtractedRule> {
        val rules = mutableListOf<ExtractedRule>()
        for (element in jsonArray) {
            try {
                val obj = element.jsonObject
                val headObj = obj["head"]?.jsonObject ?: continue
                val bodyArray = obj["body"]?.jsonArray ?: continue

                val head = parseAtom(headObj) ?: continue
                val body = bodyArray.mapNotNull { parseAtom(it.jsonObject) }
                if (body.isEmpty()) continue

                // Collect variables (args starting with ?)
                val allArgs = (listOf(head) + body).flatMap { it.args }
                val vars = allArgs.filter { it.startsWith("?") }.distinct()

                rules.add(ExtractedRule(head, body, vars))
            } catch (e: Exception) {
                logger.warn("Skipping malformed rule: ${e.message}")
            }
        }
        return rules
    }

    private fun parseAtom(obj: JsonObject): ExtractedAtom? {
        val predicate = obj["predicate"]?.jsonPrimitive?.content ?: return null
        val argsArray = obj["args"]?.jsonArray ?: return null
        val args = argsArray.mapNotNull { coerceArgToString(it) }
        if (predicate.isBlank() || args.isEmpty()) return null
        return ExtractedAtom(predicate, args)
    }

    private fun extractJson(text: String): String {
        val trimmed = text.trim()
        // Direct JSON object or array
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return trimmed

        // Try to extract from markdown code block (object or array)
        val codeBlockPattern = Regex("```(?:json)?\\s*\\n?([{\\[].+[}\\]])\\s*\\n?```", RegexOption.DOT_MATCHES_ALL)
        val match = codeBlockPattern.find(trimmed)
        if (match != null) return match.groupValues[1]

        // Last resort: find first { or [ and matching last } or ]
        val objStart = trimmed.indexOf('{')
        val arrStart = trimmed.indexOf('[')
        val start = when {
            objStart >= 0 && arrStart >= 0 -> minOf(objStart, arrStart)
            objStart >= 0 -> objStart
            arrStart >= 0 -> arrStart
            else -> -1
        }
        if (start >= 0) {
            val openChar = trimmed[start]
            val closeChar = if (openChar == '{') '}' else ']'
            val end = trimmed.lastIndexOf(closeChar)
            if (end > start) return trimmed.substring(start, end + 1)
        }

        throw RuntimeException("Could not find JSON in LLM response")
    }
}
