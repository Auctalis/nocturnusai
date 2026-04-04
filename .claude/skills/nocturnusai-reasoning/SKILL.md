---
name: nocturnusai-reasoning
description: >
  Use when working with NocturnusAI advanced reasoning — negation-as-failure (NAF),
  scopes (fork/merge/diff), hypothetical reasoning, confidence scores, conflict
  resolution strategies, or proof chains. Triggers on: NAF, negation, negation-as-failure,
  scope, fork, merge, hypothesis, confidence, conflict, proof, what-if, NocturnusAI reasoning.
---

# NocturnusAI Reasoning Skill

Advanced reasoning patterns for NocturnusAI: negation-as-failure, scoped hypothetical reasoning, confidence scoring, conflict resolution, and proof chains.

**Prerequisite**: See `nocturnusai-connect` skill for server connection and MCP setup.

All MCP examples below use JSON-RPC 2.0 via `POST /mcp` with headers `X-Database: default` and `X-Tenant-ID: default`.

---

## Negation-as-Failure (NAF)

NAF implements the **closed-world assumption**: `NOT p(?x)` succeeds when `p(?x)` **cannot be proven** from known facts. This is different from storing that something is explicitly false.

NAF is only meaningful in **rule bodies** — the conditions of a `teach` call. It is not valid on head atoms or asserted facts.

### Teaching a Rule with NAF

DSL syntax: `NOT predicate(?x)` in rule body.

MCP JSON-RPC: set `"naf": true` on body atoms:

```json
{
  "jsonrpc": "2.0", "id": 1, "method": "tools/call",
  "params": {
    "name": "teach",
    "arguments": {
      "head": { "predicate": "can_fly", "args": ["?x"] },
      "body": [
        { "predicate": "bird", "args": ["?x"] },
        { "predicate": "penguin", "args": ["?x"], "naf": true }
      ]
    }
  }
}
```

This rule says: `?x` can fly if `?x` is a bird AND `?x` cannot be proven to be a penguin.

---

## **WARNING: NAF Ordering Rule — Assert Blocking Facts FIRST**

**NocturnusAI's Rete forward-chaining engine fires rules immediately when a triggering fact is asserted.** If you assert facts in the wrong order, derived facts will be incorrect.

### Correct Order

Assert the NAF-blocking fact **before** the triggering fact:

```json
// Step 1: Assert the blocking fact FIRST
{
  "jsonrpc": "2.0", "id": 1, "method": "tools/call",
  "params": { "name": "tell", "arguments": { "predicate": "penguin", "args": ["tweety"] } }
}

// Step 2: Assert the triggering fact SECOND
{
  "jsonrpc": "2.0", "id": 2, "method": "tools/call",
  "params": { "name": "tell", "arguments": { "predicate": "bird", "args": ["tweety"] } }
}
```

Result: `can_fly(tweety)` is **not** derived because `penguin(tweety)` blocks the NAF condition.

### Incorrect Order

```json
// WRONG: triggering fact first
tell bird(tweety)    // Rete fires "birds can fly" rule immediately → derives can_fly(tweety)
tell penguin(tweety) // Too late — can_fly(tweety) already derived
```

**Why this matters**: The Rete engine evaluates rules at assertion time. When `bird(tweety)` is asserted, the engine checks all matching rules immediately. If `penguin(tweety)` has not yet been asserted, the NAF condition `NOT penguin(?x)` succeeds, and `can_fly(tweety)` is incorrectly derived. Backward chaining (query-time) would give the correct answer, but forward-chained derivations persist.

**Rule of thumb**: Always assert exception facts (penguins, special cases) before general category facts (birds, base cases).

---

## Explicit Negation vs NAF Negation

