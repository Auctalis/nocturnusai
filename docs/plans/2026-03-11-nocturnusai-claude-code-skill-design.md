# NocturnusAI Claude Code Skill Suite — Design Document

**Date**: 2026-03-11
**Audience**: External developers integrating NocturnusAI into their AI agents/apps via Claude Code
**Scope**: Full lifecycle — connect, assert, query, memory, reasoning, admin

## Overview

A suite of 5 Claude Code skills that teach Claude Code how to work with NocturnusAI — the logic server for Agentic AI. Each skill covers a distinct domain, stays under 500 lines, and includes inline examples for MCP JSON-RPC, curl, and SDK usage.

## Skill Suite Structure

```
.claude/skills/
├── nocturnusai-connect/
│   └── SKILL.md          # Setup, auth, tenancy, connection methods
├── nocturnusai-knowledge/
│   └── SKILL.md          # tell, ask, teach, forget, bulk ops, predicates
├── nocturnusai-memory/
│   └── SKILL.md          # context, recall, compress, cleanup, salience, events
├── nocturnusai-reasoning/
│   └── SKILL.md          # NAF, scopes, confidence, conflict strategies, proofs
└── nocturnusai-admin/
    └── SKILL.md          # databases, tenants, health, metrics, backups
```

Each skill has a `description` in frontmatter tuned for auto-triggering on relevant keywords.

---

## Skill 1: `nocturnusai-connect`

**Trigger keywords**: setup, connect, configure, MCP, auth, API key, tenant, database, bootstrap

### Three Connection Paths

