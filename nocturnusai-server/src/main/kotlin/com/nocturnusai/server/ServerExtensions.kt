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

package com.nocturnusai.server

import com.nocturnusai.NocturnusAI
import com.nocturnusai.core.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// --- DTOs ---

@Serializable
data class ExecuteRequest(val command: String)

@Serializable
data class ExecuteResponse(val result: String)

@Serializable
data class FactRequest(
    val predicate: String,
    val args: List<String>,
    val truthVal: Boolean = true,
    val negated: Boolean = false,
    val scope: String? = null,
    val metadata: Map<String, JsonElement> = emptyMap(),
    // Temporal fields for agent memory management
    val validFrom: Long? = null,
    val validUntil: Long? = null,
    val ttl: Long? = null, // time-to-live in milliseconds
    // Optional confidence score (0.0–1.0). null means unknown confidence.
    val confidence: Double? = null,
    // Conflict resolution strategy. null means use database default.
    val conflictStrategy: com.nocturnusai.core.ConflictStrategy? = null
)

@Serializable
data class AtomDto(
    val predicate: String,
    val args: List<String>,
    val negated: Boolean = false,
    val scope: String? = null,
    val metadata: Map<String, JsonElement> = emptyMap()
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
    val isMultiTenant: Boolean = false,
    val defaultConflictStrategy: com.nocturnusai.core.ConflictStrategy = com.nocturnusai.core.ConflictStrategy.REJECT
)

@Serializable
data class CreateTenantRequest(val tenantId: String)

// --- Error Response ---

@Serializable
data class ErrorResponse(
    val code: String,
    val message: String,
    val details: Map<String, String>? = null
)

// --- Response DTOs ---

@Serializable
data class AtomResponse(
    val predicate: String,
    val args: List<String>,
    val negated: Boolean = false,
    val scope: String? = null,
    val metadata: Map<String, JsonElement> = emptyMap(),
    val createdAt: Long? = null,
    val validFrom: Long? = null,
    val validUntil: Long? = null,
    val ttl: Long? = null,
    val confidence: Double? = null
) {
    companion object {
        fun from(atom: Atom): AtomResponse = AtomResponse(
            predicate = atom.predicate,
            args = atom.args.map { it.toString() },
            negated = !atom.truthVal,
            scope = atom.scope,
            metadata = atom.metadata,
            createdAt = atom.createdAt,
            validFrom = atom.validFrom,
            validUntil = atom.validUntil,
            ttl = atom.ttl,
            confidence = atom.confidence
        )
    }
}

// --- Proof Response DTOs ---

@Serializable
data class ProofStepResponse(
    val type: String,  // "fact_match" or "rule_application"
    val fact: AtomResponse? = null,
    val rule: String? = null,
    val bodyProofs: List<ProofNodeResponse>? = null
) {
    companion object {
        fun from(step: ProofStep): ProofStepResponse = when (step) {
            is ProofStep.FactMatch -> ProofStepResponse(
                type = "fact_match",
                fact = AtomResponse.from(step.fact)
            )
            is ProofStep.RuleApplication -> ProofStepResponse(
                type = "rule_application",
                rule = step.rule.toString(),
                bodyProofs = step.bodyProofs.map { ProofNodeResponse.from(it) }
            )
        }
    }
}

@Serializable
data class ProofNodeResponse(
    val goal: AtomResponse,
    val step: ProofStepResponse,
    val substitution: Map<String, String> = emptyMap()
) {
    companion object {
        fun from(node: ProofNode): ProofNodeResponse = ProofNodeResponse(
            goal = AtomResponse.from(node.goal),
            step = ProofStepResponse.from(node.step),
            substitution = node.substitution
        )
    }
}

@Serializable
data class ProofTreeResponse(
    val result: AtomResponse,
    val proof: ProofNodeResponse
) {
    companion object {
        fun from(tree: ProofTree): ProofTreeResponse = ProofTreeResponse(
            result = AtomResponse.from(tree.result),
            proof = ProofNodeResponse.from(tree.proof)
        )
    }
}

// --- Test Framework Request/Response DTOs ---

@Serializable
data class SetupActionRequest(
    val type: String,  // "assert_fact" or "assert_rule"
    val fact: FactRequest? = null,
    val rule: RuleRequest? = null
)

