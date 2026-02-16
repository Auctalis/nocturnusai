"""AxiomBase Python SDK — client library for the AxiomBase inference engine.

AxiomBase is a logic-based inference engine and knowledge database that provides
deterministic multi-step logical reasoning, rule-based inference, and state
management via an HTTP API.

Quick start::

    from axiombase import AxiomBaseClient

    async with AxiomBaseClient("http://localhost:9300") as client:
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

    from axiombase import SyncAxiomBaseClient

    with SyncAxiomBaseClient("http://localhost:9300") as client:
        client.assert_fact("parent", ["alice", "bob"])
        results = client.query("parent", ["?x", "bob"])
"""

from axiombase.client import AxiomBaseClient, SyncAxiomBaseClient
from axiombase.exceptions import (
    AxiomBaseAPIError,
    AxiomBaseConflictError,
    AxiomBaseConnectionError,
    AxiomBaseError,
    AxiomBaseNotFoundError,
    AxiomBaseTimeoutError,
    AxiomBaseValidationError,
)
from axiombase.models import (
    Atom,
    ConsolidationResult,
    ContextWindow,
    DecayResult,
    ProofNode,
    ProofStep,
    ProofTree,
    ScoredAtom,
)

__version__ = "0.1.0"

__all__ = [
    # Clients
    "AxiomBaseClient",
    "SyncAxiomBaseClient",
    # Models
    "Atom",
    "ConsolidationResult",
    "ContextWindow",
    "DecayResult",
    "ProofNode",
    "ProofStep",
    "ProofTree",
    "ScoredAtom",
    # Exceptions
    "AxiomBaseAPIError",
    "AxiomBaseConflictError",
    "AxiomBaseConnectionError",
    "AxiomBaseError",
    "AxiomBaseNotFoundError",
    "AxiomBaseTimeoutError",
    "AxiomBaseValidationError",
]
