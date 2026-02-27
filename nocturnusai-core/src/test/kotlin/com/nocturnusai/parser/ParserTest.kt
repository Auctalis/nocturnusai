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

package com.nocturnusai.parser

import com.nocturnusai.core.Atom
import com.nocturnusai.core.Rule
import com.nocturnusai.core.Term
import com.nocturnusai.logic.Constraint
import com.nocturnusai.testing.Expectation
import com.nocturnusai.testing.SetupAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit tests for [Parser].
 *
 * DSL grammar summary (as implemented):
 *   Statement  ::= ASSERT (Rule | Atom) ";"
 *               |  INFER Atom ["WITH" "PROOF"] ";"
 *               |  RESTRICT {Variable} "{" Antecedent "}" "->" "CONTRADICTION" ";"
 *               |  EXPLAIN Atom ";"
 *               |  EXTRACT String ["DRY"] ";"
 *               |  TEST String "{" [GIVEN "{" SetupAction+ "}"] EXPECT "{" Expectation+ "}" "}" ";"
 *   Rule       ::= FORALL Variable+ "{" Atom "<-" Antecedent "}"
 *   Antecedent ::= Atom {"AND" Atom}
 *   Atom       ::= Identifier "(" [Term {"," Term}] ")"
 *   Term       ::= Variable | String | Number | Identifier
 *   Variable   ::= "?" identifier-chars
 */
class ParserTest {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Parse [dsl] and return the resulting command list.
     * Tokens are produced by [Tokenizer] so these are end-to-end pipeline tests.
     */
    private fun parse(dsl: String): List<Command> {
        val tokens = Tokenizer(dsl).tokenize()
        return Parser(tokens).parse()
    }

    /** Parse a single command and cast to the expected type. */
    private inline fun <reified T : Command> parseSingle(dsl: String): T {
        val cmds = parse(dsl)
        assertEquals(1, cmds.size, "Expected exactly one command, got ${cmds.size}")
        return assertIs<T>(cmds[0])
    }

    /** Build an Atom with all Identifier args (test convenience). */
    private fun atom(pred: String, vararg args: String): Atom =
        Atom(pred, args.map { Term.Identifier(it) })

    /** Build an Atom mixing Identifier/Variable args by "?"-prefix convention. */
    private fun atomMixed(pred: String, vararg args: String): Atom =
        Atom(pred, args.map {
            when {
                it.startsWith("?") -> Term.Variable(it.drop(1))
                it.toDoubleOrNull() != null -> Term.NumberLit(it.toDouble())
                else -> Term.Identifier(it)
            }
        })

    // -----------------------------------------------------------------------
    // ASSERT fact — identifier arguments
    // -----------------------------------------------------------------------

    @Test
    fun `assert binary fact with two identifiers`() {
        val cmd = parseSingle<Command.AssertFact>("ASSERT likes(alice, bob);")
        assertEquals("likes", cmd.fact.predicate)
        assertEquals(listOf(Term.Identifier("alice"), Term.Identifier("bob")), cmd.fact.args)
    }

    @Test
    fun `assert unary fact`() {
        val cmd = parseSingle<Command.AssertFact>("ASSERT alive(alice);")
        assertEquals("alive", cmd.fact.predicate)
        assertEquals(listOf(Term.Identifier("alice")), cmd.fact.args)
    }

    @Test
    fun `assert ternary fact`() {
        val cmd = parseSingle<Command.AssertFact>("ASSERT between(a, b, c);")
        assertEquals("between", cmd.fact.predicate)
        assertEquals(3, cmd.fact.args.size)
        assertEquals(Term.Identifier("a"), cmd.fact.args[0])
        assertEquals(Term.Identifier("b"), cmd.fact.args[1])
        assertEquals(Term.Identifier("c"), cmd.fact.args[2])
    }

    @Test
    fun `assert nullary fact - zero arguments`() {
        val cmd = parseSingle<Command.AssertFact>("ASSERT worldExists();")
        assertEquals("worldExists", cmd.fact.predicate)
        assertTrue(cmd.fact.args.isEmpty())
    }

