package com.nocturnusai

import com.nocturnusai.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.File

class ProofTreeTest {

    private fun createDb(): NocturnusAI {
        val dir = File("build/test-proof-${System.nanoTime()}")
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()
        return NocturnusAI(dir)
    }

    @Test
    fun `fact-only proof produces FactMatch step`() {
        val db = createDb()
        val fact = Atom("parent", listOf(Term.Identifier("alice"), Term.Identifier("bob")))
        db.assertFact(fact)

        val query = Atom("parent", listOf(Term.Identifier("alice"), Term.Variable("x")))
        val proofs = db.inferWithProof(query).toList()

        assertEquals(1, proofs.size)
        assertEquals("parent", proofs[0].result.predicate)
        assertEquals(Term.Identifier("bob"), proofs[0].result.args[1])
        assertTrue(proofs[0].proof.step is ProofStep.FactMatch)
        db.close()
    }

    @Test
    fun `single-rule proof produces RuleApplication step`() {
        val db = createDb()
        // parent(alice, bob)
        db.assertFact(Atom("parent", listOf(Term.Identifier("alice"), Term.Identifier("bob"))))

        // ancestor(?x, ?y) <- parent(?x, ?y)
        val rule = Rule(
            variables = listOf(Term.Variable("x"), Term.Variable("y")),
            head = Atom("ancestor", listOf(Term.Variable("x"), Term.Variable("y"))),
            body = listOf(Atom("parent", listOf(Term.Variable("x"), Term.Variable("y"))))
        )
        db.assertRule(rule)

        val query = Atom("ancestor", listOf(Term.Identifier("alice"), Term.Variable("who")))
        val proofs = db.inferWithProof(query).toList()

        assertEquals(1, proofs.size)
        assertEquals(Term.Identifier("bob"), proofs[0].result.args[1])

        val step = proofs[0].proof.step
        assertTrue(step is ProofStep.RuleApplication)
        assertEquals("ancestor", (step as ProofStep.RuleApplication).rule.head.predicate)
        assertEquals(1, step.bodyProofs.size)
        assertTrue(step.bodyProofs[0].step is ProofStep.FactMatch)
        db.close()
    }

    @Test
    fun `multi-step chain produces nested RuleApplication`() {
        val db = createDb()
        // parent(alice, bob), parent(bob, charlie)
        db.assertFact(Atom("parent", listOf(Term.Identifier("alice"), Term.Identifier("bob"))))
        db.assertFact(Atom("parent", listOf(Term.Identifier("bob"), Term.Identifier("charlie"))))

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
        val proofs = db.inferWithProof(query).toList()

        // Should find ancestor(alice, bob) and ancestor(alice, charlie)
        assertTrue(proofs.size >= 2, "Expected at least 2 proofs, got ${proofs.size}")
        val resultArgs = proofs.map { it.result.args[1] }.toSet()
        assertTrue(Term.Identifier("bob") in resultArgs)
        assertTrue(Term.Identifier("charlie") in resultArgs)

        // The charlie proof should be nested (RuleApplication containing RuleApplication or FactMatch)
        val charlieProof = proofs.first { it.result.args[1] == Term.Identifier("charlie") }
        val topStep = charlieProof.proof.step
        assertTrue(topStep is ProofStep.RuleApplication)
        db.close()
    }

    @Test
    fun `multiple solutions produce distinct ProofTrees`() {
        val db = createDb()
        db.assertFact(Atom("likes", listOf(Term.Identifier("alice"), Term.Identifier("pizza"))))
        db.assertFact(Atom("likes", listOf(Term.Identifier("alice"), Term.Identifier("pasta"))))

        val query = Atom("likes", listOf(Term.Identifier("alice"), Term.Variable("food")))
        val proofs = db.inferWithProof(query).toList()

        assertEquals(2, proofs.size)
        val foods = proofs.map { it.result.args[1] }.toSet()
        assertEquals(setOf(Term.Identifier("pizza"), Term.Identifier("pasta")), foods)
        db.close()
    }

    @Test
    fun `execute INFER uses backward chaining`() {
        val db = createDb()
        // Assert fact + rule, then INFER through the rule
        db.assertFact(Atom("parent", listOf(Term.Identifier("alice"), Term.Identifier("bob"))))
        db.assertRule(Rule(
            variables = listOf(Term.Variable("x"), Term.Variable("y")),
            head = Atom("ancestor", listOf(Term.Variable("x"), Term.Variable("y"))),
            body = listOf(Atom("parent", listOf(Term.Variable("x"), Term.Variable("y"))))
        ))

        val result = db.execute("INFER ancestor(alice, ?y);")
        assertTrue(result.contains("1 matches"), "Expected 1 match via backward chaining, got: $result")
        assertTrue(result.contains("bob"), "Expected bob in results, got: $result")
        db.close()
    }

    @Test
    fun `execute INFER WITH PROOF includes proof text`() {
        val db = createDb()
        db.assertFact(Atom("parent", listOf(Term.Identifier("alice"), Term.Identifier("bob"))))
        db.assertRule(Rule(
            variables = listOf(Term.Variable("x"), Term.Variable("y")),
            head = Atom("ancestor", listOf(Term.Variable("x"), Term.Variable("y"))),
            body = listOf(Atom("parent", listOf(Term.Variable("x"), Term.Variable("y"))))
        ))

        val result = db.execute("INFER ancestor(alice, ?y) WITH PROOF;")
        assertTrue(result.contains("1 matches"), "Expected 1 match, got: $result")
        assertTrue(result.contains("RULE:"), "Expected RULE: in proof output, got: $result")
        assertTrue(result.contains("FACT:"), "Expected FACT: in proof output, got: $result")
        db.close()
    }
}
