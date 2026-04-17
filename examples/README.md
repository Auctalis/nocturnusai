# Examples

Five 5-minute wow examples — one per framework. Each answers the same four questions:

1. What problem it solves
2. Exact before/after token counts
3. Copy-paste install
4. 2-minute demo (linked GIF)

| Framework | Problem it solves | Directory |
|---|---|---|
| **[LangChain](./langchain/)** | ConversationBufferMemory replays every turn | `langchain/` |
| **[OpenAI Agents SDK](./openai-agents/)** | Every tool call re-sends the full context | `openai-agents/` |
| **[CrewAI](./crewai/)** | Each crew member sees all prior crew outputs | `crewai/` |
| **[MCP](./mcp/)** | MCP clients accumulate tool outputs across the session | `mcp/` |
| **[Vercel AI SDK](./vercel-ai-sdk/)** | `streamText` receives the full message history every request | `vercel-ai-sdk/` |

Every example includes:
- `README.md` — the 5-minute wow
- A runnable demo script
- `bench.py` (or `bench.mjs`) — measure your own before/after tokens
- `requirements.txt` or `package.json`

## Prerequisite (all examples)

```bash
docker run -d -p 9300:9300 ghcr.io/auctalis/nocturnusai:latest
```

## General-purpose quickstarts

These aren't framework-specific — just entry points for the Python and TypeScript SDKs.

- [`homepage_walkthrough.py`](./homepage_walkthrough.py) — the homepage walkthrough, line-for-line
- [`quickstart.ts`](./quickstart.ts) — minimal TypeScript SDK example

## How the numbers are measured

All examples cite **~1,259 → ~221 tokens/turn (82% / 5.7×)** from our [15-turn product-support benchmark](https://nocturnus.ai/benchmark) against Claude Opus 4. Gemini 2.0 Flash shows **10.0×**.

Run `bench.py` inside each framework directory to measure your own workload — the script runs the same conversation with and without NocturnusAI and prints the ratio.

## Website

Each example has a matching page at [nocturnus.ai/examples/](https://nocturnus.ai/examples):
- [/examples/langchain](https://nocturnus.ai/examples/langchain)
- [/examples/openai-agents](https://nocturnus.ai/examples/openai-agents)
- [/examples/crewai](https://nocturnus.ai/examples/crewai)
- [/examples/mcp](https://nocturnus.ai/examples/mcp)
- [/examples/vercel-ai-sdk](https://nocturnus.ai/examples/vercel-ai-sdk)
