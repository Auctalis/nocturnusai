package com.axiombase.server.llm

import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

data class QueryPattern(
    val predicate: String,
    val args: List<String>
)

class LlmQueryTranslator(
    private val provider: LlmProvider
) {

    private val logger = LoggerFactory.getLogger(LlmQueryTranslator::class.java)

    private val systemPrompt = """
You are a logic query translator. Given a natural language question and a knowledge base schema,
translate the question into one or more logic query patterns.

The knowledge base stores facts as: Predicate(arg1, arg2, ...)
Variables use ? prefix: ?x, ?count, ?who

Your job:
1. Identify which predicates are relevant to the question
2. Construct query patterns using the exact predicate names from the schema
3. Use ?variables for unknown values the user is asking about
4. Use exact identifiers from the schema for known entities

Return a JSON array of query patterns:
[{"predicate": "HasChildCount", "args": ["renee_good", "?count"]}]

Rules:
- Use EXACT predicate names from the provided schema
- Use EXACT entity identifiers from the schema examples
- Use ?variable for what the user is asking about
- Return 1-5 query patterns, ordered by relevance
- If the question maps to multiple facts, return multiple patterns
""".trimIndent()

    suspend fun translate(question: String, schema: Map<String, List<List<String>>>): List<QueryPattern> {
        val schemaDescription = buildString {
            append("Available predicates and example arguments:\n")
            for ((predicate, examples) in schema) {
                append("  $predicate:\n")
                for (example in examples) {
                    append("    $predicate(${example.joinToString(", ")})\n")
                }
            }
        }

        val userMessage = buildString {
            append("Question: $question\n\n")
            append(schemaDescription)
        }

        val messages = listOf(
            LlmMessage("system", systemPrompt),
            LlmMessage("user", userMessage)
        )

        val response = try {
            provider.complete(messages)
        } catch (e: Exception) {
            logger.error("LLM query translation failed: ${e.message}")
            throw RuntimeException("LLM query translation failed: ${e.message}", e)
        }

        return parseResponse(response)
    }

    private fun parseResponse(response: String): List<QueryPattern> {
        val jsonStr = extractJsonArray(response)

        val jsonArray = try {
            Json.parseToJsonElement(jsonStr).jsonArray
        } catch (e: Exception) {
            logger.error("Failed to parse query translation response as JSON array: $response")
            throw RuntimeException("LLM returned invalid JSON for query translation: ${e.message}")
        }

        val patterns = mutableListOf<QueryPattern>()
        for (element in jsonArray) {
            if (patterns.size >= 5) break
            try {
                val obj = element.jsonObject
                val predicate = obj["predicate"]?.jsonPrimitive?.content ?: continue
                val args = obj["args"]?.jsonArray?.map { it.jsonPrimitive.content } ?: continue

                if (predicate.isBlank()) continue
                if (args.isEmpty() || args.size > 5) continue

                patterns.add(QueryPattern(predicate, args))
            } catch (e: Exception) {
                logger.warn("Skipping malformed query pattern in LLM response: ${e.message}")
            }
        }

        return patterns
    }

    private fun extractJsonArray(text: String): String {
        val trimmed = text.trim()
        if (trimmed.startsWith("[")) return trimmed

        val codeBlockPattern = Regex("```(?:json)?\\s*\\n?(\\[.+])\\s*\\n?```", RegexOption.DOT_MATCHES_ALL)
        val match = codeBlockPattern.find(trimmed)
        if (match != null) return match.groupValues[1]

        val start = trimmed.indexOf('[')
        val end = trimmed.lastIndexOf(']')
        if (start >= 0 && end > start) return trimmed.substring(start, end + 1)

        throw RuntimeException("Could not find JSON array in LLM query translation response")
    }
}
