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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Unit tests for [Tokenizer].
 *
 * Covers: keywords, identifiers, variables, numbers, strings, operators,
 * delimiters, comments, multi-line input, whitespace handling, and error cases.
 */
class TokenizerTest {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Tokenize [input] and return all tokens except the trailing EOF. */
    private fun tokens(input: String): List<Token> =
        Tokenizer(input).tokenize().dropLast(1) // drop EOF

    /** Tokenize and also return the EOF token. */
    private fun tokensWithEof(input: String): List<Token> =
        Tokenizer(input).tokenize()

    // -----------------------------------------------------------------------
    // EOF
    // -----------------------------------------------------------------------

    @Test
    fun `empty input produces only EOF`() {
        val result = tokensWithEof("")
        assertEquals(1, result.size)
        assertEquals(TokenType.EOF, result[0].type)
        assertEquals("", result[0].text)
    }

    @Test
    fun `whitespace-only input produces only EOF`() {
        val result = tokensWithEof("   \t  \n  ")
        assertEquals(1, result.size)
        assertEquals(TokenType.EOF, result[0].type)
    }

    // -----------------------------------------------------------------------
    // Keywords
    // -----------------------------------------------------------------------

    @Test
    fun `keyword ASSERT is recognised`() {
        val toks = tokens("ASSERT")
        assertEquals(1, toks.size)
        assertEquals(TokenType.ASSERT, toks[0].type)
        assertEquals("ASSERT", toks[0].text)
    }

    @Test
    fun `keyword INFER is recognised`() {
        val toks = tokens("INFER")
        assertEquals(1, toks.size)
        assertEquals(TokenType.INFER, toks[0].type)
    }

    @Test
    fun `keyword RESTRICT is recognised`() {
        assertEquals(TokenType.RESTRICT, tokens("RESTRICT")[0].type)
    }

    @Test
    fun `keyword EXPLAIN is recognised`() {
        assertEquals(TokenType.EXPLAIN, tokens("EXPLAIN")[0].type)
    }

    @Test
    fun `keyword FORALL is recognised`() {
        assertEquals(TokenType.FORALL, tokens("FORALL")[0].type)
    }

    @Test
    fun `keyword WITH is recognised`() {
        assertEquals(TokenType.WITH, tokens("WITH")[0].type)
    }

    @Test
    fun `keyword PROOF is recognised`() {
        assertEquals(TokenType.PROOF, tokens("PROOF")[0].type)
    }

    @Test
    fun `keyword CONTRADICTION is recognised`() {
        assertEquals(TokenType.CONTRADICTION, tokens("CONTRADICTION")[0].type)
    }

    @Test
    fun `keyword AND is recognised`() {
        assertEquals(TokenType.AND, tokens("AND")[0].type)
    }

    @Test
    fun `keyword NOT is recognised`() {
        assertEquals(TokenType.NOT, tokens("NOT")[0].type)
    }

    @Test
    fun `keyword TEST is recognised`() {
        assertEquals(TokenType.TEST, tokens("TEST")[0].type)
    }

    @Test
    fun `keyword GIVEN is recognised`() {
        assertEquals(TokenType.GIVEN, tokens("GIVEN")[0].type)
    }

    @Test
    fun `keyword EXPECT is recognised`() {
        assertEquals(TokenType.EXPECT, tokens("EXPECT")[0].type)
    }

    @Test
    fun `keyword PROVABLE is recognised`() {
        assertEquals(TokenType.PROVABLE, tokens("PROVABLE")[0].type)
    }

    @Test
    fun `keyword NOT_PROVABLE is recognised`() {
        assertEquals(TokenType.NOT_PROVABLE, tokens("NOT_PROVABLE")[0].type)
    }

    @Test
    fun `keyword COUNT is recognised`() {
        assertEquals(TokenType.COUNT, tokens("COUNT")[0].type)
    }

    @Test
    fun `keyword EXTRACT is recognised`() {
        assertEquals(TokenType.EXTRACT, tokens("EXTRACT")[0].type)
    }

    @Test
    fun `keyword DRY is recognised`() {
        assertEquals(TokenType.DRY, tokens("DRY")[0].type)
    }

