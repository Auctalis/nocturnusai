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

class GoogleProvider(
    override val model: String,
    private val apiKey: String
) : LlmProvider {

    override val name: String = "google"

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
        // Google Gemini uses a different format: contents[] with parts[]
        val systemMessage = messages.firstOrNull { it.role == "system" }?.content
        val conversationMessages = messages.filter { it.role != "system" }

        val contentsJson = buildJsonArray {
            for (msg in conversationMessages) {
                addJsonObject {
                    put("role", if (msg.role == "assistant") "model" else "user")
                    put("parts", buildJsonArray {
                        addJsonObject {
                            put("text", msg.content)
                        }
                    })
                }
            }
        }

        val requestBody = buildJsonObject {
            put("contents", contentsJson)
            if (systemMessage != null) {
                put("systemInstruction", buildJsonObject {
                    put("parts", buildJsonArray {
                        addJsonObject {
                            put("text", systemMessage)
                        }
                    })
                })
            }
            put("generationConfig", buildJsonObject {
                put("temperature", LlmConfig.temperature)
                put("maxOutputTokens", 8192)
                put("responseMimeType", "application/json")
            })
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

        val response = client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(requestBody.toString())
        }

        if (response.status.value !in 200..299) {
            val body = response.bodyAsText()
            throw RuntimeException("Google API error (${response.status}): $body")
        }

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val candidates = json["candidates"]?.jsonArray
            ?: throw RuntimeException("No 'candidates' in Google response")
        val parts = candidates[0].jsonObject["content"]?.jsonObject?.get("parts")?.jsonArray
            ?: throw RuntimeException("No parts in Google response")
        val text = parts[0].jsonObject["text"]?.jsonPrimitive?.content
            ?: throw RuntimeException("No text in Google response parts")
        return text
    }
}
