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

/**
 * Strategy for resolving contradictions when asserting a fact whose negation already exists
 * in the same scope.
 *
 * - REJECT      (default): current behavior — throw IllegalArgumentException on contradiction.
 * - NEWEST_WINS : retract the existing contradictory fact and assert the new one.
 * - CONFIDENCE  : keep whichever fact has higher confidence; if equal or confidence is null,
 *                 the new fact wins.
 * - KEEP_BOTH   : skip the contradiction check entirely and store both.
 */
@Serializable
enum class ConflictStrategy {
    REJECT,
    NEWEST_WINS,
    CONFIDENCE,
    KEEP_BOTH
}
