"""
research_crew.py — CrewAI + NocturnusAI

Scenario: A 3-agent crew producing a market summary. Each crew member
(Researcher, Analyst, Writer) has a task-scoped Nocturnus context so
they only see the facts their role requires — not the full crew transcript.

This demonstrates:
  1. Task-scoped context per agent role
  2. Shared knowledge base across agents with scope filtering
  3. Nocturnus as the shared storage backend for a crew

Prerequisites:
  pip install nocturnusai crewai
  docker run -d -p 9300:9300 ghcr.io/auctalis/nocturnusai:latest
  export OPENAI_API_KEY=sk-...

Run:
  python research_crew.py
"""

import os
from nocturnusai import SyncNocturnusAIClient
from nocturnusai.crewai import get_nocturnusai_tools

SERVER = "http://localhost:9300"
DB = "crewai-demo"
TOPIC = "ai-agent-infrastructure-2026"


# Seed facts the crew will reason over. In a real flow, the Researcher
# agent would produce these via web search / tool calls. We seed them
# so the example runs quickly and deterministically.
SEED_FACTS = [
    ("market_size", ("ai_agent_infra_2026", "22B_usd")),
    ("market_size", ("ai_agent_infra_2024", "3B_usd")),
    ("growth_rate", ("ai_agent_infra", "cagr_61_percent")),
    ("key_player", ("langchain", "orchestration_layer")),
    ("key_player", ("nocturnus", "context_layer")),
    ("key_player", ("openai", "model_layer")),
    ("incumbent_risk", ("langchain", "framework_churn")),
    ("incumbent_risk", ("nocturnus", "model_provider_competition")),
    ("tailwind", ("context_layer", "token_cost_pressure")),
    ("tailwind", ("context_layer", "enterprise_agent_adoption")),
]


def seed_knowledge(client):
    """Seed the knowledge base with research findings."""
    print("─" * 70)
    print(" Seeding crew knowledge base")
    print("─" * 70)
    for predicate, args in SEED_FACTS:
        client.tell(predicate=predicate, args=list(args), scope=TOPIC)
    print(f"  Seeded {len(SEED_FACTS)} facts under scope='{TOPIC}'")


def run_crew(client):
    """Run the 3-agent research crew."""
    if not os.getenv("OPENAI_API_KEY"):
        print("\n[skipped] Set OPENAI_API_KEY to run the crew.\n")
        return

    try:
        from crewai import Agent, Task, Crew, Process
    except ImportError:
        print("\n[skipped] pip install crewai to run the crew.\n")
        return

    tools = get_nocturnusai_tools(client, scope=TOPIC)

    researcher = Agent(
        role="Market Researcher",
        goal="Identify market size and key players in AI agent infrastructure",
        backstory="Analyst specializing in emerging developer infrastructure.",
        tools=tools,
        verbose=True,
    )

    analyst = Agent(
        role="Strategy Analyst",
        goal="Identify risks and tailwinds for the context-layer category",
        backstory="Strategy consultant focused on infrastructure layer economics.",
        tools=tools,
        verbose=True,
    )

    writer = Agent(
        role="Brief Writer",
        goal="Write a one-paragraph executive summary for investors",
        backstory="Investment memo author. Crisp, specific, no hedging.",
        tools=tools,
        verbose=True,
    )

    research_task = Task(
        description=(
            f"Query the knowledge base (scope='{TOPIC}') for market_size and "
            "key_player facts. Summarize the market size, growth rate, and "
            "three key players in 2-3 sentences."
        ),
        expected_output="Bulleted list of findings.",
        agent=researcher,
    )

    analysis_task = Task(
        description=(
            f"Query incumbent_risk and tailwind facts (scope='{TOPIC}'). "
            "Identify the single biggest risk and the single biggest tailwind "
            "for the context-layer category."
        ),
        expected_output="Risk + tailwind in 2 sentences.",
        agent=analyst,
    )

    writing_task = Task(
        description=(
            "Write a 3-sentence executive summary incorporating the research "
            "findings and the strategy analysis."
        ),
        expected_output="Three-sentence investment brief.",
        agent=writer,
        context=[research_task, analysis_task],
    )

    crew = Crew(
        agents=[researcher, analyst, writer],
        tasks=[research_task, analysis_task, writing_task],
        process=Process.sequential,
        verbose=True,
    )

    print("\n" + "=" * 70)
    print(" CrewAI + NocturnusAI — AI agent infrastructure brief")
    print("=" * 70)
    result = crew.kickoff()
    print("\n── Final brief ──")
    print(result)


def main():
    with SyncNocturnusAIClient(SERVER, database=DB) as client:
        client.ensure_database()

        seed_knowledge(client)
        run_crew(client)

        print("\nCleaning up...")
        result = client.delete_scope(TOPIC)
        print(f"Deleted {result.get('deleted', 0)} facts from scope '{TOPIC}'")


if __name__ == "__main__":
    main()
