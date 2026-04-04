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
sealed class Term {
    @Serializable
    data class Identifier(val name: String) : Term() {
        override fun toString(): String = name
    }

    @Serializable
    data class StringLit(val value: String) : Term() {
        override fun toString(): String = "\"$value\""
    }

    @Serializable
    data class NumberLit(val value: Double) : Term() {
        override fun toString(): String = value.toString()
    }

    @Serializable
    data class Variable(val name: String) : Term() {
        override fun toString(): String = "?$name"
    }
}
