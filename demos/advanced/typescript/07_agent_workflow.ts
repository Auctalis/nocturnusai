/**
 * 07_agent_workflow.ts — Full agent memory lifecycle end-to-end
 *
 * Simulates an AI agent that:
 *   1. Bootstraps its knowledge base with world facts and rules
 *   2. Runs multi-hop inference to derive new facts
 *   3. Retrieves a salience-ranked context window before each "turn"
 *   4. Uses temporal awareness to ask time-bounded questions
 *   5. Consolidates episodic observations into semantic memory
 *   6. Uses a transaction to atomically update related facts
 *   7. Decays low-priority stale knowledge
 *   8. Subscribes to real-time events during the session
 */

import { NocturnusAIClient, KnowledgeEvent } from "@nocturnusai/sdk";

const SERVER = "http://localhost:9300";
const DB = "demo-agent-ts";

async function main() {
  const client = new NocturnusAIClient({ baseUrl: SERVER, database: DB });

  // ── Real-time event log ─────────────────────────────────────────────────
  const eventLog: string[] = [];
  const unsubscribe = client.subscribeEvents({}, (e: KnowledgeEvent) => {
    eventLog.push(`${e.type}: ${e.predicate}(${e.args?.join(", ")})`);
  });

  // ── 1. World knowledge ───────────────────────────────────────────────────
  console.log("=== 1. Bootstrap world knowledge ===");
  const facts: Array<[string, string[]]> = [
    ["person",    ["alice"]],
    ["person",    ["bob"]],
    ["person",    ["charlie"]],
    ["works_at",  ["alice",   "acme"]],
    ["works_at",  ["bob",     "acme"]],
    ["works_at",  ["charlie", "globex"]],
    ["manages",   ["alice",   "bob"]],
    ["skill",     ["alice",   "python"]],
    ["skill",     ["alice",   "ml"]],
    ["skill",     ["bob",     "python"]],
    ["skill",     ["charlie", "rust"]],
  ];
  for (const [pred, args] of facts) {
    await client.assertFact(pred, args);
  }
  console.log(`  Asserted ${facts.length} world facts`);

  // ── 2. Rules ─────────────────────────────────────────────────────────────
  console.log("\n=== 2. Define inference rules ===");
  await client.assertRule(
    { predicate: "colleague", args: ["?x", "?y"] },
    [
      { predicate: "works_at", args: ["?x", "?company"] },
      { predicate: "works_at", args: ["?y", "?company"] },
    ]
  );
  await client.assertRule(
    { predicate: "reports_to", args: ["?employee", "?manager"] },
    [{ predicate: "manages", args: ["?manager", "?employee"] }]
  );
  await client.assertRule(
    { predicate: "team_skill", args: ["?company", "?skill"] },
    [
      { predicate: "works_at", args: ["?person", "?company"] },
      { predicate: "skill",    args: ["?person", "?skill"] },
    ]
  );
  console.log("  Defined 3 rules");

  // ── 3. Inference ──────────────────────────────────────────────────────────
  console.log("\n=== 3. Run inference ===");
  const colleagues = await client.infer("colleague", ["alice", "?who"]);
  console.log(`  alice's colleagues: ${colleagues.filter((a) => a.args[1] !== "alice").map((a) => a.args[1]).join(", ")}`);

  const manager = await client.infer("reports_to", ["bob", "?mgr"]);
  console.log(`  bob reports to: ${manager.map((a) => a.args[1]).join(", ")}`);

  const acmeSkills = await client.infer("team_skill", ["acme", "?skill"]);
  const uniqueSkills = [...new Set(acmeSkills.map((a) => a.args[1]))];
  console.log(`  acme team skills: ${uniqueSkills.join(", ")}`);

  // ── 4. Context window ────────────────────────────────────────────────────
  console.log("\n=== 4. Agent context window ===");
  await client.setPriority("works_at", ["alice", "acme"], 0.9);
  await client.setPriority("manages",  ["alice", "bob"],  0.85);

  const ctx = await client.contextWindow({
    maxFacts: 6,
    predicates: ["person", "works_at", "manages", "skill"],
  });
  console.log(`  Top ${ctx.facts.length} relevant facts:`);
  for (const f of ctx.facts) {
    const sal = ((f as { salience?: number }).salience ?? 0).toFixed(2);
    console.log(`    [${sal}] ${f.predicate}(${f.args.join(", ")})`);
  }

  // ── 5. Temporal ───────────────────────────────────────────────────────────
  console.log("\n=== 5. Temporal facts ===");
  const nowMs = Date.now();
  await client.assertFact("meeting", ["alice", "bob", "q1-planning"], {
    validFrom: nowMs - 3_600_000,
    validUntil: nowMs + 3_600_000,
  });

  const atNow = await client.temporalQuery("meeting", ["alice", "bob", "?topic"], nowMs);
  console.log(`  Active meetings for alice+bob right now: ${atNow.length}`);

  const atBefore = await client.temporalQuery("meeting", ["alice", "bob", "?topic"], nowMs - 7_200_000);
  console.log(`  Active meetings 2 hours ago: ${atBefore.length}`);

  // ── 6. Transaction ────────────────────────────────────────────────────────
  console.log("\n=== 6. Atomic role change (transaction) ===");
  const tx = await client.beginTransaction();
  await client.retract("works_at",  ["bob", "acme"],   { transactionId: tx });
  await client.assertFact("works_at", ["bob", "globex"], { transactionId: tx });
  await client.commitTransaction(tx);
  const bobCompany = await client.query("works_at", ["bob", "?company"]);
  console.log(`  bob now works at: ${bobCompany.map((a) => a.args[1]).join(", ")}`);

  // ── 7. Episodic → consolidation ──────────────────────────────────────────
  console.log("\n=== 7. Consolidation ===");
  for (let i = 0; i < 6; i++) {
    await client.assertFact("observation", ["alice-online"], { metadata: { tick: i } });
  }
  const consolidation = await client.consolidate();
  console.log(`  Consolidated ${consolidation.factsConsolidated} episodic fact(s)`);

  // ── 8. Decay ──────────────────────────────────────────────────────────────
  console.log("\n=== 8. Memory decay ===");
  const decay = await client.decay(0.05);
  console.log(`  Expired: ${decay.expiredCount}  Evicted: ${decay.evictedCount}`);

  // ── 9. Schema ─────────────────────────────────────────────────────────────
  console.log("\n=== 9. Knowledge base schema ===");
  const schema = await client.predicates();
  console.log(`  ${schema.totalPredicates} predicates, ${schema.totalFacts} facts, ${schema.totalRules} rules`);

  // ── 10. Event log ─────────────────────────────────────────────────────────
  unsubscribe();
  console.log(`\n=== 10. Events captured during session: ${eventLog.length} ===`);
  for (const e of eventLog.slice(0, 10)) {
    console.log(`  ${e}`);
  }
  if (eventLog.length > 10) console.log(`  ... and ${eventLog.length - 10} more`);

  console.log("\nDone — full agent workflow complete.");
}

main().catch(console.error);
