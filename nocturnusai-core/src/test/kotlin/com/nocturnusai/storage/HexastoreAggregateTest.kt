// Copyright (c) 2026 Auctalis LLC. All rights reserved.
//
// Licensed under the Business Source License 1.1 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://github.com/auctalis/nocturnusai/blob/main/LICENSE

package com.nocturnusai.storage

import com.nocturnusai.core.Atom
import com.nocturnusai.core.Term
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun id(name: String) = Term.Identifier(name)
private fun num(value: Double) = Term.NumberLit(value)
private fun variable(name: String) = Term.Variable(name)

/** Binary fact with numeric second argument. */
private fun scoreAtom(player: String, score: Double, scope: String? = null) =
    Atom("score", listOf(id(player), num(score)), scope = scope)

/** Binary fact with identifier second argument (non-numeric). */
private fun tagAtom(entity: String, tag: String, scope: String? = null) =
    Atom("tag", listOf(id(entity), id(tag)), scope = scope)

/** Pattern matching all score/2 atoms. */
private fun scorePattern(scope: String? = null) =
    Atom("score", listOf(variable("player"), variable("val")), scope = scope)

// ---------------------------------------------------------------------------
// 1. COUNT
// ---------------------------------------------------------------------------

class HexastoreCountTest {

    @Test
    fun `count returns zero on empty store`() {
        val store = Hexastore()
        assertEquals(0, store.count(scorePattern()))
    }

    @Test
    fun `count returns correct number for single fact`() {
        val store = Hexastore()
        store.add(scoreAtom("alice", 10.0))
        assertEquals(1, store.count(scorePattern()))
    }

    @Test
    fun `count returns correct number for multiple facts`() {
        val store = Hexastore()
        store.add(scoreAtom("alice", 10.0))
        store.add(scoreAtom("bob", 20.0))
        store.add(scoreAtom("carol", 30.0))
        assertEquals(3, store.count(scorePattern()))
    }

    @Test
    fun `count with scope filter only counts matching scope`() {
        val store = Hexastore()
        store.add(scoreAtom("alice", 10.0, scope = "round1"))
        store.add(scoreAtom("bob", 20.0, scope = "round1"))
        store.add(scoreAtom("carol", 30.0, scope = "round2"))
        assertEquals(2, store.count(scorePattern(), scope = "round1"))
        assertEquals(1, store.count(scorePattern(), scope = "round2"))
    }

    @Test
    fun `count with bound subject only counts that subject`() {
        val store = Hexastore()
        store.add(scoreAtom("alice", 10.0))
        store.add(scoreAtom("alice", 20.0))
        store.add(scoreAtom("bob", 30.0))
        val pattern = Atom("score", listOf(id("alice"), variable("val")))
        assertEquals(2, store.count(pattern))
    }
}

// ---------------------------------------------------------------------------
// 2. SUM
// ---------------------------------------------------------------------------

class HexastoreSumTest {

    @Test
    fun `sum returns null on empty store`() {
        val store = Hexastore()
        assertNull(store.aggregate(scorePattern(), argIndex = 1, op = AggregateOp.SUM))
    }

    @Test
    fun `sum of single numeric value returns that value`() {
        val store = Hexastore()
        store.add(scoreAtom("alice", 42.0))
        assertEquals(42.0, store.aggregate(scorePattern(), argIndex = 1, op = AggregateOp.SUM))
    }

    @Test
    fun `sum of multiple numeric values is correct`() {
        val store = Hexastore()
        store.add(scoreAtom("alice", 10.0))
        store.add(scoreAtom("bob", 20.0))
        store.add(scoreAtom("carol", 30.0))
        assertEquals(60.0, store.aggregate(scorePattern(), argIndex = 1, op = AggregateOp.SUM))
    }

    @Test
    fun `sum skips non-numeric values at argIndex`() {
        val store = Hexastore()
        store.add(scoreAtom("alice", 10.0))
        store.add(tagAtom("alice", "gold"))  // non-numeric second arg
        val pattern = Atom("score", listOf(variable("e"), variable("v")))
        assertEquals(10.0, store.aggregate(pattern, argIndex = 1, op = AggregateOp.SUM))
    }

    @Test
    fun `sum returns null when all values are non-numeric`() {
        val store = Hexastore()
        store.add(tagAtom("alice", "gold"))
        store.add(tagAtom("bob", "silver"))
        val pattern = Atom("tag", listOf(variable("e"), variable("v")))
        assertNull(store.aggregate(pattern, argIndex = 1, op = AggregateOp.SUM))
    }

    @Test
    fun `sum with scope filter only aggregates matching scope`() {
        val store = Hexastore()
        store.add(scoreAtom("alice", 10.0, scope = "round1"))
        store.add(scoreAtom("bob", 20.0, scope = "round1"))
        store.add(scoreAtom("carol", 100.0, scope = "round2"))
        assertEquals(30.0, store.aggregate(scorePattern(), argIndex = 1, op = AggregateOp.SUM, scope = "round1"))
    }
}

