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

    Attribute access is delegated to the inner :class:`Atom`, so
    ``scored.predicate`` works the same as ``scored.atom.predicate``.
    """

    atom: Atom = Field(description="The underlying atom.")
    salience: float = Field(description="Composite salience score between 0.0 and 1.0.")

    model_config = {"populate_by_name": True}

    def __getattr__(self, name: str) -> Any:
        """Delegate unknown attribute access to the inner Atom."""
        try:
            return getattr(self.atom, name)
        except AttributeError:
            raise AttributeError(
                f"'{type(self).__name__}' object has no attribute '{name}'"
            ) from None


class ContextWindow(BaseModel):
    """A salience-ranked window of facts for agent reasoning.

    When advanced parameters (goals, session_id, relevance_buckets) are used,
    additional fields like window_id, rules, and contradictions will be populated.
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
    # New: LLM-formatted text (present when format parameter used)
    formatted_text: str | None = Field(
        default=None,
        alias="formattedText",
        description="LLM-optimized text rendering (present when format is specified).",
    )
    # Advanced fields (present when optimization engine used)
    window_id: str | None = Field(
        default=None,
        alias="windowId",
        description="Unique window identifier (present in advanced mode).",
    )
    rules: list[str] | None = Field(
        default=None,
        description="Relevant reasoning rules (present in advanced mode).",
    )
    contradictions_found: int | None = Field(
        default=None,
        alias="contradictionsFound",
        description="Number of contradictions detected.",
    )
    contradictions_resolved: int | None = Field(
        default=None,
        alias="contradictionsResolved",
        description="Number of contradictions auto-resolved.",
    )
    contradictions: list[ContradictionInfo] | None = Field(
        default=None,
        description="Detected contradictions.",
    )
    deduplication_savings: int | None = Field(
        default=None,
        alias="deduplicationSavings",
        description="Number of duplicate facts removed.",
    )
    bucket_stats: dict[str, BucketStats] | None = Field(
        default=None,
        alias="bucketStats",
        description="Statistics per relevance bucket.",
    )
    goal_driven: bool = Field(
        default=False,
        alias="goalDriven",
        description="Whether the optimization was goal-driven.",
    )
    knowledge_generation: int | None = Field(
        default=None,
        alias="knowledgeGeneration",
        description="Knowledge base generation counter.",
    )

    model_config = {"populate_by_name": True}


class DerivationInfo(BaseModel):
    """Provenance info showing how a fact was derived."""

    rule: str = Field(description="The rule used for derivation.")
    premises: list[str] = Field(
        default_factory=list,
        description="Premises used in the derivation.",
    )
    model_config = {"populate_by_name": True}


class ContextEntry(BaseModel):
    """A fact in an optimized context window with salience and provenance."""

    predicate: str = Field(description="The predicate name.")
    args: list[str] = Field(default_factory=list, description="Arguments to the predicate.")
    negated: bool = Field(default=False, description="Whether this fact is negated.")
    scope: str | None = Field(default=None, description="Optional scope.")
    salience: float = Field(description="Salience score 0.0-1.0.")
    category: str = Field(description="Category (bucket name, 'inferred', 'recent', etc.).")
    char_count: int = Field(
        alias="charCount",
        description="Character count of fact representation.",
    )
    provenance: DerivationInfo | None = Field(
        default=None,
        description="Derivation chain if available.",
    )
    created_at: int | None = Field(default=None, alias="createdAt")
    valid_from: int | None = Field(default=None, alias="validFrom")
    valid_until: int | None = Field(default=None, alias="validUntil")
    metadata: dict[str, Any] = Field(default_factory=dict)
    model_config = {"populate_by_name": True}


class ContradictionInfo(BaseModel):
    """A detected contradiction between positive and negative facts."""

    predicate: str = Field(description="The contradicted predicate.")
    args: list[str] = Field(default_factory=list, description="Arguments of the contradicted fact.")
    positive_salience: float = Field(
        alias="positiveSalience",
        description="Salience of the positive version.",
    )
    negative_salience: float = Field(
        alias="negativeSalience",
        description="Salience of the negative version.",
    )
    model_config = {"populate_by_name": True}


