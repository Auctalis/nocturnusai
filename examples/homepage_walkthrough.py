"""
Homepage Walkthrough Example
=============================
Follows the "Try It" section from the NocturnusAI front page.

Prerequisites:
  Start the server with one of:
    docker run -p 9300:9300 ghcr.io/auctalis/nocturnusai:latest
    ./gradlew :nocturnusai-server:run

Then run:
    python examples/homepage_walkthrough.py
"""

import requests
import json

BASE_URL = "http://localhost:9300"
HEADERS = {
    "Content-Type": "application/json",
    "X-Tenant-ID": "default",
}


def post_context(turns: list[str], scope: str, session_id: str) -> dict:
    """Send turns to POST /context and return the JSON response."""
    payload = {
        "turns": turns,
        "scope": scope,
        "sessionId": session_id,
    }
    resp = requests.post(f"{BASE_URL}/context", headers=HEADERS, json=payload)
    resp.raise_for_status()
    return resp.json()


def main():
    print("=" * 60)
    print("NocturnusAI Homepage Walkthrough")
    print("=" * 60)

    # ─── Turn 1: Initial support ticket context ─────────────────
    print("\n--- Turn 1: Sending initial turns ---")
    turn1 = post_context(
        turns=[
            "User: We cannot log in after the Okta cutover.",
            "Tool crm_lookup: account=acme tier=enterprise",
        ],
        scope="ticket-4821",
        session_id="ticket-4821",
    )
    print(json.dumps(turn1, indent=2))

    if turn1.get("briefingDelta"):
        print(f"\nbriefingDelta:\n{turn1['briefingDelta']}")
    else:
        print("\n(briefingDelta is null — no LLM configured or extraction disabled)")

    print(f"Facts extracted: {turn1.get('newFactsExtracted', 0)}")

    # ─── Turn 2: New SAML audit findings ────────────────────────
    print("\n--- Turn 2: Sending SAML audit findings ---")
    turn2 = post_context(
        turns=[
            "Tool auth_audit: 14 failed SAML assertions since 09:12 UTC.",
            "Tool auth_audit: issuer mismatch after IdP migration.",
        ],
        scope="ticket-4821",
        session_id="ticket-4821",
    )
    print(json.dumps(turn2, indent=2))

    if turn2.get("briefingDelta"):
        print(f"\nbriefingDelta (only what's new):\n{turn2['briefingDelta']}")
    else:
        print("\n(briefingDelta is null — no LLM configured or extraction disabled)")

    print(f"Facts extracted this turn: {turn2.get('newFactsExtracted', 0)}")

    # ─── Cleanup: Delete the conversation scope ─────────────────
    print("\n--- Cleanup: Deleting scope ticket-4821 ---")
    resp = requests.delete(
        f"{BASE_URL}/scope/ticket-4821", headers=HEADERS
    )
    resp.raise_for_status()
    print(json.dumps(resp.json(), indent=2))

    print("\nDone! The conversation data has been cleaned up.")


if __name__ == "__main__":
    main()
