"""
bench.py — Measure before/after token counts for a CrewAI workflow.

Same crew workflow (3 agents, sequential) run twice:
  1. Naive — each agent receives all prior crew outputs
  2. NocturnusAI — each agent receives a task-scoped briefingDelta

Prints total input tokens across the crew for each run.

Usage:
  pip install nocturnusai openai
  export OPENAI_API_KEY=sk-...
  docker run -d -p 9300:9300 ghcr.io/auctalis/nocturnusai:latest
  python bench.py
"""

import os
from openai import OpenAI
from nocturnusai import SyncNocturnusAIClient

SERVER = "http://localhost:9300"
DB = "crewai-bench"
SCOPE = "bench-topic"
MODEL = "gpt-4o-mini"

# Shared research findings — what all agents need access to.
RESEARCH = [
    "Market size: AI agent infra was $3B in 2024, $22B in 2026.",
    "Growth: 61% CAGR.",
    "Key players: LangChain (orchestration), NocturnusAI (context), OpenAI (model).",
    "Risk: framework churn for LangChain, model-provider competition for NocturnusAI.",
    "Tailwind: token cost pressure + enterprise agent adoption.",
]

TASKS = [
    "As Market Researcher: summarize market size, growth, and 3 key players in 2 sentences.",
    "As Strategy Analyst: identify the biggest risk and the biggest tailwind for the context layer.",
    "As Brief Writer: produce a 3-sentence investment brief.",
]


def naive_run(oai):
    """Each agent sees full research + all prior agent outputs."""
    print("\n── Naive run (full crew transcript) ──")
    tokens = []
    transcript = ["Research findings:"] + RESEARCH
    for i, task in enumerate(TASKS, 1):
        messages = [
            {"role": "system", "content": "\n".join(transcript)},
            {"role": "user", "content": task},
        ]
        resp = oai.chat.completions.create(model=MODEL, messages=messages, max_tokens=80)
        n = resp.usage.prompt_tokens
        tokens.append(n)
        transcript.append(f"Agent {i} output: {resp.choices[0].message.content}")
        print(f"  Agent {i}: {n:>5} input tokens")
    return tokens


def nocturnus_run(oai, client):
    """Each agent sees task-scoped briefingDelta only."""
    print("\n── NocturnusAI run (task-scoped context) ──")
    tokens = []
    # Seed research as facts
    for i, finding in enumerate(RESEARCH):
        client.tell(predicate="finding", args=[f"f{i}", finding[:80]], scope=SCOPE)
    for i, task in enumerate(TASKS, 1):
        ctx = client.process_turns(turns=[task], scope=SCOPE, session_id=SCOPE)
        system = ctx.briefing_delta or "No prior context."
        messages = [
            {"role": "system", "content": system},
            {"role": "user", "content": task},
        ]
        resp = oai.chat.completions.create(model=MODEL, messages=messages, max_tokens=80)
        n = resp.usage.prompt_tokens
        tokens.append(n)
        print(f"  Agent {i}: {n:>5} input tokens")
    return tokens


def main():
    if not os.getenv("OPENAI_API_KEY"):
        raise SystemExit("Set OPENAI_API_KEY to run the benchmark.")
    oai = OpenAI()

    with SyncNocturnusAIClient(SERVER, database=DB) as client:
        client.ensure_database()
        try:
            naive = naive_run(oai)
            nocturnus = nocturnus_run(oai, client)
        finally:
            client.delete_scope(SCOPE)

    sn, sc = sum(naive), sum(nocturnus)
    print("\n" + "=" * 50)
    print(f"  Naive total:     {sn:>6} tokens")
    print(f"  Nocturnus total: {sc:>6} tokens")
    print(f"  Reduction:       {(1 - sc / sn) * 100:.1f}%  ({sn / sc:.1f}x)")
    print("=" * 50)


if __name__ == "__main__":
    main()
