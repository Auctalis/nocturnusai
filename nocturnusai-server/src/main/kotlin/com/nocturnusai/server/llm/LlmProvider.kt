package com.nocturnusai.server.llm

data class LlmMessage(val role: String, val content: String)

interface LlmProvider {
    val name: String
    val model: String
    suspend fun complete(messages: List<LlmMessage>): String
}
