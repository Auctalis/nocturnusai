"""
bench.py — Measure before/after token counts for YOUR workload.

Runs the same conversation against the OpenAI API twice:
  1. Naive — full history replay every turn
  2. NocturnusAI — compressed context every turn

Prints per-turn usage.prompt_tokens and the ratio. Your numbers.

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
DB = "openai-agents-bench"
SCOPE = "bench-session"
MODEL = "gpt-4o-mini"

# Edit to benchmark your own workload.
CONVERSATION = [
    "User: Globex (enterprise tier) is asking for SLA credits. They claim 14 hours of downtime.",
    "Tool crm_lookup: account=globex-corp, tier=enterprise, ARR=$3.1M, SLA=99.95%.",
    "Tool sla_calculator: uptime Q1=99.82%, breach=true, credit_owed=$77500.",
    "Internal: Priya (CSM) confirms customer's uptime tracking matches ours.",
    "Tool contract_check: auto_approval_limit=$100000, within_limit=true.",
    "User: Is this eligible for auto-approval?",
    "Agent: Yes — $77,500 is under the $100,000 auto-approval threshold.",
    "User: When will the credit appear?",
    "Tool billing_check: next invoice date=2026-05-01, credit processing=48h.",
    "Agent: Credit will appear on the May 1 invoice, processed within 48 hours of approval.",
    "User: Can you email confirmation to Priya?",
    "Tool email_send: to=priya@globex.com, subject='SLA credit approved: $77,500'.",
    "User: Done, thank you!",
    "Agent: Ticket closed. Credit scheduled, CSM notified.",
    "Internal: Closing ticket #8812 with resolution=sla_credit_approved.",
]


def naive_run(oai):
    print("\n── Naive run (full history replay) ──")
    history, tokens = [], []
    for i, turn in enumerate(CONVERSATION, 1):
        history.append({"role": "user", "content": turn})
        resp = oai.chat.completions.create(
            model=MODEL,
            messages=history + [{"role": "user", "content": "Respond briefly."}],
            max_tokens=60,
        )
        n = resp.usage.prompt_tokens
        tokens.append(n)
        history.append({"role": "assistant", "content": resp.choices[0].message.content})
        print(f"  Turn {i:2d}: {n:>5} input tokens")
    return tokens


def nocturnus_run(oai, client):
    print("\n── NocturnusAI run (compressed context) ──")
    tokens = []
    for i, turn in enumerate(CONVERSATION, 1):
        ctx = client.process_turns(turns=[turn], scope=SCOPE, session_id=SCOPE)
        system = ctx.briefing_delta or "No prior context."
        resp = oai.chat.completions.create(
            model=MODEL,
            messages=[
                {"role": "system", "content": system},
                {"role": "user", "content": "Respond briefly to the latest message."},
            ],
            max_tokens=60,
        )
        n = resp.usage.prompt_tokens
        tokens.append(n)
        print(f"  Turn {i:2d}: {n:>5} input tokens")
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
