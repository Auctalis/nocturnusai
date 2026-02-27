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
import com.nocturnusai.server.observability.Metrics
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * Model Context Protocol (MCP) server implementation for NocturnusAI.
 *
 * Implements the MCP specification (2025-11-25) using HTTP+SSE transport.
 * This allows any MCP-compatible AI agent (Claude, GPT, Gemini, etc.) to
 * discover and use NocturnusAI as a reasoning tool.
 *
 * Spec: https://modelcontextprotocol.io/specification/2025-11-25
 *
 * Tools exposed:
 *   - tell / assert_fact: Assert a fact into the knowledge base
 *   - teach / assert_rule: Assert a logical rule (Horn clause)
 *   - ask / infer: Run backward-chaining inference
 *   - forget / retract: Retract a fact (triggers TMS cascade)
 *   - recall / temporal_query: Query facts valid at a specific point in time
 *   - context / context_window: Get salience-ranked context for agent reasoning
 *   - compress / consolidate: Run memory consolidation
 *   - cleanup / decay: Run memory decay/eviction
 *   - predicates: Discover the knowledge base schema
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

// MCP JSON-RPC error codes (standard + custom)
private object McpErrorCodes {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603
    // Custom NocturnusAI error codes
    const val DATABASE_NOT_FOUND = -32001
    const val VALIDATION_ERROR = -32002
    const val CONFLICT_ERROR = -32003
    const val UNKNOWN_TOOL = -32004
}

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
                    jsonRpcError(request.id, McpErrorCodes.DATABASE_NOT_FOUND, "Database '$dbName' not found")
                )

            val response = when (request.method) {
                "initialize" -> handleInitialize(request)
                "tools/list" -> handleToolsList(request)
                "tools/call" -> {
                    val timer = Metrics.mcpToolCallTimer()
                    val toolName = request.params?.get("name")?.jsonPrimitive?.content ?: "unknown"
                    handleToolCall(request, db, tenantId).also {
                        val isError = it.error != null ||
                            (it.result as? JsonObject)?.get("isError")?.jsonPrimitive?.booleanOrNull == true
                        val status = if (isError) "error" else "success"
                        Metrics.mcpToolCallCompleted(timer, toolName, status)
                    }
                }
                "ping" -> JsonRpcResponse(id = request.id, result = JsonObject(emptyMap()))
                else -> jsonRpcError(request.id, McpErrorCodes.METHOD_NOT_FOUND, "Method not found: ${request.method}")
            }

            // Add request-id header for traceability
            call.response.header("X-Request-ID", java.util.UUID.randomUUID().toString())
            call.respond(response)
        } catch (e: Exception) {
            call.respond(
                JsonRpcResponse(
                    error = JsonRpcError(McpErrorCodes.PARSE_ERROR, "Parse error: ${e.message}")
                )
            )
        }
    }

    // SSE endpoint for MCP streaming (server-to-client notifications)
    get("/mcp/sse") {
        val dbName = call.request.header("X-Database") ?: "default"
        val tenantId = call.request.header("X-Tenant-ID") ?: "default"
        val db = dbManager.getDatabase(dbName)

        Metrics.mcpSseConnected()
        call.response.cacheControl(CacheControl.NoCache(null))
        call.response.header("X-Request-ID", java.util.UUID.randomUUID().toString())
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
                            is com.nocturnusai.memory.KnowledgeEvent.FactAsserted -> {
                                JsonObject(mapOf(
                                    "jsonrpc" to JsonPrimitive("2.0"),
                                    "method" to JsonPrimitive("notifications/resources/updated"),
                                    "params" to JsonObject(mapOf(
                                        "uri" to JsonPrimitive("nocturnusai://facts/${event.atom.predicate}")
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
                    Metrics.mcpSseDisconnected()
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
            )),
            "resources" to JsonObject(mapOf(
                "subscribe" to JsonPrimitive(true)
            ))
        )),
        "serverInfo" to JsonObject(mapOf(
            "name" to JsonPrimitive("nocturnusai"),
            "version" to JsonPrimitive("2.0.0")
        ))
    ))
    return JsonRpcResponse(id = request.id, result = result)
}

