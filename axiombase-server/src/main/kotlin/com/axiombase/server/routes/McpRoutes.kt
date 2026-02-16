package com.axiombase.server.routes

import com.axiombase.server.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * Model Context Protocol (MCP) server implementation for AxiomBase.
 *
 * Implements the MCP specification (2025-11-25) using HTTP+SSE transport.
 * This allows any MCP-compatible AI agent (Claude, GPT, Gemini, etc.) to
 * discover and use AxiomBase as a reasoning tool.
 *
 * Spec: https://modelcontextprotocol.io/specification/2025-11-25
 *
 * Tools exposed:
 *   - assert_fact: Assert a fact into the knowledge base
 *   - assert_rule: Assert a logical rule (Horn clause)
 *   - query: Query facts matching a pattern
 *   - infer: Run backward-chaining inference
 *   - retract: Retract a fact (triggers TMS cascade)
 *   - explain: Get proof tree for an inference result
 *   - context_window: Get salience-ranked context for agent reasoning
 *   - temporal_query: Query facts valid at a specific point in time
 *   - consolidate: Run memory consolidation
 *   - decay: Run memory decay/eviction
 */

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

// --- JSON-RPC 2.0 types ---

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val method: String,
    val params: JsonObject? = null
)

@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
)

// MCP Protocol version
private const val MCP_PROTOCOL_VERSION = "2025-11-25"

fun Route.mcpRoutes(dbManager: DatabaseManager) {

    // MCP endpoint: handles all JSON-RPC requests
    post("/mcp") {
        try {
            val body = call.receiveText()
            val request = json.decodeFromString<JsonRpcRequest>(body)

            val dbName = call.request.header("X-Database") ?: "default"
            val tenantId = call.request.header("X-Tenant-ID") ?: "default"
            val db = dbManager.getDatabase(dbName)
                ?: return@post call.respond(
                    jsonRpcError(request.id, -32001, "Database '$dbName' not found")
                )

            val response = when (request.method) {
                "initialize" -> handleInitialize(request)
                "tools/list" -> handleToolsList(request)
                "tools/call" -> handleToolCall(request, db, tenantId)
                "ping" -> JsonRpcResponse(id = request.id, result = JsonObject(emptyMap()))
                else -> jsonRpcError(request.id, -32601, "Method not found: ${request.method}")
            }

            call.respond(response)
        } catch (e: Exception) {
            call.respond(
                JsonRpcResponse(
                    error = JsonRpcError(-32700, "Parse error: ${e.message}")
                )
            )
        }
    }

    // SSE endpoint for MCP streaming (server-to-client notifications)
    get("/mcp/sse") {
        val dbName = call.request.header("X-Database") ?: "default"
        val tenantId = call.request.header("X-Tenant-ID") ?: "default"
        val db = dbManager.getDatabase(dbName)

        call.response.cacheControl(CacheControl.NoCache(null))
        call.respondTextWriter(contentType = ContentType.Text.EventStream) {
            // Send endpoint info
            write("event: endpoint\ndata: /mcp\n\n")
            flush()

            if (db != null) {
                // Subscribe to knowledge events and forward as MCP notifications
                val subId = db.subscribe(
                    predicatePattern = null,
                    eventTypes = setOf("fact_asserted", "fact_retracted", "fact_expired"),
                    tenantId = tenantId
                ) { event ->
                    try {
                        val notification = when (event) {
                            is com.axiombase.memory.KnowledgeEvent.FactAsserted -> {
                                JsonObject(mapOf(
                                    "jsonrpc" to JsonPrimitive("2.0"),
                                    "method" to JsonPrimitive("notifications/resources/updated"),
                                    "params" to JsonObject(mapOf(
                                        "uri" to JsonPrimitive("axiombase://facts/${event.atom.predicate}")
                                    ))
                                ))
                            }
                            else -> null
                        }
                        if (notification != null) {
                            write("data: ${json.encodeToString(JsonObject.serializer(), notification)}\n\n")
                            flush()
                        }
                    } catch (_: Exception) {}
                }

                try {
                    while (true) {
                        write(": keepalive\n\n")
                        flush()
                        kotlinx.coroutines.delay(30_000)
                    }
                } finally {
                    db.unsubscribe(subId, tenantId)
                }
            }
        }
    }
}

