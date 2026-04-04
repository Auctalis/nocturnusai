/**
 * 04_transactions.ts — ACID transactions: commit and rollback
 *
 * Demonstrates:
 *   - beginTransaction()
 *   - commitTransaction()
 *   - rollbackTransaction()
 *   - Passing transactionId to assertFact / retract
 */

import { NocturnusAIClient } from "nocturnusai-sdk";

const SERVER = "http://localhost:9300";

async function main() {
  const client = new NocturnusAIClient({
    baseUrl: SERVER,
    database: "demo-txn-ts",
  });

  console.log("=== 1. Successful transaction (commit) ===");
  const txId = await client.beginTransaction();
  console.log(`  Started transaction: ${txId}`);

  await client.assertFact("account", ["alice", "1000"], { transactionId: txId });
  await client.assertFact("account", ["bob",   "500"],  { transactionId: txId });
  await client.commitTransaction(txId);
  console.log("  Committed: account(alice,1000), account(bob,500)");

  const accounts = await client.query("account", ["?name", "?balance"]);
  console.log(`  Visible after commit: ${accounts.length} account(s)`);
  for (const a of accounts) {
    console.log(`    account(${a.args.join(", ")})`);
  }

  console.log("\n=== 2. Rollback on simulated error ===");
  const txId2 = await client.beginTransaction();
  console.log(`  Started transaction: ${txId2}`);

  await client.assertFact("account", ["charlie", "9999"], { transactionId: txId2 });

  try {
    throw new Error("Validation failed — rolling back");
  } catch (err) {
    console.log(`  Error detected: ${(err as Error).message}`);
    await client.rollbackTransaction(txId2);
    console.log("  Rolled back transaction");
  }

  const charlie = await client.query("account", ["charlie", "?bal"]);
  console.log(`  account(charlie,...) after rollback: ${charlie.length} result(s) (expected 0)`);

  console.log("\n=== 3. Retract inside a transaction ===");
  const txId3 = await client.beginTransaction();
  await client.retract("account", ["bob", "500"], { transactionId: txId3 });
  await client.commitTransaction(txId3);
  console.log("  Committed retraction of account(bob,500)");

  const remaining = await client.query("account", ["?name", "?balance"]);
  console.log(`  Remaining accounts: ${remaining.length}`);
  for (const a of remaining) {
    console.log(`    account(${a.args.join(", ")})`);
  }

  console.log("\nDone.");
}

main().catch(console.error);