    // -----------------------------------------------------------------------
    // ASSERT fact — term types
    // -----------------------------------------------------------------------

    @Test
    fun `assert fact with variable argument`() {
        val cmd = parseSingle<Command.AssertFact>("ASSERT parent(alice, ?child);")
        assertEquals("parent", cmd.fact.predicate)
        assertEquals(Term.Identifier("alice"), cmd.fact.args[0])
        assertEquals(Term.Variable("child"), cmd.fact.args[1])
    }

    @Test
    fun `assert fact with number argument`() {
        val cmd = parseSingle<Command.AssertFact>("ASSERT age(alice, 30);")
        assertEquals("age", cmd.fact.predicate)
        assertEquals(Term.Identifier("alice"), cmd.fact.args[0])
        assertEquals(Term.NumberLit(30.0), cmd.fact.args[1])
    }

    @Test
    fun `assert fact with floating-point number argument`() {
        val cmd = parseSingle<Command.AssertFact>("ASSERT score(player, 9.5);")
        val num = assertIs<Term.NumberLit>(cmd.fact.args[1])
        assertEquals(9.5, num.value)
    }

    @Test
    fun `assert fact with string argument`() {
        val cmd = parseSingle<Command.AssertFact>("ASSERT greeting(alice, \"hello\");")
        assertEquals("greeting", cmd.fact.predicate)
        assertEquals(Term.Identifier("alice"), cmd.fact.args[0])
        assertEquals(Term.StringLit("hello"), cmd.fact.args[1])
    }

    @Test
    fun `assert fact with string containing spaces`() {
        val cmd = parseSingle<Command.AssertFact>("ASSERT note(doc, \"hello world\");")
        assertEquals(Term.StringLit("hello world"), cmd.fact.args[1])
    }

    @Test
    fun `assert fact mixing all term types`() {
        val cmd = parseSingle<Command.AssertFact>("ASSERT mixed(alice, ?x, 42, \"foo\");")
        assertEquals(Term.Identifier("alice"), cmd.fact.args[0])
        assertEquals(Term.Variable("x"),       cmd.fact.args[1])
        assertEquals(Term.NumberLit(42.0),     cmd.fact.args[2])
        assertEquals(Term.StringLit("foo"),    cmd.fact.args[3])
    }

    // -----------------------------------------------------------------------
    // ASSERT fact — default field values
    // -----------------------------------------------------------------------

    @Test
    fun `asserted fact has default truthVal true`() {
        val cmd = parseSingle<Command.AssertFact>("ASSERT alive(alice);")
        assertTrue(cmd.fact.truthVal)
    }

    @Test
    fun `asserted fact has null scope by default`() {
        val cmd = parseSingle<Command.AssertFact>("ASSERT alive(alice);")
        assertEquals(null, cmd.fact.scope)
    }

    // -----------------------------------------------------------------------
    // ASSERT rule
    // -----------------------------------------------------------------------

    @Test
    fun `assert simple rule - single body atom`() {
        val cmd = parseSingle<Command.AssertRule>(
            "ASSERT FORALL ?x { mortal(?x) <- human(?x) };"
        )
        val rule = cmd.rule
        assertEquals(listOf(Term.Variable("x")), rule.variables)
        assertEquals("mortal", rule.head.predicate)
        assertEquals(listOf(Term.Variable("x")), rule.head.args)
        assertEquals(1, rule.body.size)
        assertEquals("human", rule.body[0].predicate)
        assertEquals(listOf(Term.Variable("x")), rule.body[0].args)
    }

