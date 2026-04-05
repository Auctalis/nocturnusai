"""
08_agent_workflow.py — Full agent memory lifecycle end-to-end

Simulates an AI agent that:
  1. Bootstraps its knowledge base with world facts and rules
  2. Runs multi-hop inference to derive new facts
  3. Retrieves a salience-ranked context window before each "turn"
  4. Uses temporal awareness to ask time-bounded questions
  5. Consolidates episodic observations into semantic memory
  6. Uses a transaction to atomically update a set of related facts
  7. Decays low-priority stale knowledge

This is the closest thing to how a real AI agent would use NocturnusAI.
"""

import asyncio
import time
from nocturnusai import NocturnusAIClient

SERVER = "http://localhost:9300"
DB = "demo-agent"


async def main():
    async with NocturnusAIClient(SERVER, database=DB) as client:
        await client.ensure_database()

        # ── 1. World knowledge ────────────────────────────────────────────────
        print("=== 1. Bootstrap world knowledge ===")
        facts = [
            ("person",       ["alice"]),
            ("person",       ["bob"]),
            ("person",       ["charlie"]),
            ("works_at",     ["alice",   "acme"]),
            ("works_at",     ["bob",     "acme"]),
            ("works_at",     ["charlie", "globex"]),
            ("manages",      ["alice",   "bob"]),
            ("skill",        ["alice",   "python"]),
            ("skill",        ["alice",   "ml"]),
            ("skill",        ["bob",     "python"]),
            ("skill",        ["charlie", "rust"]),
        ]
        for pred, args in facts:
            await client.assert_fact(pred, args)
        print(f"  Asserted {len(facts)} world facts")

        # ── 2. Inference rules ───────────────────────────────────────────────
        print("\n=== 2. Define inference rules ===")
        rules = [
            # colleague: same company
            {
                "head": {"predicate": "colleague", "args": ["?x", "?y"]},
                "body": [
                    {"predicate": "works_at", "args": ["?x", "?company"]},
                    {"predicate": "works_at", "args": ["?y", "?company"]},
                ],
            },
            # report_to: bob reports to alice if alice manages bob
            {
                "head": {"predicate": "reports_to", "args": ["?employee", "?manager"]},
                "body": [{"predicate": "manages", "args": ["?manager", "?employee"]}],
            },
            # team_has_skill: company has a skill if person has it
            {
                "head": {"predicate": "team_skill", "args": ["?company", "?skill"]},
                "body": [
                    {"predicate": "works_at", "args": ["?person", "?company"]},
                    {"predicate": "skill",    "args": ["?person", "?skill"]},
                ],
            },
        ]
        for rule in rules:
            await client.assert_rule(**rule)
        print(f"  Defined {len(rules)} rules")

        # ── 3. Inference ────────────────────────────────────────────────────
        print("\n=== 3. Run inference ===")
        colleagues = await client.infer("colleague", ["alice", "?who"])
        print(f"  alice's colleagues: {[a.args[1] for a in colleagues if a.args[1] != 'alice']}")

        manager = await client.infer("reports_to", ["bob", "?mgr"])
        print(f"  bob reports to: {[a.args[1] for a in manager]}")

        acme_skills = await client.infer("team_skill", ["acme", "?skill"])
        print(f"  acme team skills: {[a.args[1] for a in acme_skills]}")

        # ── 4. Context window (agent's working memory) ────────────────────
        print("\n=== 4. Agent context window (before user turn) ===")
        # Boost priority on the most relevant facts for this session
        await client.set_priority("works_at", ["alice", "acme"], priority=0.9)
        await client.set_priority("manages",  ["alice", "bob"],   priority=0.85)

        ctx = await client.context_window(max_facts=6, predicates=["person", "works_at", "manages", "skill"])
        print(f"  Top {len(ctx.facts)} relevant facts:")
        for f in ctx.facts:
            sal = getattr(f, "salience", 0)
            print(f"    [{sal:.2f}] {f.predicate}({', '.join(f.args)})")

        # ── 5. Temporal awareness ────────────────────────────────────────
        print("\n=== 5. Temporal facts ===")
        now_ms = int(time.time() * 1000)
        # Assert a time-bounded meeting event
        await client.assert_fact(
            "meeting",
            ["alice", "bob", "q1-planning"],
            valid_from=now_ms - 3600_000,   # started 1 hour ago
            valid_until=now_ms + 3600_000,  # ends 1 hour from now
        )
        # Query at current time
        at_now = await client.temporal_query("meeting", ["alice", "bob", "?topic"], timestamp=now_ms)
        print(f"  Active meetings for alice+bob right now: {len(at_now)}")

        # Query 2 hours ago (before meeting started)
        at_before = await client.temporal_query("meeting", ["alice", "bob", "?topic"], timestamp=now_ms - 7200_000)
        print(f"  Active meetings 2 hours ago: {len(at_before)}")

        # ── 6. Transaction — atomic knowledge update ─────────────────────
        print("\n=== 6. Atomic role change (transaction) ===")
        tx = await client.begin_transaction()
        await client.retract("works_at",  ["bob", "acme"],   transaction_id=tx)
        await client.assert_fact("works_at", ["bob", "globex"], transaction_id=tx)
        await client.assert_fact("works_at", ["bob", "globex"],  # also assert collab
                                  metadata={"reason": "transfer"}, transaction_id=tx)
        await client.commit_transaction(tx)
        bob_company = await client.query("works_at", ["bob", "?company"])
        print(f"  bob now works at: {[a.args[1] for a in bob_company]}")

        # ── 7. Episodic observations → consolidation ─────────────────────
        print("\n=== 7. Episodic observations + consolidation ===")
        for i in range(6):
            await client.assert_fact("observation", ["alice-online"], metadata={"tick": i})
        consolidation = await client.consolidate()
        print(f"  Consolidated {consolidation.facts_consolidated} episodic fact(s)")

        # ── 8. Decay ───────────────────────────────────────────────────
        print("\n=== 8. Memory decay ===")
        decay = await client.decay(threshold=0.05)
        print(f"  Expired: {decay.expired_count}  Evicted: {decay.evicted_count}")

        # ── 9. Schema snapshot ────────────────────────────────────────
        print("\n=== 9. Knowledge base schema ===")
        schema = await client.predicates()
        print(f"  {schema.get('totalPredicates')} predicates, "
              f"{schema.get('totalFacts')} facts, "
              f"{schema.get('totalRules')} rules")

        print("\nDone — full agent workflow complete.")


if __name__ == "__main__":
    asyncio.run(main())
