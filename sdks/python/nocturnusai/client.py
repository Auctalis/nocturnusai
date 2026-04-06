"""Async and sync HTTP clients for the NocturnusAI server API.

Usage::

    # Async
    async with NocturnusAIClient("http://localhost:9300") as client:
        await client.assert_fact("parent", ["alice", "bob"])
        results = await client.infer("parent", ["?x", "bob"])

    # Sync
    with SyncNocturnusAIClient("http://localhost:9300") as client:
        client.assert_fact("parent", ["alice", "bob"])
        results = client.infer("parent", ["?x", "bob"])
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
import random
from typing import Any

import httpx

from nocturnusai.exceptions import (
    NocturnusAIAPIError,
    NocturnusAIConflictError,
    NocturnusAIConnectionError,
    NocturnusAINotFoundError,
    NocturnusAITimeoutError,
    NocturnusAIValidationError,
)
from nocturnusai.models import (
    Atom,
    ConsolidationResult,
    ContextDiff,
    ContextSummary,
    ContextWindow,
    DecayResult,
    OptimizedContext,
    ProofTree,
)

logger = logging.getLogger("nocturnusai")

# HTTP status codes that are safe to retry on.
_RETRYABLE_STATUS_CODES = frozenset({429, 502, 503, 504})

# Default retry configuration.
_DEFAULT_MAX_RETRIES = 3
_DEFAULT_RETRY_BASE_DELAY = 0.5  # seconds
_DEFAULT_RETRY_MAX_DELAY = 10.0  # seconds
_DEFAULT_TIMEOUT = 30.0  # seconds


class NocturnusAIClient:
    """Async HTTP client for the NocturnusAI inference engine API.

    This client communicates with the NocturnusAI server over HTTP, providing
    methods for asserting facts and rules, running inference, managing memory
    lifecycle, and executing Logiql DSL commands.

    Args:
        base_url: The base URL of the NocturnusAI server (e.g., ``http://localhost:9300``).
        api_key: Optional API key for authentication.
        database: The database name to use (sent via ``X-Database`` header).
        tenant_id: The tenant ID to use (sent via ``X-Tenant-ID`` header).
        max_retries: Maximum number of retry attempts for transient errors.
        timeout: Request timeout in seconds.

    Example::

        async with NocturnusAIClient("http://localhost:9300") as client:
            await client.assert_fact("parent", ["alice", "bob"])
            results = await client.infer("grandparent", ["?who", "charlie"])
            for atom in results:
                print(f"{atom.predicate}({', '.join(atom.args)})")
    """

    def __init__(
        self,
        base_url: str,
        api_key: str | None = None,
        database: str = "default",
        tenant_id: str = "default",
        max_retries: int = _DEFAULT_MAX_RETRIES,
        timeout: float = _DEFAULT_TIMEOUT,
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._api_key = api_key
        self._database = database
        self._tenant_id = tenant_id
        self._max_retries = max_retries
        self._timeout = timeout
        self._client: httpx.AsyncClient | None = None

    @property
    def database(self) -> str:
        """The currently selected database name."""
        return self._database

    @database.setter
    def database(self, value: str) -> None:
        self._database = value

    @property
    def tenant_id(self) -> str:
        """The currently selected tenant ID."""
        return self._tenant_id

    @tenant_id.setter
    def tenant_id(self, value: str) -> None:
        self._tenant_id = value

    def _build_headers(self) -> dict[str, str]:
        """Build the common HTTP headers for all requests."""
        headers: dict[str, str] = {
            "X-Database": self._database,
            "X-Tenant-ID": self._tenant_id,
            "Content-Type": "application/json",
            "Accept": "application/json",
        }
        if self._api_key:
            headers["X-API-Key"] = self._api_key
        return headers

    async def _ensure_client(self) -> httpx.AsyncClient:
        """Lazily create or return the underlying httpx.AsyncClient."""
        if self._client is None or self._client.is_closed:
            self._client = httpx.AsyncClient(
                base_url=self._base_url,
                headers=self._build_headers(),
                timeout=httpx.Timeout(self._timeout),
            )
        return self._client

    async def _request(
        self,
        method: str,
        path: str,
        *,
        json_body: Any | None = None,
        params: dict[str, Any] | None = None,
        expect_json: bool = True,
        extra_headers: dict[str, str] | None = None,
    ) -> Any:
        """Send an HTTP request with retry logic and error handling.

        Retries are attempted for transient errors (429, 502, 503, 504) and
        connection failures, using exponential backoff with jitter.

        Args:
            method: HTTP method (GET, POST, DELETE, etc.).
            path: URL path relative to the base URL.
            json_body: Optional JSON request body.
            params: Optional query parameters.
            expect_json: Whether to parse the response as JSON.
            extra_headers: Optional additional headers for this request only.

        Returns:
            Parsed JSON response or raw text, depending on ``expect_json``.

        Raises:
            NocturnusAIAPIError: For non-retryable server errors.
            NocturnusAIConnectionError: When the server is unreachable after retries.
            NocturnusAITimeoutError: When the request times out after retries.
        """
        client = await self._ensure_client()
        last_exception: Exception | None = None

        for attempt in range(self._max_retries + 1):
            try:
                response = await client.request(
                    method,
                    path,
                    json=json_body,
                    params=params,
                    headers=extra_headers,
                )

                # Successful response.
                if response.status_code < 400:
                    if expect_json:
                        content_type = response.headers.get("content-type", "")
                        if "application/json" in content_type:
                            return response.json()
                        # Some endpoints return plain text.
                        return response.text
                    return response.text

                # Retryable server errors.
                if response.status_code in _RETRYABLE_STATUS_CODES:
                    last_exception = NocturnusAIAPIError(
                        message=f"Server returned {response.status_code}",
                        status_code=response.status_code,
                    )
                    if attempt < self._max_retries:
                        delay = _compute_backoff_delay(attempt)
                        logger.warning(
                            "Retryable error (HTTP %d) on %s %s, retrying in %.2fs "
                            "(attempt %d/%d)",
                            response.status_code,
                            method,
                            path,
                            delay,
                            attempt + 1,
                            self._max_retries,
                        )
                        await asyncio.sleep(delay)
                        continue

                # Non-retryable error — raise immediately.
                _raise_for_status(response)

            except httpx.ConnectError as exc:
                last_exception = NocturnusAIConnectionError(
                    f"Failed to connect to NocturnusAI at {self._base_url}: {exc}"
                )
                if attempt < self._max_retries:
                    delay = _compute_backoff_delay(attempt)
                    logger.warning(
                        "Connection error on %s %s, retrying in %.2fs (attempt %d/%d)",
                        method,
                        path,
                        delay,
                        attempt + 1,
                        self._max_retries,
                    )
                    await asyncio.sleep(delay)
                    continue

            except httpx.TimeoutException as exc:
                last_exception = NocturnusAITimeoutError(
                    f"Request timed out after {self._timeout}s: {exc}"
                )
                if attempt < self._max_retries:
                    delay = _compute_backoff_delay(attempt)
                    logger.warning(
                        "Timeout on %s %s, retrying in %.2fs (attempt %d/%d)",
                        method,
                        path,
                        delay,
                        attempt + 1,
                        self._max_retries,
                    )
                    await asyncio.sleep(delay)
                    continue

        # All retries exhausted.
        if last_exception is not None:
            raise last_exception
        raise NocturnusAIAPIError(  # pragma: no cover
            message="Request failed after all retries",
            status_code=0,
        )

    # ------------------------------------------------------------------
    # Fact Operations
    # ------------------------------------------------------------------

    async def assert_fact(
        self,
        predicate: str,
        args: list[str],
        scope: str | None = None,
        negated: bool = False,
        ttl: int | None = None,
        valid_from: int | None = None,
        valid_until: int | None = None,
        metadata: dict[str, Any] | None = None,
        transaction_id: int | str | None = None,
    ) -> dict[str, Any]:
        """Assert a fact into the knowledge base.

        Facts are the fundamental units of knowledge in NocturnusAI. A fact
        consists of a predicate applied to a list of arguments.

        Args:
            predicate: The predicate name (e.g., ``"parent"``, ``"likes"``).
            args: Arguments to the predicate (e.g., ``["alice", "bob"]``).
            scope: Optional scope for fact isolation.
            negated: Whether to assert the negation of this fact.
            ttl: Time-to-live in milliseconds. The fact auto-expires after
                this duration.
            valid_from: Epoch ms from which this fact is valid.
            valid_until: Epoch ms until which this fact is valid.
            metadata: Optional key-value metadata to attach.
            transaction_id: Optional transaction ID to buffer this operation.

        Returns:
            A dict with the server's response text under the ``"result"`` key.

        Raises:
            NocturnusAIValidationError: If the fact request is malformed.
            NocturnusAIConflictError: If the fact contradicts existing knowledge.

        Example::

            await client.assert_fact("parent", ["alice", "bob"])
            await client.assert_fact("likes", ["alice", "pizza"], negated=True)
            await client.assert_fact(
                "location", ["alice", "office"],
                ttl=3600000,  # expires in 1 hour
                metadata={"source": "gps"},
            )
        """
        body: dict[str, Any] = {
            "predicate": predicate,
            "args": args,
            "negated": negated,
        }
        if scope is not None:
            body["scope"] = scope
        if ttl is not None:
            body["ttl"] = ttl
        if valid_from is not None:
            body["validFrom"] = valid_from
        if valid_until is not None:
            body["validUntil"] = valid_until
        if metadata is not None:
            body["metadata"] = metadata

        extra = {"X-Transaction-ID": str(transaction_id)} if transaction_id else None
        result = await self._request("POST", "/assert/fact", json_body=body, extra_headers=extra)
        if isinstance(result, str):
            return {"result": result}
        return result

    async def assert_rule(
        self,
        head: dict[str, Any],
        body: list[dict[str, Any]],
        scope: str | None = None,
    ) -> dict[str, Any]:
        """Assert a logical rule (Horn clause) for inference.

        Rules enable multi-step deductive reasoning. A rule has a head
        (consequent) and a body of conditions (antecedents). The head is
        derived when all body conditions are satisfied.

        Use ``?``-prefixed strings for variables (e.g., ``"?x"``, ``"?who"``).

        Args:
            head: The consequent atom as a dict with ``"predicate"`` and
                ``"args"`` keys. May also include ``"negated"`` and
                ``"scope"``.
            body: A list of antecedent atoms, each a dict with
                ``"predicate"`` and ``"args"`` keys.
            scope: Optional scope for the rule.

        Returns:
            A dict with the server's response text under the ``"result"`` key.

        Raises:
            NocturnusAIValidationError: If the rule request is malformed.

        Example::

            await client.assert_rule(
                head={"predicate": "grandparent", "args": ["?x", "?z"]},
                body=[
                    {"predicate": "parent", "args": ["?x", "?y"]},
                    {"predicate": "parent", "args": ["?y", "?z"]},
                ],
            )
        """
        request_body: dict[str, Any] = {
            "head": head,
            "body": body,
        }
        if scope is not None:
            request_body["scope"] = scope

        result = await self._request("POST", "/assert/rule", json_body=request_body)
        if isinstance(result, str):
            return {"result": result}
        return result

    async def query(
        self,
        predicate: str,
        args: list[str],
        scope: str | None = None,
    ) -> list[Atom]:
        """Query facts matching a pattern via inference.

        This sends a query to the ``/infer`` endpoint, which performs
        backward-chaining inference to find all matching facts. Use
        ``?``-prefixed strings for variable positions.

        Args:
            predicate: The predicate to query.
            args: Arguments with ``?``-prefixed variables for wildcards
                (e.g., ``["?x", "bob"]``).
            scope: Optional scope filter.

        Returns:
            A list of :class:`Atom` objects matching the query.

        Example::

            results = await client.query("parent", ["?who", "bob"])
            for atom in results:
                print(atom.args[0])  # prints names of bob's parents
        """
        body: dict[str, Any] = {
            "predicate": predicate,
            "args": args,
        }
        if scope is not None:
            body["scope"] = scope

        result = await self._request("POST", "/infer", json_body=body)
        return _parse_atom_list(result)

    async def infer(
        self,
        predicate: str,
        args: list[str],
        scope: str | None = None,
        with_proof: bool = False,
    ) -> list[Atom] | list[ProofTree]:
        """Run backward-chaining logical inference.

        Unlike :meth:`query`, which simply matches stored facts, ``infer``
        applies rules to derive new conclusions through multi-step
        deductive reasoning.

        Args:
            predicate: The goal predicate to prove.
            args: Goal arguments with ``?``-prefixed variables.
            scope: Optional scope filter.
            with_proof: If ``True``, return full proof trees showing the
                derivation chain instead of bare atoms.

        Returns:
            A list of :class:`Atom` objects if ``with_proof`` is ``False``,
            or a list of :class:`ProofTree` objects if ``True``.

        Example::

            results = await client.infer("grandparent", ["?who", "charlie"])
            proofs = await client.infer(
                "grandparent", ["?who", "charlie"], with_proof=True,
            )
        """
        body: dict[str, Any] = {
            "predicate": predicate,
            "args": args,
        }
        if scope is not None:
            body["scope"] = scope

        params: dict[str, Any] | None = None
        if with_proof:
            params = {"proof": "true"}

        result = await self._request("POST", "/infer", json_body=body, params=params)

        if with_proof:
            return _parse_proof_tree_list(result)
        return _parse_atom_list(result)

    async def retract(
        self,
        predicate: str,
        args: list[str],
        scope: str | None = None,
        transaction_id: int | str | None = None,
    ) -> dict[str, Any]:
        """Retract (remove) a fact from the knowledge base.

        Retracting a fact triggers the Truth Maintenance System (TMS):
        any facts that were derived from the retracted fact are
        automatically cascade-retracted.

        Args:
            predicate: The predicate of the fact to retract.
            args: Arguments of the fact to retract.
            scope: Optional scope.
            transaction_id: Optional transaction ID to buffer this operation.

        Returns:
            A dict with the server's response text under the ``"result"`` key.

        Example::

            await client.retract("parent", ["alice", "bob"])
        """
        body: dict[str, Any] = {
            "predicate": predicate,
            "args": args,
        }
        if scope is not None:
            body["scope"] = scope

        extra = {"X-Transaction-ID": str(transaction_id)} if transaction_id else None
        result = await self._request("POST", "/retract", json_body=body, extra_headers=extra)
        if isinstance(result, str):
            return {"result": result}
        return result

    # ------------------------------------------------------------------
    # Memory Management Operations
    # ------------------------------------------------------------------

    async def context_window(
        self,
        max_facts: int = 100,
        min_salience: float = 0.0,
        predicates: list[str] | None = None,
        scope: str | None = None,
    ) -> ContextWindow:
        """Get the most salient facts for the current reasoning context.

        .. deprecated::
            Use :meth:`context` instead, which supports both simple and
            advanced (goal-driven) modes via a single unified endpoint.

        Returns facts ranked by a composite salience score reflecting
        recency, access frequency, and explicit priority. Ideal for
        populating an LLM's context with the most relevant knowledge.

        Args:
            max_facts: Maximum number of facts to return.
            min_salience: Minimum salience score (0.0 to 1.0).
            predicates: Optional list of predicates to filter by.
            scope: Optional scope filter.

        Returns:
            A :class:`ContextWindow` with ranked facts and metadata.

        Example::

            window = await client.context_window(max_facts=50, min_salience=0.1)
            for scored in window.facts:
                print(f"[{scored.salience:.3f}] {scored.atom.predicate}")
        """
        body: dict[str, Any] = {
            "maxFacts": max_facts,
            "minSalience": min_salience,
        }
        if predicates is not None:
            body["predicates"] = predicates
        if scope is not None:
            body["scope"] = scope

        result = await self._request("POST", "/memory/context", json_body=body)
        return _parse_context_window(result)

    async def context(
        self,
        *,
        max_facts: int = 100,
        min_salience: float = 0.0,
        predicates: list[str] | None = None,
        scope: str | None = None,
        format: str | None = None,
        include_rules: bool = True,
        # Advanced params (triggers optimization engine when present)
        goals: list[dict[str, Any]] | None = None,
        session_id: str | None = None,
        auto_resolve_contradictions: bool = True,
        max_facts_per_predicate: int | None = None,
        relevance_buckets: list[dict[str, Any]] | None = None,
    ) -> ContextWindow:
        """Get the optimal context for your current reasoning step.

        Simple usage returns facts ranked by salience. When goals, session_id,
        or relevance_buckets are provided, the server uses its advanced
        optimization engine with backward chaining, contradiction detection,
        and session-based incremental diffing.

        Args:
            max_facts: Maximum number of facts to return.
            min_salience: Minimum salience score (0.0 to 1.0).
            predicates: Optional list of predicates to filter by.
            scope: Optional scope filter.
            format: Output format for formattedText field: 'natural' (LLM-optimized),
                    'structured' (grouped with metadata), or None (no formattedText).
            include_rules: Include reasoning rules in formattedText (default True).
            goals: Goal atoms for goal-driven selection,
                   e.g. [{"predicate": "recommend", "args": ["?x"]}].
            session_id: Session ID for incremental diffing across turns.
            auto_resolve_contradictions: Auto-resolve contradictions by salience.
            max_facts_per_predicate: Optional diversity cap per predicate.
            relevance_buckets: Weighted predicate buckets for allocation,
                              e.g. [{"name": "prefs", "predicates": ["likes"], "weight": 3.0}].

        Returns:
            A :class:`ContextWindow` with ranked facts and metadata. When advanced
            params are used, additional fields like window_id, rules, and
            contradictions will be populated.

        Example::

            # Simple: just get the most relevant facts
            ctx = await client.context(max_facts=50)

            # With LLM-friendly formatting
            ctx = await client.context(max_facts=50, format="natural")
            print(ctx.formatted_text)  # natural language summary

            # Advanced: goal-driven with session tracking
            ctx = await client.context(
                goals=[{"predicate": "recommend", "args": ["?product"]}],
                session_id="turn-3",
                relevance_buckets=[
                    {"name": "prefs", "predicates": ["likes"], "weight": 3},
                ],
                format="natural",
            )
        """
        body: dict[str, Any] = {
            "maxFacts": max_facts,
            "minSalience": min_salience,
        }
        if predicates is not None:
            body["predicates"] = predicates
        if scope is not None:
            body["scope"] = scope
        if format is not None:
            body["format"] = format
        if not include_rules:
            body["includeRules"] = False
        if goals is not None:
            body["goals"] = goals
        if session_id is not None:
            body["sessionId"] = session_id
        if not auto_resolve_contradictions:
            body["autoResolveContradictions"] = False
        if max_facts_per_predicate is not None:
            body["maxFactsPerPredicate"] = max_facts_per_predicate
        if relevance_buckets is not None:
            body["relevanceBuckets"] = relevance_buckets

        result = await self._request("POST", "/memory/context", json_body=body)
        return _parse_context_window(result)

    async def optimize_context(
        self,
        goals: list[dict[str, Any]] | None = None,
        max_facts: int = 100,
        max_facts_per_predicate: int | None = None,
        auto_resolve_contradictions: bool = True,
        session_id: str | None = None,
        relevance_buckets: list[dict[str, Any]] | None = None,
        predicates: list[str] | None = None,
        scope: str | None = None,
    ) -> OptimizedContext:
        """Get a goal-driven optimized context window.

        .. deprecated::
            Use :meth:`context` with goals/session_id parameters instead.
            The unified endpoint at POST /memory/context now supports all
            optimization features.

        Unlike context_window() which does flat salience ranking, this uses
        backward chaining to find facts reachable from your goals, deduplicates,
        checks for contradictions, and applies relevance buckets.

        Args:
            goals: List of goal specs, e.g. [{"predicate": "recommend", "args": ["?x"]}].
                   Each can include "negated": True for negative goals.
            max_facts: Maximum facts to return.
            max_facts_per_predicate: Optional diversity cap per predicate.
            auto_resolve_contradictions: If True, auto-resolve by salience.
                If False, keep both sides.
            session_id: Session ID for incremental diffing.
            relevance_buckets: List of buckets, e.g.
                [{"name": "prefs", "predicates": ["likes"], "weight": 3.0}].
            predicates: Filter by these predicates (non-goal mode).
            scope: Scope filter.

        Returns:
            An OptimizedContext with ranked facts, rules, contradictions, and metadata.

        Example::

            ctx = await client.optimize_context(
                goals=[{"predicate": "recommend", "args": ["?product"]}],
                max_facts=30,
                session_id="session_42",
                relevance_buckets=[
                    {"name": "prefs", "predicates": ["likes", "dislikes"], "weight": 3},
                    {"name": "products", "predicates": ["available", "price"], "weight": 5},
                ],
            )
            for entry in ctx.entries:
                print(f"[{entry.salience:.2f}] {entry.predicate}({', '.join(entry.args)})")
        """
        body: dict[str, Any] = {"maxFacts": max_facts}
        if goals is not None:
            body["goals"] = goals
        if max_facts_per_predicate is not None:
            body["maxFactsPerPredicate"] = max_facts_per_predicate
        if not auto_resolve_contradictions:
            body["autoResolveContradictions"] = False
        if session_id is not None:
            body["sessionId"] = session_id
        if relevance_buckets is not None:
            body["relevanceBuckets"] = relevance_buckets
        if predicates is not None:
            body["predicates"] = predicates
        if scope is not None:
            body["scope"] = scope

        result = await self._request("POST", "/context/optimize", json_body=body)
        return OptimizedContext.model_validate(result)

    async def diff_context(
        self,
        session_id: str,
        goals: list[dict[str, Any]] | None = None,
        max_facts: int | None = None,
        relevance_buckets: list[dict[str, Any]] | None = None,
        predicates: list[str] | None = None,
        scope: str | None = None,
    ) -> ContextDiff:
        """Get incremental diff since the last optimized context window.

        Returns only what changed: added facts, removed facts, and count of unchanged.
        Recommends a full refresh when churn exceeds 50%.

        Args:
            session_id: The session ID used in the previous optimize_context call.
            goals: Goal specs (same format as optimize_context).
            max_facts: Maximum facts in the new window.
            relevance_buckets: Relevance buckets.
            predicates: Predicate filter.
            scope: Scope filter.

        Returns:
            A ContextDiff with added/removed entries and refresh recommendation.

        Example::

            diff = await client.diff_context(session_id="session_42")
            print(
                f"Added: {len(diff.added)}, Removed: {len(diff.removed)}, "
                f"Unchanged: {diff.unchanged}"
            )
            if diff.full_refresh_recommended:
                print(f"Full refresh recommended: {diff.reason}")
        """
        body: dict[str, Any] = {"sessionId": session_id}
        if goals is not None:
            body["goals"] = goals
        if max_facts is not None:
            body["maxFacts"] = max_facts
        if relevance_buckets is not None:
            body["relevanceBuckets"] = relevance_buckets
        if predicates is not None:
            body["predicates"] = predicates
        if scope is not None:
            body["scope"] = scope

        result = await self._request("POST", "/context/diff", json_body=body)
        return ContextDiff.model_validate(result)

    async def summarize_context(self, scope: str | None = None) -> ContextSummary:
        """Get a compact summary of the knowledge base.

        Returns predicate counts, contradiction count, top salient facts,
        TTL stats, and the knowledge generation counter.

        Args:
            scope: Optional scope filter.

        Returns:
            A ContextSummary with KB statistics.

        Example::

            summary = await client.summarize_context()
            print(f"Total facts: {summary.total_facts}, Contradictions: {summary.contradictions}")
            print(f"Knowledge generation: {summary.knowledge_generation}")
        """
        body: dict[str, Any] = {}
        if scope is not None:
            body["scope"] = scope

        result = await self._request("POST", "/context/summary", json_body=body)
        return ContextSummary.model_validate(result)

    async def clear_context_session(self, session_id: str) -> str:
        """Clear a context session's snapshot data.

        Args:
            session_id: The session ID to clear.

        Returns:
            Confirmation message.
        """
        body = {"sessionId": session_id}
        result = await self._request("POST", "/context/session/clear", json_body=body)
        if isinstance(result, str):
            return result
        return str(result)

    async def extract_facts(
        self,
        text: str,
        context: str | None = None,
        assert_facts: bool = False,
        extract_rules: bool = False,
        scope: str | None = None,
    ) -> dict[str, Any]:
        """Extract structured facts from raw text using LLM.

        Send free-form text and get structured predicate-argument facts back.
        Optionally auto-assert them into the knowledge base.

        Args:
            text: The raw text to extract facts from.
            context: Optional context hint for the LLM.
            assert_facts: If True, extracted facts are automatically asserted.
            extract_rules: If True, also extract rules from the text.
            scope: Optional scope for asserted facts.

        Returns:
            Dict with 'facts', 'rules', 'asserted', 'provider', 'model' keys.

        Example::

            result = await client.extract_facts(
                text="Alice likes Bob. Bob is a student at MIT.",
                assert_facts=True,
            )
            for fact in result["facts"]:
                print(f"{fact['predicate']}({', '.join(fact['args'])})")
        """
        body: dict[str, Any] = {"text": text}
        if context is not None:
            body["context"] = context
        if assert_facts:
            body["assert"] = True
        if extract_rules:
            body["rules"] = True
        if scope is not None:
            body["scope"] = scope

        return await self._request("POST", "/extract", json_body=body)

    async def ingest_and_optimize(
        self,
        text: str,
        goals: list[dict[str, Any]] | None = None,
        max_facts: int = 50,
        session_id: str | None = None,
        relevance_buckets: list[dict[str, Any]] | None = None,
        scope: str | None = None,
        context_hint: str | None = None,
    ) -> OptimizedContext:
        """Extract facts from text, assert them, then return an optimized context.

        This is the one-call developer workflow: send raw text (conversation
        transcript, document chunk, tool output) and get back a minimal,
        structured, goal-driven context window. This maps to the server's
        ``POST /context/ingest`` endpoint so predicate-style input still works
        when no LLM extractor is configured.

        Args:
            text: Raw text to extract facts from.
            goals: Goal specs for context optimization.
            max_facts: Maximum facts in optimized output.
            session_id: Session ID for diffing.
            relevance_buckets: Relevance buckets for the optimization.
            scope: Scope filter.
            context_hint: Optional hint for the LLM extractor.

        Returns:
            An OptimizedContext after ingestion.

        Example::

            ctx = await client.ingest_and_optimize(
                text=(
                    "The user likes electronics and has a budget of $1000."
                    " Product X costs $899 and has 4.5 stars."
                ),
                goals=[{"predicate": "recommend", "args": ["?product"]}],
                max_facts=20,
                session_id="session_42",
            )
            print(
                f"Ingested and optimized: {ctx.total_facts_included}"
                f" facts from {ctx.total_facts_available}"
            )
        """
        body: dict[str, Any] = {
            "text": text,
            "maxFacts": max_facts,
        }
        if goals is not None:
            body["goals"] = goals
        if session_id is not None:
            body["sessionId"] = session_id
        if relevance_buckets is not None:
            body["relevanceBuckets"] = relevance_buckets
        if scope is not None:
            body["scope"] = scope
        if context_hint is not None:
            body["contextHint"] = context_hint

        result = await self._request("POST", "/context/ingest", json_body=body)
        if not isinstance(result, dict) or "context" not in result:
            raise NocturnusAIAPIError(
                message="Unexpected response from /context/ingest",
                status_code=0,
            )
        return OptimizedContext.model_validate(result["context"])

    async def temporal_query(
        self,
        predicate: str,
        args: list[str],
        timestamp: int,
        scope: str | None = None,
    ) -> list[Atom]:
        """Query facts that were valid at a specific point in time.

        Useful for historical reasoning: "What was true at time T?"

        Args:
            predicate: The predicate to query.
            args: Arguments with ``?``-prefixed variables for wildcards.
            timestamp: Epoch milliseconds representing the point in time.
            scope: Optional scope filter.

        Returns:
            A list of :class:`Atom` objects valid at the given timestamp.

        Example::

            import time
            one_hour_ago = int((time.time() - 3600) * 1000)
            results = await client.temporal_query(
                "location", ["alice", "?where"], timestamp=one_hour_ago,
            )
        """
        body: dict[str, Any] = {
            "predicate": predicate,
            "args": args,
            "timestamp": timestamp,
        }
        if scope is not None:
            body["scope"] = scope

        result = await self._request("POST", "/memory/query/temporal", json_body=body)
        return _parse_atom_list(result)

    async def consolidate(self) -> ConsolidationResult:
        """Run memory consolidation.

        Consolidation detects repeated episodic patterns and compresses
        them into semantic summary facts. Essential for managing memory
        growth in long-running agent sessions.

        Returns:
            A :class:`ConsolidationResult` with details of what was consolidated.

        Example::

            result = await client.consolidate()
            print(f"Consolidated {result.facts_consolidated} patterns")
        """
        result = await self._request("POST", "/memory/consolidate", json_body={})
        return ConsolidationResult.model_validate(result)

    async def decay(self, threshold: float | None = None) -> DecayResult:
        """Run memory decay: expire TTL'd facts and evict low-salience facts.

        Decay removes facts that have exceeded their time-to-live and
        evicts low-salience facts when the knowledge base is over capacity.

        Args:
            threshold: Optional salience threshold below which facts are
                evicted. If ``None``, the server's default is used.

        Returns:
            A :class:`DecayResult` with details of what was removed.

        Example::

            result = await client.decay(threshold=0.05)
            print(f"Expired: {result.expired_count}, Evicted: {result.evicted_count}")
        """
        body: dict[str, Any] = {}
        if threshold is not None:
            body["threshold"] = threshold

        result = await self._request("POST", "/memory/decay", json_body=body)
        return DecayResult.model_validate(result)

    async def set_priority(
        self,
        predicate: str,
        args: list[str],
        priority: float,
        scope: str | None = None,
    ) -> dict[str, Any]:
        """Set the salience priority for a specific fact.

        Priority is a value between 0.0 and 1.0 that influences the
        fact's position in salience-ranked queries and context windows.

        Args:
            predicate: The predicate of the target fact.
            args: Arguments of the target fact.
            priority: Priority value between 0.0 and 1.0.
            scope: Optional scope.

        Returns:
            A dict with the server's response text under the ``"result"`` key.

        Example::

            await client.set_priority("user_goal", ["complete_task"], priority=0.9)
        """
        body: dict[str, Any] = {
            "predicate": predicate,
            "args": args,
            "priority": priority,
        }
        if scope is not None:
            body["scope"] = scope

        result = await self._request("POST", "/memory/priority", json_body=body)
        if isinstance(result, str):
            return {"result": result}
        return result

    # ------------------------------------------------------------------
    # DSL / Execute
    # ------------------------------------------------------------------

    async def execute(self, command: str) -> str:
        """Execute a Logiql DSL command.

        The Logiql DSL allows expressing assertions, queries, and rules
        in a concise text syntax.

        Args:
            command: The Logiql DSL command string.

        Returns:
            The server's text response.

        Example::

            result = await client.execute("ASSERT parent(alice, bob).")
            result = await client.execute("QUERY parent(?x, bob).")
        """
        body = {"command": command}
        result = await self._request("POST", "/execute", json_body=body)
        if isinstance(result, dict) and "result" in result:
            return result["result"]
        return str(result)

    # ------------------------------------------------------------------
    # Schema Discovery
    # ------------------------------------------------------------------

    async def predicates(self, scope: str | None = None) -> dict[str, Any]:
        """Discover the knowledge base schema.

        Lists all predicates (relationship types) currently stored, with
        argument counts and fact counts.

        Args:
            scope: Optional scope filter.

        Returns:
            A dict with ``"predicates"`` (list of predicate info),
            ``"totalPredicates"``, ``"totalFacts"``, and ``"totalRules"``.

        Example::

            schema = await client.predicates()
            for p in schema["predicates"]:
                print(f"{p['predicate']}/{p['arity']} — {p['factCount']} facts")
        """
        params: dict[str, Any] = {}
        if scope is not None:
            params["scope"] = scope

        result = await self._request("GET", "/predicates", params=params)
        if isinstance(result, dict):
            return result
        return {"predicates": []}

    # ------------------------------------------------------------------
    # Transactions
    # ------------------------------------------------------------------

    async def begin_transaction(self) -> str:
        """Begin an ACID transaction.

        Returns:
            The transaction ID (a string or numeric ID).

        Example::

            tx_id = await client.begin_transaction()
            await client.assert_fact("x", ["y"], transaction_id=tx_id)
            await client.commit_transaction(tx_id)
        """
        result = await self._request("POST", "/tx/begin")
        return str(result) if not isinstance(result, str) else result

    async def commit_transaction(self, transaction_id: int | str) -> str:
        """Commit a transaction, making all buffered operations permanent.

        Args:
            transaction_id: The transaction ID from :meth:`begin_transaction`.
        """
        result = await self._request(
            "POST", f"/tx/commit/{transaction_id}", expect_json=False,
        )
        return str(result)

    async def rollback_transaction(self, transaction_id: int | str) -> str:
        """Rollback a transaction, discarding all buffered operations.

        Args:
            transaction_id: The transaction ID from :meth:`begin_transaction`.
        """
        result = await self._request(
            "POST", f"/tx/rollback/{transaction_id}", expect_json=False,
        )
        return str(result)

    # ------------------------------------------------------------------
    # Database Management
    # ------------------------------------------------------------------

    async def create_database(self, name: str | None = None) -> str:
        """Create a database on the server.

        Args:
            name: Database name. Defaults to this client's ``database`` property.

        Returns:
            The server's response text.

        Raises:
            NocturnusAIConflictError: If the database already exists.
        """
        db_name = name or self._database
        result = await self._request(
            "POST", "/admin/databases", json_body={"name": db_name}
        )
        if isinstance(result, dict) and "result" in result:
            return result["result"]
        return str(result)

    async def ensure_database(self, name: str | None = None) -> None:
        """Create the database if it does not already exist.

        This is safe to call unconditionally — it silently succeeds
        when the database is already present.

        Args:
            name: Database name. Defaults to this client's ``database`` property.
        """
        with contextlib.suppress(NocturnusAIConflictError, NocturnusAIAPIError):
            await self.create_database(name)

    # ------------------------------------------------------------------
    # Auth / Key Management
    # ------------------------------------------------------------------

    async def auth_status(self) -> dict[str, Any]:
        """Check the authentication status and mode of the server.

        Returns:
            A dict with ``"authEnabled"``, ``"mode"``, and ``"hasKeys"`` fields.

        Example::

            status = await client.auth_status()
            print(f"Auth mode: {status['mode']}")
        """
        result = await self._request("GET", "/auth/status")
        if isinstance(result, dict):
            return result
        return {"status": result}

    async def bootstrap(
        self,
        name: str = "admin",
        description: str = "Initial admin key",
    ) -> dict[str, Any]:
        """Bootstrap the first admin API key.

        Only works when RBAC auth is enabled and no keys exist yet.

        Args:
            name: Name for the admin key.
            description: Description for the key.

        Returns:
            A dict containing the key ``"id"``, ``"name"``, ``"key"``
            (raw key, shown only once), ``"prefix"``, ``"role"``, etc.

        Example::

            result = await client.bootstrap(name="my-admin")
            print(f"Save this key: {result['key']}")
        """
        body: dict[str, Any] = {"name": name, "description": description}
        return await self._request("POST", "/auth/bootstrap", json_body=body)

    async def create_key(
        self,
        name: str,
        role: str = "writer",
        databases: list[str] | None = None,
        tenants: list[str] | None = None,
        expires_in_days: int | None = None,
        description: str = "",
    ) -> dict[str, Any]:
        """Create a new API key. Requires ADMIN role.

        Args:
            name: Human-readable name for the key.
            role: One of ``"admin"``, ``"writer"``, ``"reader"``.
            databases: Optional list of databases this key can access.
                Empty means all databases.
            tenants: Optional list of tenants this key can access.
                Empty means all tenants.
            expires_in_days: Optional expiration in days.
            description: Optional description.

        Returns:
            A dict containing the key ``"id"``, ``"key"`` (raw key, shown
            only once), ``"prefix"``, ``"role"``, etc.

        Example::

            result = await client.create_key(
                name="agent-writer",
                role="writer",
                databases=["prod"],
            )
            print(f"New key: {result['key']}")
        """
        body: dict[str, Any] = {"name": name, "role": role, "description": description}
        if databases is not None:
            body["databases"] = databases
        if tenants is not None:
            body["tenants"] = tenants
        if expires_in_days is not None:
            body["expiresInDays"] = expires_in_days
        return await self._request("POST", "/auth/keys", json_body=body)

    async def list_keys(self) -> list[dict[str, Any]]:
        """List all API keys. Requires ADMIN role.

        Returns:
            A list of key info dicts (without raw keys or hashes).

        Example::

            keys = await client.list_keys()
            for key in keys:
                print(f"{key['name']} ({key['role']}) — prefix: {key['prefix']}")
        """
        result = await self._request("GET", "/auth/keys")
        if isinstance(result, list):
            return result
        return []

    async def revoke_key(self, key_id: str) -> dict[str, Any]:
        """Revoke (delete) an API key. Requires ADMIN role.

        Args:
            key_id: The ID of the key to revoke.

        Returns:
            A dict with a confirmation message.

        Example::

            await client.revoke_key("some-uuid")
        """
        result = await self._request("DELETE", f"/auth/keys/{key_id}")
        if isinstance(result, dict):
            return result
        return {"result": result}

    async def whoami(self) -> dict[str, Any]:
        """Get information about the currently authenticated key.

        Returns:
            A dict with ``"keyId"``, ``"name"``, ``"role"``,
            ``"permissions"``, ``"databases"``, and ``"tenants"``.

        Example::

            me = await client.whoami()
            print(f"Authenticated as: {me['name']} (role: {me['role']})")
        """
        result = await self._request("GET", "/auth/whoami")
        if isinstance(result, dict):
            return result
        return {"result": result}

    # ------------------------------------------------------------------
    # Observability
    # ------------------------------------------------------------------

    async def health(self) -> dict[str, Any]:
        """Check the health of the NocturnusAI server.

        Returns:
            A dict containing health status information including
            server status, database count, uptime, etc.

        Example::

            health = await client.health()
            print(f"Status: {health['status']}")
        """
        result = await self._request("GET", "/health")
        if isinstance(result, dict):
            return result
        return {"status": result}

    # ------------------------------------------------------------------
    # Lifecycle
    # ------------------------------------------------------------------

    async def close(self) -> None:
        """Close the underlying HTTP client and release resources."""
        if self._client is not None and not self._client.is_closed:
            await self._client.aclose()
            self._client = None

    async def __aenter__(self) -> NocturnusAIClient:
        """Enter the async context manager."""
        await self._ensure_client()
        return self

    async def __aexit__(self, *args: Any) -> None:
        """Exit the async context manager and close the client."""
        await self.close()


class SyncNocturnusAIClient:
    """Synchronous wrapper around :class:`NocturnusAIClient`.

    Provides the same API as the async client but blocks on each call.
    Suitable for scripts, notebooks, and non-async application code.

    Args:
        base_url: The base URL of the NocturnusAI server.
        api_key: Optional API key for authentication.
        database: The database name to use.
        tenant_id: The tenant ID to use.
        max_retries: Maximum number of retry attempts for transient errors.
        timeout: Request timeout in seconds.

    Example::

        with SyncNocturnusAIClient("http://localhost:9300") as client:
            client.assert_fact("parent", ["alice", "bob"])
            results = client.infer("grandparent", ["?who", "charlie"])
    """

    def __init__(
        self,
        base_url: str,
        api_key: str | None = None,
        database: str = "default",
        tenant_id: str = "default",
        max_retries: int = _DEFAULT_MAX_RETRIES,
        timeout: float = _DEFAULT_TIMEOUT,
    ) -> None:
        self._async_client = NocturnusAIClient(
            base_url=base_url,
            api_key=api_key,
            database=database,
            tenant_id=tenant_id,
            max_retries=max_retries,
            timeout=timeout,
        )
        self._loop: asyncio.AbstractEventLoop | None = None

    def _get_loop(self) -> asyncio.AbstractEventLoop:
        """Get or create an event loop for synchronous execution."""
        if self._loop is None or self._loop.is_closed():
            self._loop = asyncio.new_event_loop()
        return self._loop

    def _run(self, coro: Any) -> Any:
        """Run a coroutine synchronously."""
        loop = self._get_loop()
        return loop.run_until_complete(coro)

    @property
    def database(self) -> str:
        """The currently selected database name."""
        return self._async_client.database

    @database.setter
    def database(self, value: str) -> None:
        self._async_client.database = value

    @property
    def tenant_id(self) -> str:
        """The currently selected tenant ID."""
        return self._async_client.tenant_id

    @tenant_id.setter
    def tenant_id(self, value: str) -> None:
        self._async_client.tenant_id = value

    def assert_fact(
        self,
        predicate: str,
        args: list[str],
        scope: str | None = None,
        negated: bool = False,
        ttl: int | None = None,
        valid_from: int | None = None,
        valid_until: int | None = None,
        metadata: dict[str, Any] | None = None,
        transaction_id: int | str | None = None,
    ) -> dict[str, Any]:
        """Assert a fact into the knowledge base. See :meth:`NocturnusAIClient.assert_fact`."""
        return self._run(
            self._async_client.assert_fact(
                predicate=predicate,
                args=args,
                scope=scope,
                negated=negated,
                ttl=ttl,
                valid_from=valid_from,
                valid_until=valid_until,
                metadata=metadata,
                transaction_id=transaction_id,
            )
        )

    def assert_rule(
        self,
        head: dict[str, Any],
        body: list[dict[str, Any]],
        scope: str | None = None,
    ) -> dict[str, Any]:
        """Assert a logical rule. See :meth:`NocturnusAIClient.assert_rule`."""
        return self._run(self._async_client.assert_rule(head=head, body=body, scope=scope))

    def query(
        self,
        predicate: str,
        args: list[str],
        scope: str | None = None,
    ) -> list[Atom]:
        """Query facts matching a pattern. See :meth:`NocturnusAIClient.query`."""
        return self._run(self._async_client.query(predicate=predicate, args=args, scope=scope))

    def infer(
        self,
        predicate: str,
        args: list[str],
        scope: str | None = None,
        with_proof: bool = False,
    ) -> list[Atom] | list[ProofTree]:
        """Run inference. See :meth:`NocturnusAIClient.infer`."""
        return self._run(
            self._async_client.infer(
                predicate=predicate,
                args=args,
                scope=scope,
                with_proof=with_proof,
            )
        )

    def retract(
        self,
        predicate: str,
        args: list[str],
        scope: str | None = None,
        transaction_id: int | str | None = None,
    ) -> dict[str, Any]:
        """Retract a fact. See :meth:`NocturnusAIClient.retract`."""
        return self._run(
            self._async_client.retract(
                predicate=predicate, args=args, scope=scope,
                transaction_id=transaction_id,
            )
        )

    def context_window(
        self,
        max_facts: int = 100,
        min_salience: float = 0.0,
        predicates: list[str] | None = None,
        scope: str | None = None,
    ) -> ContextWindow:
        """Get context window. See :meth:`NocturnusAIClient.context_window`."""
        return self._run(
            self._async_client.context_window(
                max_facts=max_facts,
                min_salience=min_salience,
                predicates=predicates,
                scope=scope,
            )
        )

    def context(
        self,
        *,
        max_facts: int = 100,
        min_salience: float = 0.0,
        predicates: list[str] | None = None,
        scope: str | None = None,
        format: str | None = None,
        include_rules: bool = True,
        goals: list[dict[str, Any]] | None = None,
        session_id: str | None = None,
        auto_resolve_contradictions: bool = True,
        max_facts_per_predicate: int | None = None,
        relevance_buckets: list[dict[str, Any]] | None = None,
    ) -> ContextWindow:
        """Get optimal context. See :meth:`NocturnusAIClient.context`."""
        return self._run(
            self._async_client.context(
                max_facts=max_facts,
                min_salience=min_salience,
                predicates=predicates,
                scope=scope,
                format=format,
                include_rules=include_rules,
                goals=goals,
                session_id=session_id,
                auto_resolve_contradictions=auto_resolve_contradictions,
                max_facts_per_predicate=max_facts_per_predicate,
                relevance_buckets=relevance_buckets,
            )
        )

    def temporal_query(
        self,
        predicate: str,
        args: list[str],
        timestamp: int,
        scope: str | None = None,
    ) -> list[Atom]:
        """Temporal query. See :meth:`NocturnusAIClient.temporal_query`."""
        return self._run(
            self._async_client.temporal_query(
                predicate=predicate,
                args=args,
                timestamp=timestamp,
                scope=scope,
            )
        )

    def consolidate(self) -> ConsolidationResult:
        """Run memory consolidation. See :meth:`NocturnusAIClient.consolidate`."""
        return self._run(self._async_client.consolidate())

    def decay(self, threshold: float | None = None) -> DecayResult:
        """Run memory decay. See :meth:`NocturnusAIClient.decay`."""
        return self._run(self._async_client.decay(threshold=threshold))

    def set_priority(
        self,
        predicate: str,
        args: list[str],
        priority: float,
        scope: str | None = None,
    ) -> dict[str, Any]:
        """Set fact priority. See :meth:`NocturnusAIClient.set_priority`."""
        return self._run(
            self._async_client.set_priority(
                predicate=predicate,
                args=args,
                priority=priority,
                scope=scope,
            )
        )

    def optimize_context(
        self,
        goals: list[dict[str, Any]] | None = None,
        max_facts: int = 100,
        max_facts_per_predicate: int | None = None,
        auto_resolve_contradictions: bool = True,
        session_id: str | None = None,
        relevance_buckets: list[dict[str, Any]] | None = None,
        predicates: list[str] | None = None,
        scope: str | None = None,
    ) -> OptimizedContext:
        """Get goal-driven optimized context. See :meth:`NocturnusAIClient.optimize_context`."""
        return self._run(
            self._async_client.optimize_context(
                goals=goals, max_facts=max_facts,
                max_facts_per_predicate=max_facts_per_predicate,
                auto_resolve_contradictions=auto_resolve_contradictions,
                session_id=session_id, relevance_buckets=relevance_buckets,
                predicates=predicates, scope=scope,
            )
        )

    def diff_context(
        self,
        session_id: str,
        goals: list[dict[str, Any]] | None = None,
        max_facts: int | None = None,
        relevance_buckets: list[dict[str, Any]] | None = None,
        predicates: list[str] | None = None,
        scope: str | None = None,
    ) -> ContextDiff:
        """Get incremental context diff. See :meth:`NocturnusAIClient.diff_context`."""
        return self._run(
            self._async_client.diff_context(
                session_id=session_id, goals=goals, max_facts=max_facts,
                relevance_buckets=relevance_buckets, predicates=predicates, scope=scope,
            )
        )

    def summarize_context(self, scope: str | None = None) -> ContextSummary:
        """Get KB summary. See :meth:`NocturnusAIClient.summarize_context`."""
        return self._run(self._async_client.summarize_context(scope=scope))

    def clear_context_session(self, session_id: str) -> str:
        """Clear context session. See :meth:`NocturnusAIClient.clear_context_session`."""
        return self._run(self._async_client.clear_context_session(session_id=session_id))

    def extract_facts(
        self,
        text: str,
        context: str | None = None,
        assert_facts: bool = False,
        extract_rules: bool = False,
        scope: str | None = None,
    ) -> dict[str, Any]:
        """Extract facts from text. See :meth:`NocturnusAIClient.extract_facts`."""
        return self._run(
            self._async_client.extract_facts(
                text=text, context=context, assert_facts=assert_facts,
                extract_rules=extract_rules, scope=scope,
            )
        )

    def ingest_and_optimize(
        self,
        text: str,
        goals: list[dict[str, Any]] | None = None,
        max_facts: int = 50,
        session_id: str | None = None,
        relevance_buckets: list[dict[str, Any]] | None = None,
        scope: str | None = None,
        context_hint: str | None = None,
    ) -> OptimizedContext:
        """Extract facts from text then optimize.

        See :meth:`NocturnusAIClient.ingest_and_optimize`.
        """
        return self._run(
            self._async_client.ingest_and_optimize(
                text=text, goals=goals, max_facts=max_facts,
                session_id=session_id, relevance_buckets=relevance_buckets,
                scope=scope, context_hint=context_hint,
            )
        )

    def execute(self, command: str) -> str:
        """Execute a Logiql DSL command. See :meth:`NocturnusAIClient.execute`."""
        return self._run(self._async_client.execute(command=command))

    def health(self) -> dict[str, Any]:
        """Check server health. See :meth:`NocturnusAIClient.health`."""
        return self._run(self._async_client.health())

    def predicates(self, scope: str | None = None) -> dict[str, Any]:
        """Discover the KB schema. See :meth:`NocturnusAIClient.predicates`."""
        return self._run(self._async_client.predicates(scope=scope))

    def begin_transaction(self) -> str:
        """Begin a transaction. See :meth:`NocturnusAIClient.begin_transaction`."""
        return self._run(self._async_client.begin_transaction())

    def commit_transaction(self, transaction_id: int | str) -> str:
        """Commit a transaction. See :meth:`NocturnusAIClient.commit_transaction`."""
        return self._run(self._async_client.commit_transaction(transaction_id))

    def rollback_transaction(self, transaction_id: int | str) -> str:
        """Rollback a transaction. See :meth:`NocturnusAIClient.rollback_transaction`."""
        return self._run(self._async_client.rollback_transaction(transaction_id))

    def create_database(self, name: str | None = None) -> str:
        """Create a database. See :meth:`NocturnusAIClient.create_database`."""
        return self._run(self._async_client.create_database(name=name))

    def ensure_database(self, name: str | None = None) -> None:
        """Create DB if missing. See :meth:`NocturnusAIClient.ensure_database`."""
        self._run(self._async_client.ensure_database(name=name))

    def auth_status(self) -> dict[str, Any]:
        """Check auth status. See :meth:`NocturnusAIClient.auth_status`."""
        return self._run(self._async_client.auth_status())

    def bootstrap(
        self, name: str = "admin", description: str = "Initial admin key"
    ) -> dict[str, Any]:
        """Bootstrap first admin key. See :meth:`NocturnusAIClient.bootstrap`."""
        return self._run(self._async_client.bootstrap(name=name, description=description))

    def create_key(
        self,
        name: str,
        role: str = "writer",
        databases: list[str] | None = None,
        tenants: list[str] | None = None,
        expires_in_days: int | None = None,
        description: str = "",
    ) -> dict[str, Any]:
        """Create an API key. See :meth:`NocturnusAIClient.create_key`."""
        return self._run(
            self._async_client.create_key(
                name=name, role=role, databases=databases,
                tenants=tenants, expires_in_days=expires_in_days,
                description=description,
            )
        )

    def list_keys(self) -> list[dict[str, Any]]:
        """List all API keys. See :meth:`NocturnusAIClient.list_keys`."""
        return self._run(self._async_client.list_keys())

    def revoke_key(self, key_id: str) -> dict[str, Any]:
        """Revoke an API key. See :meth:`NocturnusAIClient.revoke_key`."""
        return self._run(self._async_client.revoke_key(key_id=key_id))

    def whoami(self) -> dict[str, Any]:
        """Get current key info. See :meth:`NocturnusAIClient.whoami`."""
        return self._run(self._async_client.whoami())

    def close(self) -> None:
        """Close the underlying client and event loop."""
        if self._loop is not None and not self._loop.is_closed():
            self._loop.run_until_complete(self._async_client.close())
            self._loop.close()
            self._loop = None

    def __enter__(self) -> SyncNocturnusAIClient:
        """Enter the context manager."""
        return self

    def __exit__(self, *args: Any) -> None:
        """Exit the context manager and close the client."""
        self.close()

    def __del__(self) -> None:
        """Best-effort cleanup on garbage collection."""
        with contextlib.suppress(Exception):
            self.close()


# ------------------------------------------------------------------
# Private helpers
# ------------------------------------------------------------------


def _compute_backoff_delay(attempt: int) -> float:
    """Compute exponential backoff delay with jitter.

    Uses the "full jitter" approach: delay = random(0, min(cap, base * 2^attempt)).
    """
    delay = min(
        _DEFAULT_RETRY_MAX_DELAY,
        _DEFAULT_RETRY_BASE_DELAY * (2 ** attempt),
    )
    return random.uniform(0, delay)  # noqa: S311


def _raise_for_status(response: httpx.Response) -> None:
    """Raise an appropriate exception based on the HTTP response status code."""
    status = response.status_code

    # Try to parse a structured error response.
    error_code: str | None = None
    message: str = response.text
    details: dict[str, Any] | None = None

    content_type = response.headers.get("content-type", "")
    if "application/json" in content_type:
        try:
            body = response.json()
            if isinstance(body, dict):
                error_code = body.get("code")
                message = body.get("message", message)
                details = body.get("details")
        except Exception:
            pass

    if status == 400:
        raise NocturnusAIValidationError(message, details=details)
    elif status == 404:
        raise NocturnusAINotFoundError(message)
    elif status == 409:
        raise NocturnusAIConflictError(message)
    else:
        raise NocturnusAIAPIError(
            message=message,
            status_code=status,
            error_code=error_code,
            details=details,
        )


def _parse_atom_list(result: Any) -> list[Atom]:
    """Parse a JSON response into a list of Atom objects."""
    if isinstance(result, list):
        return [Atom.model_validate(item) for item in result]
    if isinstance(result, str):
        # Some endpoints return plain text when no results found.
        return []
    return []


def _parse_proof_tree_list(result: Any) -> list[ProofTree]:
    """Parse a JSON response into a list of ProofTree objects."""
    if isinstance(result, list):
        return [ProofTree.model_validate(item) for item in result]
    return []


def _parse_context_window(result: Any) -> ContextWindow:
    """Parse a JSON response into a ContextWindow, handling nested ScoredAtoms."""
    if isinstance(result, dict):
        # The server returns ScoredAtomResponse objects with flat fields.
        # We need to nest them into ScoredAtom(atom=Atom(...), salience=...).
        raw_facts = result.get("facts", [])
        parsed_facts: list[dict[str, Any]] = []
        for sf in raw_facts:
            if isinstance(sf, dict):
                atom_data = {
                    "predicate": sf.get("predicate", ""),
                    "args": sf.get("args", []),
                    "negated": sf.get("negated", False),
                    "scope": sf.get("scope"),
                    "metadata": sf.get("metadata", {}),
                    "createdAt": sf.get("createdAt"),
                    "validFrom": sf.get("validFrom"),
                    "validUntil": sf.get("validUntil"),
                }
                parsed_facts.append({
                    "atom": atom_data,
                    "salience": sf.get("salience", 0.0),
                })

        data: dict[str, Any] = {
            "facts": parsed_facts,
            "totalAvailable": result.get("totalAvailable", 0),
            "windowSize": result.get("windowSize", 0),
            "predicateDistribution": result.get("predicateDistribution", {}),
            "generatedAt": result.get("generatedAt", 0),
        }
        # Pass through advanced / unified fields when present.
        for key in (
            "formattedText",
            "windowId",
            "rules",
            "contradictionsFound",
            "contradictionsResolved",
            "contradictions",
            "deduplicationSavings",
            "bucketStats",
            "goalDriven",
            "knowledgeGeneration",
        ):
            if key in result:
                data[key] = result[key]

        return ContextWindow.model_validate(data)

    raise NocturnusAIAPIError(
        message="Unexpected response format for context window",
        status_code=0,
    )
