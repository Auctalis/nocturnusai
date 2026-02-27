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

package com.nocturnusai.inference

import com.nocturnusai.core.*
import com.nocturnusai.storage.Hexastore
import com.nocturnusai.inference.Substitution
import com.nocturnusai.inference.Unifier
import java.util.concurrent.atomic.AtomicLong

class BackwardChainer(
    private val store: Hexastore,
    private val rules: List<Rule>,
    private val maxDepth: Int = 100 // Hard limit to prevent stack overflow
) {

    /**
     * Tries to prove the goal using existing facts AND backward chaining on rules.
     * Returns a sequence of fully unified facts matching the goal.
     */
    fun solve(goal: Atom): Sequence<Atom> {
        val rulesByPredicate = rules.groupBy { it.head.predicate }
        val memo = HashMap<Atom, List<Atom>>()
        return solveRecursive(listOf(goal), 0, emptyMap(), 0, rulesByPredicate, memo)
             .map { subst -> Unifier.substitute(goal, subst) }
             .distinct()
    }

    private fun solveRecursive(
        goals: List<Atom>,
        index: Int,
        subst: Substitution,
        depth: Int,
        rulesByPredicate: Map<String, List<Rule>>,
        memo: HashMap<Atom, List<Atom>>
    ): Sequence<Substitution> {
        if (depth > maxDepth) {
            // Log warning or just return empty?
            // Since we don't have easy logger access here without DI or global, we just return empty
            // to stop infinite recursion.
            return emptySequence()
        }

        if (index >= goals.size) {
            return sequenceOf(subst)
        }

        val currentGoal = Unifier.substitute(goals[index], subst)

        // --- Negation-as-Failure (NAF) handling ---
        // When the body condition carries naf=true, we attempt to prove the
        // inner goal (same atom with naf=false).  If no solutions exist the NAF
        // condition SUCCEEDS and we continue with the remaining goals, passing
        // the unchanged substitution.  If any solution exists the NAF condition
        // FAILS and we return an empty sequence for this branch.
        //
        // Safety guard: all variables in a NAF condition must be ground by the
        // time the condition is evaluated.  Unbound variables at NAF evaluation
        // indicate an unsafe rule and we throw rather than silently accept.
        if (currentGoal.naf) {
            // Strip NAF flag to get the positive inner goal
            val innerGoal = currentGoal.copy(naf = false)

            // Groundness check: no unbound variables allowed in NAF goals
            val unboundVars = innerGoal.args.filterIsInstance<Term.Variable>()
            if (unboundVars.isNotEmpty()) {
                throw IllegalStateException(
                    "NAF condition '${innerGoal}' contains unbound variable(s) " +
                    "${unboundVars.map { "?${it.name}" }} — NAF goals must be ground at evaluation time"
                )
            }

            // Try to prove the inner goal; NAF uses the same depth counter to
            // prevent depth budget from being exploited.
            val innerMemo = HashMap<Atom, List<Atom>>()
            val hasSolution = solveRecursive(
                listOf(innerGoal), 0, emptyMap(), depth + 1, rulesByPredicate, innerMemo
            ).any()

            return if (hasSolution) {
                // Inner goal IS provable — NAF fails
                emptySequence()
            } else {
                // Inner goal NOT provable — NAF succeeds; continue with current subst
                solveRecursive(goals, index + 1, subst, depth, rulesByPredicate, memo)
            }
        }

        // Memoization: normalize goal to use positional variable placeholders as cache key
        val normalizedKey = normalizeForMemo(currentGoal)
        val resolvedAtoms: List<Atom> = memo[normalizedKey] ?: run {
            // Sentinel: mark as in-progress to prevent infinite recursion on same goal
            memo[normalizedKey] = emptyList()

            val results = mutableListOf<Atom>()

            // 1. Unify with Facts (Base case)
            store.match(currentGoal).forEach { fact -> results.add(fact) }

            // 2. Unify with Rule Heads (Recursive case) — indexed by predicate
            val candidateRules = rulesByPredicate[currentGoal.predicate] ?: emptyList()
            for (rule in candidateRules) {
                val uniqueRule = renameVars(rule)
                val headMatch = Unifier.unifyAtoms(currentGoal, uniqueRule.head) ?: continue
                // Body goals only contain renamed rule variables; outer subst is irrelevant
                solveRecursive(uniqueRule.body, 0, headMatch, depth + 1, rulesByPredicate, memo)
                    .forEach { bodySubst ->
                        results.add(Unifier.substitute(uniqueRule.head, bodySubst))
                    }
            }

            val distinct = results.distinct()
            memo[normalizedKey] = distinct
            distinct
        }

        // Produce substitutions by unifying currentGoal with each resolved atom
        return resolvedAtoms.asSequence().mapNotNull { resultAtom ->
            Unifier.unifyAtoms(currentGoal, resultAtom)
        }.flatMap { matchSubst ->
            val nextSubst = subst + matchSubst
            solveRecursive(goals, index + 1, nextSubst, depth, rulesByPredicate, memo)
        }
    }
    
    /**
     * Proves the goal and returns proof trees showing the derivation path.
     */
    fun solveWithProof(goal: Atom): Sequence<ProofTree> {
        val rulesByPredicate = rules.groupBy { it.head.predicate }
        return solveRecursiveWithProof(listOf(goal), 0, emptyMap(), 0, rulesByPredicate)
            .map { (subst, proofs) ->
                val result = Unifier.substitute(goal, subst)
                ProofTree(result, proofs.first())
            }
            .distinctBy { it.result }
    }

    private fun solveRecursiveWithProof(
        goals: List<Atom>,
        index: Int,
        subst: Substitution,
        depth: Int,
        rulesByPredicate: Map<String, List<Rule>>
    ): Sequence<Pair<Substitution, List<ProofNode>>> {
        if (depth > maxDepth) return emptySequence()

        if (index >= goals.size) {
            return sequenceOf(Pair(subst, emptyList()))
        }

        val currentGoal = Unifier.substitute(goals[index], subst)

        // 1. Unify with facts
        val factMatches = store.match(currentGoal).mapNotNull { fact ->
            val unification = Unifier.unifyAtoms(currentGoal, fact)
            if (unification != null) Pair(unification, fact) else null
        }

        // 2. Unify with rule heads — indexed by predicate
        val candidateRules = rulesByPredicate[currentGoal.predicate] ?: emptyList()
        val ruleMatches = candidateRules.asSequence().flatMap { rule ->
            val originalRule = rule
            val uniqueRule = renameVars(rule)

            val headMatch = Unifier.unifyAtoms(currentGoal, uniqueRule.head)
            if (headMatch != null) {
                solveRecursiveWithProof(uniqueRule.body, 0, subst + headMatch, depth + 1, rulesByPredicate).map { (bodySubst, bodyProofs) ->
                    val substMap = bodySubst.entries.associate { (k, v) -> k.name to v.toString() }
                    val proofNode = ProofNode(
                        goal = currentGoal,
                        step = ProofStep.RuleApplication(originalRule, bodyProofs),
                        substitution = substMap
                    )
                    Triple(bodySubst, proofNode, true)
                }
            } else {
                emptySequence()
            }
        }

        // Combine: fact matches produce FactMatch nodes, rule matches produce RuleApplication nodes
        val factResults = factMatches.flatMap { (matchSubst, fact) ->
            val nextSubst = subst + matchSubst
            val substMap = nextSubst.entries.associate { (k, v) -> k.name to v.toString() }
            val proofNode = ProofNode(
                goal = currentGoal,
                step = ProofStep.FactMatch(fact),
                substitution = substMap
            )
            solveRecursiveWithProof(goals, index + 1, nextSubst, depth, rulesByPredicate).map { (restSubst, restProofs) ->
                Pair(restSubst, listOf(proofNode) + restProofs)
            }
        }

        val ruleResults = ruleMatches.flatMap { (matchSubst, proofNode, _) ->
            solveRecursiveWithProof(goals, index + 1, matchSubst, depth, rulesByPredicate).map { (restSubst, restProofs) ->
                Pair(restSubst, listOf(proofNode) + restProofs)
            }
        }

        return factResults + ruleResults
    }

    /**
     * Normalizes a goal for memo lookup by replacing variable names with positional
     * placeholders (?_0, ?_1, ...) so that goals differing only in variable names
     * hash to the same key.
     */
    private fun normalizeForMemo(goal: Atom): Atom {
        val varMapping = HashMap<String, Int>()
        var counter = 0
        val normalizedArgs = goal.args.map { term ->
            if (term is Term.Variable) {
                val idx = varMapping.getOrPut(term.name) { counter++ }
                Term.Variable("_$idx")
            } else {
                term
            }
        }
        return goal.copy(args = normalizedArgs)
    }

    companion object {
        private val varCounter = AtomicLong(0)
    }

    // Simple standardization apart — renames variables to unique names while
    // preserving all atom properties including the naf flag.
    private fun renameVars(rule: Rule): Rule {
        val suffix = "_${varCounter.incrementAndGet()}"

        fun rename(term: Term): Term {
            return when(term) {
                is Term.Variable -> {
                    val newName = term.name + suffix
                    Term.Variable(newName)
                }
                else -> term
            }
        }

        // copy() preserves naf and all other fields; only args are updated.
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
