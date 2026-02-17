package com.nocturnusai.server

import com.nocturnusai.core.Atom
import com.nocturnusai.core.Rule
import com.nocturnusai.core.Term
import kotlinx.serialization.Serializable

@Serializable
enum class TemplateType {
    SYLLOGISM,
    MODUS_PONENS, // Same as Syllogism basically
    MODUS_TOLLENS,
    FACT_CHAIN,
    HYPOTHETICAL_SYLLOGISM,
    DISJUNCTIVE_SYLLOGISM,
    CONSTRUCTIVE_DILEMMA,
    DESTRUCTIVE_DILEMMA,
    CAUSAL_ARGUMENT,
    DEFINITIONAL_ARGUMENT,
    PRACTICAL_ARGUMENT,
    EVALUATIVE_ARGUMENT
}

@Serializable
data class TemplateRequest(
    val type: TemplateType,
    val predicates: Map<String, String>, // e.g. "P" -> "Man", "Q" -> "Mortal"
    val args: List<String>, // Variable names e.g. ["x"]
    val scope: String? = null
)

class TemplateService {

    fun generateRule(req: TemplateRequest): List<Rule> {
        val rules = mutableListOf<Rule>()
        val variables = req.args.map { Term.Variable(it) }
        
        when (req.type) {
            TemplateType.SYLLOGISM, TemplateType.MODUS_PONENS -> {
                // Rule: Q(x) <- P(x)
                // "All Men are Mortal"
                val p = req.predicates["P"] ?: throw IllegalArgumentException("Missing predicate P")
                val q = req.predicates["Q"] ?: throw IllegalArgumentException("Missing predicate Q")
                
                val head = Atom(q, variables)
                val body = listOf(Atom(p, variables))
                rules.add(Rule(variables, head, body, scope = req.scope))
            }
            TemplateType.MODUS_TOLLENS -> {
                // Classic: Q(x) <- P(x)
                // AND Contrapositive: NOT P(x) <- NOT Q(x)
                val p = req.predicates["P"] ?: throw IllegalArgumentException("Missing predicate P")
                val q = req.predicates["Q"] ?: throw IllegalArgumentException("Missing predicate Q")
                
                // 1. Forward
                val head1 = Atom(q, variables)
                val body1 = listOf(Atom(p, variables))
                rules.add(Rule(variables, head1, body1, scope = req.scope))
                
                // 2. Contrapositive (requires Native Negation support)
                // NOT P(x) <- NOT Q(x)
                val head2 = Atom(p, variables, truthVal = false)
                val body2 = listOf(Atom(q, variables, truthVal = false))
                rules.add(Rule(variables, head2, body2, scope = req.scope))
            }
            TemplateType.FACT_CHAIN, TemplateType.HYPOTHETICAL_SYLLOGISM -> {
                // A -> B -> C
                // Inputs: A, B, C predicates.
                // Rules: B(x) <- A(x), C(x) <- B(x)
                val chain = req.predicates.toSortedMap().values.toList() // P1, P2, P3...
                for (i in 0 until chain.size - 1) {
                     val prev = chain[i]
                     val next = chain[i+1]
                     val h = Atom(next, variables)
                     val b = listOf(Atom(prev, variables))
                     rules.add(Rule(variables, h, b, scope = req.scope))
                }
            }
            TemplateType.DISJUNCTIVE_SYLLOGISM -> {
                // "P or Q" implication.
                // Rules: Q(x) <- NOT P(x), P(x) <- NOT Q(x)
                val p = req.predicates["P"] ?: throw IllegalArgumentException("Missing predicate P")
                val q = req.predicates["Q"] ?: throw IllegalArgumentException("Missing predicate Q")
                
                // 1. If not P, then Q
                rules.add(Rule(variables, Atom(q, variables), listOf(Atom(p, variables, truthVal = false)), scope = req.scope))
                // 2. If not Q, then P
                rules.add(Rule(variables, Atom(p, variables), listOf(Atom(q, variables, truthVal = false)), scope = req.scope))
            }
            TemplateType.CONSTRUCTIVE_DILEMMA -> {
                // P -> R, Q -> S, (P or Q) -> (R or S)
                // We model the IMPLICATIONS (P->R, Q->S) and the DISJUNCTION (P or Q logic)
                val p = req.predicates["P"] ?: throw IllegalArgumentException("Missing P")
                val q = req.predicates["Q"] ?: throw IllegalArgumentException("Missing Q")
                val r = req.predicates["R"] ?: throw IllegalArgumentException("Missing R")
                val s = req.predicates["S"] ?: throw IllegalArgumentException("Missing S")
                
                // P -> R
                rules.add(Rule(variables, Atom(r, variables), listOf(Atom(p, variables)), scope = req.scope))
                // Q -> S
                rules.add(Rule(variables, Atom(s, variables), listOf(Atom(q, variables)), scope = req.scope))
                
                // Disjunction logic for P or Q (Q <- !P, P <- !Q)
                rules.add(Rule(variables, Atom(q, variables), listOf(Atom(p, variables, truthVal = false)), scope = req.scope))
                rules.add(Rule(variables, Atom(p, variables), listOf(Atom(q, variables, truthVal = false)), scope = req.scope))
            }
            TemplateType.DESTRUCTIVE_DILEMMA -> {
                // P -> R, Q -> S, (!R or !S) -> (!P or !Q)
                // Standard implications:
                rules.add(Rule(variables, Atom(req.predicates["R"]!!, variables), listOf(Atom(req.predicates["P"]!!, variables)), scope = req.scope))
                rules.add(Rule(variables, Atom(req.predicates["S"]!!, variables), listOf(Atom(req.predicates["Q"]!!, variables)), scope = req.scope))
                
                // Disjunction logic for !R or !S
                // If NOT (NOT R) -> NOT S => If R -> NOT S
                // Since this uses negations, it gets tricky.
                // Disjunction D1 v D2 means D2 <- !D1.
                // Here D1=!R, D2=!S.
                // So: !S <- !(!R) => !S <- R
                // And: !R <- !(!S) => !R <- S
                
                val r = req.predicates["R"]!!
                val s = req.predicates["S"]!!
                
                rules.add(Rule(variables, Atom(s, variables, false), listOf(Atom(r, variables, true)), scope = req.scope))
                rules.add(Rule(variables, Atom(r, variables, false), listOf(Atom(s, variables, true)), scope = req.scope))
            }
            TemplateType.CAUSAL_ARGUMENT -> {
                // Effect <- Cause
                val cause = req.predicates["CAUSE"] ?: throw IllegalArgumentException("Missing CAUSE")
                val effect = req.predicates["EFFECT"] ?: throw IllegalArgumentException("Missing EFFECT")
                rules.add(Rule(variables, Atom(effect, variables), listOf(Atom(cause, variables)), scope = req.scope))
            }
            TemplateType.DEFINITIONAL_ARGUMENT -> {
                // Category <- Feature
                val feature = req.predicates["FEATURE"] ?: throw IllegalArgumentException("Missing FEATURE")
                val category = req.predicates["CATEGORY"] ?: throw IllegalArgumentException("Missing CATEGORY")
                rules.add(Rule(variables, Atom(category, variables), listOf(Atom(feature, variables)), scope = req.scope))
            }
            TemplateType.PRACTICAL_ARGUMENT -> {
                // Conclusion <- Evidence AND NOT Exception
                val conclusion = req.predicates["CONCLUSION"] ?: throw IllegalArgumentException("Missing CONCLUSION")
                val evidence = req.predicates["EVIDENCE"] ?: throw IllegalArgumentException("Missing EVIDENCE")
                val exception = req.predicates["EXCEPTION"] ?: throw IllegalArgumentException("Missing EXCEPTION")
                
                // Using explicit negation for exception (Assumption: "I know there is no exception")
                // Or Negation as Failure? My system currently supports explicit assertion of negative facts.
                // If we want "Unless exception", we usually mean NAF.
                // But let's stick to Explicit Negation logic: "Conclusion is true if Evidence is true AND Explicitly NOT Exception is true".
                // User must assert "NOT Exception(x)" to activate this.
                
                 val head = Atom(conclusion, variables)
                 val body = listOf(
                     Atom(evidence, variables),
                     Atom(exception, variables, truthVal = false)
                 )
                 rules.add(Rule(variables, head, body, scope = req.scope))
            }
            TemplateType.EVALUATIVE_ARGUMENT -> {
                // Good(x) <- Criteria(x)
                 val good = req.predicates["EVALUATION"] ?: throw IllegalArgumentException("Missing EVALUATION")
                 val criteria = req.predicates["CRITERIA"] ?: throw IllegalArgumentException("Missing CRITERIA")
                 rules.add(Rule(variables, Atom(good, variables), listOf(Atom(criteria, variables)), scope = req.scope))
            }
        }
        return rules
    }
}
