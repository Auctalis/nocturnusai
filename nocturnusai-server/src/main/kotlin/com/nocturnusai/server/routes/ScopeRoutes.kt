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

package com.nocturnusai.server.routes

import com.nocturnusai.core.MergeStrategy
import com.nocturnusai.server.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

// ─────────────────────────────────────────────────────────────────────────────
// Request / Response DTOs
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class ForkScopeRequest(
    /** Source scope to copy from.  Null = global (unscoped) partition. */
    val sourceScope: String? = null,
    /** Target scope name to copy into. Must be non-null/non-blank. */
    val targetScope: String
)

@Serializable
data class ForkScopeResponse(
    val copied: Int,
    val sourceScope: String?,
    val targetScope: String
)

@Serializable
data class DiffScopesRequest(
    val scopeA: String? = null,
    val scopeB: String? = null
)

// ── Scope DAG (Item 4: Counterfactual Simulation) ────────────────────────────

@Serializable
data class SetScopeParentRequest(
    /** The child scope that will inherit from [parent]. */
    val child: String,
    /** The parent scope to inherit from. */
    val parent: String
)

@Serializable
data class ScopeAncestorsResponse(
    val scope: String,
    val ancestors: List<String>
)

@Serializable
data class ScopeDagResponse(
    /** Map of child -> parent for all registered parent relationships. */
    val parents: Map<String, String>
)

@Serializable
data class MergeScopeRequest(
    val sourceScope: String,
    val targetScope: String? = null,
    val strategy: MergeStrategy = MergeStrategy.SOURCE_WINS
)

@Serializable
data class MergeScopeResponse(
    val merged: Int,
    val conflictsResolved: Int,
    val strategy: MergeStrategy,
    val timestamp: String
)

@Serializable
data class DeleteScopeResponse(
    val deleted: Int,
    val scope: String
)

@Serializable
data class ListScopesResponse(
    val scopes: List<String>,
    val count: Int
)

// ─────────────────────────────────────────────────────────────────────────────
// Route handler
// ─────────────────────────────────────────────────────────────────────────────

