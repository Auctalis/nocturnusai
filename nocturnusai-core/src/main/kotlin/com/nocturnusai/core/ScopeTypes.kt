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
 * The result of comparing two knowledge-base scopes.
 *
 * Atoms are compared by their logical identity (predicate + args + truthVal + scope-agnostic).
 * Two atoms are considered the same *position* when they share the same predicate + args.
 * They are a *conflict* when they share predicate + args but differ in truthVal.
 */
@Serializable
data class ScopeDiff(
    /** Atoms present only in scope A (not found in scope B at all). */
    val onlyInA: List<Atom>,
    /** Atoms present only in scope B (not found in scope A at all). */
    val onlyInB: List<Atom>,
    /** Atoms present in both scopes with the same predicate, args, and truthVal. */
    val inBoth: List<Atom>,
    /** Atoms present in both scopes with the same predicate + args but different truthVal. */
    val conflicts: List<ScopeConflict>
)

/**
 * A conflict between two scopes: same predicate + args, but opposing truth values.
 */
@Serializable
data class ScopeConflict(
    val predicate: String,
    val args: List<Term>,
    /** The version of this atom in scope A. */
    val inA: Atom,
    /** The version of this atom in scope B. */
    val inB: Atom
)

/**
 * Strategy for resolving conflicts when merging two scopes.
 */
@Serializable
enum class MergeStrategy {
    /** Source scope facts overwrite target scope facts on conflict. */
    SOURCE_WINS,
    /** Target scope facts are kept on conflict (source is ignored). */
    TARGET_WINS,
    /** Both conflicting versions are kept (may introduce contradictions). */
    KEEP_BOTH,
    /** Abort the merge if any conflicts are detected. */
    REJECT
}

/**
 * Summary of a completed scope merge operation.
 */
@Serializable
data class MergeResult(
    /** Number of atoms successfully copied from source into target. */
    val merged: Int,
    /** Number of conflicts that were resolved according to the chosen strategy. */
    val conflictsResolved: Int,
    /** The strategy that was applied. */
    val strategy: MergeStrategy,
    /** ISO-8601 timestamp of when the merge completed. */
    val timestamp: String
)
