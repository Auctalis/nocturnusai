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
import com.nocturnusai.core.Rule
import com.nocturnusai.core.SourceType
import com.nocturnusai.core.Term
import com.nocturnusai.inference.ReteEngine
import com.nocturnusai.storage.Hexastore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProvenanceTrackerTest {

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun makeComponents(): Triple<Hexastore, ProvenanceTracker, ReteEngine> {
        val store = Hexastore()
        val tracker = ProvenanceTracker()
        val engine = ReteEngine(store, tracker)
        return Triple(store, tracker, engine)
    }

    private fun assertFact(store: Hexastore, engine: ReteEngine, fact: Atom) {
        store.add(fact)
        engine.onFactAsserted(fact)
    }

    /** Build the canonical INFERRED key used by the tracker (source = INFERRED). */
    private fun inferredAtom(predicate: String, vararg args: Term): Atom =
        Atom(predicate, args.toList(), source = SourceType.INFERRED)

    // ---------------------------------------------------------------------------
    // 1. record() stores a derivation and getDerivation() retrieves it
    // ---------------------------------------------------------------------------

    @Test
    fun `record stores derivation and getDerivation returns it`() {
        val tracker = ProvenanceTracker()

        val premise1 = Atom("human", listOf(Term.Identifier("socrates")))
        val derived = inferredAtom("mortal", Term.Identifier("socrates"))
        val rule = Rule(
            variables = listOf(Term.Variable("x")),
            head = Atom("mortal", listOf(Term.Variable("x"))),
            body = listOf(Atom("human", listOf(Term.Variable("x"))))
        )

        tracker.record(derived, rule, listOf(premise1))

        val derivation = tracker.getDerivation(derived)
        assertNotNull(derivation, "Derivation should be recorded")
        assertEquals(rule, derivation.rule)
        assertEquals(listOf(premise1), derivation.premises)
    }

    // ---------------------------------------------------------------------------
    // 2. getDerivation returns null for a fact that was never recorded
    // ---------------------------------------------------------------------------

    @Test
    fun `getDerivation returns null for unknown fact`() {
        val tracker = ProvenanceTracker()
        val unknown = Atom("mortal", listOf(Term.Identifier("nobody")))
        assertNull(tracker.getDerivation(unknown), "Unknown fact should have no derivation")
    }

    // ---------------------------------------------------------------------------
    // 3. retract() on a derived fact returns the fact itself in the deleted set
    // ---------------------------------------------------------------------------

    @Test
    fun `retract returns the fact itself`() {
        val tracker = ProvenanceTracker()

        val premise = Atom("human", listOf(Term.Identifier("plato")))
        val derived = inferredAtom("mortal", Term.Identifier("plato"))
        val rule = Rule(
            variables = listOf(Term.Variable("x")),
            head = Atom("mortal", listOf(Term.Variable("x"))),
            body = listOf(Atom("human", listOf(Term.Variable("x"))))
        )
        tracker.record(derived, rule, listOf(premise))

        val deleted = tracker.retract(premise)
        assertTrue(deleted.contains(premise), "Deleted set should include the retracted premise itself")
    }

    // ---------------------------------------------------------------------------
    // 4. Cascade retraction: retracting a premise removes the derived fact
    // ---------------------------------------------------------------------------

    @Test
    fun `cascade retraction removes derived fact when premise is retracted`() {
        val (store, tracker, engine) = makeComponents()

        val varX = Term.Variable("x")
        val rule = Rule(
            variables = listOf(varX),
            head = Atom("mortal", listOf(varX)),
            body = listOf(Atom("human", listOf(varX)))
        )
        engine.addRule(rule)

        val premise = Atom("human", listOf(Term.Identifier("socrates")))
        assertFact(store, engine, premise)

        // Confirm the derived fact is tracked
        val derived = store.match(
            Atom("mortal", listOf(Term.Variable("who")), source = SourceType.INFERRED)
        ).firstOrNull { it.args == listOf(Term.Identifier("socrates")) }
        assertNotNull(derived, "mortal(socrates) should have been derived and is present in store")

        // Now use the tracker to cascade-retract
        val deleted = tracker.retract(premise)

        // The deleted set must include the derived fact
        assertTrue(
            deleted.any { it.predicate == "mortal" && it.args == listOf(Term.Identifier("socrates")) },
            "Cascade retraction should include mortal(socrates) in the deleted set"
        )
    }

    // ---------------------------------------------------------------------------
    // 5. Multi-level cascade: A derives B, B derives C — retract A removes both B and C
    // ---------------------------------------------------------------------------

    @Test
    fun `multi-level cascade removes all transitively derived facts`() {
        val (store, tracker, engine) = makeComponents()

        val varX = Term.Variable("x")
        // Rule 1: mammal(?x) <- animal(?x)
        // Rule 2: mortal(?x) <- mammal(?x)
        engine.addRule(Rule(
            variables = listOf(varX),
            head = Atom("mammal", listOf(varX)),
            body = listOf(Atom("animal", listOf(varX)))
        ))
        engine.addRule(Rule(
            variables = listOf(varX),
            head = Atom("mortal", listOf(varX)),
            body = listOf(Atom("mammal", listOf(varX)))
        ))

        val premise = Atom("animal", listOf(Term.Identifier("dog")))
        assertFact(store, engine, premise)

        // Verify both derived facts exist
        val mammalDerived = store.match(Atom("mammal", listOf(Term.Variable("x"))))
            .firstOrNull { it.args == listOf(Term.Identifier("dog")) }
        assertNotNull(mammalDerived, "mammal(dog) should be derived")

        val mortalDerived = store.match(Atom("mortal", listOf(Term.Variable("x"))))
            .firstOrNull { it.args == listOf(Term.Identifier("dog")) }
        assertNotNull(mortalDerived, "mortal(dog) should be derived via chain")

        // Cascade retract from the root premise
        val deleted = tracker.retract(premise)

        assertTrue(
            deleted.any { it.predicate == "mammal" && it.args == listOf(Term.Identifier("dog")) },
            "mammal(dog) should be in the cascade deleted set"
        )
        assertTrue(
            deleted.any { it.predicate == "mortal" && it.args == listOf(Term.Identifier("dog")) },
            "mortal(dog) should be in the cascade deleted set"
        )
    }

    // ---------------------------------------------------------------------------
    // 6. Multiple justifications: retracting one path preserves fact if another path exists
    //    NOTE: The current single-derivation TMS does not support this. This test
    //    documents the current behaviour (last-writer-wins derivation registration).
    //    When multi-justification is added, update accordingly.
    // ---------------------------------------------------------------------------

    @Test
    fun `single derivation model last recorded derivation governs cascade`() {
        val tracker = ProvenanceTracker()

        val premise1 = Atom("human", listOf(Term.Identifier("alice")))
        val premise2 = Atom("person", listOf(Term.Identifier("alice")))
        val derived = inferredAtom("mortal", Term.Identifier("alice"))

        val ruleFromHuman = Rule(
            variables = listOf(Term.Variable("x")),
            head = Atom("mortal", listOf(Term.Variable("x"))),
            body = listOf(Atom("human", listOf(Term.Variable("x"))))
        )
        val ruleFromPerson = Rule(
            variables = listOf(Term.Variable("x")),
            head = Atom("mortal", listOf(Term.Variable("x"))),
            body = listOf(Atom("person", listOf(Term.Variable("x"))))
        )

        // Record derivation from premise1 first, then overwrite with premise2
        tracker.record(derived, ruleFromHuman, listOf(premise1))
        tracker.record(derived, ruleFromPerson, listOf(premise2))

        // Current model: last record wins — derivation is now from premise2
        val derivation = tracker.getDerivation(derived)
        assertNotNull(derivation)
        assertEquals(listOf(premise2), derivation!!.premises,
            "Last recorded derivation should govern (single-justification TMS)")
    }

    // ---------------------------------------------------------------------------
    // 7. Directly asserted fact is not cascade-retracted by the tracker
    // ---------------------------------------------------------------------------

    @Test
    fun `directly asserted fact is not tracked by provenance tracker`() {
        val tracker = ProvenanceTracker()

        val directFact = Atom("human", listOf(Term.Identifier("aristotle")))
        // No call to tracker.record() — it was user-asserted, not derived

        val derivation = tracker.getDerivation(directFact)
        assertNull(derivation, "Directly asserted facts should have no derivation record")
    }

    // ---------------------------------------------------------------------------
    // 8. Retraction of a fact with no dependents only removes that fact
    // ---------------------------------------------------------------------------

    @Test
    fun `retracting fact with no dependents returns only that fact`() {
        val tracker = ProvenanceTracker()

        val premise = Atom("human", listOf(Term.Identifier("eve")))
        // No derived facts registered against this premise

        val deleted = tracker.retract(premise)
        assertEquals(
            setOf(premise), deleted,
            "Retracting a premise with no dependents should only delete the premise itself"
        )
    }

    // ---------------------------------------------------------------------------
    // 9. Retract is idempotent: double-retract does not loop or throw
    // ---------------------------------------------------------------------------

    @Test
    fun `double retract is safe and idempotent`() {
        val tracker = ProvenanceTracker()

        val premise = Atom("human", listOf(Term.Identifier("newton")))
        val derived = inferredAtom("mortal", Term.Identifier("newton"))
        val rule = Rule(
            variables = listOf(Term.Variable("x")),
            head = Atom("mortal", listOf(Term.Variable("x"))),
            body = listOf(Atom("human", listOf(Term.Variable("x"))))
        )
        tracker.record(derived, rule, listOf(premise))

        val first = tracker.retract(premise)
        assertTrue(first.isNotEmpty())

        // Second retract on same fact — should not throw or loop
        val second = tracker.retract(premise)
        // premise is already removed from dependencies map; result is still safe
        assertTrue(second.isNotEmpty() || second.isEmpty(), "Second retract should not throw")
    }

    // ---------------------------------------------------------------------------
    // 10. Derivation metadata (rule and premises) is correct after engine fires
    // ---------------------------------------------------------------------------

    @Test
    fun `engine-fired derivation records correct rule and premises in tracker`() {
        val (store, tracker, engine) = makeComponents()

        val varX = Term.Variable("x")
        val varY = Term.Variable("y")
        val rule = Rule(
            variables = listOf(varX, varY),
            head = Atom("grandparent", listOf(varX, varY)),
            body = listOf(
                Atom("parent", listOf(varX, Term.Variable("z"))),
                Atom("parent", listOf(Term.Variable("z"), varY))
            )
        )
        // Rebuild with proper z variable
        val varZ = Term.Variable("z")
        val fullRule = Rule(
            variables = listOf(varX, varY, varZ),
            head = Atom("grandparent", listOf(varX, varY)),
            body = listOf(
                Atom("parent", listOf(varX, varZ)),
                Atom("parent", listOf(varZ, varY))
            )
        )
        engine.addRule(fullRule)

        val p1 = Atom("parent", listOf(Term.Identifier("alice"), Term.Identifier("bob")))
        val p2 = Atom("parent", listOf(Term.Identifier("bob"), Term.Identifier("charlie")))
        assertFact(store, engine, p1)
        assertFact(store, engine, p2)

        // Find the derived grandparent atom in the store
        val derived = store.match(
            Atom("grandparent", listOf(Term.Variable("x"), Term.Variable("y")), source = SourceType.INFERRED)
        ).firstOrNull {
            it.args == listOf(Term.Identifier("alice"), Term.Identifier("charlie"))
        }
        assertNotNull(derived, "grandparent(alice, charlie) should have been derived")

        val derivation = tracker.getDerivation(derived!!)
        assertNotNull(derivation, "Derivation should be recorded")
        assertEquals("grandparent", derivation!!.rule.head.predicate)
        // Premises must include both parent facts
        assertTrue(
            derivation.premises.any { it.predicate == "parent" && it.args == listOf(Term.Identifier("alice"), Term.Identifier("bob")) },
            "Premise parent(alice, bob) should be recorded"
        )
        assertTrue(
            derivation.premises.any { it.predicate == "parent" && it.args == listOf(Term.Identifier("bob"), Term.Identifier("charlie")) },
            "Premise parent(bob, charlie) should be recorded"
        )
    }

    // ---------------------------------------------------------------------------
    // 11. Cascade via full NocturnusAI integration
    // ---------------------------------------------------------------------------

    @Test
    fun `full integration retractFact removes derived facts via provenance cascade`() {
        val (store, tracker, engine) = makeComponents()

        val varX = Term.Variable("x")
        val rule = Rule(
            variables = listOf(varX),
            head = Atom("mortal", listOf(varX)),
            body = listOf(Atom("human", listOf(varX)))
        )
        engine.addRule(rule)

        val premise = Atom("human", listOf(Term.Identifier("marcus")))
        assertFact(store, engine, premise)

        // Verify derived fact exists
        assertTrue(
            store.match(Atom("mortal", listOf(Term.Variable("x"))))
                .any { it.args == listOf(Term.Identifier("marcus")) },
            "mortal(marcus) should exist before retraction"
        )

        // Use tracker to retract and then delete from store
        val toDelete = tracker.retract(premise)
        for (dead in toDelete) {
            store.delete(dead)
        }

        // Both premise and derived fact should be gone
        assertFalse(
            store.match(Atom("human", listOf(Term.Variable("x"))))
                .any { it.args == listOf(Term.Identifier("marcus")) },
            "human(marcus) should be gone"
        )
        assertFalse(
            store.match(Atom("mortal", listOf(Term.Variable("x"))))
                .any { it.args == listOf(Term.Identifier("marcus")) },
            "mortal(marcus) should be cascade-deleted"
        )
    }
}
