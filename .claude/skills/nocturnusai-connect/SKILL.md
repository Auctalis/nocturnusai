---
name: nocturnusai-connect
description: >
  Use when setting up NocturnusAI connection, configuring MCP server in
  claude_desktop_config.json or .mcp.json, setting up API keys, auth,
  RBAC bootstrap, creating databases/tenants, or troubleshooting connection issues.
  Triggers on: setup, connect, configure, MCP, auth, API key, tenant, database,
  bootstrap, NocturnusAI.
---

# NocturnusAI Connect

## Overview

NocturnusAI is a logic server for Agentic AI that provides deterministic multi-step reasoning, truth maintenance, and agent memory lifecycle management via HTTP API, MCP protocol, and client SDKs (Python, TypeScript). Agents use NocturnusAI as their semantic memory and reasoning backend — storing facts, defining rules, running inference, and managing context windows with temporal awareness and salience scoring. This skill covers all connection paths, authentication modes, and tenant setup.

## Prerequisites

The NocturnusAI server must be running before attempting any connection.

**Start the server** (choose one):

```bash
# Option 1: Gradle (development)
./gradlew :nocturnusai-server:run

# Option 2: Docker
docker-compose up --build

# Option 3: Local dev script
./run_local_dev.sh
```

**Default port**: `9300`
**Default host**: `0.0.0.0`

Verify the server is running:

```bash
curl http://localhost:9300/health
```

## Connection Path 1: MCP Config

NocturnusAI exposes MCP via `POST /mcp` (JSON-RPC 2.0) and `GET /mcp/sse` (SSE streaming transport). Because Claude Desktop cannot inject custom headers like `X-Tenant-ID` natively, use the `mcp-remote` stdio bridge.

### `.mcp.json` (project-level)

```json
{
  "mcpServers": {
    "nocturnusai": {
      "command": "npx",
      "args": [
        "mcp-remote",
        "--header", "X-Tenant-ID:${NOCTURNUSAI_TENANT}",
        "--header", "X-Database:${NOCTURNUSAI_DATABASE}",
        "--header", "X-API-Key:${NOCTURNUSAI_API_KEY}",
        "${NOCTURNUSAI_URL}/mcp/sse"
      ],
      "env": {
        "NOCTURNUSAI_URL": "http://localhost:9300",
        "NOCTURNUSAI_API_KEY": "",
        "NOCTURNUSAI_DATABASE": "default",
        "NOCTURNUSAI_TENANT": "default"
      }
    }
  }
}
```

### `claude_desktop_config.json`

```json
{
  "mcpServers": {
    "nocturnusai": {
      "command": "npx",
      "args": [
        "mcp-remote",
        "--header", "X-Tenant-ID:default",
        "--header", "X-Database:default",
        "http://localhost:9300/mcp/sse"
      ]
    }
  }
}
```

### Environment Variables

| Variable | Default | Purpose |
|----------|---------|---------|
| `NOCTURNUSAI_URL` | `http://localhost:9300` | Server base URL |
| `NOCTURNUSAI_API_KEY` | _(empty)_ | API key for authentication |
| `NOCTURNUSAI_DATABASE` | `default` | Target database name |
| `NOCTURNUSAI_TENANT` | `default` | Tenant ID for data isolation |

### MCP Tools Available

Once connected, these MCP tools are available: `tell`, `ask`, `teach`, `forget`, `inspect`, `context`, `aggregate`, `bulk_assert`, `retract_pattern`, `fork_scope`, `merge_scope`, `list_scopes`, `delete_scope`.

**Note**: MCP routes default `X-Tenant-ID` to `"default"` if not provided. REST routes require it explicitly.

## Connection Path 2: Direct HTTP

### JSON-RPC 2.0 via MCP Endpoint

```bash
# Tell a fact via MCP
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
        "args": ["alice", "bob"]
      }
    }
  }'
```

```bash
# Ask a query via MCP
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
        "args": ["alice", "?who"]
      }
    }
  }'
```

### REST Simplified Endpoints

