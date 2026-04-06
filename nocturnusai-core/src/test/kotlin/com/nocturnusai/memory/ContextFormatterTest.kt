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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContextFormatterTest {

    // ── Test helpers ────────────────────────────────────────────────────

    private fun id(name: String) = Term.Identifier(name)
    private fun v(name: String) = Term.Variable(name)

    private fun atom(
        predicate: String,
        vararg args: Term,
        truthVal: Boolean = true,
        scope: String? = null,
        createdAt: Long? = null,
        validUntil: Long? = null,
        confidence: Double? = null,
        naf: Boolean = false
    ) = Atom(
        predicate = predicate,
        args = args.toList(),
        truthVal = truthVal,
        scope = scope,
        createdAt = createdAt,
        validUntil = validUntil,
        confidence = confidence,
        naf = naf
    )

    private fun scored(atom: Atom, salience: Double) = ScoredAtom(atom, salience)

    private fun window(
        facts: List<ScoredAtom>,
        totalAvailable: Int = 247,
        windowSize: Int = facts.size,
        predicateDistribution: Map<String, Int> = facts.groupBy { it.atom.predicate }
            .mapValues { it.value.size },
        generatedAt: Long = 1743854400000L // 2025-04-05T12:00:00Z
    ) = ContextWindow(facts, totalAvailable, windowSize, predicateDistribution, generatedAt)

    private fun rule(
        head: Atom,
        body: List<Atom>,
        variables: List<Term.Variable> = emptyList(),
        confidence: Double? = null
    ) = Rule(variables, head, body, confidence = confidence)

    // ── 1. PREDICATE format backward compat ─────────────────────────────

    @Test
    fun testPredicateFormatBasic() {
        val facts = listOf(
            scored(atom("likes", id("alice"), id("bob")), 0.923),
            scored(atom("role", id("alice"), id("engineer")), 0.871)
        )
        val w = window(facts, totalAvailable = 247)

        val result = ContextFormatter.format(w, format = ContextFormat.PREDICATE)

        assertTrue(result.contains("Context Window (2/247 facts):"))
        assertTrue(result.contains("[salience=0.923] likes(alice, bob)"))
        assertTrue(result.contains("[salience=0.871] role(alice, engineer)"))
        assertTrue(result.contains("Predicates:"))
    }

    // ── 2. PREDICATE format with rules ──────────────────────────────────

    @Test
    fun testPredicateFormatWithRules() {
        val facts = listOf(
            scored(atom("likes", id("alice"), id("bob")), 0.9)
        )
        val rules = listOf(
            rule(
                head = atom("mortal", v("x")),
                body = listOf(atom("human", v("x"))),
                variables = listOf(v("x"))
            ),
            rule(
                head = atom("can_fly", v("x")),
                body = listOf(
                    atom("bird", v("x")),
                    atom("penguin", v("x"), naf = true)
                ),
                variables = listOf(v("x"))
            )
        )
        val w = window(facts)

        val result = ContextFormatter.format(w, rules, ContextFormat.PREDICATE)

        assertTrue(result.contains("Rules (2):"))
        assertTrue(result.contains("mortal(?x) :- human(?x)"))
        assertTrue(result.contains("can_fly(?x) :- bird(?x), NOT penguin(?x)"))
    }

    // ── 3. NATURAL format groups by first argument ──────────────────────

    @Test
    fun testNaturalFormatGroupsByFirstArg() {
        val facts = listOf(
            scored(atom("likes", id("alice"), id("bob")), 0.9),
            scored(atom("role", id("alice"), id("engineer")), 0.8),
            scored(atom("likes", id("bob"), id("cats")), 0.7)
        )
        val w = window(facts)

        val result = ContextFormatter.format(w, format = ContextFormat.NATURAL)

        assertTrue(result.contains("### Alice"))
        assertTrue(result.contains("### Bob"))
        assertTrue(result.contains("- Alice likes Bob"))
        assertTrue(result.contains("- Alice role Engineer"))
        assertTrue(result.contains("- Bob likes Cats"))
    }

    // ── 4. NATURAL format handles negation and NAF ──────────────────────

    @Test
    fun testNaturalFormatNegation() {
        val facts = listOf(
            scored(atom("located", id("alice"), id("nyc"), truthVal = false), 0.8),
            scored(atom("can_fly", id("tweety"), naf = true), 0.7)
        )
        val w = window(facts)

        val result = ContextFormatter.format(w, format = ContextFormat.NATURAL)

        assertTrue(result.contains("It is NOT the case that") && result.contains("located"),
            "Expected explicit negation, got:\n$result")
        assertTrue(result.contains("It is not known that"),
            "Expected NAF phrasing, got:\n$result")
    }

    // ── 5. NATURAL format confidence bands ──────────────────────────────

    @Test
    fun testNaturalFormatConfidenceBands() {
        val facts = listOf(
            scored(atom("likes", id("alice"), id("bob"), confidence = 0.9), 0.9),
            scored(atom("knows", id("alice"), id("carol"), confidence = 0.5), 0.8)
        )
        val w = window(facts)

        val result = ContextFormatter.format(w, format = ContextFormat.NATURAL)

        assertTrue(result.contains("(very high confidence)"), "Expected very high for 0.9, got:\n$result")
        assertTrue(result.contains("(moderate confidence)"), "Expected moderate for 0.5, got:\n$result")
    }

    // ── 6. NATURAL format temporal info ─────────────────────────────────

    @Test
    fun testNaturalFormatTemporalInfo() {
        // 2026-05-01T00:00:00Z in epoch millis
        val may1_2026 = 1777593600000L
        val facts = listOf(
            scored(atom("location", id("alice"), id("nyc"), validUntil = may1_2026), 0.9)
        )
        val w = window(facts)

        val result = ContextFormatter.format(w, format = ContextFormat.NATURAL)

        assertTrue(result.contains("(valid until 2026-05-01T00:00:00Z)"), "Expected temporal info, got:\n$result")
    }

    // ── 7. NATURAL format scoped facts ──────────────────────────────────

    @Test
    fun testNaturalFormatScopedFacts() {
        val facts = listOf(
            scored(atom("likes", id("alice"), id("bob")), 0.9),
            scored(atom("uses", id("alice"), id("rust"), scope = "what-if-rust"), 0.7)
        )
        val w = window(facts)

        val result = ContextFormatter.format(w, format = ContextFormat.NATURAL)

        assertTrue(result.contains("### Hypothetical (scope: what-if-rust)"), "Expected scope heading, got:\n$result")
        assertTrue(result.contains("- Alice uses Rust"))
    }

    // ── 8. NATURAL format rules as natural language ─────────────────────

    @Test
    fun testNaturalFormatRulesNaturalLanguage() {
        val rules = listOf(
            rule(
                head = atom("mortal", v("x")),
                body = listOf(atom("human", v("x"))),
                variables = listOf(v("x"))
            ),
            rule(
                head = atom("can_fly", v("x")),
                body = listOf(
                    atom("bird", v("x")),
                    atom("penguin", v("x"), naf = true)
                ),
                variables = listOf(v("x"))
            )
        )
        val w = window(emptyList(), totalAvailable = 0)

        val result = ContextFormatter.format(w, rules, ContextFormat.NATURAL)

        assertTrue(result.contains("## Reasoning Rules (2 available)"), "Expected rules heading, got:\n$result")
        assertTrue(result.contains("human") && result.contains("mortal"),
            "Expected mortal/human rule, got:\n$result")
        assertTrue(result.contains("If "), "Expected If... then... rule format, got:\n$result")
        assertTrue(result.contains("NOT known"),
            "Expected NAF phrasing in rule, got:\n$result")
    }

    // ── 9. STRUCTURED format groups by predicate ────────────────────────

    @Test
    fun testStructuredFormatGroupsByPredicate() {
        val facts = listOf(
            scored(atom("likes", id("alice"), id("bob")), 0.92),
            scored(atom("likes", id("alice"), id("cats")), 0.81),
            scored(atom("role", id("alice"), id("engineer")), 0.87)
        )
        val w = window(facts)

        val result = ContextFormatter.format(w, format = ContextFormat.STRUCTURED)

        assertTrue(result.contains("<knowledge"))
        assertTrue(result.contains("total=\"247\""))
        assertTrue(result.contains("returned=\"3\""))
        assertTrue(result.contains("## likes (2 facts)"))
        assertTrue(result.contains("## role (1 facts)"))
        assertTrue(result.contains("salience: 0.92"))
        assertTrue(result.contains("</knowledge>"))
    }

    // ── 10. STRUCTURED format metadata ──────────────────────────────────

    @Test
    fun testStructuredFormatMetadata() {
        val created = 1711929600000L // 2024-04-01T00:00:00Z
        val facts = listOf(
            scored(atom("role", id("alice"), id("engineer"),
                createdAt = created, confidence = 0.85), 0.87)
        )
        val w = window(facts)

        val result = ContextFormatter.format(w, format = ContextFormat.STRUCTURED)

        assertTrue(result.contains("since: 2024-04-01T00:00:00Z"), "Expected ISO date, got:\n$result")
        assertTrue(result.contains("confidence: very high"), "Expected confidence band, got:\n$result")
    }

    @Test
    fun testStructuredFormatRulesWithNaf() {
        val rules = listOf(
            rule(
                head = atom("can_fly", v("x")),
                body = listOf(
                    atom("bird", v("x")),
                    atom("penguin", v("x"), naf = true)
                ),
                variables = listOf(v("x"))
            )
        )
        val w = window(emptyList(), totalAvailable = 0)

        val result = ContextFormatter.format(w, rules, ContextFormat.STRUCTURED)

        assertTrue(result.contains("[uses NAF]"), "Expected NAF annotation, got:\n$result")
    }

    // ── 11. Empty window for each format ────────────────────────────────

    @Test
    fun testEmptyWindowPredicateFormat() {
        val w = window(emptyList(), totalAvailable = 0)
        val result = ContextFormatter.format(w, format = ContextFormat.PREDICATE)
        assertTrue(result.contains("No facts available"), "Expected empty message, got:\n$result")
    }

    @Test
    fun testEmptyWindowNaturalFormat() {
        val w = window(emptyList(), totalAvailable = 0)
        val result = ContextFormatter.format(w, format = ContextFormat.NATURAL)
        assertTrue(result.contains("No facts available"), "Expected empty message, got:\n$result")
    }

    @Test
    fun testEmptyWindowStructuredFormat() {
        val w = window(emptyList(), totalAvailable = 0)
        val result = ContextFormatter.format(w, format = ContextFormat.STRUCTURED)
        assertTrue(result.contains("No facts available"), "Expected empty message, got:\n$result")
        assertTrue(result.contains("<knowledge"))
    }

    // ── 12. confidenceBand boundary tests ───────────────────────────────

    @Test
    fun testConfidenceBandBoundaries() {
        // We test via the NATURAL format output to exercise the private helper
        // Test null → no confidence shown
        val noConf = listOf(scored(atom("likes", id("a"), id("b")), 0.5))
        val resultNoConf = ContextFormatter.format(window(noConf), format = ContextFormat.NATURAL)
        assertTrue(!resultNoConf.contains("confidence"), "Null confidence should not show band")

        // Test 0.0 → very low
        val veryLow = listOf(scored(atom("likes", id("a"), id("b"), confidence = 0.1), 0.5))
        val resultVeryLow = ContextFormatter.format(window(veryLow), format = ContextFormat.NATURAL)
        assertTrue(resultVeryLow.contains("very low confidence"), "0.1 should be very low")

        // Test 0.2 → low (boundary)
        val low = listOf(scored(atom("likes", id("a"), id("b"), confidence = 0.2), 0.5))
        val resultLow = ContextFormatter.format(window(low), format = ContextFormat.NATURAL)
        assertTrue(resultLow.contains("low confidence"), "0.2 should be low")
        assertTrue(!resultLow.contains("very low"), "0.2 should not be very low")

        // Test 0.4 → moderate (boundary)
        val moderate = listOf(scored(atom("likes", id("a"), id("b"), confidence = 0.4), 0.5))
        val resultMod = ContextFormatter.format(window(moderate), format = ContextFormat.NATURAL)
        assertTrue(resultMod.contains("moderate confidence"), "0.4 should be moderate")

        // Test 0.6 → high (boundary)
        val high = listOf(scored(atom("likes", id("a"), id("b"), confidence = 0.6), 0.5))
        val resultHigh = ContextFormatter.format(window(high), format = ContextFormat.NATURAL)
        assertTrue(resultHigh.contains("high confidence"), "0.6 should be high")
        assertTrue(!resultHigh.contains("very high"), "0.6 should not be very high")

        // Test 0.8 → very high (boundary)
        val veryHigh = listOf(scored(atom("likes", id("a"), id("b"), confidence = 0.8), 0.5))
        val resultVeryHigh = ContextFormatter.format(window(veryHigh), format = ContextFormat.NATURAL)
        assertTrue(resultVeryHigh.contains("very high confidence"), "0.8 should be very high")

        // Test 1.0 → very high
        val max = listOf(scored(atom("likes", id("a"), id("b"), confidence = 1.0), 0.5))
        val resultMax = ContextFormatter.format(window(max), format = ContextFormat.NATURAL)
        assertTrue(resultMax.contains("very high confidence"), "1.0 should be very high")
    }

    // ── 13. humanizeDate converts epoch millis ──────────────────────────

    @Test
    fun testHumanizeDateViaStructuredFormat() {
        // 2025-04-05T12:00:00Z = 1743854400000L
        val facts = listOf(
            scored(atom("role", id("alice"), id("dev"), createdAt = 1743854400000L), 0.5)
        )
        val w = window(facts)

        val result = ContextFormatter.format(w, format = ContextFormat.STRUCTURED)

        assertTrue(result.contains("since: 2025-04-05T12:00:00Z") || result.contains("since: 2025-"),
            "Expected ISO-8601 date in structured output, got:\n$result")
    }

    @Test
    fun testHumanizeDateNullHandling() {
        // Atom with no createdAt → no "since:" in structured output
        val facts = listOf(
            scored(atom("role", id("alice"), id("dev")), 0.5)
        )
        val w = window(facts)

        val result = ContextFormatter.format(w, format = ContextFormat.STRUCTURED)

        assertTrue(!result.contains("since:"), "No createdAt should mean no since field, got:\n$result")
    }

    // ── Additional edge cases ───────────────────────────────────────────

    @Test
    fun testPredicateFormatNegationAndNaf() {
        val facts = listOf(
            scored(atom("located", id("alice"), id("nyc"), truthVal = false), 0.8),
            scored(atom("can_fly", id("tweety"), naf = true), 0.7)
        )
        val w = window(facts)

        val result = ContextFormatter.format(w, format = ContextFormat.PREDICATE)

        assertTrue(result.contains("NOT located(alice, nyc)"), "Expected NOT for negation, got:\n$result")
        assertTrue(result.contains("NAF can_fly(tweety)"), "Expected NAF prefix, got:\n$result")
    }

    @Test
    fun testNaturalFormatSingleArgFacts() {
        val facts = listOf(
            scored(atom("human", id("socrates")), 0.95),
            scored(atom("human", id("aristotle")), 0.90)
        )
        val w = window(facts)

        val result = ContextFormatter.format(w, format = ContextFormat.NATURAL)

        // Single-arg facts group by predicate
        assertTrue(result.contains("### Human"), "Expected predicate grouping header, got:\n$result")
        assertTrue(result.contains("Socrates is human"), "Expected natural phrasing, got:\n$result")
        assertTrue(result.contains("Aristotle is human"), "Expected natural phrasing, got:\n$result")
    }

    @Test
    fun testNaturalFormatUnderscoresReplaced() {
        val facts = listOf(
            scored(atom("user_interested_in", id("alice"), id("functional_programming")), 0.8)
        )
        val w = window(facts)

        val result = ContextFormatter.format(w, format = ContextFormat.NATURAL)

        // CamelCase predicates use templates; snake_case fallback splits on underscore
        assertTrue(result.contains("user interested in") || result.contains("User interested in"),
            "Expected underscores replaced in predicate, got:\n$result")
        assertTrue(result.contains("Functional programming") || result.contains("functional programming"),
            "Expected entity underscore replaced, got:\n$result")
    }

    @Test
    fun testStructuredFormatGeneratedTimestamp() {
        val w = window(
            facts = listOf(scored(atom("likes", id("a"), id("b")), 0.5)),
            generatedAt = 1743854400000L
        )

        val result = ContextFormatter.format(w, format = ContextFormat.STRUCTURED)

        assertTrue(result.contains("generated="), "Expected generated timestamp, got:\n$result")
    }

    @Test
    fun testThreeArgAtomNaturalFormat() {
        val facts = listOf(
            scored(atom("relationship", id("alice"), id("bob"), id("friends")), 0.8)
        )
        val w = window(facts)

        val result = ContextFormatter.format(w, format = ContextFormat.NATURAL)

        assertTrue(result.contains("Alice relationship: Bob, Friends") ||
            result.contains("Alice relationship Bob, Friends"),
            "Expected 3-arg natural format, got:\n$result")
    }
}
