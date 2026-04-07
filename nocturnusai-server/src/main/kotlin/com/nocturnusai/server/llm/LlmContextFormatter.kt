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

import com.nocturnusai.core.Atom
import com.nocturnusai.core.Rule
import com.nocturnusai.memory.ScoredAtom
import org.slf4j.LoggerFactory

/**
 * Sends extracted facts + rules through the LLM to produce a clean
 * natural-language briefing. This replaces the template-based formatter
 * when an LLM provider is available and format="natural".
 */
class LlmContextFormatter(private val provider: LlmProvider) {

    private val logger = LoggerFactory.getLogger(LlmContextFormatter::class.java)

    private val systemPrompt = """
You are a concise briefing writer. You receive structured facts and rules extracted from a document. Rewrite them as a short, clear, natural-language briefing that an AI agent can use as context for its next response.

Rules:
- Write plain English sentences grouped under topic headings (use ## for headings).
- Every fact must appear in the output — do not drop information.
- If rules are present, add a "## Reasoning Rules" section and state each rule as an "If... then..." sentence.
- Do NOT add information that is not in the facts.
- Do NOT use predicate notation — write normal sentences.
- Keep it concise. One sentence per fact unless two facts naturally combine.
- Use markdown formatting.
""".trimIndent()

    suspend fun format(facts: List<ScoredAtom>, rules: List<Rule> = emptyList()): String {
        if (facts.isEmpty() && rules.isEmpty()) return "No facts available."

        val factsBlock = buildString {
            appendLine("FACTS:")
            for (scored in facts) {
                val atom = scored.atom
                val args = atom.args.joinToString(", ") { it.toString() }
                val neg = if (!atom.truthVal) " [NEGATED]" else ""
                appendLine("  ${atom.predicate}($args)$neg")
            }
        }

        val rulesBlock = if (rules.isNotEmpty()) {
            buildString {
                appendLine("RULES:")
                for (rule in rules) {
                    appendLine("  ${rule}")
                }
            }
        } else ""

        val userMessage = "$factsBlock\n$rulesBlock\nRewrite as a natural-language briefing:"

        return try {
            provider.complete(listOf(
                LlmMessage("system", systemPrompt),
                LlmMessage("user", userMessage)
            ))
        } catch (e: Exception) {
            logger.warn("LLM formatting failed, falling back to structured output: ${e.message}")
            // Fallback: just list facts as simple sentences
            buildString {
                appendLine("## Context")
                for (scored in facts) {
                    val args = scored.atom.args.joinToString(", ") { it.toString() }
                    appendLine("- ${scored.atom.predicate}($args)")
                }
                if (rules.isNotEmpty()) {
                    appendLine()
                    appendLine("## Rules")
                    for (rule in rules) {
                        appendLine("- $rule")
                    }
                }
            }.trimEnd()
        }
    }
}
