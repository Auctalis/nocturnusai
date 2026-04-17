# MCP + NocturnusAI — 5-minute wow

Add NocturnusAI as an MCP server in Claude Desktop, Cursor, or Continue. Your MCP client now has a `context` tool that returns a salience-ranked working set of facts each turn — instead of re-reading every prior tool output.

## The problem

MCP clients (Claude Desktop, Cursor, Continue, Windsurf) accumulate tool outputs across the session. After 10 tool calls, the assistant is re-reading every prior tool result on every message. Context window fills up with repetition.

## Before / After

| | Without NocturnusAI MCP | With NocturnusAI MCP |
|---|---|---|
| Tokens per turn | ~1,259 | ~221 |
| Cost per month (1K req/hr, Opus 4, $15/1M) | $13,600 | $2,400 |
| Truth-preserving across retractions | no | yes |

Measured on our [15-turn product-support benchmark](https://nocturnus.ai/benchmark). MCP clients benefit the same way any agent does — the compression happens at the Nocturnus layer.

## Install

### 1. Start NocturnusAI locally

```bash
docker run -d -p 9300:9300 \
  -e EXTRACTION_ENABLED=true \
  -e ANTHROPIC_API_KEY=sk-ant-... \
  ghcr.io/auctalis/nocturnusai:latest
```

### 2. Add this to your MCP client config

```bash
cp config.json ~/Library/Application\ Support/Claude/mcp_servers/nocturnus.json
# or for Cursor: cp config.json ~/.cursor/mcp_servers/nocturnus.json
# or for Continue: cp config.json ~/.continue/mcp_servers/nocturnus.json
```

### 3. Restart your MCP client

You'll see the Nocturnus tools appear: `tell`, `ask`, `teach`, `forget`, `context`, `predicates`, `bulk_assert`, `fork_scope`, `merge_scope`, `list_scopes`, `aggregate`.

## Try it

Open a new conversation and ask the assistant:

```
Use the tell tool to remember that customer_tier(acme, enterprise) and
contract_value(acme, 2000000). Then use context to see what you know.
```

The assistant will call `tell` twice, then `context` — and receive a salience-ranked working set instead of the full transcript.

## The config

See [`config.json`](./config.json). It points to the SSE endpoint at `http://localhost:9300/mcp/sse` and sets the default tenant to `default`. Adjust as needed.

## Walkthrough

See [`example-session.md`](./example-session.md) for a full 10-turn Claude Desktop session with before/after token counts per turn.

## Next step

- [Full MCP docs](https://nocturnus.ai/docs/mcp)
- [Benchmark methodology](https://nocturnus.ai/benchmark)
- [Other framework examples →](https://nocturnus.ai/examples)
