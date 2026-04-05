"""
02_mcp_client.py — Connect to NocturnusAI via the MCP protocol

Model Context Protocol (MCP) is how AI agents (Claude, GPT, Gemini, etc.)
natively discover and call tools. NocturnusAI exposes its full API as MCP
tools at POST /mcp — so any MCP-compatible agent can use it out of the box.

This demo shows:
  - NocturnusAIMCPClient: the Python MCP client
  - initialize() — handshake + capability discovery
  - list_tools() — see exactly what tools the LLM will see
  - call_tool()  — invoke any tool by name
  - A mock LLM loop showing how an agent would select and call tools

No OPENAI_API_KEY needed — the direct tool calls work against any running server.
"""

import asyncio
import json
from nocturnusai import NocturnusAIClient
from nocturnusai.mcp import NocturnusAIMCPClient

SERVER = "http://localhost:9300"
DB = "mcp-demo"


async def show_server_capabilities(mcp: NocturnusAIMCPClient):
    print("=== Server capabilities (MCP initialize) ===")
    info = await mcp.initialize()
    print(f"  Protocol version: {info.get('protocolVersion', 'n/a')}")
    srv = info.get("serverInfo", {})
    print(f"  Server: {srv.get('name')} v{srv.get('version')}")
    caps = info.get("capabilities", {})
    print(f"  Capabilities: {list(caps.keys())}")


async def list_available_tools(mcp: NocturnusAIMCPClient):
    print("\n=== Available MCP tools (what the LLM sees) ===")
    tools = await mcp.list_tools()
    for tool in tools:
        print(f"\n  [{tool.name}]")
        print(f"    {tool.description}")
        schema = tool.input_schema
        props = schema.get("properties", {})
        required = schema.get("required", [])
        for param, spec in props.items():
            req_marker = "*" if param in required else " "
            ptype = spec.get("type", "any")
            desc = spec.get("description", "")
            print(f"    {req_marker} {param}: {ptype}  — {desc}")
    return tools


async def assert_facts_via_mcp(mcp: NocturnusAIMCPClient):
    print("\n=== Assert facts via MCP call_tool ===")
    facts = [
        {"predicate": "person",   "args": json.dumps(["alice"])},
        {"predicate": "person",   "args": json.dumps(["bob"])},
        {"predicate": "knows",    "args": json.dumps(["alice", "bob"])},
        {"predicate": "skill",    "args": json.dumps(["alice", "reasoning"])},
        {"predicate": "skill",    "args": json.dumps(["bob",   "planning"])},
        {"predicate": "goal",     "args": json.dumps(["alice", "ship-v2"])},
    ]
    for f in facts:
        result = await mcp.call_tool("assert_fact", f)
        status = "✓" if not result.is_error else "✗"
        print(f"  {status} assert_fact({f['predicate']}, {f['args']})")
        if result.is_error:
            print(f"    Error: {result.text}")


async def assert_rule_via_mcp(mcp: NocturnusAIMCPClient):
    print("\n=== Assert rule via MCP ===")
    # collaborates(?x, ?y) :- knows(?x, ?y), knows(?y, ?x)
    result = await mcp.call_tool("assert_rule", {
        "head": json.dumps({"predicate": "collaborates", "args": ["?x", "?y"]}),
        "body": json.dumps([
            {"predicate": "knows", "args": ["?x", "?y"]},
            {"predicate": "knows", "args": ["?y", "?x"]},
        ]),
    })
    print(f"  assert_rule: {result.text}")


async def query_and_infer_via_mcp(mcp: NocturnusAIMCPClient):
    print("\n=== Query via MCP ===")
    result = await mcp.call_tool("query", {
        "predicate": "person",
        "args": json.dumps(["?who"]),
    })
    print(f"  person(?who): {result.text}")

    print("\n=== Infer via MCP ===")
    result = await mcp.call_tool("infer", {
        "predicate": "skill",
        "args": json.dumps(["?person", "?ability"]),
    })
    print(f"  skill(?person, ?ability): {result.text}")


async def get_context_via_mcp(mcp: NocturnusAIMCPClient):
    print("\n=== Context window via MCP (agent working memory) ===")
    result = await mcp.call_tool("get_context", {
        "max_facts": 5,
        "predicates": json.dumps(["person", "skill", "goal"]),
    })
    print(f"  Context: {result.text[:400]}")


async def simulate_llm_tool_loop(mcp: NocturnusAIMCPClient):
    """
    Simulates what an LLM orchestrator does:
      1. Receive a user message
      2. Decide which tool(s) to call
      3. Execute tools and incorporate results
      4. Produce a final answer

    This is the exact pattern Claude's tool_use, OpenAI's tool_calls,
    and any other MCP-compatible host implement.
    """
    print("\n=== Simulated LLM tool-use loop ===")
    print("  User: 'What does Alice need to accomplish and who can help her?'\n")

    # Step 1: LLM decides to query Alice's goals
    print("  [LLM → tool call] query(goal, [alice, ?g])")
    goals = await mcp.call_tool("query", {"predicate": "goal", "args": json.dumps(["alice", "?g"])})
    print(f"  [tool result] {goals.text}")

    # Step 2: LLM decides to query who Alice knows
    print("\n  [LLM → tool call] query(knows, [alice, ?who])")
    contacts = await mcp.call_tool("query", {"predicate": "knows", "args": json.dumps(["alice", "?who"])})
    print(f"  [tool result] {contacts.text}")

    # Step 3: LLM looks up skills of those contacts
    print("\n  [LLM → tool call] infer(skill, [?person, ?ability])")
    skills = await mcp.call_tool("infer", {"predicate": "skill", "args": json.dumps(["?person", "?ability"])})
    print(f"  [tool result] {skills.text}")

    # Step 4: LLM synthesises (simulated)
    print("\n  [LLM → final answer]")
    print("  Alice needs to ship v2. Bob knows planning, which is exactly what")
    print("  shipping a project requires. Alice's reasoning skill + Bob's planning")
    print("  skill make them a strong collaboration.")


async def main():
    # Ensure the database exists before using it via MCP
    async with NocturnusAIClient(SERVER, database=DB) as setup:
        await setup.ensure_database()

    async with NocturnusAIMCPClient(SERVER, database=DB) as mcp:
        await show_server_capabilities(mcp)
        await list_available_tools(mcp)
        await assert_facts_via_mcp(mcp)
        await assert_rule_via_mcp(mcp)
        await query_and_infer_via_mcp(mcp)
        await get_context_via_mcp(mcp)
        await simulate_llm_tool_loop(mcp)
        print("\nDone.")


if __name__ == "__main__":
    asyncio.run(main())
