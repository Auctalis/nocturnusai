/**
 * 05_auth_and_keys.ts — Authentication and API key management
 *
 * Demonstrates:
 *   - authStatus()
 *   - bootstrap()
 *   - createKey()
 *   - listKeys()
 *   - whoami()
 *   - revokeKey()
 *
 * NOTE: Set AUTH_ENABLED=true on the server for full enforcement.
 */

import { NocturnusAIClient, NocturnusAIRequestError } from "nocturnusai-sdk";

const SERVER = "http://localhost:9300";

async function main() {
  // Start unauthenticated
  const anon = new NocturnusAIClient({ baseUrl: SERVER });

  console.log("=== 1. Auth status ===");
  const status = await anon.authStatus();
  console.log(`  authEnabled: ${status.authEnabled}`);
  console.log(`  mode:        ${status.mode}`);
  console.log(`  hasKeys:     ${status.hasKeys}`);

  let adminKey: string | null = null;
  let readerId: string | null = null;

  if (!status.hasKeys) {
    console.log("\n=== 2. Bootstrap (first admin key) ===");
    try {
      const result = await anon.bootstrap("admin-demo", "Demo admin key");
      adminKey = result.key;
      console.log(`  Admin key created: id=${result.id}`);
      console.log(`  Raw key (save this — shown only once): ${result.key.slice(0, 12)}...`);
    } catch (err) {
      if (err instanceof NocturnusAIRequestError) {
        console.log(`  Bootstrap unavailable: ${err.message}`);
      }
    }
  } else {
    console.log("\n=== 2. Already bootstrapped — skipping ===");
  }

  if (!adminKey) {
    console.log("\nNo admin key available. Set AUTH_ENABLED=true and re-run on a fresh server.");
    return;
  }

  const admin = new NocturnusAIClient({ baseUrl: SERVER, apiKey: adminKey });

  console.log("\n=== 3. Whoami ===");
  const me = await admin.whoami();
  console.log(`  keyId: ${me.keyId}`);
  console.log(`  name:  ${me.name}`);
  console.log(`  role:  ${me.role}`);

  console.log("\n=== 4. Create a scoped writer key ===");
  const writer = await admin.createKey({
    name: "agent-writer",
    role: "writer",
    databases: ["production"],
    tenants: ["tenant-a"],
    expiresInDays: 30,
    description: "Writer key for agent-writer demo",
  });
  const writerKey = writer.key;
  console.log(`  Writer key created: id=${writer.id}, prefix=${writer.prefix}`);

  console.log("\n=== 5. Create a read-only key ===");
  const reader = await admin.createKey({
    name: "dashboard-reader",
    role: "reader",
    description: "Read-only dashboard key",
  });
  readerId = reader.id;
  console.log(`  Reader key created: id=${readerId}`);

  console.log("\n=== 6. List all keys ===");
  const keys = await admin.listKeys();
  console.log(`  ${keys.length} active key(s):`);
  for (const k of keys) {
    console.log(`    [${k.role.padEnd(6)}] ${k.name} (id=${k.id.slice(0, 8)}...)`);
  }

  console.log("\n=== 7. Revoke the reader key ===");
  await admin.revokeKey(readerId);
  console.log(`  Revoked: ${readerId}`);
  const keysAfter = await admin.listKeys();
  console.log(`  Keys remaining: ${keysAfter.length}`);

  console.log("\n=== 8. Use writer key to assert a fact ===");
  const writerClient = new NocturnusAIClient({
    baseUrl: SERVER,
    apiKey: writerKey,
    database: "production",
  });
  await writerClient.assertFact("demo", ["auth-works"]);
  const facts = await writerClient.query("demo", ["?x"]);
  console.log(`  Writer successfully asserted and queried: ${facts.length} fact(s)`);

  console.log("\nDone.");
}

main().catch(console.error);
