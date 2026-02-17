package com.nocturnusai.core

import kotlinx.serialization.Serializable

@Serializable
data class Rule(
    val variables: List<Term.Variable>,
    val head: Atom, // Consequent
    val body: List<Atom>, // Antecedent (conditions)
    val scope: String? = null
) {
    override fun toString(): String {
        val vars = variables.joinToString(", ") { it.toString() }
        val conditions = body.joinToString(" AND ") { it.toString() }
        val scopeStr = if (scope != null) " @$scope" else ""
        return "FORALL $vars { $head <- $conditions }$scopeStr"
    }
}
