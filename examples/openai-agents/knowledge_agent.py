"""
knowledge_agent.py — OpenAI Agents SDK + NocturnusAI

Scenario: A CRM/support agent that resolves an SLA-credit ticket.

The agent has two tools from NocturnusAI (query + infer) plus a stub
CRM tool. NocturnusAI compresses each turn so the agent sees a short
briefingDelta instead of the full conversation.

This demonstrates:
  1. Wrapping any OpenAI agent with Nocturnus context middleware
  2. Storing each turn via process_turns() so later turns only see the delta
  3. Using Nocturnus knowledge tools alongside your own business-logic tools

Prerequisites:
  pip install nocturnusai openai-agents
  docker run -d -p 9300:9300 ghcr.io/auctalis/nocturnusai:latest
  export OPENAI_API_KEY=sk-...

Run:
  python knowledge_agent.py
"""

import os
from nocturnusai import SyncNocturnusAIClient
from nocturnusai.openai_agents import get_nocturnusai_tools

SERVER = "http://localhost:9300"
DB = "openai-agents-demo"
SCOPE = "sla-ticket-8812"


# Noisy, multi-turn ticket thread — exactly the kind of content you don't
# want to replay to the LLM on every turn.
TICKET_TURNS = [
    "User: Customer (Globex, enterprise tier) is asking for SLA credits. "
    "They claim 14 hours of dashboard downtime last quarter.",

    "Tool crm_lookup: {"
    '"account": "globex-corp", '
    '"tier": "enterprise", '
    '"arr": "$3.1M", '
    '"sla_tier": "premium (99.95% uptime)", '
    '"renewal": "2026-11-30", '
    '"csm": "Priya Natarajan"'
    "}",

    "Tool sla_calculator: {"
    '"measured_uptime_q1_2026": "99.82%", '
    '"threshold": "99.95%", '
    '"breach": true, '
    '"downtime_hours": 12.9, '
    '"credit_owed_percent": 10, '
    '"credit_owed_usd": 77500'
    "}",

    "Internal: Priya confirms the customer's uptime tracking matches ours "
    "within 1 hour. She recommends approving the 10% credit immediately — "
    "this is their renewal quarter and churn risk is elevated.",

    "Tool contract_check: {"
    '"sla_credit_auto_approval_limit_usd": 100000, '
    '"credit_owed_usd": 77500, '
    '"within_auto_approval": true, '
    '"approver_required": false'
    "}",
]


def run_context_compression():
    """Feed the ticket turns through Nocturnus — show what the agent sees."""
    print("=" * 70)
    print(" Context compression — SLA credit ticket #8812")
    print("=" * 70)

    with SyncNocturnusAIClient(SERVER, database=DB) as client:
        client.ensure_database()

        raw_chars = sum(len(t) for t in TICKET_TURNS)

        result = client.process_turns(
            turns=TICKET_TURNS,
            scope=SCOPE,
            session_id=SCOPE,
        )

        print(f"\n  Raw input: {len(TICKET_TURNS)} turns, ~{raw_chars} chars")
        print(f"  Facts extracted: {result.new_facts_extracted}")

        if result.briefing_delta:
            delta_chars = len(result.briefing_delta)
            print(f"  Delta: ~{delta_chars} chars "
                  f"({round((1 - delta_chars / raw_chars) * 100)}% smaller)")
            print(f"\n  briefingDelta (what the agent actually reads):")
            for line in result.briefing_delta.split("\n"):
                print(f"    {line}")
        else:
            print("  (no delta — LLM extraction may not be configured)")

        return client


def run_openai_agent(client: SyncNocturnusAIClient):
    """Run an OpenAI Agent with Nocturnus knowledge tools."""
    if not os.getenv("OPENAI_API_KEY"):
        print("\n[skipped] Set OPENAI_API_KEY to run the agent.\n")
        return

    try:
        from agents import Agent, Runner
    except ImportError:
        print("\n[skipped] pip install openai-agents to run the agent.\n")
        return

    tools = get_nocturnusai_tools(client)

    agent = Agent(
        name="sla-credit-resolver",
        instructions=(
            "You resolve SLA-credit tickets. Use nocturnusai_query and "
            "nocturnusai_infer to look up facts from the ticket knowledge "
            "base (scope='sla-ticket-8812'). Answer the user's question "
            "with the specific facts you find."
        ),
        tools=tools,
    )

    questions = [
        "Is this ticket eligible for auto-approval?",
        "What's the credit amount and why?",
        "Which CSM owns this account?",
    ]

    print("\n" + "=" * 70)
    print(" OpenAI Agent — querying the extracted knowledge")
    print("=" * 70)

    for q in questions:
        print(f"\n  [Q] {q}")
        result = Runner.run_sync(agent, q)
        print(f"  [A] {result.final_output}")


def main():
    with SyncNocturnusAIClient(SERVER, database=DB) as client:
        client.ensure_database()

        # Part 1: Show context compression
        run_context_compression()

        # Part 2: Run the agent against the extracted knowledge
        run_openai_agent(client)

        # Cleanup
        print("\nCleaning up...")
        result = client.delete_scope(SCOPE)
        print(f"Deleted {result.get('deleted', 0)} facts from scope '{SCOPE}'")


if __name__ == "__main__":
    main()
