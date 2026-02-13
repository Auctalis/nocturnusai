package com.axiombase.server.routes

import com.axiombase.server.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.transactionRoutes(dbManager: DatabaseManager) {
    post("/tx/begin") {
        try {
            val (db, tenantId) = call.getContext(dbManager)
            val id = db.transactionManager.begin(tenantId)
            call.respondText(id.toString())
        } catch (e: DatabaseNotFoundException) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", e.message ?: "Not found"))
        } catch (e: IllegalStateException) {
            call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("TOO_MANY_REQUESTS", e.message ?: "Too many active transactions"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("BAD_REQUEST", e.message ?: "Error"))
        }
    }

    post("/tx/commit/{id}") {
        try {
            val (db, _) = call.getContext(dbManager)
            val id = call.parameters["id"]?.toLongOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", "Invalid transaction ID"))
                return@post
            }
            db.transactionManager.commit(id)
            call.respondText("Committed $id")
        } catch (e: DatabaseNotFoundException) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", e.message ?: "Not found"))
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("CONFLICT", e.message ?: "Commit failed"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("INTERNAL_ERROR", e.message ?: "Error"))
        }
    }

    post("/tx/rollback/{id}") {
        try {
            val (db, _) = call.getContext(dbManager)
            val id = call.parameters["id"]?.toLongOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", "Invalid transaction ID"))
                return@post
            }
            db.transactionManager.rollback(id)
            call.respondText("Rolled back $id")
        } catch (e: DatabaseNotFoundException) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", e.message ?: "Not found"))
        } catch (e: Exception) {
             call.respond(HttpStatusCode.BadRequest, ErrorResponse("BAD_REQUEST", e.message ?: "Error"))
        }
    }
}
