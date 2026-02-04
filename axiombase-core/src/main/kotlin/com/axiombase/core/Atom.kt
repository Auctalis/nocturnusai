package com.axiombase.core

import kotlinx.serialization.Serializable

enum class SourceType {
    USER_INPUT,
    INFERRED
}

@Serializable
data class Atom(
    val predicate: String,
    val args: List<Term>,
    val truthVal: Boolean = true,
    val source: SourceType = SourceType.USER_INPUT,
    val scope: String? = null
) {
    override fun toString(): String {
        val argsStr = args.joinToString(", ")
        val negation = if (truthVal) "" else "NOT "
        val scopeStr = if (scope != null) " @$scope" else ""
        return "$negation$predicate($argsStr)$scopeStr"
    }
}
