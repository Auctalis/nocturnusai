"""AutoGen + NocturnusAI example: Agent with logical reasoning capabilities.

Prerequisites:
    pip install nocturnusai[autogen]
    # Start NocturnusAI: ./gradlew :nocturnusai-server:run
"""
from nocturnusai import SyncNocturnusAIClient
from nocturnusai.autogen import get_nocturnusai_tools, NocturnusAIMemory

client = SyncNocturnusAIClient("http://localhost:9300")
tools = get_nocturnusai_tools(client)
memory = NocturnusAIMemory(client=client)

print("NocturnusAI AutoGen tools ready:")
for tool in tools:
    print(f"  - {tool.__name__}: {tool.__doc__[:60] if tool.__doc__ else ''}")

print("\nMemory protocol ready (add/query/update_context/clear/close)")
print("Usage: ConversableAgent('agent', tools=[FunctionTool(t) for t in tools], memory=memory)")
