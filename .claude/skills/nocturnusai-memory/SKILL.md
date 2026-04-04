---
name: nocturnusai-memory
description: >
  Use when working with NocturnusAI agent memory — context windows, salience
  scoring, temporal queries, recall, consolidation, decay, TTL, expiration,
  event streaming, or memory lifecycle management.
  Triggers on: memory, context window, salience, temporal, recall, consolidate,
  decay, TTL, expire, events, NocturnusAI agent memory.
---

# NocturnusAI Agent Memory

Memory lifecycle management for AI agents: salience-ranked retrieval, temporal queries, consolidation, decay, and event streaming. This layer sits on top of the core knowledge base (see `nocturnusai-knowledge` skill for basic CRUD).

**Prerequisites**: Server must be running and tenant created. See the `nocturnusai-connect` skill for setup.

## Key Concepts

### Salience Scoring

Every fact has a composite salience score computed from three factors:

| Factor | Description | Effect |
|--------|-------------|--------|
| Recency | Time since the fact was last accessed or asserted | Recent facts score higher |
| Frequency | How often the fact has been queried or referenced | Frequently used facts score higher |
| Priority | Explicit priority value set via `/memory/priority` | Manually boosted facts score higher |

The composite score (0.0-1.0) determines which facts are most relevant for an agent's context window. Facts with low salience are candidates for eviction during decay.

### Temporal Atoms

Every fact carries temporal metadata:

| Field | Type | Purpose |
|-------|------|---------|
| `createdAt` | epoch ms | When the fact was first asserted (set automatically) |
| `validFrom` | epoch ms | Earliest time the fact is considered valid (optional) |
| `validUntil` | epoch ms | Latest time the fact is considered valid (optional) |
| `ttl` | milliseconds | Time-to-live duration from assertion time (optional) |

Temporal fields enable point-in-time queries ("What was true yesterday?") and automatic expiration of short-lived facts.

### Memory Lifecycle Overview

```
Assert facts (tell) ──> Facts accumulate with temporal metadata
                            │
                            ▼
Query context (context) ──> Salience-ranked facts for LLM prompt
                            │
                            ▼
Recall past state (recall) ──> Point-in-time temporal queries
                            │
                            ▼
Consolidate (compress) ──> Merge repeated patterns into summaries
                            │
                            ▼
Decay (cleanup) ──────────> Expire TTL'd facts, evict low-salience
```

## Context Window

The `context` MCP tool returns the most relevant facts ranked by salience, designed for populating an LLM's context window with the most important knowledge.

**All params are optional**:

| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `maxFacts` | number | 100 | Maximum facts to return |
| `minRelevance` | number | 0.0 | Minimum salience score (0.0-1.0) |
| `predicates` | string array | all | Filter to only these predicate types |
| `scope` | string | all scopes | Optional scope filter |

**MCP example**:

```bash
curl -X POST http://localhost:9300/mcp \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: default" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "tools/call",
    "params": {
      "name": "context",
      "arguments": {
        "maxFacts": 50,
        "minRelevance": 0.1,
        "predicates": ["user_preference", "conversation_topic"]
      }
    }
  }'
```

**Response** (in `result.content[0].text`):

```
Context Window (12/47 facts):
Predicates: {user_preference=5, conversation_topic=7}

  [salience=0.920] user_preference(alice, dark_mode)
  [salience=0.815] conversation_topic(session_42, kotlin)
  ...
```

The output is pre-sorted by salience. Use this to populate your LLM prompt with the most relevant knowledge without retrieving everything.

## Temporal Queries

The `recall` MCP tool finds facts that were valid at a specific point in time. It checks `validFrom`, `validUntil`, and `ttl` bounds to determine validity at the given timestamp.

**Required params**: `predicate` (string), `args` (string array), `timestamp` (epoch milliseconds)

**Optional params**: `scope` (string)

**MCP example** -- "What location was alice at on Jan 1 2024?":

