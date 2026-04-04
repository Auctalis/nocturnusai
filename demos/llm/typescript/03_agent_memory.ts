/**
 * 03_agent_memory.ts — NocturnusAI as a reasoning memory backend for AI agents
 *
 * This is the canonical "why NocturnusAI" demo.
 *
 * Most AI agents forget everything between turns. RAG systems retrieve
 * unstructured blobs. NocturnusAI gives your agent a structured,
 * reasoning-capable memory that can:
 *
 *   • Remember facts with temporal scope (validFrom / validUntil / ttl)
 *   • Define rules and derive new facts automatically (backward chaining)
 *   • Rank knowledge by salience so the most relevant facts surface first
 *   • Consolidate episodic observations into durable semantic memory
 *   • Forget stale facts automatically (decay) or on demand
 *
 * This demo simulates three agent sessions over a persistent knowledge base:
 *
 *   Session 1 — Onboarding:    agent learns about the user
 *   Session 2 — Task planning: agent reasons over memory to plan work
 *   Session 3 — Review:        agent retrieves context, handles temporal queries
 */

import { NocturnusAIClient, ScoredAtom } from "nocturnusai-sdk";

const SERVER = "http://localhost:9300";
const DB = "agent-memory-demo-ts";

function section(title: string) {
  console.log(`\n${"─".repeat(55)}`);
  console.log(`  ${title}`);
  console.log("─".repeat(55));
}