```bash
# Tell a fact
curl -X POST http://localhost:9300/tell \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: my-tenant" \
  -d '{"predicate": "likes", "args": ["alice", "bob"]}'

# Ask a query
curl -X POST http://localhost:9300/ask \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: my-tenant" \
  -d '{"predicate": "likes", "args": ["alice", "?who"]}'

# Teach a rule
curl -X POST http://localhost:9300/teach \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: my-tenant" \
  -d '{
    "head": {"predicate": "mortal", "args": ["?x"]},
    "body": [{"predicate": "human", "args": ["?x"]}]
  }'

# Forget a fact
curl -X POST http://localhost:9300/forget \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: my-tenant" \
  -d '{"predicate": "likes", "args": ["alice", "bob"]}'
```

### Required Headers

| Header | Required | Default | Purpose |
|--------|----------|---------|---------|
| `Content-Type` | Yes | — | Must be `application/json` |
| `X-Database` | No | `default` | Selects target database |
| `X-Tenant-ID` | Yes (REST) | `default` (MCP only) | Tenant isolation. **Throws `ValidationException` if missing on REST endpoints.** |
| `X-API-Key` | Conditional | — | Required when auth is LEGACY or RBAC mode |

## Connection Path 3: SDK

### Python SDK

```bash
pip install nocturnusai
```

```python
import asyncio
from nocturnusai import NocturnusAIClient

async def main():
    client = NocturnusAIClient(
        base_url="http://localhost:9300",
        database="default",
        tenant_id="my-tenant",
        # api_key="your-key"  # if auth enabled
    )

    # Assert a fact
    await client.tell("likes", ["alice", "bob"])

    # Query
    results = await client.ask("likes", ["alice", "?who"])
    print(results)

    # Teach a rule
    await client.teach(
        head={"predicate": "mortal", "args": ["?x"]},
        body=[{"predicate": "human", "args": ["?x"]}]
    )

asyncio.run(main())
```

**Sync wrapper** (for non-async contexts):

```python
from nocturnusai import SyncNocturnusAIClient

client = SyncNocturnusAIClient(
    base_url="http://localhost:9300",
    database="default",
    tenant_id="my-tenant",
)
client.tell("likes", ["alice", "bob"])
```

### TypeScript SDK

```bash
npm install nocturnusai-sdk
```

```typescript
import { NocturnusAIClient } from "nocturnusai-sdk";

const client = new NocturnusAIClient({
  baseUrl: "http://localhost:9300",
  database: "default",
  tenantId: "my-tenant",
  // apiKey: "your-key"  // if auth enabled
});

// Assert a fact
await client.tell("likes", ["alice", "bob"]);

// Query
const results = await client.ask("likes", ["alice", "?who"]);
console.log(results);

// Teach a rule
await client.teach({
  head: { predicate: "mortal", args: ["?x"] },
  body: [{ predicate: "human", args: ["?x"] }],
});
```

The TypeScript SDK has zero runtime dependencies (uses built-in `fetch`).

## Auth Modes

NocturnusAI supports three authentication modes:

| Mode | Env Vars Required | Header | Description |
|------|-------------------|--------|-------------|
| **DISABLED** | _(none)_ | _(none)_ | No authentication. All requests pass through. Default mode. |
| **LEGACY** | `API_KEY=<key>` | `X-API-Key: <key>` | Single static API key. All requests must include the key. |
| **RBAC** | `AUTH_ENABLED=true` | `X-API-Key: <key>` | Multi-key with roles and scoping. Keys created via API. |

**Detect current mode**:

```bash
curl http://localhost:9300/auth/status
```

Returns:

```json
{
  "mode": "DISABLED",
  "authenticated": false,
  "bootstrapped": false
}
```

## Auth Bootstrap Sequence (RBAC)

RBAC mode requires a bootstrap sequence to create the first admin key.

### Step 1: Start server with RBAC enabled

```bash
export AUTH_ENABLED=true
export NOCTURNUSAI_ADMIN_USER=admin
export NOCTURNUSAI_ADMIN_PASS=your-secure-password
./gradlew :nocturnusai-server:run
```

### Step 2: Bootstrap the admin key

```bash
curl -X POST http://localhost:9300/auth/bootstrap \
  -H "Content-Type: application/json" \
  -d '{
    "username": "admin",
    "password": "your-secure-password"
  }'
```

Response contains the admin API key:

```json
{
  "apiKey": "nocturnusai_ak_...",
  "role": "ADMIN",
  "description": "Bootstrap admin key"
}
```

### Step 3: Create scoped keys

