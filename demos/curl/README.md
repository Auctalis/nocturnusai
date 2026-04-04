# curl / HTTP API Examples

Raw HTTP examples covering every major endpoint.

## Prerequisites

- `curl` and `jq` installed
- NocturnusAI server running at `http://localhost:9300`

## Run all examples

```bash
bash examples.sh
```

## Endpoints covered

| Section | Endpoint |
|---------|----------|
| Health | `GET /health` |
| Assert fact | `POST /assert/fact` |
| Assert rule | `POST /assert/rule` |
| Infer | `POST /infer` |
| Infer with proof | `POST /infer?proof=true` |
| Schema | `GET /predicates` |
| Retract | `POST /retract` |
| DSL | `POST /execute` |
| Context window | `POST /memory/context` |
| Temporal query | `POST /memory/query/temporal` |
| Set priority | `POST /memory/priority` |
| Consolidate | `POST /memory/consolidate` |
| Decay | `POST /memory/decay` |
| Transactions | `POST /tx/begin` → assert → `POST /tx/commit/{id}` |
| Rollback | `POST /tx/begin` → `POST /tx/rollback/{id}` |
| Auth status | `GET /auth/status` |
| Agent card | `GET /.well-known/agent.json` |
| Admin: databases | `GET /admin/databases` |

## Quick one-liners

```bash
# Health check
curl http://localhost:9300/health | jq .

# Assert a fact
curl -s -X POST http://localhost:9300/assert/fact \
  -H 'Content-Type: application/json' \
  -H 'X-Database: mydb' \
  -d '{"predicate":"likes","args":["alice","cats"]}'

# Infer with backward chaining
curl -s -X POST http://localhost:9300/infer \
  -H 'Content-Type: application/json' \
  -H 'X-Database: mydb' \
  -d '{"predicate":"likes","args":["?who","?what"]}' | jq .

# Get context window
curl -s -X POST http://localhost:9300/memory/context \
  -H 'Content-Type: application/json' \
  -H 'X-Database: mydb' \
  -d '{"maxFacts":10}' | jq .

# Schema
curl -s http://localhost:9300/predicates \
  -H 'X-Database: mydb' | jq .
```
