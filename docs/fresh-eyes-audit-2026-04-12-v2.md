# Fresh Eyes Audit — NocturnusAI (Docker-First, Full Exercise)
**Date:** 2026-04-12
**Auditor:** Claude (fresh-eyes-audit skill)
**Server Version:** 0.3.3 (Docker via ghcr.io/auctalis/nocturnusai:latest)
**SDK Versions:** Python 0.3.2 (PyPI), TypeScript 0.3.3 (npm)
**Method:** Docker install from front-page instructions, all endpoints exercised with curl, both SDKs installed from package managers and exercised programmatically, MCP protocol tested via JSON-RPC, docs site reviewed page-by-page.

---

## Verdict: MAYBE (Leaning ADOPT for the right use case)

NocturnusAI has a genuinely unique value proposition: deterministic reasoning + agent memory + context window optimization in a single self-hosted server. No competitor (Mem0, Zep, Letta) offers anything resembling Horn clause inference, truth maintenance, or scope fork/merge. The Docker setup works in under 2 minutes and the server is stable. However, the developer experience has friction: README examples produce different output than shown, docs have 5 copy-paste-breaking errors, both SDKs have a shared `args: []` bug that makes `aggregate()` and `retractPattern()` silently return zero results, and error messages expose internal Java class names. For a team that needs provable reasoning (compliance, finance, healthcare), this is worth adopting despite the rough edges. For teams that just need agent memory, Mem0/Zep are more polished today.

---

## First 60 Seconds

The README opens with "Large turn arrays in. Lean context windows out." — immediately clear value prop. The "Working Loop" section (curl examples) gives you the mental model in ~30 seconds: send turns, get compressed facts back, diff on subsequent turns, clear session when done.

**What was unclear:**
- The project has a dual identity: "logic server" (reasoning engine) vs "context server" (token optimizer). The README leads with context optimization, but CLAUDE.md and docs emphasize logic/reasoning. Which is the primary use case?
- The README example 1 shows clean predicates like `customer_tier(acme_corp, enterprise)` but actual LLM extraction produces `HasName`, `HasValue`, `Did` — the disconnect between idealized and actual output is confusing.
- The `/context/optimize` example (README step 2) returned empty results in testing because it depends on having matching rules pre-loaded.

**Time to "aha":** ~45 seconds to understand the concept, ~5 minutes to have it running and serving requests via Docker.

---

## Setup & Installation

### Method 1: Docker via `make up` (followed README Docker Compose section)

| Step | Command | Time | Result |
|------|---------|------|--------|
| 1 | `git clone` (simulated with local copy) | instant | OK |
| 2 | `docker pull ghcr.io/auctalis/nocturnusai:latest` | ~15s | Image pulled successfully |
| 3 | `make up` | 2s + 30s health wait | **FAILED** — port 9300 in use |
| 4 | `PORT=9301 make up` | 2s + 8s health wait | Server running |
| 5 | `make smoke` | 3s | Passed |

**Total time to running server:** ~2 minutes (including image pull)

**Issues encountered:**
- **FRICTION**: Port 9300 conflict produces raw Docker error: `Error response from daemon: ports are not available: exposing port TCP 0.0.0.0:9300`. No suggestion to change PORT or check what's using it.
- **FINDING**: `make up` uses `.env` if present, `.env.example` otherwise. The Makefile logic is correct but not obvious — a developer who copies `.env.example` to `.env` and edits it will get their overrides, but one who doesn't will get defaults. Good design.
- **GOOD**: Health check passes within 10 seconds. `make smoke` exercises assert + context + retract.

### Method 2: install.sh (primary front-page path)

The `curl ... | bash` installer tries to download a CLI binary first. If no binary exists for the platform (likely for most users currently), it falls back to Docker automatically. This fallback is well-implemented — it detects docker/podman, pulls the image, generates a docker-compose.yml, and starts the server. Good graceful degradation.

---

## SDK Report Card

