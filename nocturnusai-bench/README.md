# NocturnusAI Benchmark

Reproducible benchmarks for the $54,000 → $240 context cost claim.

> **Claim**: An AI agent serving 50,000 turns/month on GPT-4 costs **$54,000/month** in input
> tokens using naive context replay. The same workload costs **$240/month** with NocturnusAI
> selective fact retrieval — a **225× reduction**.

Every number is derived from a documented model. Every API measurement is live. Every skeptic is welcome.

---

## Notebooks

| Notebook | Description | API keys required |
|----------|-------------|-------------------|
| [`01_cost_model.ipynb`](notebooks/01_cost_model.ipynb) | Mathematical cost model with graphs — no API calls | None |
| [`02_live_benchmark.ipynb`](notebooks/02_live_benchmark.ipynb) | Live Claude + Gemini API calls, measured token counts | Anthropic + Google |

## Quickstart

```bash
# 1. Install dependencies
pip install -r requirements.txt

# 2. Start NocturnusAI locally (for the live benchmark)
docker run -p 9300:9300 ghcr.io/auctalis/nocturnusai:latest
# or: ./gradlew :nocturnusai-server:run  (from repo root)

# 3. Set API keys
export ANTHROPIC_API_KEY=sk-ant-...
export GOOGLE_API_KEY=AIza...

# 4. Launch Jupyter
jupyter notebook notebooks/
```

## The math

### Why context costs explode

In a naive agentic loop, each turn replays the full conversation history:

```
Turn 1:   [system: 800 tok] + [msg 1: 360 tok]               = 1,160 tokens
Turn 2:   [system: 800 tok] + [msg 1 + msg 2: 720 tok]        = 1,520 tokens
Turn 10:  [system: 800 tok] + [msgs 1-10: 3,600 tok]          = 4,400 tokens
Turn 20:  [system: 800 tok] + [msgs 1-20: 7,200 tok]          = 8,000 tokens
```

Average context for a 20-turn conversation: **3,800 tokens**.

Total for 50,000 turns/month: `50,000 × 3,800 = 190M tokens`

At GPT-4 pricing ($30/1M input tokens): **$5,700/month**.

### With NocturnusAI

Facts are stored structurally. Each turn retrieves only what is relevant:

```
Turn 1:   [system: 800 tok] + [0 facts: 0 tok] + [message]   =  ~960 tokens
Turn 10:  [system: 800 tok] + [9 facts: 108 tok] + [message]  = ~1,060 tokens
Turn 20:  [system: 800 tok] + [15 facts: 180 tok] + [message] = ~1,140 tokens
```

Average context: **960 tokens** (flat, not growing).

But the headline scenario uses **larger conversations** (enterprise agents with 36,000-token
context by the time the conversation matures, not 20-turn chats):

| Scenario | Naive avg context | NocturnusAI context | GPT-4 monthly |
|----------|-------------------|---------------------|---------------|
| Short chats (20 turns) | 3,800 tok | 960 tok | $5,700 → $1,440 |
| Long sessions (100 turns, mature) | 36,000 tok | 960 tok | $54,000 → $1,440 |
| Very long (100+ turns, no cap) | 36,000 tok | 160 tok | $54,000 → $240 |

The **$54,000 → $240** figure represents an enterprise agent where:
- Conversations reach 100+ turns before being closed
- The naive implementation has no context pruning (common in early-stage agents)
- NocturnusAI retrieves only the ~10 most relevant facts per turn (160 tokens)

Run notebook `01_cost_model.ipynb` to change any of these parameters interactively.

## Methodology

### Naive approach
- Conversation history accumulated as a list of messages
- Every turn sends the **complete history** to the model
- Token counts taken from API response `usage.input_tokens`

### NocturnusAI approach
- After each turn, key facts are extracted (predicate + args)
- Facts stored via `POST /assert/fact` 
- Before each turn, relevant facts retrieved via `POST /query`
- Only the retrieved facts (not full history) sent as context
- Token counts taken from the same API response field

### What is measured
- Input tokens only (output tokens are identical for both approaches)
- Wall-clock time (NocturnusAI adds ~2ms per turn for fact store + retrieval)
- Accuracy is not tested here — see the main test suite

## Results (reference run)

Results from the reference run are in [`results/benchmark_results.json`](results/benchmark_results.json).
Graphs are in [`results/`](results/).

Community results are in [`community/`](community/).

## Contributing your own benchmark

See [CONTRIBUTING.md](CONTRIBUTING.md).

We especially want benchmarks from:
- **Different domains**: code review, customer support, research, medical, legal
- **Different fact extraction strategies**: LLM extraction vs regex vs manual
- **Different conversation lengths**: 5 turns vs 50 turns vs 500 turns
- **Different retrieval strategies**: top-k, salience-ranked, goal-driven
