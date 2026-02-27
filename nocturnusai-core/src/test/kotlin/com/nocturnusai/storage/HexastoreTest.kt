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

package com.nocturnusai.storage

import com.nocturnusai.core.Atom
import com.nocturnusai.core.SourceType
import com.nocturnusai.core.Term
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun id(name: String) = Term.Identifier(name)
private fun str(value: String) = Term.StringLit(value)
private fun num(value: Double) = Term.NumberLit(value)
private fun variable(name: String) = Term.Variable(name)

/** Binary positive fact shorthand. */
private fun fact(pred: String, s: String, o: String, scope: String? = null) =
    Atom(pred, listOf(id(s), id(o)), truthVal = true, scope = scope)

/** Binary negative fact shorthand. */
private fun negFact(pred: String, s: String, o: String, scope: String? = null) =
    Atom(pred, listOf(id(s), id(o)), truthVal = false, scope = scope)

/** Binary query pattern — uses Term.Variable for wildcards. */
private fun pattern(pred: String, s: Term, o: Term, scope: String? = null) =
    Atom(pred, listOf(s, o), truthVal = true, scope = scope)

// ---------------------------------------------------------------------------
// 1. Basic CRUD
// ---------------------------------------------------------------------------

class HexastoreBasicCrudTest {

    @Test
    fun `add a fact and query it back`() {
        val store = Hexastore()
        val atom = fact("likes", "alice", "bob")
        store.add(atom)

        val results = store.match(pattern("likes", id("alice"), id("bob"))).toList()
        assertEquals(1, results.size, "Expected exactly one match")
        assertEquals(atom, results[0])
    }

    @Test
    fun `add multiple facts and query all`() {
        val store = Hexastore()
        val facts = listOf(
            fact("likes", "alice", "bob"),
            fact("likes", "alice", "carol"),
            fact("likes", "bob", "dave")
        )
        facts.forEach { store.add(it) }

        val allLikes = store.match(pattern("likes", variable("x"), variable("y"))).toList()
        assertEquals(3, allLikes.size, "Expected all 3 likes facts")
        assertTrue(facts.all { it in allLikes }, "All added facts should be present")
    }

    @Test
    fun `delete a fact and verify it is gone`() {
        val store = Hexastore()
        val atom = fact("likes", "alice", "bob")
        store.add(atom)

        store.delete(atom)

        val results = store.match(pattern("likes", id("alice"), id("bob"))).toList()
        assertTrue(results.isEmpty(), "Fact should not exist after deletion")
    }

    @Test
    fun `delete one of several facts leaves others intact`() {
        val store = Hexastore()
        val a = fact("likes", "alice", "bob")
        val b = fact("likes", "alice", "carol")
        store.add(a)
        store.add(b)

        store.delete(a)

        val remaining = store.match(pattern("likes", id("alice"), variable("y"))).toList()
        assertEquals(1, remaining.size, "Only one fact should remain")
        assertEquals(b, remaining[0])
    }

    @Test
    fun `delete non-existent fact is a no-op`() {
        val store = Hexastore()
        store.add(fact("likes", "alice", "bob"))

        // Deleting something that was never added should not throw
        store.delete(fact("likes", "nobody", "nothing"))

        val results = store.match(pattern("likes", variable("x"), variable("y"))).toList()
        assertEquals(1, results.size, "Original fact should be unaffected")
    }

    @Test
    fun `getAllAtoms returns every stored atom`() {
        val store = Hexastore()
        val atoms = (1..5).map { i -> fact("rel", "a$i", "b$i") }
        atoms.forEach { store.add(it) }

        val all = store.getAllAtoms().toList()
        assertEquals(5, all.size)
        assertTrue(atoms.all { it in all })
    }

    @Test
    fun `getAllAtoms on empty store returns empty sequence`() {
        val store = Hexastore()
        val all = store.getAllAtoms().toList()
        assertTrue(all.isEmpty(), "Empty store should yield no atoms")
    }

    @Test
    fun `add same fact twice does not create duplicate`() {
        val store = Hexastore()
        val atom = fact("likes", "alice", "bob")
        store.add(atom)
        store.add(atom)

        val results = store.match(pattern("likes", id("alice"), id("bob"))).toList()
        assertEquals(1, results.size, "Duplicate add should not create two entries")
    }

