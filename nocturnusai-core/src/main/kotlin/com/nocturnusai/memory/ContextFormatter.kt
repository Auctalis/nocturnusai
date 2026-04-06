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

package com.nocturnusai.memory

import com.nocturnusai.core.Atom
import com.nocturnusai.core.Rule
import com.nocturnusai.core.Term
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Output format for context window rendering.
 */
enum class ContextFormat {
    /** Current format: [salience=0.923] likes(alice, bob) */
    PREDICATE,
    /** LLM-optimized: grouped by entity, natural language */
    NATURAL,
    /** Grouped with metadata, good for reasoning LLMs */
    STRUCTURED
}

/**
 * ContextFormatter — formats a ContextWindow (facts) plus optional rules
 * into LLM-optimized text in three modes: PREDICATE, NATURAL, STRUCTURED.
 */
object ContextFormatter {

    private val isoFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC)

    fun format(
        window: ContextWindow,
        rules: List<Rule> = emptyList(),
        format: ContextFormat = ContextFormat.PREDICATE
    ): String {
        return when (format) {
            ContextFormat.PREDICATE -> formatPredicate(window, rules)
            ContextFormat.NATURAL -> formatNatural(window, rules)
            ContextFormat.STRUCTURED -> formatStructured(window, rules)
        }
    }

    // ── PREDICATE format ────────────────────────────────────────────────

    private fun formatPredicate(window: ContextWindow, rules: List<Rule>): String {
        if (window.facts.isEmpty() && rules.isEmpty()) {
            return "Context Window (0/0 facts):\nNo facts available."
        }

        val sb = StringBuilder()
        sb.appendLine("Context Window (${window.facts.size}/${window.totalAvailable} facts):")
        sb.appendLine("Predicates: {${window.predicateDistribution.entries.joinToString(", ") { "${it.key}=${it.value}" }}}")
        sb.appendLine()

        for (scored in window.facts) {
            val salience = "%.3f".format(scored.salience)
            sb.appendLine("  [salience=$salience] ${formatAtomPredicate(scored.atom)}")
        }

        if (rules.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Rules (${rules.size}):")
            for (rule in rules) {
                sb.appendLine("  ${formatRulePredicate(rule)}")
            }
        }

        return sb.toString().trimEnd()
    }

    private fun formatAtomPredicate(atom: Atom): String {
        val prefix = when {
            atom.naf -> "NAF "
            !atom.truthVal -> "NOT "
            else -> ""
        }
        val args = atom.args.joinToString(", ") { termToString(it) }
        return "$prefix${atom.predicate}($args)"
    }

    private fun formatRulePredicate(rule: Rule): String {
        val head = formatAtomPredicate(rule.head)
        val body = rule.body.joinToString(", ") { bodyAtom ->
            when {
                bodyAtom.naf -> "NOT ${bodyAtom.predicate}(${bodyAtom.args.joinToString(", ") { termToString(it) }})"
                !bodyAtom.truthVal -> "NOT ${bodyAtom.predicate}(${bodyAtom.args.joinToString(", ") { termToString(it) }})"
                else -> "${bodyAtom.predicate}(${bodyAtom.args.joinToString(", ") { termToString(it) }})"
            }
        }
        return "$head :- $body"
    }

    // ── NATURAL format ──────────────────────────────────────────────────

    private fun formatNatural(window: ContextWindow, rules: List<Rule>): String {
        if (window.facts.isEmpty() && rules.isEmpty()) {
            return "## Current Knowledge\nNo facts available."
        }

        val sb = StringBuilder()
        sb.appendLine("## Current Knowledge (${window.facts.size} of ${window.totalAvailable} facts, most relevant first)")
        sb.appendLine()

        // Separate scoped and unscoped facts
        val unscopedFacts = window.facts.filter { it.atom.scope == null }
        val scopedGroups = window.facts.filter { it.atom.scope != null }
            .groupBy { it.atom.scope!! }

        // Group unscoped facts by subject entity
        if (unscopedFacts.isNotEmpty()) {
            appendNaturalFactGroups(sb, unscopedFacts)
        }

        // Scoped facts under separate headings
        for ((scope, facts) in scopedGroups) {
            sb.appendLine()
            sb.appendLine("### Hypothetical (scope: $scope)")
            sb.appendLine()
            appendNaturalFactGroups(sb, facts)
        }

        // Rules section
        if (rules.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("## Reasoning Rules (${rules.size} available)")
            for (rule in rules) {
                sb.appendLine("- ${naturalizeRule(rule)}")
            }
        }

        return sb.toString().trimEnd()
    }

    private fun appendNaturalFactGroups(sb: StringBuilder, facts: List<ScoredAtom>) {
        // Group by first argument (subject entity). If only one arg, group by predicate.
        val grouped = facts.groupBy { scored ->
            if (scored.atom.args.size == 1) {
                scored.atom.predicate
            } else if (scored.atom.args.isNotEmpty()) {
                termToString(scored.atom.args[0])
            } else {
                scored.atom.predicate
            }
        }

        for ((key, groupFacts) in grouped) {
            // Use capitalized entity name for multi-arg, predicate for single-arg
            val headerName = if (groupFacts.first().atom.args.size == 1) {
                humanizePredicate(key)
                    .replaceFirstChar { it.uppercaseChar() }
            } else {
                capitalizeEntity(groupFacts.first().atom.args[0])
            }
            sb.appendLine("### $headerName")
            for (scored in groupFacts) {
                val line = buildNaturalLine(scored)
                sb.appendLine("- $line")
            }
            sb.appendLine()
        }
    }

    private fun buildNaturalLine(scored: ScoredAtom): String {
        val sb = StringBuilder()
        sb.append(naturalizeAtom(scored.atom))

        // Confidence band
        val band = confidenceBand(scored.atom.confidence)
        if (band != null) {
            sb.append(" ($band confidence)")
        }

        // Temporal info
        val until = humanizeDate(scored.atom.validUntil)
        if (until != null) {
            sb.append(" (valid until $until)")
        }

        return sb.toString()
    }

    // ── STRUCTURED format ───────────────────────────────────────────────

    private fun formatStructured(window: ContextWindow, rules: List<Rule>): String {
        if (window.facts.isEmpty() && rules.isEmpty()) {
            return "<knowledge total=\"0\" returned=\"0\">\nNo facts available.\n</knowledge>"
        }

        val sb = StringBuilder()
        val generatedDate = humanizeDate(window.generatedAt) ?: "unknown"
        sb.appendLine("<knowledge total=\"${window.totalAvailable}\" returned=\"${window.facts.size}\" generated=\"$generatedDate\">")
        sb.appendLine()

        // Group by predicate
        val byPredicate = window.facts.groupBy { it.atom.predicate }
        for ((predicate, facts) in byPredicate) {
            sb.appendLine("## $predicate (${facts.size} facts)")
            for (scored in facts) {
                val parts = mutableListOf<String>()
                parts.add(formatAtomPredicate(scored.atom))
                parts.add("salience: ${"%.2f".format(scored.salience)}")

                val since = humanizeDate(scored.atom.createdAt)
                if (since != null) {
                    parts.add("since: $since")
                }

                val band = confidenceBand(scored.atom.confidence)
                if (band != null) {
                    parts.add("confidence: $band")
                }

                val until = humanizeDate(scored.atom.validUntil)
                if (until != null) {
                    parts.add("valid until: $until")
                }

                sb.appendLine("- ${parts.joinToString(" | ")}")
            }
            sb.appendLine()
        }

        // Rules
        if (rules.isNotEmpty()) {
            sb.appendLine("## Rules (${rules.size})")
            for (rule in rules) {
                val hasNaf = rule.body.any { it.naf }
                val suffix = if (hasNaf) " [uses NAF]" else ""
                sb.appendLine("- ${formatRulePredicate(rule)}$suffix")
            }
            sb.appendLine()
        }

        sb.append("</knowledge>")
        return sb.toString()
    }

    // ── Helper functions ────────────────────────────────────────────────

    private fun confidenceBand(value: Double?): String? {
        if (value == null) return null
        return when {
            value < 0.2 -> "very low"
            value < 0.4 -> "low"
            value < 0.6 -> "moderate"
            value < 0.8 -> "high"
            else -> "very high"
        }
    }

    private fun humanizeDate(epochMillis: Long?): String? {
        if (epochMillis == null) return null
        val instant = Instant.ofEpochMilli(epochMillis)
        return isoFormatter.format(instant)
    }

    private fun humanizePredicate(predicate: String): String {
        return predicate.replace('_', ' ')
    }

    private fun capitalizeEntity(term: Term): String {
        val raw = termToString(term)
        return raw.replace('_', ' ')
            .replaceFirstChar { it.uppercaseChar() }
    }

    private fun naturalizeAtom(atom: Atom): String {
        val args = atom.args
        val predicate = humanizePredicate(atom.predicate)

        val base = when (args.size) {
            0 -> predicate
            1 -> {
                val subject = capitalizeEntity(args[0])
                "$subject is $predicate"
            }
            2 -> {
                val subject = capitalizeEntity(args[0])
                val obj = capitalizeEntity(args[1])
                "$subject $predicate $obj"
            }
            else -> {
                val subject = capitalizeEntity(args[0])
                val rest = args.drop(1).joinToString(", ") { capitalizeEntity(it) }
                "$subject $predicate: $rest"
            }
        }

        return when {
            atom.naf -> "It is not known that ${base.replaceFirstChar { it.lowercaseChar() }}"
            !atom.truthVal -> {
                // Insert NOT after the subject for multi-arg, or "is NOT" for single-arg
                when (args.size) {
                    0 -> "NOT $predicate"
                    1 -> {
                        val subject = capitalizeEntity(args[0])
                        "$subject is NOT $predicate"
                    }
                    2 -> {
                        val subject = capitalizeEntity(args[0])
                        val obj = capitalizeEntity(args[1])
                        "$subject does NOT $predicate $obj"
                    }
                    else -> {
                        val subject = capitalizeEntity(args[0])
                        val rest = args.drop(1).joinToString(", ") { capitalizeEntity(it) }
                        "$subject does NOT $predicate: $rest"
                    }
                }
            }
            else -> base
        }
    }

    private fun naturalizeRule(rule: Rule): String {
        val headPredicate = humanizePredicate(rule.head.predicate)
        val conclusion = when (rule.head.args.size) {
            0 -> headPredicate
            1 -> "it is $headPredicate"
            else -> "it $headPredicate ${rule.head.args.drop(1).joinToString(", ") { termToString(it) }}"
        }

        return "If something is ${rule.body.firstOrNull()?.let { humanizePredicate(it.predicate) } ?: "true"}" +
            if (rule.body.size > 1) {
                " and ${rule.body.drop(1).joinToString(" and ") { bodyAtom ->
                    when {
                        bodyAtom.naf -> "it is not known to be ${humanizePredicate(bodyAtom.predicate)}"
                        !bodyAtom.truthVal -> "it is not ${humanizePredicate(bodyAtom.predicate)}"
                        else -> "it is ${humanizePredicate(bodyAtom.predicate)}"
                    }
                }}"
            } else {
                ""
            } + ", then $conclusion"
    }

    private fun termToString(term: Term): String {
        return when (term) {
            is Term.Identifier -> term.name
            is Term.StringLit -> term.value
            is Term.NumberLit -> term.value.toString()
            is Term.Variable -> "?${term.name}"
        }
    }
}
