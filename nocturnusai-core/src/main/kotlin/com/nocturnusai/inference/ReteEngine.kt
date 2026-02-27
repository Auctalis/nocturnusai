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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A simplified Rete-like Forward Chaining Engine.
 * It maintains a network of nodes representing rule conditions.
 * When facts are added, they propagate through the network to trigger rules.
 */
class ReteEngine(
    private val store: Hexastore,
    private val tracker: com.nocturnusai.logic.ProvenanceTracker? = null
) {

    private val rules = CopyOnWriteArrayList<Rule>()
    
    // For each rule, we maintain the state of partial matches for each stage of the antecedent.
    // RuleState: List<List<Substitution>>? 
    // Actually, we need a structure that reacts to specific predicates.
    
    // Map: Predicate -> List<Pair<Rule, ConditionIndex>>
    // When a fact with Predicate P arrives, we check all rules that have a condition with P.
    private val alphaNodes = mutableMapOf<String, MutableList<AlphaNode>>()

    init {
        // We need a way to trigger derived facts. 
        // In a real Rete, the production node executes the action. 
        // Here, the action is "Assert Fact".
        // We will need a callback or direct access to 'add' to the store, 
        // BUT we must be careful of infinite loops (though usually handled by 'set' semantics).
    }

    data class AlphaNode(
        val rule: Rule,
        val conditionIndex: Int,
        val condition: Atom
    )

    fun addRule(rule: Rule) {
        rules.add(rule)
        // Index the conditions.
        // NAF conditions do NOT trigger the rule directly (they have no positive
        // activating fact) — they are checked during solve() instead.
        rule.body.forEachIndexed { index, atom ->
            if (!atom.naf) {
                alphaNodes.computeIfAbsent(atom.predicate) { mutableListOf() }
                    .add(AlphaNode(rule, index, atom))
            }
        }

        // Retrospective: If we add a rule AFTER facts exist, we should potentially run against existing facts.
        // For this prototype, we assume rules function on NEW facts or we trigger a full run.
        // In "Project Initialization", we won't implement retrospective triggering yet unless needed.
    }

    // Called when a fact is asserted into the system
    fun onFactAsserted(fact: Atom) {
        // 1. Alpha Matching
        val nodes = alphaNodes[fact.predicate] ?: return
        
        for (node in nodes) {
            // Check if 'fact' unifies with 'node.condition'
            // node.condition usually contains variables ?x, ?y
            // fact contains constants A, B
            
            // However, we need to know if this fact helps complete the generic Rule.
            // Simplified Approach (Triggered Rete):
            // When a fact matches a condition in a rule, we attempt to evaluate the whole rule 
            // by querying the store for the OTHER conditions, consistent with the bindings from this fact.
            
            // This is "Triggered Evaluation" not pure State-Saving Rete, but efficient for many cases.
            // Pure Rete saves partial matches (Beta Memories). 
            // Given "O(1) read speeds" of the Hexastore, generated queries are fast. 
            
            // Let's try to match the specific condition
            val subst = Unifier.unifyAtoms(node.condition, fact)
            if (subst != null) {
                // This fact satisfies the condition at 'index'.
                // Now we try to satisfy the REST of the conditions using the Hexastore.
                checkRule(node.rule, subst)
            }
        }
    }

    // Checking a rule given a partial substitution
    private fun checkRule(rule: Rule, initialSubst: Substitution) {
        // We need to find bindings for ALL variables in the rule body.
        // We accumulate the FACTS that matched to build the proof tree.
        
        solve(rule.body, 0, initialSubst, emptyList()) { finalSubst, premises ->
             // If we get here, all conditions matched!
             // Instantiate the head
             val derivedFact = Unifier.substitute(rule.head, finalSubst)
             val inferredFact = derivedFact.copy(source = SourceType.INFERRED)

             // Skip if already in store — prevents infinite forward chaining loops
             val alreadyExists = store.match(inferredFact).any {
                 it.predicate == inferredFact.predicate &&
                 it.args == inferredFact.args &&
                 it.truthVal == inferredFact.truthVal
             }
             if (!alreadyExists) {
                 store.add(inferredFact)
                 tracker?.record(inferredFact, rule, premises)
                 onFactAsserted(inferredFact)
             }
        }
    }

    private fun solve(
        conditions: List<Atom>,
        index: Int,
        subst: Substitution,
        premises: List<Atom>,
        onMatch: (Substitution, List<Atom>) -> Unit
    ) {
        if (index >= conditions.size) {
            onMatch(subst, premises)
            return
        }

        val currentCond = conditions[index]
        val constrainedCond = Unifier.substitute(currentCond, subst)

        // --- NAF handling for forward chaining ---
        // For a NAF condition we check that the negated pattern does NOT exist in
        // the store.  Forward chaining works on ground facts so by the time we
        // evaluate a NAF condition all body variables before it should already be
        // bound; if unbound variables remain in the NAF atom we fail safely.
        if (constrainedCond.naf) {
            val innerCond = constrainedCond.copy(naf = false)

            // If any unbound variable remains in the NAF condition, we cannot
            // safely evaluate the closed-world check — skip this branch.
            val unboundVars = innerCond.args.filterIsInstance<com.nocturnusai.core.Term.Variable>()
            if (unboundVars.isNotEmpty()) {
                // Cannot evaluate NAF with unbound variables in forward chaining; skip.
                return
            }

            // Check whether the negated fact is present in the store.
            val existsInStore = store.match(innerCond).any {
                it.predicate == innerCond.predicate && it.args == innerCond.args
            }

            if (existsInStore) {
                // NAF condition fails — the negated pattern IS provable
                return
            }
            // NAF condition succeeds — the negated pattern is NOT in the store
            solve(conditions, index + 1, subst, premises, onMatch)
            return
        }

        val matches = store.match(constrainedCond)

        for (fact in matches) {
            val newBindings = Unifier.unifyAtoms(constrainedCond, fact)
            if (newBindings != null) {
                val nextSubst = subst + newBindings
                solve(conditions, index + 1, nextSubst, premises + fact, onMatch)
            }
        }
    }
}
