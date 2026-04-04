# NocturnusAI Python SDK

Python SDK for [NocturnusAI](https://github.com/Auctalis/nocturnusai).

The primary use case is context reduction for agent applications:

1. ingest raw text or turns
2. build a smaller working set
3. narrow by goal
4. reuse diffs on later turns

Lower-level fact, rule, inference, and memory APIs are still available when you need backend mechanics.

## Install

```bash
pip install nocturnusai
pip install "nocturnusai[langchain]"   # optional LangChain tools
```

## Context-first quick start

```python
from nocturnusai import SyncNocturnusAIClient

with SyncNocturnusAIClient("http://localhost:9300") as client:
    ctx = client.ingest_and_optimize(
        text="""
        user: Customer says they are enterprise and blocked on SLA credits.
        tool: CRM says account is Acme Corp with a 2M ARR contract.
        tool: Billing note says renewal is due next month.
        """,
        goals=[{"predicate": "eligible_for_sla", "args": ["acme_corp"]}],
        max_facts=12,
        session_id="ticket-42",
    )

    diff = client.diff_context(
        session_id="ticket-42",
        goals=[{"predicate": "eligible_for_sla", "args": ["acme_corp"]}],
        max_facts=12,
    )

    client.clear_context_session("ticket-42")
    print(ctx.total_facts_included, len(diff.added))
```

## Key context methods

- `context_window()` -> `POST /memory/context`
- `optimize_context()` -> `POST /context/optimize`
- `diff_context()` -> `POST /context/diff`
- `summarize_context()` -> `POST /context/summary`
- `clear_context_session()` -> `POST /context/session/clear`
- `ingest_and_optimize()` -> extract text, assert, then optimize

## Lower-level logic methods

```python
from nocturnusai import SyncNocturnusAIClient

with SyncNocturnusAIClient("http://localhost:9300") as client:
    client.assert_fact("parent", ["alice", "bob"])
    client.assert_rule(
        head={"predicate": "grandparent", "args": ["?x", "?z"]},
        body=[
            {"predicate": "parent", "args": ["?x", "?y"]},
            {"predicate": "parent", "args": ["?y", "?z"]},
        ],
    )
    print(client.infer("grandparent", ["?who", "bob"]))
```

## LangChain integration

```python
from nocturnusai import SyncNocturnusAIClient
from nocturnusai.langchain import get_nocturnusai_tools

client = SyncNocturnusAIClient("http://localhost:9300")
tools = get_nocturnusai_tools(client)
```

## MCP helper

```python
from nocturnusai.mcp import NocturnusAIMCPClient

async with NocturnusAIMCPClient("http://localhost:9300") as mcp:
    await mcp.initialize()
    tools = await mcp.list_tools()
    result = await mcp.call_tool("context", {"maxFacts": 10, "minSalience": 0.1})
    print(result.text)
```

## Start the server

```bash
# Docker
docker run -p 9300:9300 ghcr.io/auctalis/nocturnusai:latest

# or installer
curl -fsSL https://raw.githubusercontent.com/Auctalis/nocturnusai/main/install.sh | bash
```

## Docs

- [Docs site](https://auctalis.github.io/nocturnusai/docs)
- [SDK docs](https://auctalis.github.io/nocturnusai/docs/sdks)
- [MCP configs](https://github.com/Auctalis/nocturnusai/tree/main/mcp-configs)
- [Demos](https://github.com/Auctalis/nocturnusai/tree/main/demos)
