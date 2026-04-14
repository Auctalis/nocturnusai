# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## Project Overview

NocturnusAI is the **logic server for Agentic AI**. It provides deterministic multi-step reasoning, truth maintenance, and agent memory lifecycle management via HTTP API, MCP protocol, and client SDKs (Python, TypeScript). Agents use NocturnusAI as their semantic memory and reasoning backend — storing facts, defining rules, running inference, and managing context windows with temporal awareness and salience scoring.

## Build & Run Commands

```bash
# Build all modules
./gradlew build

# Run the HTTP API server (port 9300)
./gradlew :nocturnusai-server:run

# Run the core engine REPL directly
./gradlew :nocturnusai-core:run

# Run all tests
./gradlew test

# Run tests for a specific module
./gradlew :nocturnusai-core:test
./gradlew :nocturnusai-server:test

# Run a single test class
./gradlew :nocturnusai-core:test --tests "com.nocturnusai.TransactionTest"

# CLI (interactive REPL — connect to running server)
./gradlew :nocturnusai-cli:run                                           # defaults: localhost:9300, db=default
./gradlew :nocturnusai-cli:run --args='--server http://host:9300 --db mydb'
./gradlew :nocturnusai-cli:run --args='--api-key secret --db prod'

# Run server + use CLI
./run_local_dev.sh    # starts server on :9300

# Docker
docker-compose up --build    # server on :9300
```

## Architecture

Three-module Gradle project (`settings.gradle.kts` includes `nocturnusai-core`, `nocturnusai-server`, and `nocturnusai-cli`):

### nocturnusai-core — Pure Logic Engine Library
Package: `com.nocturnusai`

**Domain model** (`core/`):
- `Atom` — fundamental unit of knowledge: predicate + args + truth value + source + scope + temporal fields (createdAt, validFrom, validUntil, ttl) + `confidence: Double?` (0.0–1.0) + `naf: Boolean` (negation-as-failure flag, used in rule bodies)
- `Term` — sealed class: `Identifier`, `StringLit`, `NumberLit`, `Variable` (prefixed with `?`)
- `Rule` — Horn clause: head (consequent) + body (antecedent conditions) + optional `scope: String?`
- `LogicContext` — per-tenant container holding a Hexastore, rules, inference engines, and MemoryManager

**Storage** (`storage/Hexastore.kt`):
- 6-way indexed triple store (SPO/SOP/PSO/POS/OSP/OPS) for binary predicates
- Non-binary atoms in fallback map
- Thread-safe via `ReentrantReadWriteLock`
- Scope-aware queries for multi-tenant/partitioned data

**Inference** (`inference/`):
- `BackwardChainer` — goal-driven SLD resolution with unification, variable renaming, depth limit (100). Supports Negation-as-Failure (NAF): body atoms with `naf=true` succeed when the atom *cannot be proven* from known facts (closed-world assumption). NAF is only meaningful in rule bodies — not on head atoms or asserted facts.
- `ReteEngine` — forward chaining triggered on fact assertion. NAF conditions are evaluated at rule-fire time: if the blocking fact exists when the rule fires, the derivation is suppressed. **Important**: assert NAF-blocking facts *before* triggering facts so both forward and backward chaining agree.
- `Unifier` — term unification with substitution propagation

**Truth maintenance** (`logic/`):
- `ProvenanceTracker` — tracks inference dependencies, auto-retracts derived facts when premises removed
- `ConsistencyGuard` — enforces domain constraints (uniqueness, exclusivity, range validation)

**Conflict Resolution**:
- `ConflictStrategy` enum: `REJECT` (default), `NEWEST_WINS`, `CONFIDENCE`, `KEEP_BOTH`

**Persistence** (`persistence/`):
- `WriteAheadLog` — append-only WAL for crash recovery
- `SnapshotManager` — periodic full-state JSON snapshots

**Transactions** (`transaction/TransactionManager.kt`):
- ACID transactions with begin/commit/rollback, contradiction detection

**Parser** (`parser/`):
- `Tokenizer` + `Parser` for the Logiql DSL (used by `/execute` endpoint)

**Agent Memory** (`memory/`):
- `MemoryManager` — agent-facing memory lifecycle controller (temporal queries, salience-ranked retrieval, consolidation, decay)
- `SalienceTracker` — computes composite salience scores from recency, frequency, and explicit priority
- `EventBus` — pub/sub for knowledge change events (fact asserted/retracted, expired, consolidated)

**Entry point**: `Main.kt` (interactive REPL)

