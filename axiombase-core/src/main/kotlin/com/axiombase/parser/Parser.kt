package com.axiombase.parser

import com.axiombase.core.*
import com.axiombase.logic.Constraint
import com.axiombase.testing.TestCase

sealed class Command {
    data class AssertFact(val fact: Atom) : Command()
    data class AssertRule(val rule: Rule) : Command()
    data class Infer(val query: Atom, val withProof: Boolean) : Command()
    data class Restrict(val constraint: Constraint) : Command()
    data class Explain(val fact: Atom) : Command()
    data class Test(val testCase: TestCase) : Command()
    data class Extract(val text: String, val dryRun: Boolean) : Command()
}

class Parser(private val tokens: List<Token>) {
    private var current = 0

    fun parse(): List<Command> {
        val commands = mutableListOf<Command>()
        while (!isAtEnd()) {
            commands.add(parseCommand())
        }
        return commands
    }

    private fun parseCommand(): Command {
        val token = advance()
        return when (token.type) {
            TokenType.ASSERT -> parseAssertion()
            TokenType.INFER -> parseQuery()
            TokenType.RESTRICT -> parseConstraint()
            TokenType.EXPLAIN -> parseExplanation()
            TokenType.TEST -> parseTest()
            TokenType.EXTRACT -> parseExtract()
            else -> throw error(token, "Expected command start (ASSERT, INFER, RESTRICT, EXPLAIN, TEST, EXTRACT)")
        }
    }

    // ASSERT ( Fact | Rule ) ;
    private fun parseAssertion(): Command {
        // Look ahead to distinguish Rule vs Fact
        // Rule starts with FORALL
        // Fact starts with Identifier
        if (check(TokenType.FORALL)) {
            val rule = parseRule()
            consume(TokenType.SEMICOLON, "Expected ';' after rule")
            return Command.AssertRule(rule)
        } else {
            val fact = parseAtom()
            consume(TokenType.SEMICOLON, "Expected ';' after fact")
            return Command.AssertFact(fact)
        }
    }

    // FORALL ?vars { Consequent <- Antecedent }
    private fun parseRule(): Rule {
        consume(TokenType.FORALL, "Expected FORALL")
        val variables = mutableListOf<Term.Variable>()
        
        // Parse variable list: ?x, ?y
        // Assuming comma separated or just space separated? Grammar says "VariableList"
        // Let's assume comma separated based on precedent, or just sequence until '{'
        
        while (!check(TokenType.LBRACE)) {
            if (check(TokenType.VARIABLE)) {
                 val name = advance().text
                 variables.add(Term.Variable(name))
            } else if (check(TokenType.COMMA)) {
                advance()
            } else {
                throw error(peek(), "Expected variable or '{'")
            }
        }

        consume(TokenType.LBRACE, "Expected '{'")
        
        // Consequent <- Antecedent
        val head = parseAtom()
        consume(TokenType.ARROW_LEFT, "Expected '<-'")
        
        val body = parseAntecedent()
        
        consume(TokenType.RBRACE, "Expected '}'")
        
        return Rule(variables, head, body)
    }
    
    // Condition { AND Condition }
    private fun parseAntecedent(): List<Atom> {
        val conditions = mutableListOf<Atom>()
        conditions.add(parseCondition())
        while (match(TokenType.AND)) {
            conditions.add(parseCondition())
        }
        return conditions
    }
    
    private fun parseCondition(): Atom {
        // Can be Fact | Comparison | Negation
        // Not supporting Comparison/Negation fully in this pass, mostly structural
        return parseAtom()
    }

    // INFER Goal [ WITH PROOF ] ;
    private fun parseQuery(): Command {
        val goal = parseAtom()
        var withProof = false
        if (match(TokenType.WITH)) {
            consume(TokenType.PROOF, "Expected PROOF after WITH")
            withProof = true
        }
        consume(TokenType.SEMICOLON, "Expected ';'")
        return Command.Infer(goal, withProof)
    }

    // RESTRICT Pattern -> CONTRADICTION ;
    private fun parseConstraint(): Command {
        // Pattern matches Antecedent syntax typically?
        // Grammar: Pattern -> CONTRADICTION
        // The pattern is "Restricted variables { conditions }" in user example: "RESTRICT ?x { ... }"
        // But grammar says: RESTRICT Pattern -> CONTRADICTION
        // User example: RESTRICT ?x { Status(?x, Dead) AND Status(?x, Alive) } -> CONTRADICTION;
        
        // Let's skip the "?x" part if it's there and just parse the block or the conditions.
        // Actually, the example includes scoped variables.
        
        // To be safe, let's parse: optionally variables, then LBRACE Conditions RBRACE, or just Conditions
        // The grammar in spec: "RESTRICT Pattern -> CONTRADICTION"
        // Let's follow the user EXAMPLE more closely.
        
        while (check(TokenType.VARIABLE) || check(TokenType.COMMA)) {
            advance() // Skip restrict variables for now, treat as implicit
        }
        
        consume(TokenType.LBRACE, "Expected '{' for restriction pattern")
        val conditions = parseAntecedent()
        consume(TokenType.RBRACE, "Expected '}'")
        
        consume(TokenType.ARROW_RIGHT, "Expected '->'")
        consume(TokenType.CONTRADICTION, "Expected 'CONTRADICTION'")
        consume(TokenType.SEMICOLON, "Expected ';'")
        
        return Command.Restrict(Constraint(conditions))
    }

