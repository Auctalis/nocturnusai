"""CrewAI + NocturnusAI example: Research crew with shared logical memory.

Prerequisites:
    pip install nocturnusai[crewai]
    # Start NocturnusAI: ./gradlew :nocturnusai-server:run
    # Create tenant: curl -X POST http://localhost:9300/admin/databases/default/tenants \
    #   -H "Content-Type: application/json" -d '{"tenantId": "default"}'
"""
from nocturnusai import SyncNocturnusAIClient
from nocturnusai.crewai import NocturnusAIStorage, get_nocturnusai_tools

client = SyncNocturnusAIClient("http://localhost:9300")
tools = get_nocturnusai_tools(client)
storage = NocturnusAIStorage(client=client)

print("NocturnusAI CrewAI tools ready:")
for tool in tools:
    print(f"  - {tool.name}: {tool.description[:60]}...")

print("\nStorage backend ready for LongTermMemory")
print("Usage: Crew(tools=tools, memory=True, long_term_memory=LongTermMemory(storage=storage))")
