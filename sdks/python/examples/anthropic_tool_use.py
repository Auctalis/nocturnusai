"""Anthropic SDK + NocturnusAI example: Claude with knowledge tools.

Prerequisites:
    pip install nocturnusai anthropic
    # Start NocturnusAI: ./gradlew :nocturnusai-server:run
"""
from nocturnusai import SyncNocturnusAIClient
from nocturnusai.anthropic_tools import get_nocturnusai_tool_definitions

client = SyncNocturnusAIClient("http://localhost:9300")
tools = get_nocturnusai_tool_definitions()

print("NocturnusAI Anthropic tool definitions:")
for tool_def in tools:
    print(f"  - {tool_def['name']}: {tool_def['description'][:60]}...")

print("\nUsage:")
print("  response = anthropic.messages.create(tools=tools, ...)")
print("  result = handle_tool_call(client, tool_use.name, tool_use.input)")
