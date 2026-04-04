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

package com.nocturnusai

import com.nocturnusai.core.Atom
import com.nocturnusai.core.ConflictStrategy
import com.nocturnusai.core.Term
import java.io.File
import kotlin.test.*

/**
 * Tests for Feature 1 (Confidence scores on Atoms) and Feature 2 (Configurable conflict resolution).
 */
class ConfidenceAndConflictTest {

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun freshDb(subDir: String): NocturnusAI {
        val dir = File("build/test-cc/$subDir")
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()
        return NocturnusAI(dir)
    }

    private fun freshDbWithStrategy(subDir: String, strategy: ConflictStrategy): NocturnusAI {
        val dir = File("build/test-cc/$subDir")
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()
        return NocturnusAI(dir, defaultConflictStrategy = strategy)
    }

    // ---------------------------------------------------------------------------
    // Feature 1 — Confidence preserved through assert/query cycle
    // ---------------------------------------------------------------------------

    @Test
    fun `confidence is preserved through assert and query`() {
        val db = freshDb("conf-preserved")

        val fact = Atom("knows", listOf(Term.Identifier("alice"), Term.Identifier("bob")), confidence = 0.85)
        db.assertFact(fact)

        val results = db.query(fact).toList()
        assertEquals(1, results.size)
        assertNotNull(results[0].confidence)
        assertEquals(0.85, results[0].confidence!!, 0.001)

        db.close()
    }

    @Test
    fun `confidence null by default`() {
        val db = freshDb("conf-null")

        val fact = Atom("likes", listOf(Term.Identifier("alice"), Term.Identifier("coffee")))
        assertNull(fact.confidence, "Confidence should default to null")

        db.assertFact(fact)
        val results = db.query(fact).toList()
        assertEquals(1, results.size)
        assertNull(results[0].confidence, "Stored fact should have null confidence")

        db.close()
    }

    @Test
    fun `confidence does not affect identity (equals and hashCode)`() {
        val fact1 = Atom("likes", listOf(Term.Identifier("alice"), Term.Identifier("bob")), confidence = 0.9)
        val fact2 = Atom("likes", listOf(Term.Identifier("alice"), Term.Identifier("bob")), confidence = 0.5)
        val fact3 = Atom("likes", listOf(Term.Identifier("alice"), Term.Identifier("bob")))

        // All three should be equal (confidence is metadata, not identity)
        assertEquals(fact1, fact2, "Facts with different confidence but same predicate/args/truth should be equal")
        assertEquals(fact1, fact3, "Fact with confidence should equal fact without confidence")
        assertEquals(fact1.hashCode(), fact2.hashCode(), "Hash codes should match")
    }

    // ---------------------------------------------------------------------------
    // Feature 1 — Confidence filtering with minConfidence
    // ---------------------------------------------------------------------------

    @Test
    fun `minConfidence filters results in query`() {
        val db = freshDb("conf-filter-query")

        val highConf = Atom("claim", listOf(Term.Identifier("alice"), Term.Identifier("ceo")), confidence = 0.9)
        val lowConf  = Atom("claim", listOf(Term.Identifier("bob"),   Term.Identifier("intern")), confidence = 0.3)
        val noConf   = Atom("claim", listOf(Term.Identifier("carol"), Term.Identifier("engineer")))

        db.assertFact(highConf)
        db.assertFact(lowConf)
        db.assertFact(noConf)

        val pattern = Atom("claim", listOf(Term.Variable("x"), Term.Variable("y")))

        // Without filter — all 3
        val all = db.query(pattern).toList()
        assertEquals(3, all.size)

        // Filter at 0.5 — should include high-confidence and null-confidence (null is not filtered)
        val filtered = db.query(pattern, minConfidence = 0.5).toList()
        assertEquals(2, filtered.size, "Should include high-confidence and null-confidence facts")
        assertTrue(filtered.any { it.args[0] == Term.Identifier("alice") })
        assertTrue(filtered.any { it.args[0] == Term.Identifier("carol") })
        assertFalse(filtered.any { it.args[0] == Term.Identifier("bob") })

        db.close()
    }

    @Test
    fun `minConfidence filters results in infer`() {
        val db = freshDb("conf-filter-infer")

        val highConf = Atom("mortal", listOf(Term.Identifier("socrates")), confidence = 0.95)
        val lowConf  = Atom("mortal", listOf(Term.Identifier("plato")), confidence = 0.2)

        db.assertFact(highConf)
        db.assertFact(lowConf)

        val pattern = Atom("mortal", listOf(Term.Variable("x")))

        val all = db.infer(pattern).toList()
        assertEquals(2, all.size)

        val highOnly = db.infer(pattern, minConfidence = 0.5).toList()
        assertEquals(1, highOnly.size)
        assertEquals(Term.Identifier("socrates"), highOnly[0].args[0])

        db.close()
    }

