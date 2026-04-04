# nocturnusai-sdk

TypeScript/JavaScript SDK for [NocturnusAI](https://github.com/Auctalis/nocturnusai).

The main workflow is context management for real agent threads:

- `contextWindow()` for a fast salience-ranked working set
- `optimizeContext()` for goal-driven assembly
- `diffContext()` for later-turn deltas
- `clearContextSession()` when the thread ends
- `ingestAndOptimize()` when you want one-shot text ingestion plus optimization

## Install

```bash
npm install nocturnusai-sdk
```

## Context-first quick start

```ts
import { NocturnusAIClient } from 'nocturnusai-sdk';

const client = new NocturnusAIClient({
  baseUrl: 'http://localhost:9300',
  tenantId: 'default',
});

const optimized = await client.optimizeContext({
  goals: [{ predicate: 'eligible_for_sla', args: ['acme_corp'] }],
  maxFacts: 12,
  sessionId: 'ticket-42',
});

const diff = await client.diffContext({
  sessionId: 'ticket-42',
  maxFacts: 12,
});

await client.clearContextSession('ticket-42');
console.log(optimized.totalFactsIncluded, diff.added.length);
```

## One-shot ingestion

```ts
import { NocturnusAIClient } from 'nocturnusai-sdk';

const client = new NocturnusAIClient({ baseUrl: 'http://localhost:9300' });

const result = await client.ingestAndOptimize({
  text: `
user: Customer says they are enterprise and blocked on SLA credits.
tool: CRM says account is Acme Corp with a 2M ARR contract.
  `,
  goals: [{ predicate: 'eligible_for_sla', args: ['acme_corp'] }],
  maxFacts: 12,
  sessionId: 'ticket-42',
});

console.log(result.context.totalCharCount);
```

## Lower-level logic methods

```ts
import { NocturnusAIClient } from 'nocturnusai-sdk';

const client = new NocturnusAIClient({ baseUrl: 'http://localhost:9300' });

await client.assertFact('parent', ['alice', 'bob']);
await client.assertRule(
  { predicate: 'grandparent', args: ['?x', '?z'] },
  [
    { predicate: 'parent', args: ['?x', '?y'] },
    { predicate: 'parent', args: ['?y', '?z'] },
  ],
);

const results = await client.infer('grandparent', ['?who', 'bob']);
console.log(results);
```

## MCP helper

```ts
import { NocturnusAIMCPClient } from 'nocturnusai-sdk';

const mcp = new NocturnusAIMCPClient({
  baseUrl: 'http://localhost:9300',
  tenantId: 'default',
});

await mcp.initialize();
const tools = await mcp.listTools();
const result = await mcp.callTool('context', { maxFacts: 10, minSalience: 0.1 });
console.log(result.content[0]?.text);
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
