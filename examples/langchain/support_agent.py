"""
langchain_support_agent.py — LangChain agent with NocturnusAI turn compression

Scenario: A tier-2 support agent handling a complex, multi-turn escalation.
Raw tool output and user messages are messy and verbose. NocturnusAI's
process_turns() extracts the structured facts and returns a short briefingDelta
that the LLM receives instead of the raw thread.

This demonstrates:
  1. Feeding noisy tool output + user messages through process_turns()
  2. Getting a clean briefingDelta back for the LLM's system prompt
  3. Accumulating knowledge across turns without token bloat
  4. Using LangChain tools against the extracted knowledge base

Prerequisites:
  pip install nocturnusai langchain langchain-openai
  # Start server:
  docker run -p 9300:9300 -e ANTHROPIC_API_KEY=sk-ant-... ghcr.io/auctalis/nocturnusai:latest

Then run:
  python examples/langchain_support_agent.py
"""

import os
from nocturnusai import SyncNocturnusAIClient
from nocturnusai.langchain import get_nocturnusai_tools

SERVER = "http://localhost:9300"
DB = "langchain-support-demo"
SCOPE = "ticket-7734"


# ── Simulated conversation turns (raw, messy, verbose) ──────────────────────
# These mimic what a real support agent would see: tool dumps, user frustration,
# duplicate info, irrelevant noise. The kind of thing you do NOT want to replay
# to the LLM every turn.

TURN_BATCHES = [
    {
        "label": "Turn 1 — Initial complaint + CRM lookup",
        "turns": [
            "User: Hi, our entire west-coast sales team (about 40 people) can't access "
            "the dashboard since this morning around 8am PST. We've tried clearing "
            "cookies, different browsers, incognito mode — nothing works. This is "
            "really urgent because we have quarterly reviews this week and everyone "
            "needs access to their pipeline numbers. Ticket ref: INC-7734.",

            "Tool crm_lookup: {"
            '"account_id": "acme-west-7291", '
            '"account_name": "Acme Corp — West Region", '
            '"tier": "enterprise", '
            '"arr": "$2.4M", '
            '"csm": "Dana Rivera", '
            '"renewal_date": "2026-09-15", '
            '"health_score": 82, '
            '"open_tickets": 3, '
            '"last_interaction": "2026-04-08", '
            '"notes": "Migrated from legacy SSO (Ping) to Okta on 2026-04-09. '
            'Dana flagged as high-touch during migration window."'
            "}",
        ],
    },
    {
        "label": "Turn 2 — Auth system deep dive",
        "turns": [
            "Tool auth_audit: {"
            '"time_range": "2026-04-10T00:00:00Z to 2026-04-10T16:30:00Z", '
            '"total_login_attempts": 847, '
            '"failed_attempts": 312, '
            '"failure_breakdown": {'
            '  "SAML_ASSERTION_INVALID": 287, '
            '  "SESSION_EXPIRED": 18, '
            '  "RATE_LIMITED": 7'
            '}, '
            '"first_failure": "2026-04-10T07:52:14Z", '
            '"affected_idp": "okta-acme-prod", '
            '"issuer_expected": "https://acme.okta.com/app/exk1abc", '
            '"issuer_received": "https://acme.okta.com/app/exk2def", '
            '"certificate_thumbprint_match": false, '
            '"saml_response_sample_id": "resp_8f3a2b_redacted"'
            "}",

            "Tool dns_check: {"
            '"domain": "dashboard.example.com", '
            '"a_record": "203.0.113.42", '
            '"expected": "203.0.113.42", '
            '"dns_match": true, '
            '"ssl_valid": true, '
            '"ssl_expiry": "2027-01-20"'
            "}",

            "Tool status_page: {"
            '"service": "dashboard-api", '
            '"status": "operational", '
            '"uptime_24h": "99.98%", '
            '"last_incident": "2026-03-12"'
            "}",
        ],
    },
    {
        "label": "Turn 3 — Okta admin confirms + user follow-up",
        "turns": [
            "Internal note from Dana Rivera (CSM): I spoke with Acme's IT admin "
            "(Greg Torres). He confirmed they did the Okta SSO cutover last night "
            "at 11pm PST. He said he followed our migration runbook but isn't sure "
            "if the SAML app integration ID was updated in our system. He's "
            "standing by on Slack at #acme-migration for a screenshare if needed.",

            "User: Any update? Our VP of Sales is asking for a timeline. We have a "
            "board presentation Friday and need the pipeline reports pulled before "
            "end of day Thursday. If this isn't fixed by then we'll need to "
            "manually export from the old system which will take our ops team "
            "the rest of the week.",

            "Tool okta_config_check: {"
            '"app_integration_id_in_sp": "exk1abc", '
            '"app_integration_id_in_okta": "exk2def", '
            '"mismatch": true, '
            '"fix": "Update SP entity ID from exk1abc to exk2def or reconfigure '
            'Okta app to use original entity ID exk1abc", '
            '"estimated_fix_time": "15 minutes after change", '
            '"requires_okta_admin": true'
            "}",
        ],
    },
]


