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
import com.nocturnusai.core.Rule
import com.nocturnusai.core.SourceType
import com.nocturnusai.core.Term
import com.nocturnusai.inference.BackwardChainer
import com.nocturnusai.inference.ReteEngine
import com.nocturnusai.logic.ProvenanceTracker
import com.nocturnusai.parser.Parser
import com.nocturnusai.parser.Tokenizer
import com.nocturnusai.storage.Hexastore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * Comprehensive tests for Negation-as-Failure (NAF).
 *
 * NAF (closed-world assumption / Prolog \+) is implemented via the `naf=true`
 * flag on Atom.  When a rule body condition carries naf=true the condition
 * succeeds iff the inner atom CANNOT be proven.
 *
 * NAF is distinct from explicit negation (truthVal=false), which is a positive
 * assertion that something is false.
 */
class NafTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun id(name: String) = Term.Identifier(name)
    private fun v(name: String) = Term.Variable(name)

    private fun makeStore() = Hexastore()

    private fun makeChainer(store: Hexastore, vararg rules: Rule): BackwardChainer =
        BackwardChainer(store, rules.toList())

    /** Assert a fact into the store and trigger the Rete engine. */
    private fun assertFact(store: Hexastore, engine: ReteEngine, fact: Atom) {
        store.add(fact)
        engine.onFactAsserted(fact)
    }

    private fun makeReteEngine(store: Hexastore): ReteEngine =
        ReteEngine(store, ProvenanceTracker())

    // -------------------------------------------------------------------------
    // 1. Basic backward-chaining NAF: rule fires when negated condition is absent
    // -------------------------------------------------------------------------

    @Test
    fun `backward chaining - NAF condition succeeds when atom is not provable`() {
        val store = makeStore()

        // Rule: canFly(?x) <- bird(?x) AND NAF penguin(?x)
        val varX = v("x")
        val rule = Rule(
            variables = listOf(varX),
            head = Atom("canFly", listOf(varX)),
            body = listOf(
                Atom("bird", listOf(varX)),
                Atom("penguin", listOf(varX), naf = true)
            )
        )

        // Assert tweety is a bird (NOT a penguin)
        store.add(Atom("bird", listOf(id("tweety"))))

        val chainer = makeChainer(store, rule)
        val results = chainer.solve(Atom("canFly", listOf(v("who")))).toList()

        assertEquals(1, results.size, "Expected exactly one canFly result")
        assertEquals(id("tweety"), results[0].args[0])
    }

    // -------------------------------------------------------------------------
    // 2. NAF blocks rule: condition fails when atom IS provable
    // -------------------------------------------------------------------------

    @Test
    fun `backward chaining - NAF condition fails when atom is provable`() {
        val store = makeStore()

        // Rule: canFly(?x) <- bird(?x) AND NAF penguin(?x)
        val varX = v("x")
        val rule = Rule(
            variables = listOf(varX),
            head = Atom("canFly", listOf(varX)),
            body = listOf(
                Atom("bird", listOf(varX)),
                Atom("penguin", listOf(varX), naf = true)
            )
        )

        // Tweety is both a bird AND a penguin — NAF should block the rule
        store.add(Atom("bird", listOf(id("tweety"))))
        store.add(Atom("penguin", listOf(id("tweety"))))

        val chainer = makeChainer(store, rule)
        val results = chainer.solve(Atom("canFly", listOf(v("who")))).toList()

        assertTrue(results.isEmpty(), "canFly should not fire for a penguin; got $results")
    }

    // -------------------------------------------------------------------------
    // 3. Classic penguin example: birds fly unless they are penguins
    // -------------------------------------------------------------------------

    @Test
    fun `classic penguin example - bird flies, penguin does not`() {
        val store = makeStore()

        val varX = v("x")
        // Rule: flies(?x) <- bird(?x) AND NAF penguin(?x)
        val rule = Rule(
            variables = listOf(varX),
            head = Atom("flies", listOf(varX)),
            body = listOf(
                Atom("bird", listOf(varX)),
                Atom("penguin", listOf(varX), naf = true)
            )
        )

        store.add(Atom("bird", listOf(id("robin"))))
        store.add(Atom("bird", listOf(id("tux"))))
        store.add(Atom("penguin", listOf(id("tux"))))

        val chainer = makeChainer(store, rule)
        val results = chainer.solve(Atom("flies", listOf(v("who")))).toList()

        val resultNames = results.map { it.args[0].toString() }
        assertTrue("robin" in resultNames, "robin should fly")
        assertFalse("tux" in resultNames, "tux (a penguin) should not fly")
    }

    // -------------------------------------------------------------------------
    // 4. NAF with multiple positive conditions AND one NAF condition
    // -------------------------------------------------------------------------

    @Test
    fun `NAF with multiple positive conditions`() {
        val store = makeStore()

        val varX = v("x")
        // Rule: eligible(?x) <- adult(?x) AND employed(?x) AND NAF blacklisted(?x)
        val rule = Rule(
            variables = listOf(varX),
            head = Atom("eligible", listOf(varX)),
            body = listOf(
                Atom("adult", listOf(varX)),
                Atom("employed", listOf(varX)),
                Atom("blacklisted", listOf(varX), naf = true)
            )
        )

        store.add(Atom("adult", listOf(id("alice"))))
        store.add(Atom("employed", listOf(id("alice"))))

        store.add(Atom("adult", listOf(id("bob"))))
        store.add(Atom("employed", listOf(id("bob"))))
        store.add(Atom("blacklisted", listOf(id("bob"))))

        store.add(Atom("adult", listOf(id("charlie")))) // NOT employed

        val chainer = makeChainer(store, rule)
        val results = chainer.solve(Atom("eligible", listOf(v("who")))).toList()

        val names = results.map { it.args[0].toString() }
        assertTrue("alice" in names, "alice should be eligible")
        assertFalse("bob" in names, "bob is blacklisted so not eligible")
        assertFalse("charlie" in names, "charlie is not employed so not eligible")
    }

    // -------------------------------------------------------------------------
    // 5. Multiple NAF conditions in one rule body
    // -------------------------------------------------------------------------

    @Test
    fun `multiple NAF conditions in a single rule`() {
        val store = makeStore()

        val varX = v("x")
        // Rule: safe(?x) <- object(?x) AND NAF dangerous(?x) AND NAF fragile(?x)
        val rule = Rule(
            variables = listOf(varX),
            head = Atom("safe", listOf(varX)),
            body = listOf(
                Atom("object", listOf(varX)),
                Atom("dangerous", listOf(varX), naf = true),
                Atom("fragile", listOf(varX), naf = true)
            )
        )

        store.add(Atom("object", listOf(id("chair"))))   // safe
        store.add(Atom("object", listOf(id("knife"))))   // dangerous
        store.add(Atom("dangerous", listOf(id("knife"))))
        store.add(Atom("object", listOf(id("vase"))))    // fragile
        store.add(Atom("fragile", listOf(id("vase"))))
        store.add(Atom("object", listOf(id("bomb"))))    // both
        store.add(Atom("dangerous", listOf(id("bomb"))))
        store.add(Atom("fragile", listOf(id("bomb"))))

        val chainer = makeChainer(store, rule)
        val results = chainer.solve(Atom("safe", listOf(v("who")))).toList()

        val names = results.map { it.args[0].toString() }
        assertTrue("chair" in names, "chair should be safe")
        assertFalse("knife" in names, "knife is dangerous")
        assertFalse("vase" in names, "vase is fragile")
        assertFalse("bomb" in names, "bomb is dangerous and fragile")
    }

    // -------------------------------------------------------------------------
    // 6. NAF must be ground: unbound variable in NAF condition throws
    // -------------------------------------------------------------------------

    @Test
    fun `NAF with unbound variable throws IllegalStateException`() {
        val store = makeStore()

        val varX = v("x")
        val varY = v("y") // y is never bound by a positive body condition
        // Unsafe rule: NAF body uses ?y which is never bound
        val rule = Rule(
            variables = listOf(varX, varY),
            head = Atom("result", listOf(varX)),
            body = listOf(
                Atom("input", listOf(varX)),
                // ?y is unbound at NAF evaluation time — this is an unsafe rule
                Atom("other", listOf(varY), naf = true)
            )
        )

        store.add(Atom("input", listOf(id("a"))))

        val chainer = makeChainer(store, rule)

        assertFailsWith<IllegalStateException> {
            chainer.solve(Atom("result", listOf(v("who")))).toList()
        }
    }

    // -------------------------------------------------------------------------
    // 7. NAF via backward chaining — atom provable via rule (not just base fact)
    // -------------------------------------------------------------------------

    @Test
    fun `NAF blocks rule when negated atom is derivable via another rule`() {
        val store = makeStore()

        val varX = v("x")
        // Rule 1: dangerous(?x) <- weapon(?x)
        val r1 = Rule(
            variables = listOf(varX),
            head = Atom("dangerous", listOf(varX)),
            body = listOf(Atom("weapon", listOf(varX)))
        )
        // Rule 2: safe(?x) <- item(?x) AND NAF dangerous(?x)
        val r2 = Rule(
            variables = listOf(varX),
            head = Atom("safe", listOf(varX)),
            body = listOf(
                Atom("item", listOf(varX)),
                Atom("dangerous", listOf(varX), naf = true)
            )
        )

        store.add(Atom("item", listOf(id("sword"))))
        store.add(Atom("weapon", listOf(id("sword"))))  // sword is a weapon -> dangerous

        store.add(Atom("item", listOf(id("spoon"))))    // spoon is not dangerous

        val chainer = makeChainer(store, r1, r2)
        val results = chainer.solve(Atom("safe", listOf(v("who")))).toList()

        val names = results.map { it.args[0].toString() }
        assertTrue("spoon" in names, "spoon is safe")
        assertFalse("sword" in names, "sword is dangerous (via weapon rule) so not safe")
    }

    // -------------------------------------------------------------------------
    // 8. NAF vs explicit negation: truthVal=false is distinct from naf=true
    // -------------------------------------------------------------------------

    @Test
    fun `explicit negation truthVal=false is distinct from NAF`() {
        val store = makeStore()

        // Assert an explicit negative fact: NOT penguin(tweety) (truthVal=false)
        val explicitNeg = Atom("penguin", listOf(id("tweety")), truthVal = false)
        store.add(explicitNeg)

        val varX = v("x")
        // Rule using NAF: flies(?x) <- bird(?x) AND NAF penguin(?x)
        val rule = Rule(
            variables = listOf(varX),
            head = Atom("flies", listOf(varX)),
            body = listOf(
                Atom("bird", listOf(varX)),
                Atom("penguin", listOf(varX), naf = true)
            )
        )

        store.add(Atom("bird", listOf(id("tweety"))))

        val chainer = makeChainer(store, rule)
        val results = chainer.solve(Atom("flies", listOf(v("who")))).toList()

        // The explicit NOT penguin(tweety) has truthVal=false.
        // The NAF body condition looks for penguin(tweety) with truthVal=true.
        // Since no positive penguin(tweety) exists, NAF succeeds and tweety flies.
        assertTrue(results.any { it.args[0] == id("tweety") },
            "NAF should succeed when only an explicit-negative fact exists, not a positive one; " +
            "got $results")
    }

    @Test
    fun `explicit positive penguin blocks NAF unlike explicit negative penguin`() {
        val store = makeStore()

        // Now assert the POSITIVE penguin fact — NAF should block
        store.add(Atom("bird", listOf(id("tux"))))
        store.add(Atom("penguin", listOf(id("tux"))))  // positive, truthVal=true

        val varX = v("x")
        val rule = Rule(
            variables = listOf(varX),
            head = Atom("flies", listOf(varX)),
            body = listOf(
                Atom("bird", listOf(varX)),
                Atom("penguin", listOf(varX), naf = true)
            )
        )

        val chainer = makeChainer(store, rule)
        val results = chainer.solve(Atom("flies", listOf(v("who")))).toList()

        assertTrue(results.isEmpty(), "tux should not fly because penguin(tux) is provable")
    }

    // -------------------------------------------------------------------------
    // 9. NAF in DSL parsing via Parser + Tokenizer
    // -------------------------------------------------------------------------

    @Test
    fun `DSL parser handles NOT in rule body as NAF condition`() {
        val dsl = """
            ASSERT FORALL ?x {
                flies(?x) <- bird(?x) AND NOT penguin(?x)
            };
        """.trimIndent()

        val tokens = Tokenizer(dsl).tokenize()
        val commands = Parser(tokens).parse()

        assertEquals(1, commands.size)
        val cmd = commands[0] as com.nocturnusai.parser.Command.AssertRule
        val rule = cmd.rule

        assertEquals("flies", rule.head.predicate)
        assertEquals(2, rule.body.size)

        val birdCond = rule.body[0]
        assertEquals("bird", birdCond.predicate)
        assertFalse(birdCond.naf, "bird condition should NOT be NAF")

        val penguinCond = rule.body[1]
        assertEquals("penguin", penguinCond.predicate)
        assertTrue(penguinCond.naf, "penguin condition SHOULD be NAF")
    }

    @Test
    fun `DSL parser correctly handles rule with only positive conditions (no NAF regression)`() {
        val dsl = """
            ASSERT FORALL ?x {
                mortal(?x) <- human(?x)
            };
        """.trimIndent()

        val tokens = Tokenizer(dsl).tokenize()
        val commands = Parser(tokens).parse()
        val cmd = commands[0] as com.nocturnusai.parser.Command.AssertRule

        assertFalse(cmd.rule.body[0].naf, "Non-NAF body condition should have naf=false")
    }

    @Test
    fun `DSL execute integrates NAF rule with facts end-to-end`() {
        // This test exercises backward chaining via db.infer() (BackwardChainer.solve).
        // To avoid forward-chaining (Rete) eagerly deriving canFly(tux) before the
        // penguin(tux) NAF-blocking fact is in the store, we assert penguin(tux)
        // BEFORE bird(tux).  Backward chaining is then used for the final query
        // and correctly evaluates NAF at query time regardless of assertion order.
        val db = NocturnusAI(
            storageDir = createTempDir(),
            isMultiTenant = true
        )
        db.createTenant("t1")

        // Assert rule via DSL
        db.execute("""
            ASSERT FORALL ?x {
                canFly(?x) <- bird(?x) AND NOT penguin(?x)
            };
        """.trimIndent(), tenantId = "t1")

        // Assert tux's blocking fact first, then bird, so Rete also gets it right
        db.execute("ASSERT bird(robin);", tenantId = "t1")
        db.execute("ASSERT penguin(tux);", tenantId = "t1")  // blocking fact first
        db.execute("ASSERT bird(tux);", tenantId = "t1")

        // Query via backward chainer (NAF evaluated at query time)
        val results = db.infer(Atom("canFly", listOf(v("who"))), tenantId = "t1").toList()
        val names = results.map { it.args[0].toString() }

        assertTrue("robin" in names, "robin should canFly; got $names")
        assertFalse("tux" in names, "tux should not canFly (is a penguin); got $names")

        db.close()
    }

    // -------------------------------------------------------------------------
    // 10. NAF in forward chaining (ReteEngine)
    // -------------------------------------------------------------------------

    @Test
    fun `Rete engine - NAF condition blocks rule when negated fact is present`() {
        val store = makeStore()
        val engine = makeReteEngine(store)

        val varX = v("x")
        // Rule: canFly(?x) <- bird(?x) AND NAF penguin(?x)
        val rule = Rule(
            variables = listOf(varX),
            head = Atom("canFly", listOf(varX)),
            body = listOf(
                Atom("bird", listOf(varX)),
                Atom("penguin", listOf(varX), naf = true)
            )
        )
        engine.addRule(rule)

        // Assert penguin first, then bird — NAF should block derivation
        assertFact(store, engine, Atom("penguin", listOf(id("tux"))))
        assertFact(store, engine, Atom("bird", listOf(id("tux"))))

        val canFlyResults = store.match(Atom("canFly", listOf(v("who")))).toList()
        assertTrue(canFlyResults.isEmpty(),
            "canFly should not be derived for penguin tux; got $canFlyResults")
    }

    @Test
    fun `Rete engine - NAF condition succeeds when negated fact is absent`() {
        val store = makeStore()
        val engine = makeReteEngine(store)

        val varX = v("x")
        val rule = Rule(
            variables = listOf(varX),
            head = Atom("canFly", listOf(varX)),
            body = listOf(
                Atom("bird", listOf(varX)),
                Atom("penguin", listOf(varX), naf = true)
            )
        )
        engine.addRule(rule)

        // Robin is a bird but NOT a penguin
        assertFact(store, engine, Atom("bird", listOf(id("robin"))))

        val canFlyResults = store.match(Atom("canFly", listOf(v("who")))).toList()
        assertEquals(1, canFlyResults.size, "robin should be derived as canFly; got $canFlyResults")
        assertEquals(id("robin"), canFlyResults[0].args[0])
    }

    @Test
    fun `Rete engine - mixed birds one penguin and one regular`() {
        // NOTE: Forward-chaining NAF is evaluated at the time a triggering fact
        // is asserted.  To guarantee correct NAF behaviour in forward chaining,
        // the blocking (NAF-negated) fact MUST be asserted before the triggering
        // fact.  This test asserts penguin(emperor) before bird(emperor) to ensure
        // the NAF check sees the blocking fact when the rule fires.
        val store = makeStore()
        val engine = makeReteEngine(store)

        val varX = v("x")
        val rule = Rule(
            variables = listOf(varX),
            head = Atom("flies", listOf(varX)),
            body = listOf(
                Atom("bird", listOf(varX)),
                Atom("penguin", listOf(varX), naf = true)
            )
        )
        engine.addRule(rule)

        // Sparrow: no penguin fact → flies
        assertFact(store, engine, Atom("bird", listOf(id("sparrow"))))

        // Emperor: assert the blocking NAF fact BEFORE the triggering fact so
        // that the Rete NAF check finds it at rule-fire time.
        assertFact(store, engine, Atom("penguin", listOf(id("emperor"))))
        assertFact(store, engine, Atom("bird", listOf(id("emperor"))))

        val results = store.match(Atom("flies", listOf(v("who")))).toList()
        val names = results.map { it.args[0].toString() }

        assertTrue("sparrow" in names, "sparrow should fly; got $names")
        assertFalse("emperor" in names, "emperor (penguin) should not fly; got $names")
    }

    @Test
    fun `Rete engine - NAF condition only indexes positive body conditions in alpha network`() {
        // Verifies that a rule whose ENTIRE body is NAF conditions does not crash
        // (it simply never fires positively since there is no trigger fact).
        val store = makeStore()
        val engine = makeReteEngine(store)

        val varX = v("x")
        // Degenerate rule where body is all-NAF — no alpha node registered,
        // rule can never fire via forward chaining (requires a triggering positive fact).
        val rule = Rule(
            variables = listOf(varX),
            head = Atom("nothing", listOf(varX)),
            body = listOf(
                Atom("something", listOf(varX), naf = true)
            )
        )
        // This should not throw
        engine.addRule(rule)

        // Asserting unrelated fact does not trigger the rule
        assertFact(store, engine, Atom("other", listOf(id("x"))))

        val results = store.match(Atom("nothing", listOf(v("who")))).toList()
        assertTrue(results.isEmpty(), "All-NAF rule should never fire via forward chaining")
    }

    // -------------------------------------------------------------------------
    // 11. Atom naf field: default is false, serialization round-trip
    // -------------------------------------------------------------------------

    @Test
    fun `Atom naf defaults to false and does not affect non-NAF atoms`() {
        val a = Atom("foo", listOf(id("bar")))
        assertFalse(a.naf, "Default naf should be false")
    }

    @Test
    fun `Atom naf is included in equals and hashCode`() {
        val a1 = Atom("foo", listOf(id("bar")), naf = false)
        val a2 = Atom("foo", listOf(id("bar")), naf = true)

        assertFalse(a1 == a2, "Atoms with different naf should not be equal")
        assertFalse(a1.hashCode() == a2.hashCode(),
            "Atoms with different naf should have different hash codes")
    }

    @Test
    fun `Atom toString shows NAF prefix when naf=true`() {
        val a = Atom("penguin", listOf(id("tux")), naf = true)
        assertTrue(a.toString().startsWith("NAF "), "NAF atom toString should start with 'NAF ': ${a}")
    }

    @Test
    fun `Atom copy preserves naf flag`() {
        val original = Atom("foo", listOf(id("a")), naf = true)
        val copied = original.copy(predicate = "bar")
        assertTrue(copied.naf, "copy() should preserve naf=true")
    }

    // -------------------------------------------------------------------------
    // 12. NocturnusAI end-to-end: assertRule and infer with NAF via JSON-style API
    // -------------------------------------------------------------------------

    @Test
    fun `NocturnusAI API - assert rule with NAF body atom and infer`() {
        // Assert the NAF-blocking fact (suspended) BEFORE the triggering fact (member)
        // for dave so that both the forward-chaining Rete and the backward-chaining
        // query produce consistent results.
        val db = NocturnusAI(
            storageDir = createTempDir(),
            isMultiTenant = true
        )
        db.createTenant("api-test")

        val varX = Term.Variable("x")

        // Build rule programmatically (as the JSON route does)
        val rule = Rule(
            variables = listOf(varX),
            head = Atom("premium", listOf(varX)),
            body = listOf(
                Atom("member", listOf(varX)),
                Atom("suspended", listOf(varX), naf = true)
            )
        )

        db.assertRule(rule, tenantId = "api-test")

        db.assertFact(Atom("member", listOf(id("alice"))), tenantId = "api-test")
        // Assert suspended(dave) BEFORE member(dave) so Rete NAF check sees it
        db.assertFact(Atom("suspended", listOf(id("dave"))), tenantId = "api-test")
        db.assertFact(Atom("member", listOf(id("dave"))), tenantId = "api-test")

        val results = db.infer(Atom("premium", listOf(v("who"))), tenantId = "api-test").toList()
        val names = results.map { it.args[0].toString() }

        assertTrue("alice" in names, "alice should be premium")
        assertFalse("dave" in names, "dave is suspended so not premium")

        db.close()
    }

    // -------------------------------------------------------------------------
    // 13. Interaction: retract the blocking fact re-enables NAF
    // -------------------------------------------------------------------------

    @Test
    fun `backward chaining - after retracting blocking fact NAF succeeds`() {
        val store = makeStore()

        val varX = v("x")
        val rule = Rule(
            variables = listOf(varX),
            head = Atom("flies", listOf(varX)),
            body = listOf(
                Atom("bird", listOf(varX)),
                Atom("injured", listOf(varX), naf = true)
            )
        )

        store.add(Atom("bird", listOf(id("tweety"))))
        val injuredFact = Atom("injured", listOf(id("tweety")))
        store.add(injuredFact)

        val chainer = makeChainer(store, rule)

        // While injured, tweety cannot fly
        val beforeRetract = chainer.solve(Atom("flies", listOf(v("who")))).toList()
        assertTrue(beforeRetract.isEmpty(), "tweety is injured so should not fly")

        // Heal tweety
        store.delete(injuredFact)

        val afterRetract = makeChainer(store, rule)
            .solve(Atom("flies", listOf(v("who")))).toList()
        assertEquals(1, afterRetract.size, "tweety should fly after healing")
        assertEquals(id("tweety"), afterRetract[0].args[0])
    }

    // -------------------------------------------------------------------------
    // 14. Depth limit: NAF proofs share the depth counter
    // -------------------------------------------------------------------------

    @Test
    fun `NAF proof depth shares parent depth counter - no stack overflow`() {
        val store = makeStore()

        // Build a moderately deep chain and a NAF on top of it
        // a0 -> a1 -> a2 -> ... a9
        val varX = v("x")
        val rules = mutableListOf<Rule>()
        for (i in 0 until 9) {
            rules.add(Rule(
                variables = listOf(varX),
                head = Atom("a${i + 1}", listOf(varX)),
                body = listOf(Atom("a$i", listOf(varX)))
            ))
        }
        // Final rule uses NAF on a9: result(?x) <- start(?x) AND NAF a9(?x)
        rules.add(Rule(
            variables = listOf(varX),
            head = Atom("result", listOf(varX)),
            body = listOf(
                Atom("start", listOf(varX)),
                Atom("a9", listOf(varX), naf = true)
            )
        ))

        store.add(Atom("a0", listOf(id("thing"))))
        store.add(Atom("start", listOf(id("thing"))))

        val chainer = BackwardChainer(store, rules, maxDepth = 100)
        // a9 IS derivable via the chain, so NAF should block
        val results = chainer.solve(Atom("result", listOf(v("who")))).toList()
        assertTrue(results.isEmpty(), "result should be blocked because a9 is provable via chain")
    }

    // -------------------------------------------------------------------------
    // 15. Parser test: DSL with mixed NAF and positive conditions
    // -------------------------------------------------------------------------

    @Test
    fun `DSL parser handles rule with both positive and NAF conditions in sequence`() {
        val dsl = """
            ASSERT FORALL ?x ?y {
                transfer(?x, ?y) <- owner(?x, ?y) AND NOT locked(?y) AND active(?x)
            };
        """.trimIndent()

        val tokens = Tokenizer(dsl).tokenize()
        val commands = Parser(tokens).parse()
        val rule = (commands[0] as com.nocturnusai.parser.Command.AssertRule).rule

        assertEquals(3, rule.body.size)
        assertFalse(rule.body[0].naf, "owner condition should not be NAF")
        assertTrue(rule.body[1].naf, "locked condition SHOULD be NAF")
        assertEquals("locked", rule.body[1].predicate)
        assertFalse(rule.body[2].naf, "active condition should not be NAF")
    }

    // -------------------------------------------------------------------------
    // 16. Rule.toString shows NAF prefix for NAF body conditions
    // -------------------------------------------------------------------------

    @Test
    fun `Rule toString renders NAF conditions with NAF prefix`() {
        val varX = v("x")
        val rule = Rule(
            variables = listOf(varX),
            head = Atom("flies", listOf(varX)),
            body = listOf(
                Atom("bird", listOf(varX)),
                Atom("penguin", listOf(varX), naf = true)
            )
        )
        val str = rule.toString()
        assertTrue(str.contains("NAF"), "Rule toString should contain 'NAF' for NAF conditions: $str")
        assertTrue(str.contains("bird"), "Rule toString should contain 'bird': $str")
    }

    // -------------------------------------------------------------------------
    // Helper to create a temp directory for NocturnusAI instances in tests
    // -------------------------------------------------------------------------

    private fun createTempDir(): java.io.File {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "naf_test_${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }
}
