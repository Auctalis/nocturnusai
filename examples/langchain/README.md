# LangChain + NocturnusAI — 5-minute wow

A LangChain agent that stops replaying the entire conversation on every turn.

## The problem

`ConversationBufferMemory` replays every turn into every LLM call. By turn 10 of a support-ticket conversation, you are sending thousands of tokens of repetitive context. Cost scales linearly with turn count.

## Before / After

| | Without NocturnusAI | With NocturnusAI |
|---|---|---|
| Tokens per turn | ~1,259 | ~221 |
| Cost per month (1K req/hr, Opus 4, $15/1M) | $13,600 | $2,400 |
| Truth-preserving across retractions | no | yes |

Measured on our [15-turn product-support benchmark](https://nocturnus.ai/benchmark). The compression happens at the Nocturnus layer, not in LangChain — your per-turn savings will be close to these numbers regardless of framework.

## Install

```bash
pip install nocturnusai langchain langchain-openai
docker run -d -p 9300:9300 ghcr.io/auctalis/nocturnusai:latest
```

## Run

```bash
export OPENAI_API_KEY=sk-...   # optional — only needed for the LangChain agent portion
python support_agent.py
```

The example has two parts:

1. **Turn compression** — feeds 10 noisy turns (tool dumps, user messages, internal notes) through `process_turns()`. Shows the `briefingDelta` your LLM actually sees. Runs without an LLM key.
2. **LangChain agent** — wires `get_nocturnusai_tools()` into a `create_tool_calling_agent` flow. The agent queries the extracted knowledge base to answer 3 questions about the ticket. Requires `OPENAI_API_KEY`.

## Measure your own numbers

```bash
python bench.py
```

`bench.py` runs the same conversation against the OpenAI API twice — once with raw history replay, once through NocturnusAI — and prints the `usage.input_tokens` for each turn. Your numbers, your workload.

## What to look at

- `support_agent.py:129` — `run_turn_compression()` shows the before/after per batch
- `support_agent.py:178` — `run_langchain_agent()` wires Nocturnus tools into LangChain
- The `briefingDelta` field — this is what you send to your LLM instead of the full history

## Next step

- [Benchmark methodology](https://nocturnus.ai/benchmark)
- [How inference compresses context](https://nocturnus.ai/how-it-works)
- [Other framework examples →](https://nocturnus.ai/examples)