    @Test
    fun `all keywords are case-sensitive - lowercase produces IDENTIFIER`() {
        // Keywords are uppercase-only; lowercase should be plain identifiers.
        val toks = tokens("assert infer forall")
        assertTrue(toks.all { it.type == TokenType.IDENTIFIER })
    }

    // -----------------------------------------------------------------------
    // Identifiers
    // -----------------------------------------------------------------------

    @Test
    fun `simple identifier`() {
        val toks = tokens("likes")
        assertEquals(1, toks.size)
        assertEquals(TokenType.IDENTIFIER, toks[0].type)
        assertEquals("likes", toks[0].text)
    }

    @Test
    fun `identifier with underscores`() {
        val toks = tokens("is_alive")
        assertEquals(1, toks.size)
        assertEquals(TokenType.IDENTIFIER, toks[0].type)
        assertEquals("is_alive", toks[0].text)
    }

    @Test
    fun `identifier with mixed case`() {
        val toks = tokens("grandParent")
        assertEquals(1, toks.size)
        assertEquals(TokenType.IDENTIFIER, toks[0].type)
        assertEquals("grandParent", toks[0].text)
    }

    @Test
    fun `identifier with digits after first letter`() {
        val toks = tokens("alice2")
        assertEquals(1, toks.size)
        assertEquals(TokenType.IDENTIFIER, toks[0].type)
        assertEquals("alice2", toks[0].text)
    }

    @Test
    fun `multiple identifiers separated by spaces`() {
        val toks = tokens("alice bob charlie")
        assertEquals(3, toks.size)
        toks.forEach { assertEquals(TokenType.IDENTIFIER, it.type) }
        assertEquals(listOf("alice", "bob", "charlie"), toks.map { it.text })
    }

    // -----------------------------------------------------------------------
    // Variables
    // -----------------------------------------------------------------------

    @Test
    fun `simple variable ?x`() {
        val toks = tokens("?x")
        assertEquals(1, toks.size)
        assertEquals(TokenType.VARIABLE, toks[0].type)
        // Stored text is the name without '?'
        assertEquals("x", toks[0].text)
    }

    @Test
    fun `multi-character variable ?who`() {
        val toks = tokens("?who")
        assertEquals(1, toks.size)
        assertEquals(TokenType.VARIABLE, toks[0].type)
        assertEquals("who", toks[0].text)
    }

    @Test
    fun `variable with underscore ?my_var`() {
        val toks = tokens("?my_var")
        assertEquals(1, toks.size)
        assertEquals(TokenType.VARIABLE, toks[0].type)
        assertEquals("my_var", toks[0].text)
    }

    @Test
    fun `variable with digits ?x1`() {
        val toks = tokens("?x1")
        assertEquals(1, toks.size)
        assertEquals(TokenType.VARIABLE, toks[0].type)
        assertEquals("x1", toks[0].text)
    }

    @Test
    fun `multiple variables`() {
        val toks = tokens("?x ?y ?z")
        assertEquals(3, toks.size)
        toks.forEach { assertEquals(TokenType.VARIABLE, it.type) }
        assertEquals(listOf("x", "y", "z"), toks.map { it.text })
    }

    // -----------------------------------------------------------------------
    // Numbers
    // -----------------------------------------------------------------------

    @Test
    fun `integer literal`() {
        val toks = tokens("42")
        assertEquals(1, toks.size)
        assertEquals(TokenType.NUMBER, toks[0].type)
        assertEquals("42", toks[0].text)
    }

    @Test
    fun `zero`() {
        val toks = tokens("0")
        assertEquals(1, toks.size)
        assertEquals(TokenType.NUMBER, toks[0].type)
        assertEquals("0", toks[0].text)
    }

    @Test
    fun `floating-point literal 3_14`() {
        val toks = tokens("3.14")
        assertEquals(1, toks.size)
        assertEquals(TokenType.NUMBER, toks[0].type)
        assertEquals("3.14", toks[0].text)
    }

    @Test
    fun `large integer`() {
        val toks = tokens("1000000")
        assertEquals(1, toks.size)
        assertEquals(TokenType.NUMBER, toks[0].type)
        assertEquals("1000000", toks[0].text)
    }

    // -----------------------------------------------------------------------
    // String literals
    // -----------------------------------------------------------------------

