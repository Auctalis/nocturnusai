# NocturnusAI Claude Code Skill Suite — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Create 5 Claude Code skills that teach Claude how to work with NocturnusAI for external developers.

**Architecture:** Each skill is a standalone `SKILL.md` file in `.claude/skills/<name>/` within the project repo. Skills auto-trigger based on `description` keywords. Each skill covers one domain (connect, knowledge, memory, reasoning, admin) and stays under 500 lines.

**Tech Stack:** Markdown (SKILL.md files with YAML frontmatter), no code dependencies.

**Design doc:** `docs/plans/2026-03-11-nocturnusai-claude-code-skill-design.md`

---

### Task 1: Create skills directory structure

**Files:**
- Create: `.claude/skills/nocturnusai-connect/SKILL.md` (placeholder)
- Create: `.claude/skills/nocturnusai-knowledge/SKILL.md` (placeholder)
- Create: `.claude/skills/nocturnusai-memory/SKILL.md` (placeholder)
- Create: `.claude/skills/nocturnusai-reasoning/SKILL.md` (placeholder)
- Create: `.claude/skills/nocturnusai-admin/SKILL.md` (placeholder)

**Step 1: Create all 5 skill directories**

```bash
mkdir -p .claude/skills/nocturnusai-connect
mkdir -p .claude/skills/nocturnusai-knowledge
mkdir -p .claude/skills/nocturnusai-memory
mkdir -p .claude/skills/nocturnusai-reasoning
mkdir -p .claude/skills/nocturnusai-admin
```

**Step 2: Create placeholder SKILL.md files**

Each file should contain only the YAML frontmatter with `name` and `description`. The body will say `# TODO: Implementation in subsequent tasks`.

**Step 3: Commit**

```bash
git add .claude/skills/
git commit -m "feat: scaffold NocturnusAI Claude Code skill suite (5 skills)"
```

---

### Task 2: Implement `nocturnusai-connect` skill

**Files:**
- Modify: `.claude/skills/nocturnusai-connect/SKILL.md`
- Reference: `CLAUDE.md` (for env vars, server config)
- Reference: `nocturnusai-server/src/main/kotlin/com/nocturnusai/server/routes/McpRoutes.kt`
- Reference: `nocturnusai-server/src/main/kotlin/com/nocturnusai/server/auth/AuthInterceptor.kt`

**Step 1: Write the full SKILL.md**

Include these sections in order:

1. **YAML frontmatter** — name: `nocturnusai-connect`, description triggers on: setup, connect, configure, MCP, auth, API key, tenant, database, bootstrap, NocturnusAI
2. **Overview** — what NocturnusAI is (1 paragraph)
3. **Prerequisites** — server must be running, which port, how to start
4. **Connection Path 1: MCP Config** — `.mcp.json` example with `mcp-remote` stdio bridge, `claude_desktop_config.json` example, env var mapping (`NOCTURNUSAI_URL`, `NOCTURNUSAI_API_KEY`, `NOCTURNUSAI_DATABASE`, `NOCTURNUSAI_TENANT`)
5. **Connection Path 2: Direct HTTP** — curl examples for `POST /mcp` (JSON-RPC 2.0), REST alternatives (`POST /tell`, `/ask`), required headers (`Content-Type`, `X-Database`, `X-Tenant-ID`, `X-API-Key`)
6. **Connection Path 3: SDK** — Python install + async client example, TypeScript install + client example
7. **Auth Modes** — table of DISABLED/LEGACY/RBAC with how each works, `GET /auth/status` to detect mode
8. **Auth Bootstrap Sequence** — step-by-step for RBAC: bootstrap → admin key → scoped keys
9. **Tenant Setup** — step-by-step: create database → create tenant → verify
10. **Troubleshooting** — common errors (missing tenant, wrong auth mode, header issues, MCP transport)

Keep under 500 lines. Use code blocks for all examples.

**Step 2: Verify line count**

```bash
wc -l .claude/skills/nocturnusai-connect/SKILL.md
```

Expected: < 500 lines

**Step 3: Commit**

```bash
git add .claude/skills/nocturnusai-connect/SKILL.md
git commit -m "feat: implement nocturnusai-connect skill — setup, auth, tenancy"
```

---

### Task 3: Implement `nocturnusai-knowledge` skill

**Files:**
- Modify: `.claude/skills/nocturnusai-knowledge/SKILL.md`
- Reference: `nocturnusai-server/src/main/kotlin/com/nocturnusai/server/routes/McpRoutes.kt` (tool definitions)
- Reference: `nocturnusai-core/src/main/kotlin/com/nocturnusai/core/Atom.kt` (field definitions)
- Reference: `nocturnusai-core/src/main/kotlin/com/nocturnusai/inference/BackwardChainer.kt`

**Step 1: Write the full SKILL.md**

Include these sections:

