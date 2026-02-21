/**
 * 01_basics.ts — Assert facts, query, and retract
 *
 * Demonstrates:
 *   - NocturnusAIClient initialization
 *   - assertFact()
 *   - query()
 *   - infer()
 *   - retract()
 */

import { NocturnusAIClient } from "@nocturnusai/sdk";

const SERVER = "http://localhost:9300";

async function main() {
  const client = new NocturnusAIClient({
    baseUrl: SERVER,
    database: "demo-basics-ts",
  });

  console.log("=== 1. Assert Facts ===");
  await client.assertFact("human", ["socrates"]);
  await client.assertFact("human", ["plato"]);
  await client.assertFact("human", ["aristotle"]);
  await client.assertFact("teacher", ["socrates", "plato"]);
  await client.assertFact("teacher", ["plato", "aristotle"]);
  console.log("Asserted: human(socrates), human(plato), human(aristotle)");
  console.log("Asserted: teacher(socrates, plato), teacher(plato, aristotle)");

  console.log("\n=== 2. Query (pattern match) ===");
  const humans = await client.query("human", ["?x"]);
  for (const atom of humans) {
    console.log(`  ${atom.predicate}(${atom.args.join(", ")})`);
  }

  console.log("\n=== 3. Query with bound argument ===");
  const studentsOfSocrates = await client.query("teacher", ["socrates", "?who"]);
  for (const atom of studentsOfSocrates) {
    console.log(`  ${atom.predicate}(${atom.args.join(", ")})`);
  }

  console.log("\n=== 4. Infer (backward chaining) ===");
  const results = await client.infer("human", ["?x"]);
  console.log(`  Inferred ${results.length} human(s)`);

  console.log("\n=== 5. Retract a fact ===");
  await client.retract("teacher", ["socrates", "plato"]);
  const remaining = await client.query("teacher", ["?x", "?y"]);
  console.log(`  After retraction, ${remaining.length} teacher relationship(s) remain`);
  for (const atom of remaining) {
    console.log(`  ${atom.predicate}(${atom.args.join(", ")})`);
  }

  console.log("\n=== 6. Negated fact ===");
  await client.assertFact("mortal", ["gods"], { negated: true });
  const neg = await client.query("mortal", ["gods"]);
  if (neg.length > 0) {
    console.log(`  mortal(gods) negated=${neg[0].negated}`);
  }

  console.log("\nDone.");
}

main().catch(console.error);
