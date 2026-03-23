# NocturnusAI — Quality, Hardening & Feature Expansion
**Spec Date**: 2026-03-23
**Priority**: A3 (core correctness → implementation depth) → B2 (multi-tenant security hardening) → C (feature expansion)
**Deployment target**: Multi-tenant SaaS with external API exposure

---

## Table of Contents

1. [Phase 1A — Core Correctness Bugs](#phase-1a)
2. [Phase 1B — Security Hardening](#phase-1b)
3. [Phase 2A — Implementation Depth](#phase-2a)
4. [Phase 2B — Observability & Testing](#phase-2b)
5. [Phase 3 — Feature Expansion](#phase-3)
6. [Cross-Cutting Concerns](#cross-cutting)
7. [Acceptance Criteria](#acceptance-criteria)

---

## Guiding Principles

- **Fix before extend**: No new features ship while correctness bugs exist in the critical path.
- **Security is non-negotiable for B2**: Rate limiting, audit logs, and tenant enforcement are blockers, not nice-to-haves.
- **Parallelism**: Phase 1A (nocturnusai-core) and Phase 1B (nocturnusai-server) are independent modules and can be worked simultaneously.
- **Tests as proof**: Every fix and feature requires tests demonstrating the old behavior was broken and the new behavior is correct.
- **No scaffolding**: Every item here must be fully implemented — not stubbed, not partially wired up.

---

## Phase 1A — Core Correctness Bugs {#phase-1a}

> These are features claimed to work that don't. Each is a regression or a known gap that blocks real use.

### 1A-1: Complete the Parser

**Current state**: `Parser.kt` cuts off mid-implementation around line 80. The `/execute` DSL endpoint accepts input but cannot parse rules, queries, constraints, tests, or extractions. The tokenizer (`Tokenizer.kt`) is complete and correct.

**Required implementation**:

The parser must be a complete recursive descent parser covering the full Logiql grammar:

```
program       ::= statement*
statement     ::= assertion | query | rule | constraint | test | extraction | retraction
assertion     ::= "assert" atom "."
                | "assert" "-" atom "."           // negative assertion
retraction    ::= "retract" atom "."
query         ::= "?" atom "."
               | "?" atom "," atom ("," atom)* "."  // conjunction
rule          ::= atom ":-" body "."
body          ::= bodyAtom ("," bodyAtom)*
bodyAtom      ::= atom                            // positive condition
               | "NOT" atom                       // NAF condition
atom          ::= predicate "(" termList ")"
               | predicate                        // 0-arity
termList      ::= term ("," term)*
term          ::= VARIABLE | IDENTIFIER | STRING | NUMBER
constraint    ::= "constraint" atom ":-" body "."
test          ::= "test" STRING ":" expectation+ "end"
expectation   ::= "provable" atom "."
               | "not_provable" atom "."
               | "results" atom "==" number "."
               | "exactly" atom "==" "[" atom ("," atom)* "]" "."
extraction    ::= "extract" ":" STRING "."
```

**Parsing rules**:
- Variables: tokens starting with `?` (e.g. `?x`, `?who`)
- String literals: double-quoted (e.g. `"hello"`)
- Numbers: integer and floating point
- Identifiers: unquoted alphanumeric + underscore
- Predicates: identifiers (lowercase by convention)
- Whitespace and comments (`//` to end of line) are skipped

**Error handling**:
- `ParseException(message: String, line: Int, column: Int)` — thrown on syntax error
- Parser must report the token where parsing failed and what was expected
- On error in a statement, attempt to synchronize by skipping to the next `.` before continuing (error recovery)
- Invalid programs must NOT partially execute — validate the entire program before executing any statement

**Return type**:
```kotlin
sealed class ParsedStatement {
    data class AssertFact(val atom: Atom) : ParsedStatement()
    data class AssertNegativeFact(val atom: Atom) : ParsedStatement()
    data class RetractFact(val atom: Atom) : ParsedStatement()
    data class Query(val goals: List<Atom>) : ParsedStatement()
    data class AssertRule(val rule: Rule) : ParsedStatement()
    data class AssertConstraint(val rule: Rule) : ParsedStatement()
    data class RunTest(val name: String, val expectations: List<Expectation>) : ParsedStatement()
    data class Extract(val text: String) : ParsedStatement()
}
data class ParseResult(val statements: List<ParsedStatement>, val errors: List<ParseException>)
```

**Tests required**:
- Round-trip: parse every valid statement form, execute it, verify the result
- Error recovery: programs with one bad statement still execute the valid ones
- All error cases: missing `.`, unknown keyword, malformed term, unclosed paren
- NAF in rule bodies: `mortal(?x) :- human(?x), NOT immortal(?x).`
- Confidence in atoms: `likes(alice, bob) [0.9].` (if supported by grammar)
- Multi-statement programs with mixed statement types
- Empty program
- Program with only comments

---

### 1A-2: Implement `solveWithProof()` on BackwardChainer

**Current state**: `TestRunner.kt` calls `backwardChainer.solveWithProof(goal)` to populate `ProofTree` objects for test expectations. This method does not exist on `BackwardChainer`. The test framework (`/execute` with `test` blocks) is entirely non-functional.

**Required implementation**:

```kotlin
data class ProofResult(
    val substitution: Substitution,
    val confidence: Double?,
    val proof: ProofTree
)

fun solveWithProof(
    goal: Atom,
    scope: String? = null,
    maxDepth: Int = 100
): Sequence<ProofResult>
```

The proof tree must be built as a side-effect of SLD resolution, capturing:
- `ProofStep.FactMatch(atom)` — when the goal unified directly with a stored fact
- `ProofStep.RuleApplication(rule, substitution, subProofs)` — when a rule fired, including the sub-proofs for each body atom

**Implementation approach**:
Extend `solveRecursiveWithConfidence()` to accept an accumulator `proofSteps: MutableList<ProofStep>`. At each step:
1. On fact match: append `ProofStep.FactMatch(matchedFact)`
2. On rule application: recursively collect sub-proofs for each body atom, then wrap in `ProofStep.RuleApplication`
3. Build `ProofNode(goal, steps)` and return it alongside the substitution

The existing `solve()` and `solveWithConfidence()` methods must remain unchanged (they call the internal recursive helper without proof accumulation, for performance).

**ProofTree structure** (already defined in `ProofNode.kt`):
```kotlin
data class ProofTree(val root: ProofNode)
data class ProofNode(val goal: Atom, val steps: List<ProofStep>)
sealed class ProofStep {
    data class FactMatch(val fact: Atom) : ProofStep()
    data class RuleApplication(
        val rule: Rule,
        val substitution: Substitution,
        val subProofs: List<ProofNode>
    ) : ProofStep()
}
```

**Tests required**:
- Fact match: single-step proof for a directly asserted atom
- Rule application: one-level rule firing, proof shows rule + matched body facts
- Multi-step chain: `mortal(?x) :- human(?x)`, assert `human(socrates)`, proof tree has 2 nodes
- NAF in proof: proof step for a NAF condition that succeeded (goal not provable)
- Proof for failed query: empty sequence
- Proof with variables: substitution correctly applied in proof nodes
- `TestRunner` integration: full test block execution with `provable`, `not_provable`, `results` expectations all passing via proof trees

---

### 1A-3: Fix NAF Ordering Sensitivity

**Current state**: Both Rete forward chaining and backward chaining produce different results depending on the order in which facts are asserted. The CLAUDE.md explicitly documents: "Assert NAF-blocking facts *before* triggering facts so both forward and backward chaining agree." This is a semantic bug — NAF correctness must not depend on assertion order.

**Root cause**:
- **Rete**: `onFactAsserted()` evaluates NAF conditions against the store at the moment a rule fires. If the NAF-blocking fact hasn't been asserted yet, the NAF check passes, the rule fires, and the derived fact is asserted. When the blocking fact arrives later, Rete doesn't re-evaluate already-fired rules.
- **Backward chaining**: Always evaluates NAF at query time (correct by design). No ordering bug here.

**Required fix — Rete**:

When a new fact is asserted that could block a previously derived fact (i.e., the new fact matches a NAF condition in some rule body), Rete must:
1. Find all rules where this fact's predicate appears as a `naf=true` body atom.
2. For each such rule, find all previously derived facts (tracked in `derivedFacts` map in `ReteEngine`).
3. Re-evaluate each derived fact's full rule body. If the body no longer holds (because the NAF condition is now blocked), retract the derived fact via `ProvenanceTracker`.

This requires:
- A reverse index on NAF conditions: `nafPredicateToRules: Map<String, List<Rule>>` built in `addRule()`.
- `onFactAsserted()` must check `nafPredicateToRules[fact.predicate]` after asserting, and trigger re-evaluation of affected derivations.
- `ReteEngine` must hold a reference to `ProvenanceTracker` for cascading retraction.

**Tests required** (ordering-independent variants):
- Assert blocking fact BEFORE trigger fact → derived fact never asserted
- Assert blocking fact AFTER trigger fact → derived fact retracted
- Assert blocking fact, retract it, assert trigger fact → derived fact asserted
- Assert trigger fact, assert blocking fact, retract blocking fact → derived fact re-asserted
- Backward chaining and Rete agree on all of the above
- NAF with variables: blocking fact for one value doesn't block derivation for another
- Complex chain: NAF condition in a multi-body rule

---

### 1A-4: HTTP_GET_JSON Reliability

**Current state**: `BackwardChainer.kt` contains an `HTTP_GET_JSON` built-in predicate that makes synchronous HTTP calls with no timeout, no retry, no circuit breaker, and no error handling beyond swallowing exceptions. A hanging external service hangs the entire inference chain on that thread indefinitely.

**Required implementation**:

```kotlin
// Configuration (injectable via BackwardChainer constructor)
data class HttpBuiltinConfig(
    val timeoutMs: Long = 5_000,
    val maxRetries: Int = 2,
    val retryDelayMs: Long = 500,
    val failureMode: FailureMode = FailureMode.FAIL_CLOSED  // FAIL_CLOSED = fail the predicate, FAIL_OPEN = succeed with empty result
)
enum class FailureMode { FAIL_CLOSED, FAIL_OPEN }
```

**Retry logic**:
- Retry on: `IOException`, `SocketTimeoutException`, HTTP 5xx
- Do NOT retry on: HTTP 4xx, parse errors, invalid URL
- Backoff: `retryDelayMs * attempt` (linear, configurable)
- After all retries exhausted: apply `failureMode`

**Timeout**:
- Use `java.net.http.HttpClient` (JDK 11+) with `timeout(Duration.ofMillis(timeoutMs))`
- Wrap in `withTimeout { }` coroutine block if called from a coroutine context

**Error reporting**:
- On failure: log at WARN level with predicate name, URL, attempt count, final error
- Return empty sequence (FAIL_CLOSED) or a synthetic error fact (FAIL_OPEN mode)

**Tests required**:
- Success: mock server returns valid JSON, parsed into atoms correctly
- Timeout: mock server delays, verify predicate fails within `timeoutMs + buffer`
- Retry on 503: mock server returns 503 twice then 200, verify retry logic
- No retry on 404: mock server returns 404, verify exactly 1 attempt
- FAIL_OPEN mode: server unavailable, predicate returns empty (no exception propagated)
- FAIL_CLOSED mode: server unavailable, predicate fails (goal fails cleanly)
- Invalid URL in rule: parsing error, predicate fails with informative log message

---

### 1A-5: ConsistencyGuard Cycle Detection

**Current state**: `ConsistencyGuard.checkConstraints()` uses a recursive solver identical in structure to `BackwardChainer`. It has no visited-set / depth limit. Circular constraints (e.g. constraint A references predicate P, and evaluating P triggers constraint A again) cause a `StackOverflowError`.

**Required fix**:

```kotlin
private fun solveConstraint(
    goals: List<Atom>,
    substitution: Substitution,
    depth: Int = 0,
    visited: MutableSet<Atom> = mutableSetOf()
): Boolean {
    if (depth > MAX_CONSTRAINT_DEPTH) {
        logger.warn("Constraint depth limit ($MAX_CONSTRAINT_DEPTH) exceeded — treating as unsatisfied")
        return false
    }
    val currentGoal = goals.first().applySubstitution(substitution)
    if (currentGoal in visited) return false  // cycle detected
    visited.add(currentGoal)
    // ... rest of solver
}

companion object {
    const val MAX_CONSTRAINT_DEPTH = 50
}
```

**Tests required**:
- Direct self-referential constraint: `constraint foo(?x) :- foo(?x).` does not throw
- Mutual cycle: A references B, B references A — does not throw
- Deep but non-cyclic constraint chain: depth 49 succeeds, depth 51 is treated as unsatisfied with a WARN log
- Valid constraint with variables: still enforced correctly after adding cycle detection

---

### 1A-6: Scope DAG Transactional Safety

**Current state**: `NocturnusAI.setScopeParent()` modifies the `scopeParents: MutableMap<String, String>` without any synchronization. Concurrent calls from multiple tenant threads (e.g. two agents forking scopes simultaneously) can corrupt the map or create phantom cycles.

**Required fix**:

Add a `ReentrantReadWriteLock` to `NocturnusAI`:

```kotlin
private val scopeDagLock = ReentrantReadWriteLock()

fun setScopeParent(child: String, parent: String, tenantId: String) {
    scopeDagLock.writeLock().withLock {
        // Cycle check INSIDE write lock so it's atomic with the mutation
        val ancestors = getScopeAncestorsInternal(parent, tenantId)
        if (child in ancestors) {
            throw IllegalArgumentException("Setting $child -> $parent would create a cycle in scope DAG")
        }
        scopeParents[tenantId]?.put(child, parent)
            ?: throw TenantNotFoundException(tenantId)
    }
}

fun getScopeAncestors(scope: String, tenantId: String): List<String> {
    scopeDagLock.readLock().withLock {
        return getScopeAncestorsInternal(scope, tenantId)
    }
}
```

All reads and writes to `scopeParents` that cross a tenant context must hold the appropriate lock level.

**Tests required**:
- Concurrent `setScopeParent` from 100 threads — no exceptions, final DAG is consistent
- Cycle detection under concurrent modification: two threads race to create A→B and B→A — exactly one succeeds, one throws
- `getScopeAncestors` while `setScopeParent` is in-flight — always returns a consistent view
- Tenant isolation: setting scope parent in T1 does not affect T2

---

### 1A-7: Snapshot Integrity Validation on Restore

**Current state**: `SnapshotManager.saveSnapshot()` serializes state to JSON. There is no checksum written with the snapshot file. `loadSnapshot()` blindly deserializes whatever it finds. A partially written or externally corrupted snapshot loads silently, returning potentially inconsistent state.

**Required implementation**:

**On save**:
```kotlin
data class SnapshotFile(
    val version: Int = 2,
    val checksum: String,       // SHA-256 hex of the `data` field serialized
    val data: SnapshotData
)
```
Compute SHA-256 of the serialized `data` JSON string. Store it in `checksum`. Write `SnapshotFile` as the root.

**On load**:
1. Deserialize outer `SnapshotFile`
2. Re-serialize `data` field, compute SHA-256
3. Compare with stored `checksum`
4. If mismatch: log ERROR with file path and both checksums, throw `SnapshotCorruptionException`
5. Caller (`NocturnusAI.init()`) catches `SnapshotCorruptionException`: log WARN "Snapshot corrupt — falling back to WAL-only recovery", proceed without snapshot
6. If WAL also unavailable/corrupt: throw `RecoveryFailedException` with both errors

**Migration**: Add version field. Version 1 snapshots (no checksum) log a WARN and load anyway; version 2+ are validated.

**Tests required**:
- Happy path: save → load → verify atom count matches
- Corrupted checksum: manually flip a byte, verify `SnapshotCorruptionException` thrown
- Missing snapshot file: no exception, empty state returned
- Version 1 legacy format: loads without checksum validation, logs WARN
- WAL fallback: snapshot corrupt + WAL valid → WAL-only recovery succeeds with correct state
- Total failure: both corrupt → `RecoveryFailedException`

---

### 1A-8: Event Subscriber Exception Handling

**Current state**: `EventBus.kt` wraps subscriber callbacks in `try-catch(Exception)` and silently discards exceptions. A subscriber that throws (e.g. due to a bug in application code) leaves no trace.

**Required fix**:

```kotlin
private fun notifySubscriber(sub: Subscription, event: KnowledgeEvent) {
    try {
        sub.callback(event)
    } catch (e: Exception) {
        logger.error(
            "EventBus subscriber '${sub.id}' threw on event ${event::class.simpleName} " +
            "(predicate=${event.atom?.predicate}, tenant=${event.tenantId}): ${e.message}",
            e
        )
        sub.errorCount.incrementAndGet()
        if (sub.errorCount.get() >= MAX_SUBSCRIBER_ERRORS) {
            logger.warn("EventBus: subscriber '${sub.id}' exceeded error threshold ($MAX_SUBSCRIBER_ERRORS), auto-unsubscribing")
            unsubscribe(sub.id)
        }
    }
}
```

Add to `Subscription`:
- `errorCount: AtomicInteger = AtomicInteger(0)`
- `id: String` (already should exist for unsubscribe)
- `MAX_SUBSCRIBER_ERRORS = 5` (configurable)

**Tests required**:
- Subscriber throws: other subscribers still notified, error logged
- Subscriber exceeds error threshold: auto-unsubscribed, subsequent events not delivered
- Subscriber error count resets on successful delivery: NOT required (error count is monotonic — once a subscriber is flaky, it should stay suspect)
- Subscriber throws `OutOfMemoryError`: rethrown (only catch `Exception`, not `Throwable`)

---

## Phase 1B — Security Hardening {#phase-1b}

> Runs in parallel with Phase 1A. All items are blockers for external multi-tenant deployment.

### 1B-1: Uniform X-Tenant-ID Enforcement

**Current state**: Most endpoints call `requireTenantId(call)` and return 400 if missing. But `AdminRoutes` endpoints (`/admin/databases/{name}/facts`, `/admin/databases/{name}/rules`) silently pass a null `tenantId` to `getStore()`, which may return facts across all tenants or throw an NPE.

**Required fix**:

Extract a single helper:
```kotlin
fun ApplicationCall.requireTenantId(): String =
    request.headers["X-Tenant-ID"]?.takeIf { it.isNotBlank() }
        ?: throw ValidationException("X-Tenant-ID header is required")
```

Apply `requireTenantId()` uniformly in EVERY route handler that accesses tenant-scoped data. Create a Ktor plugin/interceptor that enforces this at the route group level rather than per-handler:

```kotlin
fun Route.requireTenant(block: Route.() -> Unit): Route {
    return apply {
        install(createRouteScopedPlugin("TenantRequired") {
            onCall { call ->
                call.requireTenantId()  // throws if missing, intercepted by error handler
            }
        })
        block()
    }
}
```

Wrap all `LogicRoutes`, `MemoryRoutes`, `ScopeRoutes`, `AggregateRoutes`, `TransactionRoutes`, `ExtractionRoutes`, `SynthesisRoutes` in `requireTenant { }`.

**Tests required**:
- Every tenant-scoped route: request without `X-Tenant-ID` returns 400 with clear message
- Request with blank `X-Tenant-ID` returns 400
- Admin routes with facts/rules listing: returns 400 without tenant header
- MCP routes (intentionally exempt with `default` fallback): verified to still use default tenant

---

### 1B-2: Input Sanitization — Scope, Database, Tenant Names

**Current state**: Scope names, database names, and tenant IDs are used as:
- Map keys (safe)
- File system paths: `File(rootStorageDir, databaseName)` — path traversal risk
- WAL file paths
- Log output

Only database names have a regex check (`validateDatabaseName()`). Scope names are only checked for blankness.

**Required implementation**:

Single validation function reused everywhere:
```kotlin
private val SAFE_NAME_REGEX = Regex("^[a-zA-Z0-9][a-zA-Z0-9_\\-\\.]{0,127}$")

fun validateResourceName(name: String, type: String) {
    if (name.isBlank()) throw ValidationException("$type cannot be blank")
    if (!SAFE_NAME_REGEX.matches(name)) throw ValidationException(
        "$type must match [a-zA-Z0-9][a-zA-Z0-9_\\-.]{0,127}, got: ${name.take(32)}"
    )
    // Reserved names
    val reserved = setOf("default", "admin", "system", "root", "..", ".")
    if (name.lowercase() in reserved) throw ValidationException("'$name' is a reserved $type name")
}
```

Apply to:
- Database names: already partially validated — replace with `validateResourceName(name, "database")`
- Tenant IDs: all `POST /admin/databases/{name}/tenants` bodies
- Scope names: `ForkScopeRequest.sourceScope`, `targetScope`; `MergeScopeRequest`; `SetScopeParentRequest`
- API key descriptions (50-char max, no control chars)

**Tests required**:
- Path traversal attempts: `../etc/passwd`, `../../secret`, `..` all return 400
- Control characters in names return 400
- Max length exceeded (129 chars) returns 400
- Reserved names return 400
- Valid names with hyphens, dots, underscores succeed
- Existing valid databases not affected by adding validation to creation

---

### 1B-3: Per-Key Rate Limiting on All Endpoints

**Current state**: No rate limiting on any endpoint except auth bootstrap (20 req/min) and failed auth (5 req/min). One tenant's key can issue unlimited requests, starving others.

**Required implementation**:

Use a token bucket per API key (not per IP — IPs aren't meaningful for agent workloads):

```kotlin
data class RateLimitConfig(
    val requestsPerMinute: Int,
    val burstSize: Int  // max tokens in bucket
)

val DEFAULT_LIMITS = mapOf(
    Role.ADMIN   to RateLimitConfig(requestsPerMinute = 600, burstSize = 100),
    Role.WRITER  to RateLimitConfig(requestsPerMinute = 300, burstSize = 60),
    Role.READER  to RateLimitConfig(requestsPerMinute = 120, burstSize = 30)
)
```

Implementation:
- `RateLimiter` class: `ConcurrentHashMap<String, TokenBucket>` keyed by API key ID
- `TokenBucket`: `AtomicLong tokens`, `AtomicLong lastRefill`, `tryConsume(): Boolean`
- Refill: on each call, add `(now - lastRefill) * ratePerMs` tokens, cap at `burstSize`
- If `tryConsume()` returns false: return HTTP 429 with headers:
  - `Retry-After: <seconds until next token>`
  - `X-RateLimit-Limit: <requestsPerMinute>`
  - `X-RateLimit-Remaining: 0`
  - `X-RateLimit-Reset: <epoch seconds>`
- Unauthenticated requests: still rate-limited by IP at 30 req/min (defense in depth)
- Disabled auth mode (`AUTH_ENABLED=false`): rate limiting disabled entirely

**Exemptions** (no rate limit):
- `GET /health`, `GET /health/live`, `GET /health/ready`
- `GET /metrics` (Prometheus scraper)

**Cleanup**: Buckets for inactive keys evicted after 10 minutes of inactivity (scheduled via coroutine).

**Tests required**:
- READER key: 121st request in a minute returns 429
- WRITER key: 301st request returns 429
- Rate limit resets after 1 minute (use fake clock)
- Burst: 60 WRITER requests in 1 second succeed (burst), 61st fails
- `Retry-After` header present and accurate on 429
- Health/metrics endpoints not rate-limited
- AUTH_ENABLED=false: no rate limiting applied
- Concurrent requests from same key: no race conditions in token bucket

---

### 1B-4: Audit Log

**Current state**: No record of security-sensitive operations exists. There is no way to answer: "Who created this key?", "Who deleted this tenant?", "When was the database nuked and by whom?"

**Required implementation**:

**AuditEvent data class**:
```kotlin
@Serializable
data class AuditEvent(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val actor: AuditActor,
    val action: AuditAction,
    val resource: AuditResource,
    val outcome: AuditOutcome,
    val details: Map<String, String> = emptyMap()
)

@Serializable
data class AuditActor(
    val keyId: String?,          // null for unauthenticated
    val role: String?,
    val tenantId: String?,
    val databaseId: String?,
    val ipAddress: String?       // X-Forwarded-For or remote host
)

enum class AuditAction {
    KEY_CREATED, KEY_REVOKED, KEY_UPDATED, KEY_DELETED,
    BOOTSTRAP_ATTEMPTED, BOOTSTRAP_SUCCEEDED,
    AUTH_FAILED, AUTH_SUCCEEDED,
    TENANT_CREATED, TENANT_DELETED,
    DATABASE_CREATED, DATABASE_DELETED,
    DATABASE_NUKED,
    FACT_BULK_DELETED,           // retract/pattern
    SCOPE_DELETED,
    BACKUP_CREATED
}

enum class AuditOutcome { SUCCESS, FAILURE, DENIED }

@Serializable
data class AuditResource(
    val type: String,            // "key", "tenant", "database", "scope"
    val id: String               // the resource identifier
)
```

**AuditLog storage**:
- Separate append-only JSON-lines file: `{storageDir}/audit.log`
- One `AuditEvent` per line, newline-delimited
- Rotated daily: `audit.2026-03-23.log`, current → `audit.log` symlink
- Never deleted automatically — retention is operator responsibility
- Encryption: if `ENCRYPTION_KEY` is set, each line AES-256-GCM encrypted (same as WAL)

**`AuditService`**:
```kotlin
class AuditService(storageDir: File, encryptionService: EncryptionService?) {
    fun record(event: AuditEvent)        // non-blocking, writes to buffered channel
    fun query(                            // for GET /auth/audit
        database: String? = null,
        tenantId: String? = null,
        action: AuditAction? = null,
        actorKeyId: String? = null,
        from: Long? = null,
        to: Long? = null,
        limit: Int = 100,
        offset: Int = 0
    ): List<AuditEvent>
}
```

Write path: coroutine-based async channel (capacity 1000). Background coroutine drains channel and writes to file. If channel full: log WARN "Audit log buffer full — dropping event" (never block the request).

**Instrumented callsites** (add `auditService.record(...)` calls):
- `AuthRoutes`: bootstrap, key creation, key update, key deletion, auth failure
- `AdminRoutes`: tenant creation, tenant deletion, database creation, database deletion, nuke
- `AggregateRoutes`: retract-by-pattern (bulk delete) with fact count in details
- `ScopeRoutes`: scope delete
- `ReplicationRoutes`: backup creation

**GET /auth/audit endpoint**:
```
GET /auth/audit
  Headers: X-Database, X-API-Key (ADMIN role required)
  Query params: ?tenantId=&action=&from=&to=&limit=100&offset=0
  Response: { events: AuditEvent[], total: Int, hasMore: Boolean }
```

**Tests required**:
- Bootstrap creates audit event (SUCCESS or FAILURE outcome)
- Key creation/deletion creates audit events
- Tenant deletion creates audit event
- Database nuke creates audit event with operator identity
- Auth failure creates event with correct IP
- `GET /auth/audit` filters by action, tenantId, time range
- Non-ADMIN key on `/auth/audit` returns 403
- Audit log buffer full: request completes (not blocked), WARN logged
- Encrypted audit log: events readable only with correct key

---

### 1B-5: Default Credential Warning

**Current state**: `NOCTURNUSAI_ADMIN_PASS` defaults to `"nocturnusai"`. Bootstrap completes silently with the default password. No warning is ever shown.

**Required fix**:

In `AuthRoutes.bootstrap()`:
```kotlin
val isDefaultPass = adminPass == "nocturnusai"
val isDefaultUser = adminUser == "admin"

if (isDefaultPass || isDefaultUser) {
    logger.warn("""
        ⚠️  SECURITY WARNING: NocturnusAI is bootstrapped with default credentials.
        Default admin user: '$adminUser', password: '${if (isDefaultPass) "[DEFAULT]" else "[custom]"}'
        Set NOCTURNUSAI_ADMIN_USER and NOCTURNUSAI_ADMIN_PASS environment variables
        to secure values before deploying to production.
    """.trimIndent())
}
```

Optionally: add `"defaultCredentials": true` field to the bootstrap response JSON so clients can surface this warning programmatically.

Also: add startup check in `Application.kt` module initialization — if auth is enabled and env vars are at defaults, log the same WARNING at server startup (not just at bootstrap time).

**Tests required**:
- Bootstrap with default password logs WARNING
- Bootstrap with custom password does not log WARNING
- Startup with AUTH_ENABLED=true and default creds logs WARNING
- Startup with AUTH_ENABLED=true and custom creds does not log WARNING

---

### 1B-6: Request Complexity Limits

**Current state**: A 10MB global body limit exists but does not prevent pathological requests: an `args` array with 50,000 entries or a rule with 1,000 body atoms will cause exponential unification time and potentially OOM.

**Required implementation**:

Add to `Validator`:
```kotlin
object ComplexityLimits {
    const val MAX_ARGS_LENGTH = 64
    const val MAX_RULE_BODY_LENGTH = 32
    const val MAX_PREDICATE_LENGTH = 256
    const val MAX_STRING_ARG_LENGTH = 4096
    const val MAX_METADATA_ENTRIES = 32
    const val MAX_METADATA_VALUE_LENGTH = 1024
    const val MAX_BULK_FACTS = 1000
    const val MAX_DSL_PROGRAM_STATEMENTS = 200
}

fun validateFactRequest(req: FactRequest) {
    if (req.args.size > MAX_ARGS_LENGTH)
        throw ValidationException("args exceeds max length ${MAX_ARGS_LENGTH}, got ${req.args.size}")
    req.args.forEach { arg ->
        if (arg.length > MAX_STRING_ARG_LENGTH)
            throw ValidationException("arg value too long (max $MAX_STRING_ARG_LENGTH chars)")
    }
    if (req.predicate.length > MAX_PREDICATE_LENGTH)
        throw ValidationException("predicate name too long")
    req.metadata?.let { meta ->
        if (meta.size > MAX_METADATA_ENTRIES)
            throw ValidationException("metadata exceeds max entries $MAX_METADATA_ENTRIES")
        meta.values.forEach { v ->
            if (v.length > MAX_METADATA_VALUE_LENGTH)
                throw ValidationException("metadata value too long")
        }
    }
}

fun validateRuleRequest(req: RuleRequest) {
    // Validate head atom first
    if (req.head.predicate.length > MAX_PREDICATE_LENGTH)
        throw ValidationException("rule head predicate too long")
    if (req.head.args.size > MAX_ARGS_LENGTH)
        throw ValidationException("rule head args exceeds max length $MAX_ARGS_LENGTH")
    req.head.args.forEach { arg ->
        if (arg.length > MAX_STRING_ARG_LENGTH)
            throw ValidationException("rule head arg value too long")
    }

    // Validate body
    if (req.body.size > MAX_RULE_BODY_LENGTH)
        throw ValidationException("rule body exceeds max length $MAX_RULE_BODY_LENGTH, got ${req.body.size}")
    // RuleRequest.body is List<AtomRequest> where AtomRequest has the same shape as FactRequest
    // (predicate: String, args: List<String>, metadata: Map<String,String>?).
    req.body.forEach { bodyAtom ->
        if (bodyAtom.predicate.length > MAX_PREDICATE_LENGTH)
            throw ValidationException("rule body atom predicate too long")
        if (bodyAtom.args.size > MAX_ARGS_LENGTH)
            throw ValidationException("rule body atom args exceeds max length $MAX_ARGS_LENGTH")
        bodyAtom.args.forEach { arg ->
            if (arg.length > MAX_STRING_ARG_LENGTH)
                throw ValidationException("rule body atom arg value too long")
        }
        bodyAtom.metadata?.let { meta ->
            if (meta.size > MAX_METADATA_ENTRIES)
                throw ValidationException("rule body atom metadata exceeds max entries")
            meta.values.forEach { v ->
                if (v.length > MAX_METADATA_VALUE_LENGTH)
                    throw ValidationException("rule body atom metadata value too long")
            }
        }
    }
}
```

Apply validations in all routes before passing to the engine. All limits configurable via environment variables with documented defaults.

**Tests required**:
- `args` array with 65 entries returns 400
- Rule body with 33 atoms returns 400
- Predicate name 257 chars returns 400
- String arg 4097 chars returns 400
- 1001 facts in bulk assert returns 400
- All valid at exactly the limit (boundary conditions)
- Limits configurable via env vars

---

### 1B-7: AuthInterceptor Permission Map — O(1) Lookup

**Current state**: `AuthInterceptor.PERMISSION_MAP` is a `List<Pair<RouteMatch, Permission?>>` checked sequentially for every request. O(n) per request. `/predicates` has no entry and passes with null permission (no access control).

**Required fix**:

Replace with a two-level structure:
1. **Exact match map**: `Map<String, Map<HttpMethod, Permission>>` for O(1) on known routes
2. **Prefix/pattern list**: Only for dynamic path segments (`/admin/databases/{name}/...`), checked second
3. **Default deny**: any route not in either map returns 403

```kotlin
private val EXACT_PERMISSIONS: Map<Pair<HttpMethod, String>, Permission> = mapOf(
    (HttpMethod.Post to "/tell") to Permission.FACT_WRITE,
    (HttpMethod.Post to "/ask") to Permission.INFERENCE,
    (HttpMethod.Get to "/predicates") to Permission.FACT_READ,   // ← add missing entry
    // ... all routes explicitly mapped
)

private val PATTERN_PERMISSIONS: List<Triple<HttpMethod, Regex, Permission>> = listOf(
    Triple(HttpMethod.Get, Regex("/admin/databases/[^/]+/facts"), Permission.FACT_READ),
    Triple(HttpMethod.Get, Regex("/admin/databases/[^/]+/rules"), Permission.FACT_READ),
    Triple(HttpMethod.Post, Regex("/admin/databases/[^/]+/tenants"), Permission.TENANT_MANAGE),
    // ...
)
```

Every route must have an explicit entry. Add a startup assertion that verifies all known routes are in the permission map (fail-fast on misconfiguration).

**Tests required**:
- `/predicates` without auth in RBAC mode returns 401/403
- `/predicates` with READER key returns 200
- Unknown route returns 403 (default deny)
- All existing permission tests still pass
- Startup check: remove a route from map, verify startup throws

---

### 1B-8: Key Expiry Enforcement at Authentication Time

**Current state**: API keys have an `expiresAt` field. `ApiKeyManager` tracks it. But `AuthInterceptor.authenticate()` does not check `isExpired()` — an expired key may still authenticate depending on the code path.

**Required fix**:

In `AuthInterceptor.authenticate()`, after finding the key by hash:
```kotlin
if (key.isExpired()) {
    auditService.record(AuditEvent(
        actor = AuditActor(keyId = key.id, role = key.role.name, ...),
        action = AuditAction.AUTH_FAILED,
        resource = AuditResource("key", key.id),
        outcome = AuditOutcome.DENIED,
        details = mapOf("reason" to "key_expired", "expiredAt" to key.expiresAt.toString())
    ))
    return AuthResult.Expired
}
```

`ApiKey.isExpired()`:
```kotlin
fun isExpired(): Boolean = expiresAt != null && System.currentTimeMillis() > expiresAt
```

Return 401 with body `{"code": "KEY_EXPIRED", "message": "API key has expired"}`.

**Tests required**:
- Expired key returns 401 with KEY_EXPIRED code
- Non-expired key (future expiry) returns 200
- Key with null expiry never expires
- Expired key creates audit event
- Key expiring in 1ms still works (boundary: check is `>`, not `>=`)

---

## Phase 2A — Implementation Depth {#phase-2a}

> These upgrade prototype-level implementations to production-grade. Phase 1 must be complete first.

### 2A-1: Multi-Justification Truth Maintenance System

**Current state**: `ProvenanceTracker` stores a single `Derivation` per derived fact. If that derivation's premise is retracted, the derived fact is immediately retracted — even if another rule could independently re-derive it. This violates well-founded semantics.

**Required implementation**:

```kotlin
// Before (single justification)
private val derivations: MutableMap<AtomKey, Derivation>

// After (multiple justifications)
private val justifications: ConcurrentHashMap<AtomKey, CopyOnWriteArraySet<Derivation>>
```

**Retraction algorithm** (ATMS-inspired, simplified):
1. `retract(premise)`:
   a. Remove premise from store.
   b. Find all derived facts that have `premise` in any of their justifications.
   c. For each such derived fact:
      - Remove the invalidated justification from its set.
      - If the justification set is now empty → the fact has no support → retract it (recurse).
      - If the justification set is non-empty → the fact is still supported by another proof → keep it.
2. `record(derived, premises)`:
   a. Look up or create `justifications[derived]`.
   b. Add new `Derivation(premises)` to the set.
   c. If the derived fact is not in the store, assert it.

**Consistency invariant**: A derived fact is in the store if and only if `justifications[derived]` is non-empty.

**Tests required**:
- Two rules both derive `mortal(socrates)`. Retract one premise. Fact persists (still supported by rule 2).
- Retract second premise. Fact is now retracted.
- Chain: A derives B, B derives C. Two paths to A. Retract one path to A → C persists. Retract second path → C retracted.
- Assert a derived fact manually. It persists even if all rule-based justifications removed (manual assertion = its own justification).
- No duplicate derivations stored (idempotent `record()`)
- Concurrent retractions: 50 threads retracting simultaneously, no orphaned facts

---

### 2A-2: Rete Engine — True Beta Memories

**Current state**: The Rete engine in `ReteEngine.kt` is "alpha-net only": it re-evaluates all rule body conditions from scratch on every fact assertion. This is O(rules × body_length) per fact assertion. True Rete stores partial matches in beta nodes, enabling O(1) amortized incremental updates.

**Required implementation**:

Full Rete network structure:

```
fact assertion
    ↓
Alpha Network
    ├── AlphaNode(predicate="human") → AlphaMemory[{human(socrates), human(plato), ...}]
    ├── AlphaNode(predicate="mortal") → AlphaMemory[...]
    └── ...
    ↓
Beta Network (one BetaNode per rule body)
    ├── BetaNode(rule R1)
    │     ├── LeftInput: AlphaMemory for body[0]
    │     ├── RightInput: AlphaMemory for body[1]
    │     ├── JoinMemory: Set<PartialMatch>  ← "beta memory"
    │     └── on new match → assert head
    └── ...
```

**Key data structures**:

```kotlin
// Stored in beta memory
data class PartialMatch(
    val boundVariables: Substitution,
    val matchedFacts: List<Atom>,    // one per body condition satisfied so far
    val confidence: Double?
)

class BetaNode(val rule: Rule) {
    val partialMatches: CopyOnWriteArrayList<PartialMatch> = CopyOnWriteArrayList()

    // Called when alpha memory for body[i] receives a new fact
    fun onNewFact(fact: Atom, bodyIndex: Int, store: Hexastore): List<Atom>  // returns newly derivable heads

    // Called when a fact is retracted (must remove partial matches containing it)
    fun onRetractFact(fact: Atom): List<Atom>  // returns heads that lost their support
}
```

**Incremental update algorithm**:
1. New fact `F` asserted with predicate `P`.
2. Find all rules where `P` appears at body position `i`.
3. For each such rule's `BetaNode`:
   - If `i == 0`: try to extend with each existing partial match (empty match for the first condition).
   - If `i > 0`: try to join `F` with each `PartialMatch` in `betaNode.partialMatches` where conditions `0..i-1` are satisfied.
   - For each successful join: create a new `PartialMatch` or promote to a complete match (assert head).
4. Fact `F` retracted:
   - Find all `PartialMatch` objects referencing `F` across all beta nodes.
   - Remove them. If a complete match was invalidated, retract the derived head via `ProvenanceTracker`.

**Integration with NAF** (from 1A-3):
- NAF conditions are checked at complete-match time (when all positive conditions are satisfied).
- If a NAF-blocking fact arrives later, re-evaluate complete matches per 1A-3.

**Tests required**:
- Forward chain derives correct facts (same results as current shallow Rete)
- Retraction cascades: derived fact retracted when supporting partial match invalidated
- Join across 3 conditions: partial matches built incrementally
- No redundant derivations: asserting same fact twice doesn't duplicate derived facts
- Performance: 10,000 facts × 100 rules — forward chaining completes in < 1 second
- Rule added after facts: retrospective triggering (fire new rule against existing partial matches in store)
- Memory: beta memories don't grow unboundedly on full-scan queries

---

### 2A-3: Memory Consolidation and Decay — Full Implementation

**Current state**: `MemoryManager` tracks `predicateAccessCounts` and has `consolidate()` and `decay()` method signatures, but both are stubs. Agent memory lifecycle management (the primary value proposition) is not functional.

**Required implementation**:

**Consolidation** (`consolidate()`):
Goal: compress repeated episodic facts into fewer semantic facts.

Algorithm:
1. Group all atoms by `(predicate, args)` ignoring temporal fields.
2. For groups with count ≥ `minOccurrences` (default: 3):
   - Compute aggregate confidence: `mean(confidences)` or max (configurable).
   - Create a single semantic atom with:
     - Same predicate + args
     - `confidence` = aggregated
     - `validFrom` = min(episodic `createdAt`)
     - `validUntil` = max(episodic `validUntil`) (or null if any had null)
     - `source` = "consolidated"
     - `metadata` = `{"consolidatedFrom": count.toString(), "strategy": strategy}`
   - Retract all episodic atoms.
   - Assert the consolidated atom.
3. Return `ConsolidationResult(consolidatedCount, episodicRetractedCount, affectedPredicates)`.

```kotlin
data class ConsolidationConfig(
    val minOccurrences: Int = 3,
    val confidenceAggregation: ConsolidationStrategy = ConsolidationStrategy.MEAN,
    val scope: String? = null   // null = all scopes
)
enum class ConsolidationStrategy { MEAN, MAX, MIN, WEIGHTED_RECENCY }
```

**Decay** (`decay()`):
Goal: evict low-value facts to control memory growth.

Algorithm:
1. For each fact in the store:
   a. If `fact.isExpired()` → retract immediately (TTL/validUntil expired).
   b. Compute current salience via `SalienceTracker`.
   c. If salience < `minSalienceThreshold` AND `fact.source != "user"` (don't evict user-asserted facts) → retract.
2. Return `DecayResult(expiredCount, lowSalienceCount, totalRetracted)`.

```kotlin
data class DecayConfig(
    val minSalienceThreshold: Double = 0.1,
    val protectSources: Set<String> = setOf("user", "consolidated"),
    val scope: String? = null,
    val dryRun: Boolean = false  // report what would be evicted without evicting
)
```

**Auto-decay**: Configurable via `MEMORY_DECAY_INTERVAL_MS` env var (default: disabled). If set, run decay on a background coroutine at that interval.

**Tests required**:
- Consolidation: 5 episodic `likes(alice, bob)` facts → 1 semantic fact, 5 retracted
- Consolidation: group below threshold (2 occurrences) not consolidated
- Decay: expired atoms (past `validUntil`) retracted
- Decay: low-salience atoms retracted (simulate time passing to reduce salience)
- Decay: user-sourced atoms NOT retracted even with low salience
- Dry run: returns what would be evicted without actually evicting
- Auto-decay: runs at configured interval, verifiable with test clock
- Consolidation followed by decay: consolidated facts have higher salience → survive decay

---

### 2A-4: Memoization Correctness in BackwardChainer

**Current state**: The `memo` map in `BackwardChainer` caches solutions at the atom level. This can cause incorrect results when the same atom is queried with different substitution contexts, or when the memo is not cleared between top-level queries. Under some rule configurations this produces stale cached results.

**Required fix**:

The memo should be scoped per top-level query call, not shared across calls:

```kotlin
fun solve(query: Atom, scope: String? = null): Sequence<Atom> {
    val memo = HashMap<Atom, List<Pair<Substitution, Double?>>>()  // fresh per call
    return solveRecursiveWithConfidence(
        goals = listOf(query),
        substitution = emptySubstitution(),
        depth = 0,
        memo = memo,
        scope = scope
    ).map { (subst, conf) ->
        query.applySubstitution(subst).copy(confidence = conf)
    }.distinct()
}
```

Additionally, memoize at the `(goal, substitution_of_variables_in_goal)` level, not just the raw goal atom:

```kotlin
data class MemoKey(val atom: Atom, val activeBindings: Map<String, Term>)
```

This ensures two calls to `solve(age(?x))` with `?x=alice` and `?x=bob` have separate memo entries.

**Tests required**:
- Same query called twice: second call returns same results (memoization works)
- Two different queries in same session: no cross-contamination
- Query with variable bound to different values: independent memo entries
- Memoization under recursive rules: no infinite loop, correct results
- `solveWithProof()` with memoization: proof trees correct (not returning stale proofs from cache)

---

### 2A-5: Confidence Propagation Through Rules

**Current state**: `BackwardChainer.kt` line 38-39 marks confidence aggregation "out of scope for base engine." When a rule fires, derived facts have `confidence=null` or inherit arbitrarily.

**Required implementation**:

```kotlin
enum class ConfidenceAggregation { MIN, PRODUCT, WEIGHTED_MEAN, INHERIT_HEAD }

data class ConfidencePropagationConfig(
    val ruleBodyAggregation: ConfidenceAggregation = ConfidenceAggregation.MIN,
    val headMultiplier: Double = 1.0  // rule "certainty factor"
)
```

Aggregation logic in `solveRecursiveWithConfidence()`:
- For each body condition resolved, collect its confidence.
- `MIN`: take the minimum of all body atom confidences.
- `PRODUCT`: multiply all body confidences.
- `WEIGHTED_MEAN`: mean weighted by (1/depth) for each condition.
- Apply `rule.confidence` as `headMultiplier` (default 1.0 if null).
- Derived atom confidence = `bodyAggregated * headMultiplier`.
- If any body atom has `confidence=null`: treat as 1.0 (unknown = full confidence).

Rule confidence declaration:
```kotlin
// In Rule.kt — add optional confidence field (already has it, just needs wiring)
data class Rule(
    val variables: List<String>,
    val head: Atom,
    val body: List<Atom>,
    val scope: String? = null,
    val confidence: Double? = null   // the rule's own certainty factor
)
```

**Tests required**:
- Rule with no body confidence: derived atom confidence = 1.0
- Rule with body atom confidence 0.8: derived confidence = 0.8 (MIN)
- Rule body with two atoms 0.8 and 0.6: MIN = 0.6, PRODUCT = 0.48
- Rule with explicit confidence 0.9 and body confidence 0.8: derived = 0.72 (PRODUCT + multiplier)
- `minConfidence` filter on infer endpoint works with propagated confidences
- Confidence propagated through multi-step chain correctly

---

### 2A-6: Rule Prioritization

**Current state**: When multiple rules can derive the same head, Rete firing order is undefined. Backward chaining tries rules in insertion order. No mechanism to prefer one rule over another.

**Required implementation**:

Add `priority: Int` to `Rule` (default 0, higher = fires first):
```kotlin
data class Rule(
    // existing fields...
    val priority: Int = 0
)
```

**Backward chaining**: Sort `rulesByPredicate[predicate]` by `priority` descending when selecting rules to try. First rule to produce a solution wins (for deterministic `solve()` calls).

**Rete**: Sort rules in `BetaNode` construction order by priority descending. When multiple beta nodes produce derivations for the same head, higher-priority rule's derivation is preferred (for CONFIDENCE conflict strategy).

**API**: Expose `priority` in `RuleRequest` and `RuleResponse` DTOs.

**Tests required**:
- Two rules derive same head with different args: both fire (no conflict)
- Two rules derive same head with same args: higher priority rule's confidence used when `CONFIDENCE` conflict strategy
- Priority 0 is default: existing behavior unchanged
- Backward chaining: higher priority rule tried first, its result returned before lower priority
- Priority negative: valid, fires last
- Priority in RuleRequest/RuleResponse round-trips correctly

---

## Phase 2B — Observability & Testing {#phase-2b}

### 2B-1: OpenTelemetry Distributed Tracing

**Required implementation**:

Add dependencies to `nocturnusai-server/build.gradle.kts`:
```kotlin
implementation("io.opentelemetry:opentelemetry-sdk:1.34.0")
implementation("io.opentelemetry:opentelemetry-exporter-otlp:1.34.0")
implementation("io.opentelemetry:opentelemetry-sdk-extension-autoconfigure:1.34.0")
implementation("io.opentelemetry.instrumentation:opentelemetry-ktor-2.0:1.34.0-alpha")
```

**Trace structure**:
```
HTTP Request span (auto-instrumented by Ktor plugin)
  └── "nocturnusai.inference" span
        ├── span.attribute("db.name", databaseName)
        ├── span.attribute("tenant.id", tenantId)
        ├── span.attribute("query.predicate", predicate)
        └── "nocturnusai.hexastore.query" span (child)
              ├── span.attribute("hexastore.index", "SPO")
              └── span.attribute("hexastore.results", count)
```

**Instrumented operations**:
- Every HTTP request: method, path, status, duration (via Ktor OTel plugin)
- `BackwardChainer.solve()`: span per top-level query with predicate + tenant
- `Hexastore.match()`: span per storage query with index used + result count
- `ReteEngine.onFactAsserted()`: span per forward chain trigger
- `LLM calls` (extraction/synthesis): span with model name, token count, latency

**Configuration**:
- `OTEL_EXPORTER_OTLP_ENDPOINT` env var (e.g. Jaeger or Grafana Tempo URL)
- `OTEL_SERVICE_NAME` (default: "nocturnusai")
- If `OTEL_EXPORTER_OTLP_ENDPOINT` not set: use no-op exporter (zero overhead)

**Tests required**:
- Inference request produces spans with correct attributes
- Parent-child span relationship correct (HTTP request → inference → storage)
- No-op exporter when env var not set: zero observable behavior change
- Span error recorded when inference throws exception

---

### 2B-2: Per-Tenant Metrics

**Required implementation**:

Add `tenantId` and `databaseId` tags to all existing counters and timers:

```kotlin
// Before
Metrics.factsAsserted.increment()

// After
Metrics.factsAsserted
    .tag("database", databaseId)
    .tag("tenant", tenantId)
    .increment()
```

Add new gauges:
```kotlin
// Per-tenant fact/rule counts (updated on assert/retract)
Gauge.builder("nocturnusai.facts.total") {
    dbManager.getAllDatabases().sumOf { db ->
        db.getAllTenants().sumOf { tenant -> db.getContext(tenant).store.size() }
    }
}.register(registry)

// Per-database tenant count
Gauge.builder("nocturnusai.tenants.total") { dbManager.totalTenantCount() }.register(registry)
```

**Tests required**:
- Fact assertion increments counter with correct database+tenant tags
- Counter tags queryable via Prometheus label selectors
- Tenant deletion removes associated counters (avoid stale series)

---

### 2B-3: Fix Replication Lag Metric

**Required fix**:

In `ReplicationClient` (follower mode), after each WAL batch is applied:
```kotlin
val lastWalEntryTimestamp: Long = batch.entries.maxOf { it.timestamp }
Metrics.replicationLag.set(System.currentTimeMillis() - lastWalEntryTimestamp)
```

Add to `/metrics` output documentation:
```
# HELP nocturnusai_replication_lag_ms Milliseconds behind leader WAL (follower mode only)
# TYPE nocturnusai_replication_lag_ms gauge
```

When not in follower mode: keep metric at 0 (not -1 or absent).

---

### 2B-4: JVM Metrics

Add to `Application.kt` module initialization:
```kotlin
ClassLoaderMetrics().bindTo(Metrics.registry)
JvmMemoryMetrics().bindTo(Metrics.registry)
JvmGcMetrics().bindTo(Metrics.registry)
JvmThreadMetrics().bindTo(Metrics.registry)
ProcessorMetrics().bindTo(Metrics.registry)
```

**Tests required**:
- `/metrics` endpoint includes `jvm_memory_used_bytes` metric
- `/metrics` endpoint includes `jvm_gc_pause_seconds` metric

---

### 2B-5: Python Client Test Suite

**Required tests** for `nocturnusai/client.py`:

File: `sdks/python/tests/test_client.py`

```python
# Configuration
test: NocturnusAIClient respects baseUrl trailing slash normalization
test: NocturnusAIClient sets X-Database header
test: NocturnusAIClient sets X-Tenant-ID header
test: NocturnusAIClient sets X-API-Key header when provided

# Fact operations (mocked httpx responses)
test: tell() sends POST /tell with correct body
test: ask() sends POST /ask with query predicate
test: query() sends POST /query (no inference)
test: forget() sends POST /forget
test: bulkAssert() sends POST /assert/facts
test: retractPattern() sends POST /retract/pattern

# Inference
test: ask() with withProof=True includes proof tree in response
test: infer() returns list of Atom objects

# Memory
test: temporalQuery() sends correct epoch timestamp
test: salientQuery() filters by minSalience
test: buildContextWindow() uses correct maxFacts
test: consolidate() returns ConsolidationResult
test: decay() returns DecayResult

# Transactions
test: beginTransaction() returns txId
test: commitTransaction() sends correct txId
test: rollbackTransaction() sends correct txId

# Error handling
test: 400 raises NocturnusAIValidationError
test: 401 raises NocturnusAIAPIError with AUTH_REQUIRED code
test: 404 raises NocturnusAINotFoundError
test: 409 raises NocturnusAIConflictError
test: 429 raises NocturnusAIAPIError with RATE_LIMITED code
test: connection refused raises NocturnusAIConnectionError
test: timeout raises NocturnusAITimeoutError

# Retry logic
test: 503 retried up to maxRetries times
test: 400 NOT retried
test: retry respects exponential backoff timing
test: retry uses jitter (no two retries at exactly the same interval)

# Sync wrapper
test: SyncNocturnusAIClient.tell() equivalent to async tell()
test: SyncNocturnusAIClient runs event loop correctly
```

---

### 2B-6: Extraction/Synthesis Tests

File: `nocturnusai-server/src/test/kotlin/com/nocturnusai/server/ExtractionRoutesTest.kt`

Required tests:
- `POST /extract` with mocked LLM: returns ExtractedFacts + ExtractedRules
- `POST /extract` with LLM unavailable: returns 503 with clear message
- `POST /extract/batch`: multiple texts, all facts extracted
- `POST /synthesize`: returns natural language answer from facts
- Confidence scores in extracted facts are within 0.0–1.0 range
- `EXTRACTION_ENABLED=false`: returns 404 (not 500)
- Empty text input: returns 400
- Text too long (> configured max): returns 400

---

### 2B-7: TTL / Expiration Tests

File: `nocturnusai-core/src/test/kotlin/com/nocturnusai/TemporalExpirationTest.kt`

Required tests:
- `Atom.isExpired()` with `ttl=1000ms`: returns false at t=999ms, true at t=1001ms
- `Atom.isExpired()` with `validUntil` in the past: returns true
- `Atom.isExpired()` with both `ttl` and `validUntil`: earlier one wins
- `Atom.isExpired()` with neither: always false
- Expired atoms excluded from `BackwardChainer.solve()` results
- Expired atoms excluded from `Hexastore.match()` temporal queries
- Expired atoms NOT automatically retracted (expiration is query-time filter, not deletion)
- `MemoryManager.decay()` DOES retract expired atoms when explicitly called
- Server: `POST /ask` with expired fact: not returned
- Server: `POST /memory/query/temporal` with `timestamp` in past: returns facts valid at that time

---

### 2B-8: Inference Timeout and Cancellation

**Required implementation**:

**Scope decision**: This timeout covers the **read path only** — `BackwardChainer.solve()` and `solveWithProof()` invoked via `/ask`, `/infer`, `/query`, `/batch-ask`, `/execute`. The **write path** (`ReteEngine.onFactAsserted()` triggered by `/tell`, `/assert/fact`) is **excluded from this spec item**. Forward chaining on write is bounded by `MAX_RULE_BODY_LENGTH` (1B-6) and the Rete depth limit (existing 100-step cap), which is considered sufficient for Phase 2B. Write-path timeout is a separate, future work item.

Add `queryTimeoutMs: Long = 30_000` to `BackwardChainer` constructor.

Wrap top-level solve call in Ktor coroutine context:
```kotlin
suspend fun solveAsync(
    query: Atom,
    scope: String? = null,
    timeoutMs: Long = queryTimeoutMs
): List<Atom> = withTimeout(timeoutMs) {
    solve(query, scope).toList()
}
```

In routes, use `solveAsync()` instead of `solve()` for all read-path endpoints. On `TimeoutCancellationException`:
- Return HTTP 408 with body: `{"code": "QUERY_TIMEOUT", "message": "Query exceeded ${timeoutMs}ms", "partialResults": []}`
- Log at WARN with query predicate and tenant ID

Configuration: `QUERY_TIMEOUT_MS` env var (default: 30000, `0` = disabled/no timeout).

**Tests required**:
- Query completing in < timeout: returns results normally
- Query exceeding timeout: returns 408 with QUERY_TIMEOUT code
- `QUERY_TIMEOUT_MS=0`: no timeout applied, long queries complete
- Write path (`/tell`) not affected by query timeout (asserts complete regardless of query timeout setting)
- Concurrent queries: one timing out does not affect others

---

## Phase 3 — Feature Expansion {#phase-3}

> All Phase 1 and Phase 2 items must be complete before starting Phase 3.

### 3-1: PATCH /tell — Idempotent Fact Update

**Endpoint**:
```
PATCH /tell
Headers: X-Database, X-Tenant-ID, X-API-Key
Body: {
  "predicate": "string",
  "args": ["string"],
  "scope": "string?",
  "confidence": 0.9,       // only fields present are updated
  "ttl": 3600000,
  "validUntil": 1234567890,
  "metadata": { "key": "value" }
}
Response: 200 { ...updated atom }
         404 if fact not found
```

**Semantics**: Find the unique atom by `(predicate, args, scope, tenantId)`. Update only the fields present in the request body. Do not retract and re-assert (this would break provenance). Preserve `createdAt`, `source`, existing justifications.

**Implementation**: Add `updateFact(atom: Atom, updates: AtomUpdates, tenantId: String): Atom` to `NocturnusAI`. In `Hexastore`, add `update(key: AtomKey, updates: AtomUpdates)` that finds the atom in the relevant indexes and replaces it in-place (no index restructure needed since predicate and args don't change in an update — they're the lookup key).

**Tests required**:
- Update confidence: returns updated confidence, fact still in store
- Update TTL: new TTL applied to existing atom
- Update metadata: merges with existing (not replaces)
- Update non-existent fact: returns 404
- Update predicate/args field: returns 400 (immutable fields)
- Concurrent updates: last-write-wins (no lock needed since we're updating non-key fields)

---

### 3-2: POST /batch-ask — Bulk Query

**Endpoint**:
```
POST /batch-ask
Headers: X-Database, X-Tenant-ID, X-API-Key
Body: {
  "queries": [
    { "predicate": "string", "args": ["string", "?x"], "scope": "string?", "withProof": false },
    ...
  ],
  "maxQueries": 50
}
Response: {
  "results": [
    { "query": { ...original query atom }, "atoms": [...], "durationMs": 42 },
    ...
  ],
  "totalDurationMs": 150
}
```

**Semantics**: Execute all queries atomically within a snapshot of the store (consistent read). All queries observe the same state. Queries are independent (not a conjunction). Results are ordered to match input queries.

**Implementation**: Take a read lock on `Hexastore` for the duration of the batch. Execute each query sequentially (not in parallel — avoids thundering herd). Return partial results if one query times out (mark that result with `"timedOut": true`).

**Tests required**:
- Single query in batch: equivalent to `/ask`
- Multiple queries: all return correct results
- One query times out: others still return, timed-out has `"timedOut": true`
- Empty queries array: returns 400
- Over `maxQueries` limit: returns 400
- Queries observe same store state (consistency test: retract a fact mid-batch — retracted fact still visible if retraction happens after batch starts)

---

### 3-3: GET /auth/audit — Audit Log Query

> **Note**: The `GET /auth/audit` endpoint is fully specified in [1B-4](#1b-4-audit-log) (endpoint shape, filters, pagination, `AuditService.query()` implementation, and test requirements). It is listed here as a Phase 3 unlock because it depends on the `AuditService` built in Phase 1B being stable and populated with real events before the query endpoint is meaningfully testable end-to-end. No additional specification is required — implement exactly as described in 1B-4.

---

### 3-4: GET /admin/databases/{name}/stats

**Endpoint**:
```
GET /admin/databases/{name}/stats
Headers: X-API-Key (ADMIN required)
Response: {
  "database": "name",
  "tenants": [
    {
      "tenantId": "string",
      "factCount": 1234,
      "ruleCount": 56,
      "scopeCount": 7,
      "derivedFactCount": 200,
      "oldestFact": 1234567890,
      "newestFact": 1234567890
    }
  ],
  "totalFactCount": 5678,
  "totalRuleCount": 234,
  "activeTransactionCount": 3,
  "walSizeBytes": 1048576,
  "lastSnapshotTime": 1234567890,
  "snapshotSizeBytes": 5242880,
  "uptime": 86400000
}
```

**Tests required**:
- Returns correct fact/rule counts after assertions
- Counts decrease after retraction
- Active transaction count reflects open transactions
- Non-existent database: 404
- Non-ADMIN key: 403

---

### 3-5: GDPR Right-to-Forget

**Endpoint**:
```
POST /admin/databases/{name}/tenants/{tenantId}/purge
Headers: X-API-Key (ADMIN required)
Body: {
  "identifier": "alice@example.com",     // value to search for in any arg position
  "predicates": ["user", "email", "..."] // optional: restrict to these predicates
  "dryRun": false
}
Response: {
  "purgedFacts": 47,
  "affectedPredicates": ["email", "user", "session"],
  "dryRun": false
}
```

**Semantics**: Scan all atoms in the tenant's store. For each atom where `identifier` appears in any `args` value (exact match), retract it (including cascading via ProvenanceTracker). Log a `FACT_BULK_DELETED` audit event with count. `dryRun=true` returns what would be deleted without deleting.

**Tests required**:
- Purge by identifier: all matching atoms retracted
- Predicate filter: only specified predicates checked
- Dry run: returns correct count, no actual retraction
- Audit event created with correct count
- No matching atoms: 200 with count 0 (not 404)
- Non-ADMIN key: 403
- Cascading retraction: derived facts using purged atoms also retracted

---

### 3-6: SSE Event Streaming in Python SDK

**Required implementation** in `sdks/python/nocturnusai/client.py`:

```python
async def stream_events(
    self,
    predicates: list[str] | None = None,
    event_types: list[str] | None = None,
    tenant_id: str | None = None
) -> AsyncGenerator[KnowledgeEvent, None]:
    """Subscribe to real-time knowledge change events via SSE."""
    params = {}
    if predicates:
        params["predicates"] = ",".join(predicates)
    if event_types:
        params["types"] = ",".join(event_types)

    headers = self._build_headers(tenant_id=tenant_id)
    async with self._client.stream("GET", "/memory/events",
                                    params=params, headers=headers) as response:
        response.raise_for_status()
        async for line in response.aiter_lines():
            if line.startswith("data:"):
                data = json.loads(line[5:].strip())
                yield KnowledgeEvent(**data)
```

Also add `stream_mcp_events()` wrapping `GET /mcp/sse`.

**Tests required**:
- `stream_events()` yields KnowledgeEvent objects
- Predicate filter passed as query param
- Connection closed when async generator garbage-collected
- Reconnection on server disconnect (optional with backoff)
- `KnowledgeEvent` fields correctly deserialized

---

### 3-7: Schema Discovery Endpoint

**Endpoint**:
```
GET /schema
Headers: X-Database, X-Tenant-ID, X-API-Key (READER+)
Query: ?scope=optional
Response: {
  "predicates": [
    {
      "name": "likes",
      "arity": 2,
      "factCount": 1234,
      "ruleCount": 2,
      "argSamples": [["alice", "bob"], ["alice", "carol"]],
      "firstSeen": 1234567890,
      "lastSeen": 1234567890,
      "averageConfidence": 0.87
    }
  ],
  "totalPredicates": 15,
  "totalFacts": 5678
}
```

**Implementation**: Scan all atoms in the store, group by predicate. Compute arity from first occurrence of each predicate. Take up to 3 sample arg-lists per predicate. Compute stats (count, confidence mean, first/last seen from `createdAt`). Cache result for 30 seconds (or invalidate on assertion/retraction).

**Tests required**:
- Returns correct predicate list after assertions
- `argSamples` limited to 3 per predicate
- Empty store: returns empty list (not 404)
- Scope filter: returns only predicates with facts in that scope
- Cache: second call within 30s returns same result (cache hit logged)
- READER key: 200; unauthenticated in RBAC mode: 401

---

## Cross-Cutting Concerns {#cross-cutting}

### replace `println()` with Logging
`DatabaseManager.kt` uses `println()` for errors at lines 138, 169. Replace with `logger.error()`. Apply project-wide: grep for `println()` in main sources and replace with appropriate SLF4J level.

### Remove Stale `isMultiTenant` Parameter
`DatabaseManager.loadDatabase()` accepts `isMultiTenant: Boolean` but always forces `true` (comment says "Always force multi-tenant"). Remove the parameter entirely. All databases are multi-tenant.

### Consolidate SSE Event Serialization
`MemoryRoutes.serializeEvent()` and `SimplifiedRoutes.serializeSimplifiedEvent()` are nearly identical. Extract to a single `EventSerializer` utility class.

### Backwards-Compatible WAL Migration
The `DatabaseManager.kt` contains a large commented block (lines 54-71) questioning whether to support legacy root-level WAL files. Decision: on startup, if `data/default.wal` exists at root level and `data/default/` directory does not exist, automatically migrate: create `data/default/`, move `default.wal` → `data/default/default.wal`, log WARN "Migrated legacy WAL to new directory structure." This resolves the unresolved TODO.

---

## Acceptance Criteria {#acceptance-criteria}

### Phase 1A — Done When:
- [ ] All 764 existing tests pass
- [ ] `POST /execute` with valid Logiql program executes correctly (new integration test suite)
- [ ] `solveWithProof()` returns correct proof trees for all test expectations
- [ ] NAF ordering tests pass with facts in any assertion order (before AND after)
- [ ] HTTP_GET_JSON predicate test with mock server: timeout respected, retry occurs on 503
- [ ] ConsistencyGuard cycle test: no StackOverflowError on circular constraint
- [ ] Scope DAG concurrent test (100 threads): no corruption
- [ ] Snapshot corruption test: correct exception thrown, WAL fallback succeeds
- [ ] EventBus error threshold test: flaky subscriber auto-unsubscribed after 5 errors

### Phase 1B — Done When:
- [ ] All tenant-scoped routes return 400 without X-Tenant-ID header
- [ ] Path traversal attempt `../etc/passwd` as scope name returns 400
- [ ] 121st READER request in a minute returns 429
- [ ] Audit log file created on first security-sensitive operation
- [ ] Default credential warning appears in server logs at startup (if env vars unset)
- [ ] Rule with 33 body atoms returns 400
- [ ] AuthInterceptor routes all have explicit permission entries (startup assertion passes)
- [ ] Expired key returns 401 KEY_EXPIRED

### Phase 2A — Done When:
- [ ] Multi-justification TMS: fact with 2 proofs survives single retraction
- [ ] Rete beta memory test: 10K facts × 100 rules forward chaining < 1 second
- [ ] Consolidation: 5 episodic atoms → 1 semantic atom, 5 retracted
- [ ] Decay: expired atoms retracted, user-sourced atoms protected
- [ ] Memoization cross-contamination test: independent queries produce correct results
- [ ] Confidence MIN propagation: 0.8 × 0.6 body = 0.6 derived confidence
- [ ] Rule priority: higher priority rule tried first in backward chaining

### Phase 2B — Done When:
- [ ] Inference request produces OTel spans visible in Jaeger
- [ ] `/metrics` output includes `nocturnusai_facts_asserted_total{database=...,tenant=...}`
- [ ] `/metrics` output includes `nocturnusai_replication_lag_ms`
- [ ] `/metrics` output includes `jvm_memory_used_bytes`
- [ ] Python `test_client.py` achieves > 85% coverage of `client.py`
- [ ] Extraction route test with mock LLM passes
- [ ] TTL expiration test: atom with `ttl=1ms` not returned after 2ms
- [ ] Query timeout test: 408 returned when inference exceeds configured limit

### Phase 3 — Done When:
- [ ] `PATCH /tell` updates confidence without retracting/reasserting
- [ ] `POST /batch-ask` returns consistent snapshot results
- [ ] `GET /auth/audit` filters by action, tenantId, time range
- [ ] `GET /admin/databases/{name}/stats` returns correct counts
- [ ] `POST /purge` retracts 47 atoms containing target identifier
- [ ] Python SDK `stream_events()` yields 3 events from mock SSE stream
- [ ] `GET /schema` returns correct predicate list with arity and sample args