### nocturnusai-server — Ktor HTTP API
Package: `com.nocturnusai.server`

Built on Ktor 2.3.7 with Netty. Depends on `:nocturnusai-core`.

**Routes** (`routes/`):
- `LogicRoutes` — `POST /assert/fact`, `/assert/rule`, `/assert/template`, `/infer`, `/retract`, `/execute`
- `MemoryRoutes` — `POST /memory/query/temporal`, `/memory/query/salient`, `/memory/context`, `/memory/priority`, `/memory/consolidate`, `/memory/decay`, `GET /memory/events` (SSE)
- `SimplifiedRoutes` — `POST /tell`, `/ask`, `/query`, `/teach`, `/forget` (developer-friendly aliases); `POST /memory/recall`, `/memory/compress`, `/memory/cleanup`, `/memory/prioritize`; `GET /memory/stream`
- `ScopeRoutes` — `POST /scope/fork`, `/scope/diff`, `/scope/merge`, `DELETE /scope/{name}`, `GET /scopes`
- `AggregateRoutes` — `POST /aggregate` (COUNT/SUM/MIN/MAX/AVG), `POST /assert/facts` (bulk), `POST /retract/pattern`
- `AuthRoutes` — `POST /auth/bootstrap`, `GET /auth/status`, `POST /auth/keys`, `GET /auth/keys`, `GET /auth/keys/{id}`, `PATCH /auth/keys/{id}`, `DELETE /auth/keys/{id}`, `GET /auth/whoami`
- `ExtractionRoutes` — `POST /extract`, `POST /extract/batch`
- `SynthesisRoutes` — `POST /synthesize`
- `McpRoutes` — `POST /mcp` (JSON-RPC 2.0), `GET /mcp/sse` (MCP streaming transport). MCP tools: `tell`, `ask`, `teach`, `forget`, `inspect`, `context`, `aggregate`, `bulk_assert`, `retract_pattern`, `fork_scope`, `merge_scope`, `list_scopes`, `delete_scope`. The `teach` tool maps both `negated` (→ `truthVal=false`) and `naf` (→ `Atom.naf=true`) fields on body atoms.
- `AdminRoutes` — `GET/POST/DELETE /admin/databases`, facts/rules listing, tenant management
- `TransactionRoutes` — `POST /tx/begin`, `/tx/commit/{id}`, `/tx/rollback/{id}`
- `ObservabilityRoutes` — `GET /health`, `/metrics` (Prometheus), `/llm.txt`, `GET /.well-known/agent.json` (A2A Agent Card)
- `ReplicationRoutes` — `GET /replication/wal`, `POST /admin/backups`

**Key classes**:
- `Application.kt` — Ktor module setup (CORS, content negotiation, call logging, optional API key auth)
- `DatabaseManager` — manages multiple NocturnusAI instances in a `ConcurrentHashMap`, database selected via `X-Database` header
- `ServerConfig` — env var config (`PORT`, `HOST`, `API_KEY`, `STORAGE_DIR`, `REPLICATION_MODE`, `LEADER_URL`)
- `TemplateService` — logic template application (Modus Ponens, Modus Tollens, etc.)
- `LlmTxtGenerator` — auto-generates `/llm.txt` API documentation via reflection
- `AuthInterceptor` — RBAC auth with 3 modes (DISABLED, LEGACY, RBAC), rate limiting
- `ApiKeyManager` — key lifecycle, role-based permissions, database/tenant scoping

**Multi-tenancy**: `X-Database` header selects database (defaults to `'default'`). `X-Tenant-ID` header is **required** on most endpoints (throws `ValidationException` if missing); MCP routes default to `'default'`. Each tenant gets a separate `LogicContext` with isolated Hexastore, rules, and memory.

**Scope management**: Scopes are logical partitions within a tenant (NOT true isolation — use tenants for that). `scope=null` queries match atoms from ALL scopes. Fork/diff/merge/delete available via `/scope/*` endpoints. `MergeStrategy`: `SOURCE_WINS`, `TARGET_WINS`, `KEEP_BOTH`, `REJECT`.

### nocturnusai-cli — Interactive REPL
Package: `com.nocturnusai.cli`

Ktor-client based CLI that connects to a running NocturnusAI server over HTTP.

**Commands**: `ask`, `tell`, `teach`, `forget`, `ingest`, `inspect`, `context`, `compress`, `cleanup`, `dsl`, `import`/`load`, `export`/`dump`, `use`, `tenant`, `dbs`, `health`, `status`, `setup`, `login`, `whoami`, `keys`, `history`, `clear`, `help`
**Shortcuts**: `?`=ask, `+`=tell, `++`=teach, `-`=forget, `ls`=inspect, `ctx`=context

