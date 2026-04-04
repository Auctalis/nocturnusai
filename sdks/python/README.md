# NocturnusAI Python SDK

Python SDK for [NocturnusAI](https://github.com/Auctalis/nocturnusai) — a logic-based inference engine and knowledge database for agentic AI systems.

## Install

```bash
pip install nocturnusai
```

With LangChain integration:
```bash
pip install "nocturnusai[langchain]"
```

## Quick start

```python
import asyncio
from nocturnusai import NocturnusAIClient

async def main():
    async with NocturnusAIClient("http://localhost:9300") as client:
        # Store facts
        await client.tell("likes(alice, bob)")
        await client.tell("likes(bob, carol)")

        # Define rules
        await client.teach("friends(?x, ?y) :- likes(?x, ?y), likes(?y, ?x)")

        # Run inference
        results = await client.ask("friends(?x, ?y)")
        print(results)  # ['friends(alice, bob)', 'friends(bob, alice)']

asyncio.run(main())
```

Sync usage:
```python
from nocturnusai import SyncNocturnusAIClient

with SyncNocturnusAIClient("http://localhost:9300") as client:
    client.tell("likes(alice, bob)")
    print(client.ask("likes(?x, bob)"))
```

## LangChain integration

```python
from langchain_openai import ChatOpenAI
from langchain.agents import create_react_agent, AgentExecutor
from nocturnusai.langchain import get_nocturnusai_tools

tools = get_nocturnusai_tools("http://localhost:9300")
llm = ChatOpenAI(model="gpt-4o")
agent = AgentExecutor(agent=create_react_agent(llm, tools, prompt), tools=tools)
```

## MCP (Model Context Protocol)

```python
from nocturnusai import NocturnusAIMCPClient

async with NocturnusAIMCPClient("http://localhost:9300") as mcp:
    await mcp.initialize()
    tools = await mcp.list_tools()
    result = await mcp.call_tool("tell", {"statement": "likes(alice, bob)"})
```

Or configure via `mcp-config.json` for Claude Desktop, Cursor, Windsurf, and VS Code — see [`mcp-configs/`](https://github.com/Auctalis/nocturnusai/tree/main/mcp-configs) in the main repo.

## Starting NocturnusAI

```bash
# Docker (recommended)
docker run -p 9300:9300 ghcr.io/auctalis/nocturnusai:latest

# Or one-line install
curl -fsSL https://raw.githubusercontent.com/Auctalis/nocturnusai/main/install.sh | bash
```

## Documentation

- [Full API reference](https://github.com/Auctalis/nocturnusai/blob/main/sdks/python)
- [Demos and examples](https://github.com/Auctalis/nocturnusai/tree/main/demos)
- [MCP configuration](https://github.com/Auctalis/nocturnusai/tree/main/mcp-configs)
