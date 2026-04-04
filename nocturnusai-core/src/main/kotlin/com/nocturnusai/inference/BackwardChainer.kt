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
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.*

class BackwardChainer(
    private val store: Hexastore,
    private val rules: List<Rule>,
    private val maxDepth: Int = 100, // Hard limit to prevent stack overflow
    private val semanticContext: SemanticContext = DummySemanticContext
) {

    /**
     * Tries to prove the goal using existing facts AND backward chaining on rules.
     * Returns a sequence of fully unified facts matching the goal.
     *
     * Confidence is carried from directly matched store facts to the result atom.
     * Rule-derived atoms have no confidence (null), since confidence aggregation across
     * a rule body is out of scope for the base inference engine.
     */
    fun solve(goal: Atom): Sequence<Atom> {
        val rulesByPredicate = rules.groupBy { it.head.predicate }
        val memo = HashMap<Atom, List<Atom>>()
        // We track (substitution, confidence) together so confidence from the matched fact
        // or rule aggregation is available when constructing the result atom.
        return solveRecursiveWithConfidence(listOf(goal), 0, emptyMap(), null, 0, rulesByPredicate, memo)
            .map { (subst, aggregatedConfidence) ->
                val result = Unifier.substitute(goal, subst)
                if (aggregatedConfidence != null) result.copy(confidence = aggregatedConfidence) else result
            }
            .distinctBy { it.args } // distinct by args (structural identity, ignoring metadata)
    }

    private fun solveRecursiveWithConfidence(
        goals: List<Atom>,
        index: Int,
        subst: Substitution,
        confidence: Double?,
        depth: Int,
        rulesByPredicate: Map<String, List<Rule>>,
        memo: HashMap<Atom, List<Atom>>
    ): Sequence<Pair<Substitution, Double?>> {
        if (depth > maxDepth) return emptySequence()
        if (index >= goals.size) return sequenceOf(Pair(subst, confidence))

        val currentGoal = Unifier.substitute(goals[index], subst)

        // --- HTTP_GET_JSON built-in predicate (Item 5: API-bound Extensional Logic) ---
        // Syntax: HTTP_GET_JSON("https://url", "$.json.path", ?result)
        // On success, binds ?result to the extracted JSON value string.
        // On failure (network error, missing path), the predicate fails.
        if (currentGoal.predicate == "HTTP_GET_JSON") {
            if (currentGoal.args.size != 3) {
                throw IllegalStateException("HTTP_GET_JSON requires exactly 3 args: url, jsonPath, ?binding. Got ${currentGoal.args.size}")
            }
            val urlArg = currentGoal.args[0]
            val pathArg = currentGoal.args[1]
            if (urlArg is Term.Variable || pathArg is Term.Variable) {
                throw IllegalStateException("HTTP_GET_JSON url and path must be ground (not variables)")
            }
            val url = urlArg.toString().removeSurrounding("\"")
            val jsonPath = pathArg.toString().removeSurrounding("\"")
            val resultBinding = currentGoal.args[2]

            val extracted = resolveHttpGetJson(url, jsonPath)
                ?: return emptySequence()  // fail if HTTP call or path extraction fails

            val extractedTerm = Term.StringLit(extracted)
            val newSubst = if (resultBinding is Term.Variable) {
                // Bind the variable to the extracted value
                subst + mapOf(resultBinding to extractedTerm)
            } else {
                // Arg is ground — unify and continue only if it matches
                if (resultBinding.toString().removeSurrounding("\"") == extracted) subst
                else return emptySequence()
            }
            // Scale confidence: HTTP facts inherit full confidence (no penalty)
            return solveRecursiveWithConfidence(goals, index + 1, newSubst, confidence, depth, rulesByPredicate, memo)
        }

        // --- Neuro-Symbolic SIMILAR predicate ---
        if (currentGoal.predicate == "SIMILAR") {
            if (currentGoal.args.size != 3) {
                throw IllegalStateException("SIMILAR requires exactly 3 arguments: a, b, threshold. Got ${currentGoal.args.size}")
            }
            val arg1 = currentGoal.args[0]
            val arg2 = currentGoal.args[1]
            val thresholdArg = currentGoal.args[2]

            if (arg1 is Term.Variable || arg2 is Term.Variable) {
                throw IllegalStateException("SIMILAR args must be grounded. Unbound variables: $arg1, $arg2")
            }
            
            val threshold = when (thresholdArg) {
                is Term.NumberLit -> thresholdArg.value
                else -> throw IllegalStateException("SIMILAR threshold must be a number literal")
            }

            val sim = semanticContext.cosineSimilarity(arg1.toString().removeSurrounding("\""), arg2.toString().removeSurrounding("\""))
            if (sim >= threshold) {
                val nextConfidence = when {
                    confidence == null -> sim
                    else -> confidence * sim
                }
                return solveRecursiveWithConfidence(goals, index + 1, subst, nextConfidence, depth, rulesByPredicate, memo)
            } else {
                return emptySequence()
            }
        }

        // --- Negation-as-Failure (NAF) handling ---
        if (currentGoal.naf) {
            val innerGoal = currentGoal.copy(naf = false)
            val unboundVars = innerGoal.args.filterIsInstance<Term.Variable>()
            if (unboundVars.isNotEmpty()) {
                throw IllegalStateException(
                    "NAF condition '${innerGoal}' contains unbound variable(s) " +
                    "${unboundVars.map { "?${it.name}" }} — NAF goals must be ground at evaluation time"
                )
            }
            val innerMemo = HashMap<Atom, List<Atom>>()
            val hasSolution = solveRecursiveWithConfidence(
                listOf(innerGoal), 0, emptyMap(), null, depth + 1, rulesByPredicate, innerMemo
            ).any()

            return if (hasSolution) {
                emptySequence()
            } else {
                solveRecursiveWithConfidence(goals, index + 1, subst, confidence, depth, rulesByPredicate, memo)
            }
        }

        val normalizedKey = normalizeForMemo(currentGoal)
        val resolvedAtoms: List<Atom> = memo[normalizedKey] ?: run {
            memo[normalizedKey] = emptyList()
            val results = mutableListOf<Atom>()
            store.match(currentGoal).forEach { fact -> results.add(fact) }
            val candidateRules = rulesByPredicate[currentGoal.predicate] ?: emptyList()
            for (rule in candidateRules) {
                val uniqueRule = renameVars(rule)
                val headMatch = Unifier.unifyAtoms(currentGoal, uniqueRule.head) ?: continue
                solveRecursiveWithConfidence(uniqueRule.body, 0, headMatch, uniqueRule.confidence, depth + 1, rulesByPredicate, memo)
                    .forEach { (bodySubst, branchConfidence) ->
                        results.add(Unifier.substitute(uniqueRule.head, bodySubst).copy(confidence = branchConfidence))
                    }
            }
            // For now, retaining distinct results might obscure different confidence paths,
            // but for safe recursion stopping, we must limit. In future iterations,
            // we might want `maxByOrNull { it.confidence }` grouping.
            val distinct = results.distinct()
            memo[normalizedKey] = distinct
            distinct
        }

        return resolvedAtoms.asSequence().mapNotNull { resultAtom ->
            val matchSubst = Unifier.unifyAtoms(currentGoal, resultAtom) ?: return@mapNotNull null
            // For both base facts and rule-derived atoms, we extract confidence
            val atomConfidence = resultAtom.confidence
            Pair(matchSubst, atomConfidence)
        }.flatMap { (matchSubst, atomConfidence) ->
            val nextSubst = subst + matchSubst
            // Propagate the multiplied confidence encountered along the solution path
            val nextConfidence = when {
                confidence == null && atomConfidence == null -> null
                confidence == null -> atomConfidence
                atomConfidence == null -> confidence
                else -> confidence * atomConfidence
            }
            solveRecursiveWithConfidence(goals, index + 1, nextSubst, nextConfidence, depth, rulesByPredicate, memo)
        }
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

        // --- Neuro-Symbolic SIMILAR predicate ---
        if (currentGoal.predicate == "SIMILAR") {
            if (currentGoal.args.size != 3) {
                throw IllegalStateException("SIMILAR requires exactly 3 arguments: a, b, threshold. Got ${currentGoal.args.size}")
            }
            val arg1 = currentGoal.args[0]
            val arg2 = currentGoal.args[1]
            val thresholdArg = currentGoal.args[2]

            if (arg1 is Term.Variable || arg2 is Term.Variable) {
                throw IllegalStateException("SIMILAR args must be grounded. Unbound variables: $arg1, $arg2")
            }
            
            val threshold = when (thresholdArg) {
                is Term.NumberLit -> thresholdArg.value
                else -> throw IllegalStateException("SIMILAR threshold must be a number literal")
            }

            val sim = semanticContext.cosineSimilarity(arg1.toString().removeSurrounding("\""), arg2.toString().removeSurrounding("\""))
            if (sim >= threshold) {
                return solveRecursive(goals, index + 1, subst, depth, rulesByPredicate, memo)
            } else {
                return emptySequence()
            }
        }

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

        // --- Neuro-Symbolic SIMILAR predicate handling for Proofs ---
        if (currentGoal.predicate == "SIMILAR") {
            if (currentGoal.args.size != 3) {
                throw IllegalStateException("SIMILAR requires exactly 3 arguments: a, b, threshold. Got ${currentGoal.args.size}")
            }
            val arg1 = currentGoal.args[0]
            val arg2 = currentGoal.args[1]
            val thresholdArg = currentGoal.args[2]

            if (arg1 is Term.Variable || arg2 is Term.Variable) {
                throw IllegalStateException("SIMILAR args must be grounded. Unbound variables: $arg1, $arg2")
            }
            
            val threshold = when (thresholdArg) {
                is Term.NumberLit -> thresholdArg.value
                else -> throw IllegalStateException("SIMILAR threshold must be a number literal")
            }

            val sim = semanticContext.cosineSimilarity(arg1.toString().removeSurrounding("\""), arg2.toString().removeSurrounding("\""))
            if (sim >= threshold) {
                val simFact = Atom(
                    "SIMILAR_EVALUATED", 
                    listOf(Term.StringLit(arg1.toString()), Term.StringLit(arg2.toString()), Term.NumberLit(sim))
                )
                val proofNode = ProofNode(
                    goal = currentGoal,
                    step = ProofStep.FactMatch(simFact),
                    substitution = emptyMap()
                )
                return solveRecursiveWithProof(goals, index + 1, subst, depth, rulesByPredicate).map { (restSubst, restProofs) ->
                    Pair(restSubst, listOf(proofNode) + restProofs)
                }
            } else {
                return emptySequence()
            }
        }

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

        /**
         * Makes a blocking HTTP GET to [url], parses the JSON response, and extracts
         * the value at [jsonPath] (supports simple `$.field` and `$.field.nested.path`).
         * Returns the extracted value as a String, or null on any error.
         */
        fun resolveHttpGetJson(url: String, jsonPath: String): String? {
            return try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 5_000
                connection.readTimeout = 10_000
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/json")
                val status = connection.responseCode
                if (status !in 200..299) return null
                val body = connection.inputStream.bufferedReader().readText()

                // Simple JSONPath: $.field or $.field.subfield ... etc
                val segments = jsonPath.trimStart('$', '.').split('.')
                var element: JsonElement = Json.parseToJsonElement(body)
                for (segment in segments) {
                    if (segment.isEmpty()) continue
                    element = when (element) {
                        is JsonObject -> element[segment] ?: return null
                        is JsonArray  -> element[segment.toInt()]
                        else          -> return null
                    }
                }
                // Unwrap primitive values
                when (element) {
                    is JsonPrimitive -> if (element.isString) element.content else element.toString()
                    else             -> element.toString()
                }
            } catch (e: Exception) {
                null  // network or parse error → predicate fails
            }
        }
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
            body = rule.body.map { renameAtom(it) },
            scope = rule.scope,
            confidence = rule.confidence
        )
    }
}
