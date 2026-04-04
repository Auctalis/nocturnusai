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

package com.nocturnusai.testing

import com.nocturnusai.core.*
import kotlinx.serialization.Serializable

@Serializable
sealed class Expectation {
    @Serializable
    data class Provable(val goal: Atom) : Expectation()

    @Serializable
    data class NotProvable(val goal: Atom) : Expectation()

    @Serializable
    data class ResultsExactly(val goal: Atom, val expected: List<Atom>) : Expectation()

    @Serializable
    data class ResultCount(val goal: Atom, val count: Int) : Expectation()
}

@Serializable
sealed class SetupAction {
    @Serializable
    data class AssertFact(val fact: Atom) : SetupAction()

    @Serializable
    data class AssertRule(val rule: Rule) : SetupAction()
}

@Serializable
data class TestCase(
    val name: String,
    val setup: List<SetupAction>,
    val expectations: List<Expectation>
)

@Serializable
data class ExpectationResult(
    val expectation: Expectation,
    val passed: Boolean,
    val message: String,
    val actual: List<Atom> = emptyList(),
    val proof: ProofTree? = null
)

@Serializable
data class TestResult(
    val name: String,
    val passed: Boolean,
    val expectationResults: List<ExpectationResult>,
    val durationMs: Long
)

@Serializable
data class TestSuiteResult(
    val total: Int,
    val passed: Int,
    val failed: Int,
    val results: List<TestResult>,
    val durationMs: Long
)