    @Test
    fun `upsert updates metadata while keeping logical identity`() {
        val store = Hexastore()
        val original = Atom(
            "rel", listOf(id("a"), id("b")),
            createdAt = 1000L, validUntil = 2000L
        )
        val updated = Atom(
            "rel", listOf(id("a"), id("b")),
            createdAt = 1000L, validUntil = 9999L
        )

        store.add(original)
        store.add(updated)

        val results = store.match(pattern("rel", id("a"), id("b"))).toList()
        assertEquals(1, results.size, "Upsert should replace, not append")
        assertEquals(9999L, results[0].validUntil, "Updated validUntil should be stored")
    }
}

// ---------------------------------------------------------------------------
// 2. Pattern matching — query method directly (effective-predicate aware)
// ---------------------------------------------------------------------------

class HexastoreQueryPatternTest {

    @Test
    fun `query with all args bound returns exact match`() {
        val store = Hexastore()
        store.add(fact("likes", "alice", "bob"))
        store.add(fact("likes", "alice", "carol"))

        // Effective predicate for a positive fact is just the predicate name
        val results = store.query(id("alice"), "likes", id("bob")).toList()
        assertEquals(1, results.size)
        assertEquals(id("bob"), results[0].args[1])
    }

    @Test
    fun `query with subject bound and object wildcard finds all objects for subject`() {
        val store = Hexastore()
        store.add(fact("likes", "alice", "bob"))
        store.add(fact("likes", "alice", "carol"))
        store.add(fact("likes", "bob", "dave"))

        val results = store.query(id("alice"), "likes", null).toList()
        assertEquals(2, results.size)
        val objects = results.map { it.args[1] }.toSet()
        assertEquals(setOf(id("bob"), id("carol")), objects)
    }

    @Test
    fun `query with object bound and subject wildcard finds all subjects for object`() {
        val store = Hexastore()
        store.add(fact("likes", "alice", "pizza"))
        store.add(fact("likes", "bob", "pizza"))
        store.add(fact("likes", "carol", "sushi"))

        val results = store.query(null, "likes", id("pizza")).toList()
        assertEquals(2, results.size)
        val subjects = results.map { it.args[0] }.toSet()
        assertEquals(setOf(id("alice"), id("bob")), subjects)
    }

    @Test
    fun `query with predicate only returns all facts for that predicate`() {
        val store = Hexastore()
        store.add(fact("likes", "alice", "bob"))
        store.add(fact("likes", "carol", "dave"))
        store.add(fact("knows", "alice", "carol"))

        val results = store.query(null, "likes", null).toList()
        assertEquals(2, results.size, "Should return all 'likes' facts")
        assertTrue(results.all { it.predicate == "likes" })
    }

    @Test
    fun `query with all args null returns every binary fact`() {
        val store = Hexastore()
        store.add(fact("likes", "a", "b"))
        store.add(fact("knows", "c", "d"))
        store.add(fact("hates", "e", "f"))

        val results = store.query(null, null, null).toList()
        assertEquals(3, results.size, "Wildcard query should return all binary facts")
    }

    @Test
    fun `query with non-existent predicate returns empty`() {
        val store = Hexastore()
        store.add(fact("likes", "alice", "bob"))

        val results = store.query(null, "hates", null).toList()
        assertTrue(results.isEmpty())
    }

    @Test
    fun `query with non-existent subject returns empty`() {
        val store = Hexastore()
        store.add(fact("likes", "alice", "bob"))

        val results = store.query(id("nobody"), "likes", null).toList()
        assertTrue(results.isEmpty())
    }
}

// ---------------------------------------------------------------------------
// 3. Pattern matching — match() with Term.Variable wildcards
// ---------------------------------------------------------------------------

class HexastoreMatchVariableTest {

    @Test
    fun `match with all bound identifiers returns exact fact`() {
        val store = Hexastore()
        val atom = fact("likes", "alice", "bob")
        store.add(atom)

        val results = store.match(pattern("likes", id("alice"), id("bob"))).toList()
        assertEquals(1, results.size)
        assertEquals(atom, results[0])
    }

    @Test
    fun `match with variable subject finds all subjects for given object`() {
        val store = Hexastore()
        store.add(fact("likes", "alice", "bob"))
        store.add(fact("likes", "carol", "bob"))
        store.add(fact("likes", "dave", "eve"))

        val results = store.match(pattern("likes", variable("x"), id("bob"))).toList()
        assertEquals(2, results.size)
        val subjects = results.map { it.args[0] }.toSet()
        assertEquals(setOf(id("alice"), id("carol")), subjects)
    }

    @Test
    fun `match with variable object finds all objects for given subject`() {
        val store = Hexastore()
        store.add(fact("likes", "alice", "bob"))
        store.add(fact("likes", "alice", "carol"))
        store.add(fact("likes", "dave", "eve"))

        val results = store.match(pattern("likes", id("alice"), variable("y"))).toList()
        assertEquals(2, results.size)
        val objects = results.map { it.args[1] }.toSet()
        assertEquals(setOf(id("bob"), id("carol")), objects)
    }

