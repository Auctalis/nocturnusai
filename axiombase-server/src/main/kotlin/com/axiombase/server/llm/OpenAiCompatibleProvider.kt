package com.axiombase.server.llm

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.*

/**
 * Covers OpenAI, Ollama, Groq, Mistral, DeepSeek, Together AI —
 * any endpoint that implements the /v1/chat/completions contract.
 */
class OpenAiCompatibleProvider(
    override val model: String,
    private val apiKey: String?,
    private val baseUrl: String = "https://api.openai.com/v1"
) : LlmProvider {

    override val name: String = when {
        baseUrl.contains("openai.com") -> "openai"
        baseUrl.contains("groq.com") -> "groq"
        baseUrl.contains("mistral.ai") -> "mistral"
        baseUrl.contains("deepseek.com") -> "deepseek"
        baseUrl.contains("localhost") || baseUrl.contains("127.0.0.1") -> "ollama"
        else -> "openai-compatible"
    }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 120_000
        }
    }

    override suspend fun complete(messages: List<LlmMessage>): String {
        val messagesJson = buildJsonArray {
            for (msg in messages) {
                addJsonObject {
                    put("role", msg.role)
                    put("content", msg.content)
                }
            }
        }

        val requestBody = buildJsonObject {
            put("model", model)
            put("messages", messagesJson)
            put("temperature", 0.1)
        }

        val response = client.post("${baseUrl.trimEnd('/')}/chat/completions") {
            contentType(ContentType.Application.Json)
            if (!apiKey.isNullOrBlank()) {
                header("Authorization", "Bearer $apiKey")
            }
            setBody(requestBody.toString())
        }

        if (response.status.value !in 200..299) {
            val body = response.bodyAsText()
            throw RuntimeException("LLM API error (${response.status}): $body")
        }

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val choices = json["choices"]?.jsonArray
            ?: throw RuntimeException("No 'choices' in LLM response")
        val content = choices[0].jsonObject["message"]?.jsonObject?.get("content")?.jsonPrimitive?.content
            ?: throw RuntimeException("No content in LLM response")
        return content
    }
}
