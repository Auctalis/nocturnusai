#!/usr/bin/env bash
# =============================================================================
# NocturnusAI — curl / HTTP API examples
#
# Usage:
#   bash examples.sh
#
# Prerequisites:
#   curl jq
#   NocturnusAI server running at http://localhost:9300
# =============================================================================

set -euo pipefail

BASE="http://localhost:9300"
DB="demo-curl"
TENANT="default"

# Helper: pretty-print JSON response
pp() { echo "$1" | jq . 2>/dev/null || echo "$1"; }

# Common headers
H_DB="-H 'X-Database: ${DB}'"
H_CT="-H 'Content-Type: application/json'"

echo "============================================================"
echo " NocturnusAI API Demo — $(date)"
echo " Server: $BASE  DB: $DB"
echo "============================================================"

# ── Health ──────────────────────────────────────────────────────────────────
echo -e "\n>>> GET /health"
pp "$(curl -s "$BASE/health")"

# ── Assert Facts ─────────────────────────────────────────────────────────────
echo -e "\n>>> POST /assert/fact — human(socrates)"
curl -s -X POST "$BASE/assert/fact" \
  -H "Content-Type: application/json" \
  -H "X-Database: $DB" \
  -d '{"predicate":"human","args":["socrates"]}'
echo

echo -e "\n>>> POST /assert/fact — human(plato)"
curl -s -X POST "$BASE/assert/fact" \
  -H "Content-Type: application/json" \
  -H "X-Database: $DB" \
  -d '{"predicate":"human","args":["plato"]}'
echo

echo -e "\n>>> POST /assert/fact — parent(alice, bob)"
curl -s -X POST "$BASE/assert/fact" \
  -H "Content-Type: application/json" \
  -H "X-Database: $DB" \
  -d '{"predicate":"parent","args":["alice","bob"]}'
echo

echo -e "\n>>> POST /assert/fact — parent(bob, charlie)"
curl -s -X POST "$BASE/assert/fact" \
  -H "Content-Type: application/json" \
  -H "X-Database: $DB" \
  -d '{"predicate":"parent","args":["bob","charlie"]}'
echo

# ── Assert Rule ───────────────────────────────────────────────────────────────
echo -e "\n>>> POST /assert/rule — mortal(?x) :- human(?x)"
curl -s -X POST "$BASE/assert/rule" \
  -H "Content-Type: application/json" \
  -H "X-Database: $DB" \
  -d '{
    "head": {"predicate":"mortal","args":["?x"]},
    "body": [{"predicate":"human","args":["?x"]}]
  }'
echo

echo -e "\n>>> POST /assert/rule — grandparent(?x,?z) :- parent(?x,?y), parent(?y,?z)"
curl -s -X POST "$BASE/assert/rule" \
  -H "Content-Type: application/json" \
  -H "X-Database: $DB" \
  -d '{
    "head": {"predicate":"grandparent","args":["?x","?z"]},
    "body": [
      {"predicate":"parent","args":["?x","?y"]},
      {"predicate":"parent","args":["?y","?z"]}
    ]
  }'
echo

# ── Query ──────────────────────────────────────────────────────────────────
echo -e "\n>>> POST /infer — mortal(?who)"
pp "$(curl -s -X POST "$BASE/infer" \
  -H "Content-Type: application/json" \
  -H "X-Database: $DB" \
  -d '{"predicate":"mortal","args":["?who"]}')"

echo -e "\n>>> POST /infer — grandparent(alice, ?grandchild)"
pp "$(curl -s -X POST "$BASE/infer" \
  -H "Content-Type: application/json" \
  -H "X-Database: $DB" \
  -d '{"predicate":"grandparent","args":["alice","?grandchild"]}')"

echo -e "\n>>> POST /infer — mortal(socrates) with proof tree"
pp "$(curl -s -X POST "$BASE/infer?proof=true" \
  -H "Content-Type: application/json" \
  -H "X-Database: $DB" \
  -d '{"predicate":"mortal","args":["socrates"]}')"

# ── Schema discovery ───────────────────────────────────────────────────────
echo -e "\n>>> GET /predicates"
pp "$(curl -s "$BASE/predicates" \
  -H "X-Database: $DB")"

# ── Retract ────────────────────────────────────────────────────────────────
echo -e "\n>>> POST /retract — human(plato)"
curl -s -X POST "$BASE/retract" \
  -H "Content-Type: application/json" \
  -H "X-Database: $DB" \
  -d '{"predicate":"human","args":["plato"]}'
echo

echo -e "\n>>> POST /infer — mortal(?who) after retracting plato"
pp "$(curl -s -X POST "$BASE/infer" \
  -H "Content-Type: application/json" \
  -H "X-Database: $DB" \
  -d '{"predicate":"mortal","args":["?who"]}')"

