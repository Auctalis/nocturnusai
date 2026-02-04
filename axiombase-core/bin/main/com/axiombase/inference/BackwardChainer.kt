package com.axiombase.inference

import com.axiombase.core.*
import com.axiombase.storage.Hexastore
import com.axiombase.inference.Substitution

class BackwardChainer(
    private val store: Hexastore,
    private val rules: List<Rule> // Needs access to rules. 
    // In ReteEngine, rules are local. In AxiomBase, we should share them.
    // For now, we will pass them or make them accessible.
) {

    /**
     * Tries to prove the goal using existing facts AND backward chaining on rules.
     * Returns a sequence of fully unified facts matching the goal.
     */
    fun solve(goal: Atom): Sequence<Atom> {
        return solveRecursive(listOf(goal), 0, emptyMap())
             .map { subst -> Unifier.substitute(goal, subst) }
             .distinct()
    }

    private fun solveRecursive(
        goals: List<Atom>,
        index: Int,
        subst: Substitution
    ): Sequence<Substitution> {
        if (index >= goals.size) {
            return sequenceOf(subst)
        }

        val currentGoal = Unifier.substitute(goals[index], subst)
        
        // 1. Unify with Fact (Base case)
        val factMatches = store.match(currentGoal).mapNotNull { fact ->
            Unifier.unifyAtoms(currentGoal, fact)
        }

        // 2. Unify with Rule Head (Recursive case)
        val ruleMatches = rules.asSequence().flatMap { rule ->
            // Rename variables in rule to avoid collision with current goal vars?
            // "Standardizing apart" is important in logic programming.
            // For prototype, we assume distinct variable names or rely on scope handling locally.
            // BUT strict correctness requires renaming (e.g. ?x in rule vs ?x in goal).
            // Let's implement simple renaming: append suffix to all variables in rule.
            val uniqueRule = renameVars(rule)
            
            val headMatch = Unifier.unifyAtoms(currentGoal, uniqueRule.head)
            if (headMatch != null) {
                // If head matches, we must prove the body
                // We add body clauses to the goal stack
                val newSubGoals = uniqueRule.body // These need to be solved
                // We must solve (body... then remaining goals)
                // But solveRecursive solves list sequentially.
                // Actually, we should solve the rule body given the 'headMatch' substitution.
                
                // Solve the RULE BODY first
                solveRecursive(uniqueRule.body, 0, subst + headMatch).flatMap { bodySubst ->
                    // After body is solved, we continue with original goals
                    // BUT we must propagate the substitution gained from the body
                    // back to the original computation path?
                    // solveRecursive logic: solve 'currentGoal', then 'nextGoal'.
                    // If 'currentGoal' is derived via Rule, we solved RuleBody.
                    // The result of RuleBody match gives us bindings for currentGoal.
                    
                    // So, we just return the substitution that satisfies currentGoal.
                     sequenceOf(bodySubst) // This subst contains bindings for currentGoal variables
                }
            } else {
                emptySequence()
            }
        }
        
        // Combine fact and rule matches
        return (factMatches + ruleMatches).flatMap { matchSubst ->
            val nextSubst = subst + matchSubst
            solveRecursive(goals, index + 1, nextSubst)
        }
    }
    
    // Simple standardization apart
    private fun renameVars(rule: Rule): Rule {
        val suffix = "_${System.nanoTime()}" // Simple unique suffix
        val renameMap = mutableMapOf<String, String>()
        
        fun rename(term: Term): Term {
            return when(term) {
                is Term.Variable -> {
                    val newName = term.name + suffix
                    Term.Variable(newName)
                }
                else -> term
            }
        }
        
        fun renameAtom(atom: Atom): Atom {
            return atom.copy(args = atom.args.map { rename(it) })
        }
        
        return Rule(
            variables = rule.variables.map { Term.Variable(it.name + suffix) },
            head = renameAtom(rule.head),
            body = rule.body.map { renameAtom(it) }
        )
    }
}