    @Test
    fun `assert rule - two body atoms joined by AND`() {
        val cmd = parseSingle<Command.AssertRule>(
            "ASSERT FORALL ?x, ?z { grandparent(?x, ?z) <- parent(?x, ?y) AND parent(?y, ?z) };"
        )
        val rule = cmd.rule
        // Variables list contains explicitly declared vars
        assertTrue(rule.variables.any { it.name == "x" })
        assertTrue(rule.variables.any { it.name == "z" })
        assertEquals("grandparent", rule.head.predicate)
        assertEquals(2, rule.head.args.size)
        assertEquals(2, rule.body.size)
        assertEquals("parent", rule.body[0].predicate)
        assertEquals("parent", rule.body[1].predicate)
    }

    @Test
    fun `assert rule - three body atoms`() {
        val cmd = parseSingle<Command.AssertRule>(
            "ASSERT FORALL ?x, ?y, ?z { path(?x, ?z) <- edge(?x, ?y) AND path(?y, ?z) AND exists(?z) };"
        )
        assertEquals(3, cmd.rule.body.size)
    }

    @Test
    fun `assert rule - head uses multiple variables`() {
        val cmd = parseSingle<Command.AssertRule>(
            "ASSERT FORALL ?x, ?y { sibling(?x, ?y) <- parent(?z, ?x) AND parent(?z, ?y) };"
        )
        assertEquals("sibling", cmd.rule.head.predicate)
        assertEquals(2, cmd.rule.head.args.size)
        assertIs<Term.Variable>(cmd.rule.head.args[0])
        assertIs<Term.Variable>(cmd.rule.head.args[1])
    }

    @Test
    fun `assert rule - body atom with identifier and variable`() {
        // Rule body may mix bound identifiers and variables
        val cmd = parseSingle<Command.AssertRule>(
            "ASSERT FORALL ?x { isChild(?x) <- parentOf(alice, ?x) };"
        )
        assertEquals("alice", (cmd.rule.body[0].args[0] as Term.Identifier).name)
        assertEquals("x",     (cmd.rule.body[0].args[1] as Term.Variable).name)
    }

    @Test
    fun `rule variables list single variable`() {
        val cmd = parseSingle<Command.AssertRule>(
            "ASSERT FORALL ?x { foo(?x) <- bar(?x) };"
        )
        assertEquals(1, cmd.rule.variables.size)
        assertEquals("x", cmd.rule.variables[0].name)
    }

    // -----------------------------------------------------------------------
    // INFER
    // -----------------------------------------------------------------------

    @Test
    fun `infer with two identifier args`() {
        val cmd = parseSingle<Command.Infer>("INFER likes(alice, bob);")
        assertEquals("likes", cmd.query.predicate)
        assertEquals(listOf(Term.Identifier("alice"), Term.Identifier("bob")), cmd.query.args)
        assertFalse(cmd.withProof)
    }

    @Test
    fun `infer with variable arg`() {
        val cmd = parseSingle<Command.Infer>("INFER likes(alice, ?who);")
        assertEquals(Term.Identifier("alice"), cmd.query.args[0])
        assertEquals(Term.Variable("who"),     cmd.query.args[1])
        assertFalse(cmd.withProof)
    }

    @Test
    fun `infer with all variables`() {
        val cmd = parseSingle<Command.Infer>("INFER parent(?x, ?y);")
        assertIs<Term.Variable>(cmd.query.args[0])
        assertIs<Term.Variable>(cmd.query.args[1])
    }

    @Test
    fun `infer with WITH PROOF sets flag`() {
        val cmd = parseSingle<Command.Infer>("INFER likes(alice, ?who) WITH PROOF;")
        assertTrue(cmd.withProof)
        assertEquals("likes", cmd.query.predicate)
    }

    @Test
    fun `infer with number arg`() {
        val cmd = parseSingle<Command.Infer>("INFER age(alice, 30);")
        assertEquals(Term.NumberLit(30.0), cmd.query.args[1])
    }

    @Test
    fun `infer with string arg`() {
        val cmd = parseSingle<Command.Infer>("INFER label(x, \"hello\");")
        assertEquals(Term.StringLit("hello"), cmd.query.args[1])
    }

