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

import com.nocturnusai.persistence.SnapshotData
import com.nocturnusai.persistence.TenantSnapshotData
import com.nocturnusai.server.*
import io.ktor.server.routing.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

@Serializable
data class SnapshotResponse(
    val latestWalId: Long,
    val snapshot: SnapshotData
)

fun Route.replicationRoutes(dbManager: DatabaseManager) {
    if (ServerConfig.replicationMode == ReplicationMode.LEADER) {

        // GET /replication/wal/databases — list all database names for follower discovery
        get("/replication/wal/databases") {
            val names = dbManager.getDatabaseNames().toList().sorted()
            call.application.environment.log.info("Endpoint /replication/wal/databases hit. Returning ${names.size} databases.")
            call.respond(names)
        }

        // GET /replication/wal?database=X&since=Y — stream WAL entries for a specific database
        get("/replication/wal") {
            val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
            val dbName = call.request.queryParameters["database"] ?: "default"

            call.application.environment.log.info(
                "Endpoint /replication/wal hit. database=$dbName, since=$since"
            )

            val db = dbManager.getDatabase(dbName)
            if (db == null) {
                call.application.environment.log.warn("Replication: database '$dbName' not found")
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Database '$dbName' not found"))
                return@get
            }

            val entries = db.getWalEntries(since + 1)
            call.application.environment.log.info("Streaming WAL entries for database '$dbName'...")
            call.respondTextWriter(contentType = ContentType.Text.Plain) {
                entries.forEach { entry ->
                    appendLine(Json.encodeToString(entry))
                }
            }
        }

        // GET /replication/snapshot?database=X — return full snapshot + latest WAL ID for initial sync
        get("/replication/snapshot") {
            val dbName = call.request.queryParameters["database"] ?: "default"

            call.application.environment.log.info(
                "Endpoint /replication/snapshot hit. database=$dbName"
            )

            val db = dbManager.getDatabase(dbName)
            if (db == null) {
                call.application.environment.log.warn("Replication snapshot: database '$dbName' not found")
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Database '$dbName' not found"))
                return@get
            }

            // Force a snapshot so we have a consistent point-in-time state, then
            // return it together with the WAL ID at which the follower should start polling.
            db.createSnapshot()

            // Collect current state as a SnapshotData for the response
            val tenantData = mutableMapOf<String, TenantSnapshotData>()
            for (tenant in db.getRegisteredTenants()) {
                val allFacts = db.getAllFacts(tenant).toList()
                // getAllFacts returns positive facts; negative store is exposed through the snapshot
                tenantData[tenant] = TenantSnapshotData(
                    positives = allFacts.filter { it.truthVal },
                    negatives = allFacts.filter { !it.truthVal }
                )
            }

            // After createSnapshot() the WAL is cleared; the follower should start from 0
            val snapshotData = SnapshotData(
                timestamp = System.currentTimeMillis(),
                tenants = tenantData
            )

            val response = SnapshotResponse(latestWalId = 0L, snapshot = snapshotData)
            call.application.environment.log.info(
                "Snapshot served for database '$dbName': ${tenantData.size} tenants"
            )
            call.respond(response)
        }
    }
}
