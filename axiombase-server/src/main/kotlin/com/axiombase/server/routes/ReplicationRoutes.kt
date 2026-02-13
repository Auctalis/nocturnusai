package com.axiombase.server.routes

import com.axiombase.server.*
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