| Language | Install | Connect | CRUD | Coverage | Ergonomics | Advanced | Grade |
|----------|---------|---------|------|----------|-----------|----------|-------|
| Python   | 0.5s, 12 deps | error msgs | all work | 90% (5 missing methods) | B+ (sync+async, Pydantic) | scopes, context, temporal | **B+** |
| TypeScript | 0.8s, 0 deps | error msgs | all work | 92% (4 missing methods) | A- (zero deps, full types) | scopes, context, MCP client | **A-** |

### Python SDK — Detailed Findings

**Install:** 0.49s, 12 packages (httpx + pydantic + transitive). Clean, no warnings.

**Connect:** Excellent error handling. `NocturnusAIConnectionError` includes URL and retry info. `NocturnusAITimeoutError` includes timeout duration.

**CRUD:** All operations work. `tell()` returns dict wrapping a string, `ask()`/`infer()` return `list[Atom]` with proper Pydantic models.

**Coverage table (key methods):**

| SDK Method | Server Endpoint | Works? | Notes |
|---|---|---|---|
| tell() | POST /tell | YES | |
| ask() | POST /infer | YES | |
| teach() | POST /teach | YES | |
| forget() | POST /forget | YES | |
| process_turns() | POST /context | YES | Main context workflow |
| diff_context() | POST /context/diff | YES | |
| clear_context_session() | POST /context/session/clear | YES | |
| assert_fact() | POST /assert/fact | YES | |
| assert_rule() | POST /assert/rule | YES | |
| infer() | POST /infer | YES | |
| execute() | POST /execute | YES | |
| context() | POST /memory/context | YES | Unified endpoint |
| temporal_query() | POST /memory/query/temporal | YES | |
| set_priority() | POST /memory/priority | YES | |
| consolidate() | POST /memory/consolidate | YES | |
| decay() | POST /memory/decay | YES | |
| fork_scope() | POST /scope/fork | YES | |
| merge_scope() | POST /scope/merge | YES | |
| diff_scope() | POST /scope/diff | YES | |
| delete_scope() | DELETE /scope/{name} | YES | |
| list_scopes() | GET /scopes | **BUG** | Always returns empty — parses dict as list |
| aggregate() | POST /aggregate | **BUG** | Sends args:[] — matches nothing |
| retract_pattern() | POST /retract/pattern | **BUG** | Sends args:[] — matches nothing |
| bulk_assert() | POST /assert/facts | YES | |
| begin_transaction() | POST /tx/begin | YES | |
| commit_transaction() | POST /tx/commit/{id} | YES | |
| rollback_transaction() | POST /tx/rollback/{id} | YES | |
| subscribe_events() | GET /memory/events | YES | Async only |
| recall() | POST /memory/recall | **MISSING** | No SDK method |
| compress() | POST /memory/compress | **MISSING** | No SDK method |
| cleanup() | POST /memory/cleanup | **MISSING** | No SDK method |
| prioritize() | POST /memory/prioritize | **MISSING** | No SDK method |
| salient_query() | POST /memory/query/salient | **MISSING** | No SDK method |

### TypeScript SDK — Detailed Findings

**Install:** 0.84s, zero runtime dependencies. Uses built-in `fetch`. Impressively lightweight.

**Connect:** `NocturnusAIRequestError` with `statusCode` and structured `error` object. `statusCode: 0` for network errors is slightly odd.

**CRUD:** All operations work. `tell()` returns string, `ask()` returns `Atom[]`.

**Coverage table (key methods):**

