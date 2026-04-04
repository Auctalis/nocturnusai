# curl / HTTP API Examples

Raw HTTP examples for the surfaces most teams integrate first.

Start with context reduction. Drop down to backend mechanics only when you need them.

## Prerequisites

- `curl` and `jq`
- NocturnusAI server running at `http://localhost:9300`
- `X-Tenant-ID` header on REST calls

## Run all examples

```bash
bash examples.sh
```

## Endpoints covered

| Section | Endpoint |
|---------|----------|
| Health | `GET /health` |
| Simple turn reduction | `POST /context` |
| Goal-driven context | `POST /context/optimize` |
| Incremental diff | `POST /context/diff` |
| Clear snapshot | `POST /context/session/clear` |
| Salience window | `POST /memory/context` |
| Consolidate | `POST /memory/consolidate` |
| Decay | `POST /memory/decay` |
| Simplified aliases | `POST /memory/compress`, `POST /memory/cleanup` |
| Assert fact | `POST /assert/fact` |
| Assert rule | `POST /assert/rule` |
| Infer | `POST /infer` |
| Infer with proof | `POST /infer?proof=true` |
| Schema | `GET /predicates` |
| Retract | `POST /retract` |
| DSL | `POST /execute` |
| Temporal query | `POST /memory/query/temporal` |
| Set priority | `POST /memory/priority` |
| Transactions | `POST /tx/begin` -> assert -> `POST /tx/commit/{id}` |
| Rollback | `POST /tx/begin` -> `POST /tx/rollback/{id}` |
| Auth status | `GET /auth/status` |
| Agent card | `GET /.well-known/agent.json` |
| Admin databases | `GET /admin/databases` |

## Quick one-liners

```bash
# Health check
curl http://localhost:9300/health | jq .

# Raw turns -> smaller working set
curl -s -X POST http://localhost:9300/context \
  -H 'Content-Type: application/json' \
  -H 'X-Database: mydb' \
  -H 'X-Tenant-ID: default' \
  -d '{"turns":["user: enterprise customer blocked on SLA credits","tool: contract is worth 2M"],"maxFacts":10}' | jq .

# Goal-driven context window
curl -s -X POST http://localhost:9300/context/optimize \
  -H 'Content-Type: application/json' \
  -H 'X-Database: mydb' \
  -H 'X-Tenant-ID: default' \
  -d '{"goals":[{"predicate":"eligible_for_sla","args":["acme_corp"]}],"maxFacts":10,"sessionId":"ticket-42"}' | jq .

# Diff since the last snapshot
curl -s -X POST http://localhost:9300/context/diff \
  -H 'Content-Type: application/json' \
  -H 'X-Database: mydb' \
  -H 'X-Tenant-ID: default' \
  -d '{"sessionId":"ticket-42","maxFacts":10}' | jq .

# Salience-ranked memory window
curl -s -X POST http://localhost:9300/memory/context \
  -H 'Content-Type: application/json' \
  -H 'X-Database: mydb' \
  -H 'X-Tenant-ID: default' \
  -d '{"maxFacts":10,"minSalience":0.1}' | jq .
```

## Value proof

```bash
bash value_proof.sh
```
