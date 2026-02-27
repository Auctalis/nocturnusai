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
import com.nocturnusai.core.MergeStrategy
import com.nocturnusai.core.Term
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun id(name: String) = Term.Identifier(name)
private fun variable(name: String) = Term.Variable(name)

private fun fact(pred: String, s: String, o: String, scope: String? = null) =
    Atom(pred, listOf(id(s), id(o)), truthVal = true, scope = scope)

private fun negFact(pred: String, s: String, o: String, scope: String? = null) =
    Atom(pred, listOf(id(s), id(o)), truthVal = false, scope = scope)

private fun Hexastore.allInScope(scope: String?) =
    getAllAtoms().filter { it.scope == scope }.toList()

// ─────────────────────────────────────────────────────────────────────────────
// listScopes
// ─────────────────────────────────────────────────────────────────────────────

class ScopeListTest {

    @Test
    fun `listScopes returns empty set when no named scopes exist`() {
        val store = Hexastore()
        store.add(fact("rel", "a", "b")) // global scope
        assertTrue(store.listScopes().isEmpty(), "Global facts should not appear in listScopes")
    }

    @Test
    fun `listScopes returns all distinct scope names`() {
        val store = Hexastore()
        store.add(fact("rel", "a", "b", scope = "alpha"))
        store.add(fact("rel", "c", "d", scope = "beta"))
        store.add(fact("rel", "e", "f", scope = "alpha")) // duplicate scope
        assertEquals(setOf("alpha", "beta"), store.listScopes())
    }

    @Test
    fun `listScopes does not include null scope`() {
        val store = Hexastore()
        store.add(fact("rel", "a", "b", scope = null))
        store.add(fact("rel", "c", "d", scope = "gamma"))
        assertEquals(setOf("gamma"), store.listScopes())
    }

    @Test
    fun `listScopes reflects deletions`() {
        val store = Hexastore()
        val a = fact("rel", "x", "y", scope = "s1")
        store.add(a)
        store.add(fact("rel", "p", "q", scope = "s2"))
        assertEquals(setOf("s1", "s2"), store.listScopes())

        store.delete(a)
        assertEquals(setOf("s2"), store.listScopes())
    }

