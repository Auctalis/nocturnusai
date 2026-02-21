# TypeScript SDK Demos

## Setup

```bash
cd demos/typescript
npm install
```

Make sure the server is running at `http://localhost:9300`.

## Running

```bash
npx ts-node 01_basics.ts
npx ts-node 02_rules_and_inference.ts
npx ts-node 03_memory_management.ts
npx ts-node 04_transactions.ts
npx ts-node 05_auth_and_keys.ts    # requires AUTH_ENABLED=true for full demo
npx ts-node 06_events_sse.ts
npx ts-node 07_agent_workflow.ts

# Or via npm scripts:
npm run basics
npm run rules
npm run memory
npm run transactions
npm run auth
npm run events
npm run agent
```

## Files

| File | Concepts |
|------|----------|
| `01_basics.ts` | `assertFact`, `query`, `infer`, `retract`, negated facts |
| `02_rules_and_inference.ts` | `assertRule`, multi-hop inference, proof trees, transitive closure |
| `03_memory_management.ts` | `contextWindow`, `temporalQuery`, `setPriority`, `consolidate`, `decay`, TTL |
| `04_transactions.ts` | `beginTransaction`, `commitTransaction`, `rollbackTransaction` |
| `05_auth_and_keys.ts` | `authStatus`, `bootstrap`, `createKey`, `listKeys`, `revokeKey`, `whoami` |
| `06_events_sse.ts` | `subscribeEvents` — real-time SSE event stream with filtering |
| `07_agent_workflow.ts` | Full end-to-end agent memory lifecycle with live event capture |
