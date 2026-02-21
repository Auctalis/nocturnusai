/**
 * 02_openai_tools.ts — OpenAI function calling with NocturnusAI as agent memory
 *
 * Shows how to wire NocturnusAI into the OpenAI tool_calls API directly.
 * Works with any OpenAI-compatible API: OpenAI, Azure OpenAI, Groq, Together,
 * Ollama, and others.
 *
 * The flow:
 *   1. Define NocturnusAI operations as OpenAI tool definitions
 *   2. Send user message + tools to the model
 *   3. Execute whichever tools the model calls
 *   4. Feed results back and get the final answer
 *
 * Requirements:
 *   npm install openai nocturnusai-sdk
 *   export OPENAI_API_KEY=sk-...
 */

import { NocturnusAIClient } from "nocturnusai-sdk";

const SERVER = "http://localhost:9300";
const DB = "openai-demo-ts";

// ── Tool definitions (what OpenAI sees) ───────────────────────────────────────
const TOOLS = [
  {
    type: "function" as const,
    function: {
      name: "remember",
      description:
        "Store a fact in the agent's persistent knowledge base. " +
        "Use this whenever the user tells you something worth remembering.",
      parameters: {
        type: "object",
        properties: {
          predicate: {
            type: "string",
            description: "The relationship or category (e.g. 'prefers', 'works_on', 'knows')",
          },
          args: {
            type: "array",
            items: { type: "string" },
            description: "The subjects/objects (e.g. ['alice', 'python'])",
          },
        },
        required: ["predicate", "args"],
      },
    },
  },
  {
    type: "function" as const,
    function: {
      name: "recall",
      description:
        "Search the knowledge base for facts matching a pattern. " +
        "Use '?x', '?who', etc. as wildcards in args.",
      parameters: {
        type: "object",
        properties: {
          predicate: { type: "string" },
          args: { type: "array", items: { type: "string" } },
        },
        required: ["predicate", "args"],
      },
    },
  },
  {
    type: "function" as const,
    function: {
      name: "reason",
      description:
        "Run logical inference. Use this to derive facts not stored directly " +
        "but provable from rules.",
      parameters: {
        type: "object",
        properties: {
          predicate: { type: "string" },
          args: { type: "array", items: { type: "string" } },
        },
        required: ["predicate", "args"],
      },
    },
  },
  {
    type: "function" as const,
    function: {
      name: "working_memory",
      description:
        "Retrieve the most relevant facts by salience. " +
        "Call this at the start of a session to load context.",
      parameters: {
        type: "object",
        properties: {
          max_facts: { type: "integer", default: 10 },
          filter_predicates: { type: "array", items: { type: "string" } },
        },
        required: [],
      },
    },
  },
];

// ── Tool executor ─────────────────────────────────────────────────────────────
async function executeTool(
  client: NocturnusAIClient,
  name: string,
  args: Record<string, unknown>
): Promise<string> {
  if (name === "remember") {
    const pred = args.predicate as string;
    const factArgs = args.args as string[];
    await client.assertFact(pred, factArgs);
    return `Stored: ${pred}(${factArgs.join(", ")})`;
  }

  if (name === "recall") {
    const results = await client.query(args.predicate as string, args.args as string[]);
    if (results.length === 0) return `No facts found for ${args.predicate}(${(args.args as string[]).join(", ")})`;
    return results.map((a) => `${a.predicate}(${a.args.join(", ")})`).join("\n");
  }

  if (name === "reason") {
    const results = await client.infer(args.predicate as string, args.args as string[]);
    if (results.length === 0) return `No inference results for ${args.predicate}`;
    return results.map((a) => `${a.predicate}(${a.args.join(", ")})`).join("\n");
  }

  if (name === "working_memory") {
    const ctx = await client.contextWindow({
      maxFacts: (args.max_facts as number) ?? 10,
      predicates: args.filter_predicates as string[] | undefined,
    });
    if (ctx.facts.length === 0) return "Knowledge base is empty.";
    return ctx.facts
      .map((f) => `[${(f as { salience?: number }).salience?.toFixed(2) ?? "0.00"}] ${f.predicate}(${f.args.join(", ")})`)
      .join("\n");
  }

  return `Unknown tool: ${name}`;
}