**1. MCP Config** (Claude Desktop / Claude Code)
- `.mcp.json` with stdio bridge via `mcp-remote` (Claude Desktop can't inject `X-Tenant-ID`/`X-Database` headers natively)
- `claude_desktop_config.json` equivalent
- Env vars for `API_KEY`, `DATABASE`, `TENANT_ID`

**2. Direct HTTP** (Claude Code Bash tool)
- curl examples with all required headers
- JSON-RPC 2.0 format for `/mcp` endpoint
- REST endpoint alternatives (`/tell`, `/ask`, etc.)

**3. SDK Integration** (application code)
- Python: `pip install nocturnusai` → `NocturnusAIMCPClient` or `NocturnusAIClient`
- TypeScript: `npm install nocturnusai-sdk` → `NocturnusAIMCPClient`

### Auth Bootstrapping Sequence

```
1. Server starts with AUTH_ENABLED=true
2. POST /auth/bootstrap (with admin creds) → creates first admin key
3. Use admin key to POST /auth/keys → create scoped API keys
4. Distribute scoped keys to developers/agents
```

### Tenant Setup Sequence

```
1. POST /admin/databases (create database, or use "default")
2. POST /admin/databases/{db}/tenants with {"tenantId": "my-tenant"}
3. All subsequent requests include X-Tenant-ID header
```

### Gotchas

- Tenant must exist before any tell/ask/teach operations
- `X-Tenant-ID` is required on most endpoints (throws ValidationException if missing)
- MCP routes default tenant to `"default"` — REST routes don't
- Auth mode detection: `GET /auth/status` tells you which mode is active
- Claude Desktop can't set custom headers natively → need stdio bridge

---

## Skill 2: `nocturnusai-knowledge`

**Trigger keywords**: tell, ask, teach, forget, assert, query, infer, rule, fact, predicate, bulk

### Tools Covered

| Operation | MCP Tool | What it does |
|-----------|----------|-------------|
| Assert fact | `tell` | Store knowledge: `likes(alice, bob)` |
| Query/Infer | `ask` | Backward-chain inference with optional proof traces |
| Teach rule | `teach` | Horn clauses: `grandparent(?x,?z) :- parent(?x,?y), parent(?y,?z)` |
| Retract | `forget` | Remove fact + cascade derived facts via TMS |
| Bulk assert | `bulk_assert` | Batch insert (non-transactional) |
| Pattern retract | `retract_pattern` | Wildcard retraction: `likes(?x, ?y)` removes all |
| Schema discovery | `predicates` | List all predicates with arity and counts |
| Aggregate | `aggregate` | COUNT/SUM/MIN/MAX/AVG over matched facts |

### Key Patterns

- Variable convention: always `?` prefix (`?x`, `?who`, `?name`) — not Prolog uppercase, not `$`
- `args` is always a JSON array of strings: `["alice", "?x"]`
- `negated: true` = explicit negation (fact is false), distinct from NAF (see reasoning skill)
- `confidence: 0.0-1.0` optional on tell, `minConfidence` filters on ask
- `conflictStrategy` on tell: `REJECT` (default), `NEWEST_WINS`, `CONFIDENCE`, `KEEP_BOTH`
- `withProof: true` on ask returns full inference chain
- `scope` param optional — `null` means global partition

### Common Workflows

1. **Assert → Query**: tell facts, ask questions
2. **Teach → Infer**: define rules, then query derived knowledge
3. **Schema exploration**: `predicates` to discover KB contents, then `ask` to drill in
4. **Bulk loading**: `bulk_assert` for datasets, check success/failure counts
5. **Clean retraction**: `forget` with TMS cascade vs. `retract_pattern` for bulk cleanup

### Gotchas

- `tell` with duplicate predicate+args defaults to `REJECT` — use `conflictStrategy` to override
- `ask` returns text results, not structured JSON — parse the text output
- `bulk_assert` is non-transactional: partial failures possible, check counts
- `aggregate` needs `argIndex` for numeric ops (SUM/MIN/MAX/AVG) but not COUNT

---

## Skill 3: `nocturnusai-memory`

**Trigger keywords**: memory, context window, salience, temporal, recall, consolidate, decay, TTL, expire, events, SSE

### Tools Covered

| Operation | MCP Tool | What it does |
|-----------|----------|-------------|
| Context window | `context` | Salience-ranked facts for LLM context stuffing |
| Temporal query | `recall` | Point-in-time queries respecting validFrom/validUntil/ttl |
| Consolidate | `compress` | Merge repeated episodic patterns into semantic facts |
| Decay | `cleanup` | Expire TTL-past facts + evict low-salience ones |
| Events | SSE stream | Real-time notifications on fact changes |

### Key Concepts

- **Salience scoring**: composite of recency x frequency x priority
- **Temporal atoms**: facts carry `createdAt`, `validFrom`, `validUntil`, `ttl`
- **TTL auto-expiration**: set `ttl` (milliseconds) on `tell` for self-destructing facts
- **`validFrom`/`validUntil`**: schedule facts for future validity windows
- **Context window pattern**: `context(maxFacts=50, minRelevance=0.3)` → top-N most relevant facts
- **Consolidation**: repeated episodic facts → semantic summary
- **Decay**: periodic cleanup prevents unbounded growth

### Common Workflows

1. **Context stuffing**: `context(maxFacts=50, minRelevance=0.3)` → inject into system prompt
2. **Time travel**: `recall(predicate="status", args=["server1"], timestamp=1704067200000)`
3. **Memory hygiene loop**: `compress` then `cleanup(threshold=0.05)` periodically
4. **Priority boosting**: REST-only `POST /memory/priority` (no MCP tool)
5. **Event-driven agents**: subscribe to `GET /memory/events` SSE stream

### Gotchas

- `compress` and `cleanup` take no predicate/args — operate on entire tenant's memory
- `cleanup` threshold is the salience floor — facts below it get evicted
- Priority boosting has no MCP tool — must use REST directly
- SSE events are tenant-scoped via headers, not tool params
- `recall` timestamp is epoch milliseconds, not ISO 8601

---

## Skill 4: `nocturnusai-reasoning`

**Trigger keywords**: NAF, negation, scope, fork, merge, hypothesis, confidence, conflict, proof, what-if

### Negation-as-Failure (NAF)

- Closed-world assumption: `NOT p(?x)` succeeds when `p(?x)` cannot be proven
- Distinct from explicit negation (`negated: true` = fact is known false)
- In MCP JSON: `{"predicate":"penguin","args":["?x"],"naf":true}` on body atoms
- In DSL: `NOT penguin(?x)`
- **Critical ordering rule**: assert NAF-blocking facts BEFORE triggering facts
  ```
  CORRECT:   tell penguin(tweety) → tell bird(tweety)
  INCORRECT: tell bird(tweety) → tell penguin(tweety)   # Rete fires too early
  ```

### Scopes (Hypothetical Reasoning)

- Logical partitions within a tenant — NOT isolation boundaries
- Git-like branching: fork → experiment → merge or delete
- `scope=null` queries match atoms from ALL scopes
- Merge strategies: `SOURCE_WINS`, `TARGET_WINS`, `KEEP_BOTH`, `REJECT`

Workflow:
```
fork_scope(targetScope="hypothesis_1")
→ tell facts into "hypothesis_1"
→ ask queries against "hypothesis_1"
→ merge_scope(sourceScope="hypothesis_1", strategy="SOURCE_WINS")  # accept
   OR delete_scope(scope="hypothesis_1")                            # reject
```

### Confidence & Conflict Resolution

- `confidence: 0.0-1.0` on facts, `minConfidence` on queries to filter
- `conflictStrategy`: REJECT (default), NEWEST_WINS, CONFIDENCE, KEEP_BOTH

### Proof Chains

- `ask` with `withProof: true` returns full inference trace
- Shows which rules fired and which facts matched

### Gotchas

- NAF only meaningful in rule bodies — not on head atoms or asserted facts
- Scopes are NOT tenants — don't use for data isolation between users
- `scope=null` on a query means "search all scopes" not "search only unscoped"
- Confidence omitted means no score (different from confidence=1.0)

---

## Skill 5: `nocturnusai-admin`

**Trigger keywords**: database, tenant, health, metrics, backup, API key, RBAC, admin, monitoring

### Operations (All REST-only, no MCP tools)

| Category | Endpoints | Purpose |
|----------|-----------|---------|
| Databases | `GET/POST/DELETE /admin/databases` | Create, list, delete databases |
| Tenants | `POST /admin/databases/{db}/tenants` | Create tenants within a database |
| Health | `GET /health`, `/health/live`, `/health/ready` | Liveness and readiness checks |
| Metrics | `GET /metrics` | Prometheus-format metrics |
| Auth keys | `POST/GET/PATCH/DELETE /auth/keys` | RBAC API key lifecycle |
| Backups | `POST /admin/backups` | Trigger snapshot |
| WAL | `GET /replication/wal` | Access write-ahead log |
| Discovery | `GET /llm.txt`, `GET /.well-known/agent.json` | LLM-readable docs, A2A agent card |

### Common Workflows

1. **First-time setup**: create database → create tenant → verify with health check
2. **Key management** (RBAC): bootstrap → create admin key → create scoped keys
3. **Monitoring**: health for uptime, metrics for Prometheus/Grafana
4. **Backup**: trigger snapshot before risky operations
5. **Multi-database**: separate databases for dev/staging/prod, switch via `X-Database` header

### Gotchas

- All databases are forced multi-tenant — can't skip tenant creation
- `DELETE /admin/databases/{name}` is destructive and irreversible
- Auth bootstrap can only be called once (or when no admin keys exist)
- `/llm.txt` is auto-generated — useful for feeding API docs to other LLMs
- Public endpoints (`/health`, `/metrics`, `/llm.txt`, `/.well-known/agent.json`, `/auth/status`) bypass auth

---

## Issues & Problems Identified

| # | Issue | Severity | Mitigation in Skill |
|---|-------|----------|-------------------|
| 1 | Tenant must exist before operations | High | `connect` walks through creation as step 1 |
| 2 | Auth bootstrap chicken-and-egg | High | `connect` documents the bootstrap sequence |
| 3 | Claude Desktop can't inject custom headers | High | `connect` teaches `mcp-remote` stdio bridge |
| 4 | NAF assertion ordering | High | `reasoning` has prominent warning with examples |
| 5 | `?` variable prefix convention | Medium | `knowledge` emphasizes this, shows wrong vs right |
| 6 | Scope vs Tenant confusion | Medium | `reasoning` explicitly distinguishes them |
| 7 | MCP transport deprecation (SSE → Streamable HTTP) | Medium | `connect` notes deprecation, documents bridge |
| 8 | No MCP tools for admin ops | Medium | `admin` teaches curl/REST fallback |
| 9 | No MCP tool for priority boosting | Low | `memory` documents REST-only workaround |
| 10 | `compress`/`cleanup` take no args | Low | `memory` calls this out explicitly |
| 11 | `ask` returns text not structured JSON | Low | `knowledge` shows how to parse output |
| 12 | `recall` uses epoch ms not ISO | Low | `memory` documents the format |
| 13 | `bulk_assert` non-transactional | Low | `knowledge` warns about partial failures |
| 14 | MCP tenant defaults to "default" but REST doesn't | Medium | `connect` documents the inconsistency |
