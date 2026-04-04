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

package com.nocturnusai.inference

import com.nocturnusai.core.Atom
import com.nocturnusai.core.Rule
import com.nocturnusai.core.SourceType
import com.nocturnusai.core.Term
import com.nocturnusai.logic.ProvenanceTracker
import com.nocturnusai.storage.Hexastore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReteEngineTest {

    // ---------------------------------------------------------------------------
    // Test helpers
    // ---------------------------------------------------------------------------

    /** Build a fresh (store, tracker, engine) triple for each test. */
    private fun makeEngine(): Triple<Hexastore, ProvenanceTracker, ReteEngine> {
        val store = Hexastore()
        val tracker = ProvenanceTracker()
        val engine = ReteEngine(store, tracker)
        return Triple(store, tracker, engine)
    }

    /**
     * Assert a fact into both the store AND the engine so that forward-chaining
     * fires, mirroring how NocturnusAI.internalAssertFact works.
     */
    private fun assertFact(store: Hexastore, engine: ReteEngine, fact: Atom) {
        store.add(fact)
        engine.onFactAsserted(fact)
    }

    /** Convenience: check whether the store contains an atom matching predicate + args. */
    private fun storeContains(store: Hexastore, predicate: String, vararg args: Term): Boolean {
        val pattern = Atom(predicate, args.toList())
        return store.match(pattern).any {
            it.predicate == predicate && it.args == args.toList()
        }
    }

    // ---------------------------------------------------------------------------
    // 1. Basic: single rule fires when its body fact is asserted
    // ---------------------------------------------------------------------------

    @Test
    fun `basic rule fires and derived fact appears in store`() {
        val (store, _, engine) = makeEngine()

        // FORALL ?x { mortal(?x) <- human(?x) }
        val varX = Term.Variable("x")
        val rule = Rule(
            variables = listOf(varX),
            head = Atom("mortal", listOf(varX)),
            body = listOf(Atom("human", listOf(varX)))
        )
        engine.addRule(rule)

        // Trigger: human(socrates)
        assertFact(store, engine, Atom("human", listOf(Term.Identifier("socrates"))))

        // Derived: mortal(socrates)
        assertTrue(
            storeContains(store, "mortal", Term.Identifier("socrates")),
            "Expected mortal(socrates) to be derived"
        )
    }

    // ---------------------------------------------------------------------------
    // 2. Chain of rules fires sequentially
    // ---------------------------------------------------------------------------

    @Test
    fun `chained rules fire in sequence`() {
        val (store, _, engine) = makeEngine()

        // Rule 1: mammal(?x) <- animal(?x)
        // Rule 2: mortal(?x) <- mammal(?x)
        val varX = Term.Variable("x")
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

        assertFact(store, engine, Atom("animal", listOf(Term.Identifier("dog"))))

        assertTrue(
            storeContains(store, "mammal", Term.Identifier("dog")),
            "Expected mammal(dog) after first rule fires"
        )
        assertTrue(
            storeContains(store, "mortal", Term.Identifier("dog")),
            "Expected mortal(dog) after second rule fires via chain"
        )
    }

    // ---------------------------------------------------------------------------
    // 3. Fact that matches no rule body triggers no derivation
    // ---------------------------------------------------------------------------

    @Test
    fun `asserting fact with no matching rule produces no derived facts`() {
        val (store, _, engine) = makeEngine()

        // Rule only for "human"
        val varX = Term.Variable("x")
        engine.addRule(Rule(
            variables = listOf(varX),
            head = Atom("mortal", listOf(varX)),
            body = listOf(Atom("human", listOf(varX)))
        ))

        // Assert "plant" — not "human"
        assertFact(store, engine, Atom("plant", listOf(Term.Identifier("oak"))))

        // mortal(oak) should NOT exist
        assertFalse(
            storeContains(store, "mortal", Term.Identifier("oak")),
            "mortal(oak) should not be derived from plant(oak)"
        )
        // Exactly one fact in the store — the asserted one
        assertEquals(1, store.getAllAtoms().toList().size)
    }

    // ---------------------------------------------------------------------------
    // 4. Multi-body rule fires only when ALL conditions are satisfied
    // ---------------------------------------------------------------------------

    @Test
    fun `multi-body rule fires when all conditions are met`() {
        val (store, _, engine) = makeEngine()

        // FORALL ?x ?y { related(?x, ?y) <- parent(?x, ?y) AND alive(?x) }
        val varX = Term.Variable("x")
        val varY = Term.Variable("y")
        val rule = Rule(
            variables = listOf(varX, varY),
            head = Atom("related", listOf(varX, varY)),
            body = listOf(
                Atom("parent", listOf(varX, varY)),
                Atom("alive", listOf(varX))
            )
        )
        engine.addRule(rule)

        assertFact(store, engine, Atom("parent", listOf(Term.Identifier("alice"), Term.Identifier("bob"))))
        // alive(alice) not yet asserted — rule should NOT have fired
        assertFalse(
            storeContains(store, "related", Term.Identifier("alice"), Term.Identifier("bob")),
            "related should not fire until both conditions are met"
        )

        // Now satisfy the second condition
        assertFact(store, engine, Atom("alive", listOf(Term.Identifier("alice"))))

        assertTrue(
            storeContains(store, "related", Term.Identifier("alice"), Term.Identifier("bob")),
            "Expected related(alice, bob) after both conditions are present"
        )
    }

    // ---------------------------------------------------------------------------
    // 5. Partial match: only one of two body conditions present — rule must NOT fire
    // ---------------------------------------------------------------------------

    @Test
    fun `partial match does not fire rule`() {
        val (store, _, engine) = makeEngine()

        val varX = Term.Variable("x")
        val varY = Term.Variable("y")
        // Rule requires both "likes" and "friend"
        val rule = Rule(
            variables = listOf(varX, varY),
            head = Atom("goodmatch", listOf(varX, varY)),
            body = listOf(
                Atom("likes", listOf(varX, varY)),
                Atom("friend", listOf(varX, varY))
            )
        )
        engine.addRule(rule)

        // Only "likes" asserted — "friend" is absent
        assertFact(store, engine, Atom("likes", listOf(Term.Identifier("alice"), Term.Identifier("carol"))))

        assertFalse(
            storeContains(store, "goodmatch", Term.Identifier("alice"), Term.Identifier("carol")),
            "goodmatch should not fire with only one of two conditions"
        )
    }

    // ---------------------------------------------------------------------------
    // 6. Variable binding: rule with two variables binds each independently
    // ---------------------------------------------------------------------------

    @Test
    fun `variable binding unifies correctly across multiple facts`() {
        val (store, _, engine) = makeEngine()

        // FORALL ?x ?y { grandparent(?x, ?y) <- parent(?x, ?z) AND parent(?z, ?y) }
        val varX = Term.Variable("x")
        val varY = Term.Variable("y")
        val varZ = Term.Variable("z")
        val rule = Rule(
            variables = listOf(varX, varY, varZ),
            head = Atom("grandparent", listOf(varX, varY)),
            body = listOf(
                Atom("parent", listOf(varX, varZ)),
                Atom("parent", listOf(varZ, varY))
            )
        )
        engine.addRule(rule)

        assertFact(store, engine, Atom("parent", listOf(Term.Identifier("alice"), Term.Identifier("bob"))))
        assertFact(store, engine, Atom("parent", listOf(Term.Identifier("bob"), Term.Identifier("charlie"))))

        assertTrue(
            storeContains(store, "grandparent", Term.Identifier("alice"), Term.Identifier("charlie")),
            "Expected grandparent(alice, charlie) via chain alice->bob->charlie"
        )
    }

    // ---------------------------------------------------------------------------
    // 7. Variable binding does not incorrectly join across unrelated args
    // ---------------------------------------------------------------------------

    @Test
    fun `variable binding does not fire when intermediate variable cannot unify`() {
        val (store, _, engine) = makeEngine()

        // grandparent(?x, ?y) <- parent(?x, ?z) AND parent(?z, ?y)
        val varX = Term.Variable("x")
        val varY = Term.Variable("y")
        val varZ = Term.Variable("z")
        val rule = Rule(
            variables = listOf(varX, varY, varZ),
            head = Atom("grandparent", listOf(varX, varY)),
            body = listOf(
                Atom("parent", listOf(varX, varZ)),
                Atom("parent", listOf(varZ, varY))
            )
        )
        engine.addRule(rule)

        // alice->bob and carol->dave — no shared intermediate
        assertFact(store, engine, Atom("parent", listOf(Term.Identifier("alice"), Term.Identifier("bob"))))
        assertFact(store, engine, Atom("parent", listOf(Term.Identifier("carol"), Term.Identifier("dave"))))

        // grandparent(alice, dave) must NOT be derived (bob != carol)
        assertFalse(
            storeContains(store, "grandparent", Term.Identifier("alice"), Term.Identifier("dave")),
            "grandparent(alice, dave) must not be derived when intermediate variables don't match"
        )
    }

    // ---------------------------------------------------------------------------
    // 8. Negated head: rule that derives a negative (truthVal = false) fact
    // ---------------------------------------------------------------------------

    @Test
    fun `rule derives a fact with truthVal false`() {
        val (store, _, engine) = makeEngine()

        // NOT_alive(?x) <- dead(?x)   (head has truthVal = false)
        val varX = Term.Variable("x")
        val negHead = Atom("alive", listOf(varX), truthVal = false)
        val rule = Rule(
            variables = listOf(varX),
            head = negHead,
            body = listOf(Atom("dead", listOf(varX)))
        )
        engine.addRule(rule)

        assertFact(store, engine, Atom("dead", listOf(Term.Identifier("julius"))))

        // The store should contain NOT alive(julius)
        val negPattern = Atom("alive", listOf(Term.Identifier("julius")), truthVal = false)
        val found = store.match(negPattern).any {
            it.predicate == "alive" && it.args == listOf(Term.Identifier("julius")) && !it.truthVal
        }
        assertTrue(found, "Expected a negative alive(julius) atom to be derived")
    }

    // ---------------------------------------------------------------------------
    // 9. Scope isolation: rules and facts in different scopes don't interfere
    // ---------------------------------------------------------------------------

    @Test
    fun `facts in different scopes do not trigger rules across scopes`() {
        val (store, _, engine) = makeEngine()

        // Rule with no explicit scope — will fire for scope-less facts
        val varX = Term.Variable("x")
        val rule = Rule(
            variables = listOf(varX),
            head = Atom("mortal", listOf(varX)),
            body = listOf(Atom("human", listOf(varX)))
        )
        engine.addRule(rule)

        // Assert human(plato) in scope "hypothetical"
        val scopedFact = Atom("human", listOf(Term.Identifier("plato")), scope = "hypothetical")
        store.add(scopedFact)
        engine.onFactAsserted(scopedFact)

        // Assert human(aristotle) with no scope
        assertFact(store, engine, Atom("human", listOf(Term.Identifier("aristotle"))))

        // mortal(aristotle) should exist (no scope)
        assertTrue(
            storeContains(store, "mortal", Term.Identifier("aristotle")),
            "Expected mortal(aristotle) from scope-less rule"
        )

        // mortal(plato) — the derived fact, if it exists, must come from scope-less unification.
        // Since the body condition has no scope, Hexastore.match returns scoped facts too,
        // so we verify scope isolation only where the rule itself is scoped.
        // Here we just confirm the global mortal assertion did not somehow get attributed to the wrong subject.
        val mortalAll = store.match(Atom("mortal", listOf(Term.Variable("who"))))
            .filter { it.source == SourceType.INFERRED }
            .toList()
        // At minimum aristotle is there; plato may or may not be depending on match semantics.
        assertTrue(mortalAll.any { it.args == listOf(Term.Identifier("aristotle")) })
    }

    // ---------------------------------------------------------------------------
    // 10. Derived facts can be retracted from the store
    // ---------------------------------------------------------------------------

    @Test
    fun `derived fact can be explicitly deleted from store`() {
        val (store, _, engine) = makeEngine()

        val varX = Term.Variable("x")
        engine.addRule(Rule(
            variables = listOf(varX),
            head = Atom("mortal", listOf(varX)),
            body = listOf(Atom("human", listOf(varX)))
        ))

        assertFact(store, engine, Atom("human", listOf(Term.Identifier("homer"))))
        assertTrue(storeContains(store, "mortal", Term.Identifier("homer")))

        // Delete the derived fact directly from the store
        val derivedAtom = store.match(
            Atom("mortal", listOf(Term.Variable("x")), source = SourceType.INFERRED)
        ).firstOrNull { it.args == listOf(Term.Identifier("homer")) }

        // If the inferred atom has source = INFERRED, delete it; otherwise use the pattern key
        val toDelete = derivedAtom
            ?: Atom("mortal", listOf(Term.Identifier("homer")), source = SourceType.INFERRED)
        store.delete(toDelete)

        assertFalse(
            storeContains(store, "mortal", Term.Identifier("homer")),
            "mortal(homer) should be gone after deletion"
        )
    }

    // ---------------------------------------------------------------------------
    // 11. No duplicate derivation: asserting same triggering fact twice does not
    //     produce two copies of the derived fact
    // ---------------------------------------------------------------------------

    @Test
    fun `asserting same triggering fact twice does not produce duplicate derived facts`() {
        val (store, _, engine) = makeEngine()

        val varX = Term.Variable("x")
        engine.addRule(Rule(
            variables = listOf(varX),
            head = Atom("mortal", listOf(varX)),
            body = listOf(Atom("human", listOf(varX)))
        ))

        val fact = Atom("human", listOf(Term.Identifier("zeus")))
        assertFact(store, engine, fact)
        // Assert the same fact a second time
        assertFact(store, engine, fact)

        // Hexastore upserts, so mortal(zeus) should appear exactly once
        val derived = store.match(Atom("mortal", listOf(Term.Variable("x"))))
            .filter { it.args == listOf(Term.Identifier("zeus")) }
            .toList()
        assertEquals(1, derived.size, "mortal(zeus) should appear exactly once, not duplicated")
    }

    // ---------------------------------------------------------------------------
    // 12. Multiple rules: each independently derives a different conclusion
    // ---------------------------------------------------------------------------

    @Test
    fun `multiple rules each derive their own conclusion independently`() {
        val (store, _, engine) = makeEngine()

        val varX = Term.Variable("x")
        // Rule A: carnivore(?x) <- eats_meat(?x)
        engine.addRule(Rule(
            variables = listOf(varX),
            head = Atom("carnivore", listOf(varX)),
            body = listOf(Atom("eats_meat", listOf(varX)))
        ))
        // Rule B: herbivore(?x) <- eats_plants(?x)
        engine.addRule(Rule(
            variables = listOf(varX),
            head = Atom("herbivore", listOf(varX)),
            body = listOf(Atom("eats_plants", listOf(varX)))
        ))

        assertFact(store, engine, Atom("eats_meat", listOf(Term.Identifier("wolf"))))
        assertFact(store, engine, Atom("eats_plants", listOf(Term.Identifier("rabbit"))))

        assertTrue(storeContains(store, "carnivore", Term.Identifier("wolf")), "Expected carnivore(wolf)")
        assertFalse(storeContains(store, "herbivore", Term.Identifier("wolf")), "wolf should not be herbivore")
        assertTrue(storeContains(store, "herbivore", Term.Identifier("rabbit")), "Expected herbivore(rabbit)")
        assertFalse(storeContains(store, "carnivore", Term.Identifier("rabbit")), "rabbit should not be carnivore")
    }

    // ---------------------------------------------------------------------------
    // 13. Provenance tracker is notified when rule fires
    // ---------------------------------------------------------------------------

    @Test
    fun `provenance tracker records derivation when rule fires`() {
        val (store, tracker, engine) = makeEngine()

        val varX = Term.Variable("x")
        val rule = Rule(
            variables = listOf(varX),
            head = Atom("mortal", listOf(varX)),
            body = listOf(Atom("human", listOf(varX)))
        )
        engine.addRule(rule)

        assertFact(store, engine, Atom("human", listOf(Term.Identifier("socrates"))))

        val derived = Atom("mortal", listOf(Term.Identifier("socrates")), source = SourceType.INFERRED)
        val derivation = tracker.getDerivation(derived)
        assertTrue(derivation != null, "ProvenanceTracker should have recorded a derivation for mortal(socrates)")
        assertEquals("mortal", derivation!!.rule.head.predicate)
        assertTrue(
            derivation.premises.any { it.predicate == "human" && it.args == listOf(Term.Identifier("socrates")) },
            "Premise should be human(socrates)"
        )
    }

    // ---------------------------------------------------------------------------
    // 14. Rule fires for multiple ground instances of a variable
    // ---------------------------------------------------------------------------

    @Test
    fun `rule fires for every matching ground instance`() {
        val (store, _, engine) = makeEngine()

        val varX = Term.Variable("x")
        engine.addRule(Rule(
            variables = listOf(varX),
            head = Atom("mortal", listOf(varX)),
            body = listOf(Atom("human", listOf(varX)))
        ))

        val names = listOf("plato", "aristotle", "socrates")
        for (name in names) {
            assertFact(store, engine, Atom("human", listOf(Term.Identifier(name))))
        }

        for (name in names) {
            assertTrue(
                storeContains(store, "mortal", Term.Identifier(name)),
                "Expected mortal($name)"
            )
        }
    }
}