# ── DSL Execute ───────────────────────────────────────────────────────────
echo -e "\n>>> POST /execute — assert via Logiql DSL"
pp "$(curl -s -X POST "$BASE/execute" \
  -H "Content-Type: application/json" \
  -H "X-Database: $DB" \
  -d '{"command":"assert likes(alice, philosophy)"}')"

echo -e "\n>>> POST /execute — query via DSL"
pp "$(curl -s -X POST "$BASE/execute" \
  -H "Content-Type: application/json" \
  -H "X-Database: $DB" \
  -d '{"command":"query likes(?person, ?thing)"}')"

# ── Memory: context window ─────────────────────────────────────────────────
echo -e "\n>>> POST /memory/context — top-5 salient facts"
pp "$(curl -s -X POST "$BASE/memory/context" \
  -H "Content-Type: application/json" \
  -H "X-Database: $DB" \
  -d '{"maxFacts":5,"minSalience":0.0}')"

# ── Memory: temporal query ─────────────────────────────────────────────────
NOW_MS=$(date +%s%3N)

echo -e "\n>>> POST /memory/query/temporal — human(socrates) at now"
pp "$(curl -s -X POST "$BASE/memory/query/temporal" \
  -H "Content-Type: application/json" \
  -H "X-Database: $DB" \
  -d "{\"predicate\":\"human\",\"args\":[\"socrates\"],\"timestamp\":${NOW_MS}}")"

# ── Memory: set priority ──────────────────────────────────────────────────
echo -e "\n>>> POST /memory/priority — boost human(socrates) to 0.9"
curl -s -X POST "$BASE/memory/priority" \
  -H "Content-Type: application/json" \
  -H "X-Database: $DB" \
  -d '{"predicate":"human","args":["socrates"],"priority":0.9}'
echo

# ── Memory: consolidate ────────────────────────────────────────────────────
echo -e "\n>>> POST /memory/consolidate"
pp "$(curl -s -X POST "$BASE/memory/consolidate" \
  -H "Content-Type: application/json" \
  -H "X-Database: $DB" \
  -d '{}')"

# ── Memory: decay ──────────────────────────────────────────────────────────
echo -e "\n>>> POST /memory/decay — threshold=0.0"
pp "$(curl -s -X POST "$BASE/memory/decay" \
  -H "Content-Type: application/json" \
  -H "X-Database: $DB" \
  -d '{"threshold":0.0}')"

# ── Transactions ──────────────────────────────────────────────────────────
echo -e "\n>>> POST /tx/begin"
TX_RESPONSE=$(curl -s -X POST "$BASE/tx/begin" \
  -H "Content-Type: application/json" \
  -H "X-Database: $DB")
pp "$TX_RESPONSE"
TX_ID=$(echo "$TX_RESPONSE" | jq -r '.transactionId // empty' 2>/dev/null || echo "")

if [ -n "$TX_ID" ]; then
  echo -e "\n>>> POST /assert/fact inside transaction $TX_ID"
  curl -s -X POST "$BASE/assert/fact" \
    -H "Content-Type: application/json" \
    -H "X-Database: $DB" \
    -H "X-Transaction-ID: $TX_ID" \
    -d '{"predicate":"account","args":["alice","1000"]}'
  echo

  echo -e "\n>>> POST /tx/commit/$TX_ID"
  curl -s -X POST "$BASE/tx/commit/$TX_ID" \
    -H "X-Database: $DB"
  echo

  echo -e "\n>>> POST /tx/begin — then rollback"
  TX2=$(curl -s -X POST "$BASE/tx/begin" \
    -H "Content-Type: application/json" \
    -H "X-Database: $DB" | jq -r '.transactionId // empty' 2>/dev/null || echo "")

  if [ -n "$TX2" ]; then
    curl -s -X POST "$BASE/assert/fact" \
      -H "Content-Type: application/json" \
      -H "X-Database: $DB" \
      -H "X-Transaction-ID: $TX2" \
      -d '{"predicate":"account","args":["charlie","9999"]}' > /dev/null
    curl -s -X POST "$BASE/tx/rollback/$TX2" -H "X-Database: $DB"
    echo " (rolled back)"
  fi
fi

# ── Auth ───────────────────────────────────────────────────────────────────
echo -e "\n>>> GET /auth/status"
pp "$(curl -s "$BASE/auth/status")"

echo -e "\n>>> GET /.well-known/agent.json (A2A agent card)"
pp "$(curl -s "$BASE/.well-known/agent.json")"

# ── Admin: list databases ─────────────────────────────────────────────────
echo -e "\n>>> GET /admin/databases"
pp "$(curl -s "$BASE/admin/databases")"

echo -e "\n============================================================"
echo " Demo complete."
echo "============================================================"
