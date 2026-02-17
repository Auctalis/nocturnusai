# AxiomBase Quickstart

> Logic server for Agentic AI — deterministic reasoning, truth maintenance, and agent memory.

## One-liner install

```bash
# Works everywhere. Installs everything. You're welcome. 🦞
curl -fsSL https://openclaw.ai/install.sh | bash
```

That's it. AxiomBase is now running on `http://localhost:9300`.

### Install options

```bash
# With local LLM (Ollama — no API key needed)
curl -fsSL https://openclaw.ai/install.sh | bash -s -- --ollama

# With your own API key (auto-detects Anthropic/OpenAI/Google)
curl -fsSL https://openclaw.ai/install.sh | bash -s -- --key sk-ant-your-key

# With monitoring (Prometheus + Grafana dashboards)
curl -fsSL https://openclaw.ai/install.sh | bash -s -- --monitoring

# Custom port
curl -fsSL https://openclaw.ai/install.sh | bash -s -- --port 8080

# Everything at once
curl -fsSL https://openclaw.ai/install.sh | bash -s -- --ollama --monitoring --port 8080
```

The installer:
1. Checks for Docker (tells you how to install it if missing)
2. Downloads the docker-compose config
3. Configures your LLM provider
4. Starts the server
5. Waits for healthy
6. Prints a ready banner with example commands

---

## Verify it's running

```bash
curl http://localhost:9300/health
```

---

## 60-second tutorial

### Store facts

```bash
curl -sX POST http://localhost:9300/tell \
  -H 'Content-Type: application/json' \
  -d '{"predicate":"parent","args":["alice","bob"]}'

curl -sX POST http://localhost:9300/tell \
  -H 'Content-Type: application/json' \
  -d '{"predicate":"parent","args":["bob","charlie"]}'
```

### Teach a rule

```bash
# grandparent(?x, ?z) :- parent(?x, ?y), parent(?y, ?z)
curl -sX POST http://localhost:9300/teach \
  -H 'Content-Type: application/json' \
  -d '{
    "head": {"predicate":"grandparent","args":["?x","?z"]},
    "body": [
      {"predicate":"parent","args":["?x","?y"]},
      {"predicate":"parent","args":["?y","?z"]}
    ]
  }'
```

### Ask a question (inference)

```bash
curl -sX POST http://localhost:9300/ask \
  -H 'Content-Type: application/json' \
  -d '{"predicate":"grandparent","args":["?who","charlie"]}'
```

Response:

```json
[{"predicate":"grandparent","args":["alice","charlie"]}]
```

AxiomBase derived that Alice is Charlie's grandparent by chaining the two `parent` facts through the rule.

### Forget a fact

```bash
curl -sX POST http://localhost:9300/forget \
  -H 'Content-Type: application/json' \
  -d '{"predicate":"parent","args":["bob","charlie"]}'
```

The Truth Maintenance System automatically retracts any facts derived from the removed premise.

---

## Connect your AI agent

### MCP (Claude Desktop, Cursor, Windsurf)

Add to your MCP config:

```json
{
  "mcpServers": {
    "axiombase": {
      "url": "http://localhost:9300/mcp/sse",
      "transport": "sse"
    }
  }
}
```

### Python SDK

```bash
pip install axiombase
```

```python
from axiombase import SyncAxiomBaseClient

with SyncAxiomBaseClient("http://localhost:9300") as client:
    client.assert_fact("parent", ["alice", "bob"])
    results = client.infer("parent", ["?who", "bob"])
    print(results)
```

### TypeScript SDK

```bash
npm install @axiombase/sdk
```

```typescript
import { AxiomBaseClient } from '@axiombase/sdk';

const client = new AxiomBaseClient({ baseUrl: 'http://localhost:9300' });
await client.assertFact('parent', ['alice', 'bob']);
const results = await client.infer('parent', ['?who', 'bob']);
```

---

## Manage your server

```bash
cd ~/axiombase              # or wherever you installed

# Logs
docker compose logs -f axiombase

# Stop
docker compose down

# Restart
docker compose up -d

# Update (re-run the installer)
curl -fsSL https://openclaw.ai/install.sh | bash
```

---

## Next steps

| What | Where |
|------|-------|
| Complete API reference, SDKs, auth, production deployment | [DEVELOPER_GUIDE.md](DEVELOPER_GUIDE.md) |
| User guide with deep tutorials | [USERGUIDE.md](USERGUIDE.md) |
| Auto-generated API docs | `curl http://localhost:9300/llm.txt` |
| MCP tool reference | [mcp-config.json](mcp-config.json) |
| Agent discovery card | `curl http://localhost:9300/.well-known/agent.json` |
