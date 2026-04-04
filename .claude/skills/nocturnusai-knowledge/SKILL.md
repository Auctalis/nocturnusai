---
name: nocturnusai-knowledge
description: >
  Use when working with NocturnusAI facts and rules — asserting (tell),
  querying (ask/infer), teaching rules (teach), retracting (forget),
  bulk operations, aggregation, or discovering predicates. Covers the core
  knowledge base CRUD operations.
  Triggers on: tell, ask, teach, forget, assert, query, infer, rule, fact,
  predicate, bulk, NocturnusAI knowledge base.
---

# NocturnusAI Knowledge

Core CRUD operations for the NocturnusAI knowledge base: asserting facts, teaching rules, querying via inference, retracting knowledge, bulk operations, aggregation, and schema discovery.

**Prerequisites**: Server must be running and tenant created. See the `nocturnusai-connect` skill for setup.

## Variable Convention

Variables use the `?` prefix. This convention is enforced everywhere — MCP, REST, CLI, and SDKs.

| Wrong | Right | Why |
|-------|-------|-----|
| `X` | `?x` | Bare uppercase is treated as a literal string |
| `$x` | `?x` | `$` is not recognized as a variable marker |
| `Who` | `?who` | No prefix means it is a ground term, not a variable |
| `?` | `?x` | Bare `?` is not a valid variable name |

The `args` field is always a **JSON array of strings**: `["alice", "?x", "42"]`. Numbers passed as strings are matched literally. Variables begin with `?` and unify with any value during inference.

## Core Tools Reference

### `tell` — Assert a Fact

Store a fact in the knowledge base.

**Required params**: `predicate` (string), `args` (string array)

**Optional params**: `scope`, `negated` (bool), `ttl` (ms), `validUntil` (epoch ms), `confidence` (0.0–1.0), `conflictStrategy` (REJECT|NEWEST_WINS|CONFIDENCE|KEEP_BOTH)

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
      "name": "tell",
      "arguments": {
        "predicate": "likes",
        "args": ["alice", "bob"],
        "confidence": 0.95
      }
    }
  }'
```

**REST equivalent**:

```bash
curl -X POST http://localhost:9300/tell \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: default" \
  -d '{
    "predicate": "likes",
    "args": ["alice", "bob"],
    "confidence": 0.95
  }'
```

---

### `ask` — Query via Inference

Find all answers by matching facts and applying rules through multi-step logical reasoning. Use `?`-prefixed variables for unknowns.

**Required params**: `predicate` (string), `args` (string array)

**Optional params**: `scope`, `withProof` (bool — include reasoning chain), `minConfidence` (0.0–1.0)

**MCP example**:

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
      "name": "ask",
      "arguments": {
        "predicate": "likes",
        "args": ["alice", "?who"],
        "withProof": true
      }
    }
  }'
```

**REST equivalent**:

```bash
curl -X POST http://localhost:9300/ask \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: default" \
  -d '{
    "predicate": "likes",
    "args": ["alice", "?who"],
    "withProof": true
  }'
```

**Note**: `ask` returns **plain text**, not structured JSON. Parse the response body as a string.

---

### `teach` — Define a Rule

Teach a Horn clause rule. When all body conditions are true, the head conclusion becomes derivable.

**Required params**: `head` (object with `predicate` and `args`), `body` (array of condition objects)

**Optional params**: `scope`

Body atoms can include `negated: true` (explicit negation, sets `truthVal=false`) or `naf: true` (negation-as-failure, succeeds when the atom cannot be proven). See the `nocturnusai-reasoning` skill for full NAF details.

**MCP example**:

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
      "name": "teach",
      "arguments": {
        "head": {"predicate": "grandparent", "args": ["?x", "?z"]},
        "body": [
          {"predicate": "parent", "args": ["?x", "?y"]},
          {"predicate": "parent", "args": ["?y", "?z"]}
        ]
      }
    }
  }'
```

**REST equivalent**:

```bash
curl -X POST http://localhost:9300/teach \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: default" \
  -d '{
    "head": {"predicate": "grandparent", "args": ["?x", "?z"]},
    "body": [
      {"predicate": "parent", "args": ["?x", "?y"]},
      {"predicate": "parent", "args": ["?y", "?z"]}
    ]
  }'
```

---

### `forget` — Retract a Fact

Remove a specific fact. Any knowledge derived from it is automatically retracted (cascading retraction via truth maintenance).

**Required params**: `predicate` (string), `args` (string array)

**Optional params**: `scope`

**MCP example**:

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
      "name": "forget",
      "arguments": {
        "predicate": "likes",
        "args": ["alice", "bob"]
      }
    }
  }'
```

**REST equivalent**:

```bash
curl -X POST http://localhost:9300/forget \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: default" \
  -d '{"predicate": "likes", "args": ["alice", "bob"]}'
```

## Bulk Operations

### `bulk_assert` — Batch Insert

Assert multiple facts in a single call. **Non-transactional**: each fact is attempted independently. Contradictions are reported as errors without aborting the batch. Returns success and failure counts.

**Required params**: `facts` (array of fact objects, each with `predicate`, `args`, and optional `negated`, `scope`, `ttl`, `validUntil`)

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
      "name": "bulk_assert",
      "arguments": {
        "facts": [
          {"predicate": "color", "args": ["sky", "blue"]},
          {"predicate": "color", "args": ["grass", "green"]},
          {"predicate": "color", "args": ["sun", "yellow"]}
        ]
      }
    }
  }'
