# NocturnusAI

[![CI](https://github.com/Auctalis/nocturnusai/actions/workflows/ci.yml/badge.svg)](https://github.com/Auctalis/nocturnusai/actions/workflows/ci.yml)
[![PyPI](https://img.shields.io/pypi/v/nocturnusai?logo=python&logoColor=white)](https://pypi.org/project/nocturnusai/)
[![npm](https://img.shields.io/npm/v/nocturnusai-sdk?logo=npm&logoColor=white)](https://www.npmjs.com/package/nocturnusai-sdk)
[![Docker](https://img.shields.io/badge/docker-ghcr.io%2FAuctalis%2Fnocturnusai-blue?logo=docker)](https://github.com/Auctalis/nocturnusai/pkgs/container/nocturnusai)
[![License: BSL 1.1](https://img.shields.io/badge/license-BSL%201.1-orange.svg)](LICENSE)

**AI agents hallucinate. Give them a reasoning backend that doesn't.**

LLMs guess. NocturnusAI *proves*. It's a logic server that stores facts, applies rules, and returns deterministic answers with full proof chains — so your agent can know things instead of predicting them.

```python
pip install nocturnusai
```

```python
from nocturnusai import SyncNocturnusAIClient

with SyncNocturnusAIClient("http://localhost:9300") as ai:
    ai.assert_fact("customer_tier", ["acme", "enterprise"])
    ai.assert_rule(
        head=("priority_support", ["?c"]),
        body=[("customer_tier", ["?c", "enterprise"])]
    )
    results = ai.infer("priority_support", ["?who"])
    print(results)  # [Atom(predicate='priority_support', args=['acme'], truth_val=True)]
```

No prompt engineering. No temperature tuning. The answer is `acme` because the rule says so.

---

> **LEGAL & SAFETY NOTICE**
>
> NocturnusAI is a deterministic reasoning engine, but **its output is only as reliable as the facts provided to it.**
>
> 1. **No Warranty of Truth.** "Verified" refers to *logical consistency* of inference, not *accuracy* of real-world claims. If you assert false facts, the engine will derive false but logically consistent conclusions.
> 2. **Not for Autonomous High-Stakes Decisions.** Do not use this engine for unsupervised medical, financial, legal, or physical-safety decisions without an independent human-in-the-loop verification step.
> 3. **Logic Layer Only.** NocturnusAI provides information and inference; it does not execute actions. Any agent layer that acts upon NocturnusAI output is a separate system for which the authors bear no responsibility.
> 4. **No Liability.** Under no circumstances shall the authors or contributors be liable for damages arising from decisions made using engine output. See [DISCLAIMER.md](DISCLAIMER.md) and [LICENSE](LICENSE).

---

## Architecture

```
 ┌──────────────────────────────────────────────────────────┐
 │                      Your AI Agent                       │
 └──────┬──────────────────┬───────────────────┬────────────┘
        │                  │                   │
   Python SDK         MCP Protocol        REST / HTTP
   (pip install)    (Cursor, Claude,     (any language)
                     Windsurf, etc.)
        │                  │                   │
        └──────────────────┼───────────────────┘
                           │
 ┌─────────────────────────▼───────────────────────────────┐
 │                   NocturnusAI Server                     │
 │                                                          │
 │  ┌─────────────┐ ┌──────────────┐ ┌──────────────────┐  │
 │  │  Hexastore   │ │  Inference   │ │ Truth Maintenance│  │
 │  │ (6-way index)│ │  Backward &  │ │    System        │  │
 │  │             │ │  Forward     │ │  (auto-retract)  │  │
 │  └─────────────┘ └──────────────┘ └──────────────────┘  │
 │  ┌─────────────┐ ┌──────────────┐ ┌──────────────────┐  │
 │  │   Agent     │ │  Temporal    │ │   Multi-Tenant   │  │
 │  │   Memory    │ │   Queries    │ │   + Scopes       │  │
 │  │  (salience) │ │  (TTL, time) │ │                  │  │
 │  └─────────────┘ └──────────────┘ └──────────────────┘  │
 └─────────────────────────────────────────────────────────┘
```

---

## Why NocturnusAI?

| Capability | LLM context window | Vector RAG | LangGraph state | **NocturnusAI** |
|---|---|---|---|---|
| Answers are | Probabilistic | Approximate (top-k) | Deterministic* | **Deterministic with proof** |
| Derives new facts | No | No | Only via code | **Yes (inference engine)** |
| Detects contradictions | No | No | No | **Yes (TMS)** |
| Temporal queries | No | No | Manual | **Built-in (TTL, time ranges)** |
| Persists across sessions | No | Yes | Checkpoints | **Yes (WAL + snapshots)** |
| Shows reasoning chain | Chain-of-thought (unreliable) | No | No | **Full proof trees** |
| Sub-millisecond queries | No | ~10ms | Depends | **Yes (in-memory Hexastore)** |

*LangGraph is deterministic for its graph execution, but doesn't perform logical inference.

**Trade-offs to know about:** NocturnusAI is an in-memory store — not a replacement for your database. It excels at structured reasoning over hundreds of thousands of facts, not petabyte-scale storage. It complements LLMs; it doesn't replace them.

---

## Quick Start

### Path 1: I want to try it now

```bash
curl -fsSL https://raw.githubusercontent.com/Auctalis/nocturnusai/main/install.sh | bash
```

Checks for Docker, starts the server, installs the CLI binary. Done.

```bash
# With local LLM (Ollama)
curl -fsSL https://raw.githubusercontent.com/Auctalis/nocturnusai/main/install.sh | bash -s -- --ollama

# With Prometheus + Grafana monitoring
curl -fsSL https://raw.githubusercontent.com/Auctalis/nocturnusai/main/install.sh | bash -s -- --monitoring
```

### Path 2: I'm a Python developer

```bash
pip install nocturnusai
docker run -d -p 9300:9300 ghcr.io/auctalis/nocturnusai:latest
```

```python
from nocturnusai import SyncNocturnusAIClient

with SyncNocturnusAIClient("http://localhost:9300") as ai:
    ai.assert_fact("likes", ["alice", "logic"])
    ai.assert_fact("likes", ["bob", "logic"])
    results = ai.infer("likes", ["?who", "logic"])
    # → [Atom(predicate='likes', args=['alice']), Atom(predicate='likes', args=['bob'])]
```

### Path 3: I use MCP (Cursor, Claude, Windsurf)

Add to your MCP config (`.cursor/mcp.json`, `claude_desktop_config.json`, etc.):

```json
{
  "mcpServers": {
    "nocturnus": {
      "url": "http://localhost:9300/mcp/sse"
    }
  }
}
```

Your agent immediately gets tools: `tell`, `teach`, `ask`, `forget`, `recall`, `context`, `compress`, `cleanup`, `predicates`.

---

## Framework Integrations

NocturnusAI works with every major agent framework:

```bash
pip install nocturnusai[langchain]       # LangChain tools
pip install nocturnusai[crewai]          # CrewAI tools
pip install nocturnusai[autogen]         # AutoGen tools
pip install nocturnusai[langgraph]       # LangGraph checkpoint saver
pip install nocturnusai[openai-agents]   # OpenAI Agents SDK tools
pip install nocturnusai[all]             # Everything
npm install nocturnusai-sdk              # TypeScript/Node.js SDK
```

### LangChain

```python
from nocturnusai import SyncNocturnusAIClient
from nocturnusai.langchain import get_nocturnusai_tools
from langchain_anthropic import ChatAnthropic
from langchain.agents import AgentExecutor, create_tool_calling_agent
from langchain_core.prompts import ChatPromptTemplate

client = SyncNocturnusAIClient("http://localhost:9300")
tools = get_nocturnusai_tools(client)

llm = ChatAnthropic(model="claude-sonnet-4-6")
prompt = ChatPromptTemplate.from_messages([
    ("system", "You are an assistant with a verified knowledge base."),
    ("human", "{input}"),
    ("placeholder", "{agent_scratchpad}"),
])

agent = create_tool_calling_agent(llm, tools, prompt)
executor = AgentExecutor(agent=agent, tools=tools)
result = executor.invoke({"input": "Alice is Bob's parent. Who is Bob's parent?"})
```

### CrewAI

```python
from nocturnusai import SyncNocturnusAIClient
from nocturnusai.crewai import get_nocturnusai_tools
from crewai import Agent, Task, Crew

client = SyncNocturnusAIClient("http://localhost:9300")
tools = get_nocturnusai_tools(client)

reasoner = Agent(
    role="Knowledge Reasoner",
    goal="Answer questions using logical inference",
    backstory="You are an expert at structured reasoning.",
    tools=tools,
)

task = Task(
    description="Alice is Bob's parent. Bob is Charlie's parent. Who is Charlie's grandparent?",
    agent=reasoner,
    expected_output="The grandparent relationship",
)

crew = Crew(agents=[reasoner], tasks=[task])
result = crew.kickoff()
```

### OpenAI Agents SDK

```python
from nocturnusai import SyncNocturnusAIClient
from nocturnusai.openai_agents import get_nocturnusai_tools
from agents import Agent, Runner

client = SyncNocturnusAIClient("http://localhost:9300")
tools = get_nocturnusai_tools(client)

agent = Agent(name="reasoner", instructions="You are a knowledge reasoning agent.", tools=tools)
result = Runner.run_sync(agent, "Store that socrates is human, then ask who is mortal.")
```

### Anthropic Claude

```python
from nocturnusai import SyncNocturnusAIClient
from nocturnusai.anthropic_tools import get_nocturnusai_tool_definitions, handle_tool_call
import anthropic

client = SyncNocturnusAIClient("http://localhost:9300")
tools = get_nocturnusai_tool_definitions()

response = anthropic.Anthropic().messages.create(
    model="claude-sonnet-4-6",
    tools=tools,
    messages=[{"role": "user", "content": "Alice likes Bob. Who likes Bob?"}],
)

for block in response.content:
    if block.type == "tool_use":
        result = handle_tool_call(client, block.name, block.input)
```

[Full integration docs →](https://auctalis.github.io/nocturnusai/docs/integrations)

---

## Documentation

Full docs at **[auctalis.github.io/nocturnusai](https://auctalis.github.io/nocturnusai)**

| | |
|---|---|
| [Quickstart](https://auctalis.github.io/nocturnusai/docs) | Get a working agent in 5 minutes |
| [MCP Integration](https://auctalis.github.io/nocturnusai/docs/mcp) | Connect Cursor, Claude, Windsurf, any MCP client |
| [Python SDK](https://auctalis.github.io/nocturnusai/docs/sdks) | Async client + LangChain/CrewAI/AutoGen tools |
| [API Reference](https://auctalis.github.io/nocturnusai/docs/api) | Every endpoint with request/response shapes |
| [Core Concepts](https://auctalis.github.io/nocturnusai/docs/concepts) | Inference, TMS, temporal facts, salience, NAF |
| [Security & Auth](https://auctalis.github.io/nocturnusai/docs/security) | API keys, RBAC, TLS, encryption at rest |

---

## CLI

The installer downloads a native binary — no JVM required:

```bash
nocturnusai                                # Interactive REPL
nocturnusai -e "tell human(socrates)"      # Single command
nocturnusai -e "ask mortal(?who)"          # Query

# Manual install
# macOS (Apple Silicon)
curl -fsSL https://github.com/Auctalis/nocturnusai/releases/latest/download/nocturnusai-macos-arm64 \
  -o /usr/local/bin/nocturnusai && chmod +x /usr/local/bin/nocturnusai

# Linux (x86_64)
curl -fsSL https://github.com/Auctalis/nocturnusai/releases/latest/download/nocturnusai-linux-x86_64 \
  -o /usr/local/bin/nocturnusai && chmod +x /usr/local/bin/nocturnusai
```

---

## Docker Compose

```bash
git clone https://github.com/Auctalis/nocturnusai.git && cd nocturnusai

docker compose up -d                           # Server only
docker compose --profile monitoring up -d      # + Prometheus + Grafana
docker compose --profile ollama up -d          # + local Ollama LLM
```

---

## Build from Source

Requires JDK 17+.

```bash
./gradlew :nocturnusai-server:run              # HTTP server on :9300
./gradlew :nocturnusai-cli:run                 # Interactive REPL (JVM)
./gradlew :nocturnusai-cli:nativeCompile       # Build native binary
./gradlew test                                  # All 764 tests
```

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Issues labelled `good first issue` are good entry points.

## Security

Report vulnerabilities privately via [GitHub Security Advisories](https://github.com/Auctalis/nocturnusai/security/advisories/new). See [SECURITY.md](SECURITY.md).

## License

Business Source License 1.1 — free for non-production use and internal production use within your organization. Offering NocturnusAI to third parties as a product or service requires a commercial license from [licensing@nocturnus.ai](mailto:licensing@nocturnus.ai). Converts to Apache 2.0 on 2030-02-19. See [LICENSE](LICENSE) and [DISCLAIMER.md](DISCLAIMER.md).