    @Test
    fun `match with both variables returns all facts for predicate`() {
        val store = Hexastore()
        store.add(fact("likes", "alice", "bob"))
        store.add(fact("likes", "carol", "dave"))
        store.add(fact("knows", "alice", "carol"))

        val results = store.match(pattern("likes", variable("x"), variable("y"))).toList()
        assertEquals(2, results.size, "Should return only 'likes' facts")
        assertTrue(results.all { it.predicate == "likes" })
    }

    @Test
    fun `match on unknown predicate returns empty`() {
        val store = Hexastore()
        store.add(fact("likes", "alice", "bob"))

        val results = store.match(pattern("hates", variable("x"), variable("y"))).toList()
        assertTrue(results.isEmpty())
    }

    @Test
    fun `match with StringLit and NumberLit terms`() {
        val store = Hexastore()
        val atom = Atom("score", listOf(id("alice"), num(42.0)))
        val atom2 = Atom("label", listOf(id("item1"), str("active")))
        store.add(atom)
        store.add(atom2)

        val scoreResults = store.match(Atom("score", listOf(id("alice"), variable("v")))).toList()
        assertEquals(1, scoreResults.size)
        assertEquals(num(42.0), scoreResults[0].args[1])

        val labelResults = store.match(Atom("label", listOf(id("item1"), variable("v")))).toList()
        assertEquals(1, labelResults.size)
        assertEquals(str("active"), labelResults[0].args[1])
    }
}

// ---------------------------------------------------------------------------
// 4. Multi-arity support
// ---------------------------------------------------------------------------

class HexastoreArityTest {

    @Test
    fun `binary atoms use 6-way index and are queryable`() {
        val store = Hexastore()
        val atom = Atom("rel", listOf(id("a"), id("b")))
        store.add(atom)

        val results = store.match(Atom("rel", listOf(variable("x"), variable("y")))).toList()
        assertEquals(1, results.size)
    }

    @Test
    fun `unary atoms use fallback store and match by predicate`() {
        val store = Hexastore()
        val atom = Atom("alive", listOf(id("alice")))
        store.add(atom)

        val results = store.match(Atom("alive", listOf(variable("x")))).toList()
        assertEquals(1, results.size)
        assertEquals(atom, results[0])
    }

    @Test
    fun `unary atoms match bound identifier correctly`() {
        val store = Hexastore()
        store.add(Atom("alive", listOf(id("alice"))))
        store.add(Atom("alive", listOf(id("bob"))))

        val specific = store.match(Atom("alive", listOf(id("alice")))).toList()
        assertEquals(1, specific.size)
        assertEquals(id("alice"), specific[0].args[0])
    }

    @Test
    fun `ternary atoms use fallback store and are retrieved via match`() {
        val store = Hexastore()
        val atom = Atom("between", listOf(id("a"), id("b"), id("c")))
        store.add(atom)

        val results = store.match(
            Atom("between", listOf(variable("x"), variable("y"), variable("z")))
        ).toList()
        assertEquals(1, results.size)
        assertEquals(atom, results[0])
    }

    @Test
    fun `ternary match with bound first arg narrows results`() {
        val store = Hexastore()
        store.add(Atom("between", listOf(id("a"), id("b"), id("c"))))
        store.add(Atom("between", listOf(id("x"), id("y"), id("z"))))

        val results = store.match(
            Atom("between", listOf(id("a"), variable("mid"), variable("end")))
        ).toList()
        assertEquals(1, results.size)
        assertEquals(id("b"), results[0].args[1])
    }

    @Test
    fun `four-arg atoms are stored and retrieved`() {
        val store = Hexastore()
        val atom = Atom("quad", listOf(id("a"), id("b"), id("c"), id("d")))
        store.add(atom)

        val results = store.match(
            Atom("quad", listOf(variable("w"), variable("x"), variable("y"), variable("z")))
        ).toList()
        assertEquals(1, results.size)
        assertEquals(atom, results[0])
    }

    @Test
    fun `binary and non-binary atoms with same predicate are stored independently`() {
        val store = Hexastore()
        val binary = Atom("rel", listOf(id("a"), id("b")))
        val ternary = Atom("rel", listOf(id("a"), id("b"), id("c")))
        store.add(binary)
        store.add(ternary)

        // Binary via 6-way index
        val binaryResults = store.match(Atom("rel", listOf(variable("x"), variable("y")))).toList()
        assertEquals(1, binaryResults.size, "Binary query should not include ternary atoms")

        // Ternary via fallback
        val ternaryResults = store.match(
            Atom("rel", listOf(variable("x"), variable("y"), variable("z")))
        ).toList()
        assertEquals(1, ternaryResults.size, "Ternary query should return the ternary atom")
    }