@Serializable
data class ExpectationRequest(
    val type: String,  // "provable", "not_provable", "results_exactly", "result_count"
    val goal: FactRequest,
    val expected: List<FactRequest>? = null,
    val count: Int? = null
)

@Serializable
data class TestCaseRequest(
    val name: String,
    val setup: List<SetupActionRequest>,
    val expectations: List<ExpectationRequest>
)

// --- Extraction DTOs ---

@Serializable
data class ExtractionRequest(
    val text: String,
    val assert: Boolean = false,
    val rules: Boolean = false,
    val scope: String? = null,
    val context: String? = null
)

@Serializable
data class ExtractedFactDto(
    val predicate: String,
    val args: List<String>,
    val confidence: Float = 1.0f
)

@Serializable
data class ExtractedAtomDto(
    val predicate: String,
    val args: List<String>,
    val negated: Boolean = false
)

@Serializable
data class ExtractedRuleDto(
    val head: ExtractedAtomDto,
    val body: List<ExtractedAtomDto>,
    val variables: List<String>,
    val confidence: Float = 1.0f,
    val templateType: String? = null
)

@Serializable
data class ExtractionResponse(
    val facts: List<ExtractedFactDto>,
    val rules: List<ExtractedRuleDto> = emptyList(),
    val asserted: Boolean,
    val provider: String,
    val model: String
)

@Serializable
data class BatchExtractionRequest(
    val texts: List<String>,
    val assert: Boolean = false,
    val rules: Boolean = false,
    val scope: String? = null,
    val context: String? = null
)

@Serializable
data class BatchExtractionResult(
    val text: String,
    val facts: List<ExtractedFactDto>,
    val rules: List<ExtractedRuleDto> = emptyList(),
    val asserted: Boolean
)

@Serializable
data class BatchExtractionResponse(
    val results: List<BatchExtractionResult>,
    val provider: String,
    val model: String
)

// --- Synthesis DTOs ---

@Serializable
data class SynthesisRequest(
    val question: String,
    val scope: String? = null
)

@Serializable
data class DerivationStep(
    val fact: String,
    val type: String,
    val rule: String? = null
)

@Serializable
data class SynthesisResponse(
    val answer: String,
    val derivation: List<DerivationStep>,
    val missingContext: String,
    val confidence: Float,
    val queriesExecuted: List<String>,
    val provider: String,
    val model: String
)

// --- Exceptions ---

class ValidationException(message: String) : IllegalArgumentException(message)

class DatabaseNotFoundException(val dbName: String) : RuntimeException("Database '$dbName' not found")

// --- Validation ---

object ValidationLimits {
    const val MAX_PREDICATE_LENGTH = 255
    const val MAX_ARG_COUNT = 50
    const val MAX_STRING_LENGTH = 10_000
    const val MAX_DB_NAME_LENGTH = 64
    const val MAX_TENANT_ID_LENGTH = 128
    const val MAX_METADATA_KEYS = 32
    const val MAX_METADATA_KEY_LENGTH = 128
    const val MAX_METADATA_VALUE_SIZE = 8_192       // 8KB per value
    const val MAX_METADATA_TOTAL_SIZE = 32_768      // 32KB total
}

private val SAFE_NAME_REGEX = Regex("^[a-zA-Z0-9_-]+$")

object Validator {
    fun validateDatabaseName(name: String) {
        if (name.isBlank()) throw ValidationException("Database name must not be blank")
        if (name.length > ValidationLimits.MAX_DB_NAME_LENGTH) throw ValidationException("Database name exceeds max length of ${ValidationLimits.MAX_DB_NAME_LENGTH}")
        if (name.contains("..") || name.contains("/") || name.contains("\\")) throw ValidationException("Database name contains illegal characters")
        if (!SAFE_NAME_REGEX.matches(name)) throw ValidationException("Database name must be alphanumeric, dash, or underscore only")
    }

    fun validateTenantId(id: String) {
        if (id.isBlank()) throw ValidationException("Tenant ID must not be blank")
        if (id.length > ValidationLimits.MAX_TENANT_ID_LENGTH) throw ValidationException("Tenant ID exceeds max length of ${ValidationLimits.MAX_TENANT_ID_LENGTH}")
        if (id.contains("..") || id.contains("/") || id.contains("\\")) throw ValidationException("Tenant ID contains illegal characters")
        if (!SAFE_NAME_REGEX.matches(id)) throw ValidationException("Tenant ID must be alphanumeric, dash, or underscore only")
    }