// ── Demo: direct tool execution (no LLM) ────────────────────────────────────
async function demoToolsDirectly(client: NocturnusAIClient) {
  console.log("=== Tool execution (no LLM) ===\n");

  console.log("[remember] alice prefers async-first");
  console.log(" ", await executeTool(client, "remember", { predicate: "prefers", args: ["alice", "async-first"] }));

  console.log("[remember] alice prefers type-safety");
  console.log(" ", await executeTool(client, "remember", { predicate: "prefers", args: ["alice", "type-safety"] }));

  console.log("[remember] bob works_on billing-service");
  console.log(" ", await executeTool(client, "remember", { predicate: "works_on", args: ["bob", "billing-service"] }));

  // Seed a rule
  await client.assertRule(
    { predicate: "engineering_focus", args: ["?x", "?proj"] },
    [{ predicate: "works_on", args: ["?x", "?proj"] }]
  );

  console.log("\n[recall] prefers(alice, ?what)");
  console.log(" ", await executeTool(client, "recall", { predicate: "prefers", args: ["alice", "?what"] }));

  console.log("\n[reason] engineering_focus(?who, ?proj)");
  console.log(" ", await executeTool(client, "reason", { predicate: "engineering_focus", args: ["?who", "?proj"] }));

  console.log("\n[working_memory] top 5 facts");
  console.log(" ", await executeTool(client, "working_memory", { max_facts: 5 }));
}

// ── Live agent loop ───────────────────────────────────────────────────────────
async function runAgent(client: NocturnusAIClient, userMessage: string) {
  if (!process.env.OPENAI_API_KEY) {
    console.log(`\n[Q] ${userMessage}`);
    console.log("[skipped] Set OPENAI_API_KEY to run the live agent.");
    return;
  }

  // Dynamic import so the file still runs without openai installed
  const { default: OpenAI } = await import("openai");
  const openai = new OpenAI();

  type Message = { role: string; content?: string; tool_calls?: unknown; tool_call_id?: string };
  const messages: Message[] = [
    {
      role: "system",
      content:
        "You are a smart assistant with persistent memory powered by NocturnusAI. " +
        "Use your tools to remember important information and recall it when needed. " +
        "Always call working_memory at the start to load context.",
    },
    { role: "user", content: userMessage },
  ];

  console.log(`\n${"=".repeat(60)}`);
  console.log(`[Q] ${userMessage}`);

  while (true) {
    const response = await openai.chat.completions.create({
      model: "gpt-4o-mini",
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      messages: messages as any,
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      tools: TOOLS as any,
      tool_choice: "auto",
    });

    const msg = response.choices[0].message;
    messages.push(msg as Message);

    if (!msg.tool_calls || msg.tool_calls.length === 0) {
      console.log(`\n[A] ${msg.content}`);
      break;
    }

    for (const tc of msg.tool_calls) {
      const fnArgs = JSON.parse(tc.function.arguments) as Record<string, unknown>;
      console.log(`\n  → ${tc.function.name}(${JSON.stringify(fnArgs, null, 0).slice(0, 80)})`);
      const result = await executeTool(client, tc.function.name, fnArgs);
      console.log(`  ← ${result.slice(0, 200)}`);
      messages.push({ role: "tool", tool_call_id: tc.id, content: result });
    }
  }
}

async function main() {
  const client = new NocturnusAIClient({ baseUrl: SERVER, database: DB });

  await demoToolsDirectly(client);

  // Live agent (requires OPENAI_API_KEY)
  await runAgent(client, "Load my working memory and tell me what you know about alice.");
  await runAgent(client, "Remember that alice is leading project phoenix. What projects is she involved in?");
  await runAgent(client, "Who in the knowledge base works on what?");

  console.log("\nDone.");
}

main().catch(console.error);
