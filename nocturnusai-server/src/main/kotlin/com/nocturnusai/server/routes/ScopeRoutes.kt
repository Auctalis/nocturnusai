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
}
