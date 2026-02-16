# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

AxiomBase is the **logic server for Agentic AI**. It provides deterministic multi-step reasoning, truth maintenance, and agent memory lifecycle management via HTTP API, MCP protocol, and client SDKs (Python, TypeScript). Agents use AxiomBase as their semantic memory and reasoning backend — storing facts, defining rules, running inference, and managing context windows with temporal awareness and salience scoring.

## Build & Run Commands

```bash
# Build all modules
./gradlew build

# Run the HTTP API server (port 9300)
./gradlew :axiombase-server:run

# Run the core engine REPL directly
./gradlew :axiombase-core:run

# Run all tests
./gradlew test

# Run tests for a specific module
./gradlew :axiombase-core:test
./gradlew :axiombase-server:test

# Run a single test class
./gradlew :axiombase-core:test --tests "com.axiombase.TransactionTest"

# CLI (interactive REPL — connect to running server)
./gradlew :axiombase-cli:run                                           # defaults: localhost:9300, db=default
./gradlew :axiombase-cli:run --args='--server http://host:9300 --db mydb'
./gradlew :axiombase-cli:run --args='--api-key secret --db prod'

# Run server + use CLI
./run_local_dev.sh    # starts server on :9300

# Docker
docker-compose up --build    # server on :9300
```

## Architecture

Three-module Gradle project (`settings.gradle.kts` includes `axiombase-core`, `axiombase-server`, and `axiombase-cli`):

### axiombase-core — Pure Logic Engine Library
Package: `com.axiombase`

**Domain model** (`core/`):
- `Atom` — fundamental unit of knowledge: predicate + args + truth value + source + scope + temporal fields (createdAt, validFrom, validUntil, ttl)
- `Term` — sealed class: `Identifier`, `StringLit`, `NumberLit`, `Variable` (prefixed with `?`)
- `Rule` — Horn clause: head (consequent) + body (antecedent conditions)
- `LogicContext` — per-tenant container holding a Hexastore, rules, inference engines, and MemoryManager

**Storage** (`storage/Hexastore.kt`):
- 6-way indexed triple store (SPO/SOP/PSO/POS/OSP/OPS) for binary predicates
- Non-binary atoms in fallback map
- Thread-safe via `ReentrantReadWriteLock`
- Scope-aware queries for multi-tenant/partitioned data

**Inference** (`inference/`):
- `BackwardChainer` — goal-driven SLD resolution with unification, variable renaming, depth limit (100)
- `ReteEngine` — forward chaining triggered on fact assertion
- `Unifier` — term unification with substitution propagation

**Truth maintenance** (`logic/`):
- `ProvenanceTracker` — tracks inference dependencies, auto-retracts derived facts when premises removed
- `ConsistencyGuard` — enforces domain constraints (uniqueness, exclusivity, range validation)

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

### axiombase-server — Ktor HTTP API
Package: `com.axiombase.server`

Built on Ktor 2.3.7 with Netty. Depends on `:axiombase-core`.

**Routes** (`routes/`):
- `LogicRoutes` — `POST /assert/fact`, `/assert/rule`, `/assert/template`, `/infer`, `/retract`, `/execute`
- `MemoryRoutes` — `POST /memory/query/temporal`, `/memory/query/salient`, `/memory/context`, `/memory/priority`, `/memory/consolidate`, `/memory/decay`, `GET /memory/events` (SSE)
- `McpRoutes` — `POST /mcp` (JSON-RPC 2.0), `GET /mcp/sse` (MCP streaming transport)
- `AdminRoutes` — `GET/POST/DELETE /admin/databases`, facts/rules listing, tenant management
- `TransactionRoutes` — `POST /tx/begin`, `/tx/commit/{id}`, `/tx/rollback/{id}`
- `ObservabilityRoutes` — `GET /health`, `/metrics` (Prometheus), `/llm.txt`, `GET /.well-known/agent.json` (A2A Agent Card)
- `ReplicationRoutes` — `GET /replication/wal`, `POST /admin/backups`

**Key classes**:
- `Application.kt` — Ktor module setup (CORS, content negotiation, call logging, optional API key auth)
- `DatabaseManager` — manages multiple AxiomBase instances in a `ConcurrentHashMap`, database selected via `X-Database` header
- `ServerConfig` — env var config (`PORT`, `HOST`, `API_KEY`, `STORAGE_DIR`, `REPLICATION_MODE`, `LEADER_URL`)
- `TemplateService` — logic template application (Modus Ponens, Modus Tollens, etc.)
- `LlmTxtGenerator` — auto-generates `/llm.txt` API documentation via reflection

**Multi-tenancy**: `X-Database` header selects database, `X-Tenant-ID` header selects tenant within database.

### axiombase-cli — Interactive REPL
Package: `com.axiombase.cli`

Ktor-client based CLI that connects to a running AxiomBase server over HTTP.

**Commands**: `ask`, `tell`, `teach`, `forget`, `inspect`, `context`, `compress`, `cleanup`, `dsl`, `use`, `dbs`, `health`
**Shortcuts**: `?`=ask, `+`=tell, `++`=teach, `-`=forget, `ls`=inspect, `ctx`=context

Parses natural predicate syntax (`likes(alice, bob)`, `mortal(?x) :- human(?x)`) client-side.
Args: `--server`, `--db`, `--api-key`, `--tenant`.

### sdks/python — Python SDK (`axiombase` on PyPI)
- Async client (`AxiomBaseClient`) and sync wrapper (`SyncAxiomBaseClient`) using httpx
- Pydantic models for all DTOs
- LangChain tool wrappers (`axiombase.langchain`) — `AxiomBaseAssertTool`, `AxiomBaseQueryTool`, `AxiomBaseInferTool`, `AxiomBaseContextTool`
- MCP client helper (`axiombase.mcp`) for JSON-RPC 2.0 communication

### sdks/typescript — TypeScript SDK (`@axiombase/sdk` on npm)
- `AxiomBaseClient` with full API coverage using standard fetch
- SSE event subscription support
- MCP client helper (`AxiomBaseMCPClient`)
- Zero runtime dependencies (uses built-in fetch)

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
- **Scope-based multi-tenancy**: Facts/rules can be scoped for hypothetical reasoning, versioning, or tenant isolation.

## Environment Variables (Server)

| Variable | Default | Purpose |
|----------|---------|---------|
| `PORT` | `9300` | Server port |
| `HOST` | `0.0.0.0` | Bind address |
| `API_KEY` | _(none)_ | Optional auth key |
| `STORAGE_DIR` | `./data` | WAL/snapshot directory |
| `REPLICATION_MODE` | `LEADER` | `LEADER` or `FOLLOWER` |
| `LEADER_URL` | _(none)_ | Leader URL (follower mode) |
