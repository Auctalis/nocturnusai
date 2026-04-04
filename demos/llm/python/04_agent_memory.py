"""
04_agent_memory.py — NocturnusAI as a reasoning memory backend for AI agents

This is the canonical "why NocturnusAI" demo.

Most AI agents forget everything between turns. RAG systems retrieve
unstructured blobs. NocturnusAI is different: it gives your agent a
*structured, reasoning-capable* memory that can:

  • Remember facts with temporal scope (valid_from / valid_until / ttl)
  • Define rules and derive new facts automatically (backward chaining)
  • Rank knowledge by salience so the most relevant facts surface first
  • Consolidate episodic observations into durable semantic memory
  • Forget stale facts automatically (decay) or on demand

This demo simulates three "agent sessions" with a persistent knowledge base:

  Session 1 — Onboarding:    agent learns about the user
  Session 2 — Task planning: agent reasons over memory to plan work
  Session 3 — Review:        agent retrieves context, detects what changed

No LLM API key required — the reasoning is done by NocturnusAI's engine.
"""

import asyncio
import time
from nocturnusai import NocturnusAIClient

SERVER = "http://localhost:9300"
DB = "agent-memory-demo"


# ── Helper ────────────────────────────────────────────────────────────────────
def print_section(title: str):
    print(f"\n{'─'*55}")
    print(f"  {title}")
    print(f"{'─'*55}")


async def get_context(client: NocturnusAIClient, predicates=None, n=8):
    ctx = await client.context_window(max_facts=n, predicates=predicates)
    return ctx.facts


# ─────────────────────────────────────────────────────────────────────────────
# SESSION 1 — Onboarding: learn about the user
# ─────────────────────────────────────────────────────────────────────────────
async def session_one(client: NocturnusAIClient):
    print_section("SESSION 1 — Onboarding")

    # Store durable user facts
    print("Agent: Learning user preferences and context...\n")
    await client.assert_fact("user",       ["alice"])
    await client.assert_fact("prefers",    ["alice", "python"],        metadata={"source": "signup"})
    await client.assert_fact("prefers",    ["alice", "dark-mode"],     metadata={"source": "settings"})
    await client.assert_fact("prefers",    ["alice", "concise"],       metadata={"source": "feedback"})
    await client.assert_fact("role",       ["alice", "staff-engineer"])
    await client.assert_fact("team",       ["alice", "platform"])
    await client.assert_fact("timezone",   ["alice", "US/Pacific"])

    # Time-bounded context: active sprint
    now_ms = int(time.time() * 1000)
    sprint_end = now_ms + (14 * 24 * 3600 * 1000)  # 2 weeks
    await client.assert_fact(
        "active_sprint", ["alice", "sprint-47"],
        valid_from=now_ms, valid_until=sprint_end,
        metadata={"goal": "ship-observability-v2"}
    )

    # Store rules the agent will use later
    await client.assert_rule(
        head={"predicate": "high_context_item", "args": ["alice", "?x"]},
        body=[{"predicate": "prefers", "args": ["alice", "?x"]}],
    )
    await client.assert_rule(
        head={"predicate": "team_member", "args": ["?person", "platform"]},
        body=[{"predicate": "team", "args": ["?person", "platform"]}],
    )

    # Boost salience on the most relevant items
    await client.set_priority("role",    ["alice", "staff-engineer"], priority=0.9)
    await client.set_priority("team",    ["alice", "platform"],       priority=0.85)

    schema = await client.predicates()
    print(f"  Stored {schema.get('totalFacts')} facts, {schema.get('totalRules')} rules")
    print("  User profile established.\n")


