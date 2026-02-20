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
data class Rule(
    val variables: List<Term.Variable>,
    val head: Atom, // Consequent
    val body: List<Atom>, // Antecedent (conditions)
    val scope: String? = null
) {
    override fun toString(): String {
        val vars = variables.joinToString(", ") { it.toString() }
        val conditions = body.joinToString(" AND ") { it.toString() }
        val scopeStr = if (scope != null) " @$scope" else ""
        return "FORALL $vars { $head <- $conditions }$scopeStr"
    }
}