class BucketStats(BaseModel):
    """Statistics for a relevance bucket allocation."""
    facts_included: int = Field(alias="factsIncluded")
    max_allocation: int = Field(alias="maxAllocation")
    min_salience: float = Field(alias="minSalience")
    max_salience: float = Field(alias="maxSalience")
    model_config = {"populate_by_name": True}


class OptimizedContext(BaseModel):
    """Result of goal-driven context optimization."""

    window_id: str = Field(alias="windowId", description="Unique window identifier.")
    entries: list[ContextEntry] = Field(
        default_factory=list,
        description="Selected context entries.",
    )
    relevant_rules: list[str] = Field(
        default_factory=list,
        alias="relevantRules",
        description="Rules relevant to goals.",
    )
    total_facts_available: int = Field(alias="totalFactsAvailable")
    total_facts_included: int = Field(alias="totalFactsIncluded")
    deduplication_savings: int = Field(alias="deduplicationSavings")
    contradictions_found: int = Field(alias="contradictionsFound")
    contradictions_resolved: int = Field(alias="contradictionsResolved")
    contradictions: list[ContradictionInfo] = Field(
        default_factory=list,
        description="Detected contradictions.",
    )
    bucket_stats: dict[str, BucketStats] = Field(default_factory=dict, alias="bucketStats")
    total_char_count: int = Field(alias="totalCharCount")
    goal_driven: bool = Field(alias="goalDriven")
    knowledge_generation: int = Field(
        alias="knowledgeGeneration",
        description="Generation counter for change detection.",
    )
    generated_at: int = Field(alias="generatedAt")
    model_config = {"populate_by_name": True}


class RemovedEntry(BaseModel):
    """A fact that was removed between context snapshots."""
    key: str = Field(description="Internal entry key.")
    predicate: str = Field(description="The predicate name.")
    args: list[str] = Field(default_factory=list)
    negated: bool = Field(default=False)
    scope: str | None = Field(default=None)
    model_config = {"populate_by_name": True}


class ContextDiff(BaseModel):
    """Incremental diff between two context windows."""

    previous_window_id: str | None = Field(alias="previousWindowId")
    current_window_id: str = Field(alias="currentWindowId")
    added: list[ContextEntry] = Field(
        default_factory=list,
        description="Facts added since last window.",
    )
    removed: list[RemovedEntry] = Field(
        default_factory=list,
        description="Facts removed since last window.",
    )
    unchanged: int = Field(description="Number of unchanged facts.")
    full_refresh_recommended: bool = Field(alias="fullRefreshRecommended")
    reason: str | None = Field(default=None, description="Reason if full refresh recommended.")
    model_config = {"populate_by_name": True}


class PredicateSummaryInfo(BaseModel):
    """Summary info for a single predicate."""
    predicate: str = Field(description="The predicate name.")
    count: int = Field(description="Number of facts with this predicate.")
    model_config = {"populate_by_name": True}


class ContextSummary(BaseModel):
    """Compact summary of the knowledge base."""
    total_facts: int = Field(alias="totalFacts")
    predicate_count: int = Field(alias="predicateCount")
    top_predicates: list[PredicateSummaryInfo] = Field(default_factory=list, alias="topPredicates")
    facts_with_ttl: int = Field(alias="factsWithTtl")
    facts_expiring_within_1h: int = Field(alias="factsExpiringWithin1h")
    contradictions: int = Field(description="Number of contradictions in the KB.")
    top_salient_facts: list[ContextEntry] = Field(default_factory=list, alias="topSalientFacts")
    total_char_count: int = Field(alias="totalCharCount")
    knowledge_generation: int = Field(alias="knowledgeGeneration")
    generated_at: int = Field(alias="generatedAt")
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
