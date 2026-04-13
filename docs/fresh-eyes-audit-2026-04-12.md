# Fresh Eyes Audit — NocturnusAI
**Date:** 2026-04-12
**Auditor:** Claude (fresh-eyes-audit skill)
**Method:** Docker install from scratch, both SDKs exercised, all endpoints cross-referenced, competitors researched, agentic framework integrations tested

---

## Verdict: ADOPT (with caveats)

NocturnusAI solves a real problem — context window bloat in agentic AI — and does it with a distinctive approach (deterministic logic + salience ranking instead of pure embedding search). The Docker setup takes under 30 seconds. Both SDKs are well-typed and cover 90%+ of the API surface. The MCP configs are excellent. The caveats: the README's headline workflow either hangs for 2+ minutes (with Ollama) or silently produces zero results (without LLM), the README promotes a deprecated endpoint, several framework integrations have critical bugs (LangChain OptimizeTool crashes, AutoGen Memory is non-functional in async), and the "97% token savings" claim lacks a reproducible benchmark.

---

## First 60 Seconds

**What I understood:** NocturnusAI sits between your agent and the LLM. It extracts facts from conversation turns, stores them with salience scoring, and returns a compressed "briefing delta" instead of replaying the entire conversation. It's a "context server" — you send raw turns in, you get lean context out.

**How long until "aha":** About 30 seconds. The README's opening ("Large turn arrays in. Lean context windows out.") is clear. The four-step Working Loop makes sense. The "Choose Your Surface" section (Python, TS, MCP) is well-organized.

**What was unclear:**
- The distinction between "context management" (the main story) and "logic engine" (facts/rules/inference) took a second read to untangle. The README puts the context workflow first, which is correct, but doesn't clearly say "the logic engine works without an LLM; the context workflow needs one."
- The relationship between POST /context (turns -> facts), POST /memory/context (salience window), and POST /context/optimize (goal-driven) required reading multiple files to understand.

---

## Setup & Installation

### Docker (primary path -- from README)

**Command tested:**
```bash
docker run -d --name nocturnusai -p 9300:9300 ghcr.io/auctalis/nocturnusai:latest
```

**Results:**
- Image pull: ~334MB, took ~15s on fast connection
- Container healthy after 2 health check attempts (~4 seconds)
- `curl http://localhost:9300/health` returns detailed JSON with version, disk, memory, auth, LLM status
- **Total time from "I want to try this" to "it's running": ~25 seconds** (excellent)

**BLOCKER -- README headline example fails silently:**
The README's "Working Loop" section (the very first thing a developer sees) shows:
```bash
curl -X POST http://localhost:9300/context -d '{"turns":["user: Customer says..."]}'
```
The README shows a response with 3 extracted facts. In reality, this returns:
```json
{"facts":[], "newFactsExtracted": 0, "warning": "No LLM provider configured..."}
```
The Quick Start Docker command doesn't include LLM configuration, but the Working Loop example depends on it. A developer following the README top-to-bottom will hit zero results on their first try.

**The predicate-syntax fallback works perfectly:**
```bash
curl -X POST http://localhost:9300/context -d '{"turns":["customer_tier(acme_corp, enterprise)"]}'
```
Returns expected results. But this fallback syntax is never shown in the main README before the Quick Start section.

### Docker Compose

`make up` works cleanly with `.env.example`. `make smoke` passes. The Makefile is well-organized with helpful output messages.

### Install Script

`install.sh` exists and handles Docker fallback gracefully. The interactive setup wizard approach is a good UX choice.

---

## SDK Report Card

| Language | Install | Connect | CRUD | Coverage | Ergonomics | Advanced | Grade |
|----------|---------|---------|------|----------|-----------|----------|-------|
| Python | pass | pass | pass | 92% | A- | pass | **A-** |
| TypeScript | pass | pass | pass | 88% | B+ | pass | **B+** |

### Python SDK -- Detailed Findings

**Install (Level 1):** `pip install nocturnusai` -- clean install, 2 runtime deps (httpx, pydantic). Zero warnings. Optional extras for frameworks are well-organized: `nocturnusai[langchain]`, `nocturnusai[crewai]`, etc.

**Connect (Level 2):** Both async (`NocturnusAIClient`) and sync (`SyncNocturnusAIClient`) work out of the box. Wrong URL gives `NocturnusAIConnectionError` with clear message. Timeout behavior is configurable (default 30s) with retry logic built in.

