/**
 * 03_memory_management.ts — Agent memory lifecycle management
 *
 * Demonstrates:
 *   - contextWindow() — salience-ranked retrieval
 *   - temporalQuery() — point-in-time fact lookup
 *   - setPriority() — boost salience
 *   - consolidate() — compress episodic observations
 *   - decay() — evict expired / low-salience facts
 *   - TTL-based expiry
 */

import { NocturnusAIClient } from "nocturnusai-sdk";

const SERVER = "http://localhost:9300";

function sleep(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function main() {
  const client = new NocturnusAIClient({
    baseUrl: SERVER,
    database: "demo-memory-ts",
  });

  console.log("=== 1. Seeding agent knowledge base ===");
  await client.assertFact("task", ["write-report"], { metadata: { status: "pending" } });
  await client.assertFact("task", ["send-email"],   { metadata: { status: "pending" } });
  await client.assertFact("task", ["review-pr"],    { metadata: { status: "done" } });
  await client.assertFact("user_preference", ["theme",    "dark"]);
  await client.assertFact("user_preference", ["language", "typescript"]);
  await client.assertFact("session_context",  ["user",    "alice"]);
  await client.assertFact("session_context",  ["project", "atlas"]);
  console.log("  Asserted 7 facts across task / user_preference / session_context");

  console.log("\n=== 2. Context window (top-N by salience) ===");
  const ctx = await client.contextWindow({ maxFacts: 5 });
  console.log(`  Context window (${ctx.facts.length} facts, ${ctx.totalAvailable} available):`);
  for (const f of ctx.facts) {
    const score = (f as { salience?: number }).salience ?? "n/a";
    const sal = typeof score === "number" ? score.toFixed(2) : score;
    console.log(`    [${sal}] ${f.predicate}(${f.args.join(", ")})`);
  }

  console.log("\n=== 3. Boost priority on critical facts ===");
  await client.setPriority("task",            ["write-report"],    0.95);
  await client.setPriority("session_context", ["user", "alice"],   0.90);

  const ctx2 = await client.contextWindow({ maxFacts: 3 });
  console.log("  Top-3 after priority boost:");
  for (const f of ctx2.facts) {
    const sal = ((f as { salience?: number }).salience ?? 0).toFixed(2);
    console.log(`    [${sal}] ${f.predicate}(${f.args.join(", ")})`);
  }

  console.log("\n=== 4. Temporal query (point-in-time) ===");
  const nowMs = Date.now();
  const pastMs = nowMs - 5000;
  await client.assertFact("event", ["login"], {
    validFrom: pastMs,
    validUntil: nowMs + 10_000,
  });

  const atNow = await client.temporalQuery("event", ["login"], nowMs - 2000);
  console.log(`  event(login) during valid window: ${atNow.length} result(s)`);

  const beforeValid = await client.temporalQuery("event", ["login"], pastMs - 1000);
  console.log(`  event(login) before validFrom: ${beforeValid.length} result(s)`);

  console.log("\n=== 5. Short-lived fact with TTL ===");
  await client.assertFact("ephemeral", ["cache-warm"], { ttl: 1000 }); // 1 second
  const immediate = await client.query("ephemeral", ["cache-warm"]);
  console.log(`  ephemeral(cache-warm) right after assert: ${immediate.length} result(s)`);
  console.log("  Waiting 1.5 seconds for TTL expiry...");
  await sleep(1500);
  const decayResult = await client.decay(0.0);
  const afterTtl = await client.query("ephemeral", ["cache-warm"]);
  console.log(`  ephemeral(cache-warm) after TTL + decay: ${afterTtl.length} result(s)`);
  console.log(`  Decay removed ${decayResult.expiredCount} expired fact(s)`);

  console.log("\n=== 6. Consolidation ===");
  for (let i = 0; i < 5; i++) {
    await client.assertFact("observation", ["user-active"], { metadata: { tick: i } });
  }
  const consolidation = await client.consolidate();
  console.log(`  Consolidated ${consolidation.factsConsolidated} fact(s)`);
  if (consolidation.newFacts?.length) {
    console.log(`  New semantic facts: ${consolidation.newFacts.length}`);
  }

  console.log("\n=== 7. Decay — evict low-salience facts ===");
  await client.assertFact("noise", ["tmp1"]);
  await client.assertFact("noise", ["tmp2"]);
  const decay2 = await client.decay(0.1);
  console.log(`  Decay evicted ${decay2.evictedCount} low-salience fact(s)`);

  console.log("\nDone.");
}

main().catch(console.error);