    fun validateFactRequest(req: FactRequest) {
        if (req.predicate.length > ValidationLimits.MAX_PREDICATE_LENGTH) throw ValidationException("Predicate exceeds max length of ${ValidationLimits.MAX_PREDICATE_LENGTH}")
        if (req.predicate.isBlank()) throw ValidationException("Predicate must not be blank")
        if (req.args.size > ValidationLimits.MAX_ARG_COUNT) throw ValidationException("Argument count exceeds max of ${ValidationLimits.MAX_ARG_COUNT}")
        for (arg in req.args) {
            if (arg.length > ValidationLimits.MAX_STRING_LENGTH) throw ValidationException("Argument exceeds max length of ${ValidationLimits.MAX_STRING_LENGTH}")
        }
        validateMetadata(req.metadata, "fact")
    }

    fun validateRuleRequest(req: RuleRequest) {
        validateAtomDto(req.head, "head")
        if (req.body.isEmpty()) throw ValidationException("Rule body must not be empty")
        req.body.forEachIndexed { i, atom ->
            validateAtomDto(atom, "body[$i]")
        }
    }

    private fun validateAtomDto(atom: AtomDto, label: String) {
        if (atom.predicate.length > ValidationLimits.MAX_PREDICATE_LENGTH) throw ValidationException("$label predicate exceeds max length of ${ValidationLimits.MAX_PREDICATE_LENGTH}")
        if (atom.predicate.isBlank()) throw ValidationException("$label predicate must not be blank")
        if (atom.args.size > ValidationLimits.MAX_ARG_COUNT) throw ValidationException("$label argument count exceeds max of ${ValidationLimits.MAX_ARG_COUNT}")
        for (arg in atom.args) {
            if (arg.length > ValidationLimits.MAX_STRING_LENGTH) throw ValidationException("$label argument exceeds max length of ${ValidationLimits.MAX_STRING_LENGTH}")
        }
        validateMetadata(atom.metadata, label)
    }

    fun validateMetadata(metadata: Map<String, JsonElement>, label: String) {
        if (metadata.isEmpty()) return
        if (metadata.size > ValidationLimits.MAX_METADATA_KEYS) {
            throw ValidationException("$label metadata exceeds max key count of ${ValidationLimits.MAX_METADATA_KEYS}")
        }
        var totalSize = 0
        for ((key, value) in metadata) {
            if (key.length > ValidationLimits.MAX_METADATA_KEY_LENGTH) {
                throw ValidationException("$label metadata key '$key' exceeds max length of ${ValidationLimits.MAX_METADATA_KEY_LENGTH}")
            }
            val valueSize = value.toString().length
            if (valueSize > ValidationLimits.MAX_METADATA_VALUE_SIZE) {
                throw ValidationException("$label metadata value for key '$key' exceeds max size of ${ValidationLimits.MAX_METADATA_VALUE_SIZE} bytes")
            }
            totalSize += key.length + valueSize
        }
        if (totalSize > ValidationLimits.MAX_METADATA_TOTAL_SIZE) {
            throw ValidationException("$label metadata total size exceeds max of ${ValidationLimits.MAX_METADATA_TOTAL_SIZE} bytes")
        }
    }
}

// --- Helper Functions ---

fun ApplicationCall.getContext(dbManager: DatabaseManager): Pair<NocturnusAI, String> {
    val dbName = request.header("X-Database") ?: "default"
    val tenantId = request.header("X-Tenant-ID")?.takeIf { it.isNotBlank() }
        ?: throw ValidationException("X-Tenant-ID header is required")
    Validator.validateTenantId(tenantId)
    val db = dbManager.getDatabase(dbName)
        ?: throw DatabaseNotFoundException(dbName)
    return Pair(db, tenantId)
}

fun parseTerm(str: String): com.nocturnusai.core.Term {
    return if (str.startsWith("?")) {
        com.nocturnusai.core.Term.Variable(str.drop(1))
    } else {
        // Try parsing number?
        val d = str.toDoubleOrNull()
        if (d != null) com.nocturnusai.core.Term.NumberLit(d)
        else com.nocturnusai.core.Term.Identifier(str)
    }
}
