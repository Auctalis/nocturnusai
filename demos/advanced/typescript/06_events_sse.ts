/**
 * 06_events_sse.ts — Real-time knowledge change events via SSE
 *
 * Demonstrates:
 *   - subscribeEvents() — subscribe to fact_asserted / fact_retracted / fact_expired
 *   - Unsubscribing after a set number of events
 *   - Filtering events by predicate
 */

import { NocturnusAIClient, KnowledgeEvent } from "nocturnusai-sdk";

const SERVER = "http://localhost:9300";

function sleep(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function main() {
  const client = new NocturnusAIClient({
    baseUrl: SERVER,
    database: "demo-events-ts",
  });

  console.log("=== Subscribing to all knowledge events ===");
  console.log("(Will assert and retract facts, then unsubscribe after 5 events)\n");

  let eventCount = 0;
  const MAX_EVENTS = 5;

  // Subscribe — returns an unsubscribe function
  const unsubscribe = client.subscribeEvents(
    { predicate: undefined }, // no filter — catch everything
    (event: KnowledgeEvent) => {
      eventCount++;
      const args = Array.isArray(event.args) ? event.args.join(", ") : "";
      console.log(
        `  [${eventCount}] ${event.type.padEnd(20)} ${event.predicate}(${args})`
      );
    }
  );

  // Give the SSE stream a moment to connect
  await sleep(200);

  console.log("Triggering events by asserting and retracting facts...\n");

  await client.assertFact("color", ["sky", "blue"]);
  await sleep(100);
  await client.assertFact("color", ["grass", "green"]);
  await sleep(100);
  await client.assertFact("color", ["sun", "yellow"]);
  await sleep(100);
  await client.retract("color", ["sky", "blue"]);
  await sleep(100);
  await client.assertFact("shape", ["circle", "round"]);
  await sleep(500); // let final event arrive

  console.log(`\nReceived ${eventCount} event(s). Unsubscribing.`);
  unsubscribe();

  console.log("\n=== Filtered subscription (predicate: 'task') ===");
  let taskEvents = 0;

  const unsubscribeFiltered = client.subscribeEvents(
    { predicate: "task" },
    (event: KnowledgeEvent) => {
      taskEvents++;
      console.log(`  [task event] ${event.type}: task(${event.args?.join(", ")})`);
    }
  );

  await sleep(200);
  await client.assertFact("task",  ["write-docs"]);
  await client.assertFact("other", ["irrelevant"]);  // should NOT appear
  await client.assertFact("task",  ["deploy"]);
  await client.retract("task",     ["write-docs"]);
  await sleep(500);

  console.log(`\nReceived ${taskEvents} task event(s) (non-task events filtered out).`);
  unsubscribeFiltered();

  console.log("\nDone.");
}

main().catch(console.error);
