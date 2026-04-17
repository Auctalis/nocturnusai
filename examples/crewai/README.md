# CrewAI + NocturnusAI — 5-minute wow

A 3-agent research crew producing an investment brief. Each agent sees only the facts its role requires.

## The problem

In a default CrewAI flow, each crew member receives all prior crew outputs as context. By the third agent, you are sending the full transcript of the previous two agents' work into every LLM call — regardless of whether the final agent needs it.

## Before / After

| | Without NocturnusAI | With NocturnusAI |
|---|---|---|
| Tokens per turn | ~1,259 | ~221 |
| Cost per month (1K req/hr, Opus 4, $15/1M) | $13,600 | $2,400 |
| Truth-preserving across retractions | no | yes |

Measured on our [15-turn product-support benchmark](https://nocturnus.ai/benchmark). For a CrewAI workflow specifically, task-scoped context means agents receive only the facts relevant to their role — further reducing their prompt size.

## Install

```bash
pip install nocturnusai crewai
docker run -d -p 9300:9300 ghcr.io/auctalis/nocturnusai:latest
export OPENAI_API_KEY=sk-...
```

## Run

```bash
python research_crew.py
```

The example:

1. **Seeds 10 facts** into Nocturnus under scope `ai-agent-infrastructure-2026` (market_size, key_player, incumbent_risk, tailwind)
2. **Runs a 3-agent sequential crew:** Researcher → Analyst → Writer
3. Each agent queries the knowledge base via `get_nocturnusai_tools()` and receives a scoped context
4. Writer produces the final 3-sentence investment brief

## Measure your own numbers

```bash
python bench.py
```

Runs a multi-turn crew workflow with and without NocturnusAI compression. Prints total tokens for each.

## What to look at

- `get_nocturnusai_tools(client, scope=TOPIC)` — tools auto-scope to the crew's topic
- Each agent's `tools` parameter — same knowledge base, role-specific queries
- The final brief — grounded in the seeded facts, not hallucinated

## Next step

- [Benchmark methodology](https://nocturnus.ai/benchmark)
- [How inference compresses context](https://nocturnus.ai/how-it-works)
- [Other framework examples →](https://nocturnus.ai/examples)
