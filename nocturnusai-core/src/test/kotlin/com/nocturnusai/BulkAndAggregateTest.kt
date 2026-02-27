// Copyright (c) 2026 Auctalis LLC. All rights reserved.
//
// Licensed under the Business Source License 1.1 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://github.com/auctalis/nocturnusai/blob/main/LICENSE

package com.nocturnusai

import com.nocturnusai.core.Atom
import com.nocturnusai.core.Term
import com.nocturnusai.storage.AggregateOp
import java.io.File
import java.nio.file.Files
import kotlin.test.*

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun id(name: String) = Term.Identifier(name)
private fun num(value: Double) = Term.NumberLit(value)
private fun variable(name: String) = Term.Variable(name)

/** Create a temporary NocturnusAI instance backed by a fresh temp directory. */
private fun tempEngine(): Pair<NocturnusAI, File> {
    val dir = Files.createTempDirectory("nai-test-").toFile()
    val engine = NocturnusAI(storageDir = dir, isMultiTenant = false)
    return engine to dir
}

// ---------------------------------------------------------------------------
// Aggregation tests via NocturnusAI public API
// ---------------------------------------------------------------------------

class NocturnusAIAggregationTest {

    private lateinit var engine: NocturnusAI
    private lateinit var tmpDir: File

    @BeforeTest
    fun setup() {
        val (e, d) = tempEngine()
        engine = e
        tmpDir = d
    }

    @AfterTest
    fun teardown() {
        engine.close()
        tmpDir.deleteRecursively()
    }

    // COUNT

    @Test
    fun `countFacts returns 0 for empty store`() {
        val pattern = Atom("score", listOf(variable("p"), variable("v")))
        assertEquals(0, engine.countFacts(pattern, "default"))
    }

    @Test
    fun `countFacts counts asserted facts`() {
        engine.assertFact(Atom("score", listOf(id("alice"), num(10.0))), "default")
        engine.assertFact(Atom("score", listOf(id("bob"), num(20.0))), "default")
        val pattern = Atom("score", listOf(variable("p"), variable("v")))
        assertEquals(2, engine.countFacts(pattern, "default"))
    }

    @Test
    fun `countFacts with scope filter only counts matching scope`() {
        engine.assertFact(Atom("score", listOf(id("alice"), num(10.0)), scope = "r1"), "default")
        engine.assertFact(Atom("score", listOf(id("bob"), num(20.0)), scope = "r1"), "default")
        engine.assertFact(Atom("score", listOf(id("carol"), num(30.0)), scope = "r2"), "default")
        val pattern = Atom("score", listOf(variable("p"), variable("v")))
        assertEquals(2, engine.countFacts(pattern, "default", scope = "r1"))
        assertEquals(1, engine.countFacts(pattern, "default", scope = "r2"))
    }

    // SUM / MIN / MAX / AVG

    @Test
    fun `aggregateFacts SUM returns correct total`() {
        engine.assertFact(Atom("score", listOf(id("alice"), num(10.0))), "default")
        engine.assertFact(Atom("score", listOf(id("bob"), num(20.0))), "default")
        engine.assertFact(Atom("score", listOf(id("carol"), num(30.0))), "default")
        val pattern = Atom("score", listOf(variable("p"), variable("v")))
        assertEquals(60.0, engine.aggregateFacts(pattern, 1, AggregateOp.SUM, "default"))
    }

    @Test
    fun `aggregateFacts MIN and MAX return correct extremes`() {
        engine.assertFact(Atom("score", listOf(id("alice"), num(5.0))), "default")
        engine.assertFact(Atom("score", listOf(id("bob"), num(50.0))), "default")
        val pattern = Atom("score", listOf(variable("p"), variable("v")))
        assertEquals(5.0, engine.aggregateFacts(pattern, 1, AggregateOp.MIN, "default"))
        assertEquals(50.0, engine.aggregateFacts(pattern, 1, AggregateOp.MAX, "default"))
    }

