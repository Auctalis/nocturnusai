"""LangGraph + NocturnusAI example: Stateful workflow with checkpointing.

Prerequisites:
    pip install nocturnusai[langgraph]
    # Start NocturnusAI: ./gradlew :nocturnusai-server:run
"""
from nocturnusai import SyncNocturnusAIClient
from nocturnusai.langgraph import NocturnusAICheckpointSaver

client = SyncNocturnusAIClient("http://localhost:9300")
saver = NocturnusAICheckpointSaver(client=client)

print("NocturnusAI LangGraph checkpoint saver ready")
print("Usage: app = graph.compile(checkpointer=saver)")
print("\nLangGraph threads map to NocturnusAI scopes for isolation.")