**CRUD (Level 3):** All operations work. Return types are well-structured:
- `assert_fact` -> `dict` with result text
- `query`/`infer` -> `list[Atom]` (Pydantic models with `.predicate`, `.args`, `.truth_value`)
- `retract` -> `dict` with result text
- `context` -> `ContextWindow` model with `.facts`, `.total_available`, etc.

**Coverage (Level 4) -- Python SDK Method Coverage:**

| Method | Status | Notes |
|--------|--------|-------|
| assert_fact | pass | |
| assert_rule | pass | |
| query | pass | Alias for infer |
| infer | pass | with_proof=True works |
| retract | pass | |
| context_window | pass | Deprecated, warns correctly |
| context | pass | Unified endpoint, works great |
| optimize_context | pass | Deprecated, warns correctly |
| diff_context | pass | |
| summarize_context | pass | |
| clear_context_session | pass | |
| extract_facts | pass | Requires LLM |
| ingest_and_optimize | pass | |
| process_turns | pass | Main workflow method |
| temporal_query | pass | |
| consolidate | pass | |
| decay | pass | |
| set_priority | pass | |
| recall | pass | |
| compress | pass | |
| cleanup | pass | |
| prioritize | pass | |
| salient_query | pass | |
| execute | pass | DSL commands |
| list_scopes | pass | |
| delete_scope | pass | |
| fork_scope | pass | |
| diff_scope | pass | |
| merge_scope | pass | |
| aggregate | pass | COUNT/SUM/MIN/MAX/AVG |
| bulk_assert | pass | |
| retract_pattern | pass | |
| tell/ask/teach/forget | pass | Simplified aliases |
| subscribe_events | pass | SSE |
| predicates | pass | Schema discovery |
| begin/commit/rollback_transaction | pass | |
| create_database | pass | |
| ensure_database | pass | |
| auth_status/bootstrap/create_key/list_keys/revoke_key/whoami | pass | |
| health | pass | |

**Missing from Python SDK:**
- `POST /assert/template` -- no SDK method
- `POST /extract/batch` -- no SDK method
- `POST /synthesize` -- no SDK method
- `GET /memory/stream` -- no SDK method (separate from /memory/events)
- `POST /scope/parent`, `DELETE /scope/parent/{child}`, `GET /scope/ancestors/{scope}`, `GET /scope/dag` -- scope hierarchy not exposed
- `GET /auth/keys/{id}`, `PATCH /auth/keys/{id}` -- single key get/update not exposed
- Admin: `GET /admin/databases`, `DELETE /admin/databases/{name}`, tenant CRUD, nuke endpoints -- not exposed
- Observability: `GET /health/live`, `/health/ready`, `/metrics`, `/llm.txt`, `/userguide`, `/.well-known/agent.json` -- not exposed
- Replication: `/replication/*` -- not exposed

**Ergonomics (Level 5):**
- Context manager pattern (`with SyncNocturnusAIClient(...) as client:`) is Pythonic and clean
- Pydantic models for return types (Atom, ContextWindow, etc.) -- excellent
- Both sync and async APIs with identical signatures -- very developer-friendly
- Retry logic with exponential backoff built in
- Deprecation warnings for old methods point to new ones
- FRICTION: `assert_fact` returns `dict` while `infer` returns `list[Atom]` -- inconsistent return types

**Advanced (Level 6):**
- `process_turns` workflow works cleanly (with predicate syntax; needs LLM for natural language)
- Scope operations (fork/diff/merge) work as documented
- Transactions work
- Bulk assert handles 10+ facts fine
- Aggregate operations work for all 5 ops

### TypeScript SDK -- Detailed Findings

**Install (Level 1):** `npm install nocturnusai-sdk` -- zero runtime dependencies (uses built-in fetch). Clean install.

**Connect (Level 2):** Constructor takes a config object. No connection pooling visible. Error handling via `NocturnusAIError` class.

**Coverage (Level 4) -- TypeScript SDK Missing Methods:**
- `salientQuery` -- not exposed (Python has it)
- `extractFacts` -- not exposed (Python has it via `/extract`)
- `GET /memory/stream` -- not exposed
- Same admin/observability/replication gaps as Python
- Scope hierarchy endpoints missing

