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

package com.nocturnusai.server.core

import com.nocturnusai.core.SemanticContext
import com.nocturnusai.server.llm.LlmProvider
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt

class CachedSemanticContext(private val llmProvider: LlmProvider?) : SemanticContext {
    private val embeddingCache = ConcurrentHashMap<String, FloatArray>()

    override fun cosineSimilarity(a: String, b: String): Double {
        if (llmProvider == null) return 0.0
        
        // Quick exact match bypasses LLM
        if (a == b) return 1.0

        val embA = getEmbedding(a)
        val embB = getEmbedding(b)

        if (embA.isEmpty() || embB.isEmpty()) return 0.0
        
        return dotProduct(embA, embB) / (magnitude(embA) * magnitude(embB))
    }

    private fun getEmbedding(text: String): FloatArray {
        return embeddingCache.computeIfAbsent(text) { key ->
            try {
                val resultRef = AtomicReference(FloatArray(0))
                val thread = Thread {
                    resultRef.set(runBlocking { llmProvider!!.embed(key) })
                }
                thread.start()
                thread.join(30_000L)
                resultRef.get()
            } catch (e: Exception) {
                FloatArray(0)
            }
        }
    }

    private fun dotProduct(a: FloatArray, b: FloatArray): Double {
        var sum = 0.0
        val len = minOf(a.size, b.size)
        for (i in 0 until len) {
            sum += a[i] * b[i]
        }
        return sum
    }

    private fun magnitude(a: FloatArray): Double {
        var sumSq = 0.0
        for (v in a) {
            sumSq += v * v
        }
        return sqrt(sumSq)
    }
}