    @Test
    fun `aggregateFacts AVG is correct`() {
        engine.assertFact(Atom("score", listOf(id("a"), num(10.0))), "default")
        engine.assertFact(Atom("score", listOf(id("b"), num(20.0))), "default")
        val pattern = Atom("score", listOf(variable("p"), variable("v")))
        assertEquals(15.0, engine.aggregateFacts(pattern, 1, AggregateOp.AVG, "default"))
    }

    @Test
    fun `aggregateFacts returns null when store is empty`() {
        val pattern = Atom("score", listOf(variable("p"), variable("v")))
        assertNull(engine.aggregateFacts(pattern, 1, AggregateOp.SUM, "default"))
        assertNull(engine.aggregateFacts(pattern, 1, AggregateOp.MIN, "default"))
        assertNull(engine.aggregateFacts(pattern, 1, AggregateOp.MAX, "default"))
        assertNull(engine.aggregateFacts(pattern, 1, AggregateOp.AVG, "default"))
    }

    @Test
    fun `aggregateFacts skips non-numeric values`() {
        // mixed predicate where second arg may be numeric or identifier
        engine.assertFact(Atom("attr", listOf(id("alice"), num(42.0))), "default")
        engine.assertFact(Atom("attr", listOf(id("bob"), id("non-numeric"))), "default")
        val pattern = Atom("attr", listOf(variable("e"), variable("v")))
        assertEquals(42.0, engine.aggregateFacts(pattern, 1, AggregateOp.SUM, "default"))
    }

    @Test
    fun `aggregateFacts COUNT via aggregate returns total as double`() {
        engine.assertFact(Atom("score", listOf(id("alice"), num(10.0))), "default")
        engine.assertFact(Atom("score", listOf(id("bob"), num(20.0))), "default")
        val pattern = Atom("score", listOf(variable("p"), variable("v")))
        assertEquals(2.0, engine.aggregateFacts(pattern, 0, AggregateOp.COUNT, "default"))
    }
}

// ---------------------------------------------------------------------------
// Bulk assert tests
// ---------------------------------------------------------------------------

class NocturnusAIBulkAssertTest {

    private lateinit var engine: NocturnusAI
    private lateinit var tmpDir: File

    @BeforeTest
    fun setup() {
        val (e, d) = tempEngine()
        engine = e
        tmpDir = d
    }

    @AfterTest
    fun teardown() {
        engine.close()
        tmpDir.deleteRecursively()
    }

    @Test
    fun `bulkAssertFacts asserts all valid facts`() {
        val atoms = listOf(
            Atom("likes", listOf(id("alice"), id("bob"))),
            Atom("likes", listOf(id("alice"), id("charlie"))),
            Atom("age", listOf(id("alice"), num(30.0)))
        )
        val result = engine.bulkAssertFacts(atoms, "default")
        assertEquals(3, result.asserted)
        assertEquals(0, result.failed)
        assertTrue(result.errors.isEmpty())

        // Verify facts are stored
        val pattern = Atom("likes", listOf(id("alice"), variable("x")))
        assertEquals(2, engine.countFacts(pattern, "default"))
    }

    @Test
    fun `bulkAssertFacts reports contradiction as failure, not exception`() {
        // Assert a positive fact first
        engine.assertFact(Atom("alive", listOf(id("alice"), id("yes"))), "default")

        // Now bulk-assert the negation — contradiction
        val atoms = listOf(
            Atom("alive", listOf(id("alice"), id("yes")), truthVal = false),
            Atom("alive", listOf(id("bob"), id("yes")))  // should still succeed
        )
        val result = engine.bulkAssertFacts(atoms, "default")
        assertEquals(1, result.asserted, "One fact should be asserted despite the contradiction")
        assertEquals(1, result.failed, "One fact should fail due to contradiction")
        assertFalse(result.errors.isEmpty(), "Errors list should contain the failure reason")
    }