    @Test
    fun `simple string literal`() {
        val toks = tokens("\"hello\"")
        assertEquals(1, toks.size)
        assertEquals(TokenType.STRING, toks[0].type)
        // Text is the content without surrounding quotes
        assertEquals("hello", toks[0].text)
    }

    @Test
    fun `string with spaces`() {
        val toks = tokens("\"hello world\"")
        assertEquals(1, toks.size)
        assertEquals(TokenType.STRING, toks[0].type)
        assertEquals("hello world", toks[0].text)
    }

    @Test
    fun `empty string`() {
        val toks = tokens("\"\"")
        assertEquals(1, toks.size)
        assertEquals(TokenType.STRING, toks[0].type)
        assertEquals("", toks[0].text)
    }

    @Test
    fun `string with special characters`() {
        val toks = tokens("\"foo-bar_baz 123\"")
        assertEquals(1, toks.size)
        assertEquals(TokenType.STRING, toks[0].type)
        assertEquals("foo-bar_baz 123", toks[0].text)
    }

    // -----------------------------------------------------------------------
    // Operators and delimiters
    // -----------------------------------------------------------------------

    @Test
    fun `left-arrow operator`() {
        val toks = tokens("<-")
        assertEquals(1, toks.size)
        assertEquals(TokenType.ARROW_LEFT, toks[0].type)
        assertEquals("<-", toks[0].text)
    }

    @Test
    fun `right-arrow operator`() {
        val toks = tokens("->")
        assertEquals(1, toks.size)
        assertEquals(TokenType.ARROW_RIGHT, toks[0].type)
        assertEquals("->", toks[0].text)
    }

    @Test
    fun `left paren`() {
        val toks = tokens("(")
        assertEquals(1, toks.size)
        assertEquals(TokenType.LPAREN, toks[0].type)
    }

    @Test
    fun `right paren`() {
        val toks = tokens(")")
        assertEquals(1, toks.size)
        assertEquals(TokenType.RPAREN, toks[0].type)
    }

    @Test
    fun `comma`() {
        val toks = tokens(",")
        assertEquals(1, toks.size)
        assertEquals(TokenType.COMMA, toks[0].type)
    }

    @Test
    fun `semicolon`() {
        val toks = tokens(";")
        assertEquals(1, toks.size)
        assertEquals(TokenType.SEMICOLON, toks[0].type)
    }

    @Test
    fun `left brace`() {
        val toks = tokens("{")
        assertEquals(1, toks.size)
        assertEquals(TokenType.LBRACE, toks[0].type)
    }

    @Test
    fun `right brace`() {
        val toks = tokens("}")
        assertEquals(1, toks.size)
        assertEquals(TokenType.RBRACE, toks[0].type)
    }

    @Test
    fun `full atom token sequence`() {
        // likes(alice, bob)
        val toks = tokens("likes(alice, bob)")
        val types = toks.map { it.type }
        assertEquals(
            listOf(TokenType.IDENTIFIER, TokenType.LPAREN, TokenType.IDENTIFIER,
                   TokenType.COMMA, TokenType.IDENTIFIER, TokenType.RPAREN),
            types
        )
        assertEquals("likes", toks[0].text)
        assertEquals("alice", toks[2].text)
        assertEquals("bob",   toks[4].text)
    }

    // -----------------------------------------------------------------------
    // Comments
    // -----------------------------------------------------------------------

    @Test
    fun `hash comment strips rest of line`() {
        val toks = tokens("# this is a comment\nalice")
        assertEquals(1, toks.size)
        assertEquals(TokenType.IDENTIFIER, toks[0].type)
        assertEquals("alice", toks[0].text)
    }

    @Test
    fun `double-dash comment strips rest of line`() {
        val toks = tokens("-- this is a comment\nbob")
        assertEquals(1, toks.size)
        assertEquals(TokenType.IDENTIFIER, toks[0].type)
        assertEquals("bob", toks[0].text)
    }

    @Test
    fun `inline hash comment after tokens`() {
        val toks = tokens("alice # rest is comment")
        assertEquals(1, toks.size)
        assertEquals("alice", toks[0].text)
    }

    @Test
    fun `inline double-dash comment after tokens`() {
        val toks = tokens("alice -- rest is comment")
        assertEquals(1, toks.size)
        assertEquals("alice", toks[0].text)
    }

