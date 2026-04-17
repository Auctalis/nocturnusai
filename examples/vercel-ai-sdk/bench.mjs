/**
 * bench.mjs — Measure before/after tokens for a Vercel AI SDK chat workload.
 *
 * Runs a 15-turn conversation against the OpenAI API twice:
 *   1. Naive — full history replay every turn (what streamText does by default)
 *   2. NocturnusAI — only the briefingDelta is sent as system prompt
 *
 * Prints per-turn usage.prompt_tokens and totals.
 *
 * Usage:
 *   npm install nocturnusai-sdk openai
 *   export OPENAI_API_KEY=sk-...
 *   docker run -d -p 9300:9300 ghcr.io/auctalis/nocturnusai:latest
 *   node bench.mjs
 */
import OpenAI from 'openai';
import { NocturnusAIClient } from 'nocturnusai-sdk';

const SERVER = 'http://localhost:9300';
const DB = 'vercel-bench';
const SCOPE = 'bench-session';
const MODEL = 'gpt-4o-mini';

const CONVERSATION = [
  "I can't access the dashboard since this morning.",
  'About 40 people on west coast affected.',
  'We have a board presentation Friday.',
  'Auth audit shows 287 SAML_ASSERTION_INVALID errors.',
  'DNS check passes — resolves correctly.',
  'Status page says dashboard-api is operational.',
  'We migrated from Ping to Okta last night.',
  'Could the migration have caused this?',
  'SP has entity ID exk1abc, Okta sends exk2def.',
  'How do we fix the mismatch?',
  "I'll ping Greg on Slack for the Okta admin work.",
  'Greg confirmed the fix worked.',
  'Users are logging in now.',
  'What was the root cause?',
  'Is there a runbook update needed?',
];

const oai = new OpenAI();
const noct = new NocturnusAIClient({ baseUrl: SERVER, tenantId: 'default' });

async function naiveRun() {
  console.log('\n── Naive run (full history replay) ──');
  const history = [];
  const tokens = [];
  for (let i = 0; i < CONVERSATION.length; i++) {
    history.push({ role: 'user', content: CONVERSATION[i] });
    const resp = await oai.chat.completions.create({
      model: MODEL,
      messages: [...history, { role: 'user', content: 'Respond briefly.' }],
      max_tokens: 60,
    });
    tokens.push(resp.usage.prompt_tokens);
    history.push({
      role: 'assistant',
      content: resp.choices[0].message.content,
    });
    console.log(`  Turn ${String(i + 1).padStart(2)}: ${resp.usage.prompt_tokens}`);
  }
  return tokens;
}

async function nocturnusRun() {
  console.log('\n── NocturnusAI run (compressed context) ──');
  const tokens = [];
  for (let i = 0; i < CONVERSATION.length; i++) {
    const ctx = await noct.processTurns({
      turns: [CONVERSATION[i]],
      sessionId: SCOPE,
      scope: SCOPE,
    });
    const system = ctx.briefingDelta ?? 'No prior context.';
    const resp = await oai.chat.completions.create({
      model: MODEL,
      messages: [
        { role: 'system', content: system },
        {
          role: 'user',
          content: 'Respond briefly to the latest message.',
        },
      ],
      max_tokens: 60,
    });
    tokens.push(resp.usage.prompt_tokens);
    console.log(`  Turn ${String(i + 1).padStart(2)}: ${resp.usage.prompt_tokens}`);
  }
  return tokens;
}

async function main() {
  if (!process.env.OPENAI_API_KEY) {
    throw new Error('Set OPENAI_API_KEY');
  }
  await noct.admin.databases.ensure(DB);
  try {
    const naive = await naiveRun();
    const nocturnus = await nocturnusRun();
    const sn = naive.reduce((a, b) => a + b, 0);
    const sc = nocturnus.reduce((a, b) => a + b, 0);
    console.log('\n' + '='.repeat(50));
    console.log(`  Naive total:     ${String(sn).padStart(6)} tokens`);
    console.log(`  Nocturnus total: ${String(sc).padStart(6)} tokens`);
    console.log(
      `  Reduction:       ${((1 - sc / sn) * 100).toFixed(1)}%  (${(sn / sc).toFixed(1)}x)`,
    );
    console.log('='.repeat(50));
  } finally {
    await noct.scope.delete(SCOPE);
  }
}

main().catch(console.error);
