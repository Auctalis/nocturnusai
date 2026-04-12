# Fresh Eyes Audit — NocturnusAI
**Date:** 2026-04-12
**Auditor:** Claude (fresh-eyes-audit skill)
**Server Version:** 0.3.2 (Docker), 0.1.0 (source)
**SDK Versions:** Python 0.3.2, TypeScript 0.3.2

## Verdict: MAYBE

NocturnusAI has a genuinely novel value proposition — a context compression server that sits between your agent and the LLM, extracting facts and returning only deltas. The architecture is solid (Hexastore, backward chaining, truth maintenance, temporal atoms). Both SDKs cover extensive API surface. The `POST /context` workflow with `briefingDelta` is compelling when it works.

The problems are reliability and polish. The primary selling feature (LLM extraction from turns) intermittently fails on the default Docker setup because the local LLM produces non-strict JSON that the server rejects. SDK methods for `aggregate()` and `retractPattern()` send wrong field names. Three different docs pages show three different SDK method names for the same workflow. These are fixable issues, but a new developer hitting them on first try may not persevere.

**Would I adopt?** For the logic/inference/fact storage engine — yes, it's solid and unique. For the "turn reduction" headline feature — not until LLM extraction reliability is addressed.

---

## First 60 Seconds

**What I understood:** NocturnusAI reduces LLM context window costs by extracting facts from conversation turns and returning only what changed since the last call. It also provides a full logic engine with rules, inference, scopes, and truth maintenance.

**What was unclear:** The relationship between `POST /context` (the primary workflow) and `POST /memory/context` (the lower-level API). The README shows both `ingest_and_optimize()` and `optimize_context()` and the homepage shows `process_turns()` — which should I use?

**Time to "aha":** ~90 seconds reading the README. The "Large turn arrays in. Lean context windows out." tagline is effective.

---

## Setup & Installation

### Method 1: Docker (primary path tested)

```bash
docker pull ghcr.io/auctalis/nocturnusai:latest    # ~30 seconds
docker run -d -p 9300:9300 --name nocturnusai \
  --add-host=host.docker.internal:host-gateway \
  ghcr.io/auctalis/nocturnusai:latest               # instant
curl http://localhost:9300/health                    # ready in ~4s
```

**Steps:** 3 commands to running server. **Time:** ~35 seconds.

**Issues found:**
- `FRICTION`: The simple `docker run -p 9300:9300 ghcr.io/auctalis/nocturnusai:latest` from the README website "Option A" doesn't include `--add-host` flag needed on Linux for Ollama connectivity
- `FRICTION`: LLM extraction requires Ollama running on host with `granite3.3:8b` pulled, but `docker run` alone doesn't check or warn about this
- `BLOCKER`: When Ollama IS reachable, extraction still intermittently fails because granite3.3:8b produces non-strict JSON (trailing commas, args as objects instead of arrays). Server returns HTTP 200 with 0 facts and a warning — silent failure of the primary feature

### Method 2: Source build

```bash
./gradlew :nocturnusai-server:run    # JDK 21, builds and starts on :9300
```

**Steps:** 1 command. **Time:** ~60 seconds (first build).
Worked perfectly. Health check passed immediately.

### Method 3: `make up-ollama` (from repo)

Available but not tested — documented clearly in Makefile.

---

## SDK Report Card

| Language | Install | Connect | CRUD | Coverage | Ergonomics | Advanced | Grade |
|----------|---------|---------|------|----------|-----------|----------|-------|
| Python   | Pass    | Pass    | Pass | ~85%     | B+        | Partial  | B     |
| TypeScript | Pass | Pass   | Pass | ~85%     | A-        | Partial  | B+    |

### Python SDK — Detailed Findings

**Level 1 — Install:** `pip install nocturnusai` — 2 deps (httpx, pydantic). Clean install. v0.3.2.

**Level 2 — Connect:** `SyncNocturnusAIClient("http://localhost:9300", tenant_id="default")` works. Error on wrong URL is from httpx — clear enough.

**Level 3 — CRUD:** All operations work: `tell()`, `ask()`, `teach()`, `forget()`, `infer()`. Return types are Pydantic models (`Atom`, `InferResult`). Truth maintenance works — retracting a premise removes derived conclusions.

**Level 4 — Coverage:**