    @Test
    fun `unary delete works correctly`() {
        val store = Hexastore()
        val atom = Atom("active", listOf(id("service1")))
        store.add(atom)
        store.delete(atom)

        val results = store.match(Atom("active", listOf(variable("x")))).toList()
        assertTrue(results.isEmpty(), "Deleted unary atom should not be returned")
    }

    @Test
    fun `getAllAtoms includes both binary and non-binary atoms`() {
        val store = Hexastore()
        store.add(Atom("binary", listOf(id("a"), id("b"))))
        store.add(Atom("unary", listOf(id("x"))))
        store.add(Atom("ternary", listOf(id("p"), id("q"), id("r"))))

        val all = store.getAllAtoms().toList()
        assertEquals(3, all.size, "getAllAtoms should include atoms of all arities")
    }
}

// ---------------------------------------------------------------------------
// 5. Scope isolation
// ---------------------------------------------------------------------------

class HexastoreScopeTest {

    @Test
    fun `fact added in scope A is found when querying scope A`() {
        val store = Hexastore()
        store.add(fact("likes", "alice", "bob", scope = "scopeA"))

        val results = store.match(
            pattern("likes", variable("x"), variable("y")),
            scope = "scopeA"
        ).toList()
        assertEquals(1, results.size)
    }

    @Test
    fun `fact added in scope A is not found when querying scope B`() {
        val store = Hexastore()
        store.add(fact("likes", "alice", "bob", scope = "scopeA"))

        val results = store.match(
            pattern("likes", variable("x"), variable("y")),
            scope = "scopeB"
        ).toList()
        assertTrue(results.isEmpty(), "Scope-A fact should not appear in scope-B query")
    }

    @Test
    fun `fact added without scope is found when querying without scope`() {
        val store = Hexastore()
        store.add(fact("likes", "alice", "bob", scope = null))

        val results = store.match(
            pattern("likes", variable("x"), variable("y"), scope = null)
        ).toList()
        assertEquals(1, results.size)
    }

    @Test
    fun `null-scope fact is not returned when querying a specific scope`() {
        val store = Hexastore()
        store.add(fact("likes", "alice", "bob", scope = null))

        // Passing scope="someScope" to match — should not find null-scope fact
        val results = store.match(
            pattern("likes", variable("x"), variable("y")),
            scope = "someScope"
        ).toList()
        assertTrue(results.isEmpty(), "Null-scoped fact should not appear in scoped query")
    }

    @Test
    fun `different scope facts for same predicate and args are stored separately`() {
        val store = Hexastore()
        val factA = fact("believes", "agent", "x_is_true", scope = "hypothesis1")
        val factB = fact("believes", "agent", "x_is_true", scope = "hypothesis2")
        store.add(factA)
        store.add(factB)

        val h1Results = store.match(
            pattern("believes", id("agent"), id("x_is_true")),
            scope = "hypothesis1"
        ).toList()
        assertEquals(1, h1Results.size)
        assertEquals("hypothesis1", h1Results[0].scope)

        val h2Results = store.match(
            pattern("believes", id("agent"), id("x_is_true")),
            scope = "hypothesis2"
        ).toList()
        assertEquals(1, h2Results.size)
        assertEquals("hypothesis2", h2Results[0].scope)
    }

    @Test
    fun `unscoped query returns facts across all scopes`() {
        val store = Hexastore()
        store.add(fact("item", "a", "b", scope = "scope1"))
        store.add(fact("item", "c", "d", scope = "scope2"))
        store.add(fact("item", "e", "f", scope = null))

        // match with no scope override and pattern scope=null → returns all
        val results = store.match(
            pattern("item", variable("x"), variable("y"), scope = null)
        ).toList()
        assertEquals(3, results.size, "Unscoped query should return facts from all scopes")
    }

    @Test
    fun `delete removes only the fact in the specified scope`() {
        val store = Hexastore()
        store.add(fact("item", "a", "b", scope = "s1"))
        store.add(fact("item", "a", "b", scope = "s2"))

        store.delete(fact("item", "a", "b", scope = "s1"))

        val s1 = store.match(pattern("item", id("a"), id("b")), scope = "s1").toList()
        val s2 = store.match(pattern("item", id("a"), id("b")), scope = "s2").toList()
        assertTrue(s1.isEmpty(), "Deleted scope-s1 fact should be gone")
        assertEquals(1, s2.size, "Scope-s2 fact should remain")
    }