private fun handleInitialize(request: JsonRpcRequest): JsonRpcResponse {
    val result = JsonObject(mapOf(
        "protocolVersion" to JsonPrimitive(MCP_PROTOCOL_VERSION),
        "capabilities" to JsonObject(mapOf(
            "tools" to JsonObject(mapOf(
                "listChanged" to JsonPrimitive(false)
            ))
        )),
        "serverInfo" to JsonObject(mapOf(
            "name" to JsonPrimitive("axiombase"),
            "version" to JsonPrimitive("1.0.0")
        ))
    ))
    return JsonRpcResponse(id = request.id, result = result)
}

private fun handleToolsList(request: JsonRpcRequest): JsonRpcResponse {
    val tools = buildJsonArray {
        add(toolSchema(
            name = "assert_fact",
            description = "Assert a fact into the AxiomBase knowledge base. Facts are predicate-argument structures that represent knowledge. Supports temporal fields (validFrom, validUntil, ttl) for automatic expiration.",
            properties = mapOf(
                "predicate" to propString("The predicate name (e.g., 'parent', 'likes', 'located_in')"),
                "args" to propArray("List of arguments. Use ?prefix for variables (e.g., ['alice', 'bob'])"),
                "scope" to propString("Optional scope for fact isolation (e.g., 'session_123')"),
                "negated" to propBool("Set true to assert the negation of this fact"),
                "ttl" to propNumber("Time-to-live in milliseconds. Fact auto-expires after this duration."),
                "validUntil" to propNumber("Epoch ms when this fact expires")
            ),
            required = listOf("predicate", "args")
        ))
        add(toolSchema(
            name = "assert_rule",
            description = "Assert a logical rule (Horn clause) for inference. Rules enable multi-step deductive reasoning. Format: head :- body1 AND body2 AND ... Use ?prefix for variables.",
            properties = mapOf(
                "head" to propObject("The consequent atom", mapOf(
                    "predicate" to propString("Predicate name"),
                    "args" to propArray("Arguments (use ?prefix for variables)")
                )),
                "body" to propArray("List of antecedent atoms, each with 'predicate' and 'args'"),
                "scope" to propString("Optional scope")
            ),
            required = listOf("head", "body")
        ))
        add(toolSchema(
            name = "query",
            description = "Query facts matching a pattern. Use ?prefix for variable positions that should match any value. Returns all matching facts from the knowledge base.",
            properties = mapOf(
                "predicate" to propString("Predicate to query"),
                "args" to propArray("Arguments. Use ?x, ?y etc. for wildcards"),
                "scope" to propString("Optional scope filter")
            ),
            required = listOf("predicate", "args")
        ))
        add(toolSchema(
            name = "infer",
            description = "Run backward-chaining logical inference. Unlike query (which only matches stored facts), infer applies rules to derive new conclusions through multi-step deductive reasoning. Returns all provable results with optional proof trees.",
            properties = mapOf(
                "predicate" to propString("Goal predicate"),
                "args" to propArray("Goal arguments. Use ?prefix for variables."),
                "scope" to propString("Optional scope"),
                "withProof" to propBool("If true, include full proof tree showing the derivation chain")
            ),
            required = listOf("predicate", "args")
        ))
        add(toolSchema(
            name = "retract",
            description = "Retract (remove) a fact from the knowledge base. Triggers the Truth Maintenance System: any facts that were derived from this fact will be automatically cascade-retracted.",
            properties = mapOf(
                "predicate" to propString("Predicate to retract"),
                "args" to propArray("Arguments of the fact to retract"),
                "scope" to propString("Optional scope")
            ),
            required = listOf("predicate", "args")
        ))
        add(toolSchema(
            name = "context_window",
            description = "Get the most salient (relevant) facts for the current reasoning context. Returns facts ranked by a composite score of recency, access frequency, and priority. Use this to efficiently populate your context with the most important knowledge.",
            properties = mapOf(
                "maxFacts" to propNumber("Maximum number of facts to return (default: 100)"),
                "minSalience" to propNumber("Minimum salience score 0.0-1.0 (default: 0.0)"),
                "predicates" to propArray("Optional list of predicates to filter by"),
                "scope" to propString("Optional scope filter")
            ),
            required = emptyList()
        ))
        add(toolSchema(
            name = "temporal_query",
            description = "Query facts that were valid at a specific point in time. Useful for historical reasoning: 'What was true at timestamp T?' Filters by validFrom/validUntil/ttl.",
            properties = mapOf(
                "predicate" to propString("Predicate to query"),
                "args" to propArray("Arguments (use ?prefix for variables)"),
                "timestamp" to propNumber("Epoch milliseconds — the point in time to query"),
                "scope" to propString("Optional scope filter")
            ),
            required = listOf("predicate", "args", "timestamp")
        ))
        add(toolSchema(
            name = "consolidate",
            description = "Run memory consolidation: detect repeated episodic patterns and compress them into semantic summary facts. Helps manage memory growth for long-running agent sessions.",
            properties = emptyMap(),
            required = emptyList()
        ))
        add(toolSchema(
            name = "decay",
            description = "Run memory decay: expire facts that have exceeded their TTL and evict low-salience facts if the knowledge base is over capacity. Essential for long-running agents.",
            properties = mapOf(
                "threshold" to propNumber("Salience threshold below which facts are evicted (default: 0.05)")
            ),
            required = emptyList()
        ))
    }

    val result = JsonObject(mapOf("tools" to tools))
    return JsonRpcResponse(id = request.id, result = result)
}

