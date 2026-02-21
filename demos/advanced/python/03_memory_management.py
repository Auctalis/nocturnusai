"""
03_memory_management.py — Agent memory lifecycle management

Demonstrates:
  - context_window() — salience-ranked retrieval for agent context
  - temporal_query() — point-in-time fact lookup
  - set_priority() — boost salience of important facts
  - query with salient facts
  - consolidate() — compress episodic patterns into semantic facts
  - decay() — evict expired / low-salience facts
  - TTL-based automatic expiry
"""

import time
from nocturnusai import SyncNocturnusAIClient

SERVER = "http://localhost:9300"


def main():
    with SyncNocturnusAIClient(SERVER, database="demo-memory") as client:

        print("=== 1. Seeding agent knowledge base ===")
        client.assert_fact("task", ["write-report"], metadata={"status": "pending"})
        client.assert_fact("task", ["send-email"], metadata={"status": "pending"})
        client.assert_fact("task", ["review-pr"], metadata={"status": "done"})
        client.assert_fact("user_preference", ["theme", "dark"])
        client.assert_fact("user_preference", ["language", "python"])
        client.assert_fact("session_context", ["user", "alice"])
        client.assert_fact("session_context", ["project", "atlas"])
        print("  Asserted 7 facts across task / user_preference / session_context")

        print("\n=== 2. Context window (top-N by salience) ===")
        ctx = client.context_window(max_facts=5)
        print(f"  Context window ({len(ctx.facts)} facts, {ctx.total_available} available):")
        for f in ctx.facts:
            score = getattr(f, "salience", "n/a")
            print(f"    [{score:.2f}] {f.predicate}({', '.join(f.args)})")

        print("\n=== 3. Boost priority on critical facts ===")
        client.set_priority("task", ["write-report"], priority=0.95)
        client.set_priority("session_context", ["user", "alice"], priority=0.90)

        ctx2 = client.context_window(max_facts=3)
        print("  Top-3 after boosting write-report and session_context/user:")
        for f in ctx2.facts:
            score = getattr(f, "salience", "n/a")
            print(f"    [{score:.2f}] {f.predicate}({', '.join(f.args)})")

        print("\n=== 4. Temporal query (point-in-time) ===")
        # Assert a fact with a TTL of 2 seconds to demonstrate expiry
        now_ms = int(time.time() * 1000)
        past_ms = now_ms - 5000  # 5 seconds ago
        client.assert_fact("event", ["login"], valid_from=past_ms, valid_until=now_ms + 10000)

        ts_during = now_ms - 2000  # while event was/is valid
        temporal_results = client.temporal_query("event", ["login"], timestamp=ts_during)
        print(f"  event(login) at timestamp (2s ago): {len(temporal_results)} result(s)")

        ts_before = past_ms - 1000  # before event was valid
        temporal_results_before = client.temporal_query("event", ["login"], timestamp=ts_before)
        print(f"  event(login) before validFrom: {len(temporal_results_before)} result(s)")

        print("\n=== 5. Short-lived fact with TTL ===")
        client.assert_fact("ephemeral", ["cache-warm"], ttl=1000)  # 1 second TTL
        immediate = client.query("ephemeral", ["cache-warm"])
        print(f"  ephemeral(cache-warm) right after assert: {len(immediate)} result(s)")
        print("  Waiting 1.5 seconds for TTL expiry...")
        time.sleep(1.5)
        # Trigger decay to sweep expired facts
        decay_result = client.decay(threshold=0.0)
        after_ttl = client.query("ephemeral", ["cache-warm"])
        print(f"  ephemeral(cache-warm) after TTL + decay: {len(after_ttl)} result(s)")
        print(f"  Decay removed {decay_result.expired_count} expired fact(s)")

        print("\n=== 6. Consolidation ===")
        # Simulate repeated episodic observations
        for i in range(5):
            client.assert_fact("observation", ["user-active"], metadata={"tick": i})
        result = client.consolidate()
        print(f"  Consolidated {result.facts_consolidated} fact(s)")
        if result.new_facts:
            print(f"  New semantic facts created: {len(result.new_facts)}")

        print("\n=== 7. Decay — evict low-salience facts ===")
        # Add some throw-away facts
        client.assert_fact("noise", ["tmp1"])
        client.assert_fact("noise", ["tmp2"])
        decay2 = client.decay(threshold=0.1)
        print(f"  Decay evicted {decay2.evicted_count} low-salience fact(s)")

        print("\nDone.")


if __name__ == "__main__":
    main()
