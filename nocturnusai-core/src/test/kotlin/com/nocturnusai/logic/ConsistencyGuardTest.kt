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

package com.nocturnusai.logic

import com.nocturnusai.core.Atom
import com.nocturnusai.core.Term
import com.nocturnusai.storage.Hexastore
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFails
import kotlin.test.assertFalse

class ConsistencyGuardTest {

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun makeGuard(): Pair<Hexastore, ConsistencyGuard> {
        val store = Hexastore()
        val guard = ConsistencyGuard(store)
        return Pair(store, guard)
    }

    // ---------------------------------------------------------------------------
    // 1. No constraint added — check() passes for any candidate
    // ---------------------------------------------------------------------------

    @Test
    fun `check passes when no constraints are registered`() {
        val (_, guard) = makeGuard()
        // Should not throw — no constraints at all
        guard.check(Atom("likes", listOf(Term.Identifier("alice"), Term.Identifier("bob"))))
    }

    // ---------------------------------------------------------------------------
    // 2. Constraint not satisfied — no contradiction thrown
    // ---------------------------------------------------------------------------

    @Test
    fun `check does not throw when constraint pattern is not satisfied`() {
        val (store, guard) = makeGuard()

        // Constraint: [{likes(?x, ?y), dislikes(?x, ?y)}]
        // Fires only when BOTH likes and dislikes hold for the same x and y.
        val varX = Term.Variable("x")
        val varY = Term.Variable("y")
        val constraint = Constraint(listOf(
            Atom("likes", listOf(varX, varY)),
            Atom("dislikes", listOf(varX, varY))
        ))
        guard.addConstraint(constraint)

        // Only likes is in the store — adding dislikes would trigger, but we only check likes here
        store.add(Atom("likes", listOf(Term.Identifier("alice"), Term.Identifier("bob"))))

        // Candidate: another likes fact — should not trigger (dislikes doesn't exist)
        guard.check(Atom("likes", listOf(Term.Identifier("carol"), Term.Identifier("dave"))))
    }

    // ---------------------------------------------------------------------------
    // 3. Constraint triggered: candidate + stored facts satisfy all conditions
    // ---------------------------------------------------------------------------

    @Test
    fun `check throws when candidate combined with store satisfies constraint`() {
        val (store, guard) = makeGuard()

        val varX = Term.Variable("x")
        val varY = Term.Variable("y")
        // Constraint: person cannot both like and dislike the same entity
        val constraint = Constraint(listOf(
            Atom("likes", listOf(varX, varY)),
            Atom("dislikes", listOf(varX, varY))
        ))
        guard.addConstraint(constraint)

        // Store already has likes(alice, bob)
        store.add(Atom("likes", listOf(Term.Identifier("alice"), Term.Identifier("bob"))))

        // Candidate: dislikes(alice, bob) — should violate the constraint
        assertFailsWith<IllegalStateException>(
            message = "Adding dislikes(alice, bob) should violate the likes+dislikes constraint"
        ) {
            guard.check(Atom("dislikes", listOf(Term.Identifier("alice"), Term.Identifier("bob"))))
        }
    }

    // ---------------------------------------------------------------------------
    // 4. Candidate alone satisfies a single-atom constraint
    // ---------------------------------------------------------------------------

    @Test
    fun `single-atom constraint fires on the candidate itself`() {
        val (_, guard) = makeGuard()

        // Constraint: forbidden(?x) must never hold
        val varX = Term.Variable("x")
        val constraint = Constraint(listOf(
            Atom("forbidden", listOf(varX))
        ))
        guard.addConstraint(constraint)

        assertFailsWith<IllegalStateException> {
            guard.check(Atom("forbidden", listOf(Term.Identifier("evil_thing"))))
        }
    }

    // ---------------------------------------------------------------------------
    // 5. Constraint with two ground atoms in store + candidate closes the pattern
    // ---------------------------------------------------------------------------

    @Test
    fun `three-atom constraint fires only when all three are present`() {
        val (store, guard) = makeGuard()

        val varX = Term.Variable("x")
        val varY = Term.Variable("y")
        val varZ = Term.Variable("z")
        // Constraint: A -> B -> C forms an invalid cycle when detected together
        val constraint = Constraint(listOf(
            Atom("edge", listOf(varX, varY)),
            Atom("edge", listOf(varY, varZ)),
            Atom("edge", listOf(varZ, varX))
        ))
        guard.addConstraint(constraint)

        store.add(Atom("edge", listOf(Term.Identifier("a"), Term.Identifier("b"))))
        store.add(Atom("edge", listOf(Term.Identifier("b"), Term.Identifier("c"))))

        // Only two edges — no cycle yet
        guard.check(Atom("edge", listOf(Term.Identifier("a"), Term.Identifier("d"))))

        // Closing edge: c -> a would complete a->b->c->a cycle
        assertFailsWith<IllegalStateException>(
            message = "Adding edge(c,a) should complete the 3-cycle constraint"
        ) {
            guard.check(Atom("edge", listOf(Term.Identifier("c"), Term.Identifier("a"))))
        }
    }

