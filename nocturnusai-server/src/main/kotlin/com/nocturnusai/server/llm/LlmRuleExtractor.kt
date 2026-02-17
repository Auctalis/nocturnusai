package com.nocturnusai.server.llm

import com.nocturnusai.extraction.ExtractedAtom
import com.nocturnusai.extraction.ExtractedFact
import com.nocturnusai.extraction.ExtractedRule
import com.nocturnusai.extraction.RuleExtractor
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

class LlmRuleExtractor(
    private val provider: LlmProvider,
    private val maxRules: Int = 10
) : RuleExtractor {

    private val logger = LoggerFactory.getLogger(LlmRuleExtractor::class.java)

    companion object {
        val VALID_TEMPLATE_TYPES = setOf(
            "MODUS_PONENS", "MODUS_TOLLENS", "FACT_CHAIN", "CAUSAL_ARGUMENT",
            "DEFINITIONAL_ARGUMENT", "PRACTICAL_ARGUMENT", "EVALUATIVE_ARGUMENT",
            "DISJUNCTIVE_SYLLOGISM"
        )
    }

    private val systemPrompt = """
You are a logical rule extraction engine. Given a set of extracted facts and the original text,
identify logical rules (implications) that connect these facts.

Rules format: Horn clauses — "head <- body" where head is derived if all body conditions hold.
Use ?variables (prefixed with ?) for generalized entities.

Use these recognized logical patterns to structure your rules:

1. MODUS_PONENS: Q(?x) <- P(?x)
   Example: Mortal(?x) <- Human(?x) — "All humans are mortal"

2. CAUSAL_ARGUMENT: Effect(?x) <- Cause(?x)
   Example: Flooded(?area) <- HeavyRain(?area) — "Heavy rain causes flooding"

3. FACT_CHAIN: B(?x) <- A(?x), then C(?x) <- B(?x)
   Example: GrandparentOf(?x,?z) <- ParentOf(?x,?y) AND ParentOf(?y,?z)

4. DEFINITIONAL_ARGUMENT: Category(?x) <- Feature(?x)
   Example: Mammal(?x) <- WarmBlooded(?x) AND HasFur(?x)

5. PRACTICAL_ARGUMENT: Conclusion(?x) <- Evidence(?x) AND NOT Exception(?x)
   Example: Eligible(?x) <- Applied(?x) AND NOT Disqualified(?x)

6. EVALUATIVE_ARGUMENT: Evaluation(?x) <- Criteria(?x)
   Example: HighQuality(?x) <- MeetsStandard(?x)

7. MODUS_TOLLENS: Q(?x) <- P(?x) paired with NOT P(?x) <- NOT Q(?x)
   Example: Mortal(?x) <- Human(?x) AND NOT Human(?x) <- NOT Mortal(?x)

8. DISJUNCTIVE_SYLLOGISM: Q(?x) <- NOT P(?x)
   Example: Guilty(?x) <- NOT Innocent(?x)

Guidelines:
- Only propose rules strongly supported by the text
- Use CamelCase predicates matching the extracted facts
- Variables should generalize specific entities (e.g., ?agency instead of "faa")
- Each rule should have 1-3 body conditions
- Each rule head should have 1-3 arguments
- Variables must appear in both head and body
- Classify each rule with the best matching templateType from the patterns above
- If no pattern clearly fits, set templateType to null
- Return valid JSON array only, no other text

Return format:
[{
  "head": {"predicate": "...", "args": ["?var1", "?var2"]},
  "body": [{"predicate": "...", "args": ["?var1", "?var2"], "negated": false}],
  "variables": ["var1", "var2"],
  "confidence": 0.0-1.0,
  "templateType": "MODUS_PONENS"
}]
""".trimIndent()

    override suspend fun extractRules(facts: List<ExtractedFact>, originalText: String): List<ExtractedRule> {
        if (facts.isEmpty()) return emptyList()

        val factsDescription = facts.joinToString("\n") { fact ->
            "  ${fact.predicate}(${fact.args.joinToString(", ")})"
        }

        val userMessage = buildString {
            append("Original text:\n$originalText\n\n")
            append("Extracted facts:\n$factsDescription\n\n")
            append("Identify logical rules (implications) connecting these facts.")
        }

        val messages = listOf(
            LlmMessage("system", systemPrompt),
            LlmMessage("user", userMessage)
        )

        val response = try {
            provider.complete(messages)
        } catch (e: Exception) {
            logger.error("LLM rule extraction failed: ${e.message}")
            throw RuntimeException("LLM rule extraction failed: ${e.message}", e)
        }

        return parseResponse(response)
    }

    private fun parseResponse(response: String): List<ExtractedRule> {
        val jsonStr = extractJsonArray(response)

        val jsonArray = try {
            Json.parseToJsonElement(jsonStr).jsonArray
        } catch (e: Exception) {
            // Try to recover truncated JSON by finding the last complete object
            val recovered = recoverTruncatedArray(jsonStr)
            if (recovered != null) {
                logger.warn("Recovered ${recovered.size} rules from truncated LLM response")
                recovered
            } else {
                logger.error("Failed to parse LLM rule response as JSON array: $response")
                throw RuntimeException("LLM returned invalid JSON for rules: ${e.message}")
            }
        }

        val rules = mutableListOf<ExtractedRule>()
        for (element in jsonArray) {
            if (rules.size >= maxRules) break
            try {
                val obj = element.jsonObject
                val head = parseAtom(obj["head"]?.jsonObject ?: continue) ?: continue
                val bodyArray = obj["body"]?.jsonArray ?: continue
                val body = bodyArray.mapNotNull { parseAtom(it.jsonObject) }
                if (body.isEmpty()) continue

                val variables = obj["variables"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                val confidence = obj["confidence"]?.jsonPrimitive?.floatOrNull ?: 1.0f

                if (head.predicate.isBlank()) continue
                if (confidence < 0f || confidence > 1f) continue

                val templateType = obj["templateType"]?.jsonPrimitive?.contentOrNull?.takeIf {
                    it in VALID_TEMPLATE_TYPES
                }

                // Validate that variables appear in both head and body
                val headVars = head.args.filter { it.startsWith("?") }.map { it.removePrefix("?") }.toSet()
                val bodyVars = body.flatMap { it.args.filter { a -> a.startsWith("?") }.map { a -> a.removePrefix("?") } }.toSet()
                val declaredVars = variables.toSet()

                // At least one variable should appear in both head and body
                val sharedVars = headVars.intersect(bodyVars)
                if (sharedVars.isEmpty() && headVars.isNotEmpty()) continue

                rules.add(ExtractedRule(
                    head = head,
                    body = body,
                    variables = if (declaredVars.isNotEmpty()) variables else (headVars + bodyVars).toList(),
                    confidence = confidence.coerceIn(0f, 1f),
                    templateType = templateType
                ))
            } catch (e: Exception) {
                logger.warn("Skipping malformed rule in LLM response: ${e.message}")
            }
        }

        return rules
    }

    private fun parseAtom(obj: JsonObject): ExtractedAtom? {
        val predicate = obj["predicate"]?.jsonPrimitive?.content ?: return null
        val args = obj["args"]?.jsonArray?.map { it.jsonPrimitive.content } ?: return null
        val negated = obj["negated"]?.jsonPrimitive?.booleanOrNull ?: false

        if (predicate.isBlank()) return null
        if (args.isEmpty() || args.size > 5) return null

        return ExtractedAtom(predicate, args, negated)
    }

    /**
     * Recover rules from truncated JSON array by finding the last complete object.
     * LLMs sometimes hit token limits mid-response, producing valid prefix JSON.
     */
    private fun recoverTruncatedArray(jsonStr: String): JsonArray? {
        // Find the last complete "}" that closes a rule object by looking for "},\n" or "}\n]"
        var depth = 0
        var lastCompleteEnd = -1
        var inString = false
        var escape = false

        for (i in jsonStr.indices) {
            val c = jsonStr[i]
            if (escape) { escape = false; continue }
            if (c == '\\' && inString) { escape = true; continue }
            if (c == '"') { inString = !inString; continue }
            if (inString) continue

            when (c) {
                '[', '{' -> depth++
                ']', '}' -> {
                    depth--
                    if (depth == 1 && c == '}') {
                        // We're back at array level after closing an object
                        lastCompleteEnd = i
                    }
                }
            }
        }

        if (lastCompleteEnd < 0) return null

        val fixedJson = jsonStr.substring(0, lastCompleteEnd + 1) + "]"
        return try {
            Json.parseToJsonElement(fixedJson).jsonArray
        } catch (e: Exception) {
            null
        }
    }

    private fun extractJsonArray(text: String): String {
        val trimmed = text.trim()
        if (trimmed.startsWith("[")) return trimmed

        // Strip markdown code fences and find the JSON array inside
        val codeBlockPattern = Regex("```(?:json)?\\s*\\n?(\\[.+])\\s*\\n?```", RegexOption.DOT_MATCHES_ALL)
        val match = codeBlockPattern.find(trimmed)
        if (match != null) return match.groupValues[1]

        val start = trimmed.indexOf('[')
        val end = trimmed.lastIndexOf(']')
        if (start >= 0 && end > start) return trimmed.substring(start, end + 1)

        throw RuntimeException("Could not find JSON array in LLM rule response")
    }
}