    @Test
    fun `multiple comment lines interleaved with tokens`() {
        val input = """
            # first comment
            alice
            -- second comment
            bob
        """.trimIndent()
        val toks = tokens(input)
        assertEquals(2, toks.size)
        assertEquals("alice", toks[0].text)
        assertEquals("bob",   toks[1].text)
    }

    // -----------------------------------------------------------------------
    // Line / position tracking
    // -----------------------------------------------------------------------

    @Test
    fun `token on line 1 has line = 1`() {
        val toks = tokensWithEof("alice")
        assertEquals(1, toks[0].line)
    }

    @Test
    fun `token after newline has incremented line number`() {
        val toks = tokensWithEof("alice\nbob")
        val aliceTok = toks.first { it.text == "alice" }
        val bobTok   = toks.first { it.text == "bob" }
        assertEquals(1, aliceTok.line)
        assertEquals(2, bobTok.line)
    }

    // -----------------------------------------------------------------------
    // Mixed realistic snippets
    // -----------------------------------------------------------------------

    @Test
    fun `ASSERT statement token sequence`() {
        val toks = tokens("ASSERT likes(alice, bob);")
        val types = toks.map { it.type }
        assertEquals(
            listOf(
                TokenType.ASSERT,
                TokenType.IDENTIFIER, TokenType.LPAREN,
                TokenType.IDENTIFIER, TokenType.COMMA,
                TokenType.IDENTIFIER, TokenType.RPAREN,
                TokenType.SEMICOLON
            ),
            types
        )
    }

    @Test
    fun `INFER statement with variable`() {
        val toks = tokens("INFER likes(alice, ?who);")
        val types = toks.map { it.type }
        assertEquals(
            listOf(
                TokenType.INFER,
                TokenType.IDENTIFIER, TokenType.LPAREN,
                TokenType.IDENTIFIER, TokenType.COMMA,
                TokenType.VARIABLE, TokenType.RPAREN,
                TokenType.SEMICOLON
            ),
            types
        )
        assertEquals("who", toks[5].text)
    }

    @Test
    fun `FORALL rule token sequence`() {
        val input = "ASSERT FORALL ?x { mortal(?x) <- human(?x) };"
        val toks = tokens(input)
        val types = toks.map { it.type }
        // ASSERT FORALL VARIABLE LBRACE IDENTIFIER LPAREN VARIABLE RPAREN
        // ARROW_LEFT IDENTIFIER LPAREN VARIABLE RPAREN RBRACE SEMICOLON
        assertEquals(TokenType.ASSERT, types[0])
        assertEquals(TokenType.FORALL, types[1])
        assertEquals(TokenType.VARIABLE, types[2])
        assertEquals(TokenType.LBRACE, types[3])
        assertEquals(TokenType.ARROW_LEFT, types.first { it == TokenType.ARROW_LEFT })
        assertEquals(TokenType.SEMICOLON, types.last())
    }

    @Test
    fun `number and string mixed with identifiers`() {
        val toks = tokens("age(alice, 30)")
        val types = toks.map { it.type }
        assertEquals(
            listOf(TokenType.IDENTIFIER, TokenType.LPAREN, TokenType.IDENTIFIER,
                   TokenType.COMMA, TokenType.NUMBER, TokenType.RPAREN),
            types
        )
        assertEquals("30", toks[4].text)
    }

    @Test
    fun `string arg produces STRING token`() {
        val toks = tokens("greeting(alice, \"hello\")")
        val types = toks.map { it.type }
        assertEquals(
            listOf(TokenType.IDENTIFIER, TokenType.LPAREN, TokenType.IDENTIFIER,
                   TokenType.COMMA, TokenType.STRING, TokenType.RPAREN),
            types
        )
        assertEquals("hello", toks[4].text)
    }

    // -----------------------------------------------------------------------
    // Error cases
    // -----------------------------------------------------------------------

    @Test
    fun `unexpected character throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            Tokenizer("@invalid").tokenize()
        }
    }

    @Test
    fun `percent sign throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            Tokenizer("foo % bar").tokenize()
        }
    }

    @Test
    fun `dollar sign throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            Tokenizer("${'$'}foo").tokenize()
        }
    }

    @Test
    fun `error message includes offending character`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            Tokenizer("@bad").tokenize()
        }
        assertTrue(ex.message?.contains("@") == true, "Error message should mention the bad character")
    }
}
