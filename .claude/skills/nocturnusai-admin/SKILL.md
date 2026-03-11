---
name: nocturnusai-admin
description: >
  Use when managing NocturnusAI databases, tenants, health checks, metrics,
  backups, API key management, RBAC configuration, admin operations, or
  operational monitoring. Triggers on: NocturnusAI database, tenant, health,
  metrics, backup, API key, RBAC, admin, monitoring, operational.
---

# NocturnusAI Admin

## Important: REST-Only Operations

ALL admin operations in this skill are REST-only. There are no MCP tools for database management, tenant management, health checks, metrics, backups, or API key management. Use `curl` via the Bash tool for every operation in this skill.

**Prerequisite**: See the `nocturnusai-connect` skill for server setup, connection configuration, and auth bootstrap details.

**Required headers** (when applicable):

| Header | When Required |
|--------|---------------|
| `Content-Type: application/json` | All POST/PATCH requests |
| `X-API-Key: <key>` | When auth is LEGACY or RBAC mode |
| `X-Database: <name>` | When targeting a non-default database |

## Database Management

### List databases

```bash
curl http://localhost:9300/admin/databases
```

### Create a database

```bash
curl -X POST http://localhost:9300/admin/databases \
  -H "Content-Type: application/json" \
  -d '{"name": "my-database"}'
```

Optional field: `"defaultConflictStrategy"` — one of `REJECT` (default), `NEWEST_WINS`, `CONFIDENCE`, `KEEP_BOTH`.

### Delete a database (DESTRUCTIVE)

```bash
curl -X DELETE http://localhost:9300/admin/databases/my-database
```

This is irreversible. All tenants, facts, rules, and memory within the database are permanently destroyed.

### List facts in a database

```bash
curl http://localhost:9300/admin/databases/default/facts \
  -H "X-Tenant-ID: my-tenant"
```

Optional query parameter: `?scope=my-scope` to filter by scope.

### List rules in a database

```bash
curl http://localhost:9300/admin/databases/default/rules \
  -H "X-Tenant-ID: my-tenant"
```

## Tenant Management

All NocturnusAI databases are forced multi-tenant. You must create at least one tenant before asserting facts or running queries.

### Create a tenant

```bash
curl -X POST http://localhost:9300/admin/databases/default/tenants \
  -H "Content-Type: application/json" \
  -d '{"tenantId": "my-tenant"}'
```

### List tenants

```bash
curl http://localhost:9300/admin/databases/default/tenants
```

### Delete a tenant (DESTRUCTIVE)

```bash
curl -X DELETE http://localhost:9300/admin/databases/default/tenants/my-tenant
```

### Nuke a database (DESTRUCTIVE)

Clears all data in all tenants within a database without deleting the database itself:

```bash
curl -X POST http://localhost:9300/admin/databases/default/nuke
```

### Nuke a tenant (DESTRUCTIVE)

Clears all data in a specific tenant without deleting the tenant:

```bash
curl -X POST http://localhost:9300/admin/databases/default/tenants/my-tenant/nuke
```

## Health Checks

All health endpoints bypass authentication.

### General health

```bash
curl http://localhost:9300/health
```

Returns JSON with `status` field (`"healthy"` or `"unhealthy"`), database count, storage info, and LLM configuration status. Returns HTTP 503 when unhealthy.

### Liveness probe

```bash
curl http://localhost:9300/health/live
```

Returns plain text `OK`. Use for Kubernetes liveness probes.

### Readiness probe

```bash
curl http://localhost:9300/health/ready
```

Same as `/health` — returns full health status with HTTP 503 when unhealthy. Use for Kubernetes readiness probes.

## Metrics

The `/metrics` endpoint returns Prometheus-format metrics and bypasses authentication.

```bash
curl http://localhost:9300/metrics
```

Returns text/plain with counters, gauges, and histograms for HTTP requests, JVM stats, and application metrics. Scrape with Prometheus or any compatible monitoring system.

## RBAC Key Management

RBAC must be enabled (`AUTH_ENABLED=true`) for key management endpoints to work. All key management endpoints (except `/auth/status`) require an admin API key.