    // EXPLAIN Fact ;
    private fun parseExplanation(): Command {
        val fact = parseAtom()
        consume(TokenType.SEMICOLON, "Expected ';'")
        return Command.Explain(fact)
    }

    // EXTRACT "text" [DRY] ;
    private fun parseExtract(): Command {
        val textToken = consume(TokenType.STRING, "Expected text string after EXTRACT")
        val dryRun = match(TokenType.DRY)
        consume(TokenType.SEMICOLON, "Expected ';' after EXTRACT")
        return Command.Extract(textToken.text, dryRun)
    }

    // TEST "name" { GIVEN { ... } EXPECT { ... } } ;
    private fun parseTest(): Command {
        val nameToken = consume(TokenType.STRING, "Expected test name string")
        val testName = nameToken.text

        consume(TokenType.LBRACE, "Expected '{' after test name")

        val setupActions = mutableListOf<com.axiombase.testing.SetupAction>()
        val expectations = mutableListOf<com.axiombase.testing.Expectation>()

        // Parse GIVEN block
        if (match(TokenType.GIVEN)) {
            consume(TokenType.LBRACE, "Expected '{' after GIVEN")
            while (!check(TokenType.RBRACE)) {
                setupActions.add(parseSetupAction())
            }
            consume(TokenType.RBRACE, "Expected '}' to close GIVEN block")
        }

        // Parse EXPECT block
        consume(TokenType.EXPECT, "Expected EXPECT block")
        consume(TokenType.LBRACE, "Expected '{' after EXPECT")
        while (!check(TokenType.RBRACE)) {
            expectations.add(parseExpectation())
        }
        consume(TokenType.RBRACE, "Expected '}' to close EXPECT block")

        consume(TokenType.RBRACE, "Expected '}' to close TEST block")
        consume(TokenType.SEMICOLON, "Expected ';' after TEST")

        return Command.Test(TestCase(testName, setupActions, expectations))
    }

    private fun parseSetupAction(): com.axiombase.testing.SetupAction {
        consume(TokenType.ASSERT, "Expected ASSERT in GIVEN block")
        return if (check(TokenType.FORALL)) {
            val rule = parseRule()
            consume(TokenType.SEMICOLON, "Expected ';' after rule in GIVEN")
            com.axiombase.testing.SetupAction.AssertRule(rule)
        } else {
            val fact = parseAtom()
            consume(TokenType.SEMICOLON, "Expected ';' after fact in GIVEN")
            com.axiombase.testing.SetupAction.AssertFact(fact)
        }
    }

    private fun parseExpectation(): com.axiombase.testing.Expectation {
        return when {
            match(TokenType.PROVABLE) -> {
                val goal = parseAtom()
                consume(TokenType.SEMICOLON, "Expected ';' after PROVABLE expectation")
                com.axiombase.testing.Expectation.Provable(goal)
            }
            match(TokenType.NOT_PROVABLE) -> {
                val goal = parseAtom()
                consume(TokenType.SEMICOLON, "Expected ';' after NOT_PROVABLE expectation")
                com.axiombase.testing.Expectation.NotProvable(goal)
            }
            match(TokenType.COUNT) -> {
                val goal = parseAtom()
                val countToken = consume(TokenType.NUMBER, "Expected count number after goal")
                val count = countToken.text.toDouble().toInt()
                consume(TokenType.SEMICOLON, "Expected ';' after COUNT expectation")
                com.axiombase.testing.Expectation.ResultCount(goal, count)
            }
            else -> throw error(peek(), "Expected PROVABLE, NOT_PROVABLE, or COUNT")
        }
    }

    // Identifier "(" Term { "," Term } ")"
    private fun parseAtom(): Atom {
        val nameToken = consume(TokenType.IDENTIFIER, "Expected predicate identifier")
        val predicate = nameToken.text
        
        consume(TokenType.LPAREN, "Expected '('")
        val args = mutableListOf<Term>()
        if (!check(TokenType.RPAREN)) {
            args.add(parseTerm())
            while (match(TokenType.COMMA)) {
                args.add(parseTerm())
            }
        }
        consume(TokenType.RPAREN, "Expected ')'")
        
        return Atom(predicate, args)
    }

    private fun parseTerm(): Term {
        if (match(TokenType.VARIABLE)) return Term.Variable(previous().text)
        if (match(TokenType.STRING)) return Term.StringLit(previous().text)
        if (match(TokenType.NUMBER)) return Term.NumberLit(previous().text.toDouble())
        if (match(TokenType.IDENTIFIER)) return Term.Identifier(previous().text)
        throw error(peek(), "Expected term")
    }

    // Helpers
    private fun match(vararg types: TokenType): Boolean {
        for (type in types) {
            if (check(type)) {
                advance()
                return true
            }
        }
        return false
    }

    private fun check(type: TokenType): Boolean {
        if (isAtEnd()) return false
        return peek().type == type
    }

    private fun advance(): Token {
        if (!isAtEnd()) current++
        return previous()
    }

    private fun isAtEnd(): Boolean {
        return peek().type == TokenType.EOF
    }

    private fun peek(): Token {
        return tokens[current]
    }

    private fun previous(): Token {
        return tokens[current - 1]
    }

    private fun consume(type: TokenType, message: String): Token {
        if (check(type)) return advance()
        throw error(peek(), message)
    }

    private fun error(token: Token, message: String): RuntimeException {
        return RuntimeException("Parse Error at line ${token.line}: $message")
    }
}
