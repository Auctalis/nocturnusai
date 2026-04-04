# LLM & Agent Integration — Featured Demos

These are the primary demos. They show how AI agents use NocturnusAI as their
reasoning memory backend — via LangChain, MCP, OpenAI function calling, or direct
async client code.

## Quick start

```bash
# Python
cd demos/llm/python
pip install nocturnusai          # or: pip install -e ../../../sdks/python
python 01_langchain_agent.py

# TypeScript
cd demos/llm/typescript
npm install
npx ts-node 01_mcp_client.ts
```

---

## Python

| File | What it demonstrates |
|------|----------------------|
| `01_langchain_agent.py` | **LangChain ReAct agent** — `get_nocturnusai_tools()` wires all 4 tools (Assert, Query, Infer, Context) into a LangChain agent. Direct tool execution shown first (no API key), then a live ReAct loop. |
| `02_mcp_client.py` | **MCP protocol** — `NocturnusAIMCPClient`: `initialize()`, `list_tools()`, `call_tool()`. Shows server capability discovery, all tool definitions the LLM sees, and a simulated tool-use decision loop. |
| `03_openai_tools.py` | **OpenAI function calling** — NocturnusAI operations defined as OpenAI tool schemas. Full `tool_calls` loop: model picks tools → execute → feed results back → final answer. Works with any OpenAI-compatible API. |
| `04_agent_memory.py` | **Canonical agent memory demo** — 3 simulated sessions: onboarding (learn user), task planning (reason over memory), review (temporal queries, TTL, decay). The "why NocturnusAI" story. |

### Running Python demos

```bash
pip install nocturnusai
# Optional: pip install langchain langchain-openai openai

python 01_langchain_agent.py     # requires OPENAI_API_KEY for agent loop
python 02_mcp_client.py          # no API key needed
python 03_openai_tools.py        # requires OPENAI_API_KEY for agent loop
python 04_agent_memory.py        # no API key needed
```

---

## TypeScript

| File | What it demonstrates |
|------|----------------------|
| `01_mcp_client.ts` | **MCP protocol** — `NocturnusAIMCPClient`: `initialize()`, `listTools()`, `callTool()`. Full tool discovery and a simulated LLM tool-selection loop. |
| `02_openai_tools.ts` | **OpenAI function calling** — NocturnusAI as `remember` / `recall` / `reason` / `working_memory` tools. Full `tool_calls` loop with live agent (optional). |
| `03_agent_memory.ts` | **Canonical agent memory demo** — same 3-session story as the Python version. Onboarding → reasoning → temporal review. |

### Running TypeScript demos

```bash
cd demos/llm/typescript
npm install
npx ts-node 01_mcp_client.ts
npx ts-node 02_openai_tools.ts   # requires OPENAI_API_KEY for agent loop
npx ts-node 03_agent_memory.ts
```

---

## The story in one paragraph

> An AI agent needs memory that is **structured** (not just text blobs),
> **reasoned** (facts + rules → inference), **temporal** (what was true _when_),
> and **ranked** (most relevant facts first). NocturnusAI provides all of that
> via a single HTTP API, exposed natively as MCP tools so any LLM can use it
> without custom glue code.

For raw SDK mechanics, see [../advanced/](../advanced/).
