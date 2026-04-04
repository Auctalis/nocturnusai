/**
 * 01_mcp_client.ts — Connect to NocturnusAI via the MCP protocol
 *
 * Model Context Protocol (MCP) is the native interface AI agents use
 * to discover and call tools. NocturnusAI exposes its full API as MCP
 * tools at POST /mcp — so Claude, GPT, Gemini, and any MCP-compatible
 * host can use it without any custom glue code.
 *
 * This demo shows:
 *   - NocturnusAIMCPClient: initialization + capability discovery
 *   - list_tools(): see exactly what the LLM will see
 *   - call_tool(): invoke any operation by name
 *   - A simulated LLM tool-selection loop
 */

import { NocturnusAIMCPClient } from "nocturnusai-sdk";

const SERVER = "http://localhost:9300";
const DB = "mcp-demo-ts";

async function showServerCapabilities(mcp: NocturnusAIMCPClient) {
  console.log("=== Server capabilities (MCP initialize) ===");
  const info = await mcp.initialize();
  console.log(`  Protocol version: ${info.protocolVersion}`);
  console.log(`  Server: ${info.serverInfo.name} v${info.serverInfo.version}`);
  console.log(`  Capabilities: ${Object.keys(info.capabilities).join(", ")}`);
}

async function listAvailableTools(mcp: NocturnusAIMCPClient) {
  console.log("\n=== Available MCP tools (what the LLM sees) ===");
  const tools = await mcp.listTools();
  for (const tool of tools) {
    console.log(`\n  [${tool.name}]`);
    console.log(`    ${tool.description}`);
    const props = (tool.inputSchema as Record<string, unknown>).properties as
      Record<string, { type: string; description?: string }> | undefined;
    const required = ((tool.inputSchema as Record<string, unknown>).required as string[]) ?? [];
    if (props) {
      for (const [param, spec] of Object.entries(props)) {
        const req = required.includes(param) ? "*" : " ";
        console.log(`    ${req} ${param}: ${spec.type}  — ${spec.description ?? ""}`);
      }
    }
  }
  return tools;
}

async function assertFactsViaMcp(mcp: NocturnusAIMCPClient) {
  console.log("\n=== Assert facts via MCP call_tool ===");
  const facts: Array<{ predicate: string; args: string[] }> = [
    { predicate: "person",  args: ["alice"] },
    { predicate: "person",  args: ["bob"] },
    { predicate: "knows",   args: ["alice", "bob"] },
    { predicate: "skill",   args: ["alice", "reasoning"] },
    { predicate: "skill",   args: ["bob",   "planning"] },
    { predicate: "goal",    args: ["alice", "ship-v2"] },
  ];

  for (const f of facts) {
    const result = await mcp.callTool("assert_fact", {
      predicate: f.predicate,
      args: JSON.stringify(f.args),
    });
    const status = result.isError ? "✗" : "✓";
    console.log(`  ${status} assert_fact(${f.predicate}, ${JSON.stringify(f.args)})`);
    if (result.isError) {
      console.log(`    Error: ${result.content.map((c) => c.text).join("")}`);
    }
  }
}

async function assertRuleViaMcp(mcp: NocturnusAIMCPClient) {
  console.log("\n=== Assert rule via MCP ===");
  const result = await mcp.callTool("assert_rule", {
    head: JSON.stringify({ predicate: "collaborates", args: ["?x", "?y"] }),
    body: JSON.stringify([
      { predicate: "knows", args: ["?x", "?y"] },
      { predicate: "knows", args: ["?y", "?x"] },
    ]),
  });
  const text = result.content.map((c) => c.text).join("");
  console.log(`  assert_rule: ${text}`);
}

async function queryAndInferViaMcp(mcp: NocturnusAIMCPClient) {
  console.log("\n=== Query via MCP ===");
  const q = await mcp.callTool("query", {
    predicate: "person",
    args: JSON.stringify(["?who"]),
  });
  console.log(`  person(?who): ${q.content.map((c) => c.text).join("")}`);

  console.log("\n=== Infer via MCP ===");
  const i = await mcp.callTool("infer", {
    predicate: "skill",
    args: JSON.stringify(["?person", "?ability"]),
  });
  console.log(`  skill(?person, ?ability): ${i.content.map((c) => c.text).join("")}`);
}

async function getContextViaMcp(mcp: NocturnusAIMCPClient) {
  console.log("\n=== Context window via MCP ===");
  const result = await mcp.callTool("get_context", {
    max_facts: 5,
    predicates: JSON.stringify(["person", "skill", "goal"]),
  });
  const text = result.content.map((c) => c.text).join("");
  console.log(`  Context: ${text.slice(0, 400)}`);
}

async function simulateLlmToolLoop(mcp: NocturnusAIMCPClient) {
  /**
   * Simulates what an LLM orchestrator does:
   *   1. Receive a user message
   *   2. Choose tool(s) to call
   *   3. Execute tools and incorporate results
   *   4. Produce final answer
   *
   * This is the exact pattern Claude tool_use, OpenAI tool_calls,
   * and all MCP-compatible hosts implement.
   */
  console.log("\n=== Simulated LLM tool-use loop ===");
  console.log("  User: 'What does Alice need to accomplish and who can help her?'\n");

  console.log("  [LLM → tool call] query(goal, [alice, ?g])");
  const goals = await mcp.callTool("query", {
    predicate: "goal",
    args: JSON.stringify(["alice", "?g"]),
  });
  console.log(`  [tool result] ${goals.content.map((c) => c.text).join("")}`);

  console.log("\n  [LLM → tool call] query(knows, [alice, ?who])");
  const contacts = await mcp.callTool("query", {
    predicate: "knows",
    args: JSON.stringify(["alice", "?who"]),
  });
  console.log(`  [tool result] ${contacts.content.map((c) => c.text).join("")}`);

  console.log("\n  [LLM → tool call] infer(skill, [?person, ?ability])");
  const skills = await mcp.callTool("infer", {
    predicate: "skill",
    args: JSON.stringify(["?person", "?ability"]),
  });
  console.log(`  [tool result] ${skills.content.map((c) => c.text).join("")}`);

  console.log("\n  [LLM → final answer]");
  console.log("  Alice needs to ship v2. Bob knows planning — the right complement");
  console.log("  to Alice's reasoning skill. Recommended: pair them on this goal.");
}

async function main() {
  const mcp = new NocturnusAIMCPClient({ baseUrl: SERVER, database: DB });

  await showServerCapabilities(mcp);
  await listAvailableTools(mcp);
  await assertFactsViaMcp(mcp);
  await assertRuleViaMcp(mcp);
  await queryAndInferViaMcp(mcp);
  await getContextViaMcp(mcp);
  await simulateLlmToolLoop(mcp);

  console.log("\nDone.");
}

main().catch(console.error);
