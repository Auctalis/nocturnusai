# Vercel AI SDK + NocturnusAI — 5-minute wow

A Next.js chat app that calls NocturnusAI before `streamText()` — so your model only sees the compressed delta, not the full message history.

## The problem

Default `useChat` + `streamText` sends the full `messages` array to the model on every user turn. A 15-turn conversation means every turn after the first is paying to re-process tokens the model already saw.

## Before / After

| | Without NocturnusAI | With NocturnusAI |
|---|---|---|
| Tokens per turn | ~1,259 | ~221 |
| Cost per month (1K req/hr, Opus 4, $15/1M) | $13,600 | $2,400 |
| Truth-preserving across retractions | no | yes |

Measured on our [15-turn product-support benchmark](https://nocturnus.ai/benchmark). The compression happens at the Nocturnus layer — framework-agnostic.

## Install

```bash
npx create-next-app@latest my-chat --typescript --app
cd my-chat
npm install nocturnusai-sdk ai @ai-sdk/openai
```

Then copy `app/page.tsx` and `app/api/chat/route.ts` from this directory into your app. Start the Nocturnus server:

```bash
docker run -d -p 9300:9300 ghcr.io/auctalis/nocturnusai:latest
```

Set your API keys:

```bash
export OPENAI_API_KEY=sk-...
```

Run:

```bash
npm run dev
```

Open `http://localhost:3000` and chat. Watch the network tab — every turn sends a short compressed prompt, not a growing transcript.

## How it works

The `/api/chat` route:

1. Receives the new user message from `useChat`
2. Calls `nocturnusai.processTurns()` with the new turn and a session ID
3. Receives back a `briefingDelta` — the compressed context for this turn
4. Passes the briefing as the `system` message to `streamText()`

The model never sees the full message history. It sees only the delta.

```ts
// app/api/chat/route.ts (simplified)
const { messages, sessionId } = await req.json();
const lastUserMessage = messages[messages.length - 1].content;

const ctx = await noct.processTurns({
  turns: [lastUserMessage],
  sessionId,
  scope: sessionId,
});

const result = streamText({
  model: openai('gpt-4o-mini'),
  system: ctx.briefingDelta ?? 'Start of conversation.',
  messages: [{ role: 'user', content: lastUserMessage }],
});

return result.toDataStreamResponse();
```

## Measure your own numbers

```bash
node bench.mjs
```

Runs a 15-turn conversation with and without NocturnusAI compression against `/api/chat`. Prints `usage.prompt_tokens` per turn and totals.

## What to look at

- `app/api/chat/route.ts` — the core compression: `processTurns` → `streamText`
- `app/page.tsx` — standard `useChat` UI, no changes needed
- The network tab — every request to `/api/chat` stays small

## Next step

- [Benchmark methodology](https://nocturnus.ai/benchmark)
- [TypeScript SDK docs](https://nocturnus.ai/docs/sdks)
- [Other framework examples →](https://nocturnus.ai/examples)