    @Test
    fun `scope isolation works for non-binary atoms in fallback store`() {
        val store = Hexastore()
        store.add(Atom("tag", listOf(id("item1")), scope = "ctx1"))
        store.add(Atom("tag", listOf(id("item1")), scope = "ctx2"))

        val ctx1 = store.match(Atom("tag", listOf(id("item1"))), scope = "ctx1").toList()
        assertEquals(1, ctx1.size)
        assertEquals("ctx1", ctx1[0].scope)

        val ctx2 = store.match(Atom("tag", listOf(id("item1"))), scope = "ctx2").toList()
        assertEquals(1, ctx2.size)
        assertEquals("ctx2", ctx2[0].scope)
    }
}

// ---------------------------------------------------------------------------
// 6. Negated / negative-truth-value atoms
// ---------------------------------------------------------------------------

class HexastoreNegationTest {

    @Test
    fun `negative fact is stored and retrieved separately from positive fact`() {
        val store = Hexastore()
        val pos = Atom("likes", listOf(id("alice"), id("bob")), truthVal = true)
        val neg = Atom("likes", listOf(id("alice"), id("bob")), truthVal = false)
        store.add(pos)
        store.add(neg)

        val posResults = store.match(
            Atom("likes", listOf(id("alice"), id("bob")), truthVal = true)
        ).toList()
        assertEquals(1, posResults.size)
        assertTrue(posResults[0].truthVal)

        val negResults = store.match(
            Atom("likes", listOf(id("alice"), id("bob")), truthVal = false)
        ).toList()
        assertEquals(1, negResults.size)
        assertFalse(negResults[0].truthVal)
    }

    @Test
    fun `negative fact match with variable returns negated atoms`() {
        val store = Hexastore()
        store.add(Atom("likes", listOf(id("alice"), id("bob")), truthVal = false))
        store.add(Atom("likes", listOf(id("carol"), id("dave")), truthVal = false))

        val results = store.match(
            Atom("likes", listOf(variable("x"), variable("y")), truthVal = false)
        ).toList()
        assertEquals(2, results.size)
        assertTrue(results.all { !it.truthVal })
    }

    @Test
    fun `positive query does not return negative facts`() {
        val store = Hexastore()
        store.add(Atom("likes", listOf(id("alice"), id("bob")), truthVal = false))

        val results = store.match(
            Atom("likes", listOf(variable("x"), variable("y")), truthVal = true)
        ).toList()
        assertTrue(results.isEmpty(), "Positive query should not return negative facts")
    }

    @Test
    fun `negative query does not return positive facts`() {
        val store = Hexastore()
        store.add(Atom("likes", listOf(id("alice"), id("bob")), truthVal = true))

        val results = store.match(
            Atom("likes", listOf(variable("x"), variable("y")), truthVal = false)
        ).toList()
        assertTrue(results.isEmpty(), "Negative query should not return positive facts")
    }

    @Test
    fun `delete negative fact does not affect positive fact`() {
        val store = Hexastore()
        store.add(Atom("likes", listOf(id("alice"), id("bob")), truthVal = true))
        store.add(Atom("likes", listOf(id("alice"), id("bob")), truthVal = false))

        store.delete(Atom("likes", listOf(id("alice"), id("bob")), truthVal = false))

        val pos = store.match(
            Atom("likes", listOf(id("alice"), id("bob")), truthVal = true)
        ).toList()
        val neg = store.match(
            Atom("likes", listOf(id("alice"), id("bob")), truthVal = false)
        ).toList()
        assertEquals(1, pos.size, "Positive fact should survive deletion of negative counterpart")
        assertTrue(neg.isEmpty())
    }

    @Test
    fun `getAllAtoms includes both positive and negative atoms`() {
        val store = Hexastore()
        store.add(Atom("likes", listOf(id("a"), id("b")), truthVal = true))
        store.add(Atom("likes", listOf(id("a"), id("b")), truthVal = false))

        val all = store.getAllAtoms().toList()
        assertEquals(2, all.size, "Both positive and negative atoms should appear in getAllAtoms")
        assertTrue(all.any { it.truthVal })
        assertTrue(all.any { !it.truthVal })
    }

    @Test
    fun `negated unary atom is stored and retrieved`() {
        val store = Hexastore()
        store.add(Atom("alive", listOf(id("alice")), truthVal = false))

        val results = store.match(Atom("alive", listOf(variable("x")), truthVal = false)).toList()
        assertEquals(1, results.size)
        assertFalse(results[0].truthVal)
    }
}