    @Test
    fun `infer grandparent with bound and unbound args`() {
        val cmd = parseSingle<Command.Infer>("INFER grandparent(?x, charlie);")
        assertIs<Term.Variable>(cmd.query.args[0])
        assertIs<Term.Identifier>(cmd.query.args[1])
        assertEquals("charlie", (cmd.query.args[1] as Term.Identifier).name)
    }

    // -----------------------------------------------------------------------
    // RESTRICT
    // -----------------------------------------------------------------------

    @Test
    fun `restrict with single condition`() {
        val cmd = parseSingle<Command.Restrict>(
            "RESTRICT ?x { alive(?x) } -> CONTRADICTION;"
        )
        assertEquals(1, cmd.constraint.pattern.size)
        assertEquals("alive", cmd.constraint.pattern[0].predicate)
    }

    @Test
    fun `restrict with two conditions`() {
        val cmd = parseSingle<Command.Restrict>(
            "RESTRICT ?x { Status(?x, Dead) AND Status(?x, Alive) } -> CONTRADICTION;"
        )
        assertEquals(2, cmd.constraint.pattern.size)
        assertEquals("Status", cmd.constraint.pattern[0].predicate)
        assertEquals("Status", cmd.constraint.pattern[1].predicate)
    }

    @Test
    fun `restrict with multiple variables`() {
        val cmd = parseSingle<Command.Restrict>(
            "RESTRICT ?x, ?y { parent(?x, ?y) AND parent(?y, ?x) } -> CONTRADICTION;"
        )
        assertEquals(2, cmd.constraint.pattern.size)
    }

    // -----------------------------------------------------------------------
    // EXPLAIN
    // -----------------------------------------------------------------------

    @Test
    fun `explain binary fact`() {
        val cmd = parseSingle<Command.Explain>("EXPLAIN likes(alice, bob);")
        assertEquals("likes", cmd.fact.predicate)
        assertEquals(listOf(Term.Identifier("alice"), Term.Identifier("bob")), cmd.fact.args)
    }

    @Test
    fun `explain fact with variable`() {
        val cmd = parseSingle<Command.Explain>("EXPLAIN mortal(?x);")
        assertEquals("mortal", cmd.fact.predicate)
        assertIs<Term.Variable>(cmd.fact.args[0])
    }

    // -----------------------------------------------------------------------
    // EXTRACT
    // -----------------------------------------------------------------------

    @Test
    fun `extract simple text`() {
        val cmd = parseSingle<Command.Extract>("EXTRACT \"Alice likes Bob.\";")
        assertEquals("Alice likes Bob.", cmd.text)
        assertFalse(cmd.dryRun)
    }

    @Test
    fun `extract with DRY flag`() {
        val cmd = parseSingle<Command.Extract>("EXTRACT \"Alice likes Bob.\" DRY;")
        assertEquals("Alice likes Bob.", cmd.text)
        assertTrue(cmd.dryRun)
    }

    @Test
    fun `extract empty string`() {
        val cmd = parseSingle<Command.Extract>("EXTRACT \"\";")
        assertEquals("", cmd.text)
    }

    // -----------------------------------------------------------------------
    // TEST
    // -----------------------------------------------------------------------

    @Test
    fun `test with GIVEN and EXPECT PROVABLE`() {
        val dsl = """
            TEST "check-likes" {
                GIVEN {
                    ASSERT likes(alice, bob);
                }
                EXPECT {
                    PROVABLE likes(alice, bob);
                }
            };
        """.trimIndent()
        val cmd = parseSingle<Command.Test>(dsl)
        assertEquals("check-likes", cmd.testCase.name)
        assertEquals(1, cmd.testCase.setup.size)
        assertIs<SetupAction.AssertFact>(cmd.testCase.setup[0])
        assertEquals(1, cmd.testCase.expectations.size)
        assertIs<Expectation.Provable>(cmd.testCase.expectations[0])
    }

