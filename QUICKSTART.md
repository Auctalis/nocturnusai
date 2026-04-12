# NocturnusAI Quickstart

> Start with the real problem: you have too many turns, and you need a smaller context window for the next model call.

## One-line install

```bash
curl -fsSL https://raw.githubusercontent.com/Auctalis/nocturnusai/main/install.sh | bash
```

This downloads the CLI and launches the interactive `nocturnusai setup` wizard so the developer can choose the environment instead of being forced into Ollama first.

NocturnusAI then runs on `http://localhost:9300`.

If you are starting from this repo instead of the hosted installer:

```bash
make up-ollama
make smoke
```

`make up-ollama` is the repo-local/manual path. The primary install path is still the one-line installer above.

### Install options

```bash
# Local Ollama on your machine
curl -fsSL https://raw.githubusercontent.com/Auctalis/nocturnusai/main/install.sh | bash -s -- --host-ollama

# Ollama in Docker
curl -fsSL https://raw.githubusercontent.com/Auctalis/nocturnusai/main/install.sh | bash -s -- --ollama

# With your own provider key
curl -fsSL https://raw.githubusercontent.com/Auctalis/nocturnusai/main/install.sh | bash -s -- --key sk-ant-your-key

# Custom port
curl -fsSL https://raw.githubusercontent.com/Auctalis/nocturnusai/main/install.sh | bash -s -- --port 8080
```

## Verify health

```bash
curl http://localhost:9300/health
export NOCTURNUS_TENANT=default
```

Most REST endpoints require `X-Tenant-ID`.

---

## 60-second turn reduction

### 1. Send the raw turns

```bash
curl -sX POST http://localhost:9300/context \
  -H 'Content-Type: application/json' \
  -H "X-Tenant-ID: ${NOCTURNUS_TENANT}" \
  -d '{
    "turns": [
      "user: Customer says they are enterprise and blocked on SLA credits.",
      "tool: CRM says account is Acme Corp with a 2M ARR contract.",
      "tool: Billing note says renewal is due next month.",
      "agent: Last week support promised to review SLA eligibility."
    ],
    "maxFacts": 10
  }' | jq .
```

This is the first compact pass: turns in, reduced facts out.

### 2. Narrow the window for the next question

```bash
curl -sX POST http://localhost:9300/context/optimize \
  -H 'Content-Type: application/json' \
  -H "X-Tenant-ID: ${NOCTURNUS_TENANT}" \
  -d '{
    "goals": [
      {"predicate":"eligible_for_sla","args":["acme_corp"]}
    ],
    "maxFacts": 10,
    "sessionId": "ticket-42"
  }' | jq .
```

### 3. Reuse diffs on later turns

```bash
curl -sX POST http://localhost:9300/context/diff \
  -H 'Content-Type: application/json' \
  -H "X-Tenant-ID: ${NOCTURNUS_TENANT}" \
  -d '{
    "sessionId": "ticket-42",
    "maxFacts": 10
  }' | jq .
```

### 4. Clear the session snapshot when the thread ends

```bash
curl -sX POST http://localhost:9300/context/session/clear \
  -H 'Content-Type: application/json' \
  -H "X-Tenant-ID: ${NOCTURNUS_TENANT}" \
  -d '{"sessionId":"ticket-42"}'
```

---

## Connect your app or agent

### Python SDK

```bash
pip install nocturnusai
```

```python
from nocturnusai import SyncNocturnusAIClient

with SyncNocturnusAIClient("http://localhost:9300") as client:
    ctx = client.process_turns(
        turns=["user: Customer is enterprise, blocked on SLA credits."],
        scope="ticket-42",
        session_id="ticket-42",
    )
    diff = client.diff_context(session_id="ticket-42", max_facts=10)
    client.clear_context_session("ticket-42")
```

### TypeScript SDK

```bash
npm install nocturnusai-sdk
```

```ts
import { NocturnusAIClient } from 'nocturnusai-sdk';

const client = new NocturnusAIClient({ baseUrl: 'http://localhost:9300' });
const ctx = await client.processTurns({
  turns: ['user: Customer is enterprise, blocked on SLA credits.'],
  scope: 'ticket-42',
  sessionId: 'ticket-42',
});
const diff = await client.diffContext({ sessionId: 'ticket-42', maxFacts: 10 });
await client.clearContextSession('ticket-42');
```

### MCP

Add to your MCP config:

```json
{
  "mcpServers": {
    "nocturnus": {
      "url": "http://localhost:9300/mcp/sse",
      "transport": "sse"
    }
  }
}
```

MCP gives your agent the `context` tool immediately. Use the HTTP context endpoints alongside MCP when you need goal-driven assembly and diffs.

### CLI

```bash
nocturnusai
# then inside the REPL:
#   context 10
#   compress
#   cleanup 0.05
```

`compress` maps to `POST /memory/compress`, and `cleanup` maps to `POST /memory/cleanup`.

---

## If you need backend reasoning later

When you are ready to model facts and rules directly, the low-level surfaces are still there:

```bash
curl -sX POST http://localhost:9300/tell \
  -H 'Content-Type: application/json' \
  -H "X-Tenant-ID: ${NOCTURNUS_TENANT}" \
  -d '{"predicate":"parent","args":["alice","bob"]}'

curl -sX POST http://localhost:9300/teach \
  -H 'Content-Type: application/json' \
  -H "X-Tenant-ID: ${NOCTURNUS_TENANT}" \
  -d '{
    "head": {"predicate":"grandparent","args":["?x","?z"]},
    "body": [
      {"predicate":"parent","args":["?x","?y"]},
      {"predicate":"parent","args":["?y","?z"]}
    ]
  }'

curl -sX POST http://localhost:9300/ask \
  -H 'Content-Type: application/json' \
  -H "X-Tenant-ID: ${NOCTURNUS_TENANT}" \
  -d '{"predicate":"grandparent","args":["?who","bob"]}' | jq .
```

That is the backend layer. Start there only when you actually need it.

---

## Manage your server

```bash
# Logs
docker compose logs -f nocturnusai

# Stop
make down

# Restart
make up-ollama

# Update
curl -fsSL https://raw.githubusercontent.com/Auctalis/nocturnusai/main/install.sh | bash
```

---

## Next steps

| What | Where |
|------|-------|
| Docs site | [auctalis.github.io/nocturnusai](https://auctalis.github.io/nocturnusai) |
| Context workflow | [site docs](https://auctalis.github.io/nocturnusai/docs/context) |
| Full API reference | [DEVELOPER_GUIDE.md](DEVELOPER_GUIDE.md) |
| Deep user guide | [USERGUIDE.md](USERGUIDE.md) |
| MCP configs | [mcp-configs/README.md](mcp-configs/README.md) |
| Agent card | `curl http://localhost:9300/.well-known/agent.json` |