# ─────────────────────────────────────────────────────────────────────────────
# SESSION 2 — Task planning: agent reasons over memory
# ─────────────────────────────────────────────────────────────────────────────
async def session_two(client: NocturnusAIClient):
    print_section("SESSION 2 — Task planning (reasoning over memory)")

    # ── What does the agent know about alice? ─────────────────────────────
    print("Agent: Loading working memory for alice...\n")
    ctx_facts = await get_context(client, predicates=["prefers", "role", "team"], n=6)
    print("  Working memory:")
    for f in ctx_facts:
        sal = getattr(f, "salience", 0)
        print(f"    [{sal:.2f}] {f.predicate}({', '.join(f.args)})")

    # ── Infer high-context items ──────────────────────────────────────────
    print("\nAgent: Inferring high-context items for this session...\n")
    high_ctx = await client.infer("high_context_item", ["alice", "?item"])
    print(f"  High-context items: {[a.args[1] for a in high_ctx]}")

    # ── Record task observations (episodic memory) ────────────────────────
    print("\nAgent: Recording task progress observations...\n")
    for status in ["started", "in-review", "merged"]:
        await client.assert_fact(
            "task_event", ["alice", "observability-dashboard", status],
            metadata={"timestamp": int(time.time() * 1000)}
        )
        await asyncio.sleep(0.05)  # slight delay for temporal ordering

    # ── Consolidate episodic → semantic ───────────────────────────────────
    print("Agent: Consolidating episodic observations into semantic memory...\n")
    consolidation = await client.consolidate()
    print(f"  Consolidated {consolidation.facts_consolidated} observation(s)")

    # ── Store task outcomes ───────────────────────────────────────────────
    await client.assert_fact("completed_task", ["alice", "observability-dashboard"])
    await client.assert_fact("working_on",     ["alice", "alerting-pipeline"])
    await client.set_priority("working_on", ["alice", "alerting-pipeline"], priority=0.95)

    print("\n  Task state updated in memory.")


# ─────────────────────────────────────────────────────────────────────────────
# SESSION 3 — Review: agent retrieves context and handles temporal queries
# ─────────────────────────────────────────────────────────────────────────────
async def session_three(client: NocturnusAIClient):
    print_section("SESSION 3 — Context review + temporal awareness")

    # ── Full context retrieval ────────────────────────────────────────────
    print("Agent: Retrieving full context window...\n")
    ctx = await client.context_window(max_facts=10)
    print(f"  Total facts available: {ctx.total_available}")
    print(f"  Context window ({len(ctx.facts)} facts):")
    for f in ctx.facts:
        sal = getattr(f, "salience", 0)
        print(f"    [{sal:.2f}] {f.predicate}({', '.join(f.args)})")

    # ── Is the sprint still active? (temporal query) ──────────────────────
    print("\nAgent: Checking if sprint-47 is currently active...\n")
    now_ms = int(time.time() * 1000)
    active = await client.temporal_query("active_sprint", ["alice", "sprint-47"], timestamp=now_ms)
    print(f"  sprint-47 active right now: {'yes' if active else 'no'}")

    # ── Short-lived task hint (TTL demo) ──────────────────────────────────
    print("\nAgent: Storing a 2-second hint (TTL=2000ms)...\n")
    await client.assert_fact("hint", ["check-flaky-tests"], ttl=2000)
    immediate = await client.query("hint", ["check-flaky-tests"])
    print(f"  hint present immediately: {len(immediate)} result(s)")
    await asyncio.sleep(2.5)
    decay = await client.decay(threshold=0.0)
    after_ttl = await client.query("hint", ["check-flaky-tests"])
    print(f"  hint after TTL expired + decay: {len(after_ttl)} result(s)")
    print(f"  (decay removed {decay.expired_count} expired fact(s))")

    # ── Schema snapshot ───────────────────────────────────────────────────
    print("\nAgent: Knowledge base snapshot...\n")
    schema = await client.predicates()
    print(f"  Predicates: {schema.get('totalPredicates')}  "
          f"Facts: {schema.get('totalFacts')}  "
          f"Rules: {schema.get('totalRules')}")
    for p in schema.get("predicates", []):
        print(f"    {p['predicate']}/{p['arity']}  "
              f"facts={p['factCount']}  rules={p['ruleCount']}")


# ─────────────────────────────────────────────────────────────────────────────
async def main():
    print("\nNocturnusAI — Agent Memory Lifecycle Demo")
    print("=" * 55)
    print("This simulates 3 agent sessions over a shared knowledge base.")
    print("The KB persists across sessions — this is the point.")

    async with NocturnusAIClient(SERVER, database=DB) as client:
        await session_one(client)
        await session_two(client)
        await session_three(client)

    print("\n" + "=" * 55)
    print("Done. The knowledge base continues to exist at DB:", DB)
    print("Re-run and the agent will find it already populated.")
    print("=" * 55)


if __name__ == "__main__":
    asyncio.run(main())