| Aspect | Explicit (`negated: true`) | NAF (`naf: true`) |
|--------|---------------------------|---------------------|
| **Meaning** | Fact is **known to be false** | Succeeds when fact **cannot be proven** |
| **Storage** | Stored with `truthVal = false` | Not stored — evaluated at query/fire time |
| **Where used** | `tell` (assert facts) | `teach` body atoms only |
| **Example** | "Alice definitely does not like broccoli" | "If we can't prove X is a penguin, assume it isn't" |
| **Closed-world** | No — explicitly states falsehood | Yes — absence of proof = assumed false |
| **MCP field** | `"negated": true` on `tell` | `"naf": true` on `teach` body atoms |
| **DSL syntax** | `tell NOT likes(alice, broccoli)` | `NOT penguin(?x)` in rule body |

---

## Scopes

Scopes are **logical partitions within a tenant** for hypothetical reasoning, versioning, and A/B testing. They are **not isolation boundaries** — use separate tenants (`X-Tenant-ID` header) for true data isolation.

Key behaviors:
- `scope = null` (omitted) queries match atoms from **all** scopes, not "only unscoped"
- Facts asserted without a scope go into the global (unscoped) partition
- Scopes enable Git-like knowledge branching: fork, experiment, merge or discard

### Scope Workflow

```
Global facts ──fork──► "hypothesis-a" ──experiment──► merge back or delete
```

1. **Fork** a scope to create a copy for experimentation
2. **Assert/retract** facts within the forked scope
3. **Ask** questions scoped to the fork to test hypotheses
4. **Merge** successful results back, or **delete** the fork

### Fork Scope

Creates a copy of all facts from `sourceScope` into `targetScope`.

```json
{
  "jsonrpc": "2.0", "id": 1, "method": "tools/call",
  "params": {
    "name": "fork_scope",
    "arguments": {
      "targetScope": "what-if-alice-moves",
      "sourceScope": null
    }
  }
}
```

- **Required**: `targetScope` (name of the new scope)
- **Optional**: `sourceScope` (defaults to global/unscoped partition when omitted or null)

### Experiment in a Scope

```json
{
  "jsonrpc": "2.0", "id": 2, "method": "tools/call",
  "params": {
    "name": "tell",
    "arguments": {
      "predicate": "located_in", "args": ["alice", "london"],
      "scope": "what-if-alice-moves"
    }
  }
}
```

### Ask Within a Scope

```json
{
  "jsonrpc": "2.0", "id": 3, "method": "tools/call",
  "params": {
    "name": "ask",
    "arguments": {
      "predicate": "located_in", "args": ["alice", "?where"],
      "scope": "what-if-alice-moves"
    }
  }
}
```

### Merge Scope

Merges facts from `sourceScope` back into `targetScope`.

```json
{
  "jsonrpc": "2.0", "id": 4, "method": "tools/call",
  "params": {
    "name": "merge_scope",
    "arguments": {
      "sourceScope": "what-if-alice-moves",
      "strategy": "SOURCE_WINS"
    }
  }
}
```

- **Required**: `sourceScope`
- **Optional**: `targetScope` (defaults to global), `strategy` (defaults to `SOURCE_WINS`)

### List Scopes

```json
{
  "jsonrpc": "2.0", "id": 5, "method": "tools/call",
  "params": { "name": "list_scopes", "arguments": {} }
}
```

### Delete Scope

```json
{
  "jsonrpc": "2.0", "id": 6, "method": "tools/call",
  "params": {
    "name": "delete_scope",
    "arguments": { "scope": "what-if-alice-moves" }
  }
}
```

- **Required**: `scope`

---

## Merge Strategies

| Strategy | Behavior | Use When |
|----------|----------|----------|
| `SOURCE_WINS` | Source facts overwrite conflicting target facts (default) | Committing confirmed hypotheses |
| `TARGET_WINS` | Target facts kept, conflicting source facts discarded | Cautious merge, preserving existing knowledge |
| `KEEP_BOTH` | Both versions retained | Need to preserve all perspectives |
| `REJECT` | Merge aborted if any conflicts exist | Must guarantee no contradictions |