```bash
curl -X POST http://localhost:9300/mcp \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: default" \
  -d '{
    "jsonrpc": "2.0",
    "id": 2,
    "method": "tools/call",
    "params": {
      "name": "recall",
      "arguments": {
        "predicate": "location",
        "args": ["alice", "?where"],
        "timestamp": 1704067200000
      }
    }
  }'
```

**Response** (in `result.content[0].text`):

```
Found 1 fact(s) valid at timestamp 1704067200000:
  location(alice, london) [valid: 1703980800000 -> 1704153600000]
```

Use `?`-prefixed variables in args to match any value at that position, just like `ask`.

## TTL and Scheduled Facts

### Auto-Expiring Facts with TTL

Set `ttl` (in milliseconds) when asserting a fact to make it expire automatically after that duration.

**Example** -- fact expires after 1 hour (3600000 ms):

```bash
curl -X POST http://localhost:9300/mcp \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: default" \
  -d '{
    "jsonrpc": "2.0",
    "id": 3,
    "method": "tools/call",
    "params": {
      "name": "tell",
      "arguments": {
        "predicate": "session_active",
        "args": ["user_42"],
        "ttl": 3600000
      }
    }
  }'
```

### Time-Bounded Facts with validFrom/validUntil

Use `validFrom` and `validUntil` (epoch milliseconds) to create facts that are only valid within a specific time window. These facts exist in the store but only match temporal queries within their validity range.

**Example** -- meeting scheduled for a specific window:

```bash
curl -X POST http://localhost:9300/mcp \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: default" \
  -d '{
    "jsonrpc": "2.0",
    "id": 4,
    "method": "tools/call",
    "params": {
      "name": "tell",
      "arguments": {
        "predicate": "meeting",
        "args": ["standup", "room_3"],
        "validFrom": 1704092400000,
        "validUntil": 1704094200000
      }
    }
  }'
```

This fact is valid from 1704092400000 to 1704094200000. A `recall` query with a timestamp inside that range will find it; a timestamp outside will not.

## Consolidation

The `compress` MCP tool merges repeated episodic patterns into semantic summaries. It takes **no arguments**.

**MCP example**:

```bash
curl -X POST http://localhost:9300/mcp \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: default" \
  -d '{
    "jsonrpc": "2.0",
    "id": 5,
    "method": "tools/call",
    "params": {
      "name": "compress",
      "arguments": {}
    }
  }'
```

**Response** (in `result.content[0].text`):

```
Consolidated 3 pattern(s):
  NEW: asked_about_frequently(alice, kotlin)
  NEW: asked_about_frequently(alice, coroutines)
  NEW: visited_regularly(alice, docs_page)
```

**When to use**: Run consolidation after many similar events have accumulated (e.g., an agent has queried the same topics repeatedly across a session). Consolidation detects these episodic patterns and creates semantic summary facts, reducing memory bloat while preserving the knowledge signal.

## Decay

The `cleanup` MCP tool expires facts past their TTL and evicts facts with salience below a threshold.

**Optional param**: `threshold` (number) -- salience floor below which facts are evicted. Default: 0.05.

**MCP example**:

```bash
curl -X POST http://localhost:9300/mcp \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: default" \
  -d '{
    "jsonrpc": "2.0",
    "id": 6,
    "method": "tools/call",
    "params": {
      "name": "cleanup",
      "arguments": {
        "threshold": 0.05
      }
    }
  }'
```

**Response** (in `result.content[0].text`):

```
Decay complete: 4 expired, 7 evicted (11 total removed)
```

The response distinguishes between:
- **Expired**: Facts removed because their TTL or `validUntil` has passed.
- **Evicted**: Facts removed because their salience score fell below the threshold.

## Memory Hygiene Pattern

For long-running agent sessions, run this periodic maintenance workflow:

```
Step 1:  compress   (no arguments)
Step 2:  cleanup    (threshold: 0.05)
```