```bash
# Create a key scoped to a specific database and tenant
curl -X POST http://localhost:9300/auth/keys \
  -H "Content-Type: application/json" \
  -H "X-API-Key: nocturnusai_ak_..." \
  -d '{
    "name": "agent-key",
    "role": "WRITE",
    "databases": ["default"],
    "tenants": ["my-tenant"]
  }'
```

### Step 4: Use scoped keys in requests

```bash
curl -X POST http://localhost:9300/tell \
  -H "Content-Type: application/json" \
  -H "X-API-Key: nocturnusai_ak_agent_..." \
  -H "X-Database: default" \
  -H "X-Tenant-ID: my-tenant" \
  -d '{"predicate": "status", "args": ["system", "online"]}'
```

## Tenant Setup

All NocturnusAI databases are multi-tenant. Each tenant gets an isolated `LogicContext` with its own Hexastore, rules, and memory.

### Step 1: Create or use a database

The `default` database exists automatically. To create a new one:

```bash
curl -X POST http://localhost:9300/admin/databases \
  -H "Content-Type: application/json" \
  -d '{"name": "my-database"}'
```

### Step 2: Create a tenant

```bash
curl -X POST http://localhost:9300/admin/databases/default/tenants \
  -H "Content-Type: application/json" \
  -d '{"tenantId": "my-tenant"}'
```

### Step 3: Include X-Tenant-ID on all requests

```bash
# Every request to tenant-scoped endpoints MUST include this header
curl -X POST http://localhost:9300/tell \
  -H "Content-Type: application/json" \
  -H "X-Database: default" \
  -H "X-Tenant-ID: my-tenant" \
  -d '{"predicate": "ready", "args": ["agent-1"]}'
```

**Important**: `X-Tenant-ID` is **required** on most endpoints and throws a `ValidationException` if missing. MCP routes (`POST /mcp`) default to `"default"` when the header is absent, but REST routes do not.

## Troubleshooting

### Missing Tenant Header

**Error**: `ValidationException: X-Tenant-ID header is required`

**Fix**: Add `X-Tenant-ID` header to every REST request. MCP routes default to `"default"`, but REST endpoints require it explicitly.

```bash
# Wrong
curl -X POST http://localhost:9300/tell -H "Content-Type: application/json" \
  -d '{"predicate": "test", "args": ["a"]}'

# Correct
curl -X POST http://localhost:9300/tell -H "Content-Type: application/json" \
  -H "X-Tenant-ID: default" \
  -d '{"predicate": "test", "args": ["a"]}'
```

### Wrong Auth Mode

**Error**: `401 Unauthorized` when no auth is configured, or requests pass through when auth is expected.

**Fix**: Check the current auth mode:

```bash
curl http://localhost:9300/auth/status
```

Ensure the server env vars match your expectation:
- No env vars = DISABLED (no auth needed)
- `API_KEY=xxx` = LEGACY (single key)
- `AUTH_ENABLED=true` = RBAC (multi-key)

### MCP Transport Mismatch

**Symptom**: Claude Desktop fails to connect to NocturnusAI MCP server.

**Cause**: NocturnusAI uses the older HTTP+SSE MCP transport. Claude Desktop is moving toward Streamable HTTP transport.

**Fix**: Use the `mcp-remote` bridge which translates between stdio (what Claude Desktop expects) and HTTP+SSE (what NocturnusAI speaks):

```bash
npx mcp-remote http://localhost:9300/mcp/sse
```

See the `.mcp.json` example in Connection Path 1 for the full configuration with headers.

### Server Not Responding

**Symptom**: `Connection refused` on port 9300.

**Fix**:
1. Verify the server is running: `curl http://localhost:9300/health`
2. Check the port: server defaults to 9300 unless `PORT` env var is set
3. Check Docker: `docker ps` to verify the container is up
4. Check logs for startup errors

### Tenant Not Found

**Symptom**: Errors when asserting facts or querying.

**Fix**: Create the tenant first:

```bash
curl -X POST http://localhost:9300/admin/databases/default/tenants \
  -H "Content-Type: application/json" \
  -d '{"tenantId": "my-tenant"}'
```

### API Key Rejected in RBAC Mode

**Symptom**: `403 Forbidden` with a valid-looking key.

**Fix**: Verify the key has the correct role and scoping:

```bash
curl http://localhost:9300/auth/whoami \
  -H "X-API-Key: nocturnusai_ak_..."
```

Check that the key's `databases` and `tenants` arrays include the database and tenant you are targeting.