fun Route.scopeRoutes(dbManager: DatabaseManager) {

    /**
     * POST /scope/fork
     *
     * Fork (copy) all atoms from sourceScope into targetScope.
     * Body: { "sourceScope": null | "name", "targetScope": "name" }
     * Response: { "copied": N, "sourceScope": ..., "targetScope": ... }
     */
    post("/scope/fork") {
        try {
            val (db, tenantId) = call.getContext(dbManager)
            val req = call.receive<ForkScopeRequest>()

            if (req.targetScope.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("VALIDATION_ERROR", "targetScope must not be blank")
                )
                return@post
            }

            val copied = db.forkScope(req.sourceScope, req.targetScope, tenantId)
            call.respond(ForkScopeResponse(copied, req.sourceScope, req.targetScope))
        } catch (e: ValidationException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", e.message ?: "Validation error"))
        } catch (e: DatabaseNotFoundException) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", e.message ?: "Not found"))
        } catch (e: Exception) {
            call.application.environment.log.error("Fork scope error", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("INTERNAL_ERROR", e.message ?: "Internal error"))
        }
    }

    /**
     * POST /scope/diff
     *
     * Compare two scopes and return the differences.
     * Body: { "scopeA": null | "name", "scopeB": null | "name" }
     * Response: ScopeDiff JSON (onlyInA, onlyInB, inBoth, conflicts)
     */
    post("/scope/diff") {
        try {
            val (db, tenantId) = call.getContext(dbManager)
            val req = call.receive<DiffScopesRequest>()
            val diff = db.diffScopes(req.scopeA, req.scopeB, tenantId)
            call.respond(diff)
        } catch (e: ValidationException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", e.message ?: "Validation error"))
        } catch (e: DatabaseNotFoundException) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", e.message ?: "Not found"))
        } catch (e: Exception) {
            call.application.environment.log.error("Diff scopes error", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("INTERNAL_ERROR", e.message ?: "Internal error"))
        }
    }

    /**
     * POST /scope/merge
     *
     * Merge atoms from sourceScope into targetScope.
     * Body: { "sourceScope": "name", "targetScope": null | "name", "strategy": "SOURCE_WINS" | "TARGET_WINS" | "KEEP_BOTH" | "REJECT" }
     * Response: { "merged": N, "conflictsResolved": N, "strategy": "...", "timestamp": "..." }
     *
     * Returns 409 Conflict when strategy=REJECT and conflicts exist.
     */
    post("/scope/merge") {
        try {
            val (db, tenantId) = call.getContext(dbManager)
            val req = call.receive<MergeScopeRequest>()

            if (req.sourceScope.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("VALIDATION_ERROR", "sourceScope must not be blank")
                )
                return@post
            }

            val result = db.mergeScope(req.sourceScope, req.targetScope, req.strategy, tenantId)
            call.respond(MergeScopeResponse(result.merged, result.conflictsResolved, result.strategy, result.timestamp))
        } catch (e: IllegalStateException) {
            // REJECT strategy fired
            call.respond(HttpStatusCode.Conflict, ErrorResponse("CONFLICT", e.message ?: "Merge conflict"))
        } catch (e: ValidationException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", e.message ?: "Validation error"))
        } catch (e: DatabaseNotFoundException) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", e.message ?: "Not found"))
        } catch (e: Exception) {
            call.application.environment.log.error("Merge scope error", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("INTERNAL_ERROR", e.message ?: "Internal error"))
        }
    }

    /**
     * DELETE /scope/{name}
     *
     * Delete all atoms that belong to the named scope.
     * Response: { "deleted": N, "scope": "name" }
     */
    delete("/scope/{name}") {
        try {
            val (db, tenantId) = call.getContext(dbManager)
            val scopeName = call.parameters["name"]
                ?: return@delete call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("VALIDATION_ERROR", "Scope name is required in path")
                )

            if (scopeName.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", "Scope name must not be blank"))
                return@delete
            }

            val deleted = db.deleteScope(scopeName, tenantId)
            call.respond(DeleteScopeResponse(deleted, scopeName))
        } catch (e: ValidationException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", e.message ?: "Validation error"))
        } catch (e: DatabaseNotFoundException) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", e.message ?: "Not found"))
        } catch (e: Exception) {
            call.application.environment.log.error("Delete scope error", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("INTERNAL_ERROR", e.message ?: "Internal error"))
        }
    }

    /**
     * GET /scopes
     *
     * List all named scopes currently in the knowledge base.
     * Response: { "scopes": [...], "count": N }
     */
    get("/scopes") {
        try {
            val (db, tenantId) = call.getContext(dbManager)
            val scopes = db.listScopes(tenantId).sorted()
            call.respond(ListScopesResponse(scopes, scopes.size))
        } catch (e: ValidationException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", e.message ?: "Validation error"))
        } catch (e: DatabaseNotFoundException) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", e.message ?: "Not found"))
        } catch (e: Exception) {
            call.application.environment.log.error("List scopes error", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("INTERNAL_ERROR", e.message ?: "Internal error"))
        }
    }

    // ── Counterfactual Scope DAG endpoints (Item 4) ──────────────────────────

    /**
     * POST /scope/parent
     *
     * Set a parent scope, linking child -> parent in the DAG.
     * When querying the child scope with `inheritParentScopes=true`, facts from
     * the parent (and its ancestors) are returned if not overridden in the child.
     * Body: { "child": "Option_A", "parent": "Reality" }
     */
    post("/scope/parent") {
        try {
            val (db, _) = call.getContext(dbManager)
            val req = call.receive<SetScopeParentRequest>()
            if (req.child.isBlank() || req.parent.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", "child and parent must not be blank"))
                return@post
            }
            db.setScopeParent(req.child, req.parent)
            call.respond(mapOf("child" to req.child, "parent" to req.parent, "status" to "linked"))
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", e.message ?: "Invalid scope parent"))
        } catch (e: DatabaseNotFoundException) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", e.message ?: "Not found"))
        } catch (e: Exception) {
            call.application.environment.log.error("Set scope parent error", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("INTERNAL_ERROR", e.message ?: "Internal error"))
        }
    }

    /**
     * DELETE /scope/parent/{child}
     *
     * Remove the parent link for the given child scope (make it a root scope).
     */
    delete("/scope/parent/{child}") {
        try {
            val (db, _) = call.getContext(dbManager)
            val child = call.parameters["child"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", "child scope name required"))
            db.removeScopeParent(child)
            call.respond(mapOf("child" to child, "status" to "unlinked"))
        } catch (e: DatabaseNotFoundException) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", e.message ?: "Not found"))
        } catch (e: Exception) {
            call.application.environment.log.error("Remove scope parent error", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("INTERNAL_ERROR", e.message ?: "Internal error"))
        }
    }

    /**
     * GET /scope/ancestors/{scope}
     *
     * Returns the full ancestry chain for a scope: [scope, parent, grandparent, ...].
     * Response: { "scope": "Option_A", "ancestors": ["Option_A", "Reality"] }
     */
    get("/scope/ancestors/{scope}") {
        try {
            val (db, _) = call.getContext(dbManager)
            val scope = call.parameters["scope"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", "scope name required"))
            val ancestors = db.getScopeAncestors(scope)
            call.respond(ScopeAncestorsResponse(scope, ancestors))
        } catch (e: DatabaseNotFoundException) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", e.message ?: "Not found"))
        } catch (e: Exception) {
            call.application.environment.log.error("Scope ancestors error", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("INTERNAL_ERROR", e.message ?: "Internal error"))
        }
    }

    /**
     * GET /scope/dag
     *
     * Returns the full scope parent DAG as a map of child -> parent.
     * Response: { "parents": { "Option_A": "Reality", "Option_B": "Reality" } }
     */
    get("/scope/dag") {
        try {
            val (db, _) = call.getContext(dbManager)
            val dag = db.getScopeParentMap()
            call.respond(ScopeDagResponse(dag))
        } catch (e: DatabaseNotFoundException) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", e.message ?: "Not found"))
        } catch (e: Exception) {
            call.application.environment.log.error("Scope DAG error", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("INTERNAL_ERROR", e.message ?: "Internal error"))
        }
    }
}