**Why this order matters**: Consolidation runs first to detect repeated patterns and merge them into summary facts before decay runs. If you run decay first, low-salience episodic facts might be evicted before consolidation can detect the patterns they form. Running compress first preserves the knowledge signal in summary form, then cleanup safely removes the remaining noise.

**Recommended cadence**: Every 100-500 fact assertions, or at natural session boundaries.

## Priority Boosting

Manually boost a fact's salience priority. This is **REST-only** -- there is no MCP tool for priority boosting.

**Endpoint**: `POST /memory/priority`

**Required fields**: `predicate` (string), `args` (string array), `priority` (number, 0.0-1.0)

**Optional fields**: `truthVal` (bool, default true), `scope` (string)

```bash
curl -X POST http://localhost:9300/memory/priority \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: default" \
  -d '{
    "predicate": "user_preference",
    "args": ["alice", "dark_mode"],
    "priority": 0.95
  }'
```

**Response**: `Priority set: 0.95 for user_preference(alice, dark_mode)`

Boosting priority directly increases the fact's composite salience score, making it more likely to appear in context windows and less likely to be evicted by decay.

## Event Streaming

Subscribe to real-time knowledge change events via Server-Sent Events (SSE).

**Endpoint**: `GET /memory/events`

**Required header**: `X-Tenant-ID` (events are tenant-scoped)

**Optional query params**:
- `predicate` -- filter to events about a specific predicate
- `events` -- comma-separated event types to subscribe to (default: all)
- `since` -- event ID to replay missed events from

**Event types**:

| Type | Fires When |
|------|-----------|
| `fact_asserted` | A new fact is added to the knowledge base |
| `fact_retracted` | A fact is removed (explicitly or via truth maintenance) |
| `fact_expired` | A fact's TTL or validUntil has passed |
| `rule_asserted` | A new rule is taught |
| `consolidation` | Episodic patterns are consolidated into summary facts |

**Example**:

```bash
curl -N http://localhost:9300/memory/events \
  -H "X-Database: default" \
  -H "X-Tenant-ID: default"
```

**Event format** (SSE `data:` lines):

```json
{"type":"fact_asserted","eventId":42,"timestamp":1704067200000,"atom":{"predicate":"likes","args":["alice","bob"]}}
{"type":"fact_expired","eventId":43,"timestamp":1704070800000,"atom":{"predicate":"session_active","args":["user_42"]}}
```

Use event streaming for reactive agent architectures that need to respond to knowledge changes in real time.

## Gotchas

1. **`compress` and `cleanup` take no predicate/args.** They operate on the entire tenant's memory, not on specific facts. You cannot target consolidation or decay to a single predicate.

2. **`recall` timestamp is epoch milliseconds, not ISO 8601.** Use numeric timestamps like `1704067200000`, not strings like `"2024-01-01T00:00:00Z"`. The parameter is a JSON number.

3. **Priority boosting is REST-only.** There is no `prioritize` MCP tool. You must use `POST /memory/priority` with the standard HTTP headers.

4. **SSE events are tenant-scoped via headers, not tool params.** The `X-Tenant-ID` header on the SSE connection determines which tenant's events you receive. There is no way to specify tenant in query params.

5. **`cleanup` threshold is a salience floor.** Facts with salience **below** the threshold get evicted. A threshold of `0.05` removes facts scoring under 0.05. Setting it to `0.0` only expires TTL-past facts without salience-based eviction.

6. **`context` uses `minRelevance`, not `minSalience`.** The MCP tool parameter is named `minRelevance` (mapped to `minSalience` internally). The REST endpoint `/memory/context` uses `minSalience` in the request body.

7. **TTL is a duration, not a deadline.** The `ttl` param on `tell` is milliseconds from assertion time (e.g., `3600000` = 1 hour from now). Use `validUntil` for an absolute epoch deadline.

8. **Run `compress` before `cleanup`.** Reversing the order may evict episodic facts before consolidation can detect their patterns. See the Memory Hygiene Pattern section above.