private fun handleToolCall(
    request: JsonRpcRequest,
    db: com.axiombase.AxiomBase,
    tenantId: String
): JsonRpcResponse {
    val params = request.params ?: return jsonRpcError(request.id, -32602, "Missing params")
    val toolName = params["name"]?.jsonPrimitive?.content
        ?: return jsonRpcError(request.id, -32602, "Missing tool name")
    val arguments = params["arguments"]?.jsonObject
        ?: JsonObject(emptyMap())

    return try {
        val result = when (toolName) {
            "assert_fact" -> callAssertFact(db, tenantId, arguments)
            "assert_rule" -> callAssertRule(db, tenantId, arguments)
            "query" -> callQuery(db, tenantId, arguments)
            "infer" -> callInfer(db, tenantId, arguments)
            "retract" -> callRetract(db, tenantId, arguments)
            "context_window" -> callContextWindow(db, tenantId, arguments)
            "temporal_query" -> callTemporalQuery(db, tenantId, arguments)
            "consolidate" -> callConsolidate(db, tenantId)
            "decay" -> callDecay(db, tenantId, arguments)
            else -> return jsonRpcError(request.id, -32602, "Unknown tool: $toolName")
        }

        JsonRpcResponse(
            id = request.id,
            result = JsonObject(mapOf(
                "content" to buildJsonArray {
                    add(JsonObject(mapOf(
                        "type" to JsonPrimitive("text"),
                        "text" to JsonPrimitive(result)
                    )))
                }
            ))
        )
    } catch (e: Exception) {
        JsonRpcResponse(
            id = request.id,
            result = JsonObject(mapOf(
                "content" to buildJsonArray {
                    add(JsonObject(mapOf(
                        "type" to JsonPrimitive("text"),
                        "text" to JsonPrimitive("Error: ${e.message}")
                    )))
                },
                "isError" to JsonPrimitive(true)
            ))
        )
    }
}

// --- Tool Implementations ---

