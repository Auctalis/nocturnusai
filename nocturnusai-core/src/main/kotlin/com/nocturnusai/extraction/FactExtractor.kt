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

package com.nocturnusai.extraction

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
