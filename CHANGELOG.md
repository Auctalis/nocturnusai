# Changelog

All notable changes to this project will be documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
This project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [0.1.0] — 2025-02-19

Initial public release.

### Core Engine (`nocturnusai-core`)
- Hexastore: 6-way indexed in-memory fact store (SPO/SOP/PSO/POS/OSP/OPS)
- Backward chaining (SLD resolution, Prolog-style) with depth limit
- Forward chaining (Rete engine) triggered on fact assertion
- Unifier with full substitution propagation
- Truth Maintenance System (TMS) — auto-cascade retraction of derived facts
- Temporal facts: `validFrom`, `validUntil`, `ttl`, `createdAt`
- Salience scoring: recency × frequency × priority
- Memory lifecycle: consolidation and decay
- ACID transactions with contradiction detection
- Write-ahead log (WAL) + snapshot persistence
- EventBus for pub/sub knowledge change notifications
- Scope-based isolation for multi-tenant and hypothetical reasoning

### HTTP Server (`nocturnusai-server`)
- Simplified API: `POST /tell`, `/ask`, `/query`, `/teach`, `/forget`
- Full API: `POST /assert/fact`, `/assert/rule`, `/assert/template`, `/infer`, `/retract`, `/execute`
- Memory API: `/memory/context`, `/memory/query/temporal`, `/memory/query/salient`, `/memory/consolidate`, `/memory/decay`
- Transaction API: `/tx/begin`, `/tx/commit/:id`, `/tx/rollback/:id`
- MCP server: `POST /mcp` (JSON-RPC 2.0) + `GET /mcp/sse` (streaming transport)
- 9 MCP tools: tell, teach, ask, forget, recall, context, compress, cleanup, predicates
- A2A agent discovery: `GET /.well-known/agent.json`
- LLM integration: `POST /extract`, `/extract/batch`, `/synthesize` (Anthropic, OpenAI, Google, Ollama)
- Multi-tenancy: `X-Database` and `X-Tenant-ID` headers
- Optional auth: bearer token, role-based access, AES-256 encryption
- TLS support
- Prometheus metrics at `/metrics`
- Auto-generated LLM documentation at `/llm.txt`
- Docker image: `ghcr.io/Auctalis/nocturnusai`

### Python SDK (`nocturnusai` on PyPI)
- Async client (`NocturnusAIClient`) and sync wrapper (`SyncNocturnusAIClient`)
- LangChain tool wrappers: assert, query, infer, context

### TypeScript SDK (`nocturnusai-sdk` on npm)
- `NocturnusAIClient` — zero runtime dependencies
- `NocturnusAIMCPClient` — MCP JSON-RPC 2.0 client
- SSE event subscription

### CLI (`nocturnusai-cli`)
- Interactive REPL connecting to a running server
- Commands: ask, tell, teach, forget, inspect, context, compress, cleanup, dsl, use, dbs, health