Parses natural predicate syntax (`likes(alice, bob)`, `mortal(?x) :- human(?x)`) client-side. `ask`, `tell`, and `ingest` also support natural language — routed through LLM extraction/synthesis when no predicate syntax is detected.
Args: `--server`, `--db`, `--api-key`, `--tenant`/`-t`, `--exec`/`-e`.

### sdks/python — Python SDK (`nocturnusai` on PyPI)
- Async client (`NocturnusAIClient`) and sync wrapper (`SyncNocturnusAIClient`) using httpx
- Pydantic models for all DTOs
- LangChain tool wrappers (`nocturnusai.langchain`) — `NocturnusAIAssertTool`, `NocturnusAIQueryTool`, `NocturnusAIInferTool`, `NocturnusAIContextTool`
- MCP client helper (`nocturnusai.mcp`) for JSON-RPC 2.0 communication

### sdks/typescript — TypeScript SDK (`nocturnusai-sdk` on npm)
- `NocturnusAIClient` with full API coverage using standard fetch
- SSE event subscription support
- MCP client helper (`NocturnusAIMCPClient`)
- Zero runtime dependencies (uses built-in fetch)

### site/ — Documentation Site (Astro + GitHub Pages)
- Static site built with **Astro**, deployed to GitHub Pages at `https://nocturnus.ai/`
- Source: `site/src/pages/` (`.astro` files), `site/src/layouts/DocsLayout.astro` (shared sidebar + nav), `site/src/components/Navbar.astro`
- All doc pages use `DocsLayout` with `const base = import.meta.env.BASE_URL.replace(/\/$/, '')` for correct path resolution on GitHub Pages
- Convention: sidebar section titles match page `<h1>` and `<DocsLayout title="">` prop (e.g., "CLI Reference", "API Reference", "MCP Integration")
- Deploy workflow: `.github/workflows/docs.yml` — triggers on pushes to `main` with changes in `site/**`
- Build: `cd site && npm ci && npm run build`

### Agent Integration Points
- **MCP**: `POST /mcp` (JSON-RPC 2.0) + `GET /mcp/sse` (streaming). Configure via `mcp-config.json`.
- **A2A**: `GET /.well-known/agent.json` for Agent2Agent Protocol discovery
- **REST**: Full HTTP API with `X-Database` and `X-Tenant-ID` headers
- **SSE**: `GET /memory/events` for real-time knowledge change subscriptions

## Tech Stack Summary

| Layer | Technology | Version |
|-------|-----------|---------|
| Language | Kotlin | 1.9.21 |
| Serialization | kotlinx-serialization-json | 1.6.2 |
| Coroutines | kotlinx-coroutines-core | 1.8.0 |
| HTTP Framework | Ktor | 2.3.7 |
| HTTP Engine | Netty | (via Ktor) |
| Metrics | Micrometer + Prometheus | 1.10.3 |
| Logging | Logback + SLF4J | 1.4.14 / 2.0.9 |
| Build | Gradle (Kotlin DSL) | wrapper included |
| Runtime | JDK 17+ (Docker uses JDK 21) | — |

## Key Design Decisions

- **Agent-first architecture**: All features are designed for AI agent consumption. MCP protocol, salience-ranked retrieval, temporal queries, and memory lifecycle management are first-class concerns.
- **In-memory store**: All data lives in memory with WAL + snapshots for durability. No disk-based B-tree/LSM.
- **Hexastore indexing**: 6 index permutations enable efficient pattern matching regardless of which terms are bound vs. variable.
- **Backward chaining as primary inference**: Goal-driven resolution (Prolog-style) via `BackwardChainer`. Forward chaining via `ReteEngine` supplements.
- **Truth Maintenance System**: `ProvenanceTracker` automatically maintains consistency when facts are retracted.
- **Temporal atoms**: Facts carry `createdAt`, `validFrom`, `validUntil`, and `ttl` for point-in-time queries and automatic expiration.
- **Salience-based memory**: Composite scoring (recency × frequency × priority) determines which facts are most relevant for agent context windows.
- **Memory lifecycle**: Consolidation compresses repeated episodic patterns into semantic facts. Decay evicts expired/low-salience facts.
- **Variables use `?` prefix**: e.g., `?x`, `?who` — this convention is used throughout the codebase and API.
- **Scope-based partitioning**: Within a tenant, facts/rules can be scoped for hypothetical reasoning, versioning, and A/B testing. Scopes are logical partitions (not isolation boundaries) — use the `X-Tenant-ID` header for true data isolation. Scope management via fork/diff/merge enables Git-like knowledge branching.
- **Negation-as-Failure (NAF)**: Closed-world assumption — `NOT p(?x)` in a rule body succeeds when `p(?x)` cannot be proven. Distinct from explicit negation (`truthVal=false`). In JSON: `{"predicate":"p","args":["?x"],"naf":true}`. In CLI/DSL: `NOT p(?x)`. Rete forward chaining evaluates NAF at fire time; backward chainer evaluates at query time.
- **Confidence and conflict resolution**: Facts can carry `confidence: 0.0–1.0`. Queries accept `minConfidence` to filter low-confidence results. `ConflictStrategy` controls duplicate predicate+args handling: `REJECT` (error), `NEWEST_WINS`, `CONFIDENCE` (highest wins), `KEEP_BOTH`.
- **Aggregation**: `POST /aggregate` with `AggregateOp` enum: `COUNT`, `SUM`, `MIN`, `MAX`, `AVG`. Operates over matched facts with optional `scope` and `argIndex` parameters. `COUNT` doesn't require `argIndex`; numeric ops do.