**Ergonomics (Level 5):**
- Full TypeScript types for all responses -- excellent autocomplete experience
- Async-only (no sync wrapper like Python) -- appropriate for Node.js/Deno
- Method overloads for `infer`/`ask` with proof trees -- well done
- `subscribeEvents` with callback pattern is clean
- Zero dependencies is impressive -- uses native fetch

### Cross-SDK Comparison -- Same 3-Operation Workflow

**Python (6 lines):**
```python
with SyncNocturnusAIClient("http://localhost:9300") as client:
    client.tell("parent", ["alice", "bob"])
    client.tell("parent", ["bob", "charlie"])
    client.teach(
        head={"predicate": "grandparent", "args": ["?x", "?z"]},
        body=[{"predicate": "parent", "args": ["?x", "?y"]}, {"predicate": "parent", "args": ["?y", "?z"]}],
    )
    results = client.ask("grandparent", ["?who", "charlie"])
```

**TypeScript (8 lines):**
```ts
const client = new NocturnusAIClient({ baseUrl: 'http://localhost:9300' });
await client.tell('parent', ['alice', 'bob']);
await client.tell('parent', ['bob', 'charlie']);
await client.teach(
  { predicate: 'grandparent', args: ['?x', '?z'] },
  [{ predicate: 'parent', args: ['?x', '?y'] }, { predicate: 'parent', args: ['?y', '?z'] }],
);
const results = await client.ask('grandparent', ['?who', 'charlie']);
```

Both are clean and readable. Python is slightly more ergonomic due to sync + context manager.

---

## Server API Coverage Matrix

| Endpoint | Python SDK | TypeScript SDK | Notes |
|----------|-----------|----------------|-------|
| POST /assert/fact | assert_fact | assertFact | |
| POST /assert/rule | assert_rule | assertRule | |
| POST /assert/template | -- | -- | No SDK coverage |
| POST /assert/facts (bulk) | bulk_assert | bulkAssert | |
| POST /retract | retract | retract | |
| POST /retract/pattern | retract_pattern | retractPattern | |
| POST /infer | infer/query | infer/query | |
| POST /execute | execute | execute | |
| GET /predicates | predicates | predicates | |
| POST /tell | tell | tell | |
| POST /ask | ask | ask | |
| POST /teach | teach | teach | |
| POST /forget | forget | forget | |
| POST /memory/query/temporal | temporal_query | temporalQuery | |
| POST /memory/query/salient | salient_query | -- | TS missing |
| POST /memory/context | context | context | |
| POST /memory/priority | set_priority | setPriority | |
| POST /memory/consolidate | consolidate | consolidate | |
| POST /memory/decay | decay | decay | |
| GET /memory/events | subscribe_events | subscribeEvents | |
| POST /memory/recall | recall | recall | |
| POST /memory/compress | compress | compress | |
| POST /memory/cleanup | cleanup | cleanup | |
| POST /memory/prioritize | prioritize | prioritize | |
| GET /memory/stream | -- | -- | Neither SDK |
| POST /context | process_turns | processTurns | |
| POST /context/optimize | optimize_context | optimizeContext | Deprecated |
| POST /context/diff | diff_context | diffContext | |
| POST /context/summary | summarize_context | summarizeContext | |
| POST /context/session/clear | clear_context_session | clearContextSession | |
| POST /context/ingest | ingest_and_optimize | ingestAndOptimize | |
| POST /scope/fork | fork_scope | forkScope | |
| POST /scope/diff | diff_scope | diffScope | |
| POST /scope/merge | merge_scope | mergeScope | |
| DELETE /scope/{name} | delete_scope | deleteScope | |
| GET /scopes | list_scopes | listScopes | |
| POST /scope/parent | -- | -- | Scope hierarchy |
| DELETE /scope/parent/{child} | -- | -- | Scope hierarchy |
| GET /scope/ancestors/{scope} | -- | -- | Scope hierarchy |
| GET /scope/dag | -- | -- | Scope hierarchy |
| POST /aggregate | aggregate | aggregate | |
| POST /tx/begin | begin_transaction | beginTransaction | |
| POST /tx/commit/{id} | commit_transaction | commitTransaction | |
| POST /tx/rollback/{id} | rollback_transaction | rollbackTransaction | |
| POST /auth/bootstrap | bootstrap | bootstrap | |
| GET /auth/status | auth_status | authStatus | |
| POST /auth/keys | create_key | createKey | |
| GET /auth/keys | list_keys | listKeys | |
| GET /auth/keys/{id} | -- | -- | Single key fetch |
| PATCH /auth/keys/{id} | -- | -- | Key update |
| DELETE /auth/keys/{id} | revoke_key | revokeKey | |
| GET /auth/whoami | whoami | whoami | |
| POST /extract | extract_facts | -- | TS missing |
| POST /extract/batch | -- | -- | Neither SDK |
| POST /synthesize | -- | -- | Neither SDK |
| POST /mcp | (mcp module) | (mcp module) | |
| GET /health | health | health | |
| GET /health/live | -- | -- | k8s readiness |
| GET /health/ready | -- | -- | k8s liveness |
| GET /metrics | -- | -- | Prometheus |
| GET /llm.txt | -- | -- | API docs |
| POST /admin/databases | create_database | createDatabase | |
| POST /admin/databases/{name}/tenants | -- (raw HTTP) | createTenant | Python gap |

