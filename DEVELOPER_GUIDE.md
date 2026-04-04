# NocturnusAI Developer Guide

> The complete reference for building with NocturnusAI - from first `POST /context` to production deployment.

**NocturnusAI** is a context-reduction and reasoning backend for Agentic AI. Start with the context workflow when your application has too many turns, then drop into facts, rules, and inference only when you need backend mechanics.

---

## Table of Contents

0. [Start Here: Cut Down Turn Arrays First](#0-start-here-cut-down-turn-arrays-first)
1. [Installation](#1-installation)
2. [Backend Concepts](#2-backend-concepts)
3. [HTTP API Reference](#3-http-api-reference)
4. [Simplified API (tell/ask/teach/forget)](#4-simplified-api)
5. [Memory Management](#5-memory-management)
6. [Transactions](#6-transactions)
7. [MCP Protocol](#7-mcp-protocol)
8. [Python SDK](#8-python-sdk)
9. [TypeScript SDK](#9-typescript-sdk)
10. [LangChain Integration](#10-langchain-integration)
11. [CLI Reference](#11-cli-reference)
12. [Authentication & Authorization](#12-authentication--authorization)
13. [LLM-Powered Features](#13-llm-powered-features)
14. [Multi-Tenancy](#14-multi-tenancy)
15. [Observability & Monitoring](#15-observability--monitoring)
16. [Production Deployment](#16-production-deployment)
17. [Replication & Backup](#17-replication--backup)
18. [Security Hardening](#18-security-hardening)
19. [Environment Variable Reference](#19-environment-variable-reference)
20. [Architecture Deep Dive](#20-architecture-deep-dive)

---

## 0. Start Here: Cut Down Turn Arrays First

If your real problem is a huge thread payload, start with this loop:

1. `POST /context` to reduce raw turns into a smaller working set
2. `POST /context/optimize` to narrow by the next goal
3. `POST /context/diff` so later turns only send changes
4. `POST /context/session/clear` when the thread is finished

This guide still covers facts, rules, inference, transactions, scopes, and auth. Those are backend mechanics behind the context workflow, not the first thing a new integrator needs to learn.

---

## 1. Installation

### One-liner (recommended)

```bash
curl -fsSL https://raw.githubusercontent.com/Auctalis/nocturnusai/main/install.sh | bash
```

Options:

```bash
curl -fsSL https://raw.githubusercontent.com/Auctalis/nocturnusai/main/install.sh | bash -s -- --host-ollama
curl -fsSL https://raw.githubusercontent.com/Auctalis/nocturnusai/main/install.sh | bash -s -- --ollama
curl -fsSL https://raw.githubusercontent.com/Auctalis/nocturnusai/main/install.sh | bash -s -- --key sk-ant-...
curl -fsSL https://raw.githubusercontent.com/Auctalis/nocturnusai/main/install.sh | bash -s -- --port 8080
```

### Docker Compose (manual)

```bash
git clone https://github.com/Auctalis/nocturnusai.git
cd nocturnusai
docker compose up --build
```

### Local development (no Docker)

Requires JDK 17+ and Gradle.

```bash
git clone https://github.com/Auctalis/nocturnusai.git
cd nocturnusai
./gradlew build
./run_local_dev.sh    # starts server on :9300
```

### Verify

```bash
curl http://localhost:9300/health
```

---

## 2. Backend Concepts

### Facts

A **fact** is the fundamental unit of knowledge: a predicate applied to arguments.

```
parent(alice, bob)          # alice is bob's parent
likes(bob, pizza)           # bob likes pizza
NOT allergic(alice, cats)   # alice is not allergic to cats
```

Facts can carry temporal metadata (`validFrom`, `validUntil`, `ttl`) and arbitrary key-value `metadata`.

### Rules

A **rule** is a Horn clause: a head (consequent) derived when all body conditions (antecedents) are satisfied.

```
grandparent(?x, ?z) :- parent(?x, ?y), parent(?y, ?z)
```

Variables use the `?` prefix: `?x`, `?who`, `?name`.

### Inference

NocturnusAI uses **backward chaining** (Prolog-style SLD resolution) as its primary inference engine. Given a query like `grandparent(?who, charlie)`, it works backward from the goal, unifying variables, and applying rules until it finds facts that satisfy all conditions.

**Forward chaining** (Rete engine) supplements backward chaining by eagerly deriving conclusions when new facts are asserted.

### Truth Maintenance

When a fact is retracted, the **Truth Maintenance System (TMS)** automatically cascade-retracts any facts that were derived from it. This keeps the knowledge base consistent without manual cleanup.

### Scopes

Facts and rules can be isolated into **scopes** for hypothetical reasoning, versioning, or tenant isolation. A query in a scope sees both scoped and unscoped facts.

### Salience

Every fact has a **salience score** (0.0–1.0) computed from:
- **Recency** — how recently the fact was created or accessed
- **Frequency** — how often the fact has been matched in queries
- **Priority** — an explicit weight you can set (default 0.5)

Salience determines which facts appear in context windows and which survive decay.

---

## 3. HTTP API Reference

**Base URL:** `http://localhost:9300`

**Common headers:**

| Header | Purpose | Default |
|--------|---------|---------|
| `Content-Type` | Must be `application/json` for POST requests | — |
| `X-Database` | Selects the database | `default` |
| `X-Tenant-ID` | Selects the tenant within the database | `default` |
| `X-API-Key` | Authentication (when auth is enabled) | — |
| `X-Transaction-ID` | Associates request with a transaction | — |

### Assert Fact

```
POST /assert/fact
```

```json
{
  "predicate": "parent",
  "args": ["alice", "bob"],
  "truthVal": true,
  "negated": false,
  "scope": null,
  "metadata": {"source": "user_input"},
  "validFrom": null,
  "validUntil": null,
  "ttl": 3600000
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `predicate` | string | yes | Predicate name |
| `args` | string[] | yes | Arguments |
| `truthVal` | boolean | no | Truth value (default `true`) |
| `negated` | boolean | no | Negate the fact (default `false`) |
| `scope` | string | no | Scope for isolation |
| `metadata` | object | no | Arbitrary key-value pairs |
| `validFrom` | long | no | Epoch ms — fact valid from |
| `validUntil` | long | no | Epoch ms — fact valid until |
| `ttl` | long | no | Time-to-live in ms (auto-expires) |

**Response:** `"Fact Asserted: parent(alice, bob)"`

### Assert Rule

```
POST /assert/rule
```

```json
{
  "head": {
    "predicate": "grandparent",
    "args": ["?x", "?z"],
    "negated": false
  },
  "body": [
    {"predicate": "parent", "args": ["?x", "?y"]},
    {"predicate": "parent", "args": ["?y", "?z"]}
  ],
  "scope": null
}
```

**Response:** `"Rule Asserted: grandparent(?x,?z) :- parent(?x,?y), parent(?y,?z)"`

### Infer (Query)

```
POST /infer
POST /infer?proof=true
```

```json
{
  "predicate": "grandparent",
  "args": ["?who", "charlie"],
  "scope": null
}
```

**Response (without proof):**

```json
[
  {"predicate": "grandparent", "args": ["alice", "charlie"]}
]
```

**Response (with `?proof=true`):**

```json
[
  {
    "result": {"predicate": "grandparent", "args": ["alice", "charlie"]},
    "proof": {
      "step": {
        "type": "RuleApplication",
        "rule": "grandparent(?x,?z) :- parent(?x,?y), parent(?y,?z)",
        "bodyProofs": [
          {"step": {"type": "FactMatch", "fact": "parent(alice, bob)"}},
          {"step": {"type": "FactMatch", "fact": "parent(bob, charlie)"}}
        ]
      }
    }
  }
]
```

### Retract

```
POST /retract
```

```json
{"predicate": "parent", "args": ["alice", "bob"]}
```

**Response:** `"Retracted: parent(alice, bob)"`

Triggers TMS cascade — derived facts that depended on this fact are also retracted.

### Assert Template

```
POST /assert/template
```

```json
{
  "type": "MODUS_TOLLENS",
  "predicates": {"P": "guilty", "Q": "imprisoned"},
  "args": ["?x"],
  "scope": null
}
```

**Template types:** `SYLLOGISM`, `MODUS_PONENS`, `MODUS_TOLLENS`, `FACT_CHAIN`, `HYPOTHETICAL_SYLLOGISM`, `DISJUNCTIVE_SYLLOGISM`, `CONSTRUCTIVE_DILEMMA`, `DESTRUCTIVE_DILEMMA`, `CAUSAL_ARGUMENT`, `DEFINITIONAL_ARGUMENT`, `PRACTICAL_ARGUMENT`, `EVALUATIVE_ARGUMENT`

### Execute DSL

```
POST /execute
```

```json
{"command": "ASSERT parent(alice, bob)."}
```

### Schema Discovery

```
GET /predicates
GET /predicates?scope=my-scope
```

**Response:**

```json
{
  "predicates": [
    {"predicate": "parent", "factCount": 5, "ruleCount": 0, "arity": 2},
    {"predicate": "grandparent", "factCount": 0, "ruleCount": 1, "arity": 2}
  ],
  "totalPredicates": 2,
  "totalFacts": 5,
  "totalRules": 1
}
```

---

## 4. Simplified API

Developer-friendly aliases for the core operations. Identical behavior, shorter paths.

| Endpoint | Equivalent | Description |
|----------|-----------|-------------|
| `POST /tell` | `POST /assert/fact` | Store a fact |
| `POST /ask` | `POST /infer` | Run inference |
| `POST /teach` | `POST /assert/rule` | Define a rule |
| `POST /forget` | `POST /retract` | Remove a fact |

### Examples

```bash
# Tell
curl -sX POST http://localhost:9300/tell \
  -H 'Content-Type: application/json' \
  -d '{"predicate":"likes","args":["alice","logic"]}'

# Ask
curl -sX POST http://localhost:9300/ask \
  -H 'Content-Type: application/json' \
  -d '{"predicate":"likes","args":["?who","logic"]}'

# Teach
curl -sX POST http://localhost:9300/teach \
  -H 'Content-Type: application/json' \
  -d '{
    "head": {"predicate":"mortal","args":["?x"]},
    "body": [{"predicate":"human","args":["?x"]}]
  }'

# Forget
curl -sX POST http://localhost:9300/forget \
  -H 'Content-Type: application/json' \
  -d '{"predicate":"likes","args":["alice","logic"]}'
```

---

## 5. Memory Management

NocturnusAI provides agent-aware memory lifecycle management: temporal queries, salience-ranked retrieval, consolidation, and decay.

### Context Window

Get the most relevant facts for an agent's current reasoning step.

```
POST /memory/context
```

```json
{
  "maxFacts": 50,
  "minSalience": 0.1,
  "predicates": ["parent", "likes"],
  "scope": null
}
```

**Response:**

```json
{
  "facts": [
    {
      "predicate": "parent",
      "args": ["alice", "bob"],
      "salience": 0.95,
      "createdAt": 1708100000000,
      "metadata": {}
    }
  ],
  "totalAvailable": 234,
  "windowSize": 50,
  "predicateDistribution": {"parent": 12, "likes": 8},
  "generatedAt": 1708101234567
}
```

### Temporal Query

Query what was true at a specific point in time.

```
POST /memory/query/temporal
```

```json
{
  "predicate": "location",
  "args": ["alice", "?where"],
  "timestamp": 1708000000000
}
```

### Salient Query

Get facts ranked by salience score.

```
POST /memory/query/salient
```

```json
{
  "predicate": "likes",
  "args": ["?who", "?what"],
  "limit": 10,
  "minSalience": 0.3
}
```

### Set Priority

Manually boost or suppress a fact's salience priority.

```
POST /memory/priority
```

```json
{
  "predicate": "user_goal",
  "args": ["complete_task"],
  "priority": 0.9
}
```

Priority range: `0.0` (suppress) to `1.0` (maximum importance).

### Consolidation

Compress repeated episodic patterns into semantic summary facts.

```
POST /memory/consolidate
```

**Response:**

```json
{
  "factsConsolidated": 15,
  "newFacts": [
    {"predicate": "interested_in", "args": ["user_123", "machine_learning"]}
  ],
  "timestamp": 1708101234567
}
```

### Decay

Expire TTL'd facts and evict low-salience facts.

```
POST /memory/decay
```

```json
{"threshold": 0.05}
```

**Response:**

```json
{
  "expiredCount": 3,
  "evictedCount": 12,
  "removedAtoms": [...],
  "timestamp": 1708101234567
}
```

### Real-time Events (SSE)

Subscribe to knowledge change events via Server-Sent Events.

```
GET /memory/events
GET /memory/events?predicate=parent
GET /memory/events?events=fact_asserted,fact_retracted
GET /memory/events?since=42
```

**Event types:** `fact_asserted`, `fact_retracted`, `rule_asserted`, `fact_expired`, `consolidation_occurred`

```
data: {"type":"fact_asserted","atom":{"predicate":"parent","args":["alice","bob"]},"eventId":1,"timestamp":1708101234567}
```

---

## 6. Transactions

ACID transactions group multiple operations into an atomic unit.

### Workflow

```bash
# 1. Begin transaction
TX_ID=$(curl -sX POST http://localhost:9300/tx/begin \
  -H 'X-Database: default')

# 2. Assert facts within the transaction
curl -sX POST http://localhost:9300/assert/fact \
  -H 'Content-Type: application/json' \
  -H "X-Transaction-ID: $TX_ID" \
  -d '{"predicate":"account","args":["alice","500"]}'

curl -sX POST http://localhost:9300/assert/fact \
  -H 'Content-Type: application/json' \
  -H "X-Transaction-ID: $TX_ID" \
  -d '{"predicate":"account","args":["bob","300"]}'

# 3. Commit (validates constraints, applies atomically)
curl -sX POST "http://localhost:9300/tx/commit/$TX_ID" \
  -H 'X-Database: default'

# Or rollback to discard
curl -sX POST "http://localhost:9300/tx/rollback/$TX_ID" \
  -H 'X-Database: default'
```

### Validation on commit

- Constraint checking (uniqueness, exclusivity, range)
- Contradiction detection (A and NOT A)
- Cascade retraction via TMS

If validation fails, the transaction is rolled back and an error is returned.

---

## 7. MCP Protocol

NocturnusAI implements the [Model Context Protocol](https://modelcontextprotocol.io/) (MCP) for seamless AI agent integration.

### Configuration

Add to your MCP client settings (Claude Desktop, Cursor, Windsurf, etc.):

```json
{
  "mcpServers": {
    "nocturnusai": {
      "url": "http://localhost:9300/mcp/sse",
      "transport": "sse",
      "headers": {
        "X-Database": "default",
        "X-API-Key": "${NOCTURNUSAI_API_KEY}"
      }
    }
  }
}
```

### Endpoint

```
POST /mcp          — JSON-RPC 2.0 requests
GET  /mcp/sse      — Server-Sent Events stream
```

### Available Tools

| Tool | Description |
|------|-------------|
| `tell` | Store a fact (with optional TTL/temporal bounds) |
| `teach` | Define a logical rule (Horn clause) |
| `ask` | Run backward-chaining inference with optional proofs |
| `forget` | Retract a fact (cascading via TMS) |
| `recall` | Time-travel temporal queries at specific timestamps |
| `context` | Salience-ranked context window for agent reasoning |
| `compress` | Memory consolidation (episodic to semantic) |
| `cleanup` | Decay and eviction of stale/low-salience facts |
| `predicates` | Schema discovery (list all predicate types) |

### Example JSON-RPC call

```bash
# Initialize
curl -sX POST http://localhost:9300/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}'

# List tools
curl -sX POST http://localhost:9300/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'

# Call a tool
curl -sX POST http://localhost:9300/mcp \
  -H 'Content-Type: application/json' \
  -d '{
    "jsonrpc":"2.0","id":3,
    "method":"tools/call",
    "params": {
      "name": "tell",
      "arguments": {"predicate":"human","args":["socrates"]}
    }
  }'
```

### A2A Agent Discovery

NocturnusAI publishes an [Agent2Agent Protocol](https://google.github.io/A2A/) agent card:

```bash
curl http://localhost:9300/.well-known/agent.json
```

---

## 8. Python SDK

### Install

```bash
pip install nocturnusai

# With LangChain integration
pip install nocturnusai[langchain]
```

### Async client

```python
import asyncio
from nocturnusai import NocturnusAIClient

async def main():
    async with NocturnusAIClient(
        base_url="http://localhost:9300",
        api_key="your-key",         # optional
        database="default",
        tenant_id="default",
    ) as client:
        # Assert facts
        await client.assert_fact("parent", ["alice", "bob"])
        await client.assert_fact("parent", ["bob", "charlie"])

        # Assert a rule
        await client.assert_rule(
            head={"predicate": "grandparent", "args": ["?x", "?z"]},
            body=[
                {"predicate": "parent", "args": ["?x", "?y"]},
                {"predicate": "parent", "args": ["?y", "?z"]},
            ],
        )

        # Infer
        results = await client.infer("grandparent", ["?who", "charlie"])
        for atom in results:
            print(f"{atom.predicate}({', '.join(atom.args)})")

        # Infer with proof trees
        proofs = await client.infer(
            "grandparent", ["?who", "charlie"], with_proof=True
        )

        # Context window
        window = await client.context_window(max_facts=50, min_salience=0.1)
        for scored in window.facts:
            print(f"[{scored.salience:.3f}] {scored.atom.predicate}")

        # Temporal query
        import time
        one_hour_ago = int((time.time() - 3600) * 1000)
        past = await client.temporal_query(
            "location", ["alice", "?where"], timestamp=one_hour_ago
        )

        # Set priority
        await client.set_priority("user_goal", ["complete_task"], priority=0.9)

        # Consolidate and decay
        consol = await client.consolidate()
        decay = await client.decay(threshold=0.05)

        # Retract
        await client.retract("parent", ["bob", "charlie"])

        # Schema discovery
        schema = await client.predicates()

        # Health check
        health = await client.health()

asyncio.run(main())
```

### Sync client

```python
from nocturnusai import SyncNocturnusAIClient

with SyncNocturnusAIClient("http://localhost:9300") as client:
    client.assert_fact("human", ["socrates"])
    client.assert_rule(
        head={"predicate": "mortal", "args": ["?x"]},
        body=[{"predicate": "human", "args": ["?x"]}],
    )
    results = client.infer("mortal", ["?who"])
    print(results)
```

### Temporal facts

```python
import time

async with NocturnusAIClient("http://localhost:9300") as client:
    now = int(time.time() * 1000)

    # Fact valid for the next hour
    await client.assert_fact(
        "location", ["alice", "office"],
        ttl=3600000,
        metadata={"source": "gps"},
    )

    # Fact valid within a date range
    await client.assert_fact(
        "on_vacation", ["alice"],
        valid_from=now,
        valid_until=now + 7 * 86400000,  # 7 days
    )
```

### Auth management

```python
async with NocturnusAIClient("http://localhost:9300") as client:
    # Check auth status
    status = await client.auth_status()

    # Bootstrap first admin key (only when no keys exist)
    admin = await client.bootstrap(name="my-admin")
    print(f"Admin key: {admin['key']}")  # save this!

    # Create scoped keys (requires admin key)
    client._api_key = admin['key']
    writer = await client.create_key(
        name="agent-writer",
        role="writer",
        databases=["prod"],
        expires_in_days=90,
    )

    # List and revoke keys
    keys = await client.list_keys()
    await client.revoke_key(keys[0]["id"])
```

### Method reference

| Method | Description |
|--------|-------------|
| `assert_fact(predicate, args, ...)` | Store a fact |
| `assert_rule(head, body, scope)` | Define a rule |
| `query(predicate, args, scope)` | Match stored facts |
| `infer(predicate, args, scope, with_proof)` | Run inference |
| `retract(predicate, args, scope)` | Remove a fact |
| `context_window(max_facts, min_salience, predicates, scope)` | Get context window |
| `temporal_query(predicate, args, timestamp, scope)` | Point-in-time query |
| `consolidate()` | Compress episodic patterns |
| `decay(threshold)` | Expire/evict stale facts |
| `set_priority(predicate, args, priority, scope)` | Set salience priority |
| `execute(command)` | Run Logiql DSL |
| `predicates(scope)` | Schema discovery |
| `health()` | Server health check |
| `auth_status()` | Check auth mode |
| `bootstrap(name, description)` | Create first admin key |
| `create_key(name, role, databases, tenants, ...)` | Create API key |
| `list_keys()` | List all API keys |
| `revoke_key(key_id)` | Revoke an API key |
| `whoami()` | Current key identity |

---

## 9. TypeScript SDK

### Install

```bash
npm install nocturnusai-sdk
```

Zero runtime dependencies — uses built-in `fetch`.

### Usage

```typescript
import { NocturnusAIClient } from 'nocturnusai-sdk';

const client = new NocturnusAIClient({
  baseUrl: 'http://localhost:9300',
  apiKey: 'your-key',        // optional
  database: 'default',
  tenantId: 'default',
});

// Assert facts
await client.assertFact('parent', ['alice', 'bob']);
await client.assertFact('parent', ['bob', 'charlie']);

// Assert rules
await client.assertRule(
  { predicate: 'grandparent', args: ['?x', '?z'] },
  [
    { predicate: 'parent', args: ['?x', '?y'] },
    { predicate: 'parent', args: ['?y', '?z'] },
  ]
);

// Infer
const results = await client.infer('grandparent', ['?who', 'charlie']);
console.log(results);

// With proofs
const proofs = await client.infer('grandparent', ['?who', 'charlie'], {
  withProof: true,
});

// Context window
const ctx = await client.contextWindow({
  maxFacts: 50,
  minSalience: 0.1,
});
console.log(`${ctx.windowSize} of ${ctx.totalAvailable} facts`);

// Temporal query
const pastFacts = await client.temporalQuery(
  'location', ['?user', '?place'],
  Date.now() - 86400000 // 24 hours ago
);

// Set priority
await client.setPriority('user_preference', ['alice', 'dark_mode'], 0.9);

// Consolidate and decay
const consolidation = await client.consolidate();
const decay = await client.decay(0.05);

// Retract
await client.retract('parent', ['bob', 'charlie']);

// Schema discovery
const schema = await client.predicates();

// Health
const health = await client.health();

// Execute DSL
const dslResult = await client.execute('ASSERT human(socrates).');
```

### Transactions

```typescript
const txId = await client.beginTransaction();
await client.assertFact('account', ['alice', '500'], { transactionId: txId });
await client.assertFact('account', ['bob', '300'], { transactionId: txId });
await client.commitTransaction(txId);
// or: await client.rollbackTransaction(txId);
```

### SSE event subscription

```typescript
const unsubscribe = client.subscribeEvents(
  {
    events: ['fact_asserted', 'fact_retracted'],
    predicate: 'parent',
  },
  (event) => {
    console.log(`Event: ${event.type}`, event);
  }
);

// Later, to stop listening:
unsubscribe();
```

### Auth management

```typescript
// Check status
const status = await client.authStatus();

// Bootstrap first admin key
const admin = await client.bootstrap('my-admin');
console.log(`Admin key: ${admin.key}`);

// Create scoped keys
const writer = await client.createKey({
  name: 'agent-writer',
  role: 'writer',
  databases: ['prod'],
  expiresInDays: 90,
});

// List and revoke
const keys = await client.listKeys();
await client.revokeKey(keys[0].id);
```

---

## 10. LangChain Integration

The Python SDK includes first-class LangChain tool wrappers.

### Install

```bash
pip install nocturnusai[langchain]
```

### Quick start

```python
from nocturnusai import SyncNocturnusAIClient
from nocturnusai.langchain import get_nocturnusai_tools

client = SyncNocturnusAIClient("http://localhost:9300")
tools = get_nocturnusai_tools(client)

# tools = [
#   NocturnusAIAssertTool   — nocturnusai_assert
#   NocturnusAIQueryTool    — nocturnusai_query
#   NocturnusAIInferTool    — nocturnusai_infer
#   NocturnusAIContextTool  — nocturnusai_context
# ]
```

### With a LangChain agent

```python
from langchain_anthropic import ChatAnthropic
from langchain.agents import AgentExecutor, create_tool_calling_agent
from langchain_core.prompts import ChatPromptTemplate

llm = ChatAnthropic(model="claude-sonnet-4-20250514")

prompt = ChatPromptTemplate.from_messages([
    ("system", "You are an assistant with access to a logic knowledge base."),
    ("human", "{input}"),
    ("placeholder", "{agent_scratchpad}"),
])

agent = create_tool_calling_agent(llm, tools, prompt)
executor = AgentExecutor(agent=agent, tools=tools)

result = executor.invoke({
    "input": "Alice is Bob's parent. Bob is Charlie's parent. Who is Charlie's grandparent?"
})
```

### Available tools

| Tool Name | Description |
|-----------|-------------|
| `nocturnusai_assert` | Assert a fact into the knowledge base |
| `nocturnusai_query` | Query facts matching a pattern |
| `nocturnusai_infer` | Run multi-step logical inference |
| `nocturnusai_context` | Get salience-ranked context window |

---

## 11. CLI Reference

The interactive REPL connects to a running NocturnusAI server.

### Start

```bash
# Via Makefile
make cli

# Via Gradle
./gradlew :nocturnusai-cli:run --console=plain

# With options
./gradlew :nocturnusai-cli:run --args='--server http://host:9300 --db mydb --api-key secret'

# Execute a single command
./gradlew :nocturnusai-cli:run --args='-e "tell human(socrates)"'
```

### Commands

| Command | Shortcut | Example | Description |
|---------|----------|---------|-------------|
| `tell` | `+` | `+ parent(alice, bob)` | Assert a fact |
| `teach` | `++` | `++ mortal(?x) :- human(?x)` | Assert a rule |
| `ask` | `?` | `? mortal(?who)` | Run inference |
| `forget` | `-` | `- parent(alice, bob)` | Retract a fact |
| `inspect` | `ls` | `ls parent` | List facts (optional filter) |
| `context` | `ctx` | `ctx 50` | Show top-N salient facts |
| `compress` | | `compress` | Run memory consolidation |
| `cleanup` | | `cleanup` | Run decay/eviction |
| `ingest` | | `ingest Alice is an engineer` | Extract facts from natural language |
| `import` | `load` | `import kb.ab` | Load facts from file |
| `export` | `dump` | `export` | Dump all facts/rules |
| `dsl` | `exec` | `dsl ASSERT human(socrates).` | Execute Logiql DSL |
| `use` | | `use mydb` | Switch database |
| `dbs` | | `dbs` | List databases |
| `health` | | `health` | Server health check |
| `setup` | | `setup` | Bootstrap auth |
| `login` | | `login` | Authenticate with API key |
| `whoami` | | `whoami` | Show current identity |
| `keys` | | `keys list` | Manage API keys |
| `help` | `h` | `help` | Show all commands |
| `exit` | `q` | `q` | Exit REPL |

### Example session

```
nocturnusai> + human(socrates)
Stored: human(socrates)

nocturnusai> + human(plato)
Stored: human(plato)

nocturnusai> ++ mortal(?x) :- human(?x)
Taught: mortal(?x) :- human(?x)

nocturnusai> ? mortal(?who)
Results:
  mortal(socrates)
  mortal(plato)

nocturnusai> ls
  human(socrates)
  human(plato)
Rules:
  mortal(?x) :- human(?x)

nocturnusai> - human(plato)
Forgot: human(plato)

nocturnusai> ? mortal(?who)
Results:
  mortal(socrates)
```

---

## 12. Authentication & Authorization

Three auth modes, from zero-config dev to production RBAC.

### Mode 1: Dev mode (default)

No configuration needed. All requests are accepted without authentication.

### Mode 2: Legacy single key

Set `API_KEY` in your `.env`:

```env
API_KEY=my-secret-key
```

All requests must include the header:

```
X-API-Key: my-secret-key
```

### Mode 3: RBAC (recommended for production)

Full role-based access control with managed API keys.

**Enable:**

```env
AUTH_ENABLED=true
NOCTURNUSAI_ADMIN_USER=admin
NOCTURNUSAI_ADMIN_PASS=change-me-in-production
```

**Bootstrap the first admin key:**

```bash
curl -sX POST http://localhost:9300/auth/bootstrap \
  -H 'Content-Type: application/json' \
  -d '{"name":"admin","description":"Initial admin key"}'
```

Response (save the `key` — it's shown only once):

```json
{
  "id": "uuid",
  "name": "admin",
  "key": "ab_live_xxxxxxxxxxxx",
  "prefix": "ab_live_xx",
  "role": "admin"
}
```

**Create scoped keys:**

```bash
# Writer key for agents (scoped to specific databases)
curl -sX POST http://localhost:9300/auth/keys \
  -H 'Content-Type: application/json' \
  -H 'X-API-Key: ab_live_xxxxxxxxxxxx' \
  -d '{
    "name": "agent-writer",
    "role": "writer",
    "databases": ["prod"],
    "expiresInDays": 90
  }'

# Read-only key for dashboards
curl -sX POST http://localhost:9300/auth/keys \
  -H 'Content-Type: application/json' \
  -H 'X-API-Key: ab_live_xxxxxxxxxxxx' \
  -d '{
    "name": "dashboard-reader",
    "role": "reader"
  }'
```

**Roles:**

| Role | Permissions |
|------|------------|
| `admin` | Full access: CRUD keys, all databases, all operations |
| `writer` | Assert, retract, infer, memory ops, execute DSL |
| `reader` | Infer, query, context window, health, predicates |

**Key management endpoints:**

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/auth/status` | GET | Check auth mode |
| `/auth/bootstrap` | POST | Create first admin key |
| `/auth/keys` | GET | List all keys |
| `/auth/keys` | POST | Create a new key |
| `/auth/keys/{id}` | GET | Get key details |
| `/auth/keys/{id}` | PATCH | Update key properties |
| `/auth/keys/{id}` | DELETE | Revoke a key |
| `/auth/whoami` | GET | Current key identity |

---

## 13. LLM-Powered Features

NocturnusAI can use an LLM for natural language fact extraction and question answering. Configure one provider in your `.env`.

### Provider priority (auto-detected)

| Priority | Provider | Env var | Default model |
|----------|----------|---------|---------------|
| 1 | Anthropic | `ANTHROPIC_API_KEY` | claude-sonnet-4-20250514 |
| 2 | OpenAI | `OPENAI_API_KEY` | gpt-4o-mini |
| 3 | Google Gemini | `GOOGLE_API_KEY` | gemini-2.0-flash |
| 4 | Ollama (local) | `LLM_PROVIDER=ollama` | llama3.2 |
| 5 | Custom OpenAI-compatible | `LLM_API_KEY` + `LLM_BASE_URL` | (set `LLM_MODEL`) |

### Fact extraction

Extract structured facts from natural language text.

```bash
curl -sX POST http://localhost:9300/extract \
  -H 'Content-Type: application/json' \
  -d '{
    "text": "Alice is a software engineer at Acme Corp. She manages Bob and Carol.",
    "assert": true,
    "rules": true
  }'
```

**Response:**

```json
{
  "facts": [
    {"predicate": "occupation", "args": ["alice", "software_engineer"]},
    {"predicate": "works_at", "args": ["alice", "acme_corp"]},
    {"predicate": "manages", "args": ["alice", "bob"]},
    {"predicate": "manages", "args": ["alice", "carol"]}
  ],
  "rules": [],
  "asserted": true,
  "provider": "anthropic",
  "model": "claude-sonnet-4-20250514"
}
```

### Question synthesis

Answer natural language questions using the knowledge base + LLM.

```bash
curl -sX POST http://localhost:9300/synthesize \
  -H 'Content-Type: application/json' \
  -d '{"question": "Who does Alice manage?"}'
```

---

## 14. Multi-Tenancy

NocturnusAI supports two levels of data isolation.

### Databases

Completely separate knowledge bases. Selected via `X-Database` header.

```bash
# Create a database
curl -sX POST http://localhost:9300/admin/databases \
  -H 'Content-Type: application/json' \
  -d '{"name":"production"}'

# List databases
curl http://localhost:9300/admin/databases

# Use a database
curl -sX POST http://localhost:9300/tell \
  -H 'Content-Type: application/json' \
  -H 'X-Database: production' \
  -d '{"predicate":"env","args":["prod"]}'

# Delete a database
curl -sX DELETE http://localhost:9300/admin/databases/production
```

### Tenants

Isolated partitions within a database. Selected via `X-Tenant-ID` header. Enable multi-tenancy when creating the database.

```bash
# Create tenant
curl -sX POST http://localhost:9300/admin/databases/mydb/tenants \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":"tenant-a"}'

# Use tenant
curl -sX POST http://localhost:9300/tell \
  -H 'Content-Type: application/json' \
  -H 'X-Database: mydb' \
  -H 'X-Tenant-ID: tenant-a' \
  -d '{"predicate":"user","args":["alice"]}'

# List tenants
curl http://localhost:9300/admin/databases/mydb/tenants

# Nuke tenant data
curl -sX POST http://localhost:9300/admin/databases/mydb/tenants/tenant-a/nuke
```

### Export data

```bash
# All facts in a database
curl http://localhost:9300/admin/databases/mydb/facts

# All rules
curl http://localhost:9300/admin/databases/mydb/rules

# Tenant-scoped
curl http://localhost:9300/admin/databases/mydb/facts \
  -H 'X-Tenant-ID: tenant-a'
```

---

## 15. Observability & Monitoring

### Health checks

```bash
# Full health with component status
curl http://localhost:9300/health

# Liveness probe (returns "OK")
curl http://localhost:9300/health/live

# Readiness probe
curl http://localhost:9300/health/ready
```

### Prometheus metrics

```bash
curl http://localhost:9300/metrics
```

Exposed metrics include request counts, latencies, inference durations, memory stats, and database sizes.

### Grafana dashboards

Start with monitoring:

```bash
make up-monitoring
# or
docker compose --profile monitoring up -d --build
```

- **Grafana:** http://localhost:3000 (admin / nocturnusai)
- **Prometheus:** http://localhost:9090

Pre-configured dashboards for request rates, inference performance, and memory utilization.

### Structured logging

```env
LOG_FORMAT=json       # JSON for ELK/Datadog (default: text)
LOG_LEVEL=INFO        # DEBUG, INFO, WARN, ERROR
LOG_FILE=/data/nocturnusai.log  # Enable file logging (50MB rotation, 30 day retention)
```

Each log line includes `requestId`, `database`, and `tenantId` for correlation.

### Auto-generated API docs

```bash
# Human-readable API documentation (generated on startup)
curl http://localhost:9300/llm.txt

# User guide
curl http://localhost:9300/userguide
```

---

## 16. Production Deployment

### Docker production setup

```bash
# 1. Clone the repository
git clone https://github.com/Auctalis/nocturnusai.git
cd nocturnusai

# 2. Configure environment
cp .env.example .env

# 3. Start the server and monitoring stack
docker compose --profile monitoring up -d --build
```

Edit `.env`:

```env
# Auth — required in production
AUTH_ENABLED=true
NOCTURNUSAI_ADMIN_USER=admin
NOCTURNUSAI_ADMIN_PASS=<strong-password>

# LLM provider
ANTHROPIC_API_KEY=sk-ant-...

# Encryption at rest
ENCRYPTION_KEY=<64-hex-chars>  # generate: openssl rand -hex 32

# TLS
TLS_ENABLED=true
TLS_PORT=9443
TLS_KEYSTORE_PATH=/data/keystore.p12
TLS_KEYSTORE_PASSWORD=<keystore-password>

# Structured logging
LOG_FORMAT=json
LOG_LEVEL=INFO
LOG_FILE=/data/nocturnusai.log

# Monitoring
GRAFANA_USER=admin
GRAFANA_PASSWORD=<grafana-password>
```

```bash
# 3. Start
docker compose --profile monitoring up -d

# 4. Bootstrap admin key
curl -sX POST http://localhost:9300/auth/bootstrap \
  -H 'Content-Type: application/json' \
  -d '{"name":"admin"}'
# Save the returned key!

# 5. Create application keys
curl -sX POST http://localhost:9300/auth/keys \
  -H 'Content-Type: application/json' \
  -H 'X-API-Key: <admin-key>' \
  -d '{"name":"app-writer","role":"writer","databases":["prod"],"expiresInDays":90}'
```

### Kubernetes

The Docker image works directly in Kubernetes. Key considerations:

```yaml
# Deployment
spec:
  containers:
    - name: nocturnusai
      image: nocturnusai:latest
      ports:
        - containerPort: 9300
      env:
        - name: PORT
          value: "9300"
        - name: STORAGE_DIR
          value: "/data"
        - name: AUTH_ENABLED
          value: "true"
      volumeMounts:
        - name: data
          mountPath: /data
      livenessProbe:
        httpGet:
          path: /health/live
          port: 9300
        initialDelaySeconds: 30
        periodSeconds: 10
      readinessProbe:
        httpGet:
          path: /health/ready
          port: 9300
        initialDelaySeconds: 30
        periodSeconds: 10
      resources:
        requests:
          memory: "512Mi"
          cpu: "250m"
        limits:
          memory: "2Gi"
          cpu: "2000m"
  volumes:
    - name: data
      persistentVolumeClaim:
        claimName: nocturnusai-data
```

### Reverse proxy (nginx)

```nginx
upstream nocturnusai {
    server 127.0.0.1:9300;
}

server {
    listen 443 ssl http2;
    server_name nocturnusai.example.com;

    ssl_certificate /etc/ssl/certs/nocturnusai.crt;
    ssl_certificate_key /etc/ssl/private/nocturnusai.key;

    location / {
        proxy_pass http://nocturnusai;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # SSE endpoints need special handling
    location /memory/events {
        proxy_pass http://nocturnusai;
        proxy_set_header Connection '';
        proxy_http_version 1.1;
        chunked_transfer_encoding off;
        proxy_buffering off;
        proxy_cache off;
    }

    location /mcp/sse {
        proxy_pass http://nocturnusai;
        proxy_set_header Connection '';
        proxy_http_version 1.1;
        chunked_transfer_encoding off;
        proxy_buffering off;
        proxy_cache off;
    }
}
```

### Production checklist

- [ ] `AUTH_ENABLED=true` with strong admin password
- [ ] RBAC keys created with minimal scope (database + tenant restrictions)
- [ ] `ENCRYPTION_KEY` set for encryption at rest
- [ ] TLS enabled or behind a TLS-terminating reverse proxy
- [ ] `LOG_FORMAT=json` for structured log aggregation
- [ ] Prometheus + Grafana for metrics monitoring
- [ ] Persistent volume for `/data` (WAL + snapshots)
- [ ] Health check probes configured (`/health/live`, `/health/ready`)
- [ ] Backups scheduled (`POST /admin/backups`)
- [ ] Resource limits set (memory, CPU)
- [ ] Non-default admin credentials

---

## 17. Replication & Backup

### Leader-follower replication

NocturnusAI supports leader-follower replication for read scalability and disaster recovery.

**Leader configuration (default):**

```env
REPLICATION_MODE=LEADER
```

The leader exposes its WAL via `GET /replication/wal?since=<seq>`.

**Follower configuration:**

```env
REPLICATION_MODE=FOLLOWER
LEADER_URL=http://leader-host:9300
```

The follower continuously polls the leader's WAL and replays writes in order.

### Backups

```bash
# Create a backup
curl -sX POST 'http://localhost:9300/admin/backups?db=default'
```

Response: `"Backup created at: /data/backups/<timestamp>.tar.gz"`

Backup includes:
- Write-ahead log (WAL)
- Full state snapshot
- Database configuration

### Recovery

On startup, NocturnusAI automatically:
1. Loads the latest snapshot
2. Replays WAL entries since the snapshot

No manual recovery steps needed — just restart with the data volume intact.

---

## 18. Security Hardening

### Encryption at rest

Generate a 256-bit AES key and set it in `.env`:

```bash
openssl rand -hex 32
```

```env
ENCRYPTION_KEY=a1b2c3d4...  # 64 hex characters
```

WAL and snapshot files will be encrypted with AES-256.

### TLS

NocturnusAI supports native TLS:

```env
TLS_ENABLED=true
TLS_PORT=9443
TLS_KEYSTORE_PATH=/data/keystore.p12
TLS_KEYSTORE_PASSWORD=changeit
TLS_KEY_ALIAS=nocturnusai
TLS_KEY_PASSWORD=changeit
```

Generate a self-signed keystore for testing:

```bash
keytool -genkeypair -alias nocturnusai -keyalg RSA -keysize 2048 \
  -storetype PKCS12 -keystore keystore.p12 -validity 365 \
  -storepass changeit -keypass changeit \
  -dname "CN=localhost"
```

### Security headers

NocturnusAI validates all input headers:
- `X-Database` — alphanumeric, hyphens, underscores only
- `X-Tenant-ID` — same validation
- Request body size limits enforced by Ktor/Netty

### Non-root container

The Docker image runs as user `nocturnusai` (non-root) with minimal Alpine base.

---

## 19. Environment Variable Reference

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `9300` | Server port |
| `HOST` | `0.0.0.0` | Bind address |
| `STORAGE_DIR` | `/data` | WAL/snapshot directory |
| `API_KEY` | — | Legacy single-key auth |
| `AUTH_ENABLED` | `false` | Enable RBAC auth |
| `NOCTURNUSAI_ADMIN_USER` | `admin` | RBAC bootstrap username |
| `NOCTURNUSAI_ADMIN_PASS` | `nocturnusai` | RBAC bootstrap password |
| `ENCRYPTION_KEY` | — | AES-256 key (64 hex chars) |
| `TLS_ENABLED` | `false` | Enable native TLS |
| `TLS_PORT` | `9443` | HTTPS port |
| `TLS_KEYSTORE_PATH` | — | Path to PKCS12 keystore |
| `TLS_KEYSTORE_PASSWORD` | — | Keystore password |
| `TLS_KEY_ALIAS` | `nocturnusai` | Key alias in keystore |
| `TLS_KEY_PASSWORD` | — | Private key password |
| `REPLICATION_MODE` | `LEADER` | `LEADER` or `FOLLOWER` |
| `LEADER_URL` | — | Leader URL (follower mode) |
| `ANTHROPIC_API_KEY` | — | Anthropic Claude API key |
| `OPENAI_API_KEY` | — | OpenAI API key |
| `GOOGLE_API_KEY` | — | Google Gemini API key |
| `LLM_PROVIDER` | auto | `ollama`, `custom`, or auto-detect |
| `LLM_MODEL` | varies | Override default model |
| `LLM_BASE_URL` | — | Custom OpenAI-compatible endpoint |
| `LLM_API_KEY` | — | API key for custom provider |
| `EXTRACTION_ENABLED` | `true` | Enable NL fact extraction |
| `EXTRACTION_MAX_FACTS` | `50` | Max facts per extraction |
| `LOG_FORMAT` | `text` | `text` or `json` |
| `LOG_LEVEL` | `INFO` | `DEBUG`, `INFO`, `WARN`, `ERROR` |
| `LOG_FILE` | — | File path for log rotation |
| `GRAFANA_USER` | `admin` | Grafana admin username |
| `GRAFANA_PASSWORD` | `nocturnusai` | Grafana admin password |

---

## 20. Architecture Deep Dive

### Module structure

```
nocturnusai-core          Pure logic engine (no HTTP, no framework dependencies)
  ├── core/             Domain: Atom, Term, Rule, LogicContext
  ├── storage/          Hexastore (6-way indexed triple store)
  ├── inference/        BackwardChainer (SLD resolution), ReteEngine (forward)
  ├── logic/            ProvenanceTracker (TMS), ConsistencyGuard
  ├── memory/           MemoryManager, SalienceTracker, EventBus
  ├── persistence/      WriteAheadLog, SnapshotManager, encryption
  ├── transaction/      TransactionManager (ACID)
  └── parser/           Logiql DSL tokenizer + parser

nocturnusai-server        Ktor HTTP wrapper around core
  ├── routes/           REST endpoints (logic, memory, admin, MCP, auth, etc.)
  ├── auth/             RBAC API key management
  ├── llm/              Anthropic/OpenAI/Google/Ollama providers
  └── observability/    Metrics, structured logging

nocturnusai-cli           Interactive REPL client
```

### Hexastore indexing

All binary predicates are stored in 6 index permutations for efficient pattern matching:

| Index | Key order | Optimized for |
|-------|-----------|--------------|
| SPO | subject → predicate → object | Known subject queries |
| SOP | subject → object → predicate | Subject + object lookups |
| PSO | predicate → subject → object | Predicate scans |
| POS | predicate → object → subject | Object lookups by predicate |
| OSP | object → subject → predicate | Known object queries |
| OPS | object → predicate → subject | Object + predicate lookups |

Non-binary predicates (arity != 2) use a fallback hash map. Thread safety is provided by `ReentrantReadWriteLock`.

### Inference pipeline

1. **Query arrives** at `/infer` or `/ask`
2. **BackwardChainer** starts SLD resolution from the goal
3. For each goal atom:
   - Try direct **fact match** from Hexastore
   - Try **rule application**: unify rule head with goal, then prove body conditions recursively
4. **Unifier** handles variable binding and substitution propagation
5. **Depth limit** (100) prevents infinite recursion
6. Results are returned with optional **proof trees**

### Salience scoring

```
salience = recency_score × frequency_score × priority_score

recency_score  = exp(-age_ms / half_life_ms)
frequency_score = min(access_count / reference_count, 1.0)
priority_score  = explicit_priority  (0.0 to 1.0, default 0.5)
```

### Data durability

```
Write operation
  → Append to WAL (synchronous)
  → Update in-memory Hexastore
  → Periodic snapshot (full state JSON)

Recovery
  → Load latest snapshot
  → Replay WAL entries since snapshot
```

### Tech stack

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

---

## Appendix: Complete Endpoint Index

| Method | Path | Description |
|--------|------|-------------|
| POST | `/tell` | Assert a fact (simplified) |
| POST | `/ask` | Run inference (simplified) |
| POST | `/teach` | Assert a rule (simplified) |
| POST | `/forget` | Retract a fact (simplified) |
| POST | `/assert/fact` | Assert a fact |
| POST | `/assert/rule` | Assert a rule |
| POST | `/assert/template` | Apply logic template |
| POST | `/infer` | Run backward-chaining inference |
| POST | `/retract` | Retract a fact |
| POST | `/execute` | Execute Logiql DSL |
| GET | `/predicates` | Schema discovery |
| POST | `/memory/query/temporal` | Point-in-time query |
| POST | `/memory/query/salient` | Salience-ranked query |
| POST | `/memory/context` | Get context window |
| POST | `/memory/priority` | Set fact priority |
| POST | `/memory/consolidate` | Memory consolidation |
| POST | `/memory/decay` | Memory decay |
| GET | `/memory/events` | SSE event stream |
| POST | `/tx/begin` | Begin transaction |
| POST | `/tx/commit/{id}` | Commit transaction |
| POST | `/tx/rollback/{id}` | Rollback transaction |
| POST | `/mcp` | MCP JSON-RPC 2.0 |
| GET | `/mcp/sse` | MCP SSE stream |
| POST | `/extract` | NL fact extraction |
| POST | `/synthesize` | LLM Q&A synthesis |
| GET | `/health` | Full health check |
| GET | `/health/live` | Liveness probe |
| GET | `/health/ready` | Readiness probe |
| GET | `/metrics` | Prometheus metrics |
| GET | `/llm.txt` | Auto-generated API docs |
| GET | `/userguide` | User guide content |
| GET | `/.well-known/agent.json` | A2A agent card |
| GET | `/auth/status` | Auth mode status |
| POST | `/auth/bootstrap` | Create first admin key |
| GET | `/auth/keys` | List API keys |
| POST | `/auth/keys` | Create API key |
| GET | `/auth/keys/{id}` | Get key details |
| PATCH | `/auth/keys/{id}` | Update key |
| DELETE | `/auth/keys/{id}` | Revoke key |
| GET | `/auth/whoami` | Current identity |
| POST | `/admin/databases` | Create database |
| GET | `/admin/databases` | List databases |
| DELETE | `/admin/databases/{name}` | Delete database |
| GET | `/admin/databases/{name}/facts` | Export facts |
| GET | `/admin/databases/{name}/rules` | Export rules |
| POST | `/admin/databases/{name}/tenants` | Create tenant |
| GET | `/admin/databases/{name}/tenants` | List tenants |
| DELETE | `/admin/databases/{name}/tenants/{id}` | Delete tenant |
| POST | `/admin/databases/{name}/nuke` | Clear database |
| POST | `/admin/databases/{name}/tenants/{id}/nuke` | Clear tenant |
| POST | `/admin/backups` | Create backup |
| GET | `/replication/wal` | WAL stream (leader) |
