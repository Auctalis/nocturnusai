/**
 * 02_rules_and_inference.ts — Define Horn clause rules and run backward-chaining inference
 *
 * Demonstrates:
 *   - assertRule()
 *   - infer() with and without proof trees
 *   - Variable syntax (?x, ?y, ?z)
 *   - Multi-hop reasoning and transitive closure
 */

import { NocturnusAIClient, ProofTree } from "@nocturnusai/sdk";

const SERVER = "http://localhost:9300";

function printProof(node: unknown, indent = 0): void {
  const pad = " ".repeat(indent);
  const n = node as Record<string, unknown>;
  if (n.atom) {
    const a = n.atom as { predicate: string; args: string[] };
    console.log(`${pad}${a.predicate}(${a.args.join(", ")})`);
    const children = n.children as unknown[] | undefined;
    if (children?.length) children.forEach((c) => printProof(c, indent + 2));
  } else if (n.predicate) {
    console.log(`${pad}${n.predicate}(${(n.args as string[]).join(", ")})`);
    const children = n.children as unknown[] | undefined;
    if (children?.length) children.forEach((c) => printProof(c, indent + 2));
  }
}

async function main() {
  const client = new NocturnusAIClient({
    baseUrl: SERVER,
    database: "demo-rules-ts",
  });

  console.log("=== 1. Classic Syllogism ===");
  await client.assertRule(
    { predicate: "mortal", args: ["?x"] },
    [{ predicate: "human", args: ["?x"] }]
  );
  await client.assertFact("human", ["socrates"]);
  await client.assertFact("human", ["plato"]);

  const mortals = await client.infer("mortal", ["?who"]);
  console.log("mortal(?who) →");
  for (const a of mortals) {
    console.log(`  mortal(${a.args.join(", ")})`);
  }

  console.log("\n=== 2. Multi-hop: grandparent rule ===");
  await client.assertRule(
    { predicate: "grandparent", args: ["?x", "?z"] },
    [
      { predicate: "parent", args: ["?x", "?y"] },
      { predicate: "parent", args: ["?y", "?z"] },
    ]
  );
  await client.assertFact("parent", ["alice", "bob"]);
  await client.assertFact("parent", ["bob", "charlie"]);
  await client.assertFact("parent", ["bob", "diana"]);

  const grandchildren = await client.infer("grandparent", ["alice", "?grandchild"]);
  console.log("grandparent(alice, ?grandchild) →");
  for (const a of grandchildren) {
    console.log(`  grandparent(${a.args.join(", ")})`);
  }

  console.log("\n=== 3. Ancestor rule (transitive closure) ===");
  await client.assertRule(
    { predicate: "ancestor", args: ["?x", "?y"] },
    [{ predicate: "parent", args: ["?x", "?y"] }]
  );
  await client.assertRule(
    { predicate: "ancestor", args: ["?x", "?z"] },
    [
      { predicate: "parent",   args: ["?x", "?y"] },
      { predicate: "ancestor", args: ["?y", "?z"] },
    ]
  );

  const ancestors = await client.infer("ancestor", ["alice", "?desc"]);
  console.log("ancestor(alice, ?desc) →");
  for (const a of ancestors) {
    console.log(`  ancestor(${a.args.join(", ")})`);
  }

  console.log("\n=== 4. Proof trees ===");
  const proofs = await client.infer("mortal", ["socrates"], { withProof: true });
  console.log("Proof that mortal(socrates):");
  for (const proof of proofs) {
    printProof(proof, 2);
  }

  console.log("\nDone.");
}

main().catch(console.error);
