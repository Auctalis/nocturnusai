# NocturnusAI Python SDK

Python SDK for [NocturnusAI](https://github.com/Auctalis/nocturnusai) — a logic-based inference engine and knowledge database for AI agents.

## Install

```bash
pip install nocturnusai
pip install "nocturnusai[langchain]"   # optional LangChain tools
```

## Start the server

```bash
# Recommended — installs CLI, configures LLM provider, starts server
curl -fsSL https://raw.githubusercontent.com/Auctalis/nocturnusai/main/install.sh | bash
nocturnusai setup

# Or bare Docker (logic engine works; context extraction requires LLM config — see below)
docker run -p 9300:9300 ghcr.io/auctalis/nocturnusai:latest
```

Verify it's running:

```bash
curl http://localhost:9300/health
```

## Quick start — facts, rules, and inference

This works immediately with a plain server — no LLM configuration needed.

```python
from nocturnusai import SyncNocturnusAIClient

with SyncNocturnusAIClient("http://localhost:9300") as client:
    # Assert facts
    client.assert_fact("parent", ["alice", "bob"])
    client.assert_fact("parent", ["bob", "charlie"])

    # Teach a rule: grandparent(?x, ?z) :- parent(?x, ?y), parent(?y, ?z)
    client.assert_rule(
        head={"predicate": "grandparent", "args": ["?x", "?z"]},
        body=[
            {"predicate": "parent", "args": ["?x", "?y"]},
            {"predicate": "parent", "args": ["?y", "?z"]},
        ],
    )

    # Infer — the engine finds alice is grandparent of charlie
    results = client.infer("grandparent", ["?who", "charlie"])
    for atom in results:
        print(f"{atom.predicate}({', '.join(atom.args)})")
        # grandparent(alice, charlie)

    # Query — exact pattern match without inference
    parents = client.query("parent", ["?x", "?y"])
    print(f"{len(parents)} parent facts")  # 2 parent facts

    # Retract a fact
    client.retract("parent", ["bob", "charlie"])
```

## Context window — salience-ranked retrieval

Use the context methods to retrieve the most relevant facts for an agent's working memory. This works with any facts you've asserted — no LLM needed.

```python
from nocturnusai import SyncNocturnusAIClient

with SyncNocturnusAIClient("http://localhost:9300") as client:
    client.assert_fact("customer_tier", ["acme_corp", "enterprise"])
    client.assert_fact("contract_value", ["acme_corp", "2000000"])
    client.assert_fact("issue", ["acme_corp", "sla_credits_blocked"])

    # Simple salience-ranked window
    ctx = client.context(max_facts=10)
    for f in ctx.facts:
        print(f"{f.predicate}({', '.join(f.args)})")

    # Goal-driven optimization — backward chaining narrows to relevant facts
    ctx = client.context(
        max_facts=10,
        goals=[{"predicate": "customer_tier", "args": ["acme_corp", "?tier"]}],
        session_id="ticket-42",
    )
    print(f"Window has {ctx.window_size} facts out of {ctx.total_available} available")
```

## Context reduction from raw text

> **Requires LLM.** The context extraction workflow uses an LLM to convert raw text into structured facts. You **must** configure an LLM provider or this will time out / return empty results.
>
> The simplest path: run `nocturnusai setup` — it auto-configures Ollama. Or set env vars manually:
> - **Ollama (local):** `LLM_BASE_URL=http://host.docker.internal:11434/v1` + `LLM_MODEL=granite3.3:8b`
> - **OpenAI:** `OPENAI_API_KEY=sk-...`
> - **Anthropic:** `ANTHROPIC_API_KEY=sk-ant-...`

```bash
# Recommended: use the setup wizard (auto-detects Ollama)
nocturnusai setup

# Or manual Docker with Ollama:
docker run -p 9300:9300 \
  -e LLM_BASE_URL=http://host.docker.internal:11434/v1 \
  -e LLM_MODEL=granite3.3:8b \
  ghcr.io/auctalis/nocturnusai:latest
```

```python
from nocturnusai import SyncNocturnusAIClient

with SyncNocturnusAIClient("http://localhost:9300") as client:
    # Extract facts from raw text, assert them, and get an optimized context window
    ctx = client.ingest_and_optimize(
        text="""
        user: Customer says they are enterprise and blocked on SLA credits.
        tool: CRM says account is Acme Corp with a 2M ARR contract.
        tool: Billing note says renewal is due next month.
        """,
        max_facts=12,
        session_id="ticket-42",
    )
    print(f"Extracted {ctx.total_facts_included} facts from raw text")
    for entry in ctx.entries:
        print(f"  {entry.predicate}({', '.join(entry.args)})")

    # On the next turn, get only what changed (incremental diff)
    diff = client.diff_context(session_id="ticket-42", max_facts=12)
    print(f"Diff: +{len(diff.added)} / -{len(diff.removed)}")

    # Clean up the session when the conversation ends
    client.clear_context_session("ticket-42")
```

## Key context methods

| Method | Endpoint | LLM required? |
|--------|----------|---------------|
| `context()` | `POST /memory/context` | No |
| `diff_context()` | `POST /context/diff` | No |
| `summarize_context()` | `POST /context/summary` | No |
| `clear_context_session()` | `POST /context/session/clear` | No |
| `ingest_and_optimize()` | extract + optimize | **Yes** |

## Named databases

Use the `database` parameter to isolate data. Call `ensure_database()` to create the database and its default tenant on the server.

```python
with SyncNocturnusAIClient("http://localhost:9300", database="my-project") as client:
    client.ensure_database()
    client.assert_fact("status", ["ready"])
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

## Docs

- [Docs site](https://nocturnus.ai/docs)
- [SDK docs](https://nocturnus.ai/docs/sdks)
- [MCP configs](https://github.com/Auctalis/nocturnusai/tree/main/mcp-configs)
- [Demos](https://github.com/Auctalis/nocturnusai/tree/main/demos)
