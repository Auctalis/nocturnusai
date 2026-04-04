# NocturnusAI MCP Configs

Drop-in config files for connecting any MCP-compatible agent to NocturnusAI.

Use MCP when your runtime already speaks tool calling and you want a fast working set every turn. The MCP `context` tool returns salience-ranked facts. For goal-driven windows and diffs, pair MCP with the HTTP context endpoints.

**Endpoint**: `http://localhost:9300/mcp/sse`
**Transport**: SSE (Server-Sent Events)
**Protocol**: MCP 2025-11-25

---

## Recommended loop

1. Connect your agent to `GET /mcp/sse`
2. Call MCP tool `context` for the current working set
3. Call `POST /context/optimize` from your app when the next question is goal-specific
4. Call `POST /context/diff` on later turns with the same `sessionId`
5. Call `POST /context/session/clear` when the thread ends

---

## Quick start (local, no auth)

| Agent / IDE | Config file | Where it goes |
|-------------|-------------|---------------|
| Claude Desktop | `claude-desktop.json` | see below |
| Cursor | `cursor.json` | `.cursor/mcp.json` in your project or `~/.cursor/mcp.json` |
| Windsurf | `windsurf.json` | `~/.codeium/windsurf/mcp_config.json` |
| VS Code Copilot | `vscode.json` | `.vscode/mcp.json` |
| Claude Code CLI | - | `claude mcp add` |

All files point to `http://localhost:9300/mcp/sse` with auth disabled.

---

## Per-agent setup

### Claude Desktop

**macOS**: `~/Library/Application Support/Claude/claude_desktop_config.json`
**Windows**: `%APPDATA%\Claude\claude_desktop_config.json`

```bash
cp mcp-configs/claude-desktop.json \
  ~/Library/Application\ Support/Claude/claude_desktop_config.json
```

Restart Claude Desktop.

### Cursor

```bash
mkdir -p .cursor
cp mcp-configs/cursor.json .cursor/mcp.json
```

### Windsurf

```bash
cp mcp-configs/windsurf.json ~/.codeium/windsurf/mcp_config.json
```

### VS Code (GitHub Copilot)

```bash
mkdir -p .vscode
cp mcp-configs/vscode.json .vscode/mcp.json
```

### Claude Code CLI

```bash
claude mcp add nocturnusai \
  --transport sse \
  --url http://localhost:9300/mcp/sse \
  --header "X-Database: default" \
  --header "X-Tenant-ID: default"

claude mcp list
```

---

## Goal-driven context is HTTP

MCP gives you this directly:

```json
{
  "method": "tools/call",
  "params": {
    "name": "context",
    "arguments": {
      "maxFacts": 25,
      "minSalience": 0.15
    }
  }
}
```

Notes:

- `minSalience` is the primary threshold field.
- `minRelevance` is accepted as a legacy alias for clients that still surface the older schema label.
- MCP also accepts the legacy tool alias `context_window`.

Use companion HTTP calls for the full optimization pipeline:

```bash
curl -X POST http://localhost:9300/context/optimize \
  -H 'Content-Type: application/json' \
  -H 'X-Tenant-ID: default' \
  -d '{
    "goals":[{"predicate":"eligible_for_sla","args":["acme_corp"]}],
    "maxFacts":25,
    "sessionId":"ticket-42"
  }'
```

---

## Auth-enabled servers

If your server has `AUTH_ENABLED=true`, use `with-auth.json`.

Bootstrap the first admin key with the configured bootstrap credentials:

```bash
curl -s -X POST http://localhost:9300/auth/bootstrap \
  -H 'Content-Type: application/json' \
  -d '{
    "username":"admin",
    "password":"nocturnusai",
    "keyName":"my-agent"
  }' | jq .key
```

Create subsequent keys with the admin key:

```bash
curl -s -X POST http://localhost:9300/auth/keys \
  -H 'Content-Type: application/json' \
  -H 'X-API-Key: ADMIN_KEY' \
  -d '{"name":"cursor-agent","role":"writer"}' | jq .key
```

Then replace `YOUR_API_KEY_HERE` in `with-auth.json`.

---

## Production / remote server

Edit `production.json` with your hostname and headers:

```json
{
  "mcpServers": {
    "nocturnusai": {
      "url": "https://your-server.example.com/mcp/sse",
      "transport": "sse",
      "headers": {
        "X-Database": "your-db",
        "X-Tenant-ID": "your-tenant",
        "X-API-Key": "your-key"
      }
    }
  }
}
```

---

## Core tools you will actually use first

| Tool | Use it for |
|------|------------|
| `context` | Salience-ranked working set for the current turn |
| `tell` | Store a fact |
| `ask` | Run inference |
| `teach` | Add a rule |
| `forget` | Retract a fact |
| `recall` | Time-travel query |
| `compress` | Simplified alias for consolidation |
| `cleanup` | Simplified alias for decay |

Variable syntax in tool args uses `?x`, `?who`, and similar wildcards.

---

## Verify the connection

```bash
curl http://localhost:9300/health | jq .status

curl -s -X POST http://localhost:9300/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' \
  | jq '.result.tools[].name'
```
