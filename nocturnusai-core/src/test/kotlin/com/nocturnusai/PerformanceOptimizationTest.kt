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

import com.nocturnusai.core.*
import com.nocturnusai.inference.BackwardChainer
import com.nocturnusai.storage.Hexastore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.File
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class PerformanceOptimizationTest {

    private fun createDb(): NocturnusAI {
        val dir = File("build/test-perf-${System.nanoTime()}")
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()
        return NocturnusAI(dir)
    }

    // --- Optimization 1: Tabling/Memoization ---

    @Test
    fun `tabling produces correct results for recursive ancestor rules`() {
        val db = createDb()
        // Chain: alice -> bob -> charlie -> dave
        db.assertFact(Atom("parent", listOf(Term.Identifier("alice"), Term.Identifier("bob"))))
        db.assertFact(Atom("parent", listOf(Term.Identifier("bob"), Term.Identifier("charlie"))))
        db.assertFact(Atom("parent", listOf(Term.Identifier("charlie"), Term.Identifier("dave"))))

        // ancestor(?x, ?y) <- parent(?x, ?y)
        db.assertRule(Rule(
            variables = listOf(Term.Variable("x"), Term.Variable("y")),
            head = Atom("ancestor", listOf(Term.Variable("x"), Term.Variable("y"))),
            body = listOf(Atom("parent", listOf(Term.Variable("x"), Term.Variable("y"))))
        ))
        // ancestor(?x, ?z) <- parent(?x, ?y) AND ancestor(?y, ?z)
        db.assertRule(Rule(
            variables = listOf(Term.Variable("x"), Term.Variable("y"), Term.Variable("z")),
            head = Atom("ancestor", listOf(Term.Variable("x"), Term.Variable("z"))),
            body = listOf(
                Atom("parent", listOf(Term.Variable("x"), Term.Variable("y"))),
                Atom("ancestor", listOf(Term.Variable("y"), Term.Variable("z")))
            )
        ))

        val query = Atom("ancestor", listOf(Term.Identifier("alice"), Term.Variable("who")))
        val results = db.infer(query).toList()

        val names = results.map { it.args[1].toString() }.toSet()
        assertEquals(setOf("bob", "charlie", "dave"), names)
        db.close()
    }

    @Test
    fun `tabling handles diamond dependencies correctly`() {
        val db = createDb()
        // Diamond: alice -> bob, alice -> carol, bob -> dave, carol -> dave
        db.assertFact(Atom("parent", listOf(Term.Identifier("alice"), Term.Identifier("bob"))))
        db.assertFact(Atom("parent", listOf(Term.Identifier("alice"), Term.Identifier("carol"))))
        db.assertFact(Atom("parent", listOf(Term.Identifier("bob"), Term.Identifier("dave"))))
        db.assertFact(Atom("parent", listOf(Term.Identifier("carol"), Term.Identifier("dave"))))
        // dave -> eve (shared descendant)
        db.assertFact(Atom("parent", listOf(Term.Identifier("dave"), Term.Identifier("eve"))))

        db.assertRule(Rule(
            variables = listOf(Term.Variable("x"), Term.Variable("y")),
            head = Atom("ancestor", listOf(Term.Variable("x"), Term.Variable("y"))),
            body = listOf(Atom("parent", listOf(Term.Variable("x"), Term.Variable("y"))))
        ))
        db.assertRule(Rule(
            variables = listOf(Term.Variable("x"), Term.Variable("y"), Term.Variable("z")),
            head = Atom("ancestor", listOf(Term.Variable("x"), Term.Variable("z"))),
            body = listOf(
                Atom("parent", listOf(Term.Variable("x"), Term.Variable("y"))),
                Atom("ancestor", listOf(Term.Variable("y"), Term.Variable("z")))
            )
        ))

        val query = Atom("ancestor", listOf(Term.Identifier("alice"), Term.Variable("who")))
        val results = db.infer(query).toList()

        // alice is ancestor of bob, carol, dave (via bob and carol), eve (via dave)
        val names = results.map { it.args[1].toString() }.toSet()
        assertTrue("bob" in names, "Expected bob in results")
        assertTrue("carol" in names, "Expected carol in results")
        assertTrue("dave" in names, "Expected dave in results")
        assertTrue("eve" in names, "Expected eve in results")
        assertEquals(4, names.size, "Expected exactly 4 unique descendants, got: $names")
        db.close()
    }

    // --- Optimization 2: Rule Indexing ---

    @Test
    fun `rule indexing only matches rules with matching head predicate`() {
        val store = Hexastore()
        store.add(Atom("parent", listOf(Term.Identifier("alice"), Term.Identifier("bob"))))

        // Rule for "ancestor" predicate
        val ancestorRule = Rule(
            variables = listOf(Term.Variable("x"), Term.Variable("y")),
            head = Atom("ancestor", listOf(Term.Variable("x"), Term.Variable("y"))),
            body = listOf(Atom("parent", listOf(Term.Variable("x"), Term.Variable("y"))))
        )
        // Unrelated rule for "sibling" predicate
        val siblingRule = Rule(
            variables = listOf(Term.Variable("x"), Term.Variable("y")),
            head = Atom("sibling", listOf(Term.Variable("x"), Term.Variable("y"))),
            body = listOf(Atom("parent", listOf(Term.Variable("x"), Term.Variable("y"))))
        )

        val chainer = BackwardChainer(store, listOf(ancestorRule, siblingRule))

        // Query for ancestor — should use ancestor rule, not sibling rule
        val results = chainer.solve(Atom("ancestor", listOf(Term.Identifier("alice"), Term.Variable("who")))).toList()
        assertEquals(1, results.size)
        assertEquals("ancestor", results[0].predicate)
        assertEquals(Term.Identifier("bob"), results[0].args[1])

        // Query for sibling — should use sibling rule, not ancestor rule
        val sibResults = chainer.solve(Atom("sibling", listOf(Term.Identifier("alice"), Term.Variable("who")))).toList()
        assertEquals(1, sibResults.size)
        assertEquals("sibling", sibResults[0].predicate)
    }

    @Test
    fun `rule indexing returns fact-only results when no matching rules`() {
        val store = Hexastore()
        store.add(Atom("likes", listOf(Term.Identifier("alice"), Term.Identifier("pizza"))))

        // Rule only for "ancestor" — nothing for "likes"
        val rule = Rule(
            variables = listOf(Term.Variable("x"), Term.Variable("y")),
            head = Atom("ancestor", listOf(Term.Variable("x"), Term.Variable("y"))),
            body = listOf(Atom("parent", listOf(Term.Variable("x"), Term.Variable("y"))))
        )

        val chainer = BackwardChainer(store, listOf(rule))
        val results = chainer.solve(Atom("likes", listOf(Term.Identifier("alice"), Term.Variable("what")))).toList()

        assertEquals(1, results.size)
        assertEquals(Term.Identifier("pizza"), results[0].args[1])
    }

    // --- Optimization 3: StampedLock Correctness ---

    @Test
    fun `concurrent reads and writes do not corrupt hexastore results`() {
        val store = Hexastore()
        val errors = ConcurrentHashMap.newKeySet<String>()
        val iterations = 500
        val barrier = CyclicBarrier(3)

        // Writer thread: adds facts
        val writer = thread {
            barrier.await()
            for (i in 0 until iterations) {
                store.add(Atom("rel", listOf(Term.Identifier("a$i"), Term.Identifier("b$i"))))
            }
        }

        // Reader thread 1: match queries
        val reader1 = thread {
            barrier.await()
            for (i in 0 until iterations) {
                try {
                    val pattern = Atom("rel", listOf(Term.Variable("x"), Term.Variable("y")))
                    store.match(pattern).toList() // should not throw
                } catch (e: Exception) {
                    errors.add("reader1: ${e.message}")
                }
            }
        }

        // Reader thread 2: getAllAtoms
        val reader2 = thread {
            barrier.await()
            for (i in 0 until iterations) {
                try {
                    store.getAllAtoms().toList() // should not throw
                } catch (e: Exception) {
                    errors.add("reader2: ${e.message}")
                }
            }
        }

        writer.join()
        reader1.join()
        reader2.join()

        assertTrue(errors.isEmpty(), "Concurrent access errors: $errors")
        // Verify all facts were stored
        val allAtoms = store.getAllAtoms().toList()
        assertEquals(iterations, allAtoms.size, "Expected $iterations atoms after concurrent writes")
    }

    // --- Optimization 4: Variable Renaming Uniqueness ---

    @Test
    fun `concurrent solve calls produce distinct variable names`() {
        val store = Hexastore()
        store.add(Atom("parent", listOf(Term.Identifier("alice"), Term.Identifier("bob"))))

        val rule = Rule(
            variables = listOf(Term.Variable("x"), Term.Variable("y")),
            head = Atom("ancestor", listOf(Term.Variable("x"), Term.Variable("y"))),
            body = listOf(Atom("parent", listOf(Term.Variable("x"), Term.Variable("y"))))
        )

        val results = ConcurrentHashMap.newKeySet<String>()
        val threads = (1..10).map {
            thread {
                val chainer = BackwardChainer(store, listOf(rule))
                val res = chainer.solve(Atom("ancestor", listOf(Term.Identifier("alice"), Term.Variable("who")))).toList()
                res.forEach { results.add(it.toString()) }
            }
        }
        threads.forEach { it.join() }

        // All 10 threads should produce the same single result
        assertEquals(1, results.size, "All threads should find ancestor(alice, bob), got: $results")
        assertTrue(results.first().contains("bob"), "Result should contain bob")
    }

    // --- Integration: all optimizations working together ---

    @Test
    fun `deep recursive chain completes with memoization`() {
        val db = createDb()

        // Build a chain of 15 levels: person0 -> person1 -> ... -> person14
        for (i in 0 until 14) {
            db.assertFact(Atom("parent", listOf(Term.Identifier("person$i"), Term.Identifier("person${i + 1}"))))
        }

        db.assertRule(Rule(
            variables = listOf(Term.Variable("x"), Term.Variable("y")),
            head = Atom("ancestor", listOf(Term.Variable("x"), Term.Variable("y"))),
            body = listOf(Atom("parent", listOf(Term.Variable("x"), Term.Variable("y"))))
        ))
        db.assertRule(Rule(
            variables = listOf(Term.Variable("x"), Term.Variable("y"), Term.Variable("z")),
            head = Atom("ancestor", listOf(Term.Variable("x"), Term.Variable("z"))),
            body = listOf(
                Atom("parent", listOf(Term.Variable("x"), Term.Variable("y"))),
                Atom("ancestor", listOf(Term.Variable("y"), Term.Variable("z")))
            )
        ))

        val query = Atom("ancestor", listOf(Term.Identifier("person0"), Term.Variable("who")))
        val results = db.infer(query).toList()

        // person0 is ancestor of person1 through person14
        val names = results.map { it.args[1].toString() }.toSet()
        assertEquals(14, names.size, "Expected 14 descendants, got ${names.size}: $names")
        for (i in 1..14) {
            assertTrue("person$i" in names, "Expected person$i in results")
        }
        db.close()
    }
}
