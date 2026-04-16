# NocturnusAI

[![CI](https://github.com/Auctalis/nocturnusai/actions/workflows/ci.yml/badge.svg)](https://github.com/Auctalis/nocturnusai/actions/workflows/ci.yml)
[![PyPI](https://img.shields.io/pypi/v/nocturnusai?logo=python&logoColor=white)](https://pypi.org/project/nocturnusai/)
[![npm](https://img.shields.io/npm/v/nocturnusai-sdk?logo=npm&logoColor=white)](https://www.npmjs.com/package/nocturnusai-sdk)
[![Docker](https://img.shields.io/badge/docker-ghcr.io%2FAuctalis%2Fnocturnusai-blue?logo=docker)](https://github.com/Auctalis/nocturnusai/pkgs/container/nocturnusai)
[![License: BUSL-1.1](https://img.shields.io/badge/license-BUSL--1.1-orange.svg?logo=spdx&logoColor=white)](LICENSE)
[![MCP](https://glama.ai/mcp/servers/Auctalis/nocturnusai/badge?score=true)](https://glama.ai/mcp/servers/Auctalis/nocturnusai)

> **License in one line.** [Business Source License 1.1](LICENSE) (SPDX: `BUSL-1.1`). Free for internal use — including internal production — inside your own organization. Offering NocturnusAI or substantial functionality as a product/hosted service to third parties requires a commercial license ([licensing@nocturnus.ai](mailto:licensing@nocturnus.ai)). Converts to Apache 2.0 on 2030-02-19.

**Large turn arrays in. Lean context windows out.**

If your agent keeps replaying chat history, tool output, CRM notes, retries, and stale summaries into every model call, NocturnusAI cuts that down first.

The primary workflow is not "learn predicates." It is:

1. Send the raw turns you already have.
2. Get back a smaller working set.
3. Narrow that set for the next question.
4. Reuse diffs so later turns only send what changed.

Predicates, rules, inference, truth maintenance, scopes, and temporal logic are still there. They matter. They just belong behind the main story, not in front of it.

---

## The Working Loop

> **LLM required for natural-language turns.** The examples below send raw text turns through an LLM to extract structured facts. If you start the server without an LLM provider, natural-language turns will return zero facts. See [Quick Start](#quick-start) for setup options, or use predicate syntax (e.g., `"customer_tier(acme_corp, enterprise)"`) which works without any LLM.

### 1. First reduction: `POST /context`

```bash
curl -X POST http://localhost:9300/context \
  -H 'Content-Type: application/json' \
  -H 'X-Tenant-ID: default' \
  -d '{
    "turns": [
      "user: Customer says they are enterprise and blocked on SLA credits.",
      "tool: CRM says account is Acme Corp with a 2M ARR contract.",
      "agent: Last week support promised to review SLA eligibility.",
      "tool: Billing note says renewal is due next month."
    ],
    "maxFacts": 12
  }'
```

Response shape (facts and salience values depend on LLM extraction):

```json
{
  "facts": [
    {"predicate":"customer_tier","args":["acme_corp","enterprise"],"salience":0.65},
    {"predicate":"contract_value","args":["acme_corp","2000000"],"salience":0.65},
    {"predicate":"issue","args":["acme_corp","sla_credits"],"salience":0.64}
  ],
  "totalFactsInKB": 7,
  "factsReturned": 3,
  "contradictions": 0,
  "newFactsExtracted": 3
}
```

### 2. Goal-driven pass: `POST /memory/context`

```bash
curl -X POST http://localhost:9300/memory/context \
  -H 'Content-Type: application/json' \
  -H 'X-Tenant-ID: default' \
  -d '{
    "goals": [
      {"predicate":"eligible_for_sla","args":["acme_corp"]}
    ],
    "maxFacts": 12,
    "sessionId": "ticket-42"
  }'
```

Use this when you know what the next model call is trying to answer.

### 3. Later turns: `POST /context/diff`

```bash
curl -X POST http://localhost:9300/context/diff \
  -H 'Content-Type: application/json' \
  -H 'X-Tenant-ID: default' \
  -d '{
    "sessionId": "ticket-42",
    "maxFacts": 12
  }'
```

This returns only `added` and `removed` entries between snapshots.

### 4. End of thread: `POST /context/session/clear`

```bash
curl -X POST http://localhost:9300/context/session/clear \
  -H 'Content-Type: application/json' \
  -H 'X-Tenant-ID: default' \
  -d '{"sessionId":"ticket-42"}'
```

---

## Choose Your Surface

### Python SDK

```python
from nocturnusai import SyncNocturnusAIClient

with SyncNocturnusAIClient("http://localhost:9300") as client:
    ctx = client.process_turns(
        turns=[
            "user: Customer says they are enterprise and blocked on SLA credits.",
            "tool: CRM says account is Acme Corp with a 2M ARR contract.",
        ],
        scope="ticket-42",
        session_id="ticket-42",
    )

    diff = client.diff_context(session_id="ticket-42", max_facts=12)
    client.clear_context_session("ticket-42")

    print(ctx.briefing_delta)
```

### TypeScript SDK

```ts
import { NocturnusAIClient } from 'nocturnusai-sdk';

const client = new NocturnusAIClient({
  baseUrl: 'http://localhost:9300',
  tenantId: 'default',
});

const ctx = await client.processTurns({
  turns: [
    'user: Customer says they are enterprise and blocked on SLA credits.',
    'tool: CRM says account is Acme Corp with a 2M ARR contract.',
  ],
  scope: 'ticket-42',
  sessionId: 'ticket-42',
});

const diff = await client.diffContext({
  sessionId: 'ticket-42',
  maxFacts: 12,
});

await client.clearContextSession('ticket-42');
console.log(ctx.briefingDelta);
```

### MCP

Add Nocturnus as an MCP server:

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

Use the `context` tool each turn for a salience-ranked working set. Pair MCP with the HTTP context endpoints when you need goal-driven assembly and diffs.

---

## What Lives Behind The Workflow

When you do need backend mechanics, NocturnusAI provides them:

- Deterministic fact and rule storage
- Backward-chaining inference with proof chains
- Truth maintenance and contradiction handling
- Temporal facts with `ttl`, `validFrom`, and `validUntil`
- Multi-tenancy via `X-Database` and `X-Tenant-ID`
- MCP, REST, Python SDK, TypeScript SDK, and CLI surfaces over the same engine

That is the backend. The front-of-product story is still turn reduction.

---

## Quick Start

### Docker (fastest)

```bash
docker run -d --name nocturnusai -p 9300:9300 \
  --restart unless-stopped \
  -v nocturnusai-data:/data \
  ghcr.io/auctalis/nocturnusai:latest
```

Verify it's running:

```bash
curl http://localhost:9300/health
```

Try the logic engine (works immediately, no LLM needed):

```bash
curl -X POST http://localhost:9300/tell \
  -H 'Content-Type: application/json' \
  -H 'X-Tenant-ID: default' \
  -d '{"predicate":"customer_tier","args":["acme_corp","enterprise"]}'

curl -X POST http://localhost:9300/tell \
  -H 'Content-Type: application/json' \
  -H 'X-Tenant-ID: default' \
  -d '{"predicate":"contract_value","args":["acme_corp","2000000"]}'

curl -X POST http://localhost:9300/ask \
  -H 'Content-Type: application/json' \
  -H 'X-Tenant-ID: default' \
  -d '{"predicate":"customer_tier","args":["acme_corp","?tier"]}'
```

That's it. Server is running, persists data to a named Docker volume, and restarts automatically. For natural-language turn extraction (the Working Loop above), add an LLM provider -- see the next section.

### Docker with Ollama (enables natural-language extraction)

If you have [Ollama](https://ollama.com) running locally:

```bash
docker run -d --name nocturnusai -p 9300:9300 \
  --add-host=host.docker.internal:host-gateway \
  -e LLM_PROVIDER=ollama \
  -e LLM_MODEL=granite3.3:8b \
  -e LLM_BASE_URL=http://host.docker.internal:11434/v1 \
  -e EXTRACTION_ENABLED=true \
  ghcr.io/auctalis/nocturnusai:latest
```

### Install script (CLI + setup wizard)

```bash
curl -fsSL https://raw.githubusercontent.com/Auctalis/nocturnusai/main/install.sh | bash
```

Downloads the CLI binary and launches an interactive setup wizard where you choose your LLM provider (Ollama, Anthropic, OpenAI, Google, or skip). Creates a persistent Docker Compose install.

Shortcuts if you already know what you want:

```bash
curl -fsSL ... | bash -s -- --host-ollama    # Reuse local Ollama
curl -fsSL ... | bash -s -- --ollama         # Bundle Ollama in Docker
curl -fsSL ... | bash -s -- --key sk-ant-... # Use Anthropic
```

### Python SDK

```bash
pip install nocturnusai
```

### TypeScript SDK

```bash
npm install nocturnusai-sdk
```

### MCP client

Copy one of the configs from [`mcp-configs/`](./mcp-configs).

### From this repo (contributors)

```bash
make up-ollama
make smoke
```

---

## CLI

The CLI is useful for interactive inspection and salience-window retrieval:

```bash
nocturnusai                                # Interactive REPL
nocturnusai -e "context 10"               # Salience-ranked working set
nocturnusai -e "compress"                 # Simplified alias: POST /memory/compress
nocturnusai -e "cleanup 0.05"             # Simplified alias: POST /memory/cleanup
```

For goal-driven context windows and diffs, use the REST API or SDKs alongside the CLI.

---

## Documentation

Full docs: **[nocturnus.ai](https://nocturnus.ai)**

| | |
|---|---|
| [Start Here](https://nocturnus.ai/docs) | Begin with the turn-reduction workflow |
| [Context Workflow](https://nocturnus.ai/docs/context) | Raw turns -> optimize -> diff -> clear |
| [API Reference](https://nocturnus.ai/docs/api) | REST endpoints and response shapes |
| [SDKs](https://nocturnus.ai/docs/sdks) | Python and TypeScript client methods |
| [Integrations](https://nocturnus.ai/integrations) | LangChain, CrewAI, AutoGen, LangGraph, OpenAI Agents, Anthropic, MCP |
| [MCP Integration](https://nocturnus.ai/docs/mcp) | MCP config plus companion context API usage |
| [How It Works on the Backend](https://nocturnus.ai/docs/concepts) | Facts, rules, inference, salience, scopes |
| [Security & Auth](https://nocturnus.ai/docs/security) | API keys, RBAC, TLS, encryption at rest |

---

## Docker Compose (advanced)

For persistent config, monitoring, or Ollama bundling:

```bash
git clone https://github.com/Auctalis/nocturnusai.git && cd nocturnusai

make up                                        # Server using .env.example defaults
make up-ollama                                 # + Ollama (reuses host or starts bundled)
make up-monitoring                             # + Prometheus + Grafana
make smoke                                     # Verify health + context endpoint
```

---

## Build from Source

Requires JDK 17+.

```bash
./gradlew :nocturnusai-server:run              # HTTP server on :9300
./gradlew :nocturnusai-cli:run                 # Interactive REPL (JVM)
./gradlew :nocturnusai-cli:nativeCompile       # Build native binary
./gradlew test                                 # Full test suite
```

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Issues labelled `good first issue` are good entry points.

## Security

Report vulnerabilities privately via [GitHub Security Advisories](https://github.com/Auctalis/nocturnusai/security/advisories/new). See [SECURITY.md](SECURITY.md).

## License

Business Source License 1.1 - free for non-production use and internal production use within your organization. Offering NocturnusAI to third parties as a product or service requires a commercial license from [licensing@nocturnus.ai](mailto:licensing@nocturnus.ai). Converts to Apache 2.0 on 2030-02-19. See [LICENSE](LICENSE) and [DISCLAIMER.md](DISCLAIMER.md).

---

> **LEGAL & SAFETY NOTICE**
>
> NocturnusAI is a deterministic reasoning engine, but **its output is only as reliable as the facts provided to it.**
>
> 1. **No Warranty of Truth.** "Verified" refers to logical consistency of inference, not accuracy of real-world claims.
> 2. **Not for Autonomous High-Stakes Decisions.** Do not use this engine for unsupervised medical, financial, legal, or physical-safety decisions without an independent human verification step.
> 3. **Logic Layer Only.** NocturnusAI provides information and inference; it does not execute actions.
> 4. **No Liability.** See [DISCLAIMER.md](DISCLAIMER.md) and [LICENSE](LICENSE).