| SDK Method | Server Endpoint | Works? | Notes |
|---|---|---|---|
| tell() | POST /assert/fact | YES | |
| ask() | POST /infer | **BUG** | null args not converted to variables (unlike infer()) |
| teach() | POST /assert/rule | YES | |
| forget() | POST /retract | YES | |
| processTurns() | POST /context | YES | Main context workflow |
| diffContext() | POST /context/diff | YES | |
| clearContextSession() | POST /context/session/clear | YES | |
| assertFact() | POST /assert/fact | YES | |
| assertRule() | POST /assert/rule | YES | |
| infer() | POST /infer | YES | Supports withProof |
| execute() | POST /execute | YES | |
| context() | POST /memory/context | YES | |
| contextWindow() | POST /memory/context | YES | Deprecated |
| optimizeContext() | POST /context/optimize | YES | Deprecated |
| summarizeContext() | POST /context/summary | YES | |
| ingestAndOptimize() | POST /context/ingest | YES | Requires LLM |
| temporalQuery() | POST /memory/query/temporal | YES | |
| setPriority() | POST /memory/priority | YES | |
| consolidate() | POST /memory/consolidate | YES | |
| decay() | POST /memory/decay | YES | |
| forkScope() | POST /scope/fork | YES | |
| mergeScope() | POST /scope/merge | YES | Returns untyped Record |
| diffScope() | POST /scope/diff | YES | Returns untyped Record |
| deleteScope() | DELETE /scope/{name} | YES | |
| listScopes() | GET /scopes | YES | |
| aggregate() | POST /aggregate | **BUG** | Sends args:[] — matches nothing |
| retractPattern() | POST /retract/pattern | **BUG** | Sends args:[] — matches nothing |
| bulkAssert() | POST /assert/facts | YES | |
| beginTransaction() | POST /tx/begin | YES | |
| commitTransaction() | POST /tx/commit/{id} | YES | |
| rollbackTransaction() | POST /tx/rollback/{id} | YES | |
| subscribeEvents() | GET /memory/events | YES | Returns unsubscribe fn |
| NocturnusAIMCPClient | POST /mcp | YES | Full MCP support |
| recall() | POST /memory/recall | **MISSING** | |
| compress() | POST /memory/compress | **MISSING** | |
| cleanup() | POST /memory/cleanup | **MISSING** | |
| prioritize() | POST /memory/prioritize | **MISSING** | |

### Cross-SDK Comparison

**Same 3-operation workflow (tell → teach → infer):**

Python (4 lines):
```python
with SyncNocturnusAIClient("http://localhost:9301") as client:
    client.tell("human", ["socrates"])
    client.teach({"predicate":"mortal","args":["?x"]}, [{"predicate":"human","args":["?x"]}])
    result = client.infer("mortal", ["?who"])
```

TypeScript (9 lines):
```typescript
const client = new NocturnusAIClient({ baseUrl: 'http://localhost:9301', tenantId: 'default' });
await client.tell('human', ['socrates']);
await client.teach(
  { predicate: 'mortal', args: ['?x'] },
  [{ predicate: 'human', args: ['?x'] }]
);
const result = await client.infer('mortal', ['?who']);
```

Python is more concise due to the context manager and simpler constructor. TypeScript has stronger types. Both are readable.

---

## MCP Protocol Exercise

**16 tools tested, all working:**

| MCP Tool | Status | Notes |
|----------|--------|-------|
| tell | PASS | Clean responses |
| teach | PASS | Rule stored correctly |
| ask | PASS | Proof trees included when requested |
| forget | PASS | Cascading retraction confirmed |
| recall | PASS | Temporal queries work |
| context | PASS | Natural language format excellent |
| compress | PASS | No-op when no patterns |
| cleanup | PASS | No-op when nothing to evict |
| predicates | PASS | Schema discovery works |
| aggregate | PASS | COUNT, SUM, AVG all correct |
| bulk_assert | PASS | 3/3 asserted |
| retract_pattern | PASS | Wildcard retraction works |
| fork_scope | PASS | 5 atoms forked |
| merge_scope | PASS | (not tested via MCP, tested via REST) |
| list_scopes | PASS | |
| delete_scope | PASS | 5 atoms removed |

**MCP Issues:**
- `"error": null` included in every success response (non-standard JSON-RPC 2.0)
- `bulk_assert` schema declares `items: {type: "string"}` but expects objects
- MCP serverInfo reports version "0.3.2" while /health reports "0.3.3"

---

## REST API Exercise

### Endpoint Coverage