function sleep(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

// ─────────────────────────────────────────────────────────────────────────────
// SESSION 1 — Onboarding
// ─────────────────────────────────────────────────────────────────────────────
async function sessionOne(client: NocturnusAIClient) {
  section("SESSION 1 — Onboarding");
  console.log("Agent: Learning user preferences and context...\n");

  await client.assertFact("user",       ["alice"]);
  await client.assertFact("prefers",    ["alice", "typescript"],  { metadata: { source: "signup" } });
  await client.assertFact("prefers",    ["alice", "dark-mode"],   { metadata: { source: "settings" } });
  await client.assertFact("prefers",    ["alice", "concise"],     { metadata: { source: "feedback" } });
  await client.assertFact("role",       ["alice", "staff-engineer"]);
  await client.assertFact("team",       ["alice", "platform"]);
  await client.assertFact("timezone",   ["alice", "US/Pacific"]);

  // Time-bounded active sprint
  const nowMs = Date.now();
  const sprintEnd = nowMs + 14 * 24 * 3600 * 1000; // 2 weeks
  await client.assertFact("active_sprint", ["alice", "sprint-47"], {
    validFrom: nowMs,
    validUntil: sprintEnd,
    metadata: { goal: "ship-observability-v2" },
  });

  // Inference rules the agent will use later
  await client.assertRule(
    { predicate: "high_context_item", args: ["alice", "?x"] },
    [{ predicate: "prefers", args: ["alice", "?x"] }]
  );
  await client.assertRule(
    { predicate: "team_member", args: ["?person", "platform"] },
    [{ predicate: "team", args: ["?person", "platform"] }]
  );

  // Boost salience on important facts
  await client.setPriority("role",  ["alice", "staff-engineer"], 0.9);
  await client.setPriority("team",  ["alice", "platform"],       0.85);

  const schema = await client.predicates();
  console.log(`  Stored ${schema.totalFacts} facts, ${schema.totalRules} rules`);
  console.log("  User profile established.");
}

// ─────────────────────────────────────────────────────────────────────────────
// SESSION 2 — Task planning
// ─────────────────────────────────────────────────────────────────────────────
async function sessionTwo(client: NocturnusAIClient) {
  section("SESSION 2 — Task planning (reasoning over memory)");

  console.log("Agent: Loading working memory for alice...\n");
  const ctx = await client.contextWindow({
    maxFacts: 6,
    predicates: ["prefers", "role", "team"],
  });
  console.log("  Working memory:");
  for (const f of ctx.facts) {
    const sal = (f as ScoredAtom).salience?.toFixed(2) ?? "0.00";
    console.log(`    [${sal}] ${f.predicate}(${f.args.join(", ")})`);
  }

  console.log("\nAgent: Inferring high-context items...\n");
  const highCtx = await client.infer("high_context_item", ["alice", "?item"]);
  console.log(`  High-context items: ${highCtx.map((a) => a.args[1]).join(", ")}`);

  console.log("\nAgent: Recording task progress observations...\n");
  for (const status of ["started", "in-review", "merged"]) {
    await client.assertFact("task_event", ["alice", "observability-dashboard", status], {
      metadata: { timestamp: Date.now() },
    });
    await sleep(50);
  }

  console.log("Agent: Consolidating episodic observations...\n");
  const consolidation = await client.consolidate();
  console.log(`  Consolidated ${consolidation.factsConsolidated} observation(s)`);

  await client.assertFact("completed_task", ["alice", "observability-dashboard"]);
  await client.assertFact("working_on",     ["alice", "alerting-pipeline"]);
  await client.setPriority("working_on", ["alice", "alerting-pipeline"], 0.95);

  console.log("\n  Task state updated in memory.");
}

// ─────────────────────────────────────────────────────────────────────────────
// SESSION 3 — Review
// ─────────────────────────────────────────────────────────────────────────────
async function sessionThree(client: NocturnusAIClient) {
  section("SESSION 3 — Context review + temporal awareness");

  console.log("Agent: Retrieving full context window...\n");
  const ctx = await client.contextWindow({ maxFacts: 10 });
  console.log(`  Total facts available: ${ctx.totalAvailable}`);
  console.log(`  Context window (${ctx.facts.length} facts):`);
  for (const f of ctx.facts) {
    const sal = (f as ScoredAtom).salience?.toFixed(2) ?? "0.00";
    console.log(`    [${sal}] ${f.predicate}(${f.args.join(", ")})`);
  }

  console.log("\nAgent: Is sprint-47 currently active?\n");
  const nowMs = Date.now();
  const active = await client.temporalQuery("active_sprint", ["alice", "sprint-47"], nowMs);
  console.log(`  sprint-47 active right now: ${active.length > 0 ? "yes" : "no"}`);

  console.log("\nAgent: Short-lived hint (TTL = 2 seconds)...\n");
  await client.assertFact("hint", ["check-flaky-tests"], { ttl: 2000 });
  const immediate = await client.query("hint", ["check-flaky-tests"]);
  console.log(`  hint present immediately: ${immediate.length} result(s)`);
  console.log("  Waiting 2.5s...");
  await sleep(2500);
  const decay = await client.decay(0.0);
  const afterTtl = await client.query("hint", ["check-flaky-tests"]);
  console.log(`  hint after TTL + decay: ${afterTtl.length} result(s)`);
  console.log(`  Decay removed ${decay.expiredCount} expired fact(s)`);

  console.log("\nAgent: Knowledge base snapshot...\n");
  const schema = await client.predicates();
  console.log(`  Predicates: ${schema.totalPredicates}  Facts: ${schema.totalFacts}  Rules: ${schema.totalRules}`);
  for (const p of schema.predicates) {
    console.log(`    ${p.predicate}/${p.arity}  facts=${p.factCount}  rules=${p.ruleCount}`);
  }
}

// ─────────────────────────────────────────────────────────────────────────────
async function main() {
  console.log("\nNocturnusAI — Agent Memory Lifecycle Demo");
  console.log("=".repeat(55));
  console.log("Simulating 3 agent sessions over a shared knowledge base.");
  console.log("The KB persists across sessions — this is the point.\n");

  const client = new NocturnusAIClient({ baseUrl: SERVER, database: DB });

  await sessionOne(client);
  await sessionTwo(client);
  await sessionThree(client);

  console.log("\n" + "=".repeat(55));
  console.log("Done. The knowledge base continues to exist at DB:", DB);
  console.log("Re-run and the agent will find it already populated.");
  console.log("=".repeat(55));
}

main().catch(console.error);