// ---------------------------------------------------------------------------
// 7. Source type differentiation
// ---------------------------------------------------------------------------

class HexastoreSourceTypeTest {

    @Test
    fun `atoms with different source types are stored as separate entries`() {
        val store = Hexastore()
        val userFact = Atom("likes", listOf(id("alice"), id("bob")), source = SourceType.USER_INPUT)
        val inferredFact = Atom("likes", listOf(id("alice"), id("bob")), source = SourceType.INFERRED)
        store.add(userFact)
        store.add(inferredFact)

        val all = store.getAllAtoms().toList()
        assertEquals(2, all.size, "Atoms differing only by source should both be stored")
        assertTrue(all.any { it.source == SourceType.USER_INPUT })
        assertTrue(all.any { it.source == SourceType.INFERRED })
    }
}

// ---------------------------------------------------------------------------
// 8. Thread safety
// ---------------------------------------------------------------------------

class HexastoreThreadSafetyTest {

    @Test
    fun `concurrent writes from multiple threads do not corrupt the store`() {
        val store = Hexastore()
        val threadCount = 8
        val factsPerThread = 100
        val barrier = CyclicBarrier(threadCount)
        val errors = ConcurrentHashMap.newKeySet<String>()

        val threads = (0 until threadCount).map { t ->
            thread {
                barrier.await()
                for (i in 0 until factsPerThread) {
                    try {
                        store.add(fact("rel", "s${t}_$i", "o${t}_$i"))
                    } catch (e: Exception) {
                        errors.add("writer-$t: ${e.message}")
                    }
                }
            }
        }
        threads.forEach { it.join() }

        assertTrue(errors.isEmpty(), "No errors expected during concurrent writes: $errors")
        val total = store.getAllAtoms().toList().size
        assertEquals(threadCount * factsPerThread, total,
            "All ${ threadCount * factsPerThread } facts should be present")
    }

    @Test
    fun `concurrent reads and writes do not cause exceptions`() {
        val store = Hexastore()
        val iterations = 300
        val barrier = CyclicBarrier(4)
        val errors = ConcurrentHashMap.newKeySet<String>()

        // Pre-populate
        (0 until 50).forEach { i -> store.add(fact("data", "s$i", "o$i")) }

        val writer = thread {
            barrier.await()
            for (i in 50 until 50 + iterations) {
                try { store.add(fact("data", "s$i", "o$i")) }
                catch (e: Exception) { errors.add("writer: ${e.message}") }
            }
        }

        val reader1 = thread {
            barrier.await()
            for (i in 0 until iterations) {
                try { store.match(pattern("data", variable("x"), variable("y"))).toList() }
                catch (e: Exception) { errors.add("reader1: ${e.message}") }
            }
        }

        val reader2 = thread {
            barrier.await()
            for (i in 0 until iterations) {
                try { store.getAllAtoms().toList() }
                catch (e: Exception) { errors.add("reader2: ${e.message}") }
            }
        }

        val deleter = thread {
            barrier.await()
            for (i in 0 until 50) {
                try { store.delete(fact("data", "s$i", "o$i")) }
                catch (e: Exception) { errors.add("deleter: ${e.message}") }
            }
        }

        writer.join(); reader1.join(); reader2.join(); deleter.join()
        assertTrue(errors.isEmpty(), "Concurrent read/write/delete errors: $errors")
    }

    @Test
    fun `concurrent match queries return consistent results`() {
        val store = Hexastore()
        val atomCount = 200
        (0 until atomCount).forEach { i -> store.add(fact("item", "a$i", "b$i")) }

        val barrier = CyclicBarrier(6)
        val resultSizes = Array(6) { AtomicInteger(-1) }
        val errors = ConcurrentHashMap.newKeySet<String>()

        val readers = (0 until 6).map { t ->
            thread {
                barrier.await()
                try {
                    val results = store.match(pattern("item", variable("x"), variable("y"))).toList()
                    resultSizes[t].set(results.size)
                } catch (e: Exception) {
                    errors.add("reader-$t: ${e.message}")
                }
            }
        }
        readers.forEach { it.join() }

        assertTrue(errors.isEmpty(), "No errors during concurrent reads: $errors")
        resultSizes.forEach { size ->
            assertEquals(atomCount, size.get(), "All readers should see all $atomCount facts")
        }
    }

