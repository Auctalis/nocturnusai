package com.axiombase.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

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
    val scope: String? = null,
    val metadata: Map<String, JsonElement> = emptyMap()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Atom) return false
        return predicate == other.predicate &&
               args == other.args &&
               truthVal == other.truthVal &&
               source == other.source &&
               scope == other.scope
    }

    override fun hashCode(): Int {
        var result = predicate.hashCode()
        result = 31 * result + args.hashCode()
        result = 31 * result + truthVal.hashCode()
        result = 31 * result + source.hashCode()
        result = 31 * result + (scope?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        val argsStr = args.joinToString(", ")
        val negation = if (truthVal) "" else "NOT "
        val scopeStr = if (scope != null) " @$scope" else ""
        return "$negation$predicate($argsStr)$scopeStr"
    }
}
