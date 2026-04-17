"""
bench.py — Measure before/after token counts for YOUR workload.

Runs a 15-turn conversation against the OpenAI API twice:
  1. Naive — full history replay every turn
  2. NocturnusAI — compressed context every turn

Prints per-turn usage.input_tokens and the ratio. Your numbers, your workload.

Usage:
  pip install nocturnusai langchain langchain-openai
  export OPENAI_API_KEY=sk-...
  docker run -d -p 9300:9300 ghcr.io/auctalis/nocturnusai:latest
  python bench.py
"""

import os
from openai import OpenAI
from nocturnusai import SyncNocturnusAIClient

SERVER = "http://localhost:9300"
DB = "langchain-bench"
SCOPE = "bench-session"
MODEL = "gpt-4o-mini"  # Swap for Claude Opus 4 or Gemini 2.0 Flash to match site numbers.

# Edit this list to benchmark YOUR conversation shape.
CONVERSATION = [
    "User: I can't access the dashboard since this morning. Ticket INC-7734.",
    "Tool crm_lookup: account=Acme Corp, tier=enterprise, ARR=$2.4M, CSM=Dana Rivera.",
    "User: About 40 people on west coast affected. Quarterly reviews this week.",
    "Tool auth_audit: 287 SAML_ASSERTION_INVALID errors since 07:52 UTC.",
    "Tool dns_check: dashboard.example.com resolves correctly, SSL valid.",
    "User: We have a board presentation Friday. Need this fixed by Thursday EOD.",
    "Tool okta_config_check: app integration ID mismatch. SP has exk1abc, Okta has exk2def.",
    "Internal: Dana spoke with Greg Torres (Acme IT). Okta cutover happened 11pm PST last night.",
    "User: Any update? VP is asking for a timeline.",
    "Tool status_page: dashboard-api operational, 99.98% uptime.",
    "Agent: Root cause identified — SAML entity ID mismatch post-Okta migration.",
    "User: How long to fix?",
    "Tool okta_config_check: fix is to update SP entity ID from exk1abc to exk2def. 15 min after change.",
    "User: Who needs to make the change?",
    "Agent: Requires Okta admin — Greg Torres is standing by in #acme-migration.",
]


def naive_run(oai):
    print("\n── Naive run (full history replay) ──")
    history = []
    tokens = []
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
        ctx = client.process_turns(
            turns=[turn],
            scope=SCOPE,
            session_id=SCOPE,
        )
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