    @Test
    fun `many concurrent writers then single reader sees all facts`() {
        val store = Hexastore()
        val threads = 16
        val perThread = 50
        val barrier = CyclicBarrier(threads)

        (0 until threads).map { t ->
            thread {
                barrier.await()
                (0 until perThread).forEach { i ->
                    store.add(fact("node", "t${t}n$i", "v${t}n$i"))
                }
            }
        }.forEach { it.join() }

        val all = store.getAllAtoms().toList()
        assertEquals(threads * perThread, all.size,
            "Reader should see all ${threads * perThread} facts")
    }
}

// ---------------------------------------------------------------------------
// 9. Edge cases
// ---------------------------------------------------------------------------

class HexastoreEdgeCasesTest {

    @Test
    fun `predicate with special characters is handled correctly`() {
        val store = Hexastore()
        val atom = Atom("is_a_kind-of.type/v2", listOf(id("x"), id("y")))
        store.add(atom)

        val results = store.match(
            Atom("is_a_kind-of.type/v2", listOf(variable("a"), variable("b")))
        ).toList()
        assertEquals(1, results.size)
        assertEquals(atom.predicate, results[0].predicate)
    }

    @Test
    fun `very long predicate and arg names are handled`() {
        val longName = "a".repeat(500)
        val store = Hexastore()
        val atom = Atom(longName, listOf(id(longName + "_s"), id(longName + "_o")))
        store.add(atom)

        val results = store.match(
            Atom(longName, listOf(variable("x"), variable("y")))
        ).toList()
        assertEquals(1, results.size)
    }

    @Test
    fun `empty string predicate is handled without exception`() {
        val store = Hexastore()
        val atom = Atom("", listOf(id("a"), id("b")))
        store.add(atom)

        val results = store.match(Atom("", listOf(variable("x"), variable("y")))).toList()
        assertEquals(1, results.size)
    }

    @Test
    fun `unicode identifiers are stored and retrieved correctly`() {
        val store = Hexastore()
        val atom = Atom("関係", listOf(id("エンティティA"), id("エンティティB")))
        store.add(atom)

        val results = store.match(
            Atom("関係", listOf(id("エンティティA"), variable("y")))
        ).toList()
        assertEquals(1, results.size)
        assertEquals(id("エンティティB"), results[0].args[1])
    }

    @Test
    fun `one thousand facts stored and all retrievable`() {
        val store = Hexastore()
        val count = 1000
        (0 until count).forEach { i -> store.add(fact("entry", "s$i", "o$i")) }

        val all = store.match(pattern("entry", variable("x"), variable("y"))).toList()
        assertEquals(count, all.size, "All $count facts should be retrievable")
    }

    @Test
    fun `querying after deletion of all facts returns empty`() {
        val store = Hexastore()
        val atoms = (0 until 50).map { i -> fact("tmp", "a$i", "b$i") }
        atoms.forEach { store.add(it) }
        atoms.forEach { store.delete(it) }

        val remaining = store.match(pattern("tmp", variable("x"), variable("y"))).toList()
        assertTrue(remaining.isEmpty(), "Store should be empty after deleting all facts")

        val all = store.getAllAtoms().toList()
        assertTrue(all.isEmpty(), "getAllAtoms should also be empty")
    }

    @Test
    fun `same subject and object in a fact is handled correctly`() {
        val store = Hexastore()
        val atom = fact("knows", "alice", "alice")
        store.add(atom)

        val results = store.match(pattern("knows", id("alice"), id("alice"))).toList()
        assertEquals(1, results.size)

        val bySubject = store.match(pattern("knows", id("alice"), variable("y"))).toList()
        assertEquals(1, bySubject.size)
        assertEquals(id("alice"), bySubject[0].args[1])
    }

    @Test
    fun `mixed predicate types in same store do not interfere`() {
        val store = Hexastore()
        store.add(fact("A", "s1", "o1"))
        store.add(Atom("A", listOf(id("s1"))))                   // unary, same pred name
        store.add(Atom("A", listOf(id("s1"), id("o1"), id("e")))) // ternary, same pred name

        val binary = store.match(Atom("A", listOf(variable("x"), variable("y")))).toList()
        assertEquals(1, binary.size, "Only binary atom should match binary pattern")

        val unary = store.match(Atom("A", listOf(variable("x")))).toList()
        assertEquals(1, unary.size, "Only unary atom should match unary pattern")

        val ternary = store.match(Atom("A", listOf(variable("x"), variable("y"), variable("z")))).toList()
        assertEquals(1, ternary.size, "Only ternary atom should match ternary pattern")
    }