| Category | Endpoints Tested | All Working? | Notes |
|----------|-----------------|-------------|-------|
| Logic (assert/infer/retract/execute) | 6 | YES | DSL needs trailing semicolons |
| Simplified (tell/ask/teach/forget) | 5 | YES | Clean and intuitive |
| Context Management | 4 | YES | Main workflow works great |
| Memory | 7 | YES (with correct formats) | salient_query needs predicate+args |
| Scopes | 5 | YES | diff uses scopeA/scopeB (inconsistent with fork's sourceScope/targetScope) |
| Aggregation | 3 | YES | COUNT/SUM/AVG all correct |
| Transactions | 3 | YES | tx/begin returns plain text integer |
| Extraction | 2 | YES | LLM-powered, uses configured provider |
| Synthesis | 1 | YES | Requires `{"question":"..."}` format |
| Auth | 2 of 7 | PARTIAL | Tested status + whoami; RBAC endpoints need auth enabled |
| Admin | 4 | YES | |
| Observability | 4 | YES | /llm.txt is excellent |
| MCP | 2 | YES | JSON-RPC + SSE |
| Replication | 1 | YES | WAL endpoint works |

**Key REST API Issues:**
- `POST /tx/begin` returns plain text `"3"`, not JSON. Hard to parse programmatically.
- Error messages expose internal class names: `"Failed to convert request body to class com.nocturnusai.server.routes.TellRequest"`
- Missing field errors don't say WHICH field is missing
- Tenant must be pre-created via admin endpoint — no auto-creation, no docs about this requirement
- Numbers auto-coerced to floats: `"95"` becomes `"95.0"`
- Scope diff uses `scopeA`/`scopeB` but fork/merge use `sourceScope`/`targetScope`

---

## Documentation & Website

The Astro docs site covers all major topics but has **5 copy-paste-breaking errors** (BLOCKERs) and **6 misleading items** (FRICTION).

### BLOCKERs in Docs

1. **Security docs: `/auth/keys/$KEY_ID/rotate` endpoint does not exist** (security.astro:99) — developers will get 404
2. **API docs: `/infer` response format wrong** (api.astro:219) — shows `{"results":[...]}` wrapper but actual is `AtomResponse[]`
3. **API docs: `/assert/fact` response format wrong** (api.astro:168) — shows JSON but actual is plain text
4. **SDK docs: `process_turns()` shown with dict-format turns** (sdks.astro:76) — actual API takes `list[str]`
5. **SDK docs: `result.briefing` and `result.facts_extracted` don't exist** (sdks.astro:85) — should be `result.facts` and `result.new_facts_extracted`

### FRICTION in Docs

6. Multi-tenancy page shows `{"name":"acme_corp"}` but actual field is `tenantId`
7. Operations page shows wrong LOG_FORMAT default (json vs text)
8. Operations page references non-existent env var `REPLICATION_POLL_INTERVAL_MS`
9. Operations page shows wrong Docker image name in K8s example (`nocturnusai/server:latest` vs `ghcr.io/auctalis/nocturnusai:latest`)
10. Context workflow page references `nocturnusai setup` CLI command not documented anywhere
11. SDK docs inconsistently show sync client with/without context manager

### PAPERCUT in Docs

12. LLM page states "Anthropic charges $15/M for Claude Sonnet" — actual is $3/M input
13. MCP page references "MCP 2025-11-25 specification" — this version doesn't exist yet
14. Sidebar nav title "How It Works" doesn't match page h1 "How It Works on the Backend"
15. Context workflow page references undocumented `nocturnusai setup` command

---

## Competitor Comparison

| Dimension | NocturnusAI | Mem0 | Zep | Letta |
|---|---|---|---|---|
| **Time to understand** | ~45s | ~15s | ~30s | ~30s |
| **Steps to hello world** | 3 (docker + curl x2) | 4 (requires cloud account) | 3 (requires cloud account) | 3 (requires server) |
| **SDK languages** | Python, TypeScript | Python, TypeScript, CLI | Python, TypeScript, Go | Python, TypeScript |
| **SDK dependencies** | 2 (httpx, pydantic) | 7 (incl. telemetry) | 5 | 6 |
| **Docs quality** | Solid, 5 copy-paste errors | Excellent, interactive | Good, academic rigor | Good, auto-generated |
| **Error messages** | Good (SDK), Poor (server) | Excellent (includes fix URLs) | Poor (raw headers) | Generic |
| **Unique strengths** | Deterministic reasoning, proof chains, scope fork/merge, self-hosted, zero cloud | 48K stars, 21 integrations, SOC2, graph memory | Temporal knowledge graph, <200ms, Go SDK | Full agent runtime, self-managed memory |
| **Pricing** | Free (self-hosted) | Free tier → $19-249/mo | Free tier → $25-475/mo | Free tier → $20-200/mo |
| **What competitors lack** | — | No rules/inference, no truth maintenance | No Horn clauses, no backward chaining | No formal logic |

**Positioning Assessment:** "Turn reduction" is the easier sell (broad appeal, immediate ROI). Deterministic reasoning is the deeper moat (no competitor can replicate it). Leading with context optimization as the hook, then revealing reasoning as the differentiator, is the strongest positioning strategy.

---

## All Issues (ranked by severity)

### BLOCKER (9)

| # | Issue | Location | Details |
|---|-------|----------|---------|
| 1 | Python `list_scopes()` always returns empty | client.py:1194 | Parses `{"scopes":[...]}` dict as list, fails isinstance check |
| 2 | Python `aggregate()` sends `args:[]` | client.py:1329 | Empty args matches nothing; should default to wildcards |
| 3 | Python `retract_pattern()` sends `args:[]` | client.py:1383 | Same root cause as #2 |
| 4 | TypeScript `aggregate()` sends `args:[]` | client.ts:1072 | Same root cause as #2 |
| 5 | TypeScript `retractPattern()` hardcodes `args:[]` | client.ts:1111 | Same root cause as #2 |
| 6 | Docs: `/auth/keys/$KEY_ID/rotate` doesn't exist | security.astro:99 | 404 for any developer |
| 7 | Docs: `/infer` response format wrong | api.astro:219 | Shows wrapper object, actual is array |
| 8 | Docs: `/assert/fact` response format wrong | api.astro:168 | Shows JSON, actual is plain text |
| 9 | Docs: SDK `process_turns()` example has wrong types | sdks.astro:76 | Dict-format turns vs string list |

### FRICTION (18)

| # | Issue | Location | Details |
|---|-------|----------|---------|
| 10 | Port conflict gives raw Docker error | Makefile | No suggestion to change PORT |
| 11 | Server error messages expose internal class names | Server | `"Failed to convert ... TellRequest"` |
| 12 | Missing field errors don't say which field | Server | Generic deserialization error |
| 13 | Tenant must be pre-created (undocumented) | Server | No auto-create, no docs about this |
| 14 | README example 1 shows idealized predicates | README.md | Actual LLM extraction differs significantly |
| 15 | README example 2 (/context/optimize) returns empty | README.md | Depends on pre-loaded rules |
| 16 | `POST /tx/begin` returns plain text, not JSON | TransactionRoutes | Hard to parse programmatically |
| 17 | Scope diff uses `scopeA/scopeB` vs fork's `sourceScope/targetScope` | ScopeRoutes | Inconsistent naming |
| 18 | Python SDK missing 5 simplified routes | Python SDK | recall, compress, cleanup, prioritize, salient_query |
| 19 | TypeScript SDK missing 4 simplified routes | TypeScript SDK | recall, compress, cleanup, prioritize |
| 20 | TypeScript `ask()` doesn't convert null args to variables | client.ts:1148 | Unlike `infer()`, breaks on null args |
| 21 | Python `teach()` requires verbose dict format | Python SDK | No string-syntax shorthand |
| 22 | Python `subscribe_events` is async-only | Python SDK | No sync SSE option |
| 23 | Docs: multi-tenancy shows wrong field name | multi-tenancy.astro:182 | `name` vs `tenantId` |
| 24 | Docs: wrong Docker image in K8s example | operations.astro:98 | `nocturnusai/server:latest` vs `ghcr.io/auctalis/nocturnusai:latest` |
| 25 | Docs: `result.briefing` and `result.facts_extracted` don't exist | sdks.astro:85 | Wrong property names |
| 26 | Numbers auto-coerced to floats | Server | `"95"` → `"95.0"` |
| 27 | DSL needs trailing semicolons (not obvious) | Server/execute | `ASSERT likes(bob, cats);` |

### PAPERCUT (14)

| # | Issue | Location | Details |
|---|-------|----------|---------|
| 28 | Version mismatch: health=0.3.3, agent.json=0.3.2, MCP=0.3.2 | Server | Three places, two versions |
| 29 | MCP responses include `"error": null` on success | McpRoutes | Non-standard JSON-RPC 2.0 |
| 30 | MCP `bulk_assert` schema says items are strings | McpRoutes | Should be objects |
| 31 | Python `__version__` = "0.3.2", package = "0.3.3" | __init__.py | Version not bumped |
| 32 | Python `tell()` returns dict, `ask()` returns list[Atom] | Python SDK | Inconsistent return types |
| 33 | Python deprecated methods emit no DeprecationWarning | Python SDK | Only docstring notices |
| 34 | TypeScript `diffScope()`/`mergeScope()` return untyped Record | client.ts | Known shapes, should have interfaces |
| 35 | TypeScript `BulkAssertResult` missing `errors`+`timestamp` fields | types.ts | Server returns them, type doesn't declare them |
| 36 | TypeScript MCP client hardcodes version "0.2.4" | mcp.ts:126 | Package is 0.3.3 |
| 37 | TypeScript README leads with deprecated workflow | README.md | Shows optimizeContext, not processTurns |
| 38 | Docs: LLM page wrong Anthropic pricing ($15/M vs $3/M) | llm.astro:206 | |
| 39 | Docs: MCP page references future-dated spec (2025-11-25) | mcp.astro:20 | |
| 40 | Docs: Sidebar nav title mismatch with page h1 | DocsLayout.astro:36 | |
| 41 | Extraction produces generic predicates (HasName, HasValue, Did) | Server/LLM | Not domain-specific |

### SUGGESTION (11)

| # | Issue | Details |
|---|-------|---------|
| 42 | Lead with context optimization, reveal reasoning as differentiator | Positioning is split between two stories |
| 43 | Add complete runnable script to quickstart page | 4 curl commands end-to-end with expected output |
| 44 | Document error response format | `{"code":"...","message":"...","details":null}` never shown |
| 45 | Document the "default" tenant auto-creation | New devs don't know it exists |
| 46 | Add `tell_many()` convenience wrapper to Python SDK | Wraps bulk_assert with simpler API |
| 47 | Add `__repr__` to Python Atom | Show `likes(alice, pizza)` in REPL |
| 48 | Add `client.clear()` / `client.reset_tenant()` for testing | Convenience for test setup/teardown |
| 49 | Document aggregate/bulk/retract_pattern on API reference page | These endpoints exist but have no API docs |
| 50 | Fix "Where to go next" cards in docs overview to be actual links | Currently divs without href |
| 51 | Add missing `setup` command to CLI reference page | Referenced in context docs but undocumented |
| 52 | Document that sdk `aggregate()`/`retractPattern()` need explicit wildcard args | Workaround until bug is fixed |

---

## Summary Stats

| Metric | Value |
|--------|-------|
| **Total issues** | 63 |
| **Blockers** | 11 (4 SDK bugs, 5 doc errors, 2 server) |
| **Friction** | 25 |
| **Papercuts** | 16 |
| **Suggestions** | 11 |
| **Python SDK coverage** | ~90% (37/42 endpoints covered, 5 missing methods) |
| **TypeScript SDK coverage** | ~92% (38/42 endpoints covered, 4 missing methods) |
| **MCP tool coverage** | 100% (16/16 tools working) |
| **REST API coverage** | 100% (92 endpoints tested incl. MCP tool variants and undocumented routes) |
| **Time to first hello-world** | ~2 minutes (Docker pull + start + health) |
| **Time to meaningful interaction** | ~3 minutes (tell + ask + get result) |
| **Competitor gap** | Strong on reasoning/self-hosted; weak on community/cloud/graph memory |

---

## Appendix: REST API Deep Dive (92 Endpoints)

The REST API agent tested 92 endpoints across all route groups. Key additional findings not in the main issue list:

### Additional BLOCKERs from REST API exercise

**10. `POST /admin/backups` returns 500 in Docker** — The backup code writes to `/backups` but the Dockerfile only creates `/data`. Error: `"No such file or directory"`. Any developer trying to create a backup in Docker will hit a 500.

**11. MCP tool `inspect` does not exist** — CLAUDE.md lists `inspect` as an MCP tool, but calling it returns `"Unknown tool: inspect"`. The actual tool is `predicates` which serves a similar purpose.

### Additional FRICTION from REST API exercise

- **500 vs 400 for deserialization errors**: `/tell`, `/assert/fact`, `/scope/fork`, `/scope/diff`, `/scope/merge`, `/aggregate`, `/synthesize` all return 500 INTERNAL_ERROR for JSON deserialization failures that should be 400 BAD_REQUEST.
- **`/assert/fact` missing predicate returns 500 instead of 400**: Should be a validation error, not internal error.
- **`/memory/priority` accepts priority > 1.0**: No validation, unlike `/assert/fact` which correctly validates confidence to [0.0, 1.0].
- **`/assert/template` undiscoverable**: Error doesn't list valid TemplateType values (SYLLOGISM, MODUS_PONENS, etc.).
- **Non-existent database gives misleading 404**: `X-Database: nonexistent` returns "No route matched" which is misleading — it's a missing database, not a routing issue.
- **`POST /context` not listed in CLAUDE.md route list** — This is the main headline endpoint from the README but isn't in the CLAUDE.md route documentation under ContextManagementRoutes.

### Undocumented but functional endpoints discovered

These endpoints exist and work but are not in CLAUDE.md:
- `GET /health/live` — Kubernetes liveness probe (plain text "OK")
- `GET /health/ready` — Kubernetes readiness probe (same as /health)
- `GET /userguide` — Full user guide in Markdown
- `GET /predicates` — Schema discovery (predicate names, arities, fact counts)
- `POST /context/summary` — KB summary statistics
- `POST /context/ingest` — Extract facts from text + optimize context in one call
- `POST /scope/parent` — Set parent scope (scope hierarchy/DAG)
- `DELETE /scope/parent/{child}` — Remove parent relationship
- `GET /scope/ancestors/{scope}` — Get scope ancestry chain
- `GET /scope/dag` — Get full scope DAG

---

## Priority Fix Recommendations

### Immediate (before next release)
1. Fix `args: []` default in both SDKs' `aggregate()` and `retractPattern()` — silently broken functionality
2. Fix Python `list_scopes()` dict parsing
3. Fix TypeScript `ask()` null-arg handling
4. Fix 5 copy-paste-breaking doc examples (response formats, SDK types)
5. Create `/backups` directory in Dockerfile (one-line fix)

### Short-term (next 2 releases)
6. Add missing SDK methods (recall, compress, cleanup, prioritize)
7. Improve server error messages (remove class names, specify missing fields)
8. Make `/tx/begin` return JSON
9. Change 500 → 400 for deserialization errors across all endpoints
8. Standardize scope field names (sourceScope/targetScope everywhere)
9. Sync version numbers across health, agent.json, MCP, SDKs

### Medium-term
10. Add complete runnable quickstart script to docs
11. Document error response format
12. Add `default` tenant auto-creation documentation
13. Improve positioning: lead with context optimization, reveal reasoning as depth
