# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

AxiomBase is a logic-based inference engine and knowledge database ("Symbolic Cortex") built in Kotlin. It provides deterministic multi-step logical reasoning, rule-based inference, and state management via an HTTP API, with a React web console.

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

# Frontend (separate terminal)
cd axiombase-web && npm install && npm run dev    # dev server on :5173
cd axiombase-web && npm run build                 # production build
cd axiombase-web && npm run lint                  # ESLint

# Run both server + web together
./run_local_dev.sh    # server on :9300, web on :9350

# Docker (full stack)
docker-compose up --build    # server on :9300, web on :9400
```

## Architecture

Three-module Gradle project (`settings.gradle.kts` includes `axiombase-core` and `axiombase-server`; `axiombase-web` is a standalone npm project):

### axiombase-core — Pure Logic Engine Library
Package: `com.axiombase`

**Domain model** (`core/`):
- `Atom` — fundamental unit of knowledge: predicate + args + truth value + source + optional scope
- `Term` — sealed class: `Identifier`, `StringLit`, `NumberLit`, `Variable` (prefixed with `?`)
- `Rule` — Horn clause: head (consequent) + body (antecedent conditions)
- `LogicContext` — per-tenant container holding a Hexastore, rules, and inference engines

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

**Entry point**: `Main.kt` (interactive REPL)

### axiombase-server — Ktor HTTP API
Package: `com.axiombase.server`

Built on Ktor 2.3.7 with Netty. Depends on `:axiombase-core`.

**Routes** (`routes/`):
- `LogicRoutes` — `POST /assert/fact`, `/assert/rule`, `/assert/template`, `/infer`, `/retract`, `/execute`
- `AdminRoutes` — `GET/POST/DELETE /admin/databases`, facts/rules listing, tenant management
- `TransactionRoutes` — `POST /tx/begin`, `/tx/commit/{id}`, `/tx/rollback/{id}`
- `ObservabilityRoutes` — `GET /health`, `/metrics` (Prometheus), `/llm.txt`
- `ReplicationRoutes` — `GET /replication/wal`, `POST /admin/backups`

**Key classes**:
- `Application.kt` — Ktor module setup (CORS, content negotiation, call logging, optional API key auth)
- `DatabaseManager` — manages multiple AxiomBase instances in a `ConcurrentHashMap`, database selected via `X-Database` header
- `ServerConfig` — env var config (`PORT`, `HOST`, `API_KEY`, `STORAGE_DIR`, `REPLICATION_MODE`, `LEADER_URL`)
- `TemplateService` — logic template application (Modus Ponens, Modus Tollens, etc.)
- `LlmTxtGenerator` — auto-generates `/llm.txt` API documentation via reflection

**Multi-tenancy**: `X-Database` header selects database, `X-Tenant-ID` header selects tenant within database.

### axiombase-web — React Web Console
Vite 7 + React 19 + react-router-dom 7. Plain JavaScript (no TypeScript).

**Pages**: `Login.jsx` (API key), `Dashboard.jsx` (database list), `QueryConsole.jsx` (query interface)
**Components**: `Layout`, `Sidebar`, `ActionToolbar`, `VisualBuilder`, `ResultsTable`, `LogicResultVisualizer`

API base URL configured via `VITE_API_URL` env var.

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
| Frontend | React + Vite | 19.2.0 / 7.2.4 |
| Build | Gradle (Kotlin DSL) | wrapper included |
| Runtime | JDK 17+ (Docker uses JDK 21) | — |

## Key Design Decisions

- **In-memory store**: All data lives in memory with WAL + snapshots for durability. No disk-based B-tree/LSM.
- **Hexastore indexing**: 6 index permutations enable efficient pattern matching regardless of which terms are bound vs. variable.
- **Backward chaining as primary inference**: Goal-driven resolution (Prolog-style) via `BackwardChainer`. Forward chaining via `ReteEngine` supplements.
- **Truth Maintenance System**: `ProvenanceTracker` automatically maintains consistency when facts are retracted.
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
