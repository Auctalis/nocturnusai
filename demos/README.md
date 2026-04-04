# NocturnusAI Demos

NocturnusAI is a context-reduction and reasoning backend for agents.

Start with the practical problem first:

- you have too many turns
- you need a smaller context window
- you want later turns to send only diffs

The demos then show the backend machinery that makes that work: structured facts, rules, inference, temporal state, and salience.

## Start the server

```bash
./gradlew :nocturnusai-server:run
# or
docker compose up --build
```

---

## Featured: LLM & Agent Integration [`llm/`](./llm/)

These are the primary demos. Start here.

| Demo | Language | What it shows |
|------|----------|---------------|
| [LangChain agent](./llm/python/01_langchain_agent.py) | Python | Tool-calling agent over Nocturnus-backed memory |
| [MCP protocol](./llm/python/02_mcp_client.py) | Python | How MCP clients discover and call Nocturnus tools |
| [OpenAI function calling](./llm/python/03_openai_tools.py) | Python | Tool loop over a Nocturnus-backed state layer |
| [Agent memory lifecycle](./llm/python/04_agent_memory.py) | Python | The real story: onboarding -> focused context -> later-turn cleanup |
| [MCP protocol](./llm/typescript/01_mcp_client.ts) | TypeScript | Tool discovery and simulated MCP loop |
| [OpenAI function calling](./llm/typescript/02_openai_tools.ts) | TypeScript | Same tool loop pattern in TypeScript |
| [Agent memory lifecycle](./llm/typescript/03_agent_memory.ts) | TypeScript | Context reduction and memory lifecycle in TypeScript |

### Quickstart

```bash
# Python MCP demo
cd demos/llm/python
pip install nocturnusai
python 02_mcp_client.py

# Python memory lifecycle demo
python 04_agent_memory.py

# TypeScript MCP demo
cd ../typescript
npm install
npx ts-node 01_mcp_client.ts
```

---

## Advanced / Deep-Dive [`advanced/`](./advanced/)

These demos cover the lower-level SDK mechanics behind the LLM demos.

- fact and rule operations
- salience windows, temporal queries, consolidation, decay
- transactions and auth
- event streams and agent workflows

---

## Raw HTTP [`curl/`](./curl/)

Use these when you want exact request and response shapes for REST calls.

```bash
bash demos/curl/examples.sh
bash demos/curl/value_proof.sh
```

---

## Why these demos exist

| Problem | Demo path |
|---------|-----------|
| Too many turns in every prompt | `llm/*agent_memory*` and `curl/value_proof.sh` |
| Need a fast working set on each turn | MCP demos and memory demos |
| Need exact REST shapes | `curl/` |
| Need low-level SDK behavior | `advanced/` |
| Need backend reasoning examples | `advanced/01_basics`, `02_rules_and_inference` |
