"""
Homepage Walkthrough Example
=============================
Follows the "Try It" section from the NocturnusAI front page,
using the official Python SDK.

Prerequisites:
  Start the server with one of:
    docker run -p 9300:9300 ghcr.io/auctalis/nocturnusai:latest
    ./gradlew :nocturnusai-server:run

Then run:
    pip install nocturnusai
    python examples/homepage_walkthrough.py
"""

from nocturnusai import SyncNocturnusAIClient


def main() -> None:
    print("=" * 60)
    print("NocturnusAI Homepage Walkthrough")
    print("=" * 60)

    with SyncNocturnusAIClient("http://localhost:9300") as client:
        # ─── Turn 1: Initial support ticket context ─────────────────
        print("\n--- Turn 1: Sending initial turns ---")
        turn1 = client.process_turns(
            turns=[
                "User: We cannot log in after the Okta cutover.",
                "Tool crm_lookup: account=acme tier=enterprise",
            ],
            scope="ticket-4821",
            session_id="ticket-4821",
        )
        print(f"Facts extracted: {turn1.new_facts_extracted}")

        if turn1.briefing_delta:
            print(f"\nbriefingDelta:\n{turn1.briefing_delta}")
        else:
            print("\n(briefingDelta is null — no LLM configured or extraction disabled)")

        # ─── Turn 2: New SAML audit findings ────────────────────────
        print("\n--- Turn 2: Sending SAML audit findings ---")
        turn2 = client.process_turns(
            turns=[
                "Tool auth_audit: 14 failed SAML assertions since 09:12 UTC.",
                "Tool auth_audit: issuer mismatch after IdP migration.",
            ],
            scope="ticket-4821",
            session_id="ticket-4821",
        )
        print(f"Facts extracted this turn: {turn2.new_facts_extracted}")

        if turn2.briefing_delta:
            print(f"\nbriefingDelta (only what's new):\n{turn2.briefing_delta}")
        else:
            print("\n(briefingDelta is null — no LLM configured or extraction disabled)")

        # ─── Cleanup: Delete the conversation scope ─────────────────
        print("\n--- Cleanup: Deleting scope ticket-4821 ---")
        result = client.delete_scope("ticket-4821")
        print(f"Deleted {result.get('deleted', 0)} facts")

    print("\nDone! The conversation data has been cleaned up.")


if __name__ == "__main__":
    main()