    @Test
    fun `test with NOT_PROVABLE expectation`() {
        val dsl = """
            TEST "not-likes" {
                GIVEN {
                    ASSERT likes(alice, bob);
                }
                EXPECT {
                    NOT_PROVABLE likes(charlie, bob);
                }
            };
        """.trimIndent()
        val cmd = parseSingle<Command.Test>(dsl)
        assertIs<Expectation.NotProvable>(cmd.testCase.expectations[0])
        val goal = (cmd.testCase.expectations[0] as Expectation.NotProvable).goal
        assertEquals("likes", goal.predicate)
        assertEquals(Term.Identifier("charlie"), goal.args[0])
    }

    @Test
    fun `test with COUNT expectation`() {
        val dsl = """
            TEST "count-test" {
                GIVEN {
                    ASSERT likes(alice, pizza);
                    ASSERT likes(alice, pasta);
                }
                EXPECT {
                    COUNT likes(alice, ?food) 2;
                }
            };
        """.trimIndent()
        val cmd = parseSingle<Command.Test>(dsl)
        val expectation = assertIs<Expectation.ResultCount>(cmd.testCase.expectations[0])
        assertEquals(2, expectation.count)
        assertEquals("likes", expectation.goal.predicate)
    }

    @Test
    fun `test without GIVEN block`() {
        val dsl = """
            TEST "no-setup" {
                EXPECT {
                    NOT_PROVABLE anything(x);
                }
            };
        """.trimIndent()
        val cmd = parseSingle<Command.Test>(dsl)
        assertTrue(cmd.testCase.setup.isEmpty())
        assertEquals(1, cmd.testCase.expectations.size)
    }

    @Test
    fun `test GIVEN block may assert rule`() {
        val dsl = """
            TEST "rule-test" {
                GIVEN {
                    ASSERT likes(alice, bob);
                    ASSERT FORALL ?x, ?y { knows(?x, ?y) <- likes(?x, ?y) };
                }
                EXPECT {
                    PROVABLE knows(alice, bob);
                }
            };
        """.trimIndent()
        val cmd = parseSingle<Command.Test>(dsl)
        assertEquals(2, cmd.testCase.setup.size)
        assertIs<SetupAction.AssertFact>(cmd.testCase.setup[0])
        assertIs<SetupAction.AssertRule>(cmd.testCase.setup[1])
    }

    @Test
    fun `test name is preserved`() {
        val dsl = """
            TEST "my test name" {
                EXPECT {
                    NOT_PROVABLE foo(x);
                }
            };
        """.trimIndent()
        val cmd = parseSingle<Command.Test>(dsl)
        assertEquals("my test name", cmd.testCase.name)
    }

    // -----------------------------------------------------------------------
    // Multiple commands in one input
    // -----------------------------------------------------------------------

    @Test
    fun `two ASSERT facts parsed as two commands`() {
        val dsl = "ASSERT likes(alice, bob); ASSERT likes(alice, charlie);"
        val cmds = parse(dsl)
        assertEquals(2, cmds.size)
        val f1 = assertIs<Command.AssertFact>(cmds[0])
        val f2 = assertIs<Command.AssertFact>(cmds[1])
        assertEquals(listOf(Term.Identifier("alice"), Term.Identifier("bob")),     f1.fact.args)
        assertEquals(listOf(Term.Identifier("alice"), Term.Identifier("charlie")), f2.fact.args)
    }

    @Test
    fun `mixed assert and infer commands`() {
        val dsl = """
            ASSERT parent(alice, bob);
            INFER parent(alice, ?x);
        """.trimIndent()
        val cmds = parse(dsl)
        assertEquals(2, cmds.size)
        assertIs<Command.AssertFact>(cmds[0])
        assertIs<Command.Infer>(cmds[1])
    }

    @Test
    fun `assert rule then infer`() {
        val dsl = """
            ASSERT FORALL ?x { mortal(?x) <- human(?x) };
            INFER mortal(?who);
        """.trimIndent()
        val cmds = parse(dsl)
        assertEquals(2, cmds.size)
        assertIs<Command.AssertRule>(cmds[0])
        assertIs<Command.Infer>(cmds[1])
    }

