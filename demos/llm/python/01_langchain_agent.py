"""
01_langchain_agent.py — LangChain ReAct agent with NocturnusAI as persistent memory

The agent has four tools wired directly to NocturnusAI:
  nocturnusai_assert  — store a fact into the knowledge base
  nocturnusai_query   — search the knowledge base by pattern
  nocturnusai_infer   — run backward-chaining inference over facts + rules
  nocturnusai_context — retrieve the most salient facts for working memory

The scenario: a personal-assistant agent that learns user preferences,
reasons over them, and retrieves relevant context before answering.

Requirements:
  pip install nocturnusai langchain langchain-openai
  export OPENAI_API_KEY=sk-...
"""

import os
import asyncio
from nocturnusai import SyncNocturnusAIClient
from nocturnusai.langchain import get_nocturnusai_tools

SERVER = "http://localhost:9300"
DB = "langchain-demo"


# ── Pre-seed some world knowledge so the agent has something to reason over ──
def seed_knowledge(client: SyncNocturnusAIClient):
    facts = [
        ("prefers",    ["alice", "python"]),
        ("prefers",    ["alice", "dark-mode"]),
        ("prefers",    ["alice", "concise-answers"]),
        ("dislikes",   ["alice", "verbose-output"]),
        ("project",    ["alice", "atlas"]),
        ("project",    ["alice", "orion"]),
        ("deadline",   ["atlas",  "2025-03-01"]),
        ("deadline",   ["orion",  "2025-06-15"]),
        ("skill",      ["alice", "ml"]),
        ("skill",      ["alice", "backend"]),
    ]
    for pred, args in facts:
        client.assert_fact(pred, args)

    # Rule: if alice dislikes something, it's low-priority for her context
    client.assert_rule(
        head={"predicate": "low_priority_for", "args": ["alice", "?x"]},
        body=[{"predicate": "dislikes", "args": ["alice", "?x"]}],
    )
    print(f"  Seeded {len(facts)} facts + 1 rule into '{DB}'")


def demo_tools_directly(client: SyncNocturnusAIClient):
    """
    Show each LangChain tool working on its own — no LLM needed.
    This is how you'd call them from an agent executor.
    """
    tools = {t.name: t for t in get_nocturnusai_tools(client)}

    print("\n--- nocturnusai_assert ---")
    result = tools["nocturnusai_assert"].run(
        '{"predicate": "meeting", "args": ["alice", "bob", "monday"]}'
    )
    print(f"  {result}")

    print("\n--- nocturnusai_query ---")
    result = tools["nocturnusai_query"].run(
        '{"predicate": "prefers", "args": ["alice", "?what"]}'
    )
    print(f"  {result}")

    print("\n--- nocturnusai_infer ---")
    result = tools["nocturnusai_infer"].run(
        '{"predicate": "low_priority_for", "args": ["alice", "?x"]}'
    )
    print(f"  {result}")

    print("\n--- nocturnusai_context ---")
    result = tools["nocturnusai_context"].run(
        '{"max_facts": 5, "predicates": ["prefers", "project", "deadline"]}'
    )
    print(f"  {result[:300]}...")


def demo_with_llm(client: SyncNocturnusAIClient):
    """Full ReAct agent that uses NocturnusAI for memory."""
    if not os.getenv("OPENAI_API_KEY"):
        print("\n[skipped] Set OPENAI_API_KEY to run the live agent demo.")
        return

    from langchain.agents import AgentExecutor, create_react_agent
    from langchain_core.prompts import PromptTemplate
    from langchain_openai import ChatOpenAI

    tools = get_nocturnusai_tools(client)

    system_prompt = PromptTemplate.from_template("""You are a helpful personal assistant with \
persistent memory powered by NocturnusAI.

You have access to these tools:
{tools}

Tool names: {tool_names}

Use the following format:
Question: the input question
Thought: think about what to do
Action: the action to take — one of [{tool_names}]
Action Input: the input to the action (always valid JSON)
Observation: the result of the action
... (repeat Thought/Action/Action Input/Observation as needed)
Thought: I now know the final answer
Final Answer: the final answer

Begin!

Question: {input}
Thought: {agent_scratchpad}""")

    llm = ChatOpenAI(model="gpt-4o-mini", temperature=0)
    agent = create_react_agent(llm, tools, system_prompt)
    executor = AgentExecutor(agent=agent, tools=tools, verbose=True, max_iterations=8)

    questions = [
        "What does Alice prefer in terms of her tools and interface?",
        "Remember that Alice also prefers async programming. Then tell me all her preferences.",
        "What projects is Alice working on and when are they due?",
        "Given Alice's skills and preferences, what kind of work suits her best?",
    ]

    print("\n" + "=" * 60)
    print(" LangChain ReAct Agent Demo")
    print("=" * 60)

    for q in questions:
        print(f"\n[Q] {q}")
        result = executor.invoke({"input": q})
        print(f"\n[A] {result['output']}")
        print("-" * 40)


def main():
    with SyncNocturnusAIClient(SERVER, database=DB) as client:
        client.ensure_database()
        print("=== Seeding knowledge base ===")
        seed_knowledge(client)

        print("\n=== Direct tool calls (no LLM) ===")
        demo_tools_directly(client)

        print("\n=== Live LangChain agent ===")
        demo_with_llm(client)


if __name__ == "__main__":
    main()
