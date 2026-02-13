package com.axiombase.testing

import com.axiombase.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class TestRunnerTest {

    private val runner = TestRunner()

    private fun fact(pred: String, vararg args: String): Atom =
        Atom(pred, args.map { Term.Identifier(it) })

    private fun varAtom(pred: String, vararg args: String): Atom =
        Atom(pred, args.map { if (it.startsWith("?")) Term.Variable(it.drop(1)) else Term.Identifier(it) })

    @Test
    fun `provable fact passes`() {
        val tc = TestCase(
            name = "provable fact",
            setup = listOf(SetupAction.AssertFact(fact("parent", "alice", "bob"))),
            expectations = listOf(Expectation.Provable(varAtom("parent", "alice", "?x")))
        )
        val result = runner.run(tc)
        assertTrue(result.passed)
        assertEquals(1, result.expectationResults.size)
        assertTrue(result.expectationResults[0].passed)
        assertTrue(result.expectationResults[0].proof != null)
    }

    @Test
    fun `not provable passes when goal has no solutions`() {
        val tc = TestCase(
            name = "not provable",
            setup = listOf(SetupAction.AssertFact(fact("parent", "alice", "bob"))),
            expectations = listOf(Expectation.NotProvable(varAtom("parent", "charlie", "?x")))
        )
        val result = runner.run(tc)
        assertTrue(result.passed)
    }

    @Test
    fun `not provable fails when goal is provable`() {
        val tc = TestCase(
            name = "unexpected provability",
            setup = listOf(SetupAction.AssertFact(fact("parent", "alice", "bob"))),
            expectations = listOf(Expectation.NotProvable(varAtom("parent", "alice", "?x")))
        )
        val result = runner.run(tc)
        assertFalse(result.passed)
        assertFalse(result.expectationResults[0].passed)
        assertTrue(result.expectationResults[0].message.contains("IS provable"))
        // Should include proof for debugging
        assertTrue(result.expectationResults[0].proof != null)
    }

    @Test
    fun `results exactly matches set`() {
        val tc = TestCase(
            name = "exact results",
            setup = listOf(
                SetupAction.AssertFact(fact("likes", "alice", "pizza")),
                SetupAction.AssertFact(fact("likes", "alice", "pasta"))
            ),
            expectations = listOf(
                Expectation.ResultsExactly(
                    goal = varAtom("likes", "alice", "?food"),
                    expected = listOf(
                        fact("likes", "alice", "pasta"),
                        fact("likes", "alice", "pizza")
                    )
                )
            )
        )
        val result = runner.run(tc)
        assertTrue(result.passed, "Expected pass, got: ${result.expectationResults[0].message}")
    }

    @Test
    fun `results exactly fails on mismatch`() {
        val tc = TestCase(
            name = "mismatched results",
            setup = listOf(SetupAction.AssertFact(fact("likes", "alice", "pizza"))),
            expectations = listOf(
                Expectation.ResultsExactly(
                    goal = varAtom("likes", "alice", "?food"),
                    expected = listOf(
                        fact("likes", "alice", "pizza"),
                        fact("likes", "alice", "sushi")
                    )
                )
            )
        )
        val result = runner.run(tc)
        assertFalse(result.passed)
        assertTrue(result.expectationResults[0].message.contains("Missing"))
    }

    @Test
    fun `result count matches`() {
        val tc = TestCase(
            name = "count check",
            setup = listOf(
                SetupAction.AssertFact(fact("likes", "alice", "pizza")),
                SetupAction.AssertFact(fact("likes", "alice", "pasta")),
                SetupAction.AssertFact(fact("likes", "alice", "sushi"))
            ),
            expectations = listOf(Expectation.ResultCount(varAtom("likes", "alice", "?food"), 3))
        )
        val result = runner.run(tc)
        assertTrue(result.passed)
    }

    @Test
    fun `result count fails on wrong count`() {
        val tc = TestCase(
            name = "wrong count",
            setup = listOf(SetupAction.AssertFact(fact("likes", "alice", "pizza"))),
            expectations = listOf(Expectation.ResultCount(varAtom("likes", "alice", "?food"), 5))
        )
        val result = runner.run(tc)
        assertFalse(result.passed)
        assertTrue(result.expectationResults[0].message.contains("Expected 5"))
    }

    @Test
    fun `rule-based provability through inference`() {
        val tc = TestCase(
            name = "rule inference",
            setup = listOf(
                SetupAction.AssertFact(fact("parent", "alice", "bob")),
                SetupAction.AssertRule(Rule(
                    variables = listOf(Term.Variable("x"), Term.Variable("y")),
                    head = Atom("ancestor", listOf(Term.Variable("x"), Term.Variable("y"))),
                    body = listOf(Atom("parent", listOf(Term.Variable("x"), Term.Variable("y"))))
                ))
            ),
            expectations = listOf(
                Expectation.Provable(varAtom("ancestor", "alice", "?who")),
                Expectation.NotProvable(varAtom("ancestor", "charlie", "?who"))
            )
        )
        val result = runner.run(tc)
        assertTrue(result.passed, "Expected pass, failures: ${result.expectationResults.filter { !it.passed }.map { it.message }}")
    }

    @Test
    fun `suite execution aggregates results`() {
        val passingTest = TestCase(
            name = "passes",
            setup = listOf(SetupAction.AssertFact(fact("a", "1"))),
            expectations = listOf(Expectation.Provable(fact("a", "1")))
        )
        val failingTest = TestCase(
            name = "fails",
            setup = emptyList(),
            expectations = listOf(Expectation.Provable(fact("b", "2")))
        )
        val suite = runner.runSuite(listOf(passingTest, failingTest))

        assertEquals(2, suite.total)
        assertEquals(1, suite.passed)
        assertEquals(1, suite.failed)
        assertTrue(suite.results[0].passed)
        assertFalse(suite.results[1].passed)
    }

    @Test
    fun `tests are isolated from each other`() {
        val test1 = TestCase(
            name = "adds fact",
            setup = listOf(SetupAction.AssertFact(fact("secret", "data"))),
            expectations = listOf(Expectation.Provable(fact("secret", "data")))
        )
        val test2 = TestCase(
            name = "should not see test1 fact",
            setup = emptyList(),
            expectations = listOf(Expectation.NotProvable(fact("secret", "data")))
        )
        val suite = runner.runSuite(listOf(test1, test2))

        assertEquals(2, suite.passed, "Both tests should pass — test2 should NOT see test1's data")
    }
}