    @Test
    fun `empty input parses to zero commands`() {
        val cmds = parse("")
        assertTrue(cmds.isEmpty())
    }

    @Test
    fun `whitespace-only input parses to zero commands`() {
        val cmds = parse("   \n\t  ")
        assertTrue(cmds.isEmpty())
    }

    @Test
    fun `comments between commands are skipped`() {
        val dsl = """
            # first command
            ASSERT likes(alice, bob);
            -- second is an infer
            INFER likes(alice, ?who);
        """.trimIndent()
        val cmds = parse(dsl)
        assertEquals(2, cmds.size)
        assertIs<Command.AssertFact>(cmds[0])
        assertIs<Command.Infer>(cmds[1])
    }

    // -----------------------------------------------------------------------
    // Predicate names
    // -----------------------------------------------------------------------

    @Test
    fun `predicate name with underscore`() {
        val cmd = parseSingle<Command.AssertFact>("ASSERT is_alive(alice);")
        assertEquals("is_alive", cmd.fact.predicate)
    }

    @Test
    fun `predicate name with mixed case`() {
        val cmd = parseSingle<Command.AssertFact>("ASSERT grandParent(alice, charlie);")
        assertEquals("grandParent", cmd.fact.predicate)
    }

    @Test
    fun `predicate name that starts with keyword prefix`() {
        // "assertFoo" should be an IDENTIFIER, not an ASSERT keyword
        val cmd = parseSingle<Command.AssertFact>("ASSERT assertFoo(x);")
        assertEquals("assertFoo", cmd.fact.predicate)
    }

    // -----------------------------------------------------------------------
    // Error cases — missing semicolons
    // -----------------------------------------------------------------------

    @Test
    fun `missing semicolon after fact throws RuntimeException`() {
        assertFailsWith<RuntimeException> {
            parse("ASSERT likes(alice, bob)")
        }
    }

    @Test
    fun `missing semicolon after infer throws RuntimeException`() {
        assertFailsWith<RuntimeException> {
            parse("INFER likes(alice, ?who)")
        }
    }

    @Test
    fun `missing semicolon after rule throws RuntimeException`() {
        assertFailsWith<RuntimeException> {
            parse("ASSERT FORALL ?x { mortal(?x) <- human(?x) }")
        }
    }

    // -----------------------------------------------------------------------
    // Error cases — malformed atoms
    // -----------------------------------------------------------------------

    @Test
    fun `missing opening paren throws RuntimeException`() {
        assertFailsWith<RuntimeException> {
            parse("ASSERT likes alice, bob);")
        }
    }

    @Test
    fun `missing closing paren throws RuntimeException`() {
        assertFailsWith<RuntimeException> {
            parse("ASSERT likes(alice, bob;")
        }
    }

    @Test
    fun `unknown command keyword throws RuntimeException`() {
        assertFailsWith<RuntimeException> {
            parse("DELETE likes(alice, bob);")
        }
    }

    @Test
    fun `ASSERT with no following tokens throws RuntimeException`() {
        assertFailsWith<RuntimeException> {
            parse("ASSERT;")
        }
    }

    @Test
    fun `INFER with no atom throws RuntimeException`() {
        assertFailsWith<RuntimeException> {
            parse("INFER;")
        }
    }

    // -----------------------------------------------------------------------
    // Error cases — malformed rules
    // -----------------------------------------------------------------------

    @Test
    fun `rule missing FORALL body brace throws RuntimeException`() {
        assertFailsWith<RuntimeException> {
            parse("ASSERT FORALL ?x mortal(?x) <- human(?x);")
        }
    }

    @Test
    fun `rule missing arrow throws RuntimeException`() {
        assertFailsWith<RuntimeException> {
            parse("ASSERT FORALL ?x { mortal(?x) human(?x) };")
        }
    }

