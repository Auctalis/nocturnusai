"""
03_openai_tools.py — OpenAI function calling with NocturnusAI as agent memory

Shows how to wire NocturnusAI into the OpenAI tool_calls API directly —
no LangChain abstraction. This is the pattern used by any OpenAI-compatible
model host (OpenAI, Azure OpenAI, Groq, Together, Ollama with OpenAI compat).

The flow:
  1. Define NocturnusAI operations as OpenAI tool definitions
  2. Send user message + tool definitions to the model
  3. Execute whichever tools the model calls
  4. Feed results back and get the final answer

Requirements:
  pip install nocturnusai openai
  export OPENAI_API_KEY=sk-...
"""

import json
import os
from nocturnusai import SyncNocturnusAIClient

SERVER = "http://localhost:9300"
DB = "openai-demo"

# ── Tool definitions (what OpenAI sees) ─────────────────────────────────────
TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "remember",
            "description": (
                "Store a fact in the agent's persistent knowledge base. "
                "Use this whenever the user tells you something worth remembering."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "predicate": {
                        "type": "string",
                        "description": "The relationship or category (e.g. 'prefers', 'works_on', 'knows')",
                    },
                    "args": {
                        "type": "array",
                        "items": {"type": "string"},
                        "description": "The subjects/objects of the fact (e.g. ['alice', 'python'])",
                    },
                },
                "required": ["predicate", "args"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "recall",
            "description": (
                "Search the knowledge base for facts matching a pattern. "
                "Use '?x', '?who', etc. as wildcards in the args array."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "predicate": {"type": "string", "description": "The predicate to query"},
                    "args": {
                        "type": "array",
                        "items": {"type": "string"},
                        "description": "Pattern to match — use '?x' for wildcards",
                    },
                },
                "required": ["predicate", "args"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "reason",
            "description": (
                "Run logical inference over the knowledge base. "
                "Use this to derive facts that aren't stored directly but can be inferred from rules."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "predicate": {"type": "string", "description": "The goal predicate to prove"},
                    "args": {
                        "type": "array",
                        "items": {"type": "string"},
                        "description": "The goal arguments — use '?x' for unknowns",
                    },
                },
                "required": ["predicate", "args"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "working_memory",
            "description": (
                "Retrieve the most relevant facts from the knowledge base based on salience. "
                "Call this at the start of a session to load context."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "max_facts": {"type": "integer", "default": 10},
                    "filter_predicates": {
                        "type": "array",
                        "items": {"type": "string"},
                        "description": "Only return facts with these predicates",
                    },
                },
                "required": [],
            },
        },
    },
]


# ── Tool executor ─────────────────────────────────────────────────────────────
def execute_tool(client: SyncNocturnusAIClient, name: str, args: dict) -> str:
    if name == "remember":
        client.assert_fact(args["predicate"], args["args"])
        return f"Stored: {args['predicate']}({', '.join(args['args'])})"

    if name == "recall":
        results = client.query(args["predicate"], args["args"])
        if not results:
            return f"No facts found for {args['predicate']}({', '.join(args['args'])})"
        return "\n".join(f"{a.predicate}({', '.join(a.args)})" for a in results)

    if name == "reason":
        results = client.infer(args["predicate"], args["args"])
        if not results:
            return f"No inference results for {args['predicate']}({', '.join(args['args'])})"
        return "\n".join(f"{a.predicate}({', '.join(a.args)})" for a in results)

    if name == "working_memory":
        predicates = args.get("filter_predicates")
        ctx = client.context_window(
            max_facts=args.get("max_facts", 10),
            predicates=predicates,
        )
        if not ctx.facts:
            return "Knowledge base is empty."
        lines = [f"[{getattr(f,'salience',0):.2f}] {f.predicate}({', '.join(f.args)})" for f in ctx.facts]
        return "\n".join(lines)

    return f"Unknown tool: {name}"


# ── Agent loop ────────────────────────────────────────────────────────────────
def run_agent(client: SyncNocturnusAIClient, user_message: str):
    if not os.getenv("OPENAI_API_KEY"):
        print(f"\n[Q] {user_message}")
        print("[skipped] Set OPENAI_API_KEY to run the live agent.")
        return

    import openai

    openai_client = openai.OpenAI()
    messages = [
        {
            "role": "system",
            "content": (
                "You are a smart assistant with persistent memory powered by NocturnusAI. "
                "Use your tools to remember important information and recall it when needed. "
                "Always check working_memory at the start of a session."
            ),
        },
        {"role": "user", "content": user_message},
    ]

    print(f"\n{'='*60}")
    print(f"[Q] {user_message}")

    while True:
        response = openai_client.chat.completions.create(
            model="gpt-4o-mini",
            messages=messages,
            tools=TOOLS,
            tool_choice="auto",
        )
        msg = response.choices[0].message
        messages.append(msg)

        if not msg.tool_calls:
            print(f"\n[A] {msg.content}")
            break

        for tc in msg.tool_calls:
            fn_name = tc.function.name
            fn_args = json.loads(tc.function.arguments)
            print(f"\n  → {fn_name}({json.dumps(fn_args, separators=(',', ':'))})")
            result = execute_tool(client, fn_name, fn_args)
            print(f"  ← {result[:200]}")
            messages.append({
                "role": "tool",
                "tool_call_id": tc.id,
                "content": result,
            })


def demo_tool_execution_without_llm(client: SyncNocturnusAIClient):
    """Show each tool working directly — no API key needed."""
    print("=== Tool execution (no LLM) ===\n")

    print("[remember] alice prefers async-first")
    print(" ", execute_tool(client, "remember", {"predicate": "prefers", "args": ["alice", "async-first"]}))

    print("[remember] alice prefers type-safety")
    print(" ", execute_tool(client, "remember", {"predicate": "prefers", "args": ["alice", "type-safety"]}))

    print("[remember] bob works_on billing-service")
    print(" ", execute_tool(client, "remember", {"predicate": "works_on", "args": ["bob", "billing-service"]}))

    # Seed a rule
    client.assert_rule(
        head={"predicate": "engineering_focus", "args": ["?x", "?proj"]},
        body=[{"predicate": "works_on", "args": ["?x", "?proj"]}],
    )

    print("\n[recall] prefers(alice, ?what)")
    print(" ", execute_tool(client, "recall", {"predicate": "prefers", "args": ["alice", "?what"]}))

    print("\n[reason] engineering_focus(?who, ?proj)")
    print(" ", execute_tool(client, "reason", {"predicate": "engineering_focus", "args": ["?who", "?proj"]}))

    print("\n[working_memory] top 5 facts")
    print(" ", execute_tool(client, "working_memory", {"max_facts": 5}))


def main():
    with SyncNocturnusAIClient(SERVER, database=DB) as client:
        demo_tool_execution_without_llm(client)

        # Live agent questions (requires OPENAI_API_KEY)
        run_agent(client, "Load my working memory and tell me what you know about alice.")
        run_agent(client, "Remember that alice is leading project phoenix. What projects is she involved in?")
        run_agent(client, "Who in the knowledge base works on what?")


if __name__ == "__main__":
    main()
