# NocturnusAI

[![CI](https://github.com/Auctalis/nocturnusai/actions/workflows/ci.yml/badge.svg)](https://github.com/Auctalis/nocturnusai/actions/workflows/ci.yml)
[![Docker](https://img.shields.io/badge/docker-ghcr.io%2FAuctalis%2Fnocturnusai-blue?logo=docker)](https://github.com/Auctalis/nocturnusai/pkgs/container/nocturnusai)
[![PyPI](https://img.shields.io/pypi/v/nocturnusai?logo=python&logoColor=white)](https://pypi.org/project/nocturnusai/)
[![npm](https://img.shields.io/npm/v/@nocturnusai/sdk?logo=npm&logoColor=white)](https://www.npmjs.com/package/@nocturnusai/sdk)
[![License: BSL 1.1](https://img.shields.io/badge/license-BSL%201.1-orange.svg)](LICENSE)

**Verified knowledge for AI agents.** Store facts, define rules, and ask questions — get deterministic answers with proof, not LLM guesses.

NocturnusAI is a logic server: an in-memory reasoning engine with a Hexastore, backward and forward chaining, a Truth Maintenance System, and salience-based agent memory. Connect agents via MCP, Python SDK, TypeScript SDK, or plain HTTP.

---

## Quick start

```bash
curl -fsSL https://raw.githubusercontent.com/Auctalis/nocturnusai/main/install.sh | bash
```

That's it. Checks for Docker, downloads the compose config, starts the server, waits for healthy, installs the native CLI binary, and prints ready.

Options:

```bash
# With local LLM (Ollama — no API key needed)
curl -fsSL https://raw.githubusercontent.com/Auctalis/nocturnusai/main/install.sh | bash -s -- --ollama

# With your own API key
curl -fsSL https://raw.githubusercontent.com/Auctalis/nocturnusai/main/install.sh | bash -s -- --key sk-ant-your-key

# With Prometheus + Grafana monitoring
curl -fsSL https://raw.githubusercontent.com/Auctalis/nocturnusai/main/install.sh | bash -s -- --monitoring
```

Verify:

```bash
curl http://localhost:9300/health
# {"status":"healthy"}
```

**Docker only (no wizard):**

```bash
docker run -p 9300:9300 ghcr.io/auctalis/nocturnusai:latest
```

---

## 60-second example

```bash
# Store a fact
curl -X POST http://localhost:9300/tell \
  -H "Content-Type: application/json" \
  -d '{"predicate":"customer_tier","args":["acme","enterprise"]}'

# Teach a rule
curl -X POST http://localhost:9300/teach \
  -H "Content-Type: application/json" \
  -d '{
    "head":{"predicate":"priority_support","args":["?c"]},
    "body":[{"predicate":"customer_tier","args":["?c","enterprise"]}]
  }'

# Ask a question — inference fires the rule
curl -X POST http://localhost:9300/ask \
  -H "Content-Type: application/json" \
  -d '{"predicate":"priority_support","args":["?who"]}'
# [{"predicate":"priority_support","args":["acme"],"truthVal":true}]
```

---

## Connect your agent (MCP)

Add to `.cursor/mcp.json`, `claude_desktop_config.json`, or any MCP client:

```json
{
  "mcpServers": {
    "nocturnus": {
      "url": "http://localhost:9300/mcp/sse"
    }
  }
}
```

Your agent immediately gets 9 tools: `tell`, `teach`, `ask`, `forget`, `recall`, `context`, `compress`, `cleanup`, `predicates`.

---

## Why NocturnusAI?

| | LLM context window | Vector search | NocturnusAI |
|---|---|---|---|
| Answers are | Probabilistic | Approximate | **Deterministic** |
| Shows proof | No | No | **Yes** |
| Derives new facts | No | No | **Yes (inference)** |
| Handles contradiction | No | No | **Yes (TMS)** |
| Persists across sessions | Only with RAG | Yes | **Yes** |
| Temporal queries | No | No | **Yes** |

NocturnusAI is not a replacement for LLMs — it's a reasoning layer that gives LLM agents a reliable source of truth to query and a structured place to store what they learn.

---

## Install SDKs

**Python** (async + sync, LangChain integration):
```bash
pip install nocturnusai
pip install nocturnusai[langchain]   # with LangChain tools
```

**TypeScript / Node.js** (zero runtime dependencies):
```bash
npm install @nocturnusai/sdk
```

---

## Documentation

Full documentation at **[auctalis.github.io/nocturnusai](https://auctalis.github.io/nocturnusai)**

| | |
|---|---|
| [Quickstart](https://auctalis.github.io/nocturnusai/docs) | Get a working agent in 5 minutes |
| [MCP Integration](https://auctalis.github.io/nocturnusai/docs/mcp) | Connect Cursor, Claude, Windsurf, any MCP client |
| [Python SDK](https://auctalis.github.io/nocturnusai/docs/sdks) | Async client + LangChain tools |
| [API Reference](https://auctalis.github.io/nocturnusai/docs/api) | Every endpoint with request/response shapes |
| [Core Concepts](https://auctalis.github.io/nocturnusai/docs/concepts) | Inference, TMS, temporal facts, salience |
| [Security & Auth](https://auctalis.github.io/nocturnusai/docs/security) | API keys, roles, TLS, encryption |

---

## Docker Compose (with Prometheus/Grafana monitoring)

```bash
git clone https://github.com/Auctalis/nocturnusai.git
cd nocturnusai

# Server only
docker compose up -d

# Server + Prometheus + Grafana
docker compose --profile monitoring up -d

# Server + local Ollama model
docker compose --profile ollama up -d
```

---

## CLI (native binary)

The installer downloads a native binary — no JVM required, instant startup:

```bash
nocturnusai                          # Interactive REPL
nocturnusai --server http://host:9300 --db mydb
nocturnusai -e "tell human(socrates)"   # Single command

# Install manually
# macOS (Apple Silicon)
curl -fsSL https://github.com/Auctalis/nocturnusai/releases/latest/download/nocturnusai-macos-arm64 -o /usr/local/bin/nocturnusai && chmod +x /usr/local/bin/nocturnusai

# Linux (x86_64)
curl -fsSL https://github.com/Auctalis/nocturnusai/releases/latest/download/nocturnusai-linux-x86_64 -o /usr/local/bin/nocturnusai && chmod +x /usr/local/bin/nocturnusai
```

---

## Build from source

Requires JDK 17+.

```bash
./gradlew :nocturnusai-server:run   # HTTP server on :9300
./gradlew :nocturnusai-cli:run      # Interactive REPL (JVM)
./gradlew :nocturnusai-cli:nativeCompile  # Build native binary
./gradlew test                       # All tests
```

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Issues labelled `good first issue` are good entry points.

## Security

Report vulnerabilities privately via [GitHub Security Advisories](https://github.com/Auctalis/nocturnusai/security/advisories/new). See [SECURITY.md](SECURITY.md).

## License

Business Source License 1.1 — free for non-production use. Converts to Apache 2.0 on 2030-02-19. Commercial use requires a license from [licensing@nocturnus.ai](mailto:licensing@nocturnus.ai). See [LICENSE](LICENSE).
