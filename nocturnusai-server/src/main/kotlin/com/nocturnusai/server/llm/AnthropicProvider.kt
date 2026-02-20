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

package com.nocturnusai.server.llm

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.*

class AnthropicProvider(
    override val model: String,
    private val apiKey: String
) : LlmProvider {

    override val name: String = "anthropic"

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
        // Anthropic separates system message from user/assistant messages
        val systemMessage = messages.firstOrNull { it.role == "system" }?.content
        val conversationMessages = messages.filter { it.role != "system" }

        val messagesJson = buildJsonArray {
            for (msg in conversationMessages) {
                addJsonObject {
                    put("role", msg.role)
                    put("content", msg.content)
                }
            }
        }

        val requestBody = buildJsonObject {
            put("model", model)
            put("max_tokens", 4096)
            put("messages", messagesJson)
            if (systemMessage != null) {
                put("system", systemMessage)
            }
            put("temperature", 0.1)
        }

        val response = client.post("https://api.anthropic.com/v1/messages") {
            contentType(ContentType.Application.Json)
            header("x-api-key", apiKey)
            header("anthropic-version", "2023-06-01")
            setBody(requestBody.toString())
        }

        if (response.status.value !in 200..299) {
            val body = response.bodyAsText()
            throw RuntimeException("Anthropic API error (${response.status}): $body")
        }

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val content = json["content"]?.jsonArray
            ?: throw RuntimeException("No 'content' in Anthropic response")
        // Extract text from first content block
        val text = content[0].jsonObject["text"]?.jsonPrimitive?.content
            ?: throw RuntimeException("No text in Anthropic response content")
        return text
    }
}
