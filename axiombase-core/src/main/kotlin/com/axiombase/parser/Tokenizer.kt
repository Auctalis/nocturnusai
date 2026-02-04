package com.axiombase.parser

enum class TokenType {
    ASSERT, INFER, RESTRICT, EXPLAIN, FORALL,
    WITH, PROOF, CONTRADICTION,
    AND, NOT,
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
        "NOT" to TokenType.NOT
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
