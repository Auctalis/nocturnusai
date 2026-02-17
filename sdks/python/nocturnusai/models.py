"""Pydantic models for the NocturnusAI Python SDK.

These models mirror the server-side DTOs and provide structured,
validated representations of NocturnusAI API responses.
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field


class Atom(BaseModel):
    """A single unit of knowledge in the NocturnusAI knowledge base.

    An atom represents a predicate applied to a list of arguments, with an
    associated truth value, optional scope for multi-tenancy/isolation, and
    temporal metadata for memory lifecycle management.

    Example::

        Atom(predicate="parent", args=["alice", "bob"])
        Atom(predicate="likes", args=["alice", "pizza"], negated=True)
    """

    predicate: str = Field(description="The predicate name (e.g., 'parent', 'likes').")
    args: list[str] = Field(default_factory=list, description="Arguments to the predicate.")
    negated: bool = Field(default=False, description="Whether this atom is negated.")
    scope: str | None = Field(default=None, description="Optional scope for fact isolation.")
    metadata: dict[str, Any] = Field(
        default_factory=dict,
        description="Arbitrary key-value metadata attached to this atom.",
    )
    created_at: int | None = Field(
        default=None,
        alias="createdAt",
        description="Epoch milliseconds when this atom was created.",
    )
    valid_from: int | None = Field(
        default=None,
        alias="validFrom",
        description="Epoch milliseconds from which this atom is valid.",
    )
    valid_until: int | None = Field(
        default=None,
        alias="validUntil",
        description="Epoch milliseconds until which this atom is valid.",
    )
    ttl: int | None = Field(
        default=None,
        description="Time-to-live in milliseconds. The atom auto-expires after this duration.",
    )

    model_config = {"populate_by_name": True}


class ScoredAtom(BaseModel):
    """An atom paired with its salience score.

    Salience is a composite score (0.0 to 1.0) reflecting recency, access
    frequency, and explicit priority. Used in context window and
    salience-ranked query responses.
    """

    atom: Atom = Field(description="The underlying atom.")
    salience: float = Field(description="Composite salience score between 0.0 and 1.0.")

    model_config = {"populate_by_name": True}


class ContextWindow(BaseModel):
    """A salience-ranked window of facts for agent reasoning.

    Represents the optimal subset of knowledge for the current reasoning
    step, ranked by a composite score of recency, access frequency, and
    priority.
    """

    facts: list[ScoredAtom] = Field(
        default_factory=list,
        description="Salience-ranked list of facts.",
    )
    total_available: int = Field(
        alias="totalAvailable",
        description="Total number of facts available in the knowledge base.",
    )
    window_size: int = Field(
        alias="windowSize",
        description="Number of facts included in this window.",
    )
    predicate_distribution: dict[str, int] = Field(
        default_factory=dict,
        alias="predicateDistribution",
        description="Distribution of predicates within the window.",
    )
    generated_at: int = Field(
        alias="generatedAt",
        description="Epoch milliseconds when this window was generated.",
    )

    model_config = {"populate_by_name": True}


class ConsolidationResult(BaseModel):
    """Result of a memory consolidation operation.

    Consolidation detects repeated episodic patterns and compresses them
    into semantic summary facts.
    """

    facts_consolidated: int = Field(
        alias="factsConsolidated",
        description="Number of episodic fact patterns that were consolidated.",
    )
    new_facts: list[Atom] = Field(
        default_factory=list,
        alias="newFacts",
        description="Newly created summary facts from consolidation.",
    )
    timestamp: int = Field(description="Epoch milliseconds when consolidation was performed.")

    model_config = {"populate_by_name": True}


class DecayResult(BaseModel):
    """Result of a memory decay operation.

    Decay expires facts that have exceeded their TTL and evicts
    low-salience facts when the knowledge base exceeds capacity.
    """

    expired_count: int = Field(
        alias="expiredCount",
        description="Number of facts removed due to TTL expiration.",
    )
    evicted_count: int = Field(
        alias="evictedCount",
        description="Number of facts evicted due to low salience.",
    )
    removed_atoms: list[Atom] = Field(
        default_factory=list,
        alias="removedAtoms",
        description="List of atoms that were removed.",
    )
    timestamp: int = Field(description="Epoch milliseconds when decay was performed.")

    model_config = {"populate_by_name": True}


class ProofStep(BaseModel):
    """A single step in a proof tree.

    Either a direct fact match or a rule application with sub-proofs
    for each body condition.
    """

    type: str = Field(description="Step type: 'fact_match' or 'rule_application'.")
    fact: Atom | None = Field(default=None, description="The matched fact (for fact_match).")
    rule: str | None = Field(default=None, description="The applied rule (for rule_application).")
    body_proofs: list[ProofNode] | None = Field(
        default=None,
        alias="bodyProofs",
        description="Sub-proofs for each body condition (for rule_application).",
    )

    model_config = {"populate_by_name": True}


class ProofNode(BaseModel):
    """A node in a proof tree, showing how a goal was derived."""

    goal: Atom = Field(description="The goal that was proved at this node.")
    step: ProofStep = Field(description="How the goal was proved.")
    substitution: dict[str, str] = Field(
        default_factory=dict,
        description="Variable substitutions applied at this node.",
    )

    model_config = {"populate_by_name": True}


class ProofTree(BaseModel):
    """A complete proof tree for an inference result.

    Contains the derived result atom and the full proof showing
    the chain of reasoning.
    """

    result: Atom = Field(description="The derived result atom.")
    proof: ProofNode = Field(description="The root of the proof tree.")

    model_config = {"populate_by_name": True}


class ErrorResponse(BaseModel):
    """Error response from the NocturnusAI server."""

    code: str = Field(description="Machine-readable error code.")
    message: str = Field(description="Human-readable error message.")
    details: dict[str, str] | None = Field(
        default=None,
        description="Additional error details.",
    )

    model_config = {"populate_by_name": True}