private fun handleToolsList(request: JsonRpcRequest): JsonRpcResponse {
    val tools = buildJsonArray {
        add(toolSchema(
            name = "tell",
            description = "Tell NocturnusAI something it should know. Stores a fact (knowledge) that can be queried and used in reasoning. Supports auto-expiration via ttl (milliseconds) or validUntil (epoch ms).",
            properties = mapOf(
                "predicate" to propString("The relationship or property name (e.g., 'parent', 'likes', 'located_in')"),
                "args" to propArray("The entities involved (e.g., ['alice', 'bob'] for 'alice is parent of bob')"),
                "scope" to propString("Optional isolation scope (e.g., 'session_123', 'hypothesis_a')"),
                "negated" to propBool("Set true to store the negation of this fact"),
                "ttl" to propNumber("Auto-expire after this many milliseconds"),
                "validUntil" to propNumber("Epoch ms when this fact stops being valid"),
                "confidence" to propNumber("Optional confidence score 0.0–1.0 (e.g., 0.9 = high confidence from LLM extraction)"),
                "conflictStrategy" to propString("How to handle contradictions: REJECT (default), NEWEST_WINS, CONFIDENCE, KEEP_BOTH")
            ),
            required = listOf("predicate", "args")
        ))
        add(toolSchema(
            name = "teach",
            description = "Teach NocturnusAI a rule for automatic reasoning. When conditions (body) are all true, the conclusion (head) is automatically derivable. Use ?prefix for variables (e.g., ?x, ?who). Example: 'If ?x is parent of ?y AND ?y is parent of ?z, THEN ?x is grandparent of ?z'.",
            properties = mapOf(
                "head" to propObject("The conclusion (what becomes true)", mapOf(
                    "predicate" to propString("Conclusion relationship name"),
                    "args" to propArray("Arguments (use ?x, ?y for variables)")
                )),
                "body" to propArray("The conditions (list of facts that must be true), each with 'predicate' and 'args'"),
                "scope" to propString("Optional scope")
            ),
            required = listOf("head", "body")
        ))
        add(toolSchema(
            name = "ask",
            description = "Ask NocturnusAI a question. Finds all answers by applying rules and matching facts through multi-step logical reasoning. Use ?prefix for unknowns you want to discover. Returns all provable answers, optionally with full proof chains showing how each answer was derived.",
            properties = mapOf(
                "predicate" to propString("What you're asking about (e.g., 'grandparent')"),
                "args" to propArray("Use ?x, ?who etc. for unknowns, concrete values to constrain (e.g., ['?who', 'charlie'])"),
                "scope" to propString("Optional scope filter"),
                "withProof" to propBool("If true, include the full reasoning chain showing how each answer was derived"),
                "minConfidence" to propNumber("Optional minimum confidence threshold 0.0–1.0. Filters out facts below this confidence.")
            ),
            required = listOf("predicate", "args")
        ))
        add(toolSchema(
            name = "forget",
            description = "Make NocturnusAI forget a fact. Any knowledge that was derived from this fact is also automatically forgotten (cascading retraction).",
            properties = mapOf(
                "predicate" to propString("The relationship to forget"),
                "args" to propArray("The specific entities to forget about"),
                "scope" to propString("Optional scope")
            ),
            required = listOf("predicate", "args")
        ))
        add(toolSchema(
            name = "recall",
            description = "Recall what was known at a specific point in time. Useful for time-travel queries like 'What was true an hour ago?' Respects temporal bounds (validFrom/validUntil/ttl).",
            properties = mapOf(
                "predicate" to propString("What to recall"),
                "args" to propArray("Arguments (use ?prefix for unknowns)"),
                "timestamp" to propNumber("Epoch milliseconds — the moment in time to recall"),
                "scope" to propString("Optional scope filter")
            ),
            required = listOf("predicate", "args", "timestamp")
        ))
        add(toolSchema(
            name = "context",
            description = "Get the most relevant knowledge for your current reasoning step. Returns facts ranked by relevance (composite of recency, access frequency, and priority). Use this to efficiently populate your context window with the most important knowledge.",
            properties = mapOf(
                "maxFacts" to propNumber("Maximum facts to return (default: 100)"),
                "minRelevance" to propNumber("Minimum relevance score 0.0-1.0 (default: 0.0)"),
                "predicates" to propArray("Optional: only include these relationship types"),
                "scope" to propString("Optional scope filter")
            ),
            required = emptyList()
        ))
        add(toolSchema(
            name = "compress",
            description = "Compress repeated patterns into summary knowledge. Detects episodic patterns (e.g., 'user asked about X five times') and creates semantic summaries. Helps manage memory growth in long-running sessions.",
            properties = emptyMap(),
            required = emptyList()
        ))
        add(toolSchema(
            name = "cleanup",
            description = "Clean up stale knowledge. Expires facts past their TTL and evicts low-relevance facts when memory is over capacity. Run periodically in long-running agent sessions.",
            properties = mapOf(
                "threshold" to propNumber("Relevance threshold below which facts are evicted (default: 0.05)")
            ),
            required = emptyList()
        ))
        add(toolSchema(
            name = "predicates",
            description = "Discover the knowledge base schema. Lists all predicates (relationship types) currently stored, with argument counts and fact counts. Useful for understanding what knowledge is available before querying.",
            properties = mapOf(
                "scope" to propString("Optional scope filter")
            ),
            required = emptyList()
        ))
        add(toolSchema(
            name = "aggregate",
            description = "Compute COUNT, SUM, MIN, MAX, or AVG over facts matching a pattern. Use COUNT to count matches, or SUM/MIN/MAX/AVG to aggregate a numeric argument at a specific position. Example: COUNT all score(player, ?) facts, or SUM the scores at argIndex=1.",
            properties = mapOf(
                "predicate" to propString("The predicate to aggregate over"),
                "args" to propArray("Pattern arguments. Use ?x, ?who etc. for wildcards, concrete values to constrain"),
                "operation" to propString("Aggregation operation: COUNT, SUM, MIN, MAX, or AVG"),
                "argIndex" to propNumber("Argument position (0-based) to aggregate for SUM/MIN/MAX/AVG (ignored for COUNT)"),
                "scope" to propString("Optional scope filter")
            ),
            required = listOf("predicate", "args", "operation")
        ))
        add(toolSchema(
            name = "bulk_assert",
            description = "Assert multiple facts in a single call. Non-transactional: each fact is attempted independently — contradictions are reported as errors rather than aborting the batch. Returns counts of successful and failed assertions.",
            properties = mapOf(
                "facts" to propArray("Array of fact objects, each with 'predicate', 'args', optional 'negated', 'scope', 'ttl', 'validUntil'")
            ),
            required = listOf("facts")
        ))
        add(toolSchema(
            name = "retract_pattern",
            description = "Retract all facts matching a pattern in a single call. Use ?x, ?y etc. as wildcards to retract multiple facts at once. Returns the count and list of retracted facts.",
            properties = mapOf(
                "predicate" to propString("The predicate pattern to match for retraction"),
                "args" to propArray("Arguments. Use ?x, ?who etc. as wildcards to match multiple facts"),
                "scope" to propString("Optional scope filter")
            ),
            required = listOf("predicate", "args")
        ))
        add(toolSchema(
            name = "fork_scope",
            description = "Fork a knowledge base scope. Creates an independent copy of all facts in sourceScope under targetScope. Use this to safely explore hypothetical scenarios ('What if Alice moves to London?') without modifying the main knowledge base. sourceScope=null forks from the global (unscoped) partition.",
            properties = mapOf(
                "sourceScope" to propString("Scope to copy from. Omit or pass null for the global partition."),
                "targetScope" to propString("New scope name to copy all facts into.")
            ),
            required = listOf("targetScope")
        ))
        add(toolSchema(
            name = "merge_scope",
            description = "Merge facts from sourceScope back into targetScope (default: global). Useful for committing hypothetical reasoning back into the main knowledge base. Choose a conflict strategy: SOURCE_WINS overwrites, TARGET_WINS keeps existing, KEEP_BOTH retains both, REJECT aborts if conflicts found.",
            properties = mapOf(
                "sourceScope" to propString("Scope to merge facts from."),
                "targetScope" to propString("Destination scope. Omit or pass null for the global partition."),
                "strategy" to propString("Conflict strategy: SOURCE_WINS | TARGET_WINS | KEEP_BOTH | REJECT (default: SOURCE_WINS)")
            ),
            required = listOf("sourceScope")
        ))
        add(toolSchema(
            name = "list_scopes",
            description = "List all named scopes currently in the knowledge base. Useful for discovering what hypothetical contexts or reasoning branches exist.",
            properties = emptyMap(),
            required = emptyList()
        ))
        add(toolSchema(
            name = "delete_scope",
            description = "Delete a knowledge base scope and all facts within it. Use this to clean up completed or abandoned hypothetical reasoning branches.",
            properties = mapOf(
                "scope" to propString("The scope name to delete.")
            ),
            required = listOf("scope")
        ))
    }

    val result = JsonObject(mapOf("tools" to tools))
    return JsonRpcResponse(id = request.id, result = result)
}

