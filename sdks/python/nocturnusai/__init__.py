"""NocturnusAI Python SDK — client library for the NocturnusAI inference engine.

NocturnusAI is a logic-based inference engine and knowledge database that provides
deterministic multi-step logical reasoning, rule-based inference, and state
management via an HTTP API.

Quick start::

    from nocturnusai import NocturnusAIClient

    async with NocturnusAIClient("http://localhost:9300") as client:
        await client.assert_fact("parent", ["alice", "bob"])
        await client.assert_rule(
            head={"predicate": "grandparent", "args": ["?x", "?z"]},
            body=[
                {"predicate": "parent", "args": ["?x", "?y"]},
                {"predicate": "parent", "args": ["?y", "?z"]},
            ],
        )
        results = await client.infer("grandparent", ["?who", "?child"])

For synchronous usage::

    from nocturnusai import SyncNocturnusAIClient

    with SyncNocturnusAIClient("http://localhost:9300") as client:
        client.assert_fact("parent", ["alice", "bob"])
        results = client.query("parent", ["?x", "bob"])
"""

from nocturnusai.client import NocturnusAIClient, SyncNocturnusAIClient
from nocturnusai.exceptions import (
    NocturnusAIAPIError,
    NocturnusAIConflictError,
    NocturnusAIConnectionError,
    NocturnusAIError,
    NocturnusAINotFoundError,
    NocturnusAITimeoutError,
    NocturnusAIValidationError,
)
from nocturnusai.models import (
    Atom,
    ConsolidationResult,
    ContextWindow,
    DecayResult,
    ProofNode,
    ProofStep,
    ProofTree,
    ScoredAtom,
    TurnContextResult,
    TurnFact,
)

__version__ = "0.3.2"

__all__ = [
    # Clients
    "NocturnusAIClient",
    "SyncNocturnusAIClient",
    # Models
    "Atom",
    "ConsolidationResult",
    "ContextWindow",
    "DecayResult",
    "ProofNode",
    "ProofStep",
    "ProofTree",
    "ScoredAtom",
    "TurnContextResult",
    "TurnFact",
    # Exceptions
    "NocturnusAIAPIError",
    "NocturnusAIConflictError",
    "NocturnusAIConnectionError",
    "NocturnusAIError",
    "NocturnusAINotFoundError",
    "NocturnusAITimeoutError",
    "NocturnusAIValidationError",
]