### Roles

| Role | Permissions |
|------|-------------|
| `ADMIN` | Full access: read, write, manage keys, admin operations |
| `WRITER` | Read and write facts/rules, query, memory operations |
| `READER` | Read-only: query facts, inspect, context retrieval |

### Check auth status

```bash
curl http://localhost:9300/auth/status
```

Returns `mode` (disabled/legacy/rbac), `bootstrapRequired`, and `keyCount`. Bypasses auth.

### Create an API key

```bash
curl -X POST http://localhost:9300/auth/keys \
  -H "Content-Type: application/json" \
  -H "X-API-Key: nocturnusai_ak_ADMIN_KEY_HERE" \
  -d '{
    "name": "agent-key",
    "role": "writer",
    "databases": ["default"],
    "tenants": ["my-tenant"],
    "expiresInDays": 90,
    "description": "Key for production agent"
  }'
```

`databases` and `tenants` arrays scope the key. Empty arrays mean unrestricted access to all databases/tenants. Only admin keys can create other admin keys.

Response includes the raw API key (shown only once):

```json
{
  "id": "uuid",
  "name": "agent-key",
  "key": "nocturnusai_ak_...",
  "prefix": "nocturnusai_ak_abc",
  "role": "writer",
  "databases": ["default"],
  "tenants": ["my-tenant"],
  "expiresAt": "2026-06-11T..."
}
```

### List all keys

```bash
curl http://localhost:9300/auth/keys \
  -H "X-API-Key: nocturnusai_ak_ADMIN_KEY_HERE"
```

### Get key details

```bash
curl http://localhost:9300/auth/keys/KEY_ID \
  -H "X-API-Key: nocturnusai_ak_ADMIN_KEY_HERE"
```

### Update a key

```bash
curl -X PATCH http://localhost:9300/auth/keys/KEY_ID \
  -H "Content-Type: application/json" \
  -H "X-API-Key: nocturnusai_ak_ADMIN_KEY_HERE" \
  -d '{"enabled": false}'
```

Updatable fields: `name`, `databases`, `tenants`, `enabled`, `description`.

### Revoke (delete) a key

```bash
curl -X DELETE http://localhost:9300/auth/keys/KEY_ID \
  -H "X-API-Key: nocturnusai_ak_ADMIN_KEY_HERE"
```

You cannot revoke your own key. Use a different admin key.

### Check current identity

```bash
curl http://localhost:9300/auth/whoami \
  -H "X-API-Key: nocturnusai_ak_YOUR_KEY"
```

Returns the key's role, permissions, and database/tenant scoping.

## Backups

### Trigger a snapshot

```bash
curl -X POST http://localhost:9300/admin/backups
```

Optional query parameter: `?db=my-database` (defaults to `default`).

Creates a full-state JSON snapshot in the `backups/` directory relative to the configured `STORAGE_DIR`.

## Discovery Endpoints

Both discovery endpoints bypass authentication.

### LLM API Documentation

```bash
curl http://localhost:9300/llm.txt
```

Returns auto-generated plain-text API documentation designed for LLM consumption. Describes all available endpoints, parameters, and usage patterns.

### A2A Agent Card

```bash
curl http://localhost:9300/.well-known/agent.json
```

Returns an Agent2Agent Protocol discovery document describing NocturnusAI's capabilities, authentication scheme, skills, and protocol support (MCP + REST). Used by other AI agents to discover and interact with NocturnusAI.

### User Guide

```bash
curl http://localhost:9300/userguide
```

Returns the bundled USERGUIDE.md in plain text.

## Common Workflows

### First-time setup

```bash
# 1. Verify server is running
curl http://localhost:9300/health

# 2. Create a database (or use 'default')
curl -X POST http://localhost:9300/admin/databases \
  -H "Content-Type: application/json" \
  -d '{"name": "my-app"}'

# 3. Create a tenant (required — all databases are multi-tenant)
curl -X POST http://localhost:9300/admin/databases/my-app/tenants \
  -H "Content-Type: application/json" \
  -d '{"tenantId": "my-tenant"}'

# 4. Verify by listing tenants
curl http://localhost:9300/admin/databases/my-app/tenants
```

