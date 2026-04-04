# NocturnusAI Demos

NocturnusAI is a **reasoning memory backend for AI agents** — structured facts,
Horn clause rules, backward-chaining inference, salience-ranked retrieval, and
temporal awareness, all accessible via HTTP, MCP, and client SDKs.

## Start the server

```bash
./gradlew :nocturnusai-server:run   # port 9300
# or
docker-compose up --build
```

---

## ⭐ Featured: LLM & Agent Integration [`llm/`](./llm/)

These are the primary demos. Start here.

| Demo | Language | What it shows |
|------|----------|---------------|
| [LangChain agent](./llm/python/01_langchain_agent.py) | Python | `get_nocturnusai_tools()` → 4 tools wired into a ReAct agent |
| [MCP protocol](./llm/python/02_mcp_client.py) | Python | `NocturnusAIMCPClient` — how Claude/GPT connect natively |
| [OpenAI function calling](./llm/python/03_openai_tools.py) | Python | NocturnusAI as `remember`/`recall`/`reason`/`working_memory` tools |
| [Agent memory lifecycle](./llm/python/04_agent_memory.py) | Python | The canonical "why NocturnusAI" demo — 3 agent sessions |
| [MCP protocol](./llm/typescript/01_mcp_client.ts) | TypeScript | `NocturnusAIMCPClient` — tool discovery + simulated LLM loop |
| [OpenAI function calling](./llm/typescript/02_openai_tools.ts) | TypeScript | Same OpenAI tool pattern, TypeScript |
| [Agent memory lifecycle](./llm/typescript/03_agent_memory.ts) | TypeScript | Full agent memory lifecycle in TypeScript |

### Quickstart (5 minutes, no API key needed)

```bash
# Python — MCP protocol demo
cd demos/llm/python
pip install nocturnusai
python 02_mcp_client.py

# Python — canonical agent memory demo
python 04_agent_memory.py

# TypeScript — MCP protocol demo
cd demos/llm/typescript
npm install && npx ts-node 01_mcp_client.ts

# TypeScript — agent memory lifecycle
npx ts-node 03_agent_memory.ts
```

### With an LLM API key

```bash
export OPENAI_API_KEY=sk-...

# LangChain ReAct agent
python demos/llm/python/01_langchain_agent.py

# OpenAI function calling loop
python demos/llm/python/03_openai_tools.py
```

---

## Advanced / Deep-Dive [`advanced/`](./advanced/)

Raw SDK mechanics — the building blocks powering the LLM demos above.

### Python SDK

```bash
cd demos/advanced/python && pip install nocturnusai
python 01_basics.py                # assert_fact, query, infer, retract
python 02_rules_and_inference.py   # rules, proof trees, transitive closure
python 03_memory_management.py     # context_window, temporal, consolidate, decay
python 04_transactions.py          # ACID transactions
python 05_auth_and_keys.py         # key management (AUTH_ENABLED=true)
python 06_dsl_execute.py           # Logiql DSL
python 08_agent_workflow.py        # full async agent workflow
```

### TypeScript SDK

```bash
cd demos/advanced/typescript && npm install
npx ts-node 01_basics.ts
npx ts-node 06_events_sse.ts       # real-time SSE event stream
npx ts-node 07_agent_workflow.ts
```

---

## Raw HTTP [`curl/`](./curl/)

Every major REST endpoint as `curl` one-liners.

```bash
bash demos/curl/examples.sh
```

---

## Why NocturnusAI for agents?

| Problem | NocturnusAI solution |
|---------|----------------------|
| Agent forgets between turns | Persistent structured knowledge base |
| RAG returns irrelevant blobs | Salience-ranked `context_window` |
| Can't derive new facts | Horn clause rules + backward chaining |
| No temporal awareness | `validFrom` / `validUntil` / `ttl` on every fact |
| Episodic repetition | `consolidate()` compresses into semantic memory |
| Stale knowledge accumulates | `decay()` evicts expired / low-salience facts |
| LLM can't find the tools | Native MCP at `POST /mcp` — zero glue code |