private fun callAssertFact(db: com.axiombase.AxiomBase, tenantId: String, args: JsonObject): String {
    val predicate = args["predicate"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing predicate")
    val argsList = args["args"]?.jsonArray?.map { it.jsonPrimitive.content } ?: throw IllegalArgumentException("Missing args")
    val scope = args["scope"]?.jsonPrimitive?.contentOrNull
    val negated = args["negated"]?.jsonPrimitive?.booleanOrNull ?: false
    val ttl = args["ttl"]?.jsonPrimitive?.longOrNull
    val validUntil = args["validUntil"]?.jsonPrimitive?.longOrNull

    val terms = argsList.map { parseTerm(it) }
    val atom = com.axiombase.core.Atom(
        predicate = predicate,
        args = terms,
        truthVal = !negated,
        scope = scope,
        ttl = ttl,
        validUntil = validUntil
    )
    db.assertFact(atom, tenantId)
    return "Fact asserted: $atom"
}

private fun callAssertRule(db: com.axiombase.AxiomBase, tenantId: String, args: JsonObject): String {
    val headObj = args["head"]?.jsonObject ?: throw IllegalArgumentException("Missing head")
    val bodyArr = args["body"]?.jsonArray ?: throw IllegalArgumentException("Missing body")
    val scope = args["scope"]?.jsonPrimitive?.contentOrNull

    val headPredicate = headObj["predicate"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing head.predicate")
    val headArgs = headObj["args"]?.jsonArray?.map { parseTerm(it.jsonPrimitive.content) } ?: emptyList()
    val headAtom = com.axiombase.core.Atom(headPredicate, headArgs, scope = scope)

    val bodyAtoms = bodyArr.map { elem ->
        val obj = elem.jsonObject
        val bp = obj["predicate"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing body predicate")
        val ba = obj["args"]?.jsonArray?.map { parseTerm(it.jsonPrimitive.content) } ?: emptyList()
        val negated = obj["negated"]?.jsonPrimitive?.booleanOrNull ?: false
        com.axiombase.core.Atom(bp, ba, truthVal = !negated, scope = scope)
    }

    val allTerms = headArgs + bodyAtoms.flatMap { it.args }
    val variables = allTerms.filterIsInstance<com.axiombase.core.Term.Variable>().distinct()
    val rule = com.axiombase.core.Rule(variables, headAtom, bodyAtoms, scope = scope)
    db.assertRule(rule, tenantId)
    return "Rule asserted: $rule"
}

private fun callQuery(db: com.axiombase.AxiomBase, tenantId: String, args: JsonObject): String {
    val predicate = args["predicate"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing predicate")
    val argsList = args["args"]?.jsonArray?.map { it.jsonPrimitive.content } ?: throw IllegalArgumentException("Missing args")
    val scope = args["scope"]?.jsonPrimitive?.contentOrNull

    val terms = argsList.map { parseTerm(it) }
    val pattern = com.axiombase.core.Atom(predicate, terms, scope = scope)
    val results = db.query(pattern, tenantId, scope).toList()

    if (results.isEmpty()) return "No matching facts found."

    val sb = StringBuilder("Found ${results.size} matching fact(s):\n")
    for (atom in results) {
        sb.append("  ${atom}\n")
    }
    return sb.toString()
}

private fun callInfer(db: com.axiombase.AxiomBase, tenantId: String, args: JsonObject): String {
    val predicate = args["predicate"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing predicate")
    val argsList = args["args"]?.jsonArray?.map { it.jsonPrimitive.content } ?: throw IllegalArgumentException("Missing args")
    val scope = args["scope"]?.jsonPrimitive?.contentOrNull
    val withProof = args["withProof"]?.jsonPrimitive?.booleanOrNull ?: false

    val terms = argsList.map { parseTerm(it) }
    val pattern = com.axiombase.core.Atom(predicate, terms, scope = scope)

    if (withProof) {
        val proofTrees = db.inferWithProof(pattern, tenantId).toList()
        if (proofTrees.isEmpty()) return "No results could be inferred."

        val sb = StringBuilder("Inferred ${proofTrees.size} result(s) with proofs:\n")
        for (pt in proofTrees) {
            sb.append("\nResult: ${pt.result}\n")
            sb.append(formatProof(pt.proof, "  "))
        }
        return sb.toString()
    } else {
        val results = db.infer(pattern, tenantId).toList()
        if (results.isEmpty()) return "No results could be inferred."

        val sb = StringBuilder("Inferred ${results.size} result(s):\n")
        for (atom in results) {
            sb.append("  ${atom}\n")
        }
        return sb.toString()
    }
}

private fun callRetract(db: com.axiombase.AxiomBase, tenantId: String, args: JsonObject): String {
    val predicate = args["predicate"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing predicate")
    val argsList = args["args"]?.jsonArray?.map { it.jsonPrimitive.content } ?: throw IllegalArgumentException("Missing args")
    val scope = args["scope"]?.jsonPrimitive?.contentOrNull

    val terms = argsList.map { parseTerm(it) }
    val atom = com.axiombase.core.Atom(predicate, terms, scope = scope)
    db.retractFact(atom, tenantId)
    return "Retracted: $atom (and any facts derived from it via TMS)"
}

private fun callContextWindow(db: com.axiombase.AxiomBase, tenantId: String, args: JsonObject): String {
    val maxFacts = args["maxFacts"]?.jsonPrimitive?.intOrNull ?: 100
    val minSalience = args["minSalience"]?.jsonPrimitive?.doubleOrNull ?: 0.0
    val predicates = args["predicates"]?.jsonArray?.map { it.jsonPrimitive.content }
    val scope = args["scope"]?.jsonPrimitive?.contentOrNull

    val window = db.buildContextWindow(tenantId, scope, maxFacts, minSalience, predicates)

    if (window.facts.isEmpty()) return "Context window is empty. No facts match the criteria."

    val sb = StringBuilder("Context Window (${window.windowSize}/${window.totalAvailable} facts):\n")
    sb.append("Predicates: ${window.predicateDistribution}\n\n")
    for (scored in window.facts) {
        sb.append("  [salience=${String.format("%.3f", scored.salience)}] ${scored.atom}\n")
    }
    return sb.toString()
}

private fun callTemporalQuery(db: com.axiombase.AxiomBase, tenantId: String, args: JsonObject): String {
    val predicate = args["predicate"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing predicate")
    val argsList = args["args"]?.jsonArray?.map { it.jsonPrimitive.content } ?: throw IllegalArgumentException("Missing args")
    val timestamp = args["timestamp"]?.jsonPrimitive?.longOrNull ?: throw IllegalArgumentException("Missing timestamp")
    val scope = args["scope"]?.jsonPrimitive?.contentOrNull

    val terms = argsList.map { parseTerm(it) }
    val pattern = com.axiombase.core.Atom(predicate, terms, scope = scope)
    val results = db.queryAtTime(pattern, timestamp, tenantId, scope)

    if (results.isEmpty()) return "No facts matching this pattern were valid at timestamp $timestamp."

    val sb = StringBuilder("Found ${results.size} fact(s) valid at timestamp $timestamp:\n")
    for (atom in results) {
        sb.append("  ${atom}")
        if (atom.validFrom != null || atom.validUntil != null) {
            sb.append(" [valid: ${atom.validFrom ?: "∞"} → ${atom.validUntil ?: "∞"}]")
        }
        sb.append("\n")
    }
    return sb.toString()
}

private fun callConsolidate(db: com.axiombase.AxiomBase, tenantId: String): String {
    val result = db.runConsolidation(tenantId)
    return if (result.factsConsolidated == 0) {
        "No patterns found for consolidation."
    } else {
        val sb = StringBuilder("Consolidated ${result.factsConsolidated} pattern(s):\n")
        for (fact in result.newFacts) {
            sb.append("  NEW: $fact\n")
        }
        sb.toString()
    }
}

private fun callDecay(db: com.axiombase.AxiomBase, tenantId: String, args: JsonObject): String {
    val threshold = args["threshold"]?.jsonPrimitive?.doubleOrNull
    val result = db.runDecay(tenantId, threshold)
    return "Decay complete: ${result.expiredCount} expired, ${result.evictedCount} evicted (${result.removedAtoms.size} total removed)"
}

// --- Helpers ---

private fun formatProof(node: com.axiombase.core.ProofNode, indent: String): String {
    val sb = StringBuilder()
    when (val step = node.step) {
        is com.axiombase.core.ProofStep.FactMatch -> {
            sb.append("${indent}FACT: ${step.fact}\n")
        }
        is com.axiombase.core.ProofStep.RuleApplication -> {
            sb.append("${indent}RULE: ${step.rule}\n")
            for (bodyProof in step.bodyProofs) {
                sb.append(formatProof(bodyProof, "$indent  "))
            }
        }
    }
    return sb.toString()
}

private fun jsonRpcError(id: JsonElement?, code: Int, message: String): JsonRpcResponse {
    return JsonRpcResponse(id = id, error = JsonRpcError(code, message))
}

// --- Schema builder helpers ---

private fun toolSchema(
    name: String,
    description: String,
    properties: Map<String, JsonObject>,
    required: List<String>
): JsonObject {
    return JsonObject(mapOf(
        "name" to JsonPrimitive(name),
        "description" to JsonPrimitive(description),
        "inputSchema" to JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(properties),
            "required" to buildJsonArray { required.forEach { add(JsonPrimitive(it)) } }
        ))
    ))
}

private fun propString(description: String): JsonObject = JsonObject(mapOf(
    "type" to JsonPrimitive("string"),
    "description" to JsonPrimitive(description)
))

private fun propArray(description: String): JsonObject = JsonObject(mapOf(
    "type" to JsonPrimitive("array"),
    "items" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
    "description" to JsonPrimitive(description)
))

private fun propBool(description: String): JsonObject = JsonObject(mapOf(
    "type" to JsonPrimitive("boolean"),
    "description" to JsonPrimitive(description)
))

private fun propNumber(description: String): JsonObject = JsonObject(mapOf(
    "type" to JsonPrimitive("number"),
    "description" to JsonPrimitive(description)
))

private fun propObject(description: String, props: Map<String, JsonObject>): JsonObject = JsonObject(mapOf(
    "type" to JsonPrimitive("object"),
    "description" to JsonPrimitive(description),
    "properties" to JsonObject(props)
))