### RBAC key management

See the `nocturnusai-connect` skill for the full auth bootstrap sequence (starting the server with `AUTH_ENABLED=true`, bootstrapping the first admin key with `POST /auth/bootstrap`).

After bootstrap:

```bash
# Create a scoped writer key for an agent
curl -X POST http://localhost:9300/auth/keys \
  -H "Content-Type: application/json" \
  -H "X-API-Key: nocturnusai_ak_ADMIN_KEY" \
  -d '{
    "name": "agent-writer",
    "role": "writer",
    "databases": ["my-app"],
    "tenants": ["my-tenant"]
  }'

# Create a read-only key for monitoring
curl -X POST http://localhost:9300/auth/keys \
  -H "Content-Type: application/json" \
  -H "X-API-Key: nocturnusai_ak_ADMIN_KEY" \
  -d '{
    "name": "monitor-reader",
    "role": "reader",
    "databases": ["my-app"],
    "tenants": ["my-tenant"]
  }'

# List all keys to verify
curl http://localhost:9300/auth/keys \
  -H "X-API-Key: nocturnusai_ak_ADMIN_KEY"
```

### Monitoring

```bash
# Quick health check
curl http://localhost:9300/health

# Kubernetes-style probes
curl http://localhost:9300/health/live    # liveness
curl http://localhost:9300/health/ready   # readiness

# Prometheus metrics scrape
curl http://localhost:9300/metrics
```

### Multi-database environments

Use separate databases for dev/staging/prod. The `X-Database` header selects the target:

```bash
# Create environment databases
curl -X POST http://localhost:9300/admin/databases \
  -H "Content-Type: application/json" -d '{"name": "dev"}'

curl -X POST http://localhost:9300/admin/databases \
  -H "Content-Type: application/json" -d '{"name": "staging"}'

curl -X POST http://localhost:9300/admin/databases \
  -H "Content-Type: application/json" -d '{"name": "prod"}'

# Create tenants in each
for db in dev staging prod; do
  curl -X POST "http://localhost:9300/admin/databases/$db/tenants" \
    -H "Content-Type: application/json" \
    -d '{"tenantId": "app"}'
done

# Target a specific environment with X-Database header
curl -X POST http://localhost:9300/tell \
  -H "Content-Type: application/json" \
  -H "X-Database: staging" \
  -H "X-Tenant-ID: app" \
  -d '{"predicate": "status", "args": ["deploy", "pending"]}'
```

## Gotchas

1. **All databases are forced multi-tenant.** You cannot skip tenant creation. Even the `default` database requires a tenant before you can assert facts or run queries.

2. **`DELETE /admin/databases/{name}` is destructive and irreversible.** All tenants, facts, rules, and memory are permanently destroyed. There is no confirmation prompt via the API.

3. **Auth bootstrap can only run once.** `POST /auth/bootstrap` only succeeds when no admin keys exist. After the first admin key is created, use `POST /auth/keys` with an admin key to create additional keys. The bootstrap endpoint is also rate-limited (5 attempts per minute, 5-minute lockout).

4. **Public endpoints bypass auth entirely.** These endpoints are always accessible regardless of auth mode: `/health`, `/health/live`, `/health/ready`, `/metrics`, `/llm.txt`, `/.well-known/agent.json`, `/userguide`, `/auth/status`.

5. **Nuke vs. Delete.** `POST /admin/databases/{name}/nuke` clears all data but keeps the database and tenant structure. `DELETE /admin/databases/{name}` removes the database entirely. Use nuke for data resets; use delete for decommissioning.

6. **Backup query parameter.** `POST /admin/backups` defaults to the `default` database. Use `?db=my-database` to back up a specific database.

7. **Tenant-scoped admin queries.** `GET /admin/databases/{name}/facts` and `/rules` require `X-Tenant-ID` header to specify which tenant's data to list.

8. **Cannot revoke your own key.** The `DELETE /auth/keys/{id}` endpoint prevents self-revocation. Use a different admin key to revoke the current one.
