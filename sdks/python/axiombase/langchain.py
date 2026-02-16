"""LangChain tool wrappers for AxiomBase.

Provides LangChain-compatible tools for asserting facts, querying knowledge,
running inference, and retrieving context windows from an AxiomBase server.

Requires the ``langchain`` extra::

    pip install axiombase[langchain]

Usage::

    from axiombase import SyncAxiomBaseClient
    from axiombase.langchain import get_axiombase_tools

    client = SyncAxiomBaseClient("http://localhost:9300")
    tools = get_axiombase_tools(client)

    # Use with a LangChain agent
    from langchain.agents import AgentExecutor
    agent = AgentExecutor(agent=..., tools=tools)
"""

from __future__ import annotations

import json
import logging
from typing import Any

logger = logging.getLogger("axiombase.langchain")

try:
    from langchain_core.tools import BaseTool
    from pydantic import BaseModel, Field

    _LANGCHAIN_AVAILABLE = True
except ImportError:
    _LANGCHAIN_AVAILABLE = False


def _check_langchain() -> None:
    """Raise an import error if langchain_core is not installed."""
    if not _LANGCHAIN_AVAILABLE:
        raise ImportError(
            "LangChain integration requires the 'langchain-core' package. "
            "Install it with: pip install axiombase[langchain]"
        )


def _parse_json_list(value: str) -> list[str]:
    """Parse a JSON array string into a Python list of strings."""
    try:
        parsed = json.loads(value)
        if isinstance(parsed, list):
            return [str(item) for item in parsed]
    except (json.JSONDecodeError, TypeError):
        pass
    # Fallback: try comma-separated.
    return [s.strip().strip("\"'") for s in value.split(",") if s.strip()]


# All LangChain-dependent classes are defined inside this block so that the
# module can be safely imported even when langchain_core is not installed.
# The public entry point is get_axiombase_tools(), which checks availability.