// ---------------------------------------------------------------------------
// 3. MIN / MAX
// ---------------------------------------------------------------------------

class HexastoreMinMaxTest {

    @Test
    fun `min returns correct minimum`() {
        val store = Hexastore()
        store.add(scoreAtom("alice", 30.0))
        store.add(scoreAtom("bob", 10.0))
        store.add(scoreAtom("carol", 20.0))
        assertEquals(10.0, store.aggregate(scorePattern(), argIndex = 1, op = AggregateOp.MIN))
    }

    @Test
    fun `max returns correct maximum`() {
        val store = Hexastore()
        store.add(scoreAtom("alice", 30.0))
        store.add(scoreAtom("bob", 10.0))
        store.add(scoreAtom("carol", 20.0))
        assertEquals(30.0, store.aggregate(scorePattern(), argIndex = 1, op = AggregateOp.MAX))
    }

    @Test
    fun `min returns null on empty store`() {
        val store = Hexastore()
        assertNull(store.aggregate(scorePattern(), argIndex = 1, op = AggregateOp.MIN))
    }

    @Test
    fun `max returns null on empty store`() {
        val store = Hexastore()
        assertNull(store.aggregate(scorePattern(), argIndex = 1, op = AggregateOp.MAX))
    }

    @Test
    fun `min with scope filter only considers matching scope`() {
        val store = Hexastore()
        store.add(scoreAtom("alice", 5.0, scope = "s1"))
        store.add(scoreAtom("bob", 100.0, scope = "s2"))
        assertEquals(5.0, store.aggregate(scorePattern(), argIndex = 1, op = AggregateOp.MIN, scope = "s1"))
        assertEquals(100.0, store.aggregate(scorePattern(), argIndex = 1, op = AggregateOp.MIN, scope = "s2"))
    }
}

// ---------------------------------------------------------------------------
// 4. AVG
// ---------------------------------------------------------------------------

class HexastoreAvgTest {

    @Test
    fun `avg returns null on empty store`() {
        val store = Hexastore()
        assertNull(store.aggregate(scorePattern(), argIndex = 1, op = AggregateOp.AVG))
    }

    @Test
    fun `avg of single value equals that value`() {
        val store = Hexastore()
        store.add(scoreAtom("alice", 42.0))
        assertEquals(42.0, store.aggregate(scorePattern(), argIndex = 1, op = AggregateOp.AVG))
    }

    @Test
    fun `avg of multiple values is correct`() {
        val store = Hexastore()
        store.add(scoreAtom("alice", 10.0))
        store.add(scoreAtom("bob", 20.0))
        store.add(scoreAtom("carol", 30.0))
        assertEquals(20.0, store.aggregate(scorePattern(), argIndex = 1, op = AggregateOp.AVG))
    }

    @Test
    fun `avg skips non-numeric values`() {
        val store = Hexastore()
        store.add(scoreAtom("alice", 10.0))
        store.add(tagAtom("alice", "gold"))   // non-numeric at index 1 for "tag" predicate
        val pattern = Atom("score", listOf(variable("p"), variable("v")))
        assertEquals(10.0, store.aggregate(pattern, argIndex = 1, op = AggregateOp.AVG))
    }
}

// ---------------------------------------------------------------------------
// 5. COUNT via aggregate()
// ---------------------------------------------------------------------------

class HexastoreAggregateCountTest {

    @Test
    fun `aggregate COUNT returns zero for empty store`() {
        val store = Hexastore()
        assertEquals(0.0, store.aggregate(scorePattern(), argIndex = 0, op = AggregateOp.COUNT))
    }

    @Test
    fun `aggregate COUNT returns total matching atoms`() {
        val store = Hexastore()
        store.add(scoreAtom("alice", 10.0))
        store.add(scoreAtom("bob", 20.0))
        assertEquals(2.0, store.aggregate(scorePattern(), argIndex = 0, op = AggregateOp.COUNT))
    }

    @Test
    fun `aggregate COUNT ignores argIndex`() {
        val store = Hexastore()
        store.add(scoreAtom("alice", 10.0))
        // argIndex=-1 would be out of bounds for SUM/MIN/MAX/AVG but COUNT should still work
        assertEquals(1.0, store.aggregate(scorePattern(), argIndex = -1, op = AggregateOp.COUNT))
    }

    @Test
    fun `aggregate COUNT with scope only counts scoped facts`() {
        val store = Hexastore()
        store.add(scoreAtom("alice", 10.0, scope = "s1"))
        store.add(scoreAtom("bob", 20.0, scope = "s1"))
        store.add(scoreAtom("carol", 30.0, scope = "s2"))
        assertEquals(2.0, store.aggregate(scorePattern(), argIndex = 0, op = AggregateOp.COUNT, scope = "s1"))
        assertEquals(1.0, store.aggregate(scorePattern(), argIndex = 0, op = AggregateOp.COUNT, scope = "s2"))
    }
}
