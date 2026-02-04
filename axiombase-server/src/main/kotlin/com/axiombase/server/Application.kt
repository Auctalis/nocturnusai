package com.axiombase.server

import com.axiombase.AxiomBase
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

fun main() {
    embeddedServer(Netty, port = ServerConfig.port, host = ServerConfig.host, module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) {
        json()
    }
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
        allowHeader("X-Transaction-ID")
        allowHeader("Authorization")
        allowHeader("X-API-Key")
        allowHeader("X-Database")
        allowHeader("X-Tenant-ID")
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)
    }

    // Database Manager
    val dbManager = DatabaseManager(ServerConfig.storageDir)
    
    // Helper to get DB context
    fun getContext(call: ApplicationCall): Pair<AxiomBase, String?> {
        val dbName = call.request.header("X-Database") ?: "default"
        val tenantId = call.request.header("X-Tenant-ID")
        val db = dbManager.getDatabase(dbName) 
            ?: throw IllegalArgumentException("Database '$dbName' not found")
        return Pair(db, tenantId)
    }

    // Authentication Middleware
    intercept(ApplicationCallPipeline.Call) {
        val apiKey = ServerConfig.apiKey
        if (apiKey != null) {
            val keyContext = call.request.header("X-API-Key")
            // Allow health check without auth?
            val isPublic = call.request.uri == "/health" || call.request.uri == "/metrics"
            
            if (!isPublic && keyContext != apiKey) {
                call.respondText("Unauthorized", status = io.ktor.http.HttpStatusCode.Unauthorized)
                return@intercept finish()
            }
        }
    }

    routing {
        // ... (rest of routing)
        
        post("/admin/databases") {
            try {
                val req = call.receive<CreateDbRequest>()
                dbManager.createDatabase(req.name, req.isMultiTenant)
                call.respondText("Database '${req.name}' created (MultiTenant=${req.isMultiTenant})")
            } catch (e: Exception) {
                call.respond(io.ktor.http.HttpStatusCode.BadRequest, e.message ?: "Error")
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
                 call.respond(io.ktor.http.HttpStatusCode.NotFound, "Database not found")
                 return@get
             }
             val tenantId = call.request.header("X-Tenant-ID") // Optional tenant
             val scope = call.request.queryParameters["scope"]
             
             // Use .toList() to consume sequence safely before serializing
             val sequence = db.getStore(tenantId).getAllAtoms()
             val filtered = if (scope != null) sequence.filter { it.scope == scope } else sequence
             
             val facts = filtered.map { it.toString() }.toList() 
             call.respond(facts)
        }

        get("/admin/databases/{name}/rules") {
             val name = call.parameters["name"]
             val db = dbManager.getDatabase(name ?: "default")
             if (db == null) {
                 call.respond(io.ktor.http.HttpStatusCode.NotFound, "Database not found")
                 return@get
             }
             val tenantId = call.request.header("X-Tenant-ID") 
             val scope = call.request.queryParameters["scope"]
             
             val rules = db.getRules(tenantId, scope).map { it.toString() }
             call.respond(rules)
        }

        // --- Tenant CRUD ---
        post("/admin/databases/{name}/tenants") {
            val name = call.parameters["name"]
            val db = dbManager.getDatabase(name ?: "default")
            if (db == null) {
                call.respond(io.ktor.http.HttpStatusCode.NotFound, "Database not found")
                return@post
            }
            try {
                val req = call.receive<CreateTenantRequest>()
                db.createTenant(req.tenantId)
                call.respondText("Tenant '${req.tenantId}' created")
            } catch (e: Exception) {
                call.respond(io.ktor.http.HttpStatusCode.BadRequest, e.message ?: "Error")
            }
        }
        
        get("/admin/databases/{name}/tenants") {
            val name = call.parameters["name"]
            val db = dbManager.getDatabase(name ?: "default")
            if (db == null) {
                call.respond(io.ktor.http.HttpStatusCode.NotFound, "Database not found")
                return@get
            }
            try {
                call.respond(db.getRegisteredTenants())
            } catch (e: Exception) {
                call.respond(io.ktor.http.HttpStatusCode.BadRequest, e.message ?: "Error")
            }
        }
        
        delete("/admin/databases/{name}/tenants/{id}") {
            val name = call.parameters["name"]
            val id = call.parameters["id"]
            val db = dbManager.getDatabase(name ?: "default")
            if (db == null) {
                call.respond(io.ktor.http.HttpStatusCode.NotFound, "Database not found")
                return@delete
            }
            if (id == null) {
                call.respond(io.ktor.http.HttpStatusCode.BadRequest, "Missing tenant ID")
                return@delete
            }
            try {
                db.deleteTenant(id)
                call.respondText("Tenant '$id' deleted")
            } catch (e: Exception) {
                call.respond(io.ktor.http.HttpStatusCode.BadRequest, e.message ?: "Error")
            }
        }

        get("/metrics") {
             // Simple metrics for default DB
             val db = dbManager.getDatabase("default")
             val count = db?.getStore()?.getAllAtoms()?.count() ?: 0
             val stats = """
                 axiombase_facts_count_default $count
                 axiombase_transactions_active 0 
             """.trimIndent()
             call.respondText(stats)
        }
        
        get("/health") {
            call.respondText("OK")
        }

        // Legacy/Generic Endpoint
        // Legacy/Generic Endpoint
        post("/execute") {
            try {
                val (db, tenantId) = getContext(call)
                val request = call.receive<ExecuteRequest>()
                val result = db.execute(request.command, tenantId)
                call.respond(ExecuteResponse(result))
            } catch (e: Exception) {
                call.respond(io.ktor.http.HttpStatusCode.BadRequest, e.message ?: "Error")
            }
        }

        // --- Transactions ---
        // --- Transactions ---
        post("/tx/begin") {
            try {
                val (db, tenantId) = getContext(call)
                // If DB is MT, tenantId is required? logic server handles it via getContext check inside AxiomBase?
                // Actually TransactionManager.begin(tenantId) is where we pass it.
                val id = db.transactionManager.begin(tenantId)
                call.respondText(id.toString())
            } catch (e: Exception) {
                call.respond(io.ktor.http.HttpStatusCode.BadRequest, e.message ?: "Error")
            }
        }

        post("/tx/commit/{id}") {
            try {
                val (db, _) = getContext(call) // Commit doesn't strictly need tenantId if TxId maps to it, but DB is needed.
                val id = call.parameters["id"]?.toLongOrNull()
                if (id == null) {
                    call.respondText("Invalid ID", status = io.ktor.http.HttpStatusCode.BadRequest)
                    return@post
                }
                db.transactionManager.commit(id)
                call.respondText("Committed $id")
            } catch (e: Exception) {
                call.respondText("Commit Failed: ${e.message}", status = io.ktor.http.HttpStatusCode.InternalServerError)
            }
        }

        post("/tx/rollback/{id}") {
            try {
                val (db, _) = getContext(call)
                val id = call.parameters["id"]?.toLongOrNull()
                if (id == null) {
                    call.respondText("Invalid ID", status = io.ktor.http.HttpStatusCode.BadRequest)
                    return@post
                }
                db.transactionManager.rollback(id)
                call.respondText("Rolled back $id")
            } catch (e: Exception) {
                 call.respond(io.ktor.http.HttpStatusCode.BadRequest, e.message ?: "Error")
            }
        }

        // --- Microservice API ---

        // Endpoint 1: Add Knowledge (ASSERT)
        // POST /assert/fact { "predicate": "Man", "args": ["Socrates"] }
        post("/assert/fact") {
            try {
                val (db, tenantId) = getContext(call)
                val req = call.receive<FactRequest>()
                val terms = req.args.map { parseTerm(it) }
                // Determine truthVal: if negated is true, truthVal is false. 
                // Prioritize 'negated' if strictly sent, otherwise fallback to 'truthVal'.
                // Ideally they should be consistent.
                val effectiveTruth = if (req.negated) false else req.truthVal
                
                val atom = com.axiombase.core.Atom(req.predicate, terms, effectiveTruth, scope = req.scope)
                
                val txId = call.request.header("X-Transaction-ID")?.toLongOrNull()
                
                if (txId != null) {
                    if (effectiveTruth) {
                        db.transactionManager.assertFact(txId, atom)
                    } else {
                        db.transactionManager.retractFact(txId, atom)
                        db.transactionManager.assertFact(txId, atom)
                    }
                    call.respondText("Fact Buffered in Tx $txId: $atom")
                } else {
                    // Direct (Auto-commit)
                    db.assertFact(atom, tenantId)
                    call.respondText("Fact Asserted: $atom")
                }
            } catch (e: Exception) {
                 call.respondText("Error: ${e.message}", status = io.ktor.http.HttpStatusCode.BadRequest)
            }
        }
        
        // Endpoint 4: Retract Knowledge (TMS Trigger)
        // POST /retract
        post("/retract") {
            try {
                val (db, tenantId) = getContext(call)
                val req = call.receive<FactRequest>()
                val terms = req.args.map { parseTerm(it) }
                val atom = com.axiombase.core.Atom(req.predicate, terms, req.truthVal, scope = req.scope)
                
                val txId = call.request.header("X-Transaction-ID")?.toLongOrNull()

                if (txId != null) {
                     db.transactionManager.retractFact(txId, atom)
                     call.respondText("Retraction Buffered in Tx $txId: $atom")
                } else {
                     db.retractFact(atom, tenantId)
                     call.respondText("Retracted: $atom")
                }
            } catch (e: Exception) {
                call.respondText("Error: ${e.message}", status = io.ktor.http.HttpStatusCode.BadRequest)
            }
        }

        // Endpoint 2: Teach Logic (ADD RULE)
        // POST /assert/rule 
        post("/assert/rule") {
            try {
                val (db, tenantId) = getContext(call)
                val req = call.receive<RuleRequest>()
                
                // Parse Head
                val headTerms = req.head.args.map { parseTerm(it) }
                val headAtom = com.axiombase.core.Atom(req.head.predicate, headTerms, truthVal = !req.head.negated, scope = req.head.scope)

                // Parse Body
                val bodyAtoms = req.body.map { atomReq ->
                    val terms = atomReq.args.map { parseTerm(it) }
                    com.axiombase.core.Atom(atomReq.predicate, terms, truthVal = !atomReq.negated, scope = atomReq.scope)
                }

                // Collect Variables (from Head and ALL Body atoms)
                val allTerms = headTerms + bodyAtoms.flatMap { it.args }
                val variables = allTerms.filterIsInstance<com.axiombase.core.Term.Variable>().distinct()
                
                val rule = com.axiombase.core.Rule(variables, headAtom, bodyAtoms, scope = req.scope)
                
                val txId = call.request.header("X-Transaction-ID")?.toLongOrNull()
                
                if (txId != null) {
                    db.transactionManager.assertRule(txId, rule)
                     call.respondText("Rule Buffered in Tx $txId: $rule")
                } else {
                    db.assertRule(rule, tenantId)
                    call.respondText("Rule Asserted: $rule")
                }
            } catch (e: Exception) {
                // e.printStackTrace() // helpful for debugging
                call.respondText("Error: ${e.message}", status = io.ktor.http.HttpStatusCode.BadRequest)
            }
        }

        // Endpoint 2.5: Apply Template
        post("/assert/template") {
            try {
                val (db, tenantId) = getContext(call)
                val req = call.receive<TemplateRequest>()
                val service = TemplateService()
                val rules = service.generateRule(req)
                
                val txId = call.request.header("X-Transaction-ID")?.toLongOrNull()
                
                val buffer = StringBuilder()
                rules.forEach { rule ->
                    if (txId != null) {
                        db.transactionManager.assertRule(txId, rule)
                        buffer.append("Rule Buffered in Tx $txId: $rule\n")
                    } else {
                        db.assertRule(rule, tenantId)
                        buffer.append("Rule Asserted: $rule\n")
                    }
                }
                call.respondText(buffer.toString())
            } catch (e: Exception) {
                 call.respondText("Error: ${e.message}", status = io.ktor.http.HttpStatusCode.BadRequest)
            }
        }

// ...



        // Endpoint 3: Ask Questions (INFER)
        // POST /infer 
        post("/infer") {
            try {
                val (db, tenantId) = getContext(call)
                val req = call.receive<FactRequest>()
                val terms = req.args.map { parseTerm(it) }
                val effectiveTruth = if (req.negated) false else req.truthVal
                val queryAtom = com.axiombase.core.Atom(req.predicate, terms, effectiveTruth, scope = req.scope)
                
                // Use Backward Chaining (Layer 4b) for inference queries
                // Note: infer() in AxiomBase (LogicContext) checks Hexastore which now respects scope in match()
                // BUT backwardChainer.solve() might need to respect scope during unification?
                // AxiomBase.infer calls ctx.backwardChainer.solve(pattern)
                // We haven't updated BackwardChainer to respect scope in recursion explicitly, 
                // but it calls store.match() eventually.
                // If I query infer(scope="doc1"), the initial goal has scope="doc1".
                // When it looks for rules, does it strictly look for rules in "doc1"? 
                // Currently getRules() returns ALL rules effectively unless we added filtering in chainer.
                // NOTE: BackwardChainer logic was not requested to be updated in step 1, but might be implicitly shielded by Hexastore query.
                // However, the entry point queryAtom now has the scope.
                
                val results = db.infer(queryAtom, tenantId)
                val response = results.map { it.toString() }.toList()
                call.respond(response)
            } catch (e: Exception) {
                call.respondText("Error: ${e.message}", status = io.ktor.http.HttpStatusCode.BadRequest)
            }
        }
        // --- Backup & Replication ---

        // Backup Endpoints
        post("/admin/backups") {
             // Only Leader can backup? Or anyone? 
             // Ideally leader. But follower backup is also valid for read-replicas.
             try {
                 val backupDir = File(ServerConfig.storageDir.parentFile, "backups")
                 // We need to backup ALL databases?
                 // Current AxiomBase logic backs up "the" database storage dir.
                 // dbManager manages multiple DBs. 
                 // This is tricky. simpler implementation: Backup Default or All?
                 // Let's iterate all DBs and backup them? 
                 // Or just backup the whole data directory at OS level?
                 // Implementation Plan said: "Add createBackup... to AxiomBase"
                 // So we call it per DB.
                 
                 val dbName = call.request.queryParameters["db"] ?: "default"
                 val db = dbManager.getDatabase(dbName)
                 if (db != null) {
                     val path = db.createBackup(backupDir)
                     call.respondText("Backup created at: ${path.absolutePath}")
                 } else {
                     call.respond(io.ktor.http.HttpStatusCode.NotFound, "Database not found")
                 }
                 
             } catch (e: Exception) {
                 call.respond(io.ktor.http.HttpStatusCode.InternalServerError, e.message ?: "Error")
             }
        }

        // Replication Endpoints (Only on Leader)
        if (ServerConfig.replicationMode == ReplicationMode.LEADER) {
            get("/replication/wal") {
                 val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
                 // We stream WAL from default DB for now (Simplification)
                 // TODO: Support multi-db replication
                 val db = dbManager.getDatabase("default")
                 if (db != null) {
                     val entries = db.getWalEntries(since + 1)
                     call.respondTextWriter(contentType = ContentType.Text.Plain) {
                         entries.forEach { entry ->
                             appendLine(Json.encodeToString(entry))
                         }
                     }
                 } else {
                     call.respond(io.ktor.http.HttpStatusCode.NotFound)
                 }
            }
        }
    }
    
    // Start Replication Client if Follower
    if (ServerConfig.replicationMode == ReplicationMode.FOLLOWER) {
        val leader = ServerConfig.leaderUrl
        if (leader != null) {
            // Replicate Default DB
            val db = dbManager.getDatabase("default")
            if (db != null) {
                val client = ReplicationClient(db, leader)
                client.start()
            }
        } else {
            System.err.println("Replication Mode is FOLLOWER but LEADER_URL is missing!")
        }
    }
}


fun parseTerm(str: String): com.axiombase.core.Term {
    return if (str.startsWith("?")) {
        com.axiombase.core.Term.Variable(str.drop(1))
    } else {
        // Try parsing number?
        val d = str.toDoubleOrNull()
        if (d != null) com.axiombase.core.Term.NumberLit(d)
        else com.axiombase.core.Term.Identifier(str) 
    }
}

@Serializable
data class ExecuteRequest(val command: String)

@Serializable
data class ExecuteResponse(val result: String)

@Serializable 
data class FactRequest(
    val predicate: String, 
    val args: List<String>,
    val truthVal: Boolean = true,
    val negated: Boolean = false, // Added for consistency with verify, though truthVal exists
    val scope: String? = null
)

@Serializable
data class AtomDto(
    val predicate: String, 
    val args: List<String>,
    val negated: Boolean = false,
    val scope: String? = null
)

@Serializable 
data class RuleRequest(
    val head: AtomDto, 
    val body: List<AtomDto>,
    val scope: String? = null
)




@Serializable
data class CreateDbRequest(
    val name: String,
    val isMultiTenant: Boolean = false
)

@Serializable
data class CreateTenantRequest(val tenantId: String)
