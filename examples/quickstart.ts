/**
 * NocturnusAI TypeScript Quickstart
 * ==================================
 * Follows the turn-reduction workflow from the docs.
 *
 * Prerequisites:
 *   docker run -d -p 9300:9300 ghcr.io/auctalis/nocturnusai:latest
 *
 * Run:
 *   npx tsx examples/quickstart.ts
 */

import { NocturnusAIClient } from 'nocturnusai-sdk';

const client = new NocturnusAIClient({
  baseUrl: 'http://localhost:9300',
  tenantId: 'default',
});

async function main() {
  // 1. Health check
  const health = await client.health();
  console.log('Server:', health.status, 'v' + health.version);

  // 2. Send turns — get a reduced briefing
  console.log('\n--- Turn 1: Initial ticket context ---');
  const turn1 = await client.processTurns({
    turns: [
      'User: We cannot log in after the Okta cutover.',
      'Tool crm_lookup: account=acme tier=enterprise',
    ],
    scope: 'ticket-4821',
    sessionId: 'ticket-4821',
  });
  console.log('Facts extracted:', turn1.newFactsExtracted);
  console.log('Briefing:', turn1.briefingDelta);

  // 3. Send more turns — only the delta comes back
  console.log('\n--- Turn 2: SAML audit findings ---');
  const turn2 = await client.processTurns({
    turns: [
      'Tool auth_audit: 14 failed SAML assertions since 09:12 UTC.',
      'Tool auth_audit: issuer mismatch after IdP migration.',
    ],
    scope: 'ticket-4821',
    sessionId: 'ticket-4821',
  });
  console.log('New facts:', turn2.newFactsExtracted);
  console.log('Delta:', turn2.briefingDelta);

  // 4. Clean up
  await client.deleteScope('ticket-4821');
  console.log('\nDone — scope cleaned up.');

  // 5. Bonus: low-level logic engine
  console.log('\n--- Logic engine demo ---');
  await client.tell('parent', ['alice', 'bob']);
  await client.tell('parent', ['bob', 'charlie']);
  await client.teach(
    { predicate: 'grandparent', args: ['?x', '?z'] },
    [
      { predicate: 'parent', args: ['?x', '?y'] },
      { predicate: 'parent', args: ['?y', '?z'] },
    ],
  );
  const results = await client.ask('grandparent', ['?who', 'charlie']);
  console.log('Grandparent of charlie:', results.map((a) => a.args[0]));
}

main().catch(console.error);
