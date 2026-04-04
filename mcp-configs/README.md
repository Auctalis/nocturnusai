# NocturnusAI MCP Configs

Drop-in config files for connecting any MCP-compatible agent to NocturnusAI.

**Endpoint**: `http://localhost:9300/mcp/sse`
**Transport**: SSE (Server-Sent Events)
**Protocol**: MCP 2025-11-25

---

## Quick start (no auth, local server)

Start the server, then copy the right file for your agent:

| Agent / IDE | Config file | Where it goes |
|-------------|-------------|---------------|
| Claude Desktop | `claude-desktop.json` | see below |
| Cursor | `cursor.json` | `.cursor/mcp.json` in your project (or `~/.cursor/mcp.json` globally) |
| Windsurf | `windsurf.json` | `~/.codeium/windsurf/mcp_config.json` |
| VS Code Copilot | `vscode.json` | `.vscode/mcp.json` in your project |
| Claude Code CLI | — | `claude mcp add` (see below) |

All files connect to `http://localhost:9300` with no API key (auth disabled by default).

---

## Per-agent setup

### Claude Desktop

Copy `claude-desktop.json` into the Claude Desktop config file, merging the
`mcpServers` block with any existing entries:

**macOS**: `~/Library/Application Support/Claude/claude_desktop_config.json`
**Windows**: `%APPDATA%\Claude\claude_desktop_config.json`

```bash
# macOS — copy and open
cp mcp-configs/claude-desktop.json \
   ~/Library/Application\ Support/Claude/claude_desktop_config.json
```

Then restart Claude Desktop. NocturnusAI tools appear as `tell`, `ask`, `teach`,
`forget`, `recall`, `context`, `compress`, `cleanup`, `predicates`.

---

### Cursor

```bash
# Per-project (recommended)
mkdir -p .cursor
cp mcp-configs/cursor.json .cursor/mcp.json

# Or globally
cp mcp-configs/cursor.json ~/.cursor/mcp.json
```

Reload the Cursor window. NocturnusAI will appear in the MCP tools panel.

---

### Windsurf

```bash
cp mcp-configs/windsurf.json ~/.codeium/windsurf/mcp_config.json
```

Restart Windsurf.

---

### VS Code (GitHub Copilot)

```bash
mkdir -p .vscode
cp mcp-configs/vscode.json .vscode/mcp.json
```

VS Code uses `"servers"` (not `"mcpServers"`) and `"type"` (not `"transport"`).
The `vscode.json` file is already formatted correctly for this.

---

### Claude Code CLI

```bash
# Add nocturnusai as an MCP server
claude mcp add nocturnusai \
  --transport sse \
  --url http://localhost:9300/mcp/sse \
  --header "X-Database: default" \
  --header "X-Tenant-ID: default"

# Verify
claude mcp list
```

---

## Auth-enabled servers

If your server has `AUTH_ENABLED=true`, use `with-auth.json` instead.
Replace `YOUR_API_KEY_HERE` with a key obtained from:

```bash
# First time — bootstrap admin key
curl -s -X POST http://localhost:9300/auth/bootstrap \
  -H 'Content-Type: application/json' \
  -d '{"name":"my-agent"}' | jq .key

# Subsequent keys — use admin key
curl -s -X POST http://localhost:9300/auth/keys \
  -H 'Content-Type: application/json' \
  -H 'X-API-Key: ADMIN_KEY' \
  -d '{"name":"cursor-agent","role":"writer"}' | jq .key
```

Then edit `with-auth.json` and copy it to the right location for your agent.

---

## Production / remote server

Edit `production.json` with your actual hostname and credentials:

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

## Available tools

Once connected, your agent has access to:

| Tool | What it does |
|------|-------------|
| `tell` | Store a fact: `tell(predicate, args)` — supports TTL and temporal bounds |
| `teach` | Define a rule: `teach(head, body)` — Horn clause, enables automatic inference |
| `ask` | Run inference: `ask(predicate, args)` — multi-hop backward chaining, optional proof tree |
| `forget` | Retract a fact: `forget(predicate, args)` — cascades via Truth Maintenance System |
| `recall` | Time-travel query: `recall(predicate, args, timestamp)` — what was true at a given moment |
| `context` | Working memory: `context(maxFacts, predicates)` — salience-ranked facts for reasoning |
| `compress` | Consolidate: `compress()` — collapse episodic patterns into semantic memory |
| `cleanup` | Decay: `cleanup(threshold)` — evict expired and low-salience facts |
| `predicates` | Schema: `predicates()` — list all stored predicate types |

Variable syntax: use `?x`, `?who`, `?anything` as wildcards in `args`.

---

## Verify the connection

```bash
# Health check
curl http://localhost:9300/health | jq .status

# List MCP tools (JSON-RPC)
curl -s -X POST http://localhost:9300/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' \
  | jq '.result.tools[].name'
```
