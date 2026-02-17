package com.nocturnusai.server.llm

import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

data class SynthesisResult(
    val answer: String,
    val derivation: List<String>,
    val missingContext: String
)

class LlmAnswerSynthesizer(
    private val provider: LlmProvider
) {

    private val logger = LoggerFactory.getLogger(LlmAnswerSynthesizer::class.java)

    private val systemPrompt = """
You are a knowledge base answer synthesizer. Given a question, query results from a logic
knowledge base, and proof trees showing derivation, produce a structured response.

Return JSON:
{
  "answer": "Natural language answer to the question.",
  "derivation": ["Fact1(arg1, arg2)", "Rule: Head <- Body"],
  "missingContext": "Description of any missing information or gaps."
}

Rules:
- Answer should be a natural, complete sentence
- If no results found, explain what was searched and why it wasn't found
- Derivation should list the specific facts and rules that produced the answer
- If the answer was derived through rules, show the chain of reasoning
- MissingContext should note any gaps, caveats, or information that would improve the answer
- If the answer is complete, say "No missing information."
- Return valid JSON only, no other text
""".trimIndent()

    suspend fun synthesize(
        question: String,
        matchedFacts: List<String>,
        proofDescriptions: List<String>,
        rulesUsed: List<String>
    ): SynthesisResult {
        val userMessage = buildString {
            append("Question: $question\n\n")

            if (matchedFacts.isNotEmpty()) {
                append("Matched facts from the knowledge base:\n")
                for (fact in matchedFacts) {
                    append("  $fact\n")
                }
                append("\n")
            } else {
                append("No matching facts were found in the knowledge base.\n\n")
            }

            if (proofDescriptions.isNotEmpty()) {
                append("Proof trees (derivation chains):\n")
                for (proof in proofDescriptions) {
                    append("  $proof\n")
                }
                append("\n")
            }

            if (rulesUsed.isNotEmpty()) {
                append("Rules used in derivation:\n")
                for (rule in rulesUsed) {
                    append("  $rule\n")
                }
            }
        }

        val messages = listOf(
            LlmMessage("system", systemPrompt),
            LlmMessage("user", userMessage)
        )

        val response = try {
            provider.complete(messages)
        } catch (e: Exception) {
            logger.error("LLM answer synthesis failed: ${e.message}")
            throw RuntimeException("LLM answer synthesis failed: ${e.message}", e)
        }

        return parseResponse(response)
    }

    private fun parseResponse(response: String): SynthesisResult {
        val jsonStr = extractJsonObject(response)

        val obj = try {
            Json.parseToJsonElement(jsonStr).jsonObject
        } catch (e: Exception) {
            logger.error("Failed to parse synthesis response as JSON: $response")
            throw RuntimeException("LLM returned invalid JSON for synthesis: ${e.message}")
        }

        val answer = obj["answer"]?.jsonPrimitive?.content
            ?: "Unable to synthesize an answer."
        val derivation = obj["derivation"]?.jsonArray?.mapNotNull {
            try { it.jsonPrimitive.content } catch (_: Exception) { null }
        } ?: emptyList()
        val missingContext = obj["missingContext"]?.jsonPrimitive?.content
            ?: "Unknown"

        return SynthesisResult(answer, derivation, missingContext)
    }

    private fun extractJsonObject(text: String): String {
        val trimmed = text.trim()
        if (trimmed.startsWith("{")) return trimmed

        val codeBlockPattern = Regex("```(?:json)?\\s*\\n?(\\{.+})\\s*\\n?```", RegexOption.DOT_MATCHES_ALL)
        val match = codeBlockPattern.find(trimmed)
        if (match != null) return match.groupValues[1]

        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start >= 0 && end > start) return trimmed.substring(start, end + 1)

        throw RuntimeException("Could not find JSON object in LLM synthesis response")
    }
}
