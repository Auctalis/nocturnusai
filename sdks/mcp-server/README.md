# nocturnusai-mcp

[![nocturnusai MCP server](https://glama.ai/mcp/servers/Auctalis/nocturnusai/badges/score.svg)](https://glama.ai/mcp/servers/Auctalis/nocturnusai)

MCP stdio server for [NocturnusAI](https://nocturnus.ai/) — agent reasoning, memory, and token-optimized context for AI applications. Point any MCP-compatible client (Claude Desktop, Cursor, Windsurf, etc.) at a running NocturnusAI instance and get:

- **Deterministic reasoning** — multi-step inference with proof chains, not LLM guessing
- **Token-optimized context** — salience-ranked retrieval puts only the most relevant facts in your context window, cutting token waste
- **Agent memory lifecycle** — temporal facts, auto-expiration, consolidation, and decay so long-running agents stay focused
- **Truth maintenance** — automatic retraction of derived knowledge when premises change

This package is a thin stdio shim. It forwards MCP requests to a NocturnusAI server's `/mcp` JSON-RPC endpoint. It does not include the server itself.

## Quick start — run a NocturnusAI server

The fastest way to get a server running locally:

```bash
docker run -d --name nocturnusai -p 9300:9300 ghcr.io/auctalis/nocturnusai:latest
```

Data persists to a named Docker volume. Point your MCP client at this instance via the config below.

Alternatives: build from source (`./gradlew :nocturnusai-server:run` from [the repo](https://github.com/Auctalis/nocturnusai)) or deploy to your own infra.

## Client configuration

### Claude Desktop (`claude_desktop_config.json`)

```json
{
  "mcpServers": {
    "nocturnusai": {
      "command": "npx",
      "args": ["-y", "nocturnusai-mcp"],
      "env": {
        "NOCTURNUSAI_URL": "http://localhost:9300"
      }
    }
  }
}
```

### Cursor / Windsurf

Same structure — most clients accept a `command` + `args` + `env` block.

## Environment variables

| Variable              | Default                  | Purpose                                           |
| --------------------- | ------------------------ | ------------------------------------------------- |
| `NOCTURNUSAI_URL`     | `http://localhost:9300`  | Base URL of the NocturnusAI server                |
| `NOCTURNUSAI_API_KEY` | _(none)_                 | Sent as `X-API-Key` when set                      |
| `NOCTURNUSAI_DATABASE`| `default`                | Sent as `X-Database` header                       |
| `NOCTURNUSAI_TENANT`  | `default`                | Sent as `X-Tenant-ID` header                      |

## What tools are available

Whatever tools the upstream NocturnusAI server exposes. As of 0.3.x this includes `tell`, `ask`, `teach`, `forget`, `predicates`, `context`, `aggregate`, `bulk_assert`, `retract_pattern`, `fork_scope`, `merge_scope`, `list_scopes`, `delete_scope`. The shim is transparent — new tools added to the server appear automatically without a package update.

## Design

- `tools/list` and `tools/call` requests are forwarded via HTTP JSON-RPC 2.0 to `${NOCTURNUSAI_URL}/mcp`.
- Tool schemas come from the upstream server. This package doesn't duplicate them.
- Startup prints a warning to stderr if `/health` is unreachable so misconfig fails loudly.

## License

BUSL-1.1
