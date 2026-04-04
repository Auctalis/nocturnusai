"""OpenAI Agents SDK + NocturnusAI example: Agent with knowledge tools.

Prerequisites:
    pip install nocturnusai[openai-agents]
    # Start NocturnusAI: ./gradlew :nocturnusai-server:run
"""
from nocturnusai import SyncNocturnusAIClient
from nocturnusai.openai_agents import get_nocturnusai_tools

client = SyncNocturnusAIClient("http://localhost:9300")
tools = get_nocturnusai_tools(client)

print("NocturnusAI OpenAI Agents tools ready:")
for i, tool in enumerate(tools):
    name = getattr(tool, "name", getattr(tool, "__name__", f"tool_{i}"))
    print(f"  - {name}")

print("\nUsage: Agent(name='reasoner', tools=tools)")
