package com.axiombase.extraction

import kotlinx.serialization.Serializable

@Serializable
data class ExtractedFact(
    val predicate: String,
    val args: List<String>,
    val confidence: Float = 1.0f
)

@Serializable
data class ExtractedAtom(
    val predicate: String,
    val args: List<String>,
    val negated: Boolean = false
)

@Serializable
data class ExtractedRule(
    val head: ExtractedAtom,
    val body: List<ExtractedAtom>,
    val variables: List<String>,
    val confidence: Float = 1.0f,
    val templateType: String? = null
)

interface FactExtractor {
    suspend fun extract(text: String, context: String? = null): List<ExtractedFact>
}

interface RuleExtractor {
    suspend fun extractRules(facts: List<ExtractedFact>, originalText: String): List<ExtractedRule>
}
