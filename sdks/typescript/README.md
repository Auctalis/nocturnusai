# nocturnusai-sdk

TypeScript/JavaScript SDK for [NocturnusAI](https://github.com/Auctalis/nocturnusai) — a logic-based inference engine and knowledge database for agentic AI systems.

## Install

```bash
npm install nocturnusai-sdk
```

## Quick start

```typescript
import { NocturnusAIClient } from "nocturnusai-sdk";

const client = new NocturnusAIClient("http://localhost:9300");

// Store facts
await client.tell("likes(alice, bob)");
await client.tell("likes(bob, alice)");

// Define rules
await client.teach("friends(?x, ?y) :- likes(?x, ?y), likes(?y, ?x)");

// Run inference
const results = await client.ask("friends(?x, ?y)");
console.log(results); // ['friends(alice, bob)', 'friends(bob, alice)']

// Recall recent memory
const context = await client.context({ limit: 10 });
```

## With authentication

```typescript
const client = new NocturnusAIClient("http://localhost:9300", {
  apiKey: "your-api-key",
  database: "mydb",
  tenantId: "tenant-1",
});
```

## MCP (Model Context Protocol)

```typescript
import { NocturnusAIMCPClient } from "nocturnusai-sdk";

const mcp = new NocturnusAIMCPClient("http://localhost:9300");
await mcp.initialize();

const tools = await mcp.listTools();
const result = await mcp.callTool("tell", { statement: "likes(alice, bob)" });
```

Or configure via `mcp-config.json` for Claude Desktop, Cursor, Windsurf, and VS Code — see [`mcp-configs/`](https://github.com/Auctalis/nocturnusai/tree/main/mcp-configs) in the main repo.

## OpenAI function calling

```typescript
import OpenAI from "openai";
import { NocturnusAIClient } from "nocturnusai-sdk";

const client = new NocturnusAIClient("http://localhost:9300");
const openai = new OpenAI();

const tools = [
  {
    type: "function" as const,
    function: {
      name: "remember",
      description: "Store a fact in the knowledge base",
      parameters: {
        type: "object",
        properties: { statement: { type: "string" } },
        required: ["statement"],
      },
    },
  },
  {
    type: "function" as const,
    function: {
      name: "recall",
      description: "Query the knowledge base",
      parameters: {
        type: "object",
        properties: { query: { type: "string" } },
        required: ["query"],
      },
    },
  },
];

// tool_calls loop — see demos/llm/typescript/02_openai_tools.ts for full example
```

## Starting NocturnusAI

```bash
# Docker (recommended)
docker run -p 9300:9300 ghcr.io/auctalis/nocturnusai:latest

# Or one-line install
curl -fsSL https://raw.githubusercontent.com/Auctalis/nocturnusai/main/install.sh | bash
```

## Documentation

- [Full API reference](https://github.com/Auctalis/nocturnusai/tree/main/sdks/typescript)
- [Demos and examples](https://github.com/Auctalis/nocturnusai/tree/main/demos)
- [MCP configuration](https://github.com/Auctalis/nocturnusai/tree/main/mcp-configs)