    // ---------------------------------------------------------------------------
    // 6. Multiple constraints — each is checked independently
    // ---------------------------------------------------------------------------

    @Test
    fun `multiple constraints are each independently evaluated`() {
        val (store, guard) = makeGuard()

        val varX = Term.Variable("x")

        // Constraint A: forbidden(?x)
        guard.addConstraint(Constraint(listOf(Atom("forbidden", listOf(varX)))))

        // Constraint B: dead(?x) AND alive(?x)
        guard.addConstraint(Constraint(listOf(
            Atom("dead", listOf(varX)),
            Atom("alive", listOf(varX))
        )))

        // Trigger constraint A
        assertFailsWith<IllegalStateException> {
            guard.check(Atom("forbidden", listOf(Term.Identifier("x"))))
        }

        // Trigger constraint B
        store.add(Atom("dead", listOf(Term.Identifier("zombie"))))
        assertFailsWith<IllegalStateException> {
            guard.check(Atom("alive", listOf(Term.Identifier("zombie"))))
        }

        // Unrelated fact — neither constraint fires
        guard.check(Atom("likes", listOf(Term.Identifier("alice"), Term.Identifier("pizza"))))
    }

    // ---------------------------------------------------------------------------
    // 7. Constraint with no variables (ground atoms only)
    // ---------------------------------------------------------------------------

    @Test
    fun `ground constraint fires only for exact match`() {
        val (_, guard) = makeGuard()

        // Exact ground constraint: foo(alpha)
        val constraint = Constraint(listOf(
            Atom("foo", listOf(Term.Identifier("alpha")))
        ))
        guard.addConstraint(constraint)

        // foo(beta) should NOT trigger
        guard.check(Atom("foo", listOf(Term.Identifier("beta"))))

        // foo(alpha) SHOULD trigger
        assertFailsWith<IllegalStateException> {
            guard.check(Atom("foo", listOf(Term.Identifier("alpha"))))
        }
    }

    // ---------------------------------------------------------------------------
    // 8. Constraint involving negative truth value (if supported)
    //    Hexastore stores negatives as "!predicate" internally.
    // ---------------------------------------------------------------------------

    @Test
    fun `constraint can involve a negative (truthVal false) atom`() {
        val (store, guard) = makeGuard()

        val varX = Term.Variable("x")
        // Constraint: NOT allowed(?x) AND guest(?x) => contradiction
        val negAllowed = Atom("allowed", listOf(varX), truthVal = false)
        val constraint = Constraint(listOf(
            negAllowed,
            Atom("guest", listOf(varX))
        ))
        guard.addConstraint(constraint)

        // Store a negative "allowed" atom
        store.add(Atom("allowed", listOf(Term.Identifier("mallory")), truthVal = false))

        // Now candidate guest(mallory) should violate
        assertFailsWith<IllegalStateException> {
            guard.check(Atom("guest", listOf(Term.Identifier("mallory"))))
        }
    }

    // ---------------------------------------------------------------------------
    // 9. Removing / not adding a constraint means violations are not detected
    // ---------------------------------------------------------------------------

    @Test
    fun `no violation detected when constraint was never registered`() {
        val (store, guard) = makeGuard()

        store.add(Atom("likes", listOf(Term.Identifier("alice"), Term.Identifier("bob"))))

        // Guard has NO constraints — dislikes should pass freely
        guard.check(Atom("dislikes", listOf(Term.Identifier("alice"), Term.Identifier("bob"))))
    }

    // ---------------------------------------------------------------------------
    // 10. Adding a constraint and then checking a non-violating atom is safe
    // ---------------------------------------------------------------------------

    @Test
    fun `check is safe for atom that does not satisfy any registered constraint`() {
        val (store, guard) = makeGuard()

        val varX = Term.Variable("x")
        val varY = Term.Variable("y")
        guard.addConstraint(Constraint(listOf(
            Atom("owns", listOf(varX, varY)),
            Atom("stolen", listOf(varY))
        )))

        // stolen(ring) is in the store
        store.add(Atom("stolen", listOf(Term.Identifier("ring"))))

        // owns(alice, watch) — "watch" is not stolen; constraint not satisfied
        guard.check(Atom("owns", listOf(Term.Identifier("alice"), Term.Identifier("watch"))))

        // owns(bob, ring) — "ring" IS stolen; constraint IS satisfied → throw
        assertFailsWith<IllegalStateException> {
            guard.check(Atom("owns", listOf(Term.Identifier("bob"), Term.Identifier("ring"))))
        }
    }
}
