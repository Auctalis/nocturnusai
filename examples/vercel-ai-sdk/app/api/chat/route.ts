/**
 * app/api/chat/route.ts — Vercel AI SDK + NocturnusAI
 *
 * Every turn, the new user message goes through NocturnusAI first.
 * The returned briefingDelta becomes the system prompt for streamText.
 * The model never sees the full message history.
 */
import { streamText, type Message } from 'ai';
import { openai } from '@ai-sdk/openai';
import { NocturnusAIClient } from 'nocturnusai-sdk';

const noct = new NocturnusAIClient({
  baseUrl: process.env.NOCTURNUS_URL ?? 'http://localhost:9300',
  tenantId: 'default',
});

export async function POST(req: Request) {
  const { messages, sessionId }: { messages: Message[]; sessionId: string } =
    await req.json();

  const lastUserMessage = messages[messages.length - 1]?.content ?? '';

  // Compress prior context — what the model should know right now.
  const ctx = await noct.processTurns({
    turns: [lastUserMessage],
    sessionId,
    scope: sessionId,
  });

  // streamText receives a short system prompt and only the new user message.
  const result = streamText({
    model: openai('gpt-4o-mini'),
    system:
      ctx.briefingDelta ??
      'This is the start of the conversation. Respond to the user.',
    messages: [{ role: 'user', content: lastUserMessage }],
  });

  return result.toDataStreamResponse();
}