    @Test
    fun `minConfidence null means no filtering`() {
        val db = freshDb("conf-no-filter")

        db.assertFact(Atom("thing", listOf(Term.Identifier("a")), confidence = 0.1))
        db.assertFact(Atom("thing", listOf(Term.Identifier("b")), confidence = 0.9))

        val pattern = Atom("thing", listOf(Term.Variable("x")))
        val all = db.query(pattern, minConfidence = null).toList()
        assertEquals(2, all.size)

        db.close()
    }

    // ---------------------------------------------------------------------------
    // Feature 2 — Default strategy is REJECT (backward compat)
    // ---------------------------------------------------------------------------

    @Test
    fun `default conflict strategy is REJECT`() {
        val db = freshDb("reject-default")
        assertEquals(ConflictStrategy.REJECT, db.defaultConflictStrategy)

        val pos = Atom("alive", listOf(Term.Identifier("socrates")))
        val neg = Atom("alive", listOf(Term.Identifier("socrates")), truthVal = false)

        db.assertFact(pos)

        assertFailsWith<IllegalArgumentException> {
            db.assertFact(neg)
        }

        db.close()
    }

    @Test
    fun `REJECT strategy throws on contradiction with per-request override`() {
        val db = freshDbWithStrategy("reject-override", ConflictStrategy.NEWEST_WINS)

        val pos = Atom("status", listOf(Term.Identifier("task1")), truthVal = true)
        val neg = Atom("status", listOf(Term.Identifier("task1")), truthVal = false)

        db.assertFact(pos)

        // Per-request REJECT overrides the database default
        assertFailsWith<IllegalArgumentException> {
            db.assertFact(neg, conflictStrategy = ConflictStrategy.REJECT)
        }

        db.close()
    }

    // ---------------------------------------------------------------------------
    // Feature 2 — NEWEST_WINS strategy
    // ---------------------------------------------------------------------------

    @Test
    fun `NEWEST_WINS replaces contradicted fact`() {
        val db = freshDb("newest-wins")

        val pos = Atom("active", listOf(Term.Identifier("server1")), truthVal = true)
        val neg = Atom("active", listOf(Term.Identifier("server1")), truthVal = false)

        db.assertFact(pos)

        // NEWEST_WINS: retract the positive, assert the negative
        db.assertFact(neg, conflictStrategy = ConflictStrategy.NEWEST_WINS)

        val allFacts = db.getAllFacts().toList()
        // The negative fact should exist
        val negFacts = allFacts.filter { it.predicate == "active" && !it.truthVal }
        val posFacts = allFacts.filter { it.predicate == "active" && it.truthVal }
        assertEquals(1, negFacts.size, "Negative fact should be stored")
        assertEquals(0, posFacts.size, "Positive fact should have been retracted")

        db.close()
    }

    @Test
    fun `NEWEST_WINS as database default`() {
        val db = freshDbWithStrategy("newest-wins-default", ConflictStrategy.NEWEST_WINS)

        val pos = Atom("flag", listOf(Term.Identifier("x")), truthVal = true)
        val neg = Atom("flag", listOf(Term.Identifier("x")), truthVal = false)

        db.assertFact(pos)
        db.assertFact(neg) // Should use NEWEST_WINS by default

        val negFacts = db.getAllFacts().filter { it.predicate == "flag" && !it.truthVal }.toList()
        assertEquals(1, negFacts.size, "Negative fact should win")

        db.close()
    }

    // ---------------------------------------------------------------------------
    // Feature 2 — CONFIDENCE strategy
    // ---------------------------------------------------------------------------

    @Test
    fun `CONFIDENCE keeps higher-confidence fact`() {
        val db = freshDb("conf-strategy")

        // Assert a positive fact with high confidence
        val highConf = Atom("healthy", listOf(Term.Identifier("patient1")), truthVal = true, confidence = 0.9)
        db.assertFact(highConf)

        // Try to assert the negation with lower confidence — it should be discarded
        val lowConfNeg = Atom("healthy", listOf(Term.Identifier("patient1")), truthVal = false, confidence = 0.3)
        db.assertFact(lowConfNeg, conflictStrategy = ConflictStrategy.CONFIDENCE)

        val facts = db.getAllFacts().filter { it.predicate == "healthy" }.toList()
        assertEquals(1, facts.size)
        assertTrue(facts[0].truthVal, "High-confidence positive should survive")

        db.close()
    }

