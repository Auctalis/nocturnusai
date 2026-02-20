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
import kotlinx.serialization.json.JsonElement

enum class SourceType {
    USER_INPUT,
    INFERRED,
    CONSOLIDATED
}

@Serializable
data class Atom(
    val predicate: String,
    val args: List<Term>,
    val truthVal: Boolean = true,
    val source: SourceType = SourceType.USER_INPUT,
    val scope: String? = null,
    val metadata: Map<String, JsonElement> = emptyMap(),
    // Temporal fields — excluded from equals/hashCode (like metadata)
    val createdAt: Long? = null,
    val validFrom: Long? = null,
    val validUntil: Long? = null,
    val ttl: Long? = null
) {
    /** Returns true if this atom is temporally valid at the given timestamp. */
    fun isValidAt(timestamp: Long): Boolean {
        if (validFrom != null && timestamp < validFrom) return false
        if (validUntil != null && timestamp >= validUntil) return false
        if (ttl != null && createdAt != null && timestamp >= createdAt + ttl) return false
        return true
    }

    /** Returns true if this atom has expired (validUntil passed or TTL elapsed). */
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean {
        if (validUntil != null && now >= validUntil) return true
        if (ttl != null && createdAt != null && now >= createdAt + ttl) return true
        return false
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Atom) return false
        return predicate == other.predicate &&
               args == other.args &&
               truthVal == other.truthVal &&
               source == other.source &&
               scope == other.scope
    }

    override fun hashCode(): Int {
        var result = predicate.hashCode()
        result = 31 * result + args.hashCode()
        result = 31 * result + truthVal.hashCode()
        result = 31 * result + source.hashCode()
        result = 31 * result + (scope?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        val argsStr = args.joinToString(", ")
        val negation = if (truthVal) "" else "NOT "
        val scopeStr = if (scope != null) " @$scope" else ""
        return "$negation$predicate($argsStr)$scopeStr"
    }
}