private fun handleToolCall(
    request: JsonRpcRequest,
    db: com.nocturnusai.NocturnusAI,
    tenantId: String
): JsonRpcResponse {
    val params = request.params ?: return jsonRpcError(request.id, McpErrorCodes.INVALID_PARAMS, "Missing params")
    val toolName = params["name"]?.jsonPrimitive?.content
        ?: return jsonRpcError(request.id, McpErrorCodes.INVALID_PARAMS, "Missing tool name")
    val arguments = params["arguments"]?.jsonObject
        ?: JsonObject(emptyMap())

    return try {
        val result = when (toolName) {
            // Simplified names (primary)
            "tell" -> callAssertFact(db, tenantId, arguments)
            "teach" -> callAssertRule(db, tenantId, arguments)
            "ask" -> callInfer(db, tenantId, arguments)
            "forget" -> callRetract(db, tenantId, arguments)
            "recall" -> callTemporalQuery(db, tenantId, arguments)
            "context" -> callContextWindow(db, tenantId, arguments)
            "compress" -> callConsolidate(db, tenantId)
            "cleanup" -> callDecay(db, tenantId, arguments)
            "predicates" -> callPredicates(db, tenantId, arguments)
            // Aggregation and bulk tools
            "aggregate" -> callAggregate(db, tenantId, arguments)
            "bulk_assert" -> callBulkAssert(db, tenantId, arguments)
            "retract_pattern" -> callRetractPattern(db, tenantId, arguments)
            // Scope management tools
            "fork_scope" -> callForkScope(db, tenantId, arguments)
            "merge_scope" -> callMergeScope(db, tenantId, arguments)
            "list_scopes" -> callListScopes(db, tenantId)
            "delete_scope" -> callDeleteScope(db, tenantId, arguments)
            // Legacy names (backward compatible)
            "assert_fact" -> callAssertFact(db, tenantId, arguments)
            "assert_rule" -> callAssertRule(db, tenantId, arguments)
            "query" -> callQuery(db, tenantId, arguments)
            "infer" -> callInfer(db, tenantId, arguments)
            "retract" -> callRetract(db, tenantId, arguments)
            "context_window" -> callContextWindow(db, tenantId, arguments)
            "temporal_query" -> callTemporalQuery(db, tenantId, arguments)
            "consolidate" -> callConsolidate(db, tenantId)
            "decay" -> callDecay(db, tenantId, arguments)
            else -> return jsonRpcError(
                request.id, McpErrorCodes.UNKNOWN_TOOL, "Unknown tool: $toolName",
                JsonObject(mapOf("tool" to JsonPrimitive(toolName)))
            )
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
    } catch (e: IllegalArgumentException) {
        // Validation / missing argument errors
        Metrics.mcpToolCallError(toolName, "validation")
        toolErrorResponse(request.id, toolName, "VALIDATION_ERROR", e.message ?: "Invalid arguments")
    } catch (e: Exception) {
        Metrics.mcpToolCallError(toolName, "internal")
        toolErrorResponse(request.id, toolName, "INTERNAL_ERROR", e.message ?: "Unexpected error")
    }
}

/** Build a structured MCP tool error response with error metadata. */
private fun toolErrorResponse(id: JsonElement?, toolName: String, errorCode: String, message: String): JsonRpcResponse {
    return JsonRpcResponse(
        id = id,
        result = JsonObject(mapOf(
            "content" to buildJsonArray {
                add(JsonObject(mapOf(
                    "type" to JsonPrimitive("text"),
                    "text" to JsonPrimitive("Error [$errorCode]: $message")
                )))
            },
            "isError" to JsonPrimitive(true),
            "_meta" to JsonObject(mapOf(
                "errorCode" to JsonPrimitive(errorCode),
                "tool" to JsonPrimitive(toolName)
            ))
        ))
    )
}

// --- Tool Implementations ---

private fun callAssertFact(db: com.nocturnusai.NocturnusAI, tenantId: String, args: JsonObject): String {
    val predicate = args["predicate"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing predicate")
    val argsList = args["args"]?.jsonArray?.map { it.jsonPrimitive.content } ?: throw IllegalArgumentException("Missing args")
    val scope = args["scope"]?.jsonPrimitive?.contentOrNull
    val negated = args["negated"]?.jsonPrimitive?.booleanOrNull ?: false
    val ttl = args["ttl"]?.jsonPrimitive?.longOrNull
    val validUntil = args["validUntil"]?.jsonPrimitive?.longOrNull
    val confidence = args["confidence"]?.jsonPrimitive?.doubleOrNull
    val conflictStrategyStr = args["conflictStrategy"]?.jsonPrimitive?.contentOrNull
    val conflictStrategy = conflictStrategyStr?.let {
        try { com.nocturnusai.core.ConflictStrategy.valueOf(it) }
        catch (_: IllegalArgumentException) { throw IllegalArgumentException("Invalid conflictStrategy: $it. Valid values: REJECT, NEWEST_WINS, CONFIDENCE, KEEP_BOTH") }
    } ?: db.defaultConflictStrategy

    val terms = argsList.map { parseTerm(it) }
    val atom = com.nocturnusai.core.Atom(
        predicate = predicate,
        args = terms,
        truthVal = !negated,
        scope = scope,
        ttl = ttl,
        validUntil = validUntil,
        confidence = confidence
    )
    db.assertFact(atom, tenantId, conflictStrategy = conflictStrategy)
    return "Stored: $atom"
}

private fun callAssertRule(db: com.nocturnusai.NocturnusAI, tenantId: String, args: JsonObject): String {
    val headObj = args["head"]?.jsonObject ?: throw IllegalArgumentException("Missing head")
    val bodyArr = args["body"]?.jsonArray ?: throw IllegalArgumentException("Missing body")
    val scope = args["scope"]?.jsonPrimitive?.contentOrNull

    val headPredicate = headObj["predicate"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing head.predicate")
    val headArgs = headObj["args"]?.jsonArray?.map { parseTerm(it.jsonPrimitive.content) } ?: emptyList()
    val headAtom = com.nocturnusai.core.Atom(headPredicate, headArgs, scope = scope)

    val bodyAtoms = bodyArr.map { elem ->
        val obj = elem.jsonObject
        val bp = obj["predicate"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing body predicate")
        val ba = obj["args"]?.jsonArray?.map { parseTerm(it.jsonPrimitive.content) } ?: emptyList()
        val negated = obj["negated"]?.jsonPrimitive?.booleanOrNull ?: false
        com.nocturnusai.core.Atom(bp, ba, truthVal = !negated, scope = scope)
    }

    val allTerms = headArgs + bodyAtoms.flatMap { it.args }
    val variables = allTerms.filterIsInstance<com.nocturnusai.core.Term.Variable>().distinct()
    val rule = com.nocturnusai.core.Rule(variables, headAtom, bodyAtoms, scope = scope)
    db.assertRule(rule, tenantId)
    return "Rule stored: $rule"
}

private fun callQuery(db: com.nocturnusai.NocturnusAI, tenantId: String, args: JsonObject): String {
    val predicate = args["predicate"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing predicate")
    val argsList = args["args"]?.jsonArray?.map { it.jsonPrimitive.content } ?: throw IllegalArgumentException("Missing args")
    val scope = args["scope"]?.jsonPrimitive?.contentOrNull

    val terms = argsList.map { parseTerm(it) }
    val pattern = com.nocturnusai.core.Atom(predicate, terms, scope = scope)
    val results = db.query(pattern, tenantId, scope).toList()

    if (results.isEmpty()) return "No matching facts found."

    val sb = StringBuilder("Found ${results.size} matching fact(s):\n")
    for (atom in results) {
        sb.append("  ${atom}\n")
    }
    return sb.toString()
}

private fun callInfer(db: com.nocturnusai.NocturnusAI, tenantId: String, args: JsonObject): String {
    val predicate = args["predicate"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing predicate")
    val argsList = args["args"]?.jsonArray?.map { it.jsonPrimitive.content } ?: throw IllegalArgumentException("Missing args")
    val scope = args["scope"]?.jsonPrimitive?.contentOrNull
    val withProof = args["withProof"]?.jsonPrimitive?.booleanOrNull ?: false
    val minConfidence = args["minConfidence"]?.jsonPrimitive?.doubleOrNull

    val terms = argsList.map { parseTerm(it) }
    val pattern = com.nocturnusai.core.Atom(predicate, terms, scope = scope)

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
        val results = db.infer(pattern, tenantId, minConfidence = minConfidence).toList()
        if (results.isEmpty()) return "No results could be inferred."

        val sb = StringBuilder("Inferred ${results.size} result(s):\n")
        for (atom in results) {
            val confStr = if (atom.confidence != null) " [confidence=${String.format("%.2f", atom.confidence)}]" else ""
            sb.append("  ${atom}$confStr\n")
        }
        return sb.toString()
    }
}

private fun callRetract(db: com.nocturnusai.NocturnusAI, tenantId: String, args: JsonObject): String {
    val predicate = args["predicate"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing predicate")
    val argsList = args["args"]?.jsonArray?.map { it.jsonPrimitive.content } ?: throw IllegalArgumentException("Missing args")
    val scope = args["scope"]?.jsonPrimitive?.contentOrNull

    val terms = argsList.map { parseTerm(it) }
    val atom = com.nocturnusai.core.Atom(predicate, terms, scope = scope)
    db.retractFact(atom, tenantId)
    return "Forgotten: $atom (and any knowledge derived from it)"
}

private fun callContextWindow(db: com.nocturnusai.NocturnusAI, tenantId: String, args: JsonObject): String {
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

private fun callTemporalQuery(db: com.nocturnusai.NocturnusAI, tenantId: String, args: JsonObject): String {
    val predicate = args["predicate"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing predicate")
    val argsList = args["args"]?.jsonArray?.map { it.jsonPrimitive.content } ?: throw IllegalArgumentException("Missing args")
    val timestamp = args["timestamp"]?.jsonPrimitive?.longOrNull ?: throw IllegalArgumentException("Missing timestamp")
    val scope = args["scope"]?.jsonPrimitive?.contentOrNull

    val terms = argsList.map { parseTerm(it) }
    val pattern = com.nocturnusai.core.Atom(predicate, terms, scope = scope)
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

private fun callConsolidate(db: com.nocturnusai.NocturnusAI, tenantId: String): String {
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

private fun callDecay(db: com.nocturnusai.NocturnusAI, tenantId: String, args: JsonObject): String {
    val threshold = args["threshold"]?.jsonPrimitive?.doubleOrNull
    val result = db.runDecay(tenantId, threshold)
    return "Decay complete: ${result.expiredCount} expired, ${result.evictedCount} evicted (${result.removedAtoms.size} total removed)"
}

private fun callPredicates(db: com.nocturnusai.NocturnusAI, tenantId: String, args: JsonObject): String {
    val scope = args["scope"]?.jsonPrimitive?.contentOrNull

    // Get all facts and group by predicate
    val allFacts = db.getAllFacts(tenantId, scope).toList()
    val factsByPredicate = allFacts.groupBy { it.predicate }
    val predicateMap = mutableMapOf<String, MutableMap<String, Any>>()

    for ((pred, facts) in factsByPredicate) {
        predicateMap[pred] = mutableMapOf(
            "count" to facts.size,
            "arity" to (facts.firstOrNull()?.args?.size ?: 0)
        )
    }

    // Also include rules' head predicates
    val rules = db.getRules(tenantId, scope)
    for (rule in rules) {
        val pred = rule.head.predicate
        if (pred !in predicateMap) {
            predicateMap[pred] = mutableMapOf(
                "count" to 0,
                "arity" to rule.head.args.size
            )
        }
        predicateMap.getOrPut(pred) { mutableMapOf() }["hasRules"] = true
    }

    if (predicateMap.isEmpty()) return "Knowledge base is empty. No predicates found."

    val sb = StringBuilder("Schema: ${predicateMap.size} predicate(s) found:\n\n")
    for ((pred, info) in predicateMap.entries.sortedByDescending { it.value["count"] as? Int ?: 0 }) {
        val count = info["count"] ?: 0
        val arity = info["arity"] ?: "?"
        val hasRules = if (info["hasRules"] == true) " [has rules]" else ""
        sb.append("  $pred/$arity — $count fact(s)$hasRules\n")
    }
    return sb.toString()
}

// --- Aggregation & Bulk Tool Implementations ---

private fun callAggregate(db: com.nocturnusai.NocturnusAI, tenantId: String, args: JsonObject): String {
    val predicate = args["predicate"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing predicate")
    val argsList = args["args"]?.jsonArray?.map { it.jsonPrimitive.content } ?: throw IllegalArgumentException("Missing args")
    val operationStr = args["operation"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing operation")
    val argIndex = args["argIndex"]?.jsonPrimitive?.intOrNull ?: 0
    val scope = args["scope"]?.jsonPrimitive?.contentOrNull

    val op = try {
        com.nocturnusai.storage.AggregateOp.valueOf(operationStr.uppercase())
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("Unknown operation '$operationStr'. Allowed: COUNT, SUM, MIN, MAX, AVG")
    }

    val terms = argsList.map { parseTerm(it) }
    val pattern = com.nocturnusai.core.Atom(predicate, terms, scope = scope)

    return if (op == com.nocturnusai.storage.AggregateOp.COUNT) {
        val count = db.countFacts(pattern, tenantId, scope)
        "COUNT($predicate) = $count"
    } else {
        val matchedFacts = db.countFacts(pattern, tenantId, scope)
        val result = db.aggregateFacts(pattern, argIndex, op, tenantId, scope)
        if (result == null) {
            "${op.name}($predicate, argIndex=$argIndex) = null (no numeric values found among $matchedFacts matched facts)"
        } else {
            "${op.name}($predicate, argIndex=$argIndex) = $result (over $matchedFacts matched facts)"
        }
    }
}

private fun callBulkAssert(db: com.nocturnusai.NocturnusAI, tenantId: String, args: JsonObject): String {
    val factsArray = args["facts"]?.jsonArray ?: throw IllegalArgumentException("Missing facts array")

    val atoms = factsArray.mapIndexed { index, elem ->
        val obj = elem.jsonObject
        val predicate = obj["predicate"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("facts[$index]: Missing predicate")
        val argsList = obj["args"]?.jsonArray?.map { it.jsonPrimitive.content }
            ?: throw IllegalArgumentException("facts[$index]: Missing args")
        val negated = obj["negated"]?.jsonPrimitive?.booleanOrNull ?: false
        val scope = obj["scope"]?.jsonPrimitive?.contentOrNull
        val ttl = obj["ttl"]?.jsonPrimitive?.longOrNull
        val validUntil = obj["validUntil"]?.jsonPrimitive?.longOrNull

        val terms = argsList.map { parseTerm(it) }
        com.nocturnusai.core.Atom(
            predicate = predicate,
            args = terms,
            truthVal = !negated,
            scope = scope,
            ttl = ttl,
            validUntil = validUntil
        )
    }

    val result = db.bulkAssertFacts(atoms, tenantId)

    val sb = StringBuilder("Bulk assert: ${result.asserted} stored, ${result.failed} failed.\n")
    if (result.errors.isNotEmpty()) {
        sb.append("Errors:\n")
        result.errors.forEach { sb.append("  - $it\n") }
    }
    return sb.toString()
}

private fun callRetractPattern(db: com.nocturnusai.NocturnusAI, tenantId: String, args: JsonObject): String {
    val predicate = args["predicate"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing predicate")
    val argsList = args["args"]?.jsonArray?.map { it.jsonPrimitive.content } ?: throw IllegalArgumentException("Missing args")
    val scope = args["scope"]?.jsonPrimitive?.contentOrNull

    val terms = argsList.map { parseTerm(it) }
    val pattern = com.nocturnusai.core.Atom(predicate, terms, scope = scope)

    val result = db.retractByPattern(pattern, tenantId, scope)

    if (result.retracted == 0) {
        return "No facts matched the pattern $predicate(${argsList.joinToString(", ")})."
    }

    val sb = StringBuilder("Retracted ${result.retracted} fact(s):\n")
    result.atoms.forEach { atom -> sb.append("  $atom\n") }
    return sb.toString()
}

// --- Scope Tool Implementations ---

private fun callForkScope(db: com.nocturnusai.NocturnusAI, tenantId: String, args: JsonObject): String {
    val sourceScope = args["sourceScope"]?.jsonPrimitive?.contentOrNull
    val targetScope = args["targetScope"]?.jsonPrimitive?.content
        ?: throw IllegalArgumentException("Missing targetScope")
    if (targetScope.isBlank()) throw IllegalArgumentException("targetScope must not be blank")

    val copied = db.forkScope(sourceScope, targetScope, tenantId)
    val sourceName = sourceScope ?: "<global>"
    return "Forked $copied atom(s) from scope '$sourceName' into scope '$targetScope'."
}

private fun callMergeScope(db: com.nocturnusai.NocturnusAI, tenantId: String, args: JsonObject): String {
    val sourceScope = args["sourceScope"]?.jsonPrimitive?.content
        ?: throw IllegalArgumentException("Missing sourceScope")
    if (sourceScope.isBlank()) throw IllegalArgumentException("sourceScope must not be blank")
    val targetScope = args["targetScope"]?.jsonPrimitive?.contentOrNull
    val strategyStr = args["strategy"]?.jsonPrimitive?.contentOrNull ?: "SOURCE_WINS"
    val strategy = try {
        com.nocturnusai.core.MergeStrategy.valueOf(strategyStr)
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("Unknown merge strategy '$strategyStr'. Valid values: SOURCE_WINS, TARGET_WINS, KEEP_BOTH, REJECT")
    }

    val result = db.mergeScope(sourceScope, targetScope, strategy, tenantId)
    val targetName = targetScope ?: "<global>"
    return "Merged scope '$sourceScope' into '$targetName': " +
        "${result.merged} atom(s) merged, ${result.conflictsResolved} conflict(s) resolved (strategy: ${result.strategy})."
}

private fun callListScopes(db: com.nocturnusai.NocturnusAI, tenantId: String): String {
    val scopes = db.listScopes(tenantId).sorted()
    if (scopes.isEmpty()) return "No named scopes found. Only the global (unscoped) partition exists."
    return "Found ${scopes.size} scope(s): ${scopes.joinToString(", ") { "'$it'" }}"
}

private fun callDeleteScope(db: com.nocturnusai.NocturnusAI, tenantId: String, args: JsonObject): String {
    val scope = args["scope"]?.jsonPrimitive?.content
        ?: throw IllegalArgumentException("Missing scope")
    if (scope.isBlank()) throw IllegalArgumentException("scope must not be blank")

    val deleted = db.deleteScope(scope, tenantId)
    return "Deleted scope '$scope': $deleted atom(s) removed."
}

// --- Helpers ---

private fun formatProof(node: com.nocturnusai.core.ProofNode, indent: String): String {
    val sb = StringBuilder()
    when (val step = node.step) {
        is com.nocturnusai.core.ProofStep.FactMatch -> {
            sb.append("${indent}FACT: ${step.fact}\n")
        }
        is com.nocturnusai.core.ProofStep.RuleApplication -> {
            sb.append("${indent}RULE: ${step.rule}\n")
            for (bodyProof in step.bodyProofs) {
                sb.append(formatProof(bodyProof, "$indent  "))
            }
        }
    }
    return sb.toString()
}

private fun jsonRpcError(id: JsonElement?, code: Int, message: String, data: JsonElement? = null): JsonRpcResponse {
    return JsonRpcResponse(id = id, error = JsonRpcError(code, message, data))
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