1. **YAML frontmatter** — name: `nocturnusai-knowledge`, description triggers on: tell, ask, teach, forget, assert, query, infer, rule, fact, predicate, bulk, NocturnusAI knowledge base
2. **Variable Convention** — `?` prefix always, show wrong (`X`, `$x`) vs right (`?x`)
3. **Core Tools Reference** — for each of `tell`, `ask`, `teach`, `forget`: purpose, required params, optional params, MCP JSON-RPC example, curl equivalent
4. **Bulk Operations** — `bulk_assert` and `retract_pattern` with examples
5. **Schema Discovery** — `predicates` tool with example
6. **Aggregation** — `aggregate` with COUNT example and numeric op (SUM) example, explain `argIndex`
7. **Common Workflows** — assert→query, teach→infer, schema exploration, bulk loading
8. **Conflict Strategies** — table of REJECT/NEWEST_WINS/CONFIDENCE/KEEP_BOTH with when to use each
9. **Gotchas** — duplicate handling, text output parsing, non-transactional bulk, argIndex requirement

Keep under 500 lines.

**Step 2: Verify line count**

```bash
wc -l .claude/skills/nocturnusai-knowledge/SKILL.md
```

**Step 3: Commit**

```bash
git add .claude/skills/nocturnusai-knowledge/SKILL.md
git commit -m "feat: implement nocturnusai-knowledge skill — facts, rules, queries"
```

---

### Task 4: Implement `nocturnusai-memory` skill

**Files:**
- Modify: `.claude/skills/nocturnusai-memory/SKILL.md`
- Reference: `nocturnusai-core/src/main/kotlin/com/nocturnusai/memory/MemoryManager.kt`
- Reference: `nocturnusai-core/src/main/kotlin/com/nocturnusai/memory/SalienceTracker.kt`
- Reference: `nocturnusai-server/src/main/kotlin/com/nocturnusai/server/routes/MemoryRoutes.kt`

**Step 1: Write the full SKILL.md**

Include these sections:

1. **YAML frontmatter** — name: `nocturnusai-memory`, description triggers on: memory, context window, salience, temporal, recall, consolidate, decay, TTL, expire, events, NocturnusAI agent memory
2. **Concepts** — salience scoring (recency x frequency x priority), temporal atoms (createdAt/validFrom/validUntil/ttl), memory lifecycle
3. **Context Window** — `context` tool: params, example, how to use output for LLM prompt stuffing
4. **Temporal Queries** — `recall` tool: params, epoch ms format, example
5. **TTL and Scheduled Facts** — how to set ttl on `tell`, validFrom/validUntil examples
6. **Consolidation** — `compress` tool: what it does, when to use, no-args warning
7. **Decay** — `cleanup` tool: threshold param, what gets evicted
8. **Memory Hygiene Pattern** — recommended periodic workflow (compress → cleanup)
9. **Priority Boosting** — REST-only `POST /memory/priority` with curl example (no MCP tool)
10. **Event Streaming** — SSE `GET /memory/events`, tenant-scoped, event format
11. **Gotchas** — no-args tools, epoch ms format, REST-only operations, threshold meaning

Keep under 500 lines.

**Step 2: Verify line count**

```bash
wc -l .claude/skills/nocturnusai-memory/SKILL.md
```

**Step 3: Commit**

```bash
git add .claude/skills/nocturnusai-memory/SKILL.md
git commit -m "feat: implement nocturnusai-memory skill — context, salience, temporal"
```

---

### Task 5: Implement `nocturnusai-reasoning` skill

**Files:**
- Modify: `.claude/skills/nocturnusai-reasoning/SKILL.md`
- Reference: `nocturnusai-core/src/main/kotlin/com/nocturnusai/inference/BackwardChainer.kt`
- Reference: `nocturnusai-core/src/main/kotlin/com/nocturnusai/inference/ReteEngine.kt`
- Reference: `nocturnusai-server/src/main/kotlin/com/nocturnusai/server/routes/ScopeRoutes.kt`

**Step 1: Write the full SKILL.md**

Include these sections:

1. **YAML frontmatter** — name: `nocturnusai-reasoning`, description triggers on: NAF, negation, negation-as-failure, scope, fork, merge, hypothesis, confidence, conflict, proof, what-if, NocturnusAI reasoning
2. **Negation-as-Failure (NAF)** — what it is (closed-world assumption), how it differs from explicit negation, JSON format (`naf: true` on body atoms), DSL format (`NOT pred(?x)`)
3. **NAF Ordering Rule** — PROMINENT WARNING: assert blocking facts before triggering facts, correct vs incorrect examples, explain why (Rete fires immediately)
4. **Explicit vs NAF Negation** — side-by-side comparison table with examples
5. **Scopes** — what they are (logical partitions, NOT isolation), vs tenants distinction
6. **Scope Workflow** — fork → experiment → merge/delete, with MCP examples for `fork_scope`, `merge_scope`, `list_scopes`, `delete_scope`
7. **Merge Strategies** — table: SOURCE_WINS, TARGET_WINS, KEEP_BOTH, REJECT
8. **Confidence** — how to set on tell, how to filter on ask with minConfidence
9. **Conflict Resolution** — REJECT/NEWEST_WINS/CONFIDENCE/KEEP_BOTH table (brief, cross-ref knowledge skill)
10. **Proof Chains** — `withProof: true` on ask, example output, when to use
11. **Gotchas** — NAF only in bodies, scope=null means all scopes, confidence omitted vs 1.0