def run_turn_compression():
    """Show how process_turns compresses each batch into a clean delta."""
    print("=" * 70)
    print(" NocturnusAI Turn Compression — Support Ticket INC-7734")
    print("=" * 70)

    with SyncNocturnusAIClient(SERVER, database=DB) as client:
        client.ensure_database()

        cumulative_facts = 0

        for batch in TURN_BATCHES:
            print(f"\n{'─' * 70}")
            print(f" {batch['label']}")
            print(f"{'─' * 70}")

            # Show raw input size
            raw_chars = sum(len(t) for t in batch["turns"])
            print(f"\n  Raw input: {len(batch['turns'])} turns, ~{raw_chars} chars")

            # Send through NocturnusAI
            result = client.process_turns(
                turns=batch["turns"],
                scope=SCOPE,
                session_id=SCOPE,
            )

            cumulative_facts += result.new_facts_extracted
            print(f"  New facts extracted: {result.new_facts_extracted}")
            print(f"  Total facts in KB:   {result.total_facts_in_kb}")

            if result.briefing_delta:
                delta_chars = len(result.briefing_delta)
                compression = round((1 - delta_chars / raw_chars) * 100)
                print(f"  Delta size: ~{delta_chars} chars ({compression}% smaller)")
                print(f"\n  briefingDelta (what the LLM actually sees):")
                for line in result.briefing_delta.split("\n"):
                    print(f"    {line}")
            else:
                print("  (no briefingDelta — LLM extraction may not be configured)")

        print(f"\n{'═' * 70}")
        print(f" Summary: {cumulative_facts} structured facts extracted from")
        print(f" {sum(len(b['turns']) for b in TURN_BATCHES)} noisy turns")
        print(f"{'═' * 70}")

        return client


def run_langchain_agent(client: SyncNocturnusAIClient):
    """Wire up a LangChain agent that reasons over the extracted knowledge."""
    if not os.getenv("OPENAI_API_KEY"):
        print("\n[skipped] Set OPENAI_API_KEY to run the LangChain agent demo.")
        print("  The turn compression demo above works without it.\n")
        return

    from langchain.agents import AgentExecutor, create_tool_calling_agent
    from langchain_core.prompts import ChatPromptTemplate
    from langchain_openai import ChatOpenAI

    tools = get_nocturnusai_tools(client)
    llm = ChatOpenAI(model="gpt-4o-mini", temperature=0)

    prompt = ChatPromptTemplate.from_messages([
        (
            "system",
            "You are a tier-2 support agent. You have a knowledge base of "
            "structured facts extracted from the ticket conversation. Use the "
            "nocturnusai_query and nocturnusai_infer tools to look up facts "
            "and answer questions. All facts are scoped to 'ticket-7734'.",
        ),
        ("human", "{input}"),
        ("placeholder", "{agent_scratchpad}"),
    ])

    agent = create_tool_calling_agent(llm, tools, prompt)
    executor = AgentExecutor(agent=agent, tools=tools, verbose=True, max_iterations=6)

    questions = [
        "What is the root cause of the login failures?",
        "What's the fix and how long will it take?",
        "What's the business impact and deadline?",
    ]

    print("\n" + "=" * 70)
    print(" LangChain Agent — Querying Extracted Knowledge")
    print("=" * 70)

    for q in questions:
        print(f"\n  [Q] {q}")
        result = executor.invoke({"input": q})
        print(f"  [A] {result['output']}")
        print()


def main():
    with SyncNocturnusAIClient(SERVER, database=DB) as client:
        client.ensure_database()

        # Part 1: Show turn compression (no LLM API key needed for this part)
        run_turn_compression()

        # Part 2: Query the extracted facts with a LangChain agent
        run_langchain_agent(client)

        # Cleanup
        print("\nCleaning up...")
        result = client.delete_scope(SCOPE)
        print(f"Deleted {result.get('deleted', 0)} facts from scope '{SCOPE}'")


if __name__ == "__main__":
    main()