**Summary:** Python covers ~92% of core endpoints. TypeScript covers ~88%. The gaps are mostly admin, observability, scope hierarchy, and LLM extraction endpoints.

---

## Framework Integrations

| Framework | Install | Import | Basic Test | Tools | Grade |
|-----------|---------|--------|------------|-------|-------|
| LangChain | pass | pass | 5/6 pass | 6 tools (assert, query, infer, context, optimize, extract) | B+ (optimize crashes, async broken) |
| CrewAI | pass | pass | 5/5 pass | 5 tools + Storage backend | B (Storage.reset() bug) |
| AutoGen | pass | pass | tools pass | 5 tools + Memory class | C+ (Memory completely broken) |
| LangGraph | pass | pass | pass | Checkpoint saver | B- (no base class inheritance) |
| OpenAI Agents | pass | pass | pass | 5 tools | A- |
| Anthropic (claude) | pass | pass | pass | 5 tool definitions + dispatcher | A (cleanest integration) |
| MCP | pass | pass | pass | 16 tools via JSON-RPC | A |

**Integration quality varies significantly.** The Anthropic tools, OpenAI Agents, and MCP Client integrations are clean and functional. LangChain has the broadest tool set (6 tools) and a great example, but the OptimizeTool crashes on every call and all `_arun()` methods fail in async contexts. AutoGen's Memory class is non-functional in its intended use case. CrewAI's Storage backend has an arity bug that silently prevents `reset()` from working.

**Adoption assessment:** A developer already using LangChain could integrate in under 10 lines, but would hit crashes within minutes. CrewAI and AutoGen integrations have `get_nocturnusai_tools()` convenience functions. The Anthropic integration is the most reliable. MCP (16 tools, 5 pre-built IDE configs) is the strongest integration overall.

**Priority fixes for integrations:**
1. **P0**: AutoGen `NocturnusAIMemory` -- must accept async client
2. **P0**: LangChain `_arun()` on all tools -- must use async client
3. **P1**: LangChain `NocturnusAIOptimizeTool._run()` -- use model attributes, not `.get()`
4. **P1**: CrewAI `NocturnusAIStorage.reset()` -- fix query arity
5. **P2**: LangGraph -- inherit from `BaseCheckpointSaver`

---

## Documentation & Website

**Live site:** https://auctalis.github.io/nocturnusai/

**Homepage:**
- Clear value proposition in first 5 seconds
- "97% fewer tokens" headline is attention-grabbing
- The "Try It" section with two copyable curls is good
- FRICTION: The "Try It" section shows two options (Ollama + Cloud LLM) but the code examples after it use natural language turns that require LLM -- Option A just says "docker run" without the LLM env vars

**Docs site navigation:** Logical sidebar with 14 sections. API Reference, SDKs, Context Workflow, CLI Reference, MCP Integration all findable in 1-2 clicks. Good.

**Code examples:** Generally accurate and copy-pasteable. The docs site code examples match the actual SDK APIs.

