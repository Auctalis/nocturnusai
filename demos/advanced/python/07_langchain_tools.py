"""
07_langchain_tools.py — LangChain tool integration

Demonstrates:
  - NocturnusAIAssertTool — let an LLM agent assert facts
  - NocturnusAIQueryTool  — let an LLM agent query the knowledge base
  - NocturnusAIInferTool  — let an LLM agent run inference
  - NocturnusAIContextTool — let an LLM agent fetch its context window

Requires:
  pip install langchain langchain-openai nocturnusai
  export OPENAI_API_KEY=...
"""

import os
from nocturnusai import NocturnusAIClient
from nocturnusai.langchain import (
    NocturnusAIAssertTool,
    NocturnusAIQueryTool,
    NocturnusAIInferTool,
    NocturnusAIContextTool,
)

SERVER = "http://localhost:9300"


def demo_tools_directly():
    """
    Show how each LangChain tool works when called directly (no LLM needed).
    Useful for understanding the input/output format the LLM will use.
    """
    import asyncio

    async def run():
        async with NocturnusAIClient(SERVER, database="demo-langchain") as nai:
            await nai.ensure_database()
            assert_tool = NocturnusAIAssertTool(client=nai)
            query_tool = NocturnusAIQueryTool(client=nai)
            infer_tool = NocturnusAIInferTool(client=nai)
            context_tool = NocturnusAIContextTool(client=nai)

            print("=== Assert via tool ===")
            res = await assert_tool.arun('{"predicate": "likes", "args": ["alice", "cats"]}')
            print(f"  {res}")

            res = await assert_tool.arun('{"predicate": "likes", "args": ["bob", "dogs"]}')
            res = await assert_tool.arun('{"predicate": "pet_owner", "args": ["alice", "whiskers"]}')

            print("\n=== Query via tool ===")
            res = await query_tool.arun('{"predicate": "likes", "args": ["?person", "?thing"]}')
            print(f"  {res}")

            print("\n=== Infer via tool ===")
            # First add a rule through the SDK directly
            await nai.assert_rule(
                head={"predicate": "animal_lover", "args": ["?x"]},
                body=[{"predicate": "likes", "args": ["?x", "?animal"]}],
            )
            res = await infer_tool.arun('{"predicate": "animal_lover", "args": ["?who"]}')
            print(f"  {res}")

            print("\n=== Context window via tool ===")
            res = await context_tool.arun('{"max_facts": 5}')
            print(f"  {res[:300]}...")

    asyncio.run(run())


def demo_with_agent():
    """
    Wire the tools into an actual LangChain ReAct agent.
    Requires OPENAI_API_KEY to be set.
    """
    if not os.getenv("OPENAI_API_KEY"):
        print("\n=== LangChain Agent (skipped — OPENAI_API_KEY not set) ===")
        print("  Set OPENAI_API_KEY and re-run to see the full agent demo.")
        return

    import asyncio

    async def run():
        from langchain.agents import AgentType, initialize_agent
        from langchain_openai import ChatOpenAI

        async with NocturnusAIClient(SERVER, database="demo-langchain-agent") as nai:
            await nai.ensure_database()
            tools = [
                NocturnusAIAssertTool(client=nai),
                NocturnusAIQueryTool(client=nai),
                NocturnusAIInferTool(client=nai),
                NocturnusAIContextTool(client=nai),
            ]

            llm = ChatOpenAI(model="gpt-4o-mini", temperature=0)
            agent = initialize_agent(tools, llm, agent=AgentType.OPENAI_FUNCTIONS, verbose=True)

            print("\n=== LangChain Agent Demo ===")
            result = await agent.arun(
                "Remember that Alice likes hiking and Bob likes cooking. "
                "Then tell me who likes what."
            )
            print(f"\nAgent answer: {result}")

    asyncio.run(run())


if __name__ == "__main__":
    demo_tools_directly()
    demo_with_agent()
