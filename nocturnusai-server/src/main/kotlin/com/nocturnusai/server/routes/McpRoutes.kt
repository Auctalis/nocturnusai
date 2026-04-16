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

import com.nocturnusai.context.GoalSpec
import com.nocturnusai.context.OptimizeContextRequest
import com.nocturnusai.context.RelevanceBucket
import com.nocturnusai.memory.ContextFormat
import com.nocturnusai.memory.ContextFormatter
import com.nocturnusai.memory.ContextWindow
import com.nocturnusai.memory.ScoredAtom
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

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val result: JsonElement? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
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
            // SECURITY: X-Tenant-ID is required for tenant isolation. MCP previously
            // defaulted to "default", which silently routed every caller into the
            // same shared tenant when the header was omitted.
            val tenantId = call.request.header("X-Tenant-ID")
                ?: return@post call.respond(
                    jsonRpcError(
                        request.id,
                        McpErrorCodes.VALIDATION_ERROR,
                        "Missing required header: X-Tenant-ID"
                    )
                )
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
        // SECURITY: X-Tenant-ID is required — see /mcp handler above for rationale.
        val tenantId = call.request.header("X-Tenant-ID")
            ?: return@get call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("VALIDATION_ERROR", "Missing required header: X-Tenant-ID")
            )
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
            "version" to JsonPrimitive("0.3.12")
        ))
    ))
    return JsonRpcResponse(id = request.id, result = result)
}