```

### `retract_pattern` — Wildcard Retraction

Retract all facts matching a pattern. Use `?`-prefixed variables as wildcards. Returns the count and list of retracted facts.

**Required params**: `predicate` (string), `args` (string array with `?` wildcards)

**Optional params**: `scope`

**MCP example** — retract all `color` facts regardless of arguments:

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
      "name": "retract_pattern",
      "arguments": {
        "predicate": "color",
        "args": ["?x", "?y"]
      }
    }
  }'
```

## Schema Discovery

### `predicates` — List All Predicates

Lists all predicates currently stored, with their argument count (arity) and fact count. Use this to understand the knowledge base structure before querying.

**Optional params**: `scope`

**MCP example**:

```bash
curl -X POST http://localhost:9300/mcp \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: default" \
  -d '{
    "jsonrpc": "2.0",
    "id": 7,
    "method": "tools/call",
    "params": {
      "name": "predicates",
      "arguments": {}
    }
  }'
```

**Example response** (in `result.content[0].text`):

```
likes/2: 3 facts
parent/2: 4 facts
color/2: 3 facts
human/1: 2 facts
```

## Aggregation

### `aggregate` — Compute Over Facts

Perform COUNT, SUM, MIN, MAX, or AVG over facts matching a pattern.

**Required params**: `predicate` (string), `args` (string array), `operation` (string)

**Optional params**: `argIndex` (0-based integer — required for SUM/MIN/MAX/AVG, ignored for COUNT), `scope`

The `argIndex` parameter specifies which argument position holds the numeric value to aggregate. For example, in `score("alice", "85")`, the numeric value `85` is at `argIndex=1`.

**COUNT example** — count all `score` facts (no `argIndex` needed):

```bash
curl -X POST http://localhost:9300/mcp \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: default" \
  -d '{
    "jsonrpc": "2.0",
    "id": 8,
    "method": "tools/call",
    "params": {
      "name": "aggregate",
      "arguments": {
        "predicate": "score",
        "args": ["?player", "?points"],
        "operation": "COUNT"
      }
    }
  }'
```

**SUM example** — sum all scores (`argIndex=1` points to the numeric value):

```bash
curl -X POST http://localhost:9300/mcp \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: default" \
  -d '{
    "jsonrpc": "2.0",
    "id": 9,
    "method": "tools/call",
    "params": {
      "name": "aggregate",
      "arguments": {
        "predicate": "score",
        "args": ["?player", "?points"],
        "operation": "SUM",
        "argIndex": 1
      }
    }
  }'
```

**Available operations**: COUNT, SUM, MIN, MAX, AVG.

## Common Workflows

### Assert then Query

```
1. tell  predicate:"parent" args:["alice","bob"]
2. tell  predicate:"parent" args:["bob","charlie"]
3. ask   predicate:"parent" args:["alice","?child"]
   → returns "bob"
```

### Teach then Infer

```
1. tell  predicate:"parent" args:["alice","bob"]
2. tell  predicate:"parent" args:["bob","charlie"]
3. teach head:{"predicate":"grandparent","args":["?x","?z"]}
         body:[{"predicate":"parent","args":["?x","?y"]},
               {"predicate":"parent","args":["?y","?z"]}]
4. ask   predicate:"grandparent" args:["?who","charlie"]
   → returns "alice"
```

### Schema Exploration

```
1. predicates  → see what predicates exist and their fact counts
2. ask predicate:"likes" args:["?x","?y"]  → enumerate all likes facts
3. aggregate predicate:"likes" args:["?x","?y"] operation:"COUNT"  → count them
```

### Bulk Loading

```
1. bulk_assert facts:[
     {"predicate":"city","args":["paris","france"]},
     {"predicate":"city","args":["london","uk"]},
     {"predicate":"city","args":["tokyo","japan"]}
   ]
2. predicates  → verify city/2 shows 3 facts
3. ask predicate:"city" args:["?name","france"]  → returns "paris"
```

## Conflict Strategies

When asserting a fact with the same predicate and args as an existing fact, the `conflictStrategy` parameter controls behavior.

| Strategy | Behavior | When to Use |
|----------|----------|-------------|
| `REJECT` | Error on duplicate (default) | Strict data integrity; prevent accidental overwrites |
| `NEWEST_WINS` | Replace existing fact silently | Mutable state (e.g., current location, status) |
| `CONFIDENCE` | Keep the fact with the highest `confidence` score | Multiple sources with varying reliability |
| `KEEP_BOTH` | Store both facts side by side | Tracking history or multiple perspectives |

**Example** — update a mutable property:

```json
{
  "predicate": "location",
  "args": ["alice", "london"],
  "conflictStrategy": "NEWEST_WINS"
}
```

## Gotchas

1. **Duplicate handling defaults to REJECT.** Asserting the same predicate+args twice without a `conflictStrategy` causes an error. Use `NEWEST_WINS` for mutable facts or `KEEP_BOTH` to retain history.

2. **`ask` returns plain text, not structured JSON.** The response body is a string (or list of strings), not a JSON object with field names. Parse accordingly.

3. **`bulk_assert` is non-transactional.** Each fact is attempted independently. If one fails (e.g., contradiction with REJECT strategy), the others still succeed. Check the returned success/fail counts.

4. **`aggregate` needs `argIndex` for numeric operations.** SUM, MIN, MAX, and AVG require `argIndex` to know which argument position holds the number. COUNT does not need it — it just counts matching facts.

5. **Variables must use `?` prefix.** Using `X`, `$x`, or `Who` without `?` treats the value as a literal ground term, not a variable. The query will match nothing (or the wrong thing).

6. **`X-Tenant-ID` is required on REST endpoints.** MCP routes default to `"default"` but REST routes throw `ValidationException` if the header is missing. Always include it.

7. **Args are always strings.** Even numeric values like `"42"` must be passed as strings in the `args` array. Aggregation operations parse them as numbers internally.
