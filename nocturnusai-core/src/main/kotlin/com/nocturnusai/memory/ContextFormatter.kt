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

    /**
     * Templates for the locked predicate vocabulary.
     * Each entry maps a predicate to a function: (subject, object) -> sentence.
     * For 3-arg predicates the third arg is appended naturally.
     */
    private val predicateTemplates: Map<String, (String, String) -> String> = mapOf(
        "IsA" to { s, o -> "$s is $o" },
        "HasRole" to { s, o -> "$s serves as $o" },
        "AffiliatedWith" to { s, o -> "$s is affiliated with $o" },
        "Did" to { s, o -> "$s $o" },
        "Said" to { s, o -> "$s said: \"$o\"" },
        "Threatened" to { s, o -> "$s threatened $o" },
        "Proposed" to { s, o -> "$s proposed $o" },
        "Rejected" to { s, o -> "$s rejected $o" },
        "Demanded" to { s, o -> "$s demanded $o" },
        "Blocked" to { s, o -> "$s blocked $o" },
        "Approved" to { s, o -> "$s approved $o" },
        "CausedBy" to { s, o -> "$s was caused by $o" },
        "Opposes" to { s, o -> "$s opposes $o" },
        "Supports" to { s, o -> "$s supports $o" },
        "Mediates" to { s, o -> "$s is mediated by $o" },
        "Involves" to { s, o -> "$s involves $o" },
        "HasValue" to { s, o -> "The $s is $o" },
        "HasCount" to { s, o -> "There are $o $s" },
        "HasStatus" to { s, o -> "The $s is $o" },
        "HasName" to { s, o -> "$s is named $o" },
        "OccurredOn" to { s, o -> "$s occurred on $o" },
        "Deadline" to { s, o -> "The deadline for $s is $o" },
        "ScheduledFor" to { s, o -> "$s is scheduled for $o" },
        "Duration" to { s, o -> "The duration of $s is $o" },
        "LocatedIn" to { s, o -> "$s is located in $o" },
        "LocatedAt" to { s, o -> "$s is at $o" },
    )

    private fun naturalizeAtom(atom: Atom): String {
        val args = atom.args
        val predicate = atom.predicate

        val subject = if (args.isNotEmpty()) capitalizeEntity(args[0]) else ""
        val obj = if (args.size >= 2) capitalizeEntity(args[1]) else ""
        val third = if (args.size >= 3) capitalizeEntity(args[2]) else ""

        // Try template lookup first
        val template = predicateTemplates[predicate]
        val base = when {
            template != null && args.size >= 2 -> {
                val sentence = template(subject, obj)
                if (third.isNotEmpty()) "$sentence ($third)" else sentence
            }
            template != null && args.size == 1 -> template(subject, "")
            // Fallback: split CamelCase into words
            args.isEmpty() -> splitCamelCase(predicate)
            args.size == 1 -> "$subject is ${splitCamelCase(predicate).lowercase()}"
            args.size == 2 -> "$subject ${splitCamelCase(predicate).lowercase()} $obj"
            else -> {
                val rest = args.drop(1).joinToString(", ") { capitalizeEntity(it) }
                "$subject ${splitCamelCase(predicate).lowercase()}: $rest"
            }
        }

        return when {
            atom.naf -> "It is not known that ${base.replaceFirstChar { it.lowercaseChar() }}"
            !atom.truthVal -> "It is NOT the case that ${base.replaceFirstChar { it.lowercaseChar() }}"
            else -> base
        }
    }

    /** Convert CamelCase or snake_case to space-separated words: "HasStatus" -> "Has status", "user_interested_in" -> "User interested in" */
    private fun splitCamelCase(s: String): String {
        // Handle snake_case first
        if (s.contains('_')) return s.replace('_', ' ').replaceFirstChar { it.uppercaseChar() }
        // Handle CamelCase
        return s.replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1 $2")
            .replaceFirstChar { it.uppercaseChar() }
    }

    private fun naturalizeRule(rule: Rule): String {
        // Build the "then" clause from the head
        val headAtom = rule.head
        val conclusion = naturalizeAtom(headAtom)

        // Build the "if" conditions from the body
        val conditions = rule.body.map { bodyAtom ->
            when {
                bodyAtom.naf -> "it is NOT known that ${naturalizeAtom(bodyAtom).replaceFirstChar { it.lowercaseChar() }}"
                !bodyAtom.truthVal -> "it is NOT the case that ${naturalizeAtom(bodyAtom).replaceFirstChar { it.lowercaseChar() }}"
                else -> naturalizeAtom(bodyAtom).replaceFirstChar { it.lowercaseChar() }
            }
        }

        return if (conditions.isEmpty()) {
            conclusion
        } else {
            "If ${conditions.joinToString(" AND ")}, then ${conclusion.replaceFirstChar { it.lowercaseChar() }}"
        }
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