| Endpoint | Python Method | Status | Notes |
|----------|-------------|--------|-------|
| POST /tell | `tell()` | Pass | |
| POST /ask | `ask()` | Pass | |
| POST /teach | `teach()` | Pass | |
| POST /forget | `forget()` | Pass | |
| POST /assert/fact | `assert_fact()` | Pass | |
| POST /assert/rule | `assert_rule()` | Pass | |
| POST /assert/facts | `bulk_assert()` | Pass | |
| POST /query | `query()` | Pass | |
| POST /infer | `infer()` | Pass | |
| POST /retract | `retract()` | Pass | |
| POST /retract/pattern | `retract_pattern()` | **FAIL** | Missing `args` field |
| POST /context | `process_turns()` | Pass | LLM-dependent |
| POST /context/optimize | `optimize_context()` | Pass | Deprecated |
| POST /context/diff | `diff_context()` | Pass | |
| POST /context/session/clear | `clear_context_session()` | Pass | |
| POST /memory/context | `context()` | Pass | |
| POST /memory/consolidate | `consolidate()` | Pass | |
| POST /memory/decay | `decay()` | Pass | |
| POST /memory/priority | `set_priority()` | Pass | |
| POST /aggregate | `aggregate()` | **FAIL** | Wrong field name: `op` vs `operation` |
| POST /execute | `execute()` | Pass | |
| GET /scopes | `list_scopes()` | Pass | |
| POST /scope/fork | `fork_scope()` | Pass | |
| POST /scope/diff | `diff_scope()` | Pass | |
| POST /scope/merge | `merge_scope()` | Pass | |
| DELETE /scope/{name} | `delete_scope()` | Pass | |
| POST /tx/begin | `begin_transaction()` | Pass | |
| POST /tx/commit/{id} | `commit_transaction()` | Pass | |
| POST /tx/rollback/{id} | `rollback_transaction()` | Pass | |
| GET /memory/events | `subscribe_events()` | Untested | SSE, requires async |
| GET /health | `health()` | Pass | |
| GET /predicates | `predicates()` | Pass | |
| POST /extract | `extract_facts()` | Untested | LLM-dependent |
| POST /context/ingest | — | Missing | No SDK method |
| POST /context/summary | — | Missing | No SDK method |
| POST /memory/compress | — | Missing | No SDK method |
| POST /memory/cleanup | — | Missing | No SDK method |
| POST /memory/recall | — | Missing | No SDK method |
| POST /memory/prioritize | — | Missing | No SDK method |
| POST /memory/query/temporal | — | Missing | No SDK method |
| POST /memory/query/salient | — | Missing | No SDK method |
| POST /auth/* | — | Missing | No auth methods |
| POST /admin/* | — | Missing | No admin methods |

**Level 5 — Ergonomics:**
- Return types: Pydantic models (Atom, InferResult, etc.) — well-typed
- Both sync and async clients — idiomatic Python
- `infer()` return type annotation allows `ProofTree` but accessing `.args` on it triggers Pyright errors (type narrowing issue)
- Error messages come from httpx — reasonably clear
- The 3 context methods (`process_turns`, `optimize_context`, `ingest_and_optimize`) have overlapping purposes and confusing naming

**Level 6 — Advanced:** Scopes (fork/diff/merge), transactions, bulk assert all work. Aggregation fails due to SDK bug.

### TypeScript SDK — Detailed Findings

**Level 1 — Install:** `npm install nocturnusai-sdk` — 0 runtime deps. Ships compiled JS + TypeScript source + .d.ts types. 4 exports. ~1 second install. Excellent.

**Level 2 — Connect:** `new NocturnusAIClient({ baseUrl: 'http://localhost:9300', tenantId: 'default' })` — works immediately. Wrong URL gives `"Connection failed: fetch failed"` — clear but could include the URL.

**Level 3 — CRUD:** All pass. `tell`, `ask`, `teach`, `forget`, `infer` work. Truth maintenance verified. Return types are well-typed interfaces.

**Level 4 — Coverage:** 38 of 44 methods passed. **11 issues found (3 HIGH, 4 MEDIUM, 4 LOW):**

**HIGH:**
1. **`aggregate()`** — sends `op` instead of `operation`, missing required `args` field. Completely broken.
2. **`retractPattern()`** — missing required `args` field. Completely broken.
3. **`infer()` with null args** — SDK type signature accepts `(string | null)[]` but server requires non-nullable `List<String>`. Null wildcards don't work.

**MEDIUM:**
4. **`execute()` JSDoc** — says `QUERY` but DSL parser only accepts `INFER`. Misleading docs.
5. **`forkScope()` return type** — SDK declares `source`/`target` fields but server returns `sourceScope`/`targetScope`. Returns `undefined`.
6. **`AuthStatus` type mismatch** — SDK type has `authEnabled`/`hasKeys`, server returns `bootstrapRequired`/`keyCount`.
7. **`AggregateResult` type mismatch** — SDK type has `op`/`count`, server returns `operation`/`matchedFacts`.

**LOW:**
8. **`diffScope()`** — works but response leaks Kotlin internal class names (`com.nocturnusai.core.Term.Identifier`)
9. **`diffScope()`/`mergeScope()` return `Record<string, unknown>`** — untyped returns
10. **`processTurns()`** — hangs when LLM is slow (no timeout)
11. **`subscribeEvents()`** — works but no built-in reconnection

**Root cause:** Contract drift between server-side Kotlin DTOs and SDK type definitions. Server evolved without SDK updates.

**Level 5 — Ergonomics:**
- Zero runtime deps (uses `fetch`) — excellent
- Full TypeScript types — excellent
- `NocturnusAIRequestError` with status code and body — good
- SSE handling is custom (not using EventSource) — works but no auto-reconnect
- Method names follow camelCase convention — idiomatic

### Cross-SDK Comparison: Same Workflow

**Python (7 lines):**
```python
with SyncNocturnusAIClient("http://localhost:9300") as c:
    c.tell("parent", ["alice", "bob"])
    c.tell("parent", ["bob", "charlie"])
    c.teach({"predicate":"grandparent","args":["?x","?z"]},
            [{"predicate":"parent","args":["?x","?y"]},{"predicate":"parent","args":["?y","?z"]}])
    result = c.infer("grandparent", ["alice", "?who"])
    print(result)  # [Atom(predicate='grandparent', args=['alice','charlie'])]
```

**TypeScript (8 lines):**
```typescript
const c = new NocturnusAIClient({ baseUrl: 'http://localhost:9300' });
await c.tell('parent', ['alice', 'bob']);
await c.tell('parent', ['bob', 'charlie']);
await c.teach({predicate:'grandparent',args:['?x','?z']},
  [{predicate:'parent',args:['?x','?y']},{predicate:'parent',args:['?y','?z']}]);
const result = await c.infer('grandparent', ['alice', null]);
console.log(result); // [{predicate:'grandparent',args:['alice','charlie']}]
```

Both are comparably concise. Python has the edge with context manager.

---

## Documentation & Website

**Homepage (site/src/pages/index.astro):**
- Clear value prop: "97% fewer tokens" — grabs attention
- Two-step walkthrough is effective
- **FRICTION**: The "97% fewer tokens" and "$54K → $240/month" claims cite "1,000 requests/hour on GPT-4o" in fine print but the methodology isn't linked to a benchmark
- **FRICTION**: SDK examples on homepage use `process_turns()` / `processTurns()` — but README uses `ingest_and_optimize()` and QUICKSTART uses `optimize_context()`. Three different method names across three pages for the primary workflow.

**Docs site:**
- Good structure with clear navigation
- "Start in four moves" on docs/index.astro is effective
- **PAPERCUT**: Docs overview references `POST /context/optimize` which CLAUDE.md says is deprecated (sunset 2026-07-01)
- **FRICTION**: No mention anywhere that tenants must be created before use (or that `default` tenant may not exist on source builds)

**llm.txt:** Auto-generated, comprehensive API reference. Well-written "Mental Model" section. This is a standout feature — the best API reference I've seen for AI agent consumption.

**Agent card (/.well-known/agent.json):** Present and correct. Version says 0.2.4 though (stale).

---

## Competitor Comparison

| Dimension | NocturnusAI | Mem0 | Letta (MemGPT) | Zep |
|-----------|-------------|------|----------------|-----|
| **Focus** | Context compression + logic engine | Memory layer for AI agents | LLM-as-OS memory management | Temporal knowledge graph |
| **Time to understand** | ~90s | ~2 min | ~5 min | ~2 min |
| **Steps to hello world** | 3 (docker + curl) | 2-3 (pip + add_memory) | 3-4 (create agent + memory blocks) | 3 (sign up + API key + SDK) |
| **SDK languages** | Python, TypeScript | Python, TypeScript | Python, TypeScript | Python, TypeScript, Go |
| **Docs quality** | Good, some inconsistency | Good, polished | Good, thorough | Excellent, managed platform |
| **Open source?** | BSL 1.1 (Apache 2.0 in 2030) | Apache 2.0 | Apache 2.0 | Cloud + open source |
| **Unique strengths** | Logic engine, inference, scopes, truth maintenance, `briefingDelta` | Simple memory add/search API | Agentic memory with tool use | Temporal graphs, managed service |
| **Pricing** | Self-hosted (free) | Open source + hosted | Open source + hosted | Free tier + paid plans |
| **GitHub stars** | New project | ~25K+ | ~15K+ | ~3K+ |

**Where NocturnusAI wins:**
- Deterministic logic engine with backward chaining inference — unique in this space
- Truth maintenance (retract a premise, derived conclusions auto-retract)
- Scope-based hypothetical reasoning (fork/diff/merge knowledge branches)
- `briefingDelta` natural language summary of what changed — very useful
- Self-hosted with no cloud dependency

**Where competitors win:**
- Mem0: Simpler API (just add/search), larger community, Apache 2.0
- Letta: More mature agent integration, self-managing memory
- Zep: Managed service (no ops), temporal graph queries, Go SDK

---

## All Issues (ranked by severity)

### BLOCKER (2)

1. **LLM extraction silently fails on Docker default** — granite3.3:8b produces non-strict JSON (trailing commas, args as objects). Server's strict JSON parser rejects it. `POST /context` returns HTTP 200 with 0 facts and a warning. The primary "turn reduction" feature doesn't work reliably out of the box.
   - File: `nocturnusai-server/.../llm/LlmFactExtractor.kt` — needs `allowTrailingComma = true` and `ignoreUnknownKeys = true`
   - Impact: New developer following README "Try It" section gets empty results ~50% of attempts

2. **SDK `aggregate()` and `retractPattern()` send wrong field names** — Both Python and TypeScript SDKs send `op` instead of `operation` for aggregate, and omit required `args` field for retract_pattern. These endpoints are completely broken in both SDKs.
   - Files: `sdks/python/nocturnusai/client.py`, `sdks/typescript/src/client.ts`
   - Server expects: `{"operation":"COUNT","predicate":"x","args":["?a","?b"]}` and `{"predicate":"x","args":[]}`

### FRICTION (16)

3. **Three different method names for primary workflow across docs** — README shows `ingest_and_optimize()`, QUICKSTART shows `optimize_context()`, homepage shows `process_turns()`. A new dev doesn't know which to use.

4. **Tenant creation not documented in quickstart** — Source builds don't auto-create "default" tenant. `POST /tell` returns `"Tenant 'X' not found"` with no hint to create one first. Docker image does auto-create.

5. **Scope API field names differ from intuitive names** — Server uses `sourceScope`/`targetScope` for fork/merge, `scopeA`/`scopeB` for diff. SDK uses `source`/`target`. SDKs translate correctly, but raw REST users get "Failed to convert request body" errors.

6. **`diffScope()` leaks Kotlin internal class names** — Response includes `"type":"com.nocturnusai.core.Term.Identifier"` in args. SDK consumers see implementation details.

7. **Version numbers inconsistent** — Health: 0.3.2, Agent card: 0.2.4, Source build: 0.1.0. Which is the real version?

8. **`POST /execute` DSL fails with "Invalid command"** — `ASSERT test_dsl(works).` returns bad request. DSL syntax documentation is thin.

9. **`infer()` with null args fails in TypeScript SDK** — SDK type signature accepts `(string | null)[]` but server requires non-null strings. Should auto-convert nulls to variable names.

10. **No timeout on LLM-dependent operations** — `processTurns()` and `ingestAndOptimize()` can hang indefinitely waiting for slow LLM responses (observed with Ollama on local hardware).

11. **Python SDK `infer()` return type allows ProofTree but Pyright can't access .args** — Type union `list[Atom] | list[ProofTree]` causes type narrowing issues. Pyright reports 10+ attribute access errors.

12. **`POST /context` warning message says "use predicate syntax"** — When LLM extraction fails, the fallback message suggests `predicate(arg1, arg2)` but doesn't explain that this requires `POST /tell` not `POST /context`.

13. **Transaction + assert/fact integration** — Using `transactionId` in JSON body for `POST /assert/fact` returns internal error on Docker image. Docs say to use `X-Transaction-ID` header instead.

14. **Docker `docker run` README example missing `--add-host` flag** — Needed on Linux for container-to-host Ollama connectivity. Works on Docker Desktop Mac without it.

15. **TS SDK `forkScope()` return type field name mismatch** — SDK declares `source`/`target` fields but server returns `sourceScope`/`targetScope`. Returns `undefined` for both fields.

16. **TS SDK `AuthStatus` type doesn't match server response** — SDK type has `authEnabled`/`hasKeys`, server returns `bootstrapRequired`/`keyCount`. Users get `undefined`.

17. **TS SDK `AggregateResult` type uses wrong field names** — SDK type has `op`/`count`, server returns `operation`/`matchedFacts`. Even if request was fixed, response parsing would fail.

18. **TS SDK `execute()` JSDoc uses wrong DSL keyword** — Documentation says `QUERY` but DSL parser only accepts `INFER`. Users get "Invalid command".

### PAPERCUT (7)

15. **No TypeScript examples** — `examples/` has 2 Python files. No TypeScript equivalent.

16. **`homepage_walkthrough.py` uses `requests` instead of SDK** — Example file uses raw HTTP instead of the `nocturnusai` package. Misses opportunity to showcase SDK ergonomics.

17. **Homepage "97% fewer tokens" claim** — The math assumes full conversation replay vs. delta. Real-world reduction depends heavily on conversation length and content. Fine print mentions "1,000 requests/hour on GPT-4o" but no link to benchmark methodology.

18. **Deprecated endpoint `POST /context/optimize` still shown in README** — README step 2 uses this endpoint which CLAUDE.md says sunsets 2026-07-01.

19. **No SDK methods for simplified memory aliases** — `POST /memory/compress`, `/memory/cleanup`, `/memory/recall`, `/memory/prioritize` have no Python or TypeScript SDK methods.

20. **No SDK methods for auth/admin management** — All `/auth/*` and `/admin/*` endpoints require raw HTTP. No typed SDK methods.

21. **SSE `subscribeEvents()` has no auto-reconnect** — TypeScript SDK's SSE implementation uses raw fetch without EventSource. No automatic reconnection on network failure.

### SUGGESTION (5)

22. **Add `allowTrailingComma = true` to LLM JSON parser** — LLMs commonly produce non-strict JSON. This one change would fix the #1 blocker.

23. **Unify context method naming** — Pick one name (`processTurns`/`process_turns`) and deprecate the others with clear migration docs.

24. **Add TypeScript examples** — At minimum, a `examples/quickstart.ts` equivalent of `homepage_walkthrough.py`.

25. **Add Go SDK** — Competitor Zep has Go SDK. Go is common in infrastructure/agent tooling.

26. **Auto-create "default" tenant on first request** — Remove the tenant creation friction for new developers.

---

## Summary Stats

| Metric | Value |
|--------|-------|
| **Total issues** | 30 |
| **Blockers** | 2 |
| **Friction** | 16 |
| **Papercuts** | 7 |
| **Suggestions** | 5 |
| **Python SDK API coverage** | ~85% (30/35 core methods pass, 2 fail, 3 missing) |
| **TypeScript SDK API coverage** | ~80% (38/44 methods pass, 3 HIGH + 4 MEDIUM + 4 LOW issues) |
| **Total server endpoints** | 72 |
| **SDK-covered endpoints** | ~40/72 (~56%) |
| **Time to first hello-world (Docker)** | ~35 seconds |
| **Time to first hello-world (source)** | ~60 seconds |
| **Time to working turn reduction** | Variable — depends on Ollama + granite3.3:8b reliability |

---

## What Works Well

- **Logic engine core is rock solid** — tell, ask, teach, forget, infer all work flawlessly with correct truth maintenance
- **`briefingDelta` is a killer feature** — natural language summary of what changed is exactly what agents need
- **Zero-dep TypeScript SDK** — no runtime dependencies, built-in fetch, full TypeScript types
- **`llm.txt` auto-generated reference** — best-in-class API documentation for LLM consumption
- **Scope management** — fork/diff/merge for hypothetical reasoning is unique and powerful
- **Multi-tenancy** — proper isolation via `X-Database` + `X-Tenant-ID`
- **Docker setup** — simple, fast, well-documented (except for the LLM extraction issue)
- **Makefile** — excellent developer ergonomics for contributors