    @Test
    fun `CONFIDENCE replaces existing when new has higher confidence`() {
        val db = freshDb("conf-strategy-replace")

        // Low-confidence positive fact
        val lowConf = Atom("valid", listOf(Term.Identifier("record1")), truthVal = true, confidence = 0.2)
        db.assertFact(lowConf)

        // High-confidence negation should win
        val highConf = Atom("valid", listOf(Term.Identifier("record1")), truthVal = false, confidence = 0.95)
        db.assertFact(highConf, conflictStrategy = ConflictStrategy.CONFIDENCE)

        val facts = db.getAllFacts().filter { it.predicate == "valid" }.toList()
        assertEquals(1, facts.size)
        assertFalse(facts[0].truthVal, "High-confidence negative should replace low-confidence positive")

        db.close()
    }

    @Test
    fun `CONFIDENCE new wins on tie (equal confidence)`() {
        val db = freshDb("conf-strategy-tie")

        val first = Atom("active", listOf(Term.Identifier("svc")), truthVal = true, confidence = 0.5)
        db.assertFact(first)

        val second = Atom("active", listOf(Term.Identifier("svc")), truthVal = false, confidence = 0.5)
        db.assertFact(second, conflictStrategy = ConflictStrategy.CONFIDENCE)

        // Tie: new fact should win
        val facts = db.getAllFacts().filter { it.predicate == "active" }.toList()
        assertEquals(1, facts.size)
        assertFalse(facts[0].truthVal, "On tie, new fact should win")

        db.close()
    }

    @Test
    fun `CONFIDENCE new wins when existing has no confidence`() {
        val db = freshDb("conf-strategy-null-existing")

        val noConf = Atom("ready", listOf(Term.Identifier("job1")), truthVal = true, confidence = null)
        db.assertFact(noConf)

        val withConf = Atom("ready", listOf(Term.Identifier("job1")), truthVal = false, confidence = 0.6)
        db.assertFact(withConf, conflictStrategy = ConflictStrategy.CONFIDENCE)

        val facts = db.getAllFacts().filter { it.predicate == "ready" }.toList()
        assertEquals(1, facts.size)
        assertFalse(facts[0].truthVal, "New fact with confidence should replace null-confidence existing")

        db.close()
    }

    // ---------------------------------------------------------------------------
    // Feature 2 — KEEP_BOTH strategy
    // ---------------------------------------------------------------------------

    @Test
    fun `KEEP_BOTH stores contradictory facts`() {
        val db = freshDb("keep-both")

        val pos = Atom("status", listOf(Term.Identifier("item1")), truthVal = true)
        val neg = Atom("status", listOf(Term.Identifier("item1")), truthVal = false)

        db.assertFact(pos)
        db.assertFact(neg, conflictStrategy = ConflictStrategy.KEEP_BOTH)

        val facts = db.getAllFacts().filter { it.predicate == "status" }.toList()
        assertEquals(2, facts.size, "Both contradictory facts should be stored")
        assertTrue(facts.any { it.truthVal })
        assertTrue(facts.any { !it.truthVal })

        db.close()
    }

    // ---------------------------------------------------------------------------
    // Feature 2 — Per-request override of database default
    // ---------------------------------------------------------------------------

    @Test
    fun `per-request strategy overrides database default`() {
        // DB default is REJECT
        val db = freshDb("per-request-override")

        val pos = Atom("enabled", listOf(Term.Identifier("feature1")), truthVal = true)
        val neg = Atom("enabled", listOf(Term.Identifier("feature1")), truthVal = false)

        db.assertFact(pos)

        // Override to NEWEST_WINS for this specific call
        db.assertFact(neg, conflictStrategy = ConflictStrategy.NEWEST_WINS)

        val facts = db.getAllFacts().filter { it.predicate == "enabled" }.toList()
        assertEquals(1, facts.size)
        assertFalse(facts[0].truthVal, "Negative fact should win with NEWEST_WINS override")

        db.close()
    }

    // ---------------------------------------------------------------------------
    // Feature 1 — Confidence flows from LLM extraction (integration simulation)
    // ---------------------------------------------------------------------------

    @Test
    fun `confidence is preserved when constructing atom from extracted fact confidence`() {
        // Simulate what ExtractionRoutes.kt does: fact.confidence.toDouble()
        val extractedConfidence = 0.87f
        val atom = Atom(
            predicate = "IsA",
            args = listOf(Term.Identifier("alice"), Term.Identifier("doctor")),
            confidence = extractedConfidence.toDouble()
        )

        assertEquals(0.87, atom.confidence!!, 0.001)
    }

    @Test
    fun `confidence range 0 to 1 is valid`() {
        // Confidence is just stored; validation is at the API layer, not core.
        val low  = Atom("thing", listOf(Term.Identifier("x")), confidence = 0.0)
        val high = Atom("thing", listOf(Term.Identifier("y")), confidence = 1.0)
        val mid  = Atom("thing", listOf(Term.Identifier("z")), confidence = 0.5)

        assertEquals(0.0, low.confidence)
        assertEquals(1.0, high.confidence)
        assertEquals(0.5, mid.confidence)
    }
}