    @Test
    fun `listScopes includes scopes from non-binary fallback store`() {
        val store = Hexastore()
        store.add(Atom("tag", listOf(id("item")), scope = "ctx1"))
        assertEquals(setOf("ctx1"), store.listScopes())
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// forkScope
// ─────────────────────────────────────────────────────────────────────────────

class ScopeForkTest {

    @Test
    fun `fork from global scope copies all unscoped facts`() {
        val store = Hexastore()
        store.add(fact("likes", "alice", "bob"))
        store.add(fact("knows", "carol", "dave"))

        val copied = store.forkScope(null, "hypothesis-1")
        assertEquals(2, copied)

        val inHyp = store.allInScope("hypothesis-1")
        assertEquals(2, inHyp.size)
        assertTrue(inHyp.all { it.scope == "hypothesis-1" })
    }

    @Test
    fun `fork preserves all fact properties`() {
        val store = Hexastore()
        val original = Atom("likes", listOf(id("alice"), id("bob")), truthVal = true, createdAt = 1000L, ttl = 5000L)
        store.add(original)

        store.forkScope(null, "hyp")

        val copy = store.allInScope("hyp").single()
        assertEquals("likes", copy.predicate)
        assertEquals(listOf(id("alice"), id("bob")), copy.args)
        assertEquals("hyp", copy.scope)
        assertEquals(1000L, copy.createdAt)
        assertEquals(5000L, copy.ttl)
    }

    @Test
    fun `fork from named scope to another named scope`() {
        val store = Hexastore()
        store.add(fact("p", "a", "b", scope = "src"))
        store.add(fact("p", "c", "d", scope = "src"))
        store.add(fact("p", "e", "f", scope = "other")) // should NOT be copied

        val copied = store.forkScope("src", "dst")
        assertEquals(2, copied)

        val inDst = store.allInScope("dst")
        assertEquals(2, inDst.size)
        val inOther = store.allInScope("other")
        assertEquals(1, inOther.size, "Other scope should be unchanged")
    }

    @Test
    fun `fork does not mutate source scope`() {
        val store = Hexastore()
        store.add(fact("rel", "x", "y", scope = "src"))

        store.forkScope("src", "fork")

        val inSrc = store.allInScope("src")
        assertEquals(1, inSrc.size, "Source scope must be unchanged after fork")
    }

    @Test
    fun `fork returns zero when source scope is empty`() {
        val store = Hexastore()
        val copied = store.forkScope("nonexistent", "dst")
        assertEquals(0, copied)
    }

    @Test
    fun `fork into existing scope upserts atoms`() {
        val store = Hexastore()
        store.add(fact("p", "a", "b", scope = null))
        store.add(fact("p", "a", "b", scope = "existing")) // same logical fact, already in target

        val copied = store.forkScope(null, "existing")
        assertEquals(1, copied, "One atom should have been upserted")

        val all = store.allInScope("existing")
        assertEquals(1, all.size, "No duplicates in target after fork")
    }

    @Test
    fun `fork copies negative facts correctly`() {
        val store = Hexastore()
        store.add(negFact("rel", "a", "b"))

        store.forkScope(null, "h1")

        val copy = store.allInScope("h1").single()
        assertEquals(false, copy.truthVal)
    }

    @Test
    fun `fork copies unary fallback atoms`() {
        val store = Hexastore()
        store.add(Atom("alive", listOf(id("alice")), scope = null))

        val copied = store.forkScope(null, "h2")
        assertEquals(1, copied)

        val inH2 = store.getAllAtoms().filter { it.scope == "h2" }.toList()
        assertEquals(1, inH2.size)
        assertEquals("alive", inH2[0].predicate)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// diffScopes
// ─────────────────────────────────────────────────────────────────────────────

class ScopeDiffTest {

    @Test
    fun `diff between identical scopes shows nothing different`() {
        val store = Hexastore()
        store.add(fact("p", "a", "b", scope = "s1"))
        store.add(fact("p", "a", "b", scope = "s2"))

        val diff = store.diffScopes("s1", "s2")
        assertTrue(diff.onlyInA.isEmpty())
        assertTrue(diff.onlyInB.isEmpty())
        assertEquals(1, diff.inBoth.size)
        assertTrue(diff.conflicts.isEmpty())
    }

    @Test
    fun `diff detects atoms only in A`() {
        val store = Hexastore()
        store.add(fact("p", "a", "b", scope = "s1"))

        val diff = store.diffScopes("s1", "s2")
        assertEquals(1, diff.onlyInA.size)
        assertTrue(diff.onlyInB.isEmpty())
        assertTrue(diff.inBoth.isEmpty())
    }

    @Test
    fun `diff detects atoms only in B`() {
        val store = Hexastore()
        store.add(fact("p", "a", "b", scope = "s2"))

        val diff = store.diffScopes("s1", "s2")
        assertTrue(diff.onlyInA.isEmpty())
        assertEquals(1, diff.onlyInB.size)
    }

    @Test
    fun `diff detects conflicts - same pred and args but different truthVal`() {
        val store = Hexastore()
        store.add(fact("p", "a", "b", scope = "s1"))     // truthVal = true
        store.add(negFact("p", "a", "b", scope = "s2"))  // truthVal = false

        val diff = store.diffScopes("s1", "s2")
        assertEquals(1, diff.conflicts.size)
        assertTrue(diff.onlyInA.isEmpty())
        assertTrue(diff.onlyInB.isEmpty())
        val c = diff.conflicts[0]
        assertEquals("p", c.predicate)
        assertEquals(true, c.inA.truthVal)
        assertEquals(false, c.inB.truthVal)
    }

    @Test
    fun `diff between global scope and named scope`() {
        val store = Hexastore()
        store.add(fact("p", "x", "y")) // global
        store.add(fact("p", "x", "z", scope = "branch"))

        val diff = store.diffScopes(null, "branch")
        assertEquals(1, diff.onlyInA.size, "x->y only in global")
        assertEquals(1, diff.onlyInB.size, "x->z only in branch")
    }

    @Test
    fun `diff after fork shows no differences`() {
        val store = Hexastore()
        store.add(fact("p", "a", "b"))
        store.add(fact("q", "c", "d"))

        store.forkScope(null, "copy")

        val diff = store.diffScopes(null, "copy")
        assertTrue(diff.onlyInA.isEmpty())
        assertTrue(diff.onlyInB.isEmpty())
        assertEquals(2, diff.inBoth.size)
        assertTrue(diff.conflicts.isEmpty())
    }

    @Test
    fun `diff after fork then modification shows changes`() {
        val store = Hexastore()
        store.add(fact("p", "a", "b"))
        store.forkScope(null, "branch")

        // Modify branch: add a new fact
        store.add(fact("q", "x", "y", scope = "branch"))

        val diff = store.diffScopes(null, "branch")
        assertEquals(1, diff.inBoth.size)    // p(a,b)
        assertEquals(1, diff.onlyInB.size)   // q(x,y) only in branch
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// mergeScope
// ─────────────────────────────────────────────────────────────────────────────

class ScopeMergeTest {

    @Test
    fun `merge with SOURCE_WINS copies unique source facts to target`() {
        val store = Hexastore()
        store.add(fact("new", "a", "b", scope = "src"))

        val result = store.mergeScope("src", null, MergeStrategy.SOURCE_WINS)
        assertEquals(1, result.merged)
        assertEquals(0, result.conflictsResolved)

        val inGlobal = store.allInScope(null)
        assertEquals(1, inGlobal.size)
    }

    @Test
    fun `merge SOURCE_WINS resolves conflicts by overwriting target`() {
        val store = Hexastore()
        store.add(fact("p", "a", "b"))               // global: true
        store.add(negFact("p", "a", "b", scope = "src")) // src: false

        val result = store.mergeScope("src", null, MergeStrategy.SOURCE_WINS)
        assertEquals(1, result.conflictsResolved)

        val inGlobal = store.getAllAtoms().filter { it.scope == null }.toList()
        assertEquals(1, inGlobal.size)
        assertEquals(false, inGlobal[0].truthVal, "SOURCE_WINS should have replaced the global fact")
    }

    @Test
    fun `merge TARGET_WINS keeps target fact on conflict`() {
        val store = Hexastore()
        store.add(fact("p", "a", "b"))               // global: true
        store.add(negFact("p", "a", "b", scope = "src")) // src: false

        val result = store.mergeScope("src", null, MergeStrategy.TARGET_WINS)
        assertEquals(1, result.conflictsResolved)

        val inGlobal = store.getAllAtoms().filter { it.scope == null }.toList()
        assertEquals(1, inGlobal.size)
        assertEquals(true, inGlobal[0].truthVal, "TARGET_WINS should have kept the existing global fact")
    }

    @Test
    fun `merge KEEP_BOTH retains both versions`() {
        val store = Hexastore()
        store.add(fact("p", "a", "b"))               // global: true
        store.add(negFact("p", "a", "b", scope = "src")) // src: false

        val result = store.mergeScope("src", null, MergeStrategy.KEEP_BOTH)
        assertEquals(1, result.conflictsResolved)

        val inGlobal = store.getAllAtoms().filter { it.scope == null }.toList()
        assertEquals(2, inGlobal.size, "KEEP_BOTH should store both versions")
    }

    @Test
    fun `merge REJECT throws on conflict`() {
        val store = Hexastore()
        store.add(fact("p", "a", "b"))               // global: true
        store.add(negFact("p", "a", "b", scope = "src")) // src: false

        assertFailsWith<IllegalStateException> {
            store.mergeScope("src", null, MergeStrategy.REJECT)
        }

        // Target should be unchanged
        val inGlobal = store.getAllAtoms().filter { it.scope == null }.toList()
        assertEquals(1, inGlobal.size)
        assertEquals(true, inGlobal[0].truthVal)
    }

    @Test
    fun `merge REJECT succeeds when no conflicts exist`() {
        val store = Hexastore()
        store.add(fact("p", "a", "b", scope = "src"))

        val result = store.mergeScope("src", null, MergeStrategy.REJECT)
        assertEquals(1, result.merged)
        assertEquals(0, result.conflictsResolved)
    }

    @Test
    fun `merge into named target scope`() {
        val store = Hexastore()
        store.add(fact("p", "a", "b", scope = "src"))

        val result = store.mergeScope("src", "dst", MergeStrategy.SOURCE_WINS)
        assertEquals(1, result.merged)

        val inDst = store.allInScope("dst")
        assertEquals(1, inDst.size)
        assertEquals("dst", inDst[0].scope)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// deleteScope
// ─────────────────────────────────────────────────────────────────────────────

class ScopeDeleteTest {

    @Test
    fun `deleteScope removes all atoms in that scope`() {
        val store = Hexastore()
        store.add(fact("p", "a", "b", scope = "del-me"))
        store.add(fact("q", "c", "d", scope = "del-me"))

        val deleted = store.deleteScope("del-me")
        assertEquals(2, deleted)
        assertTrue(store.allInScope("del-me").isEmpty())
    }

    @Test
    fun `deleteScope does not affect other scopes`() {
        val store = Hexastore()
        store.add(fact("p", "a", "b", scope = "keep"))
        store.add(fact("p", "c", "d", scope = "gone"))

        store.deleteScope("gone")

        assertEquals(1, store.allInScope("keep").size, "Other scope must be untouched")
    }

    @Test
    fun `deleteScope does not affect global scope`() {
        val store = Hexastore()
        store.add(fact("p", "a", "b"))              // global
        store.add(fact("p", "c", "d", scope = "tmp"))

        store.deleteScope("tmp")

        assertEquals(1, store.allInScope(null).size, "Global scope must be untouched")
    }

    @Test
    fun `deleteScope returns zero for nonexistent scope`() {
        val store = Hexastore()
        val deleted = store.deleteScope("ghost")
        assertEquals(0, deleted)
    }

    @Test
    fun `deleteScope removes scope from listScopes`() {
        val store = Hexastore()
        store.add(fact("p", "a", "b", scope = "s1"))
        store.add(fact("q", "c", "d", scope = "s2"))

        store.deleteScope("s1")

        assertEquals(setOf("s2"), store.listScopes())
    }

    @Test
    fun `deleteScope removes unary fallback atoms`() {
        val store = Hexastore()
        store.add(Atom("tag", listOf(id("item")), scope = "ctx"))

        val deleted = store.deleteScope("ctx")
        assertEquals(1, deleted)
        assertTrue(store.getAllAtoms().filter { it.scope == "ctx" }.toList().isEmpty())
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// End-to-end workflow
// ─────────────────────────────────────────────────────────────────────────────

class ScopeWorkflowTest {

    @Test
    fun `full workflow fork then modify then diff then merge`() {
        val store = Hexastore()

        // 1. Seed global knowledge
        //    Use a boolean-style fact: "confirmed(alice, proposal)" = true (the default)
        store.add(fact("confirmed", "alice", "proposal")) // true in global
        store.add(fact("likes", "alice", "cheese"))

        // 2. Fork into a hypothesis
        val copied = store.forkScope(null, "h1")
        assertEquals(2, copied)

        // 3. In the hypothesis, retract the confirmed fact so it becomes negated
        store.delete(fact("confirmed", "alice", "proposal", scope = "h1"))
        store.add(negFact("confirmed", "alice", "proposal", scope = "h1"))

        // 4. Diff should show a conflict (same pred+args, different truthVal)
        val diff = store.diffScopes(null, "h1")
        assertEquals(0, diff.onlyInA.size)
        assertEquals(0, diff.onlyInB.size)
        assertEquals(1, diff.inBoth.size, "likes cheese is unchanged")
        assertEquals(1, diff.conflicts.size, "confirmed is in conflict")

        val conflict = diff.conflicts[0]
        assertEquals(true, conflict.inA.truthVal, "global has true")
        assertEquals(false, conflict.inB.truthVal, "hypothesis has false")

        // 5. Merge hypothesis back (SOURCE_WINS: hypothesis overrides global)
        val mergeResult = store.mergeScope("h1", null, MergeStrategy.SOURCE_WINS)
        assertTrue(mergeResult.merged >= 1)
        assertEquals(1, mergeResult.conflictsResolved)

        // Global should now have the negated version from the hypothesis
        val globalFacts = store.allInScope(null)
        val confirmedFacts = globalFacts.filter { it.predicate == "confirmed" }
        assertEquals(1, confirmedFacts.size)
        assertEquals(false, confirmedFacts[0].truthVal, "SOURCE_WINS should have applied the hypothesis value")

        // 6. Clean up hypothesis
        val deleted = store.deleteScope("h1")
        assertTrue(deleted > 0)
        assertTrue(store.listScopes().isEmpty())
    }
}
