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

import com.nocturnusai.server.*
import io.ktor.server.routing.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

fun Route.replicationRoutes(dbManager: DatabaseManager) {
    if (ServerConfig.replicationMode == ReplicationMode.LEADER) {
        get("/replication/wal") {
             val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
             // We stream WAL from default DB for now (Simplification)
             val db = dbManager.getDatabase("default")
             call.application.environment.log.info("Endpoint /replication/wal hit. Client requested since: $since")
             if (db != null) {
                 val entries = db.getWalEntries(since + 1)
                 call.application.environment.log.info("Streaming WAL entries...")
                 call.respondTextWriter(contentType = ContentType.Text.Plain) {
                     entries.forEach { entry ->
                         appendLine(Json.encodeToString(entry))
                     }
                 }
             } else {
                 call.application.environment.log.warn("Replication: Default database not found")
                 call.respond(HttpStatusCode.NotFound)
             }
        }
    }
}
