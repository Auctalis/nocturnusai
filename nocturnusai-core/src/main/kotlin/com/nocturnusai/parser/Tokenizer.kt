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

enum class TokenType {
    ASSERT, INFER, RESTRICT, EXPLAIN, FORALL,
    WITH, PROOF, CONTRADICTION,
    AND, NOT,
    TEST, GIVEN, EXPECT, PROVABLE, NOT_PROVABLE, COUNT,
    EXTRACT, DRY,
    ARROW_LEFT, ARROW_RIGHT, // <-, ->
    LBRACE, RBRACE, LPAREN, RPAREN, COMMA, SEMICOLON,
    IDENTIFIER, VARIABLE, STRING, NUMBER,
    EOF
}

data class Token(val type: TokenType, val text: String, val line: Int, val pos: Int)

class Tokenizer(private val input: String) {
    private var pos = 0
    private var line = 1
    private var lineStart = 0

    private val keywords = mapOf(
        "ASSERT" to TokenType.ASSERT,
        "INFER" to TokenType.INFER,
        "RESTRICT" to TokenType.RESTRICT,
        "EXPLAIN" to TokenType.EXPLAIN,
        "FORALL" to TokenType.FORALL,
        "WITH" to TokenType.WITH,
        "PROOF" to TokenType.PROOF,
        "CONTRADICTION" to TokenType.CONTRADICTION,
        "AND" to TokenType.AND,
        "NOT" to TokenType.NOT,
        "TEST" to TokenType.TEST,
        "GIVEN" to TokenType.GIVEN,
        "EXPECT" to TokenType.EXPECT,
        "PROVABLE" to TokenType.PROVABLE,
        "NOT_PROVABLE" to TokenType.NOT_PROVABLE,
        "COUNT" to TokenType.COUNT,
        "EXTRACT" to TokenType.EXTRACT,
        "DRY" to TokenType.DRY
    )

    fun tokenize(): List<Token> {
        val tokens = mutableListOf<Token>()
        while (pos < input.length) {
            val c = input[pos]
            when {
                c.isWhitespace() -> {
                    if (c == '\n') {
                        line++
                        lineStart = pos + 1
                    }
                    pos++
                }
                c == '#' || (c == '-' && peek() == '-') -> {
                    // Comment using # or --
                    skipComment()
                }
                c.isLetter() -> {
                    val start = pos
                    while (pos < input.length && (input[pos].isLetterOrDigit() || input[pos] == '_')) {
                        pos++
                    }
                    val text = input.substring(start, pos)
                    val type = keywords[text] ?: TokenType.IDENTIFIER
                    tokens.add(Token(type, text, line, start - lineStart))
                }
                c == '?' -> {
                    pos++ // Skip ?
                    val start = pos
                    while (pos < input.length && (input[pos].isLetterOrDigit() || input[pos] == '_')) {
                        pos++
                    }
                    val text = input.substring(start, pos)
                    tokens.add(Token(TokenType.VARIABLE, text, line, start - 1 - lineStart))
                }
                c == '"' -> {
                    val start = pos
                    pos++
                    while (pos < input.length && input[pos] != '"') {
                        pos++
                    }
                    pos++ // Skip closing quote
                    val text = input.substring(start + 1, pos - 1)
                    tokens.add(Token(TokenType.STRING, text, line, start - lineStart))
                }
                c.isDigit() -> {
                    val start = pos
                    while (pos < input.length && (input[pos].isDigit() || input[pos] == '.')) {
                        pos++
                    }
                    val text = input.substring(start, pos)
                    tokens.add(Token(TokenType.NUMBER, text, line, start - lineStart))
                }
                c == '<' && peek() == '-' -> {
                    tokens.add(Token(TokenType.ARROW_LEFT, "<-", line, pos - lineStart))
                    pos += 2
                }
                c == '-' && peek() == '>' -> {
                    tokens.add(Token(TokenType.ARROW_RIGHT, "->", line, pos - lineStart))
                    pos += 2
                }
                else -> {
                    val type = when (c) {
                        '{' -> TokenType.LBRACE
                        '}' -> TokenType.RBRACE
                        '(' -> TokenType.LPAREN
                        ')' -> TokenType.RPAREN
                        ',' -> TokenType.COMMA
                        ';' -> TokenType.SEMICOLON
                        else -> throw IllegalArgumentException("Unexpected character '$c' at line $line")
                    }
                    tokens.add(Token(type, c.toString(), line, pos - lineStart))
                    pos++
                }
            }
        }
        tokens.add(Token(TokenType.EOF, "", line, pos - lineStart))
        return tokens
    }

    private fun peek(): Char? {
        return if (pos + 1 < input.length) input[pos + 1] else null
    }

    private fun skipComment() {
        while (pos < input.length && input[pos] != '\n') {
            pos++
        }
    }
}
