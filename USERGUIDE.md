# NocturnusAI — Comprehensive Developer & User Guide

## The Symbolic Cortex: A Logic Inference Engine and Knowledge Database

---

## Table of Contents

1. [What Is NocturnusAI and Why Does It Exist?](#1-what-is-nocturnusai-and-why-does-it-exist)
2. [Core Concepts: How NocturnusAI Thinks](#2-core-concepts-how-nocturnusai-thinks)
3. [Architecture Overview](#3-architecture-overview)
4. [Connecting Securely](#4-connecting-securely)
5. [Authentication & Headers](#5-authentication--headers)
6. [The API: Complete Reference with Examples](#6-the-api-complete-reference-with-examples)
7. [The Logic Engine: How It Works Under the Hood](#7-the-logic-engine-how-it-works-under-the-hood)
8. [Proof Trees: Explaining Derivations](#8-proof-trees-explaining-derivations)
9. [Logic Templates: Pre-Built Reasoning Patterns](#9-logic-templates-pre-built-reasoning-patterns)
10. [Logic Testing Framework](#10-logic-testing-framework)
11. [Multi-Tenancy & Scoping: Isolated Worlds](#11-multi-tenancy--scoping-isolated-worlds)
12. [Transactions: ACID Guarantees](#12-transactions-acid-guarantees)
13. [Persistence & Durability](#13-persistence--durability)
14. [Replication: Leader-Follower Architecture](#14-replication-leader-follower-architecture)
15. [Observability: Health, Metrics, and Monitoring](#15-observability-health-metrics-and-monitoring)
16. [Deployment Guide](#16-deployment-guide)
17. [The DSL: Writing Logic Programs](#17-the-dsl-writing-logic-programs)
18. [Advanced Patterns & Recipes](#18-advanced-patterns--recipes)
19. [Limitations & Design Trade-Offs](#19-limitations--design-trade-offs)
20. [Metadata: Optional Annotations](#20-metadata-optional-annotations)
21. [Glossary](#21-glossary)

---

## 1. What Is NocturnusAI and Why Does It Exist?

### The Problem

Modern software systems—especially those powered by AI—face a fundamental challenge: **deterministic, multi-step logical reasoning is hard to do reliably in application code**. Consider these scenarios:

- An access control system needs to evaluate: "Can user X access resource Y?" based on roles, permissions, group memberships, and exception rules that chain together across multiple levels.
- A compliance engine must determine: "Is this transaction legal?" by checking dozens of interlocking regulations where the answer to one rule depends on the answers to three others.
- A game engine needs to enforce: "Can this piece move here?" based on board state, piece capabilities, turn rules, and special conditions that interact in complex ways.
- An AI agent needs to know: "What is actually true right now in this world?" without guessing, hallucinating, or losing track of state across conversation turns.

You *could* encode all of this in `if/else` chains, nested SQL queries, or graph traversals. But as the number of rules grows, the interactions between rules become combinatorial. Your code becomes brittle, hard to test, and nearly impossible to modify without breaking something.

### The Solution

NocturnusAI is a **logic inference engine and knowledge database**. Instead of writing procedural code that checks conditions, you **declare facts** (what is true) and **declare rules** (what can be derived from what is true), and then **ask questions**. The engine figures out the answers using formal logic—specifically, SLD resolution (the same algorithm that powers Prolog) combined with forward chaining (the Rete algorithm used in production rule systems like Drools and CLIPS).

Think of it this way:

| Traditional Approach | NocturnusAI Approach |
|---|---|
| Write code that checks conditions | Declare what is true |
| Debug tangled `if/else` chains | Declare inference rules |
| Maintain consistency manually | Ask questions; engine derives answers |
| Re-implement logic when rules change | Retract/assert facts; engine auto-updates |
| Pray your state is consistent | Engine enforces contradictions automatically |

### Why "Symbolic Cortex"?

The name comes from its intended use as a companion to Large Language Models (LLMs). LLMs are exceptional at understanding natural language, generating text, and reasoning probabilistically—but they struggle with:

1. **Deterministic multi-step reasoning**: "If A implies B, and B implies C, and A is true, what about C?" An LLM might get this right 95% of the time. NocturnusAI gets it right 100% of the time.
2. **State maintenance**: An LLM's "memory" is its context window, which is limited and lossy. NocturnusAI maintains persistent, queryable state with ACID transactions.
3. **Consistency enforcement**: An LLM can accidentally contradict itself. NocturnusAI will throw an error if you try to assert both "X is alive" and "X is dead."

By offloading strict logic, state management, and rule evaluation to NocturnusAI, an AI system gets the best of both worlds: the creativity and language understanding of an LLM, plus the rigor and reliability of a formal logic engine.

### Core Directives for AI Agents

If you are an AI agent using NocturnusAI as your symbolic backend:

1. **Do not guess** the state of the world. Query it via `/infer`.
2. **Do not simulate** complex logic in your context window. Assert the rules via `/assert/rule` and let the engine solve it.
3. **Do not hallucinate** database consistency. Use transactions via `/tx/begin`, `/tx/commit`.

---

## 2. Core Concepts: How NocturnusAI Thinks

Before diving into the API, you need to understand the four fundamental abstractions that NocturnusAI uses to represent and reason about knowledge. These are not arbitrary implementation details—they are the building blocks of formal logic, and understanding them is essential to using the system effectively.

### 2.1 Terms: The Building Blocks

A **Term** is the most basic unit of data in NocturnusAI. There are four kinds:

| Term Type | Syntax | Example | When to Use |
|---|---|---|---|
| **Identifier** | Plain text | `alice`, `admin`, `payroll_db` | Named entities, constants, categories |
| **StringLit** | Quoted text | `"Hello World"` | Free-form text values |
| **NumberLit** | Numeric | `42`, `3.14` | Quantities, thresholds, scores |
| **Variable** | `?`-prefixed | `?x`, `?who`, `?amount` | Unknowns in queries and rules |

**Why four types?** Because logic needs to distinguish between concrete values (identifiers, strings, numbers) and placeholders for unknown values (variables). When you ask "Who is an admin?", the `?who` is a variable that the engine will try to fill in with every possible value that satisfies the query.

**Important**: Variables always start with `?`. This convention is used throughout the codebase, API, and DSL. If you send `"?x"` as an argument in an API request, the server parses it as a Variable. If you send `"alice"`, it's parsed as an Identifier. If you send `"42"`, it's parsed as a NumberLit (the server auto-detects numeric strings).

### 2.2 Atoms: Units of Knowledge

An **Atom** is a single assertion about the world. It has this structure:

```
predicate(arg1, arg2, ..., argN)
```

With additional metadata:

| Field | Type | Default | Purpose |
|---|---|---|---|
| `predicate` | String | (required) | The type of relationship or property |
| `args` | List\<Term\> | (required) | The things involved in the relationship |
| `truthVal` | Boolean | `true` | Is this assertion positive (true) or negative (false)? |
| `source` | Enum | `USER_INPUT` | Was this asserted by a user, or derived by inference? |
| `scope` | String? | `null` | Optional partition for isolated reasoning |
| `metadata` | Map\<String, JSON\> | `{}` | Optional key-value annotations (not part of logical identity) |

**Examples of atoms:**

```
Parent(alice, bob)                      → "Alice is a parent of Bob" (binary, positive)
NOT Parent(alice, charlie)              → "Alice is NOT a parent of Charlie" (binary, negative)
Is_Online(server_1)                     → "Server 1 is online" (unary, positive)
Transfer(tx_001, alice, bob, 50.0)      → "Transaction tx_001: Alice sends Bob $50" (4-ary)
```

**Why are atoms the fundamental unit?** Because they are *hyperedges*—they can connect any number of entities in a single relationship. This is more expressive than:

- **SQL tables**: Which require you to define a schema upfront and normalize relationships across multiple tables.
- **Graph databases**: Which are limited to binary edges (node → edge → node). Representing "Alice sent Bob $50 in transaction TX001" requires either multiple edges or edge properties.
- **Key-value stores**: Which have no concept of relationships at all.

An atom like `Transfer(tx_001, alice, bob, 50.0)` captures the entire relationship in one unit, and the engine can query it efficiently regardless of which terms you know and which you're searching for (thanks to the Hexastore, explained in Section 7).

### 2.3 Rules: Teaching the Engine to Think

A **Rule** is a Horn clause that tells the engine how to derive new knowledge from existing knowledge. It has the form:

```
Head(args) :- Body1(args), Body2(args), ..., BodyN(args)
```

Read this as: **"Head is true IF Body1 is true AND Body2 is true AND ... AND BodyN is true."**

| Field | Type | Purpose |
|---|---|---|
| `variables` | List\<Variable\> | All variables used in the rule |
| `head` | Atom | The conclusion (what gets derived) |
| `body` | List\<Atom\> | The conditions (what must be true) |
| `scope` | String? | Optional partition |

**Example: Grandparent rule**

```
Grandparent(?x, ?z) :- Parent(?x, ?y), Parent(?y, ?z)
```

This says: "If ?x is a parent of ?y, AND ?y is a parent of ?z, THEN ?x is a grandparent of ?z."

The engine will automatically find all values of `?x`, `?y`, and `?z` from the fact store that satisfy both conditions simultaneously, and for each valid combination, it will derive the `Grandparent` relationship.

**Variable binding is the key mechanism**. Notice how `?y` appears in both body atoms—it binds the same value across both conditions. This is what makes rules powerful: they express *join* conditions (like a SQL JOIN) declaratively.

**Why rules instead of code?** Because:
1. Rules are **composable**: You can add new rules without modifying existing ones.
2. Rules are **queryable**: You can ask "why was this derived?" and get the proof chain.
3. Rules are **retractable**: If a premise becomes false, derived conclusions are automatically withdrawn (see Truth Maintenance, Section 7.5).

### 2.4 Substitutions: The Engine's Answers

When you query the engine, it returns **substitutions**—mappings from variables to concrete values. A substitution is the engine saying "I found values that make your query true."

For example, querying `Parent(?who, bob)` against facts `Parent(alice, bob)` and `Parent(charlie, bob)` returns:

```
Substitution 1: {?who → alice}
Substitution 2: {?who → charlie}
```

The API returns these as string representations of the substituted atom:
```json
["Parent(alice, bob)", "Parent(charlie, bob)"]
```

---

## 3. Architecture Overview

NocturnusAI is a three-module system:

```
┌─────────────────────────────────────────────────────────────────────┐
│                        nocturnusai-web (React)                        │
│               Web console for interactive exploration               │
│                     TypeScript + Vite + React 19                    │
└──────────────────────────────┬──────────────────────────────────────┘
                               │ HTTP (JSON)
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     nocturnusai-server (Ktor)                         │
│            HTTP API layer: routing, auth, validation                │
│          Multi-database management, replication, TLS                │
│                     Kotlin + Ktor 2.3.7 + Netty                    │
└──────────────────────────────┬──────────────────────────────────────┘
                               │ Direct Kotlin API
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     nocturnusai-core (Pure Logic)                     │
│                                                                     │
│  ┌──────────┐  ┌──────────────┐  ┌────────────────────────────┐    │
│  │ Hexastore│  │   Backward   │  │    Truth Maintenance       │    │
│  │ (6-way   │  │   Chainer    │  │  ┌──────────────────────┐  │    │
│  │  indexed  │  │  (SLD Res.)  │  │  │ ProvenanceTracker    │  │    │
│  │  triple   │  ├──────────────┤  │  │ (dependency graph)   │  │    │
│  │  store)   │  │  Rete Engine │  │  ├──────────────────────┤  │    │
│  │          │  │  (forward    │  │  │ ConsistencyGuard     │  │    │
│  │          │  │   chaining)  │  │  │ (constraint checker) │  │    │
│  │          │  ├──────────────┤  │  └──────────────────────┘  │    │
│  │          │  │   Unifier    │  ├────────────────────────────┤    │
│  │          │  │  (term       │  │     Persistence            │    │
│  │          │  │   matching)  │  │  WAL + Snapshots + AES-256 │    │
│  └──────────┘  └──────────────┘  └────────────────────────────┘    │
│                                                                     │
│  ┌──────────────────┐  ┌──────────────────┐  ┌─────────────────┐   │
│  │ TransactionMgr   │  │ Parser/Tokenizer │  │  LogicContext    │   │
│  │ (ACID, timeout)  │  │ (LogiQL DSL)     │  │  (per-tenant)   │   │
│  └──────────────────┘  └──────────────────┘  └─────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

**Why this separation?**

- **nocturnusai-core** is a pure logic library with zero HTTP dependencies. You can embed it directly in a Kotlin/JVM application without running a server. This makes it testable, embeddable, and reusable.
- **nocturnusai-server** adds the HTTP layer, authentication, multi-database management, replication, and observability. It depends on nocturnusai-core.
- **nocturnusai-web** is a standalone React application that provides an interactive web console for exploring databases, running queries, and managing the system visually.

---

## 4. Connecting Securely

NocturnusAI supports multiple layers of security. Here is how to configure and use each one, from development to production.

### 4.1 Development Mode (No Security)

By default, NocturnusAI runs with no authentication and plain HTTP:

```bash
# Start the server
./gradlew :nocturnusai-server:run

# Connect with curl — no auth needed
curl http://localhost:9300/health
```

**When to use**: Local development only. Never expose this to the internet.

### 4.2 API Key Authentication

Set the `API_KEY` environment variable to enable header-based authentication:

```bash
# Start with authentication
API_KEY=my-secret-key-12345 ./gradlew :nocturnusai-server:run
```

Now every request (except health/metrics endpoints) must include the key:

```bash
# This works
curl -H "X-API-Key: my-secret-key-12345" \
     -H "X-Database: default" \
     -H "X-Tenant-ID: main" \
     http://localhost:9300/admin/databases

# This returns 401 Unauthorized
curl http://localhost:9300/admin/databases
```

**Public endpoints** (never require authentication):
- `GET /health` — Full health check
- `GET /health/live` — Lightweight liveness probe
- `GET /health/ready` — Readiness probe
- `GET /metrics` — Prometheus metrics

**Why a header instead of Bearer token?** The `X-API-Key` header pattern is simpler for machine-to-machine communication (the primary use case) than OAuth2 or JWT. It avoids the complexity of token refresh, scopes, and claims when the client is a trusted backend service or AI agent.

**Security considerations**:
- The API key is compared as a plain string. Use a long, random value (e.g., 64+ characters).
- Transmit over HTTPS in production to prevent interception (see TLS below).
- Rotate the key by restarting the server with a new value.

### 4.3 TLS/HTTPS (Encryption in Transit)

NocturnusAI has built-in TLS support using a PKCS12 keystore:

```bash
# Generate a self-signed keystore (for testing)
keytool -genkeypair \
  -alias nocturnusai \
  -keyalg RSA \
  -keysize 2048 \
  -storetype PKCS12 \
  -keystore keystore.p12 \
  -validity 365 \
  -storepass changeit

# Start with TLS enabled
TLS_ENABLED=true \
TLS_PORT=9443 \
TLS_KEYSTORE_PATH=./keystore.p12 \
TLS_KEYSTORE_PASSWORD=changeit \
TLS_KEY_ALIAS=nocturnusai \
./gradlew :nocturnusai-server:run
```

Now the server listens on both ports:
- **HTTP**: Port 9300 (plain)
- **HTTPS**: Port 9443 (encrypted)

```bash
# Connect via HTTPS
curl -k https://localhost:9443/health

# With authentication
curl -k -H "X-API-Key: my-secret-key" \
     https://localhost:9443/admin/databases
```

**For production with real certificates**:
1. Obtain a certificate from a CA (Let's Encrypt, etc.)
2. Convert to PKCS12 format:
   ```bash
   openssl pkcs12 -export \
     -in fullchain.pem \
     -inkey privkey.pem \
     -out keystore.p12 \
     -name nocturnusai \
     -password pass:your-password
   ```
3. Configure the environment variables accordingly.

**Alternative: Reverse Proxy TLS Termination**

In many production deployments, you'll place a reverse proxy (Nginx, Caddy, HAProxy) in front of NocturnusAI that handles TLS termination:

```
Client ──HTTPS──▶ Nginx (TLS) ──HTTP──▶ NocturnusAI:9300
```

This is often simpler because:
- Nginx/Caddy handle certificate renewal automatically (Let's Encrypt integration)
- You can terminate TLS once and proxy to multiple backend services
- The JVM doesn't need to handle TLS overhead

### 4.4 Encryption at Rest (AES-256-GCM)

NocturnusAI can encrypt all persisted data (WAL entries and snapshots) using AES-256-GCM:

```bash
# Generate a 32-byte (256-bit) key as 64 hex characters
ENCRYPTION_KEY=$(openssl rand -hex 32)
echo "Save this key securely: $ENCRYPTION_KEY"

# Start with encryption
ENCRYPTION_KEY=$ENCRYPTION_KEY ./gradlew :nocturnusai-server:run
```

**What gets encrypted**:
- Every Write-Ahead Log (WAL) entry is encrypted before being written to disk
- Snapshot files are encrypted in their entirety
- The encryption uses AES/GCM/NoPadding with a random 96-bit IV per encryption operation and a 128-bit authentication tag

**Why AES-256-GCM?** GCM (Galois/Counter Mode) provides both confidentiality and integrity. The authentication tag ensures that if someone tampers with the encrypted data on disk, the decryption will fail rather than silently producing corrupted data. This is strictly better than modes like CBC that provide only confidentiality.

**Key management**: The encryption key must be provided as an environment variable every time the server starts. If you lose the key, all persisted data becomes permanently unrecoverable. Store it in a secrets manager (AWS Secrets Manager, HashiCorp Vault, etc.).

### 4.5 Full Production Security Stack

Here is the complete configuration for a production deployment:

```bash
export PORT=9300
export HOST=0.0.0.0
export API_KEY=$(cat /run/secrets/nocturnusai-api-key)
export ENCRYPTION_KEY=$(cat /run/secrets/nocturnusai-encryption-key)
export STORAGE_DIR=/data/nocturnusai
export TLS_ENABLED=true
export TLS_PORT=9443
export TLS_KEYSTORE_PATH=/etc/nocturnusai/keystore.p12
export TLS_KEYSTORE_PASSWORD=$(cat /run/secrets/keystore-password)
export TLS_KEY_ALIAS=nocturnusai

./gradlew :nocturnusai-server:run
```

This gives you:
- **Authentication**: API key required for all data operations
- **Encryption in transit**: TLS 1.2+ on port 9443
- **Encryption at rest**: AES-256-GCM on all persisted data
- **Isolated storage**: Dedicated data directory with proper permissions

### 4.6 CORS Configuration

The server is configured with permissive CORS for API consumption:

- **Origins**: Any host (`anyHost()`)
- **Allowed Headers**: `Content-Type`, `X-Transaction-ID`, `Authorization`, `X-API-Key`, `X-Database`, `X-Tenant-ID`, `X-Request-ID`
- **Exposed Headers**: `X-Request-ID`
- **Allowed Methods**: `GET`, `POST`, `PUT`, `DELETE`, `PATCH`, `OPTIONS`

In production, you should restrict the allowed origins by placing a reverse proxy in front of the server that enforces a strict CORS policy.

### 4.7 Input Validation

All inputs are validated before processing:

| Input | Validation Rule | Max Length |
|---|---|---|
| Database name | Alphanumeric, `-`, `_` only. No `..`, `/`, `\`. | 64 chars |
| Tenant ID | Alphanumeric, `-`, `_` only. No `..`, `/`, `\`. | 128 chars |
| Predicate | Cannot be blank. | 255 chars |
| Arguments | Per-argument limit. Max 50 arguments per atom. | 10,000 chars each |

**Why these specific validations?** The database name and tenant ID are used in file paths (for per-database storage directories), so path traversal attacks (`../../etc/passwd`) must be prevented. The predicate and argument limits prevent denial-of-service via absurdly large payloads.

---

## 5. Authentication & Headers

Every request to NocturnusAI uses a set of HTTP headers to identify the target database, tenant, and authentication context. Understanding these headers is essential.

### 5.1 Header Reference

| Header | Required | Default | Purpose |
|---|---|---|---|
| `X-API-Key` | Conditional | — | Authentication key (required if `API_KEY` env var is set) |
| `X-Database` | No | `"default"` | Selects which database to operate on |
| `X-Tenant-ID` | Yes* | — | Identifies the tenant within the database |
| `X-Transaction-ID` | No | — | Associates the request with an active transaction |
| `X-Request-ID` | No | Auto-generated UUID | Correlation ID for request tracing |
| `Content-Type` | For POST/PUT | — | Should be `application/json` for JSON bodies |

*Required for all data operations (assert, infer, retract, transactions). Not required for admin or observability endpoints.

### 5.2 How Multi-Database Selection Works

NocturnusAI supports multiple independent databases, each with its own set of tenants, facts, rules, and persistence. The `X-Database` header selects which database a request operates on.

```bash
# Create two separate databases
curl -X POST http://localhost:9300/admin/databases \
  -H "Content-Type: application/json" \
  -d '{"name": "production"}'

curl -X POST http://localhost:9300/admin/databases \
  -H "Content-Type: application/json" \
  -d '{"name": "staging"}'

# Assert a fact to the production database
curl -X POST http://localhost:9300/assert/fact \
  -H "Content-Type: application/json" \
  -H "X-Database: production" \
  -H "X-Tenant-ID: main" \
  -d '{"predicate": "Server_Status", "args": ["web01", "running"]}'

# This fact does NOT exist in staging
curl -X POST http://localhost:9300/infer \
  -H "Content-Type: application/json" \
  -H "X-Database: staging" \
  -H "X-Tenant-ID: main" \
  -d '{"predicate": "Server_Status", "args": ["?server", "?status"]}'
# Returns: []
```

**Why multiple databases?** For organizational isolation. Different applications, environments (dev/staging/prod), or completely separate knowledge domains can coexist on the same server without any risk of data leaking between them.

### 5.3 How Multi-Tenancy Works

Within a single database, tenants provide a second layer of isolation. Each tenant has its own independent set of facts, rules, and inference state.

```bash
# Create tenants within a database
curl -X POST http://localhost:9300/admin/databases/production/tenants \
  -H "Content-Type: application/json" \
  -d '{"tenantId": "customer_acme"}'

curl -X POST http://localhost:9300/admin/databases/production/tenants \
  -H "Content-Type: application/json" \
  -d '{"tenantId": "customer_globex"}'

# Assert facts for different tenants
curl -X POST http://localhost:9300/assert/fact \
  -H "X-Database: production" \
  -H "X-Tenant-ID: customer_acme" \
  -H "Content-Type: application/json" \
  -d '{"predicate": "Plan", "args": ["enterprise"]}'

curl -X POST http://localhost:9300/assert/fact \
  -H "X-Database: production" \
  -H "X-Tenant-ID: customer_globex" \
  -H "Content-Type: application/json" \
  -d '{"predicate": "Plan", "args": ["starter"]}'
```

Querying `customer_acme` will only see enterprise plan; querying `customer_globex` will only see starter plan. They are completely isolated.

### 5.4 Request Correlation

Every request is assigned an `X-Request-ID` (auto-generated UUID if not provided). This ID is:
1. Added to the SLF4J MDC (so all log entries for this request include it)
2. Returned in the response `X-Request-ID` header
3. Logged in the call log: `[$requestId] POST /assert/fact -> 200`

Use this for distributed tracing:

```bash
curl -X POST http://localhost:9300/assert/fact \
  -H "X-Request-ID: my-trace-12345" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -H "Content-Type: application/json" \
  -d '{"predicate": "Test", "args": ["hello"]}'
```

The response header will include `X-Request-ID: my-trace-12345`, and all server logs for this request will be tagged with that ID.

---

## 6. The API: Complete Reference with Examples

This section documents every endpoint with full curl examples, request/response formats, and explanations of why each endpoint exists.

### 6.1 Assert a Fact — `POST /assert/fact`

**Purpose**: Teach the engine a new piece of knowledge about the world.

**Why it exists**: Facts are the foundation of all reasoning. Without facts, there is nothing to reason about. Every query, every inference, every rule activation ultimately traces back to asserted facts.

**Request**:
```bash
curl -X POST http://localhost:9300/assert/fact \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{
    "predicate": "Parent",
    "args": ["alice", "bob"],
    "truthVal": true,
    "scope": null
  }'
```

**Response** (200 OK, plain text):
```
Fact Asserted: Parent(alice, bob)
```

**Request body fields**:

| Field | Type | Default | Description |
|---|---|---|---|
| `predicate` | String | (required) | The name of the relationship |
| `args` | String[] | (required) | The arguments (entities involved) |
| `truthVal` | Boolean | `true` | `true` for positive assertion, `false` for explicit negation |
| `negated` | Boolean | `false` | If `true`, inverts `truthVal` (convenience field) |
| `scope` | String? | `null` | Optional partition key |
| `metadata` | Map\<String, JSON\> | `{}` | Optional key-value annotations (see [Metadata](#metadata-optional-annotations)) |

**How arguments are parsed**:
- `"?x"` → Variable (for use in queries/rules)
- `"42"` or `"3.14"` → NumberLit (auto-detected)
- Anything else → Identifier

**What happens internally**:
1. The server validates the predicate and arguments (length, character restrictions).
2. It checks for contradictions: if you assert `Parent(alice, bob)` with `truthVal=true`, and `Parent(alice, bob)` with `truthVal=false` already exists, the server returns **409 Conflict** with "Contradiction detected."
3. It checks consistency constraints (any `RESTRICT` rules that would be violated).
4. The fact is appended to the Write-Ahead Log (for crash recovery).
5. The fact is added to the Hexastore (6-way indexed store).
6. The Rete engine is notified, which may trigger forward-chaining rules and derive new facts.

**Asserting negative facts**:
```bash
# Explicit negation: "Alice is NOT a parent of Charlie"
curl -X POST http://localhost:9300/assert/fact \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{
    "predicate": "Parent",
    "args": ["alice", "charlie"],
    "negated": true
  }'
```

Response: `Fact Asserted: NOT Parent(alice, charlie)`

**Why explicit negation matters**: In classical logic, there's a difference between "I don't know if P is true" (absence of P) and "I know P is false" (explicit ¬P). NocturnusAI supports explicit negation. This means you can assert "Alice is NOT a parent of Charlie" as a positive piece of knowledge, not just the absence of "Alice is a parent of Charlie." This is critical for rules like Modus Tollens that reason about negation.

**Error responses**:

| Status | Code | Meaning |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Invalid predicate, arguments, or database name |
| 404 | `NOT_FOUND` | Database not found |
| 409 | — | Contradiction detected (opposite fact exists) |
| 429 | — | Too many active transactions |

### 6.2 Assert a Rule — `POST /assert/rule`

**Purpose**: Teach the engine how to derive new knowledge from existing facts.

**Why it exists**: Rules are what make NocturnusAI more than a key-value store. Without rules, you can only retrieve exactly what you stored. With rules, the engine can derive knowledge you never explicitly stated—like inferring that Alice is a grandparent of Dave because she's a parent of Bob who is a parent of Dave.

**Request**:
```bash
curl -X POST http://localhost:9300/assert/rule \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{
    "head": {
      "predicate": "Grandparent",
      "args": ["?x", "?z"]
    },
    "body": [
      {
        "predicate": "Parent",
        "args": ["?x", "?y"]
      },
      {
        "predicate": "Parent",
        "args": ["?y", "?z"]
      }
    ]
  }'
```

**Response** (200 OK, plain text):
```
Rule Asserted: Grandparent(?x, ?z) :- Parent(?x, ?y), Parent(?y, ?z)
```

**How it works**: The engine automatically extracts all variables (terms starting with `?`) from the head and body. These become the universally quantified variables of the rule. The rule says: "For all values of ?x, ?y, ?z: if Parent(?x, ?y) and Parent(?y, ?z) are both true, then Grandparent(?x, ?z) is true."

**Rules with negation in the body**:
```bash
# "Access is allowed if the user has the role AND is NOT suspended"
curl -X POST http://localhost:9300/assert/rule \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{
    "head": {
      "predicate": "Access_Allowed",
      "args": ["?user", "?resource"]
    },
    "body": [
      {
        "predicate": "Has_Role",
        "args": ["?user", "admin"]
      },
      {
        "predicate": "Is_Suspended",
        "args": ["?user"],
        "negated": true
      }
    ]
  }'
```

This rule will only fire if `Has_Role(?user, admin)` is true **AND** `NOT Is_Suspended(?user)` is explicitly asserted. Note: this is explicit negation, not "negation as failure." The system needs a `NOT Is_Suspended(alice)` fact to exist, not just the absence of `Is_Suspended(alice)`.

**Validation**:
- The body cannot be empty (a rule with no conditions would be a tautology).
- All predicates and arguments are validated.

### 6.3 Assert a Template — `POST /assert/template`

**Purpose**: Create rules from well-known logical reasoning patterns without constructing the rule structure manually.

**Why it exists**: Formal logic has a set of standard reasoning patterns (syllogism, modus ponens, modus tollens, etc.) that have been studied for millennia. Rather than requiring you to manually construct these patterns as raw rules, templates let you specify the pattern type and the predicates, and the engine generates the correct rules.

This is documented extensively in [Section 9: Logic Templates](#9-logic-templates-pre-built-reasoning-patterns).

**Request**:
```bash
# Modus Ponens: "If it is Raining, then Ground_Is_Wet"
curl -X POST http://localhost:9300/assert/template \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{
    "type": "MODUS_PONENS",
    "predicates": {
      "P": "Raining",
      "Q": "Ground_Is_Wet"
    },
    "args": ["location"]
  }'
```

**Response** (200 OK, plain text):
```
Rule Asserted: Ground_Is_Wet(?location) :- Raining(?location)
```

### 6.4 Query / Infer — `POST /infer`

**Purpose**: Ask the engine a question and get all answers that can be derived from facts and rules.

**Why it exists**: This is the core value proposition of NocturnusAI. You state facts, define rules, and then ask questions. The engine uses backward chaining (SLD resolution) to find all possible answers, following rule chains to arbitrary depth (up to the configurable limit of 100).

**Request**:
```bash
# "Who are the grandparents, and of whom?"
curl -X POST http://localhost:9300/infer \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{
    "predicate": "Grandparent",
    "args": ["?who", "?grandchild"]
  }'
```

**Response** (200 OK, JSON array of structured objects):
```json
[
  {"predicate": "Grandparent", "args": ["alice", "dave"], "negated": false, "scope": null, "metadata": {}},
  {"predicate": "Grandparent", "args": ["alice", "eve"], "negated": false, "scope": null, "metadata": {}}
]
```

Each result object contains:

| Field | Type | Description |
|---|---|---|
| `predicate` | String | The predicate name |
| `args` | String[] | The resolved arguments |
| `negated` | Boolean | `true` if this is a negative assertion |
| `scope` | String? | The scope partition, if any |
| `metadata` | Map\<String, JSON\> | Any metadata attached to the atom |

**Boolean queries** (no variables):
```bash
# "Is alice a grandparent of dave?"
curl -X POST http://localhost:9300/infer \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{
    "predicate": "Grandparent",
    "args": ["alice", "dave"]
  }'
```

If the answer is yes: `[{"predicate": "Grandparent", "args": ["alice", "dave"], "negated": false, "scope": null, "metadata": {}}]`
If the answer is no: `[]`

**Partially bound queries**:
```bash
# "Who are all the grandchildren of alice?"
curl -X POST http://localhost:9300/infer \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{
    "predicate": "Grandparent",
    "args": ["alice", "?grandchild"]
  }'
```

Response:
```json
[
  {"predicate": "Grandparent", "args": ["alice", "dave"], "negated": false, "scope": null, "metadata": {}},
  {"predicate": "Grandparent", "args": ["alice", "eve"], "negated": false, "scope": null, "metadata": {}}
]
```

**What happens internally**:
1. The query atom is created with variables for unknown positions.
2. The backward chainer is invoked.
3. For each goal, it first checks facts in the store (pattern matching via Hexastore).
4. Then it checks rules: for each rule whose head could unify with the goal, it renames the rule's variables (to avoid conflicts), unifies the head, and recursively tries to prove the body.
5. All valid substitutions are collected and returned as the string representations of the substituted query atom.

**Querying with proof trees** — `POST /infer?proof=true`:

Add `?proof=true` to the query string to receive full proof trees showing *how* each result was derived:

```bash
curl -X POST "http://localhost:9300/infer?proof=true" \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{
    "predicate": "Grandparent",
    "args": ["?who", "dave"]
  }'
```

**Response** (200 OK, JSON array of proof tree objects):
```json
[
  {
    "result": {"predicate": "Grandparent", "args": ["alice", "dave"], "negated": false, "scope": null, "metadata": {}},
    "proof": {
      "goal": {"predicate": "Grandparent", "args": ["alice", "dave"], "negated": false, "scope": null, "metadata": {}},
      "step": {
        "type": "rule_application",
        "rule": "FORALL ?x, ?y, ?z { Grandparent(?x, ?z) <- Parent(?x, ?y) AND Parent(?y, ?z) }",
        "bodyProofs": [
          {
            "goal": {"predicate": "Parent", "args": ["alice", "bob"], "negated": false, "scope": null, "metadata": {}},
            "step": {"type": "fact_match", "fact": {"predicate": "Parent", "args": ["alice", "bob"], "negated": false, "scope": null, "metadata": {}}},
            "substitution": {}
          },
          {
            "goal": {"predicate": "Parent", "args": ["bob", "dave"], "negated": false, "scope": null, "metadata": {}},
            "step": {"type": "fact_match", "fact": {"predicate": "Parent", "args": ["bob", "dave"], "negated": false, "scope": null, "metadata": {}}},
            "substitution": {}
          }
        ]
      },
      "substitution": {"x": "alice", "y": "bob", "z": "dave"}
    }
  }
]
```

Each proof tree shows the derivation path: a `fact_match` step means the goal was satisfied directly by a stored fact; a `rule_application` step shows which rule was used and includes nested proofs for each condition in the rule's body. See [Section 8](#8-proof-trees-explaining-derivations) for full details.

**Backward compatibility**: When `proof` is omitted or `false`, the response format is unchanged (`List<AtomResponse>`).

### 6.5 Retract a Fact — `POST /retract`

**Purpose**: Remove a fact from the knowledge base.

**Why it exists**: The world changes. Users log out, permissions are revoked, game states advance. Retraction is how you tell the engine that something is no longer true.

**Request**:
```bash
curl -X POST http://localhost:9300/retract \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{
    "predicate": "Parent",
    "args": ["alice", "bob"]
  }'
```

**Response** (200 OK, plain text):
```
Retracted: Parent(alice, bob)
```

**What happens internally — Truth Maintenance**:

This is where NocturnusAI's ProvenanceTracker shines. When you retract a fact:

1. The fact is removed from the Hexastore.
2. The ProvenanceTracker checks: "Are there any derived facts that depended on this fact?"
3. For each dependent derived fact, it checks: "Was this the only justification?"
4. If yes, the derived fact is also retracted, recursively.

**Example**: If you had:
- Fact: `Parent(alice, bob)`
- Fact: `Parent(bob, dave)`
- Rule: `Grandparent(?x, ?z) :- Parent(?x, ?y), Parent(?y, ?z)`
- Derived: `Grandparent(alice, dave)` (from the rule)

And you retract `Parent(alice, bob)`, then `Grandparent(alice, dave)` is **automatically retracted** because its premise no longer holds.

This is called **non-monotonic reasoning** (or "belief revision"), and it's one of the most powerful features of NocturnusAI. In classical monotonic logic, once something is proven, it's true forever. In NocturnusAI, truth depends on its supporting evidence, and removing evidence removes conclusions.

### 6.6 Execute DSL Command — `POST /execute`

**Purpose**: Execute one or more commands in the LogiQL DSL (NocturnusAI's native logic programming language).

**Why it exists**: For power users and AI agents that want to send multiple operations in a single request, or use the full expressiveness of the DSL syntax.

**Request**:
```bash
curl -X POST http://localhost:9300/execute \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{
    "command": "ASSERT Parent(alice, bob); ASSERT Parent(bob, dave); INFER Parent(?x, ?y);"
  }'
```

**Response** (200 OK, JSON):
```json
{
  "result": "Fact Asserted: Parent(alice, bob)\nFact Asserted: Parent(bob, dave)\nResults:\n  Parent(alice, bob)\n  Parent(bob, dave)"
}
```

The DSL is documented in [Section 17](#17-the-dsl-writing-logic-programs).

### 6.7 Database Management — Admin Endpoints

#### Create a Database

```bash
curl -X POST http://localhost:9300/admin/databases \
  -H "Content-Type: application/json" \
  -d '{"name": "my_project"}'
```

Response: `Database 'my_project' created (MultiTenant=true)`

**Note**: All databases are forced to multi-tenant mode. The `isMultiTenant` field in the request is accepted but ignored—the value is always `true`. This simplifies the architecture and ensures consistent behavior.

#### List All Databases

```bash
curl http://localhost:9300/admin/databases
```

Response:
```json
[
  {"name": "default", "isMultiTenant": true},
  {"name": "my_project", "isMultiTenant": true}
]
```

#### View Facts in a Database

```bash
curl "http://localhost:9300/admin/databases/default/facts" \
  -H "X-Tenant-ID: main"
```

Response:
```json
[
  {"predicate": "Parent", "args": ["alice", "bob"], "negated": false, "scope": null, "metadata": {}},
  {"predicate": "Parent", "args": ["bob", "dave"], "negated": false, "scope": null, "metadata": {}},
  {"predicate": "Is_Suspended", "args": ["alice"], "negated": true, "scope": null, "metadata": {}}
]
```

Optional query parameter: `?scope=my_scope` to filter facts by scope.

#### View Rules in a Database

```bash
curl "http://localhost:9300/admin/databases/default/rules" \
  -H "X-Tenant-ID: main"
```

Response:
```json
[
  "Grandparent(?x, ?z) :- Parent(?x, ?y), Parent(?y, ?z)"
]
```

#### Delete a Database

```bash
curl -X DELETE http://localhost:9300/admin/databases/my_project
```

Response: `Database 'my_project' deleted`

**Note**: The "default" database cannot be deleted.

#### Tenant Management

```bash
# Create a tenant
curl -X POST http://localhost:9300/admin/databases/default/tenants \
  -H "Content-Type: application/json" \
  -d '{"tenantId": "customer_1"}'

# List tenants
curl http://localhost:9300/admin/databases/default/tenants

# Delete a tenant
curl -X DELETE http://localhost:9300/admin/databases/default/tenants/customer_1
```

#### Nuclear Options

```bash
# Wipe ALL data in a database (all tenants)
curl -X POST http://localhost:9300/admin/databases/default/nuke

# Wipe all data for a specific tenant only
curl -X POST http://localhost:9300/admin/databases/default/tenants/main/nuke
```

**Use with extreme caution.** These operations are irreversible.

#### Create a Backup

```bash
curl -X POST "http://localhost:9300/admin/backups?db=default"
```

Response: `Backup created at: /path/to/backup/snapshot.json`

This triggers a snapshot and copies it to the backup directory.

### 6.8 Run Logic Tests — `POST /test`

**Purpose**: Execute isolated logic test cases to verify that a knowledge base produces expected inferences.

**Why it exists**: As knowledge bases grow in complexity, you need a way to verify that rules produce correct results, that adding new rules doesn't break existing inferences, and that edge cases are handled. The test endpoint lets you define setup (facts + rules), expectations (provable, not provable, exact results, result count), and run them in complete isolation from the live database.

**Request**:
```bash
curl -X POST http://localhost:9300/test \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '[
    {
      "name": "grandparent inference",
      "setup": [
        {"type": "assert_fact", "fact": {"predicate": "Parent", "args": ["alice", "bob"]}},
        {"type": "assert_fact", "fact": {"predicate": "Parent", "args": ["bob", "charlie"]}},
        {"type": "assert_rule", "rule": {
          "head": {"predicate": "Grandparent", "args": ["?x", "?z"]},
          "body": [
            {"predicate": "Parent", "args": ["?x", "?y"]},
            {"predicate": "Parent", "args": ["?y", "?z"]}
          ]
        }}
      ],
      "expectations": [
        {"type": "provable", "goal": {"predicate": "Grandparent", "args": ["alice", "?who"]}},
        {"type": "not_provable", "goal": {"predicate": "Grandparent", "args": ["charlie", "?who"]}},
        {"type": "result_count", "goal": {"predicate": "Grandparent", "args": ["alice", "?who"]}, "count": 1}
      ]
    }
  ]'
```

**Response** (200 OK, JSON):
```json
{
  "total": 1,
  "passed": 1,
  "failed": 0,
  "results": [
    {
      "name": "grandparent inference",
      "passed": true,
      "expectationResults": [
        {"passed": true, "message": "Goal Grandparent(alice, ?who) is provable (1 solution(s))", ...},
        {"passed": true, "message": "Goal Grandparent(charlie, ?who) is correctly not provable", ...},
        {"passed": true, "message": "Result count matches: 1", ...}
      ],
      "durationMs": 2
    }
  ],
  "durationMs": 3
}
```

**Setup action types**:
- `"assert_fact"` — requires a `fact` field (same format as `FactRequest`)
- `"assert_rule"` — requires a `rule` field (same format as `RuleRequest`)

**Expectation types**:
- `"provable"` — the goal must have at least one solution
- `"not_provable"` — the goal must have zero solutions
- `"results_exactly"` — the goal's results must match the `expected` list exactly (order-independent)
- `"result_count"` — the goal must produce exactly `count` results

**Isolation**: Each test case runs in its own isolated `LogicContext`. No data from the live database or from other test cases is visible. Tests do not modify the live database.

See [Section 10](#10-logic-testing-framework) for the full testing guide.

---

## 7. The Logic Engine: How It Works Under the Hood

This section explains the algorithms and data structures that power NocturnusAI. Understanding these will help you write efficient queries, design effective rule sets, and troubleshoot unexpected behavior.

### 7.1 The Hexastore: Why 6 Indices?

The Hexastore is the heart of NocturnusAI's storage layer. For every binary fact (a predicate with exactly 2 arguments), it maintains **six separate indices**—one for each permutation of Subject (S), Predicate (P), and Object (O).

```
Fact: Parent(alice, bob)
  S = alice  (first argument)
  P = Parent (predicate)
  O = bob    (second argument)

Indices:
  SPO: alice → Parent → bob → {fact}
  SOP: alice → bob → Parent → {fact}
  PSO: Parent → alice → bob → {fact}
  POS: Parent → bob → alice → {fact}
  OSP: bob → alice → Parent → {fact}
  OPS: bob → Parent → alice → {fact}
```

**Why?** Because different queries need different access patterns:

| Query Pattern | Which Index | Example |
|---|---|---|
| "Who is alice's parent of?" | SPO: `alice → Parent → ?` | Find bob |
| "Who are bob's parents?" | POS: `Parent → bob → ?` | Find alice |
| "What relationships involve bob?" | OPS: `bob → ? → ?` | Find Parent, etc. |
| "Does Parent(alice, bob) exist?" | SPO: `alice → Parent → bob` | O(1) lookup |
| "What does Parent relate?" | PSO: `Parent → ? → ?` | Find all pairs |
| "What involves alice and bob?" | SOP: `alice → bob → ?` | Find predicates |

Without the 6 indices, a query like "Who are bob's parents?" would require scanning every fact in the database. With the POS index, it's a direct O(1) hash map lookup.

**For non-binary facts** (0, 1, 3, or more arguments), a fallback map indexed by predicate name is used. These require a linear scan through all facts with that predicate, so binary predicates are significantly more efficient.

**Negative facts** are stored in the same indices with a `!` prefix on the predicate name. So `NOT Parent(alice, charlie)` is stored under predicate `!Parent`. This means positive and negative facts don't interfere with each other's lookups.

**Thread safety**: All Hexastore operations are protected by a `ReentrantReadWriteLock`. Multiple readers can operate concurrently, but writes are exclusive. This is important because inference (which reads) and assertion (which writes) may happen concurrently in multi-threaded scenarios.

### 7.2 Backward Chaining: Goal-Driven Search (SLD Resolution)

When you call `/infer`, the engine uses **backward chaining**—it starts from your goal and works backwards to find facts that support it.

**Algorithm**:

```
solveRecursive(goals, goalIndex, substitution, depth):
    if depth > MAX_DEPTH (100):
        return empty  (prevent infinite recursion)
    if goalIndex >= goals.length:
        return substitution  (all goals satisfied!)

    currentGoal = apply(substitution, goals[goalIndex])

    // Strategy 1: Find matching facts in the store
    for each fact matching currentGoal:
        newSubst = unify(currentGoal, fact, substitution)
        if newSubst is not null:
            yield from solveRecursive(goals, goalIndex+1, newSubst, depth)

    // Strategy 2: Find matching rules
    for each rule in rules:
        renamedRule = renameVariables(rule)  // Standardization apart
        newSubst = unify(currentGoal, renamedRule.head, substitution)
        if newSubst is not null:
            // Replace current goal with rule body
            newGoals = renamedRule.body + remaining goals
            yield from solveRecursive(newGoals, 0, newSubst, depth+1)
```

**Example walkthrough**:

Given facts:
```
Parent(alice, bob)
Parent(bob, dave)
```

And rule:
```
Grandparent(?x, ?z) :- Parent(?x, ?y), Parent(?y, ?z)
```

Query: `Grandparent(?who, dave)`

1. **Goal**: `Grandparent(?who, dave)` — No matching fact. Try rules.
2. **Rule match**: `Grandparent(?x_1, ?z_1) :- Parent(?x_1, ?y_1), Parent(?y_1, ?z_1)` (renamed variables)
3. **Unify goal with head**: `{?who → ?x_1, ?z_1 → dave}`
4. **New goals**: `Parent(?x_1, ?y_1), Parent(?y_1, dave)`
5. **Solve `Parent(?x_1, ?y_1)`**: Matches `Parent(alice, bob)` → `{?x_1 → alice, ?y_1 → bob}`
6. **Solve `Parent(bob, dave)`**: Matches! → Success
7. **Final substitution**: `{?who → alice}`
8. **Result**: `Grandparent(alice, dave)`

**Why backward chaining?** Because it's **goal-directed**. It only explores facts and rules that are relevant to your query. If you have 10 million facts but your query only touches 5 of them, backward chaining is efficient. Forward chaining (see below) would process all 10 million facts eagerly.

**Depth limit**: The default maximum depth is 100. This prevents infinite recursion from circular rules (e.g., `A(?x) :- B(?x)` and `B(?x) :- A(?x)`). If your legitimate inference chains exceed 100 levels deep, you have an extraordinarily complex rule set.

### 7.3 Forward Chaining: The Rete Engine

While backward chaining is query-driven (lazy), forward chaining is assertion-driven (eager). Every time a fact is asserted, the Rete engine checks if any rules can fire.

**How it works**:

1. Each rule condition is indexed by its predicate into an **alpha node**: `predicate → [rule, conditionIndex]`
2. When `Parent(alice, bob)` is asserted, the engine finds all alpha nodes for predicate `Parent`.
3. For each matching alpha node, it attempts to unify the asserted fact with the rule's condition.
4. If unification succeeds, it tries to satisfy the remaining conditions by querying the store.
5. If all conditions are satisfied, the rule fires and the derived fact is asserted.
6. The newly derived fact then triggers the process again (recursive).

**Example**: With the grandparent rule, asserting `Parent(bob, dave)` would:
1. Find alpha node: `Parent → [GrandparentRule, conditionIndex=1]`
2. Unify `Parent(bob, dave)` with `Parent(?y, ?z)` → `{?y → bob, ?z → dave}`
3. Try to satisfy remaining condition: `Parent(?x, bob)` → Found: `Parent(alice, bob)` → `{?x → alice}`
4. All conditions satisfied → Derive: `Grandparent(alice, dave)` with source=INFERRED
5. Record provenance: `Grandparent(alice, dave)` depends on `[Parent(alice, bob), Parent(bob, dave)]` via GrandparentRule

**Why both backward AND forward chaining?**

- **Forward chaining** eagerly derives all possible conclusions when facts are asserted. This is great for: maintaining a fully materialized view of derived knowledge, triggering side effects, and having instant query responses (since everything is pre-computed).
- **Backward chaining** lazily derives answers only when queried. This is great for: large rule sets where materializing everything would be expensive, and for queries where you only need specific answers.

NocturnusAI uses both. Forward chaining runs on every assertion (populating derived facts in the store), and backward chaining runs on every query (finding answers that might not yet be materialized or that require deeper exploration).

### 7.4 Unification: How Pattern Matching Works

Unification is the core algorithm that determines whether two terms can be made identical by assigning values to variables.

**Rules**:
1. A variable unifies with anything: `?x` unifies with `alice` → `{?x = alice}`
2. Identical constants unify: `alice` unifies with `alice` → `{}` (no new bindings)
3. Different constants don't unify: `alice` ≠ `bob` → fail
4. Variables already bound are followed: if `?x = alice`, then `?x` unifies with `alice` but not `bob`

**Atom unification** requires:
- Same predicate name
- Same truth value
- Same number of arguments
- Each argument pair unifies

**Example**:
```
Unify: Parent(?x, bob) with Parent(alice, ?y)
  Step 1: predicate "Parent" == "Parent" ✓
  Step 2: truthVal true == true ✓
  Step 3: args count 2 == 2 ✓
  Step 4: Unify ?x with alice → {?x = alice}
  Step 5: Unify bob with ?y → {?x = alice, ?y = bob}
  Result: {?x = alice, ?y = bob}
```

### 7.5 Truth Maintenance: Automatic Belief Revision

The ProvenanceTracker maintains a dependency graph:

```
Derived Fact ← (Rule, [Premise1, Premise2, ...])
```

And a reverse index:

```
Premise → [DerivedFact1, DerivedFact2, ...]
```

When a fact is retracted:
1. Find all derived facts that depend on it (via reverse index).
2. For each dependent, check if it was derived using this fact as a premise.
3. If yes, remove the derivation and recursively retract the dependent.
4. Clean up the dependency graph.

**Why this matters**: Without truth maintenance, retracting a premise would leave stale derived facts in the database. Your queries would return answers based on premises that no longer hold. The ProvenanceTracker ensures that the knowledge base is always consistent with its current set of asserted facts.

**Current limitation**: Each derived fact stores only one derivation. If a fact could be derived in multiple ways (through different rules or different premises), only the first derivation is recorded. Retracting one derivation path removes the fact even if other valid derivation paths exist. A more robust TMS would support multiple justifications.

### 7.6 Consistency Guard: Constraint Enforcement

The ConsistencyGuard allows you to define forbidden patterns—combinations of facts that should never be simultaneously true.

**Example constraint**: "A person cannot be both alive and dead"

Using the DSL:
```
RESTRICT ?x { Status(?x, alive) AND Status(?x, dead) } -> CONTRADICTION;
```

Or via the API: You would assert a rule-like constraint through the execute endpoint.

When a new fact is asserted, the ConsistencyGuard attempts to prove each constraint pattern using the new fact plus existing facts. If any constraint pattern is fully satisfiable, the assertion is rejected with an `IllegalStateException`.

This is separate from the simple contradiction check (which catches `P` and `¬P` for the same arguments). The ConsistencyGuard handles domain-specific constraints that go beyond simple negation.

---

## 8. Proof Trees: Explaining Derivations

When NocturnusAI answers a query, it doesn't just tell you *what* is true — it can tell you *why* it's true. Proof trees capture the full derivation path: which facts were matched, which rules were applied, and how variables were bound at each step. This is essential for debugging knowledge bases, auditing inferences, and building explainable AI systems.

### 8.1 What Is a Proof Tree?

A proof tree is a recursive data structure that represents the chain of reasoning from a query goal down to the ground facts that support it. Each node in the tree corresponds to a subgoal that the backward chainer needed to prove.

```
ProofTree
├── result: The fully-substituted answer atom
└── proof: ProofNode
    ├── goal: The subgoal that was proved
    ├── step: How it was proved (FactMatch or RuleApplication)
    └── substitution: Variable bindings at this step
```

### 8.2 Proof Step Types

There are exactly two ways a goal can be proved:

| Step Type | Meaning | When It Occurs |
|---|---|---|
| **FactMatch** | The goal was satisfied directly by a stored fact | The goal unifies with an existing fact in the Hexastore |
| **RuleApplication** | The goal was satisfied by applying a rule | The goal unifies with a rule's head, and all body conditions were recursively proved |

A `FactMatch` is a leaf node — the derivation bottoms out at a concrete fact. A `RuleApplication` is a branch node — it contains nested `ProofNode`s for each condition in the rule's body, forming the recursive tree structure.

### 8.3 Reading a Proof Tree

Consider a knowledge base with:
- Facts: `Parent(alice, bob)`, `Parent(bob, dave)`
- Rule: `Grandparent(?x, ?z) :- Parent(?x, ?y), Parent(?y, ?z)`

Querying `Grandparent(?who, dave)` with proof produces:

```
Grandparent(alice, dave)
└── RuleApplication: Grandparent(?x, ?z) :- Parent(?x, ?y), Parent(?y, ?z)
    ├── Parent(alice, bob) ← FactMatch
    └── Parent(bob, dave) ← FactMatch
    Substitution: {x=alice, y=bob, z=dave}
```

This tells you:
1. The answer `Grandparent(alice, dave)` was derived via the grandparent rule.
2. The rule's body was satisfied by two fact matches: `Parent(alice, bob)` and `Parent(bob, dave)`.
3. The variable bindings that made everything work were `?x=alice`, `?y=bob`, `?z=dave`.

### 8.4 Multi-Step Proof Chains

Proof trees can be arbitrarily deep. With a recursive ancestor rule:

```
Ancestor(?x, ?y) :- Parent(?x, ?y)
Ancestor(?x, ?z) :- Parent(?x, ?y), Ancestor(?y, ?z)
```

Querying `Ancestor(alice, dave)` might produce a proof tree like:

```
Ancestor(alice, dave)
└── RuleApplication: Ancestor(?x, ?z) :- Parent(?x, ?y), Ancestor(?y, ?z)
    ├── Parent(alice, bob) ← FactMatch
    └── Ancestor(bob, dave)
        └── RuleApplication: Ancestor(?x, ?y) :- Parent(?x, ?y)
            └── Parent(bob, dave) ← FactMatch
```

The inner `Ancestor(bob, dave)` proof is itself a `RuleApplication`, demonstrating that proof trees naturally represent recursive inference chains.

### 8.5 Using Proof Trees via the API

Add `?proof=true` to any `/infer` request:

```bash
curl -X POST "http://localhost:9300/infer?proof=true" \
  -H "Content-Type: application/json" \
  -H "X-Database: default" -H "X-Tenant-ID: main" \
  -d '{"predicate": "Grandparent", "args": ["?who", "dave"]}'
```

The response changes from a flat list of atoms to a list of `ProofTree` objects (see [Section 6.4](#64-query-via-inference--post-infer) for the full response format).

Without `?proof=true` (the default), the response format is unchanged — a flat `List<AtomResponse>`.

### 8.6 Using Proof Trees via the DSL

Use the `WITH PROOF` modifier on `INFER` commands:

```
ASSERT Parent(alice, bob);
ASSERT Parent(bob, dave);
ASSERT FORALL ?x, ?y, ?z {
    Grandparent(?x, ?z) <- Parent(?x, ?y) AND Parent(?y, ?z)
};

INFER Grandparent(?who, dave) WITH PROOF;
```

This outputs a text-formatted proof tree:

```
Grandparent(alice, dave) via rule Grandparent(?x, ?z) :- Parent(?x, ?y), Parent(?y, ?z)
  Parent(alice, bob) [fact]
  Parent(bob, dave) [fact]
```

### 8.7 When to Use Proof Trees

| Use Case | Benefit |
|---|---|
| **Debugging rules** | See exactly which rule fired and which facts were used |
| **Auditing inferences** | Verify that the derivation path is correct and expected |
| **Explainable AI** | Present human-readable reasoning chains to end users |
| **Testing** | The test framework includes proofs in results for debugging failed expectations |
| **Knowledge base validation** | Identify unintended derivation paths or missing rules |

### 8.8 Performance Note

Proof trees add overhead because the engine must track the derivation path at each step. For high-volume inference where you don't need explanations, omit `?proof=true` to use the standard (faster) inference path.

---

## 9. Logic Templates: Pre-Built Reasoning Patterns

Templates are pre-built reasoning structures based on classical formal logic. They save you from manually constructing rule bodies and heads for well-known patterns. Each template generates one or more rules that encode a specific form of logical reasoning.

### 9.0 Quick Reference

| Template | Enum Value | Required Predicates | Rules Generated | Use Case |
|---|---|---|---|---|
| Modus Ponens / Syllogism | `MODUS_PONENS` or `SYLLOGISM` | `P`, `Q` | 1 | If P then Q |
| Modus Tollens | `MODUS_TOLLENS` | `P`, `Q` | 2 | If not Q then not P (contrapositive) |
| Disjunctive Syllogism | `DISJUNCTIVE_SYLLOGISM` | `P`, `Q` | 2 | P or Q (elimination) |
| Hypothetical Syllogism | `HYPOTHETICAL_SYLLOGISM` or `FACT_CHAIN` | `A`, `B`, `C`, ... (sorted) | N-1 | Implication chain A→B→C |
| Constructive Dilemma | `CONSTRUCTIVE_DILEMMA` | `P`, `Q`, `R`, `S` | 4 | (P→R) ∧ (Q→S) ∧ (P∨Q) |
| Destructive Dilemma | `DESTRUCTIVE_DILEMMA` | `P`, `Q`, `R`, `S` | 4 | Contrapositive dilemma |
| Causal Argument | `CAUSAL_ARGUMENT` | `CAUSE`, `EFFECT` | 1 | Cause → Effect |
| Definitional Argument | `DEFINITIONAL_ARGUMENT` | `FEATURE`, `CATEGORY` | 1 | Feature → Category |
| Practical Argument | `PRACTICAL_ARGUMENT` | `CONCLUSION`, `EVIDENCE`, `EXCEPTION` | 1 | Evidence ∧ ¬Exception → Conclusion |
| Evaluative Argument | `EVALUATIVE_ARGUMENT` | `EVALUATION`, `CRITERIA` | 1 | Criteria → Evaluation |

### 9.0.1 Template Request Format

All templates use the same endpoint and request structure:

```
POST /assert/template
Content-Type: application/json
X-Database: <database-name>
X-Tenant-ID: <tenant-id>
```

**Request body** (`TemplateRequest`):

```json
{
  "type": "TEMPLATE_TYPE_ENUM",
  "predicates": { "KEY": "Predicate_Name", ... },
  "args": ["variable_name_1", "variable_name_2"],
  "scope": "optional_scope_name"
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `type` | string | yes | One of the `TemplateType` enum values (see table above) |
| `predicates` | object | yes | Map of placeholder keys to actual predicate names. Keys depend on the template type. |
| `args` | string[] | yes | Variable names used in the generated rule(s). These become `?variable_name` in the rules. For unary predicates use `["x"]`; for binary use `["x", "y"]`. |
| `scope` | string | no | Optional scope for multi-tenant/hypothetical reasoning |

**Response** (200 OK, plain text): One line per generated rule, e.g.:
```
Rule Asserted: Is_Mortal(?x) :- Is_Man(?x)
```

If used within a transaction (via `X-Transaction-ID` header), rules are buffered and committed with the transaction.

**Predicate naming convention**: Use `Snake_Case` with leading capital for readability. Predicates are case-sensitive identifiers.

### 9.1 Modus Ponens / Syllogism

**Template types**: `MODUS_PONENS` or `SYLLOGISM` (these are interchangeable)

**Formal logic**: P(x) → Q(x). If P implies Q, and P is true for some x, then Q is true for x.

**Plain English**: "All P are Q." / "If something is P, then it is Q."

**Required predicates**: `P` (the condition), `Q` (the conclusion)

**Rules generated**: 1 rule — `Q(?x) :- P(?x)`

```bash
# Step 1: Create the rule — "All men are mortal"
curl -X POST http://localhost:9300/assert/template \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{
    "type": "MODUS_PONENS",
    "predicates": {"P": "Is_Man", "Q": "Is_Mortal"},
    "args": ["x"]
  }'
# Response: Rule Asserted: Is_Mortal(?x) :- Is_Man(?x)

# Step 2: Assert a fact — "Socrates is a man"
curl -X POST http://localhost:9300/assert/fact \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{"predicate": "Is_Man", "args": ["socrates"]}'

# Step 3: Query — "Is Socrates mortal?"
curl -X POST http://localhost:9300/infer \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{"predicate": "Is_Mortal", "args": ["socrates"]}'
# Returns: ["Is_Mortal(socrates)"]

# Step 4: Open query — "Who is mortal?"
curl -X POST http://localhost:9300/infer \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{"predicate": "Is_Mortal", "args": ["?who"]}'
# Returns: ["Is_Mortal(socrates)"]
```

**Software engineering examples**:
- Access control: `{"P": "Is_Admin", "Q": "Has_Write_Access"}` — "If a user is an admin, they have write access."
- CI/CD: `{"P": "Build_Failed", "Q": "Deploy_Blocked"}` — "If the build fails, deployment is blocked."
- Feature flags: `{"P": "Is_Beta_User", "Q": "See_New_UI"}` — "If a user is in beta, they see the new UI."

**Multi-variable example** (binary predicates):
```bash
# "If X manages Y, then X can review Y's code"
curl -X POST http://localhost:9300/assert/template \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{
    "type": "MODUS_PONENS",
    "predicates": {"P": "Manages", "Q": "Can_Review"},
    "args": ["manager", "report"]
  }'
# Generates: Can_Review(?manager, ?report) :- Manages(?manager, ?report)

# Assert: Alice manages Bob
curl -X POST http://localhost:9300/assert/fact \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{"predicate": "Manages", "args": ["alice", "bob"]}'

# Query: Can Alice review Bob's code?
curl -X POST http://localhost:9300/infer \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{"predicate": "Can_Review", "args": ["alice", "bob"]}'
# Returns: ["Can_Review(alice, bob)"]
```

### 9.2 Modus Tollens

**Template type**: `MODUS_TOLLENS`

**Formal logic**: P(x) → Q(x), ¬Q(x) → ¬P(x). If P implies Q, and Q is false, then P must be false.

**Plain English**: "If P then Q. Q is not true. Therefore P is not true." This is the **contrapositive** of Modus Ponens.

**Required predicates**: `P` (the premise), `Q` (the expected consequence)

**Rules generated**: 2 rules:
1. Forward: `Q(?x) :- P(?x)`
2. Contrapositive: `NOT P(?x) :- NOT Q(?x)`

```bash
# Step 1: Create the rules — "If server is running, port is open"
curl -X POST http://localhost:9300/assert/template \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{
    "type": "MODUS_TOLLENS",
    "predicates": {"P": "Server_Running", "Q": "Port_Open"},
    "args": ["host"]
  }'
# Response:
#   Rule Asserted: Port_Open(?host) :- Server_Running(?host)
#   Rule Asserted: NOT Server_Running(?host) :- NOT Port_Open(?host)

# Step 2: Assert a negative fact — "Port is NOT open on webserver1"
curl -X POST http://localhost:9300/assert/fact \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{"predicate": "Port_Open", "args": ["webserver1"], "truth": false}'

# Step 3: Query — "Is webserver1 running?"
curl -X POST http://localhost:9300/infer \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{"predicate": "Server_Running", "args": ["webserver1"], "truth": false}'
# Returns: ["NOT Server_Running(webserver1)"]
# The engine derived: port is not open → server is not running
```

**How it works**: The contrapositive rule fires when you assert a negative fact for Q. If you assert `NOT Port_Open(webserver1)`, the engine can derive `NOT Server_Running(webserver1)` via the second generated rule. You must explicitly assert the negative fact — the engine uses explicit negation, not negation-as-failure.

**Software engineering examples**:
- Health checks: `{"P": "Service_Healthy", "Q": "Responds_To_Ping"}` — "If the service is healthy, it responds to pings. It's not responding. Therefore it's not healthy."
- Test validation: `{"P": "Code_Correct", "Q": "Tests_Pass"}` — "If code is correct, tests pass. Tests fail. Therefore code is not correct."
- Deployment: `{"P": "Deploy_Succeeded", "Q": "Endpoint_Reachable"}` — "If deploy succeeded, the endpoint is reachable. Endpoint unreachable. Deploy did not succeed."

### 9.3 Disjunctive Syllogism (Process of Elimination)

**Template type**: `DISJUNCTIVE_SYLLOGISM`

**Formal logic**: P(x) ∨ Q(x). If ¬P(x) then Q(x); if ¬Q(x) then P(x).

**Plain English**: "Either P or Q is true. If we know one is false, the other must be true."

**Required predicates**: `P` (first disjunct), `Q` (second disjunct)

**Rules generated**: 2 rules:
1. `Q(?x) :- NOT P(?x)`
2. `P(?x) :- NOT Q(?x)`

```bash
# Step 1: Create the rules — "The problem is either network or disk"
curl -X POST http://localhost:9300/assert/template \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{
    "type": "DISJUNCTIVE_SYLLOGISM",
    "predicates": {"P": "Network_Issue", "Q": "Disk_Issue"},
    "args": ["system"]
  }'
# Response:
#   Rule Asserted: Disk_Issue(?system) :- NOT Network_Issue(?system)
#   Rule Asserted: Network_Issue(?system) :- NOT Disk_Issue(?system)

# Step 2: Rule out one possibility — "It's NOT a network issue on prod-server"
curl -X POST http://localhost:9300/assert/fact \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{"predicate": "Network_Issue", "args": ["prod-server"], "truth": false}'

# Step 3: Query — "Is it a disk issue on prod-server?"
curl -X POST http://localhost:9300/infer \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{"predicate": "Disk_Issue", "args": ["prod-server"]}'
# Returns: ["Disk_Issue(prod-server)"]
# The engine derived: not a network issue → must be a disk issue
```

**How it works**: You assert the negation of one disjunct as an explicit negative fact (`"truth": false`). The corresponding rule fires and derives the other disjunct. This models exclusive "either/or" reasoning.

**Software engineering examples**:
- Root cause analysis: `{"P": "Frontend_Bug", "Q": "Backend_Bug"}` — "The bug is either frontend or backend. Frontend tests pass. Therefore it's a backend bug."
- Environment issues: `{"P": "Config_Error", "Q": "Code_Error"}` — "It's either config or code. Config validated OK. Therefore it's a code error."
- Failover: `{"P": "Primary_Available", "Q": "Use_Replica"}` — "Either primary is available or we use the replica."

### 9.4 Hypothetical Syllogism / Fact Chain

**Template types**: `HYPOTHETICAL_SYLLOGISM` or `FACT_CHAIN` (interchangeable)

**Formal logic**: P→Q, Q→R, therefore P→R. Creates a transitive chain of implications.

**Plain English**: "If A then B, and if B then C, and if C then D, ..." — a chain where each step implies the next.

**Required predicates**: Multiple predicates with keys that sort alphabetically to define the chain order. The map keys are sorted, so `"A"` comes before `"B"` comes before `"C"`, etc. The chain follows: A→B→C→...

**Rules generated**: N-1 rules (one for each link in the chain)

**Important**: The predicate *map keys* (e.g. `"A"`, `"B"`, `"C"`) are sorted alphabetically to determine the chain order. The *values* are the actual predicate names used in the rules.

```bash
# Step 1: Create the chain — Trainee → Junior → Senior → Lead
curl -X POST http://localhost:9300/assert/template \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{
    "type": "FACT_CHAIN",
    "predicates": {"A": "Trainee", "B": "Junior", "C": "Senior", "D": "Lead"},
    "args": ["employee"]
  }'
# Response:
#   Rule Asserted: Junior(?employee) :- Trainee(?employee)
#   Rule Asserted: Senior(?employee) :- Junior(?employee)
#   Rule Asserted: Lead(?employee) :- Senior(?employee)

# Step 2: Assert a fact — "Alice is a trainee"
curl -X POST http://localhost:9300/assert/fact \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{"predicate": "Trainee", "args": ["alice"]}'

# Step 3: Query — "Is Alice a senior?" (skipping a step via chained inference)
curl -X POST http://localhost:9300/infer \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{"predicate": "Senior", "args": ["alice"]}'
# Returns: ["Senior(alice)"]
# The engine chains: Trainee(alice) → Junior(alice) → Senior(alice)

# Step 4: Query — "Is Alice a lead?" (full chain)
curl -X POST http://localhost:9300/infer \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{"predicate": "Lead", "args": ["alice"]}'
# Returns: ["Lead(alice)"]
# The engine chains: Trainee → Junior → Senior → Lead
```

**Software engineering examples**:
- Escalation pipeline: `{"A": "Info", "B": "Warning", "C": "Error", "D": "Critical"}` — severity escalation chain
- Deployment stages: `{"A": "Code_Committed", "B": "Tests_Pass", "C": "Staging_Deployed", "D": "Production_Deployed"}`
- Approval workflow: `{"A": "Submitted", "B": "Reviewed", "C": "Approved", "D": "Merged"}`

### 9.5 Constructive Dilemma

**Template type**: `CONSTRUCTIVE_DILEMMA`

**Formal logic**: (P→R) ∧ (Q→S) ∧ (P∨Q) → (R∨S). Two conditionals with a disjunction of their antecedents.

**Plain English**: "P leads to R, and Q leads to S. Either P or Q is true. Therefore either R or S follows."

**Required predicates**: `P` (first antecedent), `Q` (second antecedent), `R` (first consequent), `S` (second consequent)

**Rules generated**: 4 rules:
1. `R(?x) :- P(?x)` — P implies R
2. `S(?x) :- Q(?x)` — Q implies S
3. `Q(?x) :- NOT P(?x)` — disjunction: if not P then Q
4. `P(?x) :- NOT Q(?x)` — disjunction: if not Q then P

```bash
# Step 1: Create the rules — weather preparedness
# "If raining, bring umbrella. If sunny, bring sunscreen. It's either raining or sunny."
curl -X POST http://localhost:9300/assert/template \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{
    "type": "CONSTRUCTIVE_DILEMMA",
    "predicates": {
      "P": "Raining", "R": "Bring_Umbrella",
      "Q": "Sunny", "S": "Bring_Sunscreen"
    },
    "args": ["day"]
  }'
# Response:
#   Rule Asserted: Bring_Umbrella(?day) :- Raining(?day)
#   Rule Asserted: Bring_Sunscreen(?day) :- Sunny(?day)
#   Rule Asserted: Sunny(?day) :- NOT Raining(?day)
#   Rule Asserted: Raining(?day) :- NOT Sunny(?day)

# Step 2: Assert one condition — "Monday is rainy"
curl -X POST http://localhost:9300/assert/fact \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{"predicate": "Raining", "args": ["monday"]}'

# Step 3: Query — "Do I need an umbrella on Monday?"
curl -X POST http://localhost:9300/infer \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{"predicate": "Bring_Umbrella", "args": ["monday"]}'
# Returns: ["Bring_Umbrella(monday)"]

# Step 4: Rule out one condition — "Tuesday is not rainy"
curl -X POST http://localhost:9300/assert/fact \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{"predicate": "Raining", "args": ["tuesday"], "truth": false}'

# Step 5: Query — "What do I need on Tuesday?"
curl -X POST http://localhost:9300/infer \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{"predicate": "Bring_Sunscreen", "args": ["tuesday"]}'
# Returns: ["Bring_Sunscreen(tuesday)"]
# The engine chains: NOT Raining(tuesday) → Sunny(tuesday) → Bring_Sunscreen(tuesday)
```

**Software engineering examples**:
- Error handling: `{"P": "Retryable_Error", "R": "Auto_Retry", "Q": "Fatal_Error", "S": "Alert_Oncall"}` — "If retryable, auto-retry. If fatal, page on-call. It's one or the other."
- Deployment strategy: `{"P": "Low_Traffic", "R": "Blue_Green_Deploy", "Q": "High_Traffic", "S": "Canary_Deploy"}`
- Storage: `{"P": "Small_File", "R": "Store_Inline", "Q": "Large_File", "S": "Store_In_Blob"}`

### 9.6 Destructive Dilemma

**Template type**: `DESTRUCTIVE_DILEMMA`

**Formal logic**: (P→R) ∧ (Q→S) ∧ (¬R∨¬S) → (¬P∨¬Q). Two conditionals; if one or both consequents are false, the corresponding antecedent(s) must be false.

**Plain English**: "P leads to R, and Q leads to S. But R or S is missing. Therefore P or Q must be false." This is the contrapositive counterpart of the Constructive Dilemma — it reasons backward from absent outcomes to eliminate causes.

**Required predicates**: `P` (first antecedent), `Q` (second antecedent), `R` (first consequent), `S` (second consequent)

**Rules generated**: 4 rules:
1. `R(?x) :- P(?x)` — P implies R (forward)
2. `S(?x) :- Q(?x)` — Q implies S (forward)
3. `NOT S(?x) :- R(?x)` — disjunction of negations: if R is true, S is false
4. `NOT R(?x) :- S(?x)` — disjunction of negations: if S is true, R is false

```bash
# Step 1: Create the rules — diagnosing system failures
# "Good hardware → fast I/O. Good software → no crashes.
#  We see slow I/O or crashes. Therefore hardware or software is bad."
curl -X POST http://localhost:9300/assert/template \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{
    "type": "DESTRUCTIVE_DILEMMA",
    "predicates": {
      "P": "Good_Hardware", "R": "Fast_IO",
      "Q": "Good_Software", "S": "No_Crashes"
    },
    "args": ["server"]
  }'
# Response:
#   Rule Asserted: Fast_IO(?server) :- Good_Hardware(?server)
#   Rule Asserted: No_Crashes(?server) :- Good_Software(?server)
#   Rule Asserted: NOT No_Crashes(?server) :- Fast_IO(?server)
#   Rule Asserted: NOT Fast_IO(?server) :- No_Crashes(?server)

# Step 2: Assert that I/O is fast on a server (one consequent is present)
curl -X POST http://localhost:9300/assert/fact \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{"predicate": "Fast_IO", "args": ["db-server"]}'

# Step 3: The disjunction rule derives the other consequent is absent
curl -X POST http://localhost:9300/infer \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{"predicate": "No_Crashes", "args": ["db-server"], "truth": false}'
# Returns: ["NOT No_Crashes(db-server)"]
# Engine derives: Fast_IO present → NOT No_Crashes (the disjunctive negation)
```

**Software engineering examples**:
- Diagnostics: `{"P": "Network_OK", "R": "Low_Latency", "Q": "DB_OK", "S": "Fast_Queries"}` — "If latency is high or queries are slow, network or DB is not OK."
- Quality gates: `{"P": "Code_Reviewed", "R": "No_Style_Issues", "Q": "Tests_Written", "S": "Coverage_Met"}` — "If there are style issues or coverage gaps, review or tests are incomplete."

### 9.7 Causal Argument

**Template type**: `CAUSAL_ARGUMENT`

**Formal logic**: Cause(x) → Effect(x). A direct cause-and-effect relationship.

**Plain English**: "If the cause is present, the effect follows."

**Required predicates**: `CAUSE` (the triggering condition), `EFFECT` (the resulting outcome)

**Rules generated**: 1 rule — `Effect(?x) :- Cause(?x)`

This is functionally identical to Modus Ponens but uses domain-specific predicate keys (`CAUSE`/`EFFECT`) for clarity when modeling causal relationships.

```bash
# Step 1: Create the rule — "High load causes latency spikes"
curl -X POST http://localhost:9300/assert/template \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{
    "type": "CAUSAL_ARGUMENT",
    "predicates": {"CAUSE": "High_Load", "EFFECT": "Latency_Spike"},
    "args": ["service"]
  }'
# Response: Rule Asserted: Latency_Spike(?service) :- High_Load(?service)

# Step 2: Assert the cause
curl -X POST http://localhost:9300/assert/fact \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{"predicate": "High_Load", "args": ["api-gateway"]}'

# Step 3: Query the effect
curl -X POST http://localhost:9300/infer \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{"predicate": "Latency_Spike", "args": ["api-gateway"]}'
# Returns: ["Latency_Spike(api-gateway)"]

# Step 4: Open query — "Which services have latency spikes?"
curl -X POST http://localhost:9300/infer \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{"predicate": "Latency_Spike", "args": ["?which"]}'
# Returns: ["Latency_Spike(api-gateway)"]
```

**Software engineering examples**:
- Monitoring: `{"CAUSE": "Memory_Leak", "EFFECT": "OOM_Kill"}` — "Memory leaks cause OOM kills."
- Incident response: `{"CAUSE": "Config_Change", "EFFECT": "Service_Restart"}` — "A config change causes a service restart."
- Performance: `{"CAUSE": "Missing_Index", "EFFECT": "Slow_Query"}` — "Missing indexes cause slow queries."

### 9.8 Definitional Argument

**Template type**: `DEFINITIONAL_ARGUMENT`

**Formal logic**: Feature(x) → Category(x). Having a feature implies membership in a category.

**Plain English**: "If something has this feature, it belongs to this category."

**Required predicates**: `FEATURE` (the observable property), `CATEGORY` (the classification)

**Rules generated**: 1 rule — `Category(?x) :- Feature(?x)`

This is functionally identical to Modus Ponens but uses domain-specific keys (`FEATURE`/`CATEGORY`) for clarity when modeling classification and categorization.

```bash
# Step 1: Create the rule — "If it has wings, it's a bird"
curl -X POST http://localhost:9300/assert/template \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{
    "type": "DEFINITIONAL_ARGUMENT",
    "predicates": {"FEATURE": "Has_Wings", "CATEGORY": "Bird"},
    "args": ["animal"]
  }'
# Response: Rule Asserted: Bird(?animal) :- Has_Wings(?animal)

# Step 2: Assert features
curl -X POST http://localhost:9300/assert/fact \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{"predicate": "Has_Wings", "args": ["eagle"]}'

curl -X POST http://localhost:9300/assert/fact \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{"predicate": "Has_Wings", "args": ["sparrow"]}'

# Step 3: Query — "What are all the birds?"
curl -X POST http://localhost:9300/infer \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{"predicate": "Bird", "args": ["?what"]}'
# Returns: ["Bird(eagle)", "Bird(sparrow)"]
```

**Software engineering examples**:
- Service classification: `{"FEATURE": "Handles_HTTP", "CATEGORY": "Web_Service"}` — "If it handles HTTP, it's a web service."
- Error categorization: `{"FEATURE": "Returns_5xx", "CATEGORY": "Server_Error"}` — "If it returns 5xx, it's a server error."
- Tagging: `{"FEATURE": "Uses_GPU", "CATEGORY": "Compute_Intensive"}` — "If it uses GPU, it's compute-intensive."

**Chaining definitions** — You can combine multiple definitional templates to build a taxonomy:
```bash
# "Has_Feathers → Bird, Bird → Animal"
curl -X POST http://localhost:9300/assert/template \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{"type": "DEFINITIONAL_ARGUMENT", "predicates": {"FEATURE": "Has_Feathers", "CATEGORY": "Bird"}, "args": ["x"]}'

curl -X POST http://localhost:9300/assert/template \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{"type": "DEFINITIONAL_ARGUMENT", "predicates": {"FEATURE": "Bird", "CATEGORY": "Animal"}, "args": ["x"]}'

# Now assert "eagle has feathers" and query "Is eagle an animal?"
# The engine chains: Has_Feathers(eagle) → Bird(eagle) → Animal(eagle)
```

### 9.9 Practical Argument

**Template type**: `PRACTICAL_ARGUMENT`

**Formal logic**: Evidence(x) ∧ ¬Exception(x) → Conclusion(x). The conclusion holds if the evidence is present AND no exception applies.

**Plain English**: "The conclusion follows from the evidence, UNLESS an exception is present." This models defeasible reasoning — conclusions that can be overridden by explicit exceptions.

**Required predicates**: `EVIDENCE` (supporting condition), `EXCEPTION` (blocking condition), `CONCLUSION` (the outcome)

**Rules generated**: 1 rule — `Conclusion(?x) :- Evidence(?x), NOT Exception(?x)`

```bash
# Step 1: Create the rule — "Approve loan if good credit AND no fraud flag"
curl -X POST http://localhost:9300/assert/template \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{
    "type": "PRACTICAL_ARGUMENT",
    "predicates": {
      "CONCLUSION": "Approve_Loan",
      "EVIDENCE": "Good_Credit",
      "EXCEPTION": "Fraud_Flag"
    },
    "args": ["applicant"]
  }'
# Response: Rule Asserted: Approve_Loan(?applicant) :- Good_Credit(?applicant), NOT Fraud_Flag(?applicant)

# Step 2: Assert evidence for two applicants
curl -X POST http://localhost:9300/assert/fact \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{"predicate": "Good_Credit", "args": ["alice"]}'

curl -X POST http://localhost:9300/assert/fact \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{"predicate": "Good_Credit", "args": ["bob"]}'

# Step 3: Flag one applicant as fraudulent (explicit negation of exception)
curl -X POST http://localhost:9300/assert/fact \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{"predicate": "Fraud_Flag", "args": ["bob"]}'

# Step 4: Assert that Alice has no fraud flag (explicit negative fact required)
curl -X POST http://localhost:9300/assert/fact \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{"predicate": "Fraud_Flag", "args": ["alice"], "truth": false}'

# Step 5: Query — "Should Alice's loan be approved?"
curl -X POST http://localhost:9300/infer \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{"predicate": "Approve_Loan", "args": ["alice"]}'
# Returns: ["Approve_Loan(alice)"]
# Alice has good credit AND explicitly no fraud flag → approved

# Step 6: Query — "Should Bob's loan be approved?"
curl -X POST http://localhost:9300/infer \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{"predicate": "Approve_Loan", "args": ["bob"]}'
# Returns: [] (empty — Bob has a fraud flag, so the exception blocks approval)
```

**Important — Explicit Negation**: NocturnusAI uses explicit negation, not negation-as-failure. For the `NOT Exception` condition to be satisfied, you must explicitly assert the negative fact: `{"predicate": "Fraud_Flag", "args": ["alice"], "truth": false}`. Simply not asserting `Fraud_Flag(alice)` is NOT sufficient — the engine won't assume it's false just because it's absent.

**Software engineering examples**:
- Deployment gates: `{"CONCLUSION": "Deploy_To_Prod", "EVIDENCE": "All_Tests_Pass", "EXCEPTION": "Deploy_Freeze"}` — "Deploy if tests pass, unless there's a freeze."
- Access control: `{"CONCLUSION": "Grant_Access", "EVIDENCE": "Valid_Credentials", "EXCEPTION": "Account_Locked"}` — "Grant access if credentials are valid, unless account is locked."
- Auto-scaling: `{"CONCLUSION": "Scale_Up", "EVIDENCE": "High_CPU", "EXCEPTION": "Budget_Exceeded"}` — "Scale up on high CPU, unless budget is exceeded."
- PR merge: `{"CONCLUSION": "Auto_Merge", "EVIDENCE": "Approved_Review", "EXCEPTION": "Merge_Conflict"}` — "Auto-merge if review approved, unless there's a conflict."

### 9.10 Evaluative Argument

**Template type**: `EVALUATIVE_ARGUMENT`

**Formal logic**: Criteria(x) → Evaluation(x). Meeting the criteria implies the evaluation holds.

**Plain English**: "If the criteria is met, then the evaluation/judgment applies."

**Required predicates**: `CRITERIA` (the measurable condition), `EVALUATION` (the qualitative judgment)

**Rules generated**: 1 rule — `Evaluation(?x) :- Criteria(?x)`

This is functionally identical to Modus Ponens but uses domain-specific keys (`CRITERIA`/`EVALUATION`) for clarity when modeling quality judgments and assessments.

```bash
# Step 1: Create the rule — "If latency is low, performance is good"
curl -X POST http://localhost:9300/assert/template \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{
    "type": "EVALUATIVE_ARGUMENT",
    "predicates": {"CRITERIA": "Low_Latency", "EVALUATION": "Good_Performance"},
    "args": ["endpoint"]
  }'
# Response: Rule Asserted: Good_Performance(?endpoint) :- Low_Latency(?endpoint)

# Step 2: Assert criteria for some endpoints
curl -X POST http://localhost:9300/assert/fact \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{"predicate": "Low_Latency", "args": ["api-users"]}'

curl -X POST http://localhost:9300/assert/fact \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{"predicate": "Low_Latency", "args": ["api-health"]}'

# Step 3: Query — "Which endpoints have good performance?"
curl -X POST http://localhost:9300/infer \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{"predicate": "Good_Performance", "args": ["?endpoint"]}'
# Returns: ["Good_Performance(api-users)", "Good_Performance(api-health)"]
```

**Software engineering examples**:
- SLA compliance: `{"CRITERIA": "Uptime_99_9", "EVALUATION": "SLA_Met"}` — "If uptime is 99.9%, SLA is met."
- Code quality: `{"CRITERIA": "Coverage_Above_80", "EVALUATION": "Well_Tested"}` — "If coverage is above 80%, it's well tested."
- Security posture: `{"CRITERIA": "No_Critical_CVEs", "EVALUATION": "Secure"}` — "If there are no critical CVEs, the system is secure."

**Chaining evaluations** — Combine multiple evaluative templates for composite quality scores:
```bash
# "Low latency → Good Performance, High Availability → Reliable, Good Performance + Reliable → Production Ready"
# Use EVALUATIVE_ARGUMENT for the first two, then a manual rule for the conjunction:
curl -X POST http://localhost:9300/assert/template \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{"type": "EVALUATIVE_ARGUMENT", "predicates": {"CRITERIA": "Low_Latency", "EVALUATION": "Good_Performance"}, "args": ["svc"]}'

curl -X POST http://localhost:9300/assert/template \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{"type": "EVALUATIVE_ARGUMENT", "predicates": {"CRITERIA": "High_Availability", "EVALUATION": "Reliable"}, "args": ["svc"]}'

# For conjunction (both criteria), use a manual rule:
curl -X POST http://localhost:9300/assert/rule \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{
    "head": {"predicate": "Production_Ready", "args": ["?svc"]},
    "body": [
      {"predicate": "Good_Performance", "args": ["?svc"]},
      {"predicate": "Reliable", "args": ["?svc"]}
    ]
  }'
```

### 9.11 Combining Templates: Real-World Patterns

Templates are most powerful when combined. Here are patterns a developer would commonly use:

#### Pattern A: Classification + Access Control
```bash
# 1. Definitional: "If handles payments, it's a PCI service"
curl -X POST http://localhost:9300/assert/template \
  -H "Content-Type: application/json" \
  -H "X-Database: default" -H "X-Tenant-ID: main" \
  -d '{"type": "DEFINITIONAL_ARGUMENT", "predicates": {"FEATURE": "Handles_Payments", "CATEGORY": "PCI_Service"}, "args": ["svc"]}'

# 2. Modus Ponens: "PCI services require encryption"
curl -X POST http://localhost:9300/assert/template \
  -H "Content-Type: application/json" \
  -H "X-Database: default" -H "X-Tenant-ID: main" \
  -d '{"type": "MODUS_PONENS", "predicates": {"P": "PCI_Service", "Q": "Requires_Encryption"}, "args": ["svc"]}'

# 3. Assert fact: "checkout-api handles payments"
curl -X POST http://localhost:9300/assert/fact \
  -H "Content-Type: application/json" \
  -H "X-Database: default" -H "X-Tenant-ID: main" \
  -d '{"predicate": "Handles_Payments", "args": ["checkout-api"]}'

# 4. Query: "Does checkout-api require encryption?"
curl -X POST http://localhost:9300/infer \
  -H "Content-Type: application/json" \
  -H "X-Database: default" -H "X-Tenant-ID: main" \
  -d '{"predicate": "Requires_Encryption", "args": ["checkout-api"]}'
# Returns: ["Requires_Encryption(checkout-api)"]
# Chain: Handles_Payments → PCI_Service → Requires_Encryption
```

#### Pattern B: Incident Diagnosis (Causal + Disjunctive + Modus Tollens)
```bash
# 1. Causal: "Memory leak causes OOM"
curl -X POST http://localhost:9300/assert/template \
  -H "Content-Type: application/json" \
  -H "X-Database: default" -H "X-Tenant-ID: main" \
  -d '{"type": "CAUSAL_ARGUMENT", "predicates": {"CAUSE": "Memory_Leak", "EFFECT": "OOM_Error"}, "args": ["proc"]}'

# 2. Modus Tollens: "If code is correct, tests pass" (and contrapositive)
curl -X POST http://localhost:9300/assert/template \
  -H "Content-Type: application/json" \
  -H "X-Database: default" -H "X-Tenant-ID: main" \
  -d '{"type": "MODUS_TOLLENS", "predicates": {"P": "Code_Correct", "Q": "Tests_Pass"}, "args": ["module"]}'

# 3. Disjunctive: "Problem is either config or code"
curl -X POST http://localhost:9300/assert/template \
  -H "Content-Type: application/json" \
  -H "X-Database: default" -H "X-Tenant-ID: main" \
  -d '{"type": "DISJUNCTIVE_SYLLOGISM", "predicates": {"P": "Config_Issue", "Q": "Code_Issue"}, "args": ["module"]}'

# Assert: tests are NOT passing for auth-module
curl -X POST http://localhost:9300/assert/fact \
  -H "Content-Type: application/json" \
  -H "X-Database: default" -H "X-Tenant-ID: main" \
  -d '{"predicate": "Tests_Pass", "args": ["auth-module"], "truth": false}'

# Infer: "Is the code correct?" → NOT Code_Correct(auth-module)
# (Modus Tollens contrapositive fires)
```

#### Pattern C: Deployment Gate (Practical + Fact Chain)
```bash
# 1. Fact chain: "Committed → Built → Tested → Staged"
curl -X POST http://localhost:9300/assert/template \
  -H "Content-Type: application/json" \
  -H "X-Database: default" -H "X-Tenant-ID: main" \
  -d '{"type": "FACT_CHAIN", "predicates": {"A": "Committed", "B": "Built", "C": "Tested", "D": "Staged"}, "args": ["release"]}'

# 2. Practical: "Deploy to prod if staged AND no freeze"
curl -X POST http://localhost:9300/assert/template \
  -H "Content-Type: application/json" \
  -H "X-Database: default" -H "X-Tenant-ID: main" \
  -d '{"type": "PRACTICAL_ARGUMENT", "predicates": {"CONCLUSION": "Deploy_Prod", "EVIDENCE": "Staged", "EXCEPTION": "Deploy_Freeze"}, "args": ["release"]}'

# Assert: v2.1 is committed, no deploy freeze
curl -X POST http://localhost:9300/assert/fact \
  -H "Content-Type: application/json" \
  -H "X-Database: default" -H "X-Tenant-ID: main" \
  -d '{"predicate": "Committed", "args": ["v2.1"]}'

curl -X POST http://localhost:9300/assert/fact \
  -H "Content-Type: application/json" \
  -H "X-Database: default" -H "X-Tenant-ID: main" \
  -d '{"predicate": "Deploy_Freeze", "args": ["v2.1"], "truth": false}'

# Query: "Can v2.1 deploy to prod?"
curl -X POST http://localhost:9300/infer \
  -H "Content-Type: application/json" \
  -H "X-Database: default" -H "X-Tenant-ID: main" \
  -d '{"predicate": "Deploy_Prod", "args": ["v2.1"]}'
# Returns: ["Deploy_Prod(v2.1)"]
# Chain: Committed → Built → Tested → Staged, then Staged ∧ ¬Deploy_Freeze → Deploy_Prod
```

### 9.12 Template Scope Support

All templates accept an optional `"scope"` field. When provided, the generated rules are scoped — they only apply within that scope. This is useful for:

- **Hypothetical reasoning**: "What if we applied this rule only in scenario X?"
- **Multi-tenant isolation**: Different tenants can have different rule sets
- **A/B testing logic**: Different rule configurations for different experiment arms

```bash
# Scoped template — only applies within the "hypothesis-1" scope
curl -X POST http://localhost:9300/assert/template \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{
    "type": "MODUS_PONENS",
    "predicates": {"P": "Premium_User", "Q": "Gets_Discount"},
    "args": ["user"],
    "scope": "pricing-experiment"
  }'
```

### 9.13 Templates vs. Manual Rules: When to Use Which

| Use Templates When | Use Manual Rules When |
|---|---|
| The pattern matches a classical form | You need more than 2 body conditions |
| You want self-documenting rule intent | You need complex variable bindings |
| You want consistent, tested rule generation | The rule doesn't fit any template pattern |
| You're building a system quickly | You need predicates with 3+ arguments |

Templates generate the same `Rule` objects as manual `/assert/rule` calls. They're syntactic sugar — there's no performance difference at inference time.

---

## 10. Logic Testing Framework

As knowledge bases grow, you need confidence that adding or changing rules doesn't break existing inferences. NocturnusAI includes a built-in testing framework that lets you define test cases with setup (facts and rules), expectations (what should or shouldn't be provable), and run them in complete isolation from the live database.

### 10.1 Core Concepts

Each test case is a self-contained unit:

| Component | Purpose |
|---|---|
| **Name** | A human-readable label for the test |
| **Setup** | Facts and rules to assert before running expectations |
| **Expectations** | Conditions that must hold for the test to pass |

Test cases are **completely isolated**: each one runs in its own `LogicContext` with a fresh Hexastore and empty rule set. No data from the live database or from other test cases is visible. Tests never modify the live database.

### 10.2 Expectation Types

| Type | What It Checks | Passes When |
|---|---|---|
| **Provable** | The goal has at least one solution | `solve(goal)` returns ≥1 result |
| **NotProvable** | The goal has zero solutions | `solve(goal)` returns 0 results |
| **ResultsExactly** | The goal's results match an expected set | Results match expected atoms (order-independent) |
| **ResultCount** | The goal produces a specific number of results | `solve(goal).count() == count` |

### 10.3 Using the Test API

Send a `POST /test` request with a JSON array of test cases:

```bash
curl -X POST http://localhost:9300/test \
  -H "Content-Type: application/json" \
  -H "X-Database: default" -H "X-Tenant-ID: main" \
  -d '[
    {
      "name": "ancestor transitivity",
      "setup": [
        {"type": "assert_fact", "fact": {"predicate": "Parent", "args": ["alice", "bob"]}},
        {"type": "assert_fact", "fact": {"predicate": "Parent", "args": ["bob", "charlie"]}},
        {"type": "assert_rule", "rule": {
          "head": {"predicate": "Ancestor", "args": ["?x", "?y"]},
          "body": [{"predicate": "Parent", "args": ["?x", "?y"]}]
        }},
        {"type": "assert_rule", "rule": {
          "head": {"predicate": "Ancestor", "args": ["?x", "?z"]},
          "body": [
            {"predicate": "Parent", "args": ["?x", "?y"]},
            {"predicate": "Ancestor", "args": ["?y", "?z"]}
          ]
        }}
      ],
      "expectations": [
        {"type": "provable", "goal": {"predicate": "Ancestor", "args": ["alice", "charlie"]}},
        {"type": "not_provable", "goal": {"predicate": "Ancestor", "args": ["charlie", "alice"]}},
        {"type": "result_count", "goal": {"predicate": "Ancestor", "args": ["alice", "?who"]}, "count": 2}
      ]
    }
  ]'
```

See [Section 6.8](#68-run-logic-tests--post-test) for the full API reference and response format.

### 10.4 Using Tests via the DSL

The `TEST` command lets you write tests directly in the LogiQL DSL:

```
TEST "basic parent lookup" {
    GIVEN {
        ASSERT Parent(alice, bob);
        ASSERT Parent(alice, charlie);
    }
    EXPECT {
        PROVABLE Parent(alice, ?child);
        NOT_PROVABLE Parent(bob, ?child);
        COUNT Parent(alice, ?child) 2;
    }
};
```

**Syntax breakdown**:
- `TEST "name"` — begins a test case with a quoted name
- `GIVEN { ... }` — setup block containing `ASSERT` statements for facts and rules
- `EXPECT { ... }` — expectation block containing `PROVABLE`, `NOT_PROVABLE`, or `COUNT` expectations
- Each expectation ends with `;`
- The test block ends with `};`

The `GIVEN` block is optional — if omitted, the test runs against an empty knowledge base (useful for testing that nothing is provable by default).

### 10.5 Test Results

Each test returns a structured result:

```json
{
  "name": "basic parent lookup",
  "passed": true,
  "expectationResults": [
    {
      "passed": true,
      "message": "Goal Parent(alice, ?child) is provable (2 solution(s))",
      "actual": [
        {"predicate": "Parent", "args": ["alice", "bob"], ...},
        {"predicate": "Parent", "args": ["alice", "charlie"], ...}
      ],
      "proof": { ... }
    },
    {
      "passed": true,
      "message": "Goal Parent(bob, ?child) is correctly not provable",
      "actual": [],
      "proof": null
    },
    {
      "passed": true,
      "message": "Result count matches: 2",
      "actual": [...],
      "proof": null
    }
  ],
  "durationMs": 1
}
```

**Key details**:
- `proof` is included for `Provable` expectations (showing the first proof) and for failed `NotProvable` expectations (showing why it was unexpectedly provable — useful for debugging).
- `actual` always contains the actual results found, regardless of pass/fail status.
- `durationMs` measures the execution time of the individual test case.

### 10.6 Test Suites

When you send multiple test cases, the response aggregates results:

```json
{
  "total": 5,
  "passed": 4,
  "failed": 1,
  "results": [ ... ],
  "durationMs": 12
}
```

### 10.7 Best Practices

1. **Test one concept per test case**: Keep tests focused on a single rule or behavior.
2. **Use descriptive names**: `"ancestor transitivity"` is better than `"test 1"`.
3. **Test negative cases**: Use `NotProvable` to verify that rules don't over-generate.
4. **Use `ResultCount` for regression detection**: If a rule should produce exactly N results, a count check catches both missing and spurious results.
5. **Test edge cases**: Empty argument lists, self-referential queries, deeply nested rules.
6. **Run tests in CI**: The `/test` endpoint returns structured JSON suitable for CI integration — check `failed == 0`.

---

## 11. Multi-Tenancy & Scoping: Isolated Worlds

NocturnusAI provides two orthogonal isolation mechanisms that serve different purposes.

### 11.1 Databases: Application-Level Isolation

Databases are the top-level isolation boundary. Each database:
- Has its own storage directory on disk
- Has its own set of tenants
- Has its own WAL and snapshots
- Is completely independent of other databases

**Use cases**: Separating different applications, environments (dev/staging/prod), or completely unrelated knowledge domains.

### 11.2 Tenants: User/Session-Level Isolation

Within a database, tenants provide a second layer of isolation. Each tenant:
- Has its own LogicContext (Hexastore, rules, inference engines)
- Cannot see or affect other tenants' data
- Is identified by the `X-Tenant-ID` header

**Use cases**: Isolating different users, sessions, simulation runs, or game instances that share the same application but need independent state.

### 11.3 Scopes: Hypothetical Reasoning Within a Tenant

Within a single tenant, scopes provide a third layer of partitioning. Unlike databases and tenants (which are completely isolated), scopes are a **filtering mechanism** on facts and rules.

**Use cases**:
- **Hypothetical reasoning**: "What if we hire Alice? Assert facts in scope='scenario_hire_alice'. Query that scope to see consequences, without affecting the base state."
- **Versioning**: "Document version 1 has these facts in scope='v1'. Version 2 has updated facts in scope='v2'. Both coexist."
- **Time-based snapshots**: "Turn 1 game state in scope='turn_1'. Turn 2 in scope='turn_2'."

```bash
# Assert facts in different scopes
curl -X POST http://localhost:9300/assert/fact \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{"predicate": "Budget", "args": ["engineering", "100000"], "scope": "scenario_A"}'

curl -X POST http://localhost:9300/assert/fact \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{"predicate": "Budget", "args": ["engineering", "200000"], "scope": "scenario_B"}'

# Query only scenario A
curl -X POST http://localhost:9300/infer \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{"predicate": "Budget", "args": ["?dept", "?amount"], "scope": "scenario_A"}'
# Returns: ["Budget(engineering, 100000)"]
```

### 11.4 The Frame Problem

In AI, the **Frame Problem** is the difficulty of knowing what doesn't change when an action occurs. NocturnusAI's scoping mechanism provides a practical solution:

1. Create a new scope for each "turn" or "state change."
2. Copy forward only the facts that persist.
3. Add new facts for the current state.
4. The old scope is still there for comparison or rollback.

This avoids the need to explicitly state "everything else stays the same" (which is what the Frame Problem is really about).

---

## 12. Transactions: ACID Guarantees

NocturnusAI supports ACID transactions for atomic multi-operation changes.

### 12.1 Why Transactions?

Without transactions, each API call is independent. If you need to assert 5 facts that should either all succeed or all fail (e.g., transferring money between accounts), a failure on fact #3 would leave facts #1 and #2 committed and facts #4 and #5 missing. Transactions solve this.

### 12.2 Transaction Lifecycle

```
1. POST /tx/begin                              → Returns txId (e.g., "1001")
2. POST /assert/fact  (X-Transaction-ID: 1001) → "Fact Buffered in Tx 1001: ..."
3. POST /assert/fact  (X-Transaction-ID: 1001) → "Fact Buffered in Tx 1001: ..."
4. POST /assert/rule  (X-Transaction-ID: 1001) → "Rule Buffered in Tx 1001: ..."
5. POST /retract      (X-Transaction-ID: 1001) → "Retraction Buffered in Tx 1001: ..."
6. POST /tx/commit/1001                        → "Committed 1001" (all applied atomically)
   OR
6. POST /tx/rollback/1001                      → "Rolled back 1001" (nothing applied)
```

### 12.3 What Happens on Commit

1. **Validation phase**: All buffered operations are checked for contradictions and constraint violations. If any operation would cause a contradiction, the entire transaction fails (409 Conflict).
2. **WAL write**: All operations are written to the WAL as a single batch entry.
3. **Apply phase**: All operations are applied to the store in order.

If validation fails, nothing is written to the WAL or applied to the store. This is all-or-nothing semantics.

### 12.4 Transaction Limits

- **Maximum concurrent transactions**: 100 per database. Exceeding this returns 429 Too Many Requests.
- **Transaction timeout**: 5 minutes. The server runs a background reaper every 60 seconds that automatically rolls back stale transactions.

### 12.5 Complete Transaction Example

```bash
# Begin transaction
TX_ID=$(curl -s -X POST http://localhost:9300/tx/begin \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main")
echo "Transaction ID: $TX_ID"

# Buffer operations
curl -X POST http://localhost:9300/assert/fact \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -H "X-Transaction-ID: $TX_ID" \
  -d '{"predicate": "Balance", "args": ["alice", "950"]}'

curl -X POST http://localhost:9300/assert/fact \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -H "X-Transaction-ID: $TX_ID" \
  -d '{"predicate": "Balance", "args": ["bob", "1050"]}'

curl -X POST http://localhost:9300/retract \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -H "X-Transaction-ID: $TX_ID" \
  -d '{"predicate": "Balance", "args": ["alice", "1000"]}'

curl -X POST http://localhost:9300/retract \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -H "X-Transaction-ID: $TX_ID" \
  -d '{"predicate": "Balance", "args": ["bob", "1000"]}'

# Commit atomically
curl -X POST http://localhost:9300/tx/commit/$TX_ID \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main"
```

---

## 13. Persistence & Durability

NocturnusAI is an in-memory database. All facts, rules, and inference state live in RAM for fast access. But it provides durability through two complementary mechanisms.

### 13.1 Write-Ahead Log (WAL)

Every mutation (assert, retract, batch) is appended to a WAL file **before** being applied to the in-memory store. This means:

- If the server crashes after writing to the WAL but before updating memory, the operation will be replayed on restart.
- If the server crashes before writing to the WAL, the operation is lost (but it was never applied either, so consistency is preserved).

**WAL entry format** (JSON, one per line):
```json
{
  "id": 1,
  "op": "ASSERT",
  "data": {"type": "FactData", "atom": {"predicate": "Parent", "args": [...], "truthVal": true}},
  "timestamp": 1707300000000,
  "tenantId": "main",
  "checksum": 1234567890
}
```

**Checksum**: CRC32 computed over (id, op, data, timestamp, tenantId). On replay, entries with invalid checksums are skipped (corruption detection).

**Encryption**: If `ENCRYPTION_KEY` is set, each WAL line is encrypted with AES-256-GCM before being written to disk, and decrypted on replay.

### 13.2 Snapshots

Snapshots are periodic full-state dumps to JSON. They serve two purposes:
1. **Faster recovery**: Instead of replaying the entire WAL from the beginning (which could be millions of entries), load the latest snapshot and only replay WAL entries after the snapshot.
2. **WAL compaction**: After a snapshot is written, the WAL is cleared.

Snapshots are taken:
- Automatically every 5 minutes (background coroutine)
- On server shutdown
- When explicitly requested via `POST /admin/backups`

**Snapshot format**:
```json
{
  "timestamp": 1707300000000,
  "tenants": {
    "main": {
      "positives": [/* all positive atoms */],
      "negatives": [/* all negative atoms */]
    },
    "customer_1": {
      "positives": [...],
      "negatives": [...]
    }
  }
}
```

### 13.3 Recovery Sequence on Startup

1. Load tenant registry from `tenants.json`
2. Load latest snapshot from `snapshot.json` → Restore all facts to in-memory stores
3. Replay WAL entries → Apply any operations that occurred after the snapshot
4. Start periodic snapshot coroutine
5. Register shutdown hook

### 13.4 Why In-Memory?

**Performance**: Hash map lookups are O(1). The 6-way Hexastore index means any query pattern hits a direct lookup. There is no disk I/O during queries or inference.

**Trade-off**: Memory is the limiting factor. The database size is bounded by available RAM. For most rule-based reasoning use cases (millions of facts), this is well within the capacity of modern servers (64GB+ RAM). For datasets approaching hundreds of millions of facts, you would need to consider sharding across multiple instances.

---

## 14. Replication: Leader-Follower Architecture

NocturnusAI supports a simple leader-follower replication model for read scaling and availability.

### 14.1 Leader Mode (Default)

The leader accepts all writes and exposes its WAL via the `/replication/wal` endpoint:

```bash
REPLICATION_MODE=LEADER ./gradlew :nocturnusai-server:run
```

Followers can poll the leader's WAL:
```bash
# Get all WAL entries since ID 0
curl "http://leader:9300/replication/wal?since=0"
```

Response (newline-delimited JSON):
```
{"id":1,"op":"ASSERT","data":{"type":"FactData","atom":{...}},"timestamp":...,"tenantId":"main","checksum":...}
{"id":2,"op":"ASSERT","data":{"type":"FactData","atom":{...}},"timestamp":...,"tenantId":"main","checksum":...}
```

### 14.2 Follower Mode

A follower automatically polls the leader every second and applies WAL entries locally:

```bash
REPLICATION_MODE=FOLLOWER \
LEADER_URL=http://leader-host:9300 \
./gradlew :nocturnusai-server:run
```

The follower:
- Reads from the same API endpoints as any other client (for read queries)
- Does NOT accept writes (to maintain consistency)
- Continuously pulls new WAL entries from the leader and applies them

### 14.3 Limitations

- This is eventual consistency, not strong consistency. There is a lag between the leader accepting a write and the follower seeing it (up to 1 second by default).
- There is no automatic failover. If the leader goes down, a follower must be manually promoted.
- The follower replicates the "default" database only (in the current implementation).

---

## 15. Observability: Health, Metrics, and Monitoring

### 15.1 Health Checks

**Full health check** — `GET /health`:

```json
{
  "status": "healthy",
  "checks": {
    "wal_writable": {"status": "pass", "message": "WAL directory is writable"},
    "disk_space": {"status": "pass", "message": "Disk usage: 45%"},
    "memory": {"status": "pass", "message": "Memory usage: 62%"},
    "databases": {"status": "pass", "message": "2 database(s) loaded"},
    "transactions": {"status": "pass", "message": "3 active transaction(s)"}
  }
}
```

**Status levels**:
- `healthy` (HTTP 200): All checks pass
- `degraded` (HTTP 200): Some checks warn but none fail
- `unhealthy` (HTTP 503): At least one check fails

**Individual checks**:

| Check | Warning Threshold | Failure Threshold |
|---|---|---|
| WAL writable | — | Storage directory not writable |
| Disk space | >85% used | >95% used |
| Memory (JVM) | >90% used | — |
| Databases | — | — |
| Transactions | >50 active | — |

**Lightweight liveness** — `GET /health/live`: Always returns `200 OK` with body `OK`. Use for Kubernetes liveness probes.

**Readiness** — `GET /health/ready`: Same as `/health`. Use for Kubernetes readiness probes.

### 15.2 Prometheus Metrics

`GET /metrics` returns Prometheus-compatible metrics:

```
# HELP jvm_memory_used_bytes The amount of used memory
# TYPE jvm_memory_used_bytes gauge
jvm_memory_used_bytes{area="heap",id="G1 Eden Space"} 1.2345678E7
...
```

Powered by Micrometer with Prometheus registry. Includes JVM metrics (heap, GC, threads), Ktor request metrics, and custom application metrics.

### 15.3 Request Logging

Every request is logged at INFO level with the pattern:
```
[$requestId] POST /assert/fact -> 200
```

This is powered by Ktor's CallLogging plugin with SLF4J MDC integration.

### 15.4 Auto-Generated API Documentation

`GET /llm.txt` returns a dynamically generated Markdown document describing all endpoints, data schemas, and request formats. This endpoint uses Kotlin reflection to discover serializable data classes and Ktor routing tree introspection to document endpoints.

---

## 16. Deployment Guide

### 16.1 Local Development

```bash
# Option 1: Run both server and web console
./run_local_dev.sh
# Server on http://localhost:9300, Web on http://localhost:9350

# Option 2: Run server only
./gradlew :nocturnusai-server:run

# Option 3: Run web console only (for frontend development)
cd nocturnusai-web && npm run dev
```

### 16.2 Docker

```bash
# Build and run the full stack
docker-compose up --build

# Server: http://localhost:9300
# Web:    http://localhost:9400
```

**docker-compose.yml** defines:
- `logic-server`: Kotlin backend (port 9300), persistent volume at `./data:/data`
- `logic-web`: React frontend (port 9400), depends on server health check
- `logic-net`: Bridge network for inter-container DNS

### 16.3 Docker with Security

```yaml
# docker-compose.override.yml
version: '3.8'
services:
  logic-server:
    environment:
      - API_KEY=your-long-random-api-key
      - ENCRYPTION_KEY=your-64-hex-char-key
```

### 16.4 Production Considerations

| Aspect | Recommendation |
|---|---|
| **Reverse proxy** | Place Nginx/Caddy in front for TLS termination and rate limiting |
| **API URL** | Override `VITE_API_URL` at build time for the frontend |
| **Secrets** | Use Docker secrets or a secrets manager for API_KEY and ENCRYPTION_KEY |
| **Monitoring** | Scrape `/metrics` with Prometheus, alert on `/health` degraded status |
| **Backups** | Schedule periodic `POST /admin/backups` calls |
| **Memory** | Monitor JVM heap usage; size `-Xmx` based on expected dataset size |
| **Disk** | Monitor disk space for WAL growth between snapshots |

---

## 17. The DSL: Writing Logic Programs

NocturnusAI includes a native DSL (Domain-Specific Language) called LogiQL that can be used via the `/execute` endpoint or the interactive REPL.

### 17.1 Syntax Overview

```
# Comments start with # or --

# Assert a fact
ASSERT predicate(arg1, arg2, ...);

# Assert a rule
ASSERT FORALL ?var1, ?var2 {
    head(?var1) <- body1(?var1, ?var2) AND body2(?var2)
};

# Query
INFER predicate(?x, constant);

# Query with proof trace
INFER predicate(?x) WITH PROOF;

# Define a constraint (forbidden pattern)
RESTRICT ?x { condition1(?x) AND condition2(?x) } -> CONTRADICTION;

# Explain how a fact was derived
EXPLAIN predicate(arg1, arg2);

# Define a test case
TEST "test name" {
    GIVEN {
        ASSERT fact(arg1, arg2);
        ASSERT FORALL ?x { head(?x) <- body(?x) };
    }
    EXPECT {
        PROVABLE goal(?x);
        NOT_PROVABLE negative_goal(?x);
        COUNT goal(?x) 3;
    }
};
```

### 17.2 Token Types

| Token | Meaning |
|---|---|
| `ASSERT` | Begin an assertion (fact or rule) |
| `INFER` | Begin a query |
| `RESTRICT` | Define a consistency constraint |
| `EXPLAIN` | Request derivation explanation |
| `FORALL` | Universal quantification for rules |
| `WITH PROOF` | Include proof trace in query results |
| `AND` | Conjunction in rule bodies |
| `NOT` | Explicit negation |
| `<-` | Rule implication arrow |
| `->` | Constraint violation arrow |
| `CONTRADICTION` | Marks a forbidden state |
| `TEST` | Begin a test case definition |
| `GIVEN` | Begin the setup block of a test case |
| `EXPECT` | Begin the expectations block of a test case |
| `PROVABLE` | Expectation: the goal must have at least one solution |
| `NOT_PROVABLE` | Expectation: the goal must have zero solutions |
| `COUNT` | Expectation: the goal must produce exactly N results |
| `(`, `)` | Argument delimiters |
| `{`, `}` | Rule/constraint body delimiters |
| `,` | Argument separator |
| `;` | Statement terminator |

### 17.3 Examples

```
-- Family relationships
ASSERT Parent(alice, bob);
ASSERT Parent(bob, dave);
ASSERT Parent(bob, eve);

-- Grandparent rule
ASSERT FORALL ?x, ?y, ?z {
    Grandparent(?x, ?z) <- Parent(?x, ?y) AND Parent(?y, ?z)
};

-- Ancestor rule (recursive)
ASSERT FORALL ?x, ?y {
    Ancestor(?x, ?y) <- Parent(?x, ?y)
};
ASSERT FORALL ?x, ?y, ?z {
    Ancestor(?x, ?z) <- Parent(?x, ?y) AND Ancestor(?y, ?z)
};

-- Query
INFER Grandparent(?who, dave);
-- Result: Grandparent(alice, dave)

INFER Ancestor(alice, ?descendant);
-- Results: Ancestor(alice, bob), Ancestor(alice, dave), Ancestor(alice, eve)

-- Constraint: nobody can be their own parent
RESTRICT ?x { Parent(?x, ?x) } -> CONTRADICTION;

-- Explanation
EXPLAIN Grandparent(alice, dave);
-- Shows: Derived via rule [Grandparent(?x, ?z) :- Parent(?x, ?y), Parent(?y, ?z)]
--         with premises [Parent(alice, bob), Parent(bob, dave)]

-- Test: verify grandparent inference
TEST "grandparent rule" {
    GIVEN {
        ASSERT Parent(alice, bob);
        ASSERT Parent(bob, dave);
        ASSERT FORALL ?x, ?y, ?z {
            Grandparent(?x, ?z) <- Parent(?x, ?y) AND Parent(?y, ?z)
        };
    }
    EXPECT {
        PROVABLE Grandparent(alice, dave);
        NOT_PROVABLE Grandparent(dave, alice);
        COUNT Grandparent(alice, ?z) 1;
    }
};
-- Output: TEST "grandparent rule": PASSED (3/3 expectations)
```

---

## 18. Advanced Patterns & Recipes

### 18.1 Access Control (RBAC)

```bash
# Define roles
curl -X POST http://localhost:9300/assert/fact \
  -H "Content-Type: application/json" -H "X-Database: default" -H "X-Tenant-ID: main" \
  -d '{"predicate": "Has_Role", "args": ["alice", "admin"]}'

curl -X POST http://localhost:9300/assert/fact \
  -H "Content-Type: application/json" -H "X-Database: default" -H "X-Tenant-ID: main" \
  -d '{"predicate": "Has_Role", "args": ["bob", "viewer"]}'

# Define resource classifications
curl -X POST http://localhost:9300/assert/fact \
  -H "Content-Type: application/json" -H "X-Database: default" -H "X-Tenant-ID: main" \
  -d '{"predicate": "Resource_Class", "args": ["payroll_db", "sensitive"]}'

curl -X POST http://localhost:9300/assert/fact \
  -H "Content-Type: application/json" -H "X-Database: default" -H "X-Tenant-ID: main" \
  -d '{"predicate": "Resource_Class", "args": ["blog", "public"]}'

# Rule: Admins can access sensitive resources
curl -X POST http://localhost:9300/assert/rule \
  -H "Content-Type: application/json" -H "X-Database: default" -H "X-Tenant-ID: main" \
  -d '{
    "head": {"predicate": "Can_Access", "args": ["?user", "?resource"]},
    "body": [
      {"predicate": "Has_Role", "args": ["?user", "admin"]},
      {"predicate": "Resource_Class", "args": ["?resource", "sensitive"]}
    ]
  }'

# Rule: Everyone can access public resources
curl -X POST http://localhost:9300/assert/rule \
  -H "Content-Type: application/json" -H "X-Database: default" -H "X-Tenant-ID: main" \
  -d '{
    "head": {"predicate": "Can_Access", "args": ["?user", "?resource"]},
    "body": [
      {"predicate": "Resource_Class", "args": ["?resource", "public"]}
    ]
  }'

# Query: What can alice access?
curl -X POST http://localhost:9300/infer \
  -H "Content-Type: application/json" -H "X-Database: default" -H "X-Tenant-ID: main" \
  -d '{"predicate": "Can_Access", "args": ["alice", "?resource"]}'
# Returns: ["Can_Access(alice, payroll_db)", "Can_Access(alice, blog)"]

# Query: Can bob access payroll?
curl -X POST http://localhost:9300/infer \
  -H "Content-Type: application/json" -H "X-Database: default" -H "X-Tenant-ID: main" \
  -d '{"predicate": "Can_Access", "args": ["bob", "payroll_db"]}'
# Returns: [] (empty - bob is only a viewer, not admin)
```

### 18.2 Transitive Closure (Graph Reachability)

```bash
# Define edges in a network
curl -X POST http://localhost:9300/assert/fact \
  -H "Content-Type: application/json" -H "X-Database: default" -H "X-Tenant-ID: main" \
  -d '{"predicate": "Link", "args": ["A", "B"]}'

curl -X POST http://localhost:9300/assert/fact \
  -H "Content-Type: application/json" -H "X-Database: default" -H "X-Tenant-ID: main" \
  -d '{"predicate": "Link", "args": ["B", "C"]}'

curl -X POST http://localhost:9300/assert/fact \
  -H "Content-Type: application/json" -H "X-Database: default" -H "X-Tenant-ID: main" \
  -d '{"predicate": "Link", "args": ["C", "D"]}'

# Recursive reachability rule
curl -X POST http://localhost:9300/assert/rule \
  -H "Content-Type: application/json" -H "X-Database: default" -H "X-Tenant-ID: main" \
  -d '{
    "head": {"predicate": "Reachable", "args": ["?a", "?b"]},
    "body": [{"predicate": "Link", "args": ["?a", "?b"]}]
  }'

curl -X POST http://localhost:9300/assert/rule \
  -H "Content-Type: application/json" -H "X-Database: default" -H "X-Tenant-ID: main" \
  -d '{
    "head": {"predicate": "Reachable", "args": ["?a", "?c"]},
    "body": [
      {"predicate": "Link", "args": ["?a", "?b"]},
      {"predicate": "Reachable", "args": ["?b", "?c"]}
    ]
  }'

# Query: What can A reach?
curl -X POST http://localhost:9300/infer \
  -H "Content-Type: application/json" -H "X-Database: default" -H "X-Tenant-ID: main" \
  -d '{"predicate": "Reachable", "args": ["A", "?destination"]}'
# Returns: ["Reachable(A, B)", "Reachable(A, C)", "Reachable(A, D)"]
```

### 18.3 Diagnostic Reasoning (Troubleshooting)

```bash
# Symptoms and diagnoses
curl -X POST http://localhost:9300/assert/rule \
  -H "Content-Type: application/json" -H "X-Database: default" -H "X-Tenant-ID: main" \
  -d '{
    "head": {"predicate": "Diagnosis", "args": ["?system", "memory_leak"]},
    "body": [
      {"predicate": "Symptom", "args": ["?system", "high_memory"]},
      {"predicate": "Symptom", "args": ["?system", "slow_gc"]}
    ]
  }'

curl -X POST http://localhost:9300/assert/rule \
  -H "Content-Type: application/json" -H "X-Database: default" -H "X-Tenant-ID: main" \
  -d '{
    "head": {"predicate": "Diagnosis", "args": ["?system", "disk_full"]},
    "body": [
      {"predicate": "Symptom", "args": ["?system", "write_errors"]},
      {"predicate": "Symptom", "args": ["?system", "low_disk"]}
    ]
  }'

# Report symptoms
curl -X POST http://localhost:9300/assert/fact \
  -H "Content-Type: application/json" -H "X-Database: default" -H "X-Tenant-ID: main" \
  -d '{"predicate": "Symptom", "args": ["web_server", "high_memory"]}'

curl -X POST http://localhost:9300/assert/fact \
  -H "Content-Type: application/json" -H "X-Database: default" -H "X-Tenant-ID: main" \
  -d '{"predicate": "Symptom", "args": ["web_server", "slow_gc"]}'

# Ask for diagnosis
curl -X POST http://localhost:9300/infer \
  -H "Content-Type: application/json" -H "X-Database: default" -H "X-Tenant-ID: main" \
  -d '{"predicate": "Diagnosis", "args": ["web_server", "?problem"]}'
# Returns: ["Diagnosis(web_server, memory_leak)"]
```

### 18.4 Hypothetical Reasoning with Scopes

```bash
# Base facts (no scope)
curl -X POST http://localhost:9300/assert/fact \
  -H "Content-Type: application/json" -H "X-Database: default" -H "X-Tenant-ID: main" \
  -d '{"predicate": "Employee", "args": ["alice", "engineering"]}'

# Scenario A: What if we promote alice?
curl -X POST http://localhost:9300/assert/fact \
  -H "Content-Type: application/json" -H "X-Database: default" -H "X-Tenant-ID: main" \
  -d '{"predicate": "Role", "args": ["alice", "manager"], "scope": "scenario_promote"}'

# Scenario B: What if alice leaves?
curl -X POST http://localhost:9300/assert/fact \
  -H "Content-Type: application/json" -H "X-Database: default" -H "X-Tenant-ID: main" \
  -d '{"predicate": "Status", "args": ["alice", "departed"], "scope": "scenario_depart"}'

# Query each scenario independently
# Scenario A:
curl -X POST http://localhost:9300/infer \
  -H "Content-Type: application/json" -H "X-Database: default" -H "X-Tenant-ID: main" \
  -d '{"predicate": "Role", "args": ["alice", "?role"], "scope": "scenario_promote"}'

# Scenario B:
curl -X POST http://localhost:9300/infer \
  -H "Content-Type: application/json" -H "X-Database: default" -H "X-Tenant-ID: main" \
  -d '{"predicate": "Status", "args": ["alice", "?status"], "scope": "scenario_depart"}'
```

### 18.5 AI Agent Integration Pattern

This is the primary intended use case—an AI agent (like Claude, GPT, etc.) using NocturnusAI as its reasoning backend.

```python
# Pseudocode for an AI agent using NocturnusAI

import requests

BASE = "http://localhost:9300"
HEADERS = {
    "Content-Type": "application/json",
    "X-API-Key": "agent-secret-key",
    "X-Database": "agent_workspace",
    "X-Tenant-ID": "session_12345"
}

# Step 1: Agent learns facts from user conversation
def assert_fact(predicate, args, scope=None):
    body = {"predicate": predicate, "args": args}
    if scope:
        body["scope"] = scope
    requests.post(f"{BASE}/assert/fact", json=body, headers=HEADERS)

# Step 2: Agent defines reasoning rules
def assert_rule(head, body):
    requests.post(f"{BASE}/assert/rule", json={
        "head": head,
        "body": body
    }, headers=HEADERS)

# Step 3: Agent queries the engine instead of reasoning in context
def query(predicate, args, scope=None):
    body = {"predicate": predicate, "args": args}
    if scope:
        body["scope"] = scope
    resp = requests.post(f"{BASE}/infer", json=body, headers=HEADERS)
    return resp.json()

# Usage in conversation:
# User: "Alice manages the engineering team. Bob reports to Alice."
assert_fact("Manages", ["alice", "engineering"])
assert_fact("Reports_To", ["bob", "alice"])

# User: "Who does bob report to?"
# Instead of trying to remember from context, query the engine:
results = query("Reports_To", ["bob", "?manager"])
# Returns: ["Reports_To(bob, alice)"]

# Complex reasoning:
# User: "If someone manages a team and a person reports to them,
#        that person is on the team."
assert_rule(
    head={"predicate": "On_Team", "args": ["?person", "?team"]},
    body=[
        {"predicate": "Reports_To", "args": ["?person", "?manager"]},
        {"predicate": "Manages", "args": ["?manager", "?team"]}
    ]
)

# User: "What team is bob on?"
results = query("On_Team", ["bob", "?team"])
# Returns: ["On_Team(bob, engineering)"]
# The engine derived this; the agent didn't have to reason about it.
```

---

## 19. Limitations & Design Trade-Offs

Understanding the limitations helps you work effectively with the system.

### 17.1 In-Memory Storage

**Trade-off**: All data lives in memory (RAM) for maximum query speed (O(1) lookups). Durability is provided by WAL and snapshots on disk.

**Implication**: Your total data size is bounded by available RAM. For most logic/rule workloads (millions of facts), this is fine. For datasets exceeding server RAM, you would need sharding.

### 17.2 Single-Justification Truth Maintenance

**Trade-off**: Each derived fact records only ONE derivation path (one rule + one set of premises). A full Truth Maintenance System (TMS) would support multiple justifications.

**Implication**: If a derived fact can be proven through multiple independent paths (different rules or different premise combinations), only the first derivation is recorded. Retracting a premise used in that derivation will retract the derived fact, even if another valid derivation path still exists.

### 17.3 Explicit Negation Only

**Trade-off**: NocturnusAI uses explicit negation (`NOT P` must be positively asserted), not "negation as failure" (where the absence of `P` is treated as `NOT P`).

**Implication**: For rules that check "if NOT suspended," you must explicitly assert `NOT Is_Suspended(user)` for each user. The mere absence of `Is_Suspended(user)` is not enough.

### 17.4 No Tabling/Memoization in Backward Chaining

**Trade-off**: The backward chainer does not cache intermediate results (no tabling).

**Implication**: The same sub-goal may be re-computed many times in a complex query. For deeply recursive or highly branching rule sets, this can be expensive. The depth limit of 100 mitigates infinite recursion but doesn't help with redundant computation.

### 17.5 Non-Binary Atoms Are Slower

**Trade-off**: The Hexastore's 6-way indexing only applies to binary predicates (exactly 2 arguments). Non-binary atoms fall back to a predicate-indexed linear scan.

**Implication**: Queries involving predicates with 0, 1, 3, or more arguments are slower than binary predicate queries. If performance matters, consider decomposing N-ary predicates into binary ones where possible.

### 17.6 Eventual Consistency in Replication

**Trade-off**: Followers poll the leader's WAL every 1 second.

**Implication**: There is a lag of up to 1 second between a write on the leader and its visibility on followers. There is no automatic failover.

### 17.7 No Built-In Aggregation

**Trade-off**: NocturnusAI does not support aggregation (COUNT, SUM, AVG, etc.) in queries.

**Implication**: You cannot ask "How many admins are there?" directly. You must retrieve all matching results and count them client-side.

---

## 20. Metadata: Optional Annotations

Atoms support an optional `metadata` field: a key-value map where keys are strings and values are arbitrary JSON (primitives, objects, arrays). Metadata lets you annotate facts with information that is useful for your application but is **not part of the logical identity** of the atom.

### 20.1 What Metadata Is (and Isn't)

Metadata is a pass-through annotation layer. It does **not** affect:

- **Logical identity**: Two atoms with the same predicate, args, truthVal, source, and scope are considered equal regardless of their metadata. This means `equals()` and `hashCode()` ignore metadata.
- **Inference**: Rules and backward chaining operate on predicates, args, and truth values. Metadata is carried along but not matched against.
- **Retraction**: When you retract a fact, you only need to specify predicate + args + truthVal + scope. Metadata is not needed for matching.

Metadata **is** preserved through:

- **WAL and snapshots**: Metadata persists across server restarts.
- **Inference results**: When a query returns facts that have metadata, the metadata is included in the response.
- **Rule derivation**: Metadata on the head atom template of a rule is carried to derived facts (typically empty).

### 20.2 Asserting Facts with Metadata

```bash
curl -X POST http://localhost:9300/assert/fact \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: main" \
  -d '{
    "predicate": "temperature",
    "args": ["sensor-42", "23.5"],
    "metadata": {
      "unit": "celsius",
      "confidence": 0.95,
      "source": {"type": "sensor", "model": "DHT22"},
      "tags": ["environment", "lab-3"]
    }
  }'
```

Response: `Fact Asserted: temperature(sensor-42, 23.5)`

### 20.3 Querying Facts with Metadata

When you query via `/infer` or list facts via `/admin/databases/{name}/facts`, metadata is included in the structured response:

```json
[{
  "predicate": "temperature",
  "args": ["sensor-42", "23.5"],
  "negated": false,
  "scope": null,
  "metadata": {
    "unit": "celsius",
    "confidence": 0.95,
    "source": {"type": "sensor", "model": "DHT22"},
    "tags": ["environment", "lab-3"]
  }
}]
```

### 20.4 Upsert Semantics

Re-asserting the same fact (same predicate + args + truthVal + scope) with different metadata **replaces** the metadata. The old metadata is discarded and the new metadata takes its place.

```bash
# First assertion
curl -X POST http://localhost:9300/assert/fact \
  -H "Content-Type: application/json" \
  -H "X-Database: default" -H "X-Tenant-ID: main" \
  -d '{"predicate": "temperature", "args": ["sensor-42", "23.5"], "metadata": {"unit": "celsius"}}'

# Second assertion — same fact, different metadata (replaces the first)
curl -X POST http://localhost:9300/assert/fact \
  -H "Content-Type: application/json" \
  -H "X-Database: default" -H "X-Tenant-ID: main" \
  -d '{"predicate": "temperature", "args": ["sensor-42", "23.5"], "metadata": {"unit": "fahrenheit"}}'
```

After the second assertion, the fact has `{"unit": "fahrenheit"}` — the original `{"unit": "celsius"}` is gone.

### 20.5 Retraction (Metadata Ignored)

To retract a fact, you do **not** need to specify its metadata. The match is based on logical identity only:

```bash
curl -X POST http://localhost:9300/retract \
  -H "Content-Type: application/json" \
  -H "X-Database: default" -H "X-Tenant-ID: main" \
  -d '{"predicate": "temperature", "args": ["sensor-42", "23.5"]}'
```

### 20.6 Metadata on Rules

You can attach metadata to the head and body atoms of a rule via the `metadata` field on `AtomDto`:

```bash
curl -X POST http://localhost:9300/assert/rule \
  -H "Content-Type: application/json" \
  -H "X-Database: default" -H "X-Tenant-ID: main" \
  -d '{
    "head": {
      "predicate": "high_temp",
      "args": ["?sensor"],
      "metadata": {"severity": "warning"}
    },
    "body": [
      {"predicate": "temperature", "args": ["?sensor", "?val"]}
    ]
  }'
```

### 20.7 Validation Limits

Metadata is validated on assertion to prevent abuse:

| Constraint | Limit |
|---|---|
| Maximum number of keys | 32 |
| Maximum key length | 128 characters |
| Maximum value size (per key) | 8 KB |
| Maximum total metadata size | 32 KB |

Exceeding any limit returns `400 VALIDATION_ERROR`.

### 20.8 Use Cases

- **Provenance tracking**: `{"source": "sensor-42", "timestamp": "2025-02-07T12:00:00Z"}`
- **Confidence scores**: `{"confidence": 0.95, "model": "v2.1"}`
- **Audit trails**: `{"assertedBy": "user-123", "reason": "manual correction"}`
- **Domain annotations**: `{"unit": "celsius", "precision": 0.1}`
- **Tagging and categorization**: `{"tags": ["critical", "production"]}`

---

## 21. Glossary

| Term | Definition |
|---|---|
| **Atom** | A single assertion of truth: predicate + arguments + truth value. The fundamental unit of knowledge. |
| **Backward Chaining** | Goal-directed inference: start from a query and work backwards through rules to find supporting facts. |
| **Contradiction** | When both P and ¬P (NOT P) are asserted for the same predicate and arguments. Detected and rejected. |
| **Forward Chaining** | Data-driven inference: when a fact is asserted, trigger rules that use it and derive new facts. |
| **Hexastore** | A 6-way indexed triple store that provides O(1) lookups for any access pattern on binary predicates. |
| **Horn Clause** | A rule of the form "Head :- Body1, Body2, ...". The head is true if all body atoms are true. |
| **Identifier** | A named constant (e.g., `alice`, `admin`). Represents a specific entity. |
| **LogicContext** | A per-tenant container holding a Hexastore, rules, and inference engines. |
| **Modus Ponens** | If P→Q and P, then Q. The most fundamental deduction rule. |
| **Modus Tollens** | If P→Q and ¬Q, then ¬P. Reasoning from the absence of a consequence. |
| **Predicate** | The name of a relationship or property (e.g., `Parent`, `Is_Online`). |
| **Proof Tree** | A recursive data structure showing how a query result was derived: which facts matched and which rules were applied at each step. |
| **ProofStep** | One step in a proof tree: either a `FactMatch` (direct fact lookup) or a `RuleApplication` (rule applied with body proofs). |
| **Provenance** | The record of how a derived fact was produced: which rule and which premises. |
| **Rete Engine** | A forward-chaining inference engine that indexes rule conditions for efficient matching. |
| **Rule** | A Horn clause that defines how to derive new knowledge from existing facts. |
| **Scope** | An optional partition key on facts/rules for hypothetical reasoning or versioning. |
| **SLD Resolution** | Selective Linear Definite clause resolution. The algorithm used by Prolog and NocturnusAI's backward chainer. |
| **Standardization Apart** | Renaming variables in a rule to unique names before each use, preventing name conflicts. |
| **Substitution** | A mapping from variables to concrete values that makes a query true. |
| **Term** | The basic data type: Identifier, StringLit, NumberLit, or Variable. |
| **Test Case** | A self-contained unit test for logic: setup (facts/rules) + expectations (provable/not provable/count), run in isolation. |
| **Test Runner** | The engine that executes test cases in isolated `LogicContext` instances and reports results. |
| **Truth Maintenance** | Automatically retracting derived facts when their supporting premises are retracted. |
| **Unification** | The process of finding variable assignments that make two terms identical. |
| **Variable** | A placeholder for unknown values, prefixed with `?` (e.g., `?x`, `?who`). |
| **WAL** | Write-Ahead Log. Every mutation is logged before being applied, for crash recovery. |

---

## Quick Reference Card

### Essential curl Commands

```bash
# Health check
curl http://localhost:9300/health

# Assert a fact
curl -X POST http://localhost:9300/assert/fact \
  -H "Content-Type: application/json" \
  -H "X-Database: default" -H "X-Tenant-ID: main" \
  -d '{"predicate": "P", "args": ["a", "b"]}'

# Assert a rule
curl -X POST http://localhost:9300/assert/rule \
  -H "Content-Type: application/json" \
  -H "X-Database: default" -H "X-Tenant-ID: main" \
  -d '{"head": {"predicate": "Q", "args": ["?x"]}, "body": [{"predicate": "P", "args": ["?x", "b"]}]}'

# Query
curl -X POST http://localhost:9300/infer \
  -H "Content-Type: application/json" \
  -H "X-Database: default" -H "X-Tenant-ID: main" \
  -d '{"predicate": "Q", "args": ["?who"]}'

# Query with proof tree
curl -X POST "http://localhost:9300/infer?proof=true" \
  -H "Content-Type: application/json" \
  -H "X-Database: default" -H "X-Tenant-ID: main" \
  -d '{"predicate": "Q", "args": ["?who"]}'

# Retract
curl -X POST http://localhost:9300/retract \
  -H "Content-Type: application/json" \
  -H "X-Database: default" -H "X-Tenant-ID: main" \
  -d '{"predicate": "P", "args": ["a", "b"]}'

# Run logic tests
curl -X POST http://localhost:9300/test \
  -H "Content-Type: application/json" \
  -H "X-Database: default" -H "X-Tenant-ID: main" \
  -d '[{"name": "test1", "setup": [{"type": "assert_fact", "fact": {"predicate": "P", "args": ["a"]}}], "expectations": [{"type": "provable", "goal": {"predicate": "P", "args": ["?x"]}}]}]'

# List all facts
curl http://localhost:9300/admin/databases/default/facts \
  -H "X-Tenant-ID: main"

# List all rules
curl http://localhost:9300/admin/databases/default/rules \
  -H "X-Tenant-ID: main"
```

### Environment Variables

```bash
PORT=9300               # Server port
HOST=0.0.0.0            # Bind address
API_KEY=                 # Authentication key (empty = no auth)
STORAGE_DIR=./data       # Persistence directory
ENCRYPTION_KEY=          # 64 hex chars for AES-256 at-rest encryption
TLS_ENABLED=false        # Enable HTTPS
TLS_PORT=9443            # HTTPS port
TLS_KEYSTORE_PATH=       # Path to PKCS12 keystore
TLS_KEYSTORE_PASSWORD=   # Keystore password
TLS_KEY_ALIAS=nocturnusai  # Key alias
REPLICATION_MODE=LEADER  # LEADER or FOLLOWER
LEADER_URL=              # Leader URL for follower mode
```

---

*This guide documents NocturnusAI as of its current implementation. For the auto-generated API documentation, visit `GET /llm.txt` on a running server. This guide is also available at `GET /userguide` on a running server.*
