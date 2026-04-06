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

import com.nocturnusai.extraction.ExtractedFact
import com.nocturnusai.extraction.FactExtractor
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

class LlmFactExtractor(
    private val provider: LlmProvider,
    private val maxFacts: Int = 20
) : FactExtractor {

    private val logger = LoggerFactory.getLogger(LlmFactExtractor::class.java)

    private val systemPrompt = """
You are a concise fact extraction engine. Extract the MINIMUM set of distinct, non-redundant facts from the given text.
Your goal is REDUCTION — compress the text into the fewest facts that preserve all key information. Never extract two facts that say the same thing.

Use ONLY these predicate categories:

ENTITIES: IsA, HasRole, AffiliatedWith
ACTIONS:  Did, Said, Threatened, Proposed, Rejected, Demanded, Blocked, Approved
RELATIONS: CausedBy, Opposes, Supports, Mediates, Involves
ATTRIBUTES: HasValue, HasCount, HasStatus, HasName
TEMPORAL: OccurredOn, Deadline, ScheduledFor, Duration
LOCATION: LocatedIn, LocatedAt

Rules:
- Use ONLY the predicates listed above. Do NOT invent new predicate names.
- Use snake_case for arguments (e.g., president_trump, strait_of_hormuz)
- Extract the FEWEST facts that capture all distinct information. Merge overlapping details into one fact.
- If the same relationship appears multiple times in the text, extract it ONCE with the most complete version.
- Each fact should have exactly 2-3 arguments.
- Set confidence 1.0 for explicit statements, 0.8-0.9 for implications.
- Return valid JSON array only, no other text.

Return format:
[{"predicate": "...", "args": ["...", "..."], "confidence": 0.0-1.0}]
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

        return deduplicateExtracted(parseResponse(response))
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

    private fun parseResponse(response: String): List<ExtractedFact> {
        // Extract JSON array from response (LLMs sometimes wrap in markdown code blocks)
        val jsonStr = extractJsonArray(response)

        val jsonArray = try {
            Json.parseToJsonElement(jsonStr).jsonArray
        } catch (e: Exception) {
            logger.error("Failed to parse LLM response as JSON array: $response")
            throw RuntimeException("LLM returned invalid JSON: ${e.message}")
        }

        val facts = mutableListOf<ExtractedFact>()
        for (element in jsonArray) {
            if (facts.size >= maxFacts) break
            try {
                val obj = element.jsonObject
                val predicate = obj["predicate"]?.jsonPrimitive?.content ?: continue
                val args = obj["args"]?.jsonArray?.map { it.jsonPrimitive.content } ?: continue
                val confidence = obj["confidence"]?.jsonPrimitive?.floatOrNull ?: 1.0f

                // Validate
                if (predicate.isBlank()) continue
                if (args.isEmpty() || args.size > 3) continue
                if (confidence < 0f || confidence > 1f) continue

                facts.add(ExtractedFact(predicate, args, confidence.coerceIn(0f, 1f)))
            } catch (e: Exception) {
                logger.warn("Skipping malformed fact in LLM response: ${e.message}")
            }
        }

        return facts
    }

    private fun extractJsonArray(text: String): String {
        // Try to find JSON array directly
        val trimmed = text.trim()
        if (trimmed.startsWith("[")) return trimmed

        // Try to extract from markdown code block
        val codeBlockPattern = Regex("```(?:json)?\\s*\\n?(\\[.+])\\s*\\n?```", RegexOption.DOT_MATCHES_ALL)
        val match = codeBlockPattern.find(trimmed)
        if (match != null) return match.groupValues[1]

        // Last resort: find first [ and last ]
        val start = trimmed.indexOf('[')
        val end = trimmed.lastIndexOf(']')
        if (start >= 0 && end > start) return trimmed.substring(start, end + 1)

        throw RuntimeException("Could not find JSON array in LLM response")
    }
}