    @Test
    fun `bulkAssertFacts with empty list returns zero counts`() {
        val result = engine.bulkAssertFacts(emptyList(), "default")
        assertEquals(0, result.asserted)
        assertEquals(0, result.failed)
    }

    @Test
    fun `bulkAssertFacts all succeed returns zero failures`() {
        val atoms = (1..10).map { i ->
            Atom("item", listOf(id("item$i"), id("present")))
        }
        val result = engine.bulkAssertFacts(atoms, "default")
        assertEquals(10, result.asserted)
        assertEquals(0, result.failed)
    }
}

// ---------------------------------------------------------------------------
// Retract-by-pattern tests
// ---------------------------------------------------------------------------

class NocturnusAIRetractByPatternTest {

    private lateinit var engine: NocturnusAI
    private lateinit var tmpDir: File

    @BeforeTest
    fun setup() {
        val (e, d) = tempEngine()
        engine = e
        tmpDir = d
    }

    @AfterTest
    fun teardown() {
        engine.close()
        tmpDir.deleteRecursively()
    }

    @Test
    fun `retractByPattern removes all matching facts`() {
        engine.assertFact(Atom("likes", listOf(id("alice"), id("bob"))), "default")
        engine.assertFact(Atom("likes", listOf(id("alice"), id("charlie"))), "default")
        engine.assertFact(Atom("likes", listOf(id("bob"), id("dave"))), "default")

        val pattern = Atom("likes", listOf(id("alice"), variable("x")))
        val result = engine.retractByPattern(pattern, "default")

        assertEquals(2, result.retracted)
        assertEquals(2, result.atoms.size)
        assertTrue(result.atoms.all { it.predicate == "likes" && it.args[0] == id("alice") })

        // Verify bob's like is still present
        val remaining = engine.query(Atom("likes", listOf(variable("s"), variable("o"))), "default").toList()
        assertEquals(1, remaining.size)
        assertEquals(id("bob"), remaining[0].args[0])
    }

    @Test
    fun `retractByPattern with full wildcard removes all facts of that predicate`() {
        engine.assertFact(Atom("tag", listOf(id("a"), id("x"))), "default")
        engine.assertFact(Atom("tag", listOf(id("b"), id("y"))), "default")
        engine.assertFact(Atom("tag", listOf(id("c"), id("z"))), "default")
        engine.assertFact(Atom("other", listOf(id("a"), id("b"))), "default")

        val pattern = Atom("tag", listOf(variable("s"), variable("o")))
        val result = engine.retractByPattern(pattern, "default")

        assertEquals(3, result.retracted)
        // "other" should be untouched
        val remaining = engine.query(Atom("other", listOf(variable("s"), variable("o"))), "default").toList()
        assertEquals(1, remaining.size)
    }

    @Test
    fun `retractByPattern with no matches returns zero`() {
        engine.assertFact(Atom("likes", listOf(id("alice"), id("bob"))), "default")
        val pattern = Atom("hates", listOf(variable("x"), variable("y")))
        val result = engine.retractByPattern(pattern, "default")
        assertEquals(0, result.retracted)
        assertTrue(result.atoms.isEmpty())
    }

    @Test
    fun `retractByPattern respects scope`() {
        engine.assertFact(Atom("item", listOf(id("a"), id("b")), scope = "s1"), "default")
        engine.assertFact(Atom("item", listOf(id("c"), id("d")), scope = "s2"), "default")

        val pattern = Atom("item", listOf(variable("x"), variable("y")))
        val result = engine.retractByPattern(pattern, "default", scope = "s1")

        assertEquals(1, result.retracted)
        assertEquals("s1", result.atoms[0].scope)

        // s2 fact should still exist
        val s2 = engine.query(
            Atom("item", listOf(variable("x"), variable("y")), scope = "s2"),
            "default", "s2"
        ).toList()
        assertEquals(1, s2.size)
    }
}
