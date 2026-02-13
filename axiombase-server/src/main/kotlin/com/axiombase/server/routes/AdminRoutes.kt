package com.axiombase.server.routes

import com.axiombase.server.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File

fun Route.adminRoutes(dbManager: DatabaseManager) {
    post("/admin/databases") {
        try {
            val req = call.receive<CreateDbRequest>()
            Validator.validateDatabaseName(req.name)
            dbManager.createDatabase(req.name, true)
            call.respondText("Database '${req.name}' created (MultiTenant=true)")
        } catch (e: ValidationException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", e.message ?: "Validation error"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("BAD_REQUEST", e.message ?: "Error"))
        }
    }

    get("/admin/databases") {
         val dbs = dbManager.getDatabases()
         call.respond(dbs)
    }

    get("/admin/databases/{name}/facts") {
         val name = call.parameters["name"]
         val db = dbManager.getDatabase(name ?: "default")
         if (db == null) {
             call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Database not found"))
             return@get
         }
         val tenantId = call.request.header("X-Tenant-ID")
         val scope = call.request.queryParameters["scope"]

         val sequence = db.getStore(tenantId).getAllAtoms()
         val filtered = if (scope != null) sequence.filter { it.scope == scope } else sequence

         val facts = filtered.map { AtomResponse.from(it) }.toList()
         call.respond(facts)
    }

    get("/admin/databases/{name}/rules") {
         val name = call.parameters["name"]
         val db = dbManager.getDatabase(name ?: "default")
         if (db == null) {
             call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Database not found"))
             return@get
         }
         val tenantId = call.request.header("X-Tenant-ID")
         val scope = call.request.queryParameters["scope"]

         val rules = db.getRules(tenantId, scope).map { it.toString() }
         call.respond(rules)
    }

    delete("/admin/databases/{name}") {
         val name = call.parameters["name"]
         if (name == null) {
             call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", "Missing database name"))
             return@delete
         }
         try {
            dbManager.deleteDatabase(name)
            call.respondText("Database '$name' deleted")
         } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("BAD_REQUEST", e.message ?: "Error"))
         }
    }

    // --- Tenant CRUD ---
    post("/admin/databases/{name}/tenants") {
        val name = call.parameters["name"]
        val db = dbManager.getDatabase(name ?: "default")
        if (db == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Database not found"))
            return@post
        }
        try {
            val req = call.receive<CreateTenantRequest>()
            Validator.validateTenantId(req.tenantId)
            db.createTenant(req.tenantId)
            call.respondText("Tenant '${req.tenantId}' created")
        } catch (e: ValidationException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", e.message ?: "Validation error"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("BAD_REQUEST", e.message ?: "Error"))
        }
    }

    get("/admin/databases/{name}/tenants") {
        val name = call.parameters["name"]
        val db = dbManager.getDatabase(name ?: "default")
        if (db == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Database not found"))
            return@get
        }
        try {
            call.respond(db.getRegisteredTenants())
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("BAD_REQUEST", e.message ?: "Error"))
        }
    }

    delete("/admin/databases/{name}/tenants/{id}") {
        val name = call.parameters["name"]
        val id = call.parameters["id"]
        val db = dbManager.getDatabase(name ?: "default")
        if (db == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Database not found"))
            return@delete
        }
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", "Missing tenant ID"))
            return@delete
        }
        try {
            db.deleteTenant(id)
            call.respondText("Tenant '$id' deleted")
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("BAD_REQUEST", e.message ?: "Error"))
        }
    }

    // --- Nuke Endpoints ---

    post("/admin/databases/{name}/nuke") {
        val name = call.parameters["name"]
        val db = dbManager.getDatabase(name ?: "default")
        if (db == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Database not found"))
            return@post
        }
        try {
            db.nukeDatabase()
            call.respondText("Database '$name' has been nuked. All data cleared.")
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("INTERNAL_ERROR", e.message ?: "Error"))
        }
    }

    post("/admin/databases/{name}/tenants/{id}/nuke") {
        val name = call.parameters["name"]
        val id = call.parameters["id"]
        val db = dbManager.getDatabase(name ?: "default")
        if (db == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Database not found"))
            return@post
        }
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", "Missing tenant ID"))
            return@post
        }
        try {
            db.nukeTenant(id)
            call.respondText("Tenant '$id' in database '$name' has been nuked. All data cleared.")
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("INTERNAL_ERROR", e.message ?: "Error"))
        }
    }

    post("/admin/backups") {
         try {
             val backupDir = File(ServerConfig.storageDir.parentFile, "backups")

             val dbName = call.request.queryParameters["db"] ?: "default"
             val db = dbManager.getDatabase(dbName)
             if (db != null) {
                 val path = db.createBackup(backupDir)
                 call.respondText("Backup created at: ${path.absolutePath}")
             } else {
                 call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Database not found"))
             }

         } catch (e: Exception) {
             call.respond(HttpStatusCode.InternalServerError, ErrorResponse("INTERNAL_ERROR", e.message ?: "Error"))
         }
    }
}