    @Test
    fun `NumberLit with integer-looking double is stored correctly`() {
        val store = Hexastore()
        store.add(Atom("count", listOf(id("bucket"), num(0.0))))
        store.add(Atom("count", listOf(id("bucket"), num(1.0))))

        val results = store.match(
            Atom("count", listOf(id("bucket"), variable("n")))
        ).toList()
        assertEquals(2, results.size)
    }

    @Test
    fun `large fan-out query performance is reasonable`() {
        val store = Hexastore()
        val fanOut = 500
        (0 until fanOut).forEach { i -> store.add(fact("likes", "hub", "node$i")) }

        val start = System.currentTimeMillis()
        val results = store.match(pattern("likes", id("hub"), variable("y"))).toList()
        val elapsed = System.currentTimeMillis() - start

        assertEquals(fanOut, results.size)
        assertTrue(elapsed < 500, "Fan-out query of $fanOut results took ${elapsed}ms, expected <500ms")
    }

    @Test
    fun `large fan-in query performance is reasonable`() {
        val store = Hexastore()
        val fanIn = 500
        (0 until fanIn).forEach { i -> store.add(fact("follows", "follower$i", "hub")) }

        val start = System.currentTimeMillis()
        val results = store.match(pattern("follows", variable("x"), id("hub"))).toList()
        val elapsed = System.currentTimeMillis() - start

        assertEquals(fanIn, results.size)
        assertTrue(elapsed < 500, "Fan-in query of $fanIn results took ${elapsed}ms, expected <500ms")
    }
}

// ---------------------------------------------------------------------------
// 10. Index permutation correctness
// ---------------------------------------------------------------------------

class HexastoreIndexPermutationTest {

    /**
     * Validates all six meaningful query access patterns against a fixed dataset,
     * ensuring every index permutation returns correct results.
     */
    @Test
    fun `all six index access patterns return correct results`() {
        val store = Hexastore()
        // Controlled dataset: alice likes bob, alice likes carol, dave likes bob
        store.add(fact("likes", "alice", "bob"))
        store.add(fact("likes", "alice", "carol"))
        store.add(fact("likes", "dave", "bob"))
        store.add(fact("hates", "eve", "frank"))

        // SPO — all three bound: exact lookup
        val spo = store.query(id("alice"), "likes", id("bob")).toList()
        assertEquals(1, spo.size, "SPO: alice likes bob")
        assertEquals(id("bob"), spo[0].args[1])

        // SP? — subject + predicate bound, object wildcard
        val sp = store.query(id("alice"), "likes", null).toList()
        assertEquals(2, sp.size, "SP?: alice likes {bob, carol}")
        assertEquals(setOf(id("bob"), id("carol")), sp.map { it.args[1] }.toSet())

        // S?? — subject only
        val s = store.query(id("alice"), null, null).toList()
        assertEquals(2, s.size, "S??: alice likes {bob, carol}")

        // ?P? — predicate only
        val p = store.query(null, "likes", null).toList()
        assertEquals(3, p.size, "?P?: all likes facts")

        // ?PO — predicate + object bound, subject wildcard
        val po = store.query(null, "likes", id("bob")).toList()
        assertEquals(2, po.size, "?PO: {alice, dave} like bob")
        assertEquals(setOf(id("alice"), id("dave")), po.map { it.args[0] }.toSet())

        // ??O — object only
        val o = store.query(null, null, id("bob")).toList()
        assertEquals(2, o.size, "??O: {alice, dave} like bob (any pred)")

        // S?O — subject + object, predicate wildcard
        val so = store.query(id("alice"), null, id("bob")).toList()
        assertEquals(1, so.size, "S?O: alice ? bob (just one fact)")
    }

    @Test
    fun `predicate-only query across multiple predicates isolates correctly`() {
        val store = Hexastore()
        store.add(fact("A", "x", "y"))
        store.add(fact("A", "x", "z"))
        store.add(fact("B", "x", "y"))

        val aResults = store.query(null, "A", null).toList()
        assertEquals(2, aResults.size, "?P? for A should return 2")
        assertTrue(aResults.all { it.predicate == "A" })

        val bResults = store.query(null, "B", null).toList()
        assertEquals(1, bResults.size, "?P? for B should return 1")
    }

    @Test
    fun `object-only query returns all atoms sharing that object across predicates`() {
        val store = Hexastore()
        store.add(fact("likes", "a", "target"))
        store.add(fact("knows", "b", "target"))
        store.add(fact("likes", "c", "other"))

        val results = store.query(null, null, id("target")).toList()
        assertEquals(2, results.size)
        val preds = results.map { it.predicate }.toSet()
        assertEquals(setOf("likes", "knows"), preds)
    }
}