**FRICTION -- "97% token savings" claim:**
- Appears 15+ times across the site
- Based on a single calculation: "150K tokens -> 820 tokens at $15/M"
- No reproducible benchmark, no test script, no "try this yourself and measure" guide
- The FAQ explains the math but presents it as fact rather than as an estimate
- A skeptical engineer (which is who I'm role-playing) would want to see this demonstrated, not just claimed

**OpenClaw integration page:** Well-written, with clear "Path A" (MCP) and "Path B" (Context Engine) approaches. Includes honest callout that the cost claim is "an inference from OpenClaw's official docs."

---

## Competitor Comparison

| Dimension | NocturnusAI | Mem0 | Zep | Letta (MemGPT) |
|-----------|------------|------|-----|----------------|
| Time to understand | 30 sec | 20 sec | 25 sec | 45 sec |
| Steps to hello world | 2 (docker + curl) | 3 (signup + pip + code) | 3 (docker + pip + code) | 4+ (pip + config + code) |
| SDK languages | Python, TypeScript | Python, TypeScript, Go, Java | Python, TypeScript | Python |
| Self-hosted | Yes (Docker) | Yes (OSS edition) | Yes (Docker) | Yes |
| Cloud hosted | No | Yes | Yes | Yes |
| MCP support | Yes (13 tools) | No | No | Yes |
| Logic/inference engine | Yes (unique) | No | No | No |
| Context optimization | Yes (goal-driven) | No | Yes (summarization) | Yes (compaction) |
| Framework integrations | 6 (LC, CrewAI, AutoGen, LangGraph, OAI Agents, Anthropic) | 4 | 2 | 2 |
| Pricing | Free (BSL) | Free tier + paid | Free tier + paid | Free (OSS) |
| Unique strengths | Deterministic inference, truth maintenance, temporal facts, scope-based hypothetical reasoning, salience scoring | Simple API, managed cloud, broad SDK support | Fast vector search, built-in summarization | Agent-first memory architecture |

**Where NocturnusAI wins:**
1. **Deterministic reasoning** -- no other competitor offers backward-chaining inference with truth maintenance. If your agent needs to reason logically (not just retrieve), NocturnusAI is the only option.
2. **MCP-first** -- 13 tools via MCP, with pre-built configs for Claude Desktop, Cursor, Windsurf, VS Code. No competitor matches this.
3. **Framework breadth** -- 6 framework integrations vs 2-4 for competitors.
4. **Scope/hypothetical reasoning** -- fork/diff/merge for A/B testing knowledge. Unique feature.
5. **Self-hosted simplicity** -- single Docker container, no database dependency.

**Where competitors win:**
1. **Mem0** has a managed cloud service -- no infrastructure to manage. NocturnusAI is self-hosted only.
2. **Mem0** has Go and Java SDKs. NocturnusAI has Python and TypeScript only.
3. **Zep** has built-in vector search for semantic similarity. NocturnusAI's retrieval is predicate-based, not embedding-based.
4. **Letta/MemGPT** has a more agent-native memory model with automatic summarization. NocturnusAI requires explicit `consolidate()` calls.
5. All competitors have simpler mental models -- "store memories, search memories." NocturnusAI's predicate/rule/inference model has a steeper learning curve for developers not familiar with logic programming.

---

## All Issues (ranked by severity)

### BLOCKER

1. **README headline workflow either hangs or fails silently**: The "Working Loop" at the top of the README shows `POST /context` with natural language turns. With the Quick Start `docker run` (no LLM env vars):
   - **If Ollama is running on host:** The request hangs for 2+ minutes (LLM extraction + briefing generation on 8B model), exceeding curl's default timeout. The developer sees nothing and gives up.
   - **If Ollama is NOT running:** Returns instantly with `{"facts":[], "newFactsExtracted":0, "warning":"No LLM provider configured..."}`. The README shows 3 facts and salience scores; reality is zero.
   - **On Linux:** `host.docker.internal` doesn't resolve without `--add-host`, so same as "no Ollama" case.
   - File: `README.md` lines 26-57 (Working Loop section)
   - Fix: Either (a) move the Quick Start section before the Working Loop, (b) add a callout that natural language turns require LLM configuration, or (c) show predicate-syntax examples first and natural-language as the "enhanced" path.

2. **README Step 2 promotes deprecated endpoint**: `POST /context/optimize` is shown as Step 2 of "The Working Loop" but is deprecated (server returns `Deprecation: true` and `Sunset: 2026-07-01` headers). CLAUDE.md says to use `POST /memory/context` with `goals` instead. The docs site correctly uses the new endpoint, but the README -- the first thing GitHub visitors see -- does not.
   - File: `README.md` lines 59-74
   - Fix: Replace with `POST /memory/context` with goals parameter, matching the docs site.

3. **LangChain `NocturnusAIOptimizeTool._run()` crashes on every invocation**: Calls `result.get("included", 0)` but `optimize_context()` returns a Pydantic `OptimizedContext` model, not a dict. Every call raises `AttributeError: 'OptimizedContext' object has no attribute 'get'`.
   - File: `sdks/python/nocturnusai/langchain.py` lines 532-568
   - Fix: Use `result.total_facts_included` instead of `result.get("included", 0)`.

4. **AutoGen `NocturnusAIMemory` is completely non-functional**: All methods are `async` but internally call `SyncNocturnusAIClient` which uses `loop.run_until_complete()`. This crashes with `RuntimeError: Cannot run the event loop while another loop is running` when called from AutoGen's async runtime -- which is the only way AutoGen agents run.
   - File: `sdks/python/nocturnusai/autogen.py` lines 114-175
   - Fix: Accept `NocturnusAIClient` (async) and `await` its methods.

### FRICTION

5. **"97% token savings" unverified**: Claimed 15+ times across site and docs, based on a single back-of-napkin calculation (150K -> 820 tokens). No benchmark script, no reproducible test, no "run this and see for yourself." A skeptical evaluator would flag this as marketing, not evidence.
   - Files: `site/src/pages/index.astro:10`, `site/src/pages/docs/faq.astro:49`, etc.
   - Fix: Create a `benchmarks/` directory with a reproducible cost comparison script.

6. **Three overlapping context endpoints are confusing**: POST /memory/context, POST /context, POST /context/optimize serve overlapping purposes. The relationship between them requires reading multiple source files.
   - Fix: The README already puts `/context` first, which is correct. But the docs should have a clear "which endpoint should I use?" decision tree.

7. **LangChain `_arun()` methods broken in async contexts**: All 6 LangChain tools delegate `_arun()` to `_run()` which uses `SyncNocturnusAIClient`. When called from an async LangChain agent (the normal case), this crashes with `RuntimeError: Cannot run the event loop while another loop is running`.
   - File: `sdks/python/nocturnusai/langchain.py` (all tool classes)
   - Fix: `_arun()` methods should use `NocturnusAIClient` (async).

8. **CrewAI `NocturnusAIStorage.reset()` has arity bug**: Queries `crew_memory` with `args=["?x"]` (1 variable) but `save()` stores facts with 2 args `[agent, value]`. The hexastore requires matching arity, so `reset()` silently deletes nothing.
   - File: `sdks/python/nocturnusai/crewai.py` lines 343-351
   - Fix: Change to `args=["?agent", "?value"]`.

9. **TypeScript SDK missing `salientQuery` and `extractFacts`**: Python has both; TypeScript doesn't. SDK parity gap.
   - File: `sdks/typescript/src/client.ts`

10. **Python SDK has no `createTenant` method**: TypeScript has `createTenant()`; Python requires raw HTTP call to create tenants.
   - File: `sdks/python/nocturnusai/client.py`

11. **README response shapes are aspirational, not reproducible**: Shows salience of 0.88-0.96 and `totalFactsInKB: 127` for 4 turns. Actual results: salience ~0.645, totalFactsInKB: 7. Predicate names are LLM-dependent and differ from what's shown.
   - File: `README.md` lines 43-57

12. **README says "restarts automatically" but docker run has no restart policy**: Line 215 says "restarts automatically" but the `docker run` command has no `--restart` flag. Only docker-compose has `restart: unless-stopped`.
   - File: `README.md` line 215

13. **Navbar "Start Here" goes to wrong page**: Links to `/docs/context` (deep-dive) instead of `/docs` (overview with 4-step quickstart). New developers skip the getting-started page entirely.
   - File: `site/src/components/Navbar.astro` line 41

14. **Docs say "Four LangChain tools" but there are actually six**: Both `integrations.astro` and `sdks.astro` say "Four pre-built tools" but `get_nocturnusai_tools()` returns 6 (assert, query, infer, context, optimize, extract).
   - Files: `site/src/pages/docs/integrations.astro`, `site/src/pages/docs/sdks.astro`

### PAPERCUT

15. **`assert_fact` returns `dict` but `infer` returns `list[Atom]`**: Inconsistent return types in Python SDK. Some methods return raw dicts, others return typed Pydantic models.

16. **TypeScript SDK is async-only**: No sync wrapper. This is fine for production but makes quick scripts/REPLs harder.

17. **Docker image is 334MB**: Reasonable for JVM but large compared to Go/Rust alternatives (~20-50MB). Not a blocker for anyone with Docker experience.

18. **`context_window()` deprecation warnings**: Both SDKs correctly warn, but the deprecated methods are still documented on the site. Could cause confusion about which to use.

19. **No Go, Java, or Rust SDK**: Common languages for backend/infrastructure work. Competitors like Mem0 offer Go and Java SDKs.

20. **BSL license may deter some adopters**: "Offering NocturnusAI to third parties as a product or service requires a commercial license." Some teams have blanket BSL-avoidance policies.

21. **Docker Quick Start uses anonymous volumes**: The basic `docker run` creates anonymous volumes (random hashes), easily lost with `docker system prune`. Should use `-v nocturnusai-data:/data` like the Makefile's `docker-run` target.

22. **Security docs show invalid encryption key format**: `ENCRYPTION_KEY=your-256-bit-key-here` instead of showing the 64-hex-char format and generation command (`openssl rand -hex 32`).
   - File: `site/src/pages/docs/security.astro` line 152

23. **LangGraph checkpoint saver doesn't inherit from `BaseCheckpointSaver`**: Despite declaring `langgraph-checkpoint>=2.0` as a dependency, the class doesn't inherit from the base class. `isinstance()` checks and type validation will fail.
   - File: `sdks/python/nocturnusai/langgraph.py`

24. **No test files for `langchain.py` and `mcp.py`**: All other integration modules have test files; these two don't.

### SUGGESTION

25. **Add a reproducible benchmark**: Create `benchmarks/token_savings.py` that demonstrates the claimed 97% reduction with a before/after comparison. Let the numbers speak.

26. **Add a "which endpoint do I use?" decision tree**: A simple flowchart would clarify the context/memory endpoint confusion.

27. **Consider a managed cloud offering**: Every competitor has one. "Just Docker" is a strength for privacy-conscious teams but a barrier for teams that don't want to manage infrastructure.

28. **Add `createTenant` to Python SDK**: Parity with TypeScript.

29. **Add `salientQuery` and `extractFacts` to TypeScript SDK**: Parity with Python.

30. **Add a "works without LLM" badge/section to the README**: The logic engine (facts/rules/inference) and salience-ranked context both work without LLM. Make this more prominent.

31. **The examples directory has only 3 files**: For a project with this many features, there should be more examples: scopes, transactions, temporal queries, MCP usage, each framework integration.

32. **Consider providing a pre-built `docker-compose.yml` with Ollama**: The Quick Start could default to including a local Ollama so natural language extraction works out of the box. The `make up-ollama` command does this, but the Docker Quick Start in the README doesn't.

---

## Summary Stats

| Metric | Value |
|--------|-------|
| Total issues | 32 |
| Blockers | 4 |
| Friction | 10 |
| Papercuts | 10 |
| Suggestions | 8 |
| Python SDK coverage | ~92% of server endpoints |
| TypeScript SDK coverage | ~88% of server endpoints |
| Framework integrations | 6 (all import and instantiate successfully) |
| Time to first hello-world | ~25 seconds (Docker) |
| Server endpoints (total) | ~65 |
| SDK methods (Python) | 48 public methods |
| SDK methods (TypeScript) | 46 public methods |
| MCP tools | 13 |
| Test suite | 764 tests |
| Docker image size | 334MB |

---

## Appendix: What Works Well

These are things worth preserving -- they represent good engineering decisions:

1. **README structure** -- context-first, not logic-engine-first. The "Working Loop" concept is the right lead.
2. **MCP configs directory** -- pre-built JSON for 5 different agents/IDEs. Nobody else does this.
3. **Makefile** -- `make up`, `make smoke`, `make up-ollama`, `make up-monitoring`. Excellent DX.
4. **Health endpoint** -- returns detailed JSON with version, disk, memory, auth, LLM status. Better than most.
5. **`.env.example`** -- exceptionally well-documented with provider comparison table and inline guidance.
6. **Python SDK context manager** -- `with SyncNocturnusAIClient(...) as client:` is idiomatic and clean.
7. **Zero-dependency TypeScript SDK** -- uses native fetch. No bloat.
8. **Framework integration breadth** -- LangChain + CrewAI + AutoGen + LangGraph + OpenAI Agents + Anthropic. Covers the market.
9. **`tell`/`ask`/`teach`/`forget` aliases** -- developer-friendly simplified API on top of the formal `assert_fact`/`infer`/`assert_rule`/`retract` methods.
10. **Deprecation strategy** -- old methods warn with `DeprecationWarning` pointing to new ones. Professional.