if _LANGCHAIN_AVAILABLE:
    from typing import Type

    from axiombase.client import SyncAxiomBaseClient as _SyncClient

    # ------------------------------------------------------------------
    # Input schemas (Pydantic v2 models for LangChain tool args)
    # ------------------------------------------------------------------

    class AssertFactInput(BaseModel):  # type: ignore[misc]
        """Input schema for the AxiomBase assert_fact tool."""

        predicate: str = Field(description="The predicate name (e.g., 'parent', 'likes').")
        args: str = Field(
            description=(
                "JSON array of arguments as strings. "
                "Example: '[\"alice\", \"bob\"]'"
            ),
        )
        scope: str | None = Field(
            default=None,
            description="Optional scope for fact isolation.",
        )
        negated: bool = Field(
            default=False,
            description="Set true to assert the negation of this fact.",
        )

    class QueryInput(BaseModel):  # type: ignore[misc]
        """Input schema for the AxiomBase query tool."""

        predicate: str = Field(description="The predicate to query.")
        args: str = Field(
            description=(
                "JSON array of arguments. Use ?-prefixed strings for variables. "
                "Example: '[\"?x\", \"bob\"]'"
            ),
        )
        scope: str | None = Field(
            default=None,
            description="Optional scope filter.",
        )

    class InferInput(BaseModel):  # type: ignore[misc]
        """Input schema for the AxiomBase infer tool."""

        predicate: str = Field(description="The goal predicate to prove.")
        args: str = Field(
            description=(
                "JSON array of goal arguments. Use ?-prefixed strings for variables. "
                "Example: '[\"?who\", \"charlie\"]'"
            ),
        )
        scope: str | None = Field(
            default=None,
            description="Optional scope filter.",
        )
        with_proof: bool = Field(
            default=False,
            description="If true, include full proof trees showing the derivation chain.",
        )

    class ContextInput(BaseModel):  # type: ignore[misc]
        """Input schema for the AxiomBase context_window tool."""

        max_facts: int = Field(
            default=100,
            description="Maximum number of facts to return.",
        )
        min_salience: float = Field(
            default=0.0,
            description="Minimum salience score (0.0 to 1.0).",
        )
        predicates: str | None = Field(
            default=None,
            description=(
                "Optional JSON array of predicate names to filter by. "
                "Example: '[\"parent\", \"likes\"]'"
            ),
        )
        scope: str | None = Field(
            default=None,
            description="Optional scope filter.",
        )

    # ------------------------------------------------------------------
    # Tool definitions
    # ------------------------------------------------------------------

    class AxiomBaseAssertTool(BaseTool):  # type: ignore[misc]
        """LangChain tool for asserting facts into AxiomBase.

        Asserts a predicate-argument fact into the knowledge base. Facts are
        the fundamental units of knowledge in AxiomBase.

        Example invocation by an LLM agent::

            Action: axiombase_assert
            Action Input: {"predicate": "parent", "args": "[\"alice\", \"bob\"]"}
        """

        name: str = "axiombase_assert"
        description: str = (
            "Assert a fact into the AxiomBase knowledge base. "
            "Facts are predicate-argument structures representing knowledge. "
            "Use this to store information like 'parent(alice, bob)' or "
            "'likes(alice, pizza)'. Arguments should be a JSON array of strings."
        )
        args_schema: Type[BaseModel] = AssertFactInput  # type: ignore[assignment]
        client: Any = None  # SyncAxiomBaseClient, typed as Any for Pydantic compat

        model_config = {"arbitrary_types_allowed": True}

        def _run(
            self,
            predicate: str,
            args: str,
            scope: str | None = None,
            negated: bool = False,
        ) -> str:
            """Execute the assert tool synchronously."""
            if self.client is None:
                return "Error: AxiomBase client not configured."
            parsed_args = _parse_json_list(args)
            try:
                result = self.client.assert_fact(
                    predicate=predicate,
                    args=parsed_args,
                    scope=scope,
                    negated=negated,
                )
                return result.get("result", str(result))
            except Exception as e:
                return f"Error asserting fact: {e}"

        async def _arun(
            self,
            predicate: str,
            args: str,
            scope: str | None = None,
            negated: bool = False,
        ) -> str:
            """Async execution delegates to sync."""
            return self._run(
                predicate=predicate, args=args, scope=scope, negated=negated,
            )

    class AxiomBaseQueryTool(BaseTool):  # type: ignore[misc]
        """LangChain tool for querying facts from AxiomBase.

        Queries the knowledge base for facts matching a pattern. Use
        ``?``-prefixed variables for wildcard positions.

        Example invocation by an LLM agent::

            Action: axiombase_query
            Action Input: {"predicate": "parent", "args": "[\"?who\", \"bob\"]"}
        """

        name: str = "axiombase_query"
        description: str = (
            "Query facts from the AxiomBase knowledge base matching a pattern. "
            "Use ?-prefixed variables (like ?x, ?who) for wildcard positions. "
            "Returns all matching facts. Arguments should be a JSON array of strings."
        )
        args_schema: Type[BaseModel] = QueryInput  # type: ignore[assignment]
        client: Any = None

        model_config = {"arbitrary_types_allowed": True}

        def _run(
            self,
            predicate: str,
            args: str,
            scope: str | None = None,
        ) -> str:
            """Execute the query tool synchronously."""
            if self.client is None:
                return "Error: AxiomBase client not configured."
            parsed_args = _parse_json_list(args)
            try:
                results = self.client.query(
                    predicate=predicate,
                    args=parsed_args,
                    scope=scope,
                )
                if not results:
                    return "No matching facts found."
                lines = [f"Found {len(results)} matching fact(s):"]
                for atom in results:
                    neg = "NOT " if atom.negated else ""
                    lines.append(f"  {neg}{atom.predicate}({', '.join(atom.args)})")
                return "\n".join(lines)
            except Exception as e:
                return f"Error querying facts: {e}"

        async def _arun(
            self,
            predicate: str,
            args: str,
            scope: str | None = None,
        ) -> str:
            """Async execution delegates to sync."""
            return self._run(predicate=predicate, args=args, scope=scope)

    class AxiomBaseInferTool(BaseTool):  # type: ignore[misc]
        """LangChain tool for running logical inference on AxiomBase.

        Runs backward-chaining SLD resolution to derive conclusions from
        facts and rules. Unlike query, infer applies rules for multi-step
        deductive reasoning.

        Example invocation by an LLM agent::

            Action: axiombase_infer
            Action Input: {"predicate": "grandparent", "args": "[\"?who\", \"charlie\"]"}
        """

        name: str = "axiombase_infer"
        description: str = (
            "Run logical inference on the AxiomBase knowledge base. "
            "Unlike query (which only matches stored facts), infer applies rules "
            "to derive new conclusions through multi-step deductive reasoning. "
            "Use ?-prefixed variables for unknowns. Arguments should be a JSON "
            "array of strings."
        )
        args_schema: Type[BaseModel] = InferInput  # type: ignore[assignment]
        client: Any = None

        model_config = {"arbitrary_types_allowed": True}

        def _run(
            self,
            predicate: str,
            args: str,
            scope: str | None = None,
            with_proof: bool = False,
        ) -> str:
            """Execute the infer tool synchronously."""
            if self.client is None:
                return "Error: AxiomBase client not configured."
            parsed_args = _parse_json_list(args)
            try:
                results = self.client.infer(
                    predicate=predicate,
                    args=parsed_args,
                    scope=scope,
                    with_proof=with_proof,
                )
                if not results:
                    return "No results could be inferred."
                if with_proof:
                    lines = [f"Inferred {len(results)} result(s) with proofs:"]
                    for pt in results:
                        lines.append(
                            f"  Result: {pt.result.predicate}"
                            f"({', '.join(pt.result.args)})"
                        )
                    return "\n".join(lines)
                else:
                    lines = [f"Inferred {len(results)} result(s):"]
                    for atom in results:
                        neg = "NOT " if atom.negated else ""
                        lines.append(
                            f"  {neg}{atom.predicate}({', '.join(atom.args)})"
                        )
                    return "\n".join(lines)
            except Exception as e:
                return f"Error running inference: {e}"

        async def _arun(
            self,
            predicate: str,
            args: str,
            scope: str | None = None,
            with_proof: bool = False,
        ) -> str:
            """Async execution delegates to sync."""
            return self._run(
                predicate=predicate,
                args=args,
                scope=scope,
                with_proof=with_proof,
            )

    class AxiomBaseContextTool(BaseTool):  # type: ignore[misc]
        """LangChain tool for retrieving the AxiomBase context window.

        Gets the most salient (relevant) facts for the current reasoning
        context, ranked by a composite score of recency, access frequency,
        and priority.

        Example invocation by an LLM agent::

            Action: axiombase_context
            Action Input: {"max_facts": 50, "min_salience": 0.1}
        """

        name: str = "axiombase_context"
        description: str = (
            "Get the most relevant facts from AxiomBase for the current reasoning "
            "context. Returns facts ranked by salience (a composite of recency, "
            "access frequency, and priority). Use this to efficiently load your "
            "context with the most important knowledge. Optionally filter by "
            "predicate names and minimum salience."
        )
        args_schema: Type[BaseModel] = ContextInput  # type: ignore[assignment]
        client: Any = None

        model_config = {"arbitrary_types_allowed": True}

        def _run(
            self,
            max_facts: int = 100,
            min_salience: float = 0.0,
            predicates: str | None = None,
            scope: str | None = None,
        ) -> str:
            """Execute the context window tool synchronously."""
            if self.client is None:
                return "Error: AxiomBase client not configured."

            parsed_predicates: list[str] | None = None
            if predicates:
                parsed_predicates = _parse_json_list(predicates)

            try:
                window = self.client.context_window(
                    max_facts=max_facts,
                    min_salience=min_salience,
                    predicates=parsed_predicates,
                    scope=scope,
                )
                if not window.facts:
                    return "Context window is empty. No facts match the criteria."
                lines = [
                    f"Context Window "
                    f"({window.window_size}/{window.total_available} facts):",
                    f"Predicates: {window.predicate_distribution}",
                    "",
                ]
                for scored in window.facts:
                    atom = scored.atom
                    neg = "NOT " if atom.negated else ""
                    lines.append(
                        f"  [salience={scored.salience:.3f}] "
                        f"{neg}{atom.predicate}({', '.join(atom.args)})"
                    )
                return "\n".join(lines)
            except Exception as e:
                return f"Error getting context window: {e}"

        async def _arun(
            self,
            max_facts: int = 100,
            min_salience: float = 0.0,
            predicates: str | None = None,
            scope: str | None = None,
        ) -> str:
            """Async execution delegates to sync."""
            return self._run(
                max_facts=max_facts,
                min_salience=min_salience,
                predicates=predicates,
                scope=scope,
            )


def get_axiombase_tools(client: Any) -> list[Any]:
    """Create and return all AxiomBase LangChain tools configured with a client.

    This is the recommended way to create AxiomBase tools for use with
    LangChain agents.

    Args:
        client: A :class:`~axiombase.client.SyncAxiomBaseClient` instance.

    Returns:
        A list of four LangChain tools: assert, query, infer, and context.

    Raises:
        ImportError: If ``langchain-core`` is not installed.

    Example::

        from axiombase import SyncAxiomBaseClient
        from axiombase.langchain import get_axiombase_tools

        client = SyncAxiomBaseClient("http://localhost:9300")
        tools = get_axiombase_tools(client)

        # Use with LangChain
        from langchain.agents import AgentExecutor, create_tool_calling_agent
        agent = create_tool_calling_agent(llm, tools, prompt)
        executor = AgentExecutor(agent=agent, tools=tools)
    """
    _check_langchain()
    return [
        AxiomBaseAssertTool(client=client),
        AxiomBaseQueryTool(client=client),
        AxiomBaseInferTool(client=client),
        AxiomBaseContextTool(client=client),
    ]