private fun handleToolsList(request: JsonRpcRequest): JsonRpcResponse {
    val tools = buildJsonArray {
        add(toolSchema(
            name = "tell",
            description = "Assert a fact into the knowledge base. Stores knowledge that can be queried and used in logical reasoning. Supports auto-expiration via ttl (milliseconds) or validUntil (epoch ms), confidence scoring, and configurable conflict resolution.",
            properties = mapOf(
                "predicate" to propString("The relationship or property name (e.g., 'parent', 'likes', 'located_in')"),
                "args" to propArray("The entities involved (e.g., ['alice', 'bob'] for 'alice is parent of bob')"),
                "scope" to propString("Optional isolation scope for partitioned reasoning (e.g., 'session_123', 'hypothesis_a')"),
                "negated" to propBool("Set true to store the explicit negation of this fact (distinct from NAF)"),
                "ttl" to propNumber("Auto-expire after this many milliseconds"),
                "validUntil" to propNumber("Epoch ms when this fact stops being valid"),
                "confidence" to propNumber("Confidence score 0.0–1.0 (e.g., 0.9 = high confidence from LLM extraction)"),
                "conflictStrategy" to propString("How to handle contradictions: REJECT (default — error on duplicate), NEWEST_WINS, CONFIDENCE (highest wins), KEEP_BOTH")
            ),
            required = listOf("predicate", "args")
        ))
        add(toolSchema(
            name = "teach",
            description = "Define a logical rule for automatic reasoning. When all conditions (body) are true, the conclusion (head) is automatically derivable via backward chaining. Use ?-prefixed variables (e.g., ?x, ?who). Supports Negation-as-Failure in body atoms. Example: 'If ?x is human AND NOT god(?x), THEN ?x is mortal'.",
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
            description = "Query the knowledge base using multi-step logical reasoning (backward chaining with unification). Finds all provable answers by applying rules and matching facts. Use ?-prefixed variables for unknowns you want to discover. Optionally returns full proof chains showing the reasoning steps.",
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
            description = "Retract a fact from the knowledge base. Any knowledge that was derived from this fact is also automatically forgotten via the Truth Maintenance System (cascading retraction). This is the inverse of 'tell'.",
            properties = mapOf(
                "predicate" to propString("The relationship to forget"),
                "args" to propArray("The specific entities to forget about"),
                "scope" to propString("Optional scope")
            ),
            required = listOf("predicate", "args")
        ))
        add(toolSchema(
            name = "recall",
            description = "Time-travel query: recall what was known at a specific point in time. Returns facts that were valid at the given timestamp, respecting temporal bounds (validFrom, validUntil, ttl). Useful for debugging agent behavior or reconstructing past state.",
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
            description = "Get the most relevant knowledge for your current reasoning step, ranked by composite salience (recency × frequency × priority). Returns a token-optimized context window. Supports three output formats: 'predicate' (machine-readable), 'natural' (LLM-optimized prose), 'structured' (grouped with metadata). Pass goals for goal-driven selection, sessionId for incremental diffing across turns.",
            properties = mapOf(
                "maxFacts" to propNumber("Maximum facts to return (default: 100)"),
                "minSalience" to propNumber("Minimum salience score 0.0-1.0 (default: 0.0)"),
                "minRelevance" to propNumber("Legacy alias for minSalience"),
                "predicates" to propArray("Optional: only include these relationship types"),
                "scope" to propString("Optional scope filter"),
                "format" to propString("Output format: 'predicate' (default, machine-readable), 'natural' (LLM-optimized natural language), or 'structured' (grouped with metadata)"),
                "includeRules" to propBool("Include reasoning rules in the context (default: true)"),
                "goals" to propGoalArray("Goal atoms for goal-driven selection, e.g. [{\"predicate\":\"recommend\",\"args\":[\"?x\"]}]"),
                "sessionId" to propString("Session ID for incremental diffing across turns"),
                "autoResolveContradictions" to propBool("Auto-resolve contradictions by salience (default: true)"),
                "maxFactsPerPredicate" to propNumber("Optional diversity cap per predicate")
            ),
            required = emptyList()
        ))
        add(toolSchema(
            name = "compress",
            description = "Run memory consolidation: detects repeated episodic patterns (e.g., 'user asked about X five times') and creates semantic summaries. Reduces memory footprint in long-running agent sessions while preserving essential knowledge.",
            properties = emptyMap(),
            required = emptyList()
        ))
        add(toolSchema(
            name = "cleanup",
            description = "Run memory decay and eviction. Expires facts past their TTL and evicts low-salience facts when memory exceeds capacity. Call periodically in long-running agent sessions to prevent unbounded memory growth.",
            properties = mapOf(
                "threshold" to propNumber("Relevance threshold below which facts are evicted (default: 0.05)")
            ),
            required = emptyList()
        ))
        add(toolSchema(
            name = "predicates",
            description = "Discover the knowledge base schema. Lists all predicates (relationship types) currently stored, with their arity (argument count), fact count, and whether they have associated rules. Use this to understand what knowledge is available before querying.",
            properties = mapOf(
                "scope" to propString("Optional scope filter")
            ),
            required = emptyList()
        ))
        add(toolSchema(
            name = "aggregate",
            description = "Compute aggregations over matching facts. Supports COUNT (number of matches), SUM, MIN, MAX, and AVG over a numeric argument at a specified position. Example: COUNT all score(player, ?) facts, or AVG scores at argIndex=1.",
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
            description = "Assert multiple facts in a single call for efficiency. Non-transactional: each fact is attempted independently — contradictions are reported as errors without aborting the batch. Returns counts of successful and failed assertions.",
            properties = mapOf(
                "facts" to propFactArray("Array of fact objects, each with 'predicate', 'args', optional 'negated', 'scope', 'ttl', 'validUntil'")
            ),
            required = listOf("facts")
        ))
        add(toolSchema(
            name = "retract_pattern",
            description = "Retract all facts matching a pattern in a single call. Use ?-prefixed variables as wildcards to retract multiple facts at once. Returns the count and list of retracted facts. Cascading retraction applies to each removed fact.",
            properties = mapOf(
                "predicate" to propString("The predicate pattern to match for retraction"),
                "args" to propArray("Arguments. Use ?x, ?who etc. as wildcards to match multiple facts"),
                "scope" to propString("Optional scope filter")
            ),
            required = listOf("predicate", "args")
        ))
        add(toolSchema(
            name = "fork_scope",
            description = "Fork a knowledge base scope — creates an independent copy of all facts in the source scope under a new target scope name. Use this for hypothetical reasoning ('What if Alice moves to London?') without modifying the main knowledge base. Similar to git branch for knowledge.",
            properties = mapOf(
                "sourceScope" to propString("Scope to copy from. Omit or pass null for the global partition."),
                "targetScope" to propString("New scope name to copy all facts into.")
            ),
            required = listOf("targetScope")
        ))
        add(toolSchema(
            name = "merge_scope",
            description = "Merge facts from one scope back into another (default: global). Use this to commit hypothetical reasoning results back into the main knowledge base. Choose a conflict strategy: SOURCE_WINS overwrites, TARGET_WINS keeps existing, KEEP_BOTH retains both versions, REJECT aborts if any conflicts.",
            properties = mapOf(
                "sourceScope" to propString("Scope to merge facts from."),
                "targetScope" to propString("Destination scope. Omit or pass null for the global partition."),
                "strategy" to propString("Conflict strategy: SOURCE_WINS | TARGET_WINS | KEEP_BOTH | REJECT (default: SOURCE_WINS)")
            ),
            required = listOf("sourceScope")
        ))
        add(toolSchema(
            name = "list_scopes",
            description = "List all named scopes in the knowledge base. Shows what hypothetical contexts or reasoning branches currently exist. The global (unscoped) partition is always present but not listed.",
            properties = emptyMap(),
            required = emptyList()
        ))
        add(toolSchema(
            name = "delete_scope",
            description = "Delete a knowledge base scope and all facts within it. Use this to clean up completed or abandoned hypothetical reasoning branches. This is irreversible.",
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
        val naf = obj["naf"]?.jsonPrimitive?.booleanOrNull ?: false
        com.nocturnusai.core.Atom(bp, ba, truthVal = !negated, scope = scope, naf = naf)
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
    val minSalience = args["minSalience"]?.jsonPrimitive?.doubleOrNull
        ?: args["minRelevance"]?.jsonPrimitive?.doubleOrNull
        ?: 0.0
    val predicates = args["predicates"]?.jsonArray?.map { it.jsonPrimitive.content }
    val scope = args["scope"]?.jsonPrimitive?.contentOrNull
    val formatStr = args["format"]?.jsonPrimitive?.contentOrNull ?: "predicate"
    val includeRules = args["includeRules"]?.jsonPrimitive?.booleanOrNull ?: true

    // Advanced mode params
    val goalsJson = args["goals"]?.jsonArray
    val sessionId = args["sessionId"]?.jsonPrimitive?.contentOrNull
    val autoResolve = args["autoResolveContradictions"]?.jsonPrimitive?.booleanOrNull ?: true
    val maxPerPredicate = args["maxFactsPerPredicate"]?.jsonPrimitive?.intOrNull

    val isAdvanced = goalsJson != null || sessionId != null || maxPerPredicate != null

    val contextFormat = when (formatStr.lowercase()) {
        "natural" -> ContextFormat.NATURAL
        "structured" -> ContextFormat.STRUCTURED
        else -> ContextFormat.PREDICATE
    }

    if (isAdvanced) {
        val goals = goalsJson?.map { goalObj ->
            val g = goalObj.jsonObject
            GoalSpec(
                predicate = g["predicate"]!!.jsonPrimitive.content,
                args = g["args"]!!.jsonArray.map { it.jsonPrimitive.content },
                negated = g["negated"]?.jsonPrimitive?.booleanOrNull ?: false
            )
        }

        val result = db.optimizeContext(
            request = OptimizeContextRequest(
                maxFacts = maxFacts,
                scope = scope,
                predicates = predicates,
                goals = goals,
                sessionId = sessionId,
                autoResolveContradictions = autoResolve,
                maxFactsPerPredicate = maxPerPredicate
            ),
            tenantId = tenantId
        )

        if (result.entries.isEmpty() && contextFormat == ContextFormat.PREDICATE) {
            return "Context window is empty. No facts match the criteria."
        }

        // Convert to ContextWindow for formatting
        val scoredAtoms = result.entries.map { entry -> ScoredAtom(entry.atom, entry.salience) }
        val window = ContextWindow(
            facts = scoredAtoms,
            totalAvailable = result.totalFactsAvailable,
            windowSize = result.totalFactsIncluded,
            predicateDistribution = scoredAtoms.groupBy { it.atom.predicate }.mapValues { it.value.size },
            generatedAt = result.generatedAt
        )
        val rules = if (includeRules) db.getRules(tenantId, scope) else emptyList()
        return ContextFormatter.format(window, rules, contextFormat)
    } else {
        val window = db.buildContextWindow(tenantId, scope, maxFacts, minSalience, predicates)

        // Backward compat: for PREDICATE format, keep the original empty-window message
        if (window.facts.isEmpty() && contextFormat == ContextFormat.PREDICATE) {
            return "Context window is empty. No facts match the criteria."
        }

        val rules = if (includeRules) db.getRules(tenantId, scope) else emptyList()
        return ContextFormatter.format(window, rules, contextFormat)
    }
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

private fun propFactArray(description: String): JsonObject = JsonObject(mapOf(
    "type" to JsonPrimitive("array"),
    "description" to JsonPrimitive(description),
    "items" to JsonObject(mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(mapOf(
            "predicate" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("The relationship or property name"))),
            "args" to JsonObject(mapOf("type" to JsonPrimitive("array"), "items" to JsonObject(mapOf("type" to JsonPrimitive("string"))), "description" to JsonPrimitive("The entities involved"))),
            "negated" to JsonObject(mapOf("type" to JsonPrimitive("boolean"), "description" to JsonPrimitive("Set true to store the negation"))),
            "scope" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Optional isolation scope"))),
            "ttl" to JsonObject(mapOf("type" to JsonPrimitive("number"), "description" to JsonPrimitive("Auto-expire after this many milliseconds"))),
            "validUntil" to JsonObject(mapOf("type" to JsonPrimitive("number"), "description" to JsonPrimitive("Epoch ms when this fact expires")))
        )),
        "required" to buildJsonArray { add(JsonPrimitive("predicate")); add(JsonPrimitive("args")) }
    ))
))

private fun propGoalArray(description: String): JsonObject = JsonObject(mapOf(
    "type" to JsonPrimitive("array"),
    "description" to JsonPrimitive(description),
    "items" to JsonObject(mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(mapOf(
            "predicate" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
            "args" to JsonObject(mapOf("type" to JsonPrimitive("array"), "items" to JsonObject(mapOf("type" to JsonPrimitive("string"))))),
            "negated" to JsonObject(mapOf("type" to JsonPrimitive("boolean")))
        ))
    ))
))
