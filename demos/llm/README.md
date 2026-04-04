# LLM & Agent Integration Demos

These are the primary demos.

They start from the real application problem: an agent has a messy thread state and needs a smaller, more relevant context window before the next model call.

NocturnusAI can then do two related jobs:

1. give the agent a smaller working set
2. provide deterministic backend reasoning behind that working set

## Quick start

```bash
# Python
cd demos/llm/python
pip install nocturnusai
python 01_langchain_agent.py

# TypeScript
cd ../typescript
npm install
npx ts-node 01_mcp_client.ts
```

---

## Python demos

| File | What it demonstrates |
|------|----------------------|
| `01_langchain_agent.py` | LangChain agent wired to Nocturnus tools |
| `02_mcp_client.py` | MCP discovery and tool execution |
| `03_openai_tools.py` | OpenAI tool loop over Nocturnus operations |
| `04_agent_memory.py` | The context story: learn state, retrieve the right subset, manage memory over time |

## TypeScript demos

| File | What it demonstrates |
|------|----------------------|
| `01_mcp_client.ts` | MCP discovery and tool execution |
| `02_openai_tools.ts` | OpenAI tool loop in TypeScript |
| `03_agent_memory.ts` | TypeScript version of the memory lifecycle story |

---

## The story in one paragraph

An agent should not keep replaying the full transcript every turn. NocturnusAI sits between the raw thread and the model call, turns messy state into a smaller working set, and then supports deterministic facts, rules, temporal recall, and memory cleanup behind that workflow.

For raw SDK mechanics, see [../advanced/](../advanced/).