---

## Confidence

Facts can carry a confidence score from `0.0` to `1.0`. Use this to represent uncertainty from LLM extractions, sensor data, or probabilistic reasoning.

### Setting Confidence on Tell

```json
{
  "jsonrpc": "2.0", "id": 1, "method": "tools/call",
  "params": {
    "name": "tell",
    "arguments": {
      "predicate": "sentiment", "args": ["review_42", "positive"],
      "confidence": 0.85
    }
  }
}
```

### Filtering by Minimum Confidence on Ask

```json
{
  "jsonrpc": "2.0", "id": 2, "method": "tools/call",
  "params": {
    "name": "ask",
    "arguments": {
      "predicate": "sentiment", "args": ["?review", "?val"],
      "minConfidence": 0.7
    }
  }
}
```

Only facts with `confidence >= 0.7` are returned.

---

## Conflict Resolution

When asserting a fact that conflicts with an existing fact (same predicate and args), the `conflictStrategy` parameter controls behavior:

| Strategy | Behavior |
|----------|----------|
| `REJECT` | Throw error, reject the new fact (default) |
| `NEWEST_WINS` | Replace existing fact with the new one |
| `CONFIDENCE` | Keep whichever fact has higher confidence |
| `KEEP_BOTH` | Store both facts |

Set via `"conflictStrategy": "NEWEST_WINS"` on `tell`.

For full details and examples, see the `nocturnusai-knowledge` skill.

---

## Proof Chains

Use `"withProof": true` on `ask` to get the full reasoning chain showing how each answer was derived. Essential for debugging rule chains and providing explainability.

### Request

```json
{
  "jsonrpc": "2.0", "id": 1, "method": "tools/call",
  "params": {
    "name": "ask",
    "arguments": {
      "predicate": "grandparent", "args": ["?who", "charlie"],
      "withProof": true
    }
  }
}
```

### Response Format

The proof tree shows each derivation step with indentation:

```
Inferred 1 result(s) with proofs:

Result: grandparent(alice, charlie)
  RULE: grandparent(?x, ?z) :- parent(?x, ?y), parent(?y, ?z)
    FACT: parent(alice, bob)
    FACT: parent(bob, charlie)
```

- `RULE` entries show which rule was applied and its definition
- `FACT` entries show which stored facts matched the rule's conditions
- Nesting shows the dependency chain — deeper indentation means a sub-goal

### When to Use Proofs

- **Debugging**: Rule not firing? Proof chains show exactly which condition failed
- **Explainability**: Show users or auditors how a conclusion was reached
- **Validation**: Verify that inference follows expected reasoning paths
- **Complex chains**: Multi-hop rules (grandparent, ancestor, transitive relations) where the derivation path matters

---

## Gotchas

1. **NAF only in rule bodies** — Setting `naf: true` on head atoms of `teach` or on `tell` facts has no effect. NAF is a query-time evaluation, not a storage property.

2. **`scope = null` means ALL scopes** — Omitting the scope on `ask` queries across every scope, not just unscoped facts. To query only the global partition, there is no explicit filter — design your scopes accordingly.

3. **Confidence omitted is different from confidence = 1.0** — A fact with no confidence (`null`) is treated as "confidence unspecified" and passes all `minConfidence` filters. A fact with `confidence: 1.0` is explicitly certain but still subject to `minConfidence` comparison.

4. **Scopes are NOT tenants** — Scopes are logical partitions within a single tenant. They share the same rule set and inference engine. For true data isolation between users or applications, use separate `X-Tenant-ID` values.

5. **NAF ordering matters for forward chaining** — Backward chaining (query-time) always gives correct NAF results regardless of assertion order. But Rete forward chaining evaluates at assertion time, so order matters. See the NAF Ordering Rule section above.

6. **Proof chains use backward chaining only** — `withProof: true` triggers backward chaining (goal-driven resolution). Forward-chained derivations do not produce proof trees.
