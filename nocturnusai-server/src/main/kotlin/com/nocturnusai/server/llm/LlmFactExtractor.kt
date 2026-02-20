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
You are a comprehensive fact extraction engine. Extract ALL structured facts from the given text.
Your goal is COMPLETENESS — capture every entity, relationship, event, attribute, and numeric detail.

Extraction categories (extract ALL that apply):
1. ENTITIES: People, organizations, places, roles, titles
   - IsA(entity, type): IsA(renee_good, mother), IsA(chuck_schumer, senate_minority_leader)
   - HasRole(person, role): HasRole(jacky_rosen, senator)
   - AffiliatedWith(person, org): AffiliatedWith(jacky_rosen, nevada)

2. EVENTS & ACTIONS: What happened, who did what to whom
   - VotedOn(actor, subject): VotedOn(senate, dhs_funding_bill)
   - Blocked(actor, target): Blocked(senate_democrats, dhs_funding_bill)
   - ShotBy(victim, agent): ShotBy(renee_good, ice_agent)

3. RELATIONSHIPS: Connections between entities
   - CausedBy(effect, cause): CausedBy(shutdown, blocked_bill)
   - Demands(actor, demand): Demands(democrats, ice_reforms)
   - Opposes(actor, target): Opposes(democrats, status_quo)

4. ATTRIBUTES & QUANTITIES: Numbers, dates, ages, measurements
   - HasVoteCount(vote, count): HasVoteCount(dhs_vote, 52)
   - RequiresCount(threshold, count): RequiresCount(filibuster_override, 60)
   - HasAge(person, age): HasAge(renee_good, 37)
   - OccurredOn(event, date): OccurredOn(renee_good_shooting, january_7)

5. LOCATIONS & TEMPORAL: Where and when things happened
   - LocatedIn(entity, place): LocatedIn(shooting, minneapolis)
   - ScheduledFor(event, time): ScheduledFor(funding_expiry, midnight_friday)

6. STATEMENTS & POSITIONS: Quotes, demands, accusations
   - Stated(person, position): Stated(schumer, oppose_continuing_resolution)
   - Accused(accuser, accused): Accused(democrats, trump_administration)

Rules:
- Use CamelCase predicates (e.g., ShotBy, HasAge, OccurredOn, LocatedIn)
- Use snake_case for arguments (e.g., renee_good, ice_agent, minneapolis)
- Extract EVERY fact — prefer over-extraction to missing information
- Decompose compound sentences into multiple atomic facts
- Capture numeric values (vote counts, ages, dates, thresholds)
- Capture temporal information (dates, deadlines, durations)
- Each fact should have exactly 2-3 arguments
- Set confidence 1.0 for explicit statements, 0.8-0.9 for strong implications
- Return valid JSON array only, no other text

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

        return parseResponse(response)
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