Keep under 500 lines.

**Step 2: Verify line count**

```bash
wc -l .claude/skills/nocturnusai-reasoning/SKILL.md
```

**Step 3: Commit**

```bash
git add .claude/skills/nocturnusai-reasoning/SKILL.md
git commit -m "feat: implement nocturnusai-reasoning skill — NAF, scopes, proofs"
```

---

### Task 6: Implement `nocturnusai-admin` skill

**Files:**
- Modify: `.claude/skills/nocturnusai-admin/SKILL.md`
- Reference: `nocturnusai-server/src/main/kotlin/com/nocturnusai/server/routes/AdminRoutes.kt`
- Reference: `nocturnusai-server/src/main/kotlin/com/nocturnusai/server/routes/AuthRoutes.kt`
- Reference: `nocturnusai-server/src/main/kotlin/com/nocturnusai/server/routes/ObservabilityRoutes.kt`

**Step 1: Write the full SKILL.md**

Include these sections:

1. **YAML frontmatter** — name: `nocturnusai-admin`, description triggers on: NocturnusAI database, tenant, health, metrics, backup, API key, RBAC, admin, monitoring, operational
2. **Important** — all admin ops are REST-only (no MCP tools), use curl/Bash
3. **Database Management** — create, list, delete with curl examples
4. **Tenant Management** — create tenant within database, curl example
5. **Health Checks** — `/health`, `/health/live`, `/health/ready` endpoints
6. **Metrics** — `/metrics` Prometheus format
7. **RBAC Key Management** — create/list/update/delete API keys, role-based permissions, database/tenant scoping
8. **Backups** — `POST /admin/backups` to trigger snapshot
9. **Discovery Endpoints** — `/llm.txt` (auto-generated API docs), `/.well-known/agent.json` (A2A agent card)
10. **Common Workflows** — first-time setup, key management, monitoring, multi-database
11. **Gotchas** — forced multi-tenant, destructive delete, bootstrap once-only, public endpoints bypass auth

Keep under 500 lines.

**Step 2: Verify line count**

```bash
wc -l .claude/skills/nocturnusai-admin/SKILL.md
```

**Step 3: Commit**

```bash
git add .claude/skills/nocturnusai-admin/SKILL.md
git commit -m "feat: implement nocturnusai-admin skill — databases, tenants, monitoring"
```

---

### Task 7: Final validation and integration commit

**Files:**
- Verify: all 5 `.claude/skills/nocturnusai-*/SKILL.md` files
- Reference: design doc `docs/plans/2026-03-11-nocturnusai-claude-code-skill-design.md`

**Step 1: Verify all skills exist and are under 500 lines**

```bash
for f in .claude/skills/nocturnusai-*/SKILL.md; do echo "$f: $(wc -l < "$f") lines"; done
```

Expected: all < 500 lines

**Step 2: Verify YAML frontmatter parses correctly**

Check each file starts with `---`, has `name:` and `description:`, ends frontmatter with `---`.

```bash
for f in .claude/skills/nocturnusai-*/SKILL.md; do echo "=== $f ==="; head -5 "$f"; echo; done
```

**Step 3: Cross-check against issues table**

Verify each of the 14 issues from the design doc is addressed in the appropriate skill:

| Issue | Skill | Check |
|-------|-------|-------|
| Tenant must exist | connect | tenant setup section exists |
| Auth bootstrap | connect | bootstrap sequence section exists |
| Claude Desktop headers | connect | mcp-remote bridge documented |
| NAF ordering | reasoning | prominent warning with examples |
| ? variable prefix | knowledge | convention section with wrong/right |
| Scope vs Tenant | reasoning | explicit distinction section |
| MCP transport deprecation | connect | bridge workaround noted |
| No MCP admin tools | admin | REST-only note at top |
| No MCP priority tool | memory | REST workaround documented |
| compress/cleanup no args | memory | called out in gotchas |
| ask returns text | knowledge | parsing note in gotchas |
| recall epoch ms | memory | format documented |
| bulk_assert non-transactional | knowledge | warning in gotchas |
| MCP vs REST tenant defaults | connect | inconsistency documented |

**Step 4: Final commit if any fixes needed**

```bash
git add .claude/skills/
git commit -m "fix: address review findings in NocturnusAI skill suite"
```
