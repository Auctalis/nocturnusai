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
import com.nocturnusai.inference.BackwardChainer
import com.nocturnusai.storage.Hexastore
import java.util.concurrent.CopyOnWriteArrayList

class TestRunner {

    fun run(testCase: TestCase): TestResult {
        val startTime = System.currentTimeMillis()

        // Isolated context per test — no persistence, no shared state
        val store = Hexastore()
        val rules = CopyOnWriteArrayList<Rule>()

        // Setup
        for (action in testCase.setup) {
            when (action) {
                is SetupAction.AssertFact -> store.add(action.fact)
                is SetupAction.AssertRule -> rules.add(action.rule)
            }
        }

        val chainer = BackwardChainer(store, rules)

        // Evaluate expectations
        val expectationResults = testCase.expectations.map { expectation ->
            evaluateExpectation(expectation, chainer)
        }

        val passed = expectationResults.all { it.passed }
        val durationMs = System.currentTimeMillis() - startTime

        return TestResult(
            name = testCase.name,
            passed = passed,
            expectationResults = expectationResults,
            durationMs = durationMs
        )
    }

    fun runSuite(testCases: List<TestCase>): TestSuiteResult {
        val startTime = System.currentTimeMillis()
        val results = testCases.map { run(it) }
        val durationMs = System.currentTimeMillis() - startTime

        return TestSuiteResult(
            total = results.size,
            passed = results.count { it.passed },
            failed = results.count { !it.passed },
            results = results,
            durationMs = durationMs
        )
    }

    private fun evaluateExpectation(
        expectation: Expectation,
        chainer: BackwardChainer
    ): ExpectationResult {
        return when (expectation) {
            is Expectation.Provable -> {
                val proofTrees = chainer.solveWithProof(expectation.goal).toList()
                if (proofTrees.isNotEmpty()) {
                    ExpectationResult(
                        expectation = expectation,
                        passed = true,
                        message = "Goal ${expectation.goal} is provable (${proofTrees.size} solution(s))",
                        actual = proofTrees.map { it.result },
                        proof = proofTrees.first()
                    )
                } else {
                    ExpectationResult(
                        expectation = expectation,
                        passed = false,
                        message = "Goal ${expectation.goal} is NOT provable (expected provable)"
                    )
                }
            }

            is Expectation.NotProvable -> {
                val proofTrees = chainer.solveWithProof(expectation.goal).toList()
                if (proofTrees.isEmpty()) {
                    ExpectationResult(
                        expectation = expectation,
                        passed = true,
                        message = "Goal ${expectation.goal} is correctly not provable"
                    )
                } else {
                    ExpectationResult(
                        expectation = expectation,
                        passed = false,
                        message = "Goal ${expectation.goal} IS provable (expected not provable, got ${proofTrees.size} solution(s))",
                        actual = proofTrees.map { it.result },
                        proof = proofTrees.first()
                    )
                }
            }

            is Expectation.ResultsExactly -> {
                val results = chainer.solve(expectation.goal).toList()
                val expectedSet = expectation.expected.toSet()
                val actualSet = results.toSet()
                if (actualSet == expectedSet) {
                    ExpectationResult(
                        expectation = expectation,
                        passed = true,
                        message = "Results match exactly (${results.size} result(s))",
                        actual = results
                    )
                } else {
                    val missing = expectedSet - actualSet
                    val extra = actualSet - expectedSet
                    val details = buildString {
                        if (missing.isNotEmpty()) append("Missing: $missing. ")
                        if (extra.isNotEmpty()) append("Extra: $extra.")
                    }
                    ExpectationResult(
                        expectation = expectation,
                        passed = false,
                        message = "Results do not match. $details",
                        actual = results
                    )
                }
            }

            is Expectation.ResultCount -> {
                val results = chainer.solve(expectation.goal).toList()
                if (results.size == expectation.count) {
                    ExpectationResult(
                        expectation = expectation,
                        passed = true,
                        message = "Result count matches: ${expectation.count}",
                        actual = results
                    )
                } else {
                    ExpectationResult(
                        expectation = expectation,
                        passed = false,
                        message = "Expected ${expectation.count} result(s), got ${results.size}",
                        actual = results
                    )
                }
            }
        }
    }
}
