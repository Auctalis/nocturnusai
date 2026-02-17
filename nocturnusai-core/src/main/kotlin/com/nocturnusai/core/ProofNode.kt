package com.nocturnusai.core

import kotlinx.serialization.Serializable

@Serializable
sealed class ProofStep {
    @Serializable
    data class FactMatch(val fact: Atom) : ProofStep()

    @Serializable
    data class RuleApplication(val rule: Rule, val bodyProofs: List<ProofNode>) : ProofStep()
}

@Serializable
data class ProofNode(
    val goal: Atom,
    val step: ProofStep,
    val substitution: Map<String, String> = emptyMap()
)

@Serializable
data class ProofTree(
    val result: Atom,
    val proof: ProofNode
)
