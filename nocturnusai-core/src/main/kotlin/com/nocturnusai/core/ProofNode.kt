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

import kotlinx.serialization.Serializable

@Serializable
sealed class ProofStep {
    @Serializable
    data class FactMatch(val fact: Atom) : ProofStep()

    @Serializable
    data class RuleApplication(val rule: Rule, val bodyProofs: List<ProofNode>) : ProofStep()
}

@Serializable
data class ProofNode(
    val goal: Atom,
    val step: ProofStep,
    val substitution: Map<String, String> = emptyMap()
)

@Serializable
data class ProofTree(
    val result: Atom,
    val proof: ProofNode
)