## Testing

764 tests across core and server modules. Key patterns:

- **`withTestApp { }`** (`TestHelpers.kt`): Creates a fresh temp directory, starts a `testApplication` with `moduleWithStorageDir(tmpDir)`, and cleans up on exit. Guarantees complete state isolation per test.
- **Tenant setup**: Most server tests require `client.post("/admin/databases/default/tenants") { setBody("""{"tenantId":"test"}""") }` before assertions.
- **Headers**: All requests to tenant-scoped endpoints must include `header("X-Tenant-ID", tenant)`.
- **MCP tests**: Send JSON-RPC 2.0 to `POST /mcp`, parse response as `JsonObject`, use `result["content"][0]["text"]` for tool output.
- **NAF test ordering**: Assert NAF-blocking facts *before* triggering facts so Rete forward chaining and backward chaining agree (e.g., assert `penguin(tweety)` before `bird(tweety)`).

```bash
./gradlew test                           # all 764 tests
./gradlew :nocturnusai-core:test --tests "com.nocturnusai.NafTest"
./gradlew :nocturnusai-server:test --tests "com.nocturnusai.server.NafRoutesTest"
```

## GitHub

- Push requires: `gh auth switch --user Auctalis` (if multiple GitHub accounts)
- CI workflow: `.github/workflows/ci.yml` (runs on all pushes to main)
- Release workflow: `.github/workflows/release.yml` (triggers on version tags `v*`)
- Docs workflow: `.github/workflows/docs.yml` (triggers on `site/**` changes)

## Environment Variables (Server)

| Variable | Default | Purpose |
|----------|---------|---------|
| `PORT` | `9300` | Server port |
| `HOST` | `0.0.0.0` | Bind address |
| `API_KEY` | _(none)_ | Legacy single-key auth |
| `AUTH_ENABLED` | `false` | Enable full RBAC mode |
| `NOCTURNUSAI_ADMIN_USER` | `admin` | Bootstrap admin username |
| `NOCTURNUSAI_ADMIN_PASS` | `nocturnusai` | Bootstrap admin password |
| `API_KEY_DEFAULT_EXPIRY_DAYS` | _(none)_ | Default key expiry in days |
| `CORS_ALLOWED_ORIGINS` | localhost:3000,5173,8080 | Comma-separated allowed origins |
| `MAX_REQUEST_BODY_BYTES` | `10485760` | Max request body size (10 MB) |
| `STORAGE_DIR` | `./data` | WAL/snapshot directory |
| `ENCRYPTION_KEY` | _(none)_ | 64 hex-char AES-256 key |
| `REPLICATION_MODE` | `LEADER` | `LEADER` or `FOLLOWER` |
| `LEADER_URL` | _(none)_ | Leader URL (follower mode) |
| `EXTRACTION_ENABLED` | `false` | Enable LLM extraction endpoints |
| `LLM_BASE_URL` | _(none)_ | Ollama or custom OpenAI-compatible endpoint |
| `LLM_MODEL` | _(none)_ | LLM model name |
| `OPENAI_API_KEY` | _(none)_ | OpenAI API key for extraction/synthesis |
| `ANTHROPIC_API_KEY` | _(none)_ | Anthropic API key for extraction/synthesis |
| `GOOGLE_API_KEY` | _(none)_ | Google API key for extraction/synthesis |
