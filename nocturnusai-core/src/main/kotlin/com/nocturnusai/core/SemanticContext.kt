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

package com.nocturnusai.core

/**
 * Interface that bridges symbolic logic with semantic/embedding space.
 * Allows the inference engine to compute similarity between terms.
 */
interface SemanticContext {
    /**
     * Compute cosine similarity between two strings, usually by embedding them.
     * Expected to return a value between -1.0 and 1.0 (though typically 0.0 to 1.0 for embeddings).
     */
    fun cosineSimilarity(a: String, b: String): Double
}

/**
 * A dummy implementation that always returns 0.0. 
 * Used when no semantic context is provided or available.
 */
object DummySemanticContext : SemanticContext {
    override fun cosineSimilarity(a: String, b: String): Double = 0.0
}
