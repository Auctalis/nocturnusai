#!/usr/bin/env bash
# =============================================================================
# NocturnusAI — 2-minute value proof
#
# Seeds one relevant reasoning chain + many irrelevant facts, then compares:
#   - full scoped context size
#   - goal-driven optimized context size
#
# Requirements: curl, jq, running server at http://localhost:9300
# =============================================================================

set -euo pipefail

BASE="${BASE:-http://localhost:9300}"
DB="${DB:-default}"
TENANT="${TENANT:-default}"
SCOPE="${SCOPE:-value_demo}"
PRICE_PER_M_TOKENS="${PRICE_PER_M_TOKENS:-15}"

for cmd in curl jq; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "Missing required command: $cmd" >&2
    exit 1
  fi
done

post() {
  local path="$1"
  local body="$2"
  curl -sfS -X POST "${BASE}${path}" \
    -H "Content-Type: application/json" \
    -H "X-Database: ${DB}" \
    -H "X-Tenant-ID: ${TENANT}" \
    -d "${body}"
}

echo "Checking server health at ${BASE}..."
curl -sfS "${BASE}/health" >/dev/null

echo "Seeding scoped dataset in scope='${SCOPE}' (db='${DB}', tenant='${TENANT}')..."

# Relevant facts
post "/tell" "{\"predicate\":\"customer_tier\",\"args\":[\"acme\",\"enterprise\"],\"scope\":\"${SCOPE}\"}" >/dev/null
post "/tell" "{\"predicate\":\"contract_value\",\"args\":[\"acme\",\"2000000\"],\"scope\":\"${SCOPE}\"}" >/dev/null
post "/tell" "{\"predicate\":\"region\",\"args\":[\"acme\",\"us_east\"],\"scope\":\"${SCOPE}\"}" >/dev/null
post "/teach" "{
  \"head\": {\"predicate\":\"priority_support\",\"args\":[\"?c\"]},
  \"body\": [
    {\"predicate\":\"customer_tier\",\"args\":[\"?c\",\"enterprise\"]}
  ],
  \"scope\": \"${SCOPE}\"
}" >/dev/null

# Irrelevant noise facts
for i in $(seq 1 140); do
  post "/tell" "{\"predicate\":\"noise_signal\",\"args\":[\"entity_${i}\",\"value_${i}\"],\"scope\":\"${SCOPE}\"}" >/dev/null
done

summary="$(post "/context/summary" "{\"scope\":\"${SCOPE}\"}")"
optimized="$(post "/context/optimize" "{
  \"scope\": \"${SCOPE}\",
  \"maxFacts\": 12,
  \"goals\": [
    {\"predicate\":\"priority_support\",\"args\":[\"acme\"]}
  ]
}")"

all_facts="$(echo "${summary}" | jq -r '.totalFacts // 0')"
all_chars="$(echo "${summary}" | jq -r '.totalCharCount // 0')"
opt_facts="$(echo "${optimized}" | jq -r '.totalFactsIncluded // 0')"
opt_chars="$(echo "${optimized}" | jq -r '.totalCharCount // 0')"

all_tokens="$(awk "BEGIN { printf \"%.0f\", ${all_chars}/4 }")"
opt_tokens="$(awk "BEGIN { printf \"%.0f\", ${opt_chars}/4 }")"

all_cost="$(awk "BEGIN { printf \"%.4f\", (${all_tokens}/1000000)*${PRICE_PER_M_TOKENS} }")"
opt_cost="$(awk "BEGIN { printf \"%.4f\", (${opt_tokens}/1000000)*${PRICE_PER_M_TOKENS} }")"

if [ "${all_chars}" -gt 0 ]; then
  reduction="$(awk "BEGIN { printf \"%.1f\", 100 - ((${opt_chars}*100)/${all_chars}) }")"
else
  reduction="0.0"
fi

echo
echo "=== Context Cost Optimization Snapshot ==="
echo "Full scoped context   : ${all_facts} facts, ~${all_tokens} tokens, ~$${all_cost}"
echo "Optimized for goal    : ${opt_facts} facts, ~${opt_tokens} tokens, ~$${opt_cost}"
echo "Estimated char reduction: ${reduction}%"
echo
echo "Top optimized facts:"
echo "${optimized}" | jq -r '.entries[] | " - \(.predicate)(\(.args | join(", ")))"' | head -n 8
echo
echo "Goal proven: priority_support(acme)"
