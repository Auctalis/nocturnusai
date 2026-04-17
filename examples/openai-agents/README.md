# OpenAI Agents SDK + NocturnusAI — 5-minute wow

An OpenAI agent that resolves an SLA-credit ticket without replaying the conversation on every tool call.

## The problem

Every tool invocation in an OpenAI Agents workflow includes the prior conversation in the agent's context. By the fifth tool call, you are paying to re-read the full ticket thread every single time.

## Before / After

| | Without NocturnusAI | With NocturnusAI |
|---|---|---|
| Tokens per turn | ~1,259 | ~221 |
| Cost per month (1K req/hr, Opus 4, $15/1M) | $13,600 | $2,400 |
| Truth-preserving across retractions | no | yes |

Measured on our [15-turn product-support benchmark](https://nocturnus.ai/benchmark). The compression happens at the Nocturnus layer — it's framework-agnostic.

## Install

```bash
pip install nocturnusai openai-agents
docker run -d -p 9300:9300 ghcr.io/auctalis/nocturnusai:latest
export OPENAI_API_KEY=sk-...
```

## Run

```bash
python knowledge_agent.py
```

The example has two parts:

1. **Context compression** — feeds 5 ticket turns (CRM lookup, SLA calculator, contract check, internal note) through `process_turns()`. Shows the `briefingDelta` the agent receives. Runs without an LLM key.
2. **OpenAI Agent** — wires `get_nocturnusai_tools()` into an `Agent` with `nocturnusai_query` and `nocturnusai_infer`. The agent answers 3 questions about the ticket using only the facts in the knowledge base.

## Measure your own numbers

```bash
python bench.py
```

Runs your conversation against the OpenAI API twice — once with naive history replay, once through NocturnusAI — and prints `usage.prompt_tokens` per turn.

## What to look at

- `knowledge_agent.py` — `run_context_compression()` shows the before/after per batch
- `get_nocturnusai_tools()` returns the knowledge tools the Agent SDK expects
- The `briefingDelta` — the short factual summary the agent reads instead of the raw thread

## Next step

- [Benchmark methodology](https://nocturnus.ai/benchmark)
- [How inference compresses context](https://nocturnus.ai/how-it-works)
- [Other framework examples →](https://nocturnus.ai/examples)