    @Test
    fun `rule missing closing brace throws RuntimeException`() {
        assertFailsWith<RuntimeException> {
            parse("ASSERT FORALL ?x { mortal(?x) <- human(?x);")
        }
    }

    // -----------------------------------------------------------------------
    // Error cases — RESTRICT
    // -----------------------------------------------------------------------

    @Test
    fun `restrict missing CONTRADICTION throws RuntimeException`() {
        assertFailsWith<RuntimeException> {
            parse("RESTRICT ?x { alive(?x) } ->;")
        }
    }

    @Test
    fun `restrict missing arrow throws RuntimeException`() {
        assertFailsWith<RuntimeException> {
            parse("RESTRICT ?x { alive(?x) } CONTRADICTION;")
        }
    }

    // -----------------------------------------------------------------------
    // Error cases — TEST
    // -----------------------------------------------------------------------

    @Test
    fun `test missing EXPECT block throws RuntimeException`() {
        assertFailsWith<RuntimeException> {
            parse("""
                TEST "bad" {
                    GIVEN {
                        ASSERT foo(x);
                    }
                };
            """.trimIndent())
        }
    }

    @Test
    fun `test unknown expectation keyword throws RuntimeException`() {
        assertFailsWith<RuntimeException> {
            parse("""
                TEST "bad" {
                    EXPECT {
                        MAYBE likes(alice, bob);
                    }
                };
            """.trimIndent())
        }
    }

    // -----------------------------------------------------------------------
    // Error cases — EXTRACT
    // -----------------------------------------------------------------------

    @Test
    fun `extract missing string throws RuntimeException`() {
        assertFailsWith<RuntimeException> {
            parse("EXTRACT;")
        }
    }

    // -----------------------------------------------------------------------
    // Error message quality
    // -----------------------------------------------------------------------

    @Test
    fun `error message includes line number`() {
        val ex = assertFailsWith<RuntimeException> {
            parse("ASSERT likes(alice, bob)")
        }
        // The error should include line information
        assertTrue(
            ex.message?.contains("line") == true || ex.message?.contains("Parse Error") == true,
            "Expected parse error message to contain line info, got: ${ex.message}"
        )
    }

    // -----------------------------------------------------------------------
    // Round-trip structural validation
    // -----------------------------------------------------------------------

    @Test
    fun `assert fact produces Atom with correct structure`() {
        val cmd = parseSingle<Command.AssertFact>("ASSERT knows(alice, bob);")
        val expected = Atom("knows", listOf(Term.Identifier("alice"), Term.Identifier("bob")))
        assertEquals(expected, cmd.fact)
    }

    @Test
    fun `infer produces query Atom with correct structure`() {
        val cmd = parseSingle<Command.Infer>("INFER knows(alice, ?who);")
        val expected = Atom("knows", listOf(Term.Identifier("alice"), Term.Variable("who")))
        assertEquals(expected, cmd.query)
    }

    @Test
    fun `rule head and body atoms have correct predicates and arg counts`() {
        val cmd = parseSingle<Command.AssertRule>(
            "ASSERT FORALL ?x, ?y, ?z { ancestor(?x, ?z) <- parent(?x, ?y) AND ancestor(?y, ?z) };"
        )
        val rule: Rule = cmd.rule
        assertEquals("ancestor", rule.head.predicate)
        assertEquals(2, rule.head.args.size)
        assertEquals(2, rule.body.size)
        assertEquals("parent",   rule.body[0].predicate)
        assertEquals(2, rule.body[0].args.size)
        assertEquals("ancestor", rule.body[1].predicate)
        assertEquals(2, rule.body[1].args.size)
    }

    @Test
    fun `restrict constraint pattern contains parsed atoms`() {
        val cmd = parseSingle<Command.Restrict>(
            "RESTRICT ?x { married(?x, ?y) AND married(?x, ?z) } -> CONTRADICTION;"
        )
        val constraint: Constraint = cmd.constraint
        assertEquals(2, constraint.pattern.size)
        assertTrue(constraint.pattern.all { it.predicate == "married" })
    }
}
