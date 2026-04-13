"""LangChain tool wrappers for NocturnusAI.

Provides LangChain-compatible tools for asserting facts, querying knowledge,
running inference, and retrieving context windows from an NocturnusAI server.

Requires the ``langchain`` extra::

    pip install nocturnusai[langchain]

Usage::

    from nocturnusai import SyncNocturnusAIClient
    from nocturnusai.langchain import get_nocturnusai_tools

    client = SyncNocturnusAIClient("http://localhost:9300")
    tools = get_nocturnusai_tools(client)

    # Use with a LangChain agent
    from langchain.agents import AgentExecutor
    agent = AgentExecutor(agent=..., tools=tools)
"""

from __future__ import annotations

import asyncio
import functools
import json
import logging
from typing import Any

logger = logging.getLogger("nocturnusai.langchain")

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
            "Install it with: pip install nocturnusai[langchain]"
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


def _format_proof_node(node: Any, lines: list[str], indent: int = 2) -> None:
    """Recursively format a ProofNode into human-readable lines."""
    prefix = " " * indent
    goal = node.goal
    neg = "NOT " if goal.negated else ""
    lines.append(f"{prefix}Goal: {neg}{goal.predicate}({', '.join(goal.args)})")

    step = node.step
    if step.type == "fact_match" and step.fact:
        f = step.fact
        lines.append(f"{prefix}  Proved by fact: {f.predicate}({', '.join(f.args)})")
    elif step.type == "rule_application" and step.rule:
        lines.append(f"{prefix}  Applied rule: {step.rule}")
        if step.body_proofs:
            for sub in step.body_proofs:
                _format_proof_node(sub, lines, indent + 4)


# All LangChain-dependent classes are defined inside this block so that the
# module can be safely imported even when langchain_core is not installed.
# The public entry point is get_nocturnusai_tools(), which checks availability.

if _LANGCHAIN_AVAILABLE:


    # ------------------------------------------------------------------
    # Input schemas (Pydantic v2 models for LangChain tool args)
    # ------------------------------------------------------------------

    class AssertFactInput(BaseModel):
        """Input schema for the NocturnusAI assert_fact tool."""

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

    class QueryInput(BaseModel):
        """Input schema for the NocturnusAI query tool."""

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

    class InferInput(BaseModel):
        """Input schema for the NocturnusAI infer tool."""

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

    class ContextInput(BaseModel):
        """Input schema for the NocturnusAI context_window tool."""

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

    class OptimizeInput(BaseModel):
        """Input schema for the NocturnusAI optimize_context tool."""

        goals: str | None = Field(
            default=None,
            description=(
                "JSON array of goal specs for goal-driven context. "
                "Each goal: {\"predicate\": \"name\", \"args\": [\"?x\", \"value\"]}. "
                "Example: '[{\"predicate\": \"recommend\", \"args\": [\"?product\"]}]'"
            ),
        )
        max_facts: int = Field(
            default=50,
            description="Maximum number of facts to return.",
        )
        session_id: str | None = Field(
            default=None,
            description="Session ID for incremental diffing between calls.",
        )
        relevance_buckets: str | None = Field(
            default=None,
            description=(
                "JSON array of relevance buckets. "
                "Each: {\"name\": \"category\", \"predicates\": [\"pred1\"], \"weight\": 3.0}. "
                "Example: '[{\"name\": \"prefs\", \"predicates\": [\"likes\"], \"weight\": 3}]'"
            ),
        )
        scope: str | None = Field(
            default=None,
            description="Optional scope filter.",
        )

    class TeachInput(BaseModel):
        """Input schema for the NocturnusAI teach tool."""

        head: str = Field(
            description=(
                "JSON object with 'predicate' and 'args' for the rule head. "
                "Example: '{\"predicate\": \"mortal\", \"args\": [\"?x\"]}'"
            ),
        )
        body: str = Field(
            description=(
                "JSON array of condition objects for the rule body. "
                "Example: '[{\"predicate\": \"human\", \"args\": [\"?x\"]}]'"
            ),
        )
        scope: str | None = Field(
            default=None,
            description="Optional scope for rule isolation.",
        )

    class ExtractInput(BaseModel):
        """Input schema for the NocturnusAI extract_facts tool."""

        text: str = Field(
            description="The raw text to extract structured facts from.",
        )
        assert_facts: bool = Field(
            default=True,
            description="If true, extracted facts are automatically stored in the knowledge base.",
        )
        scope: str | None = Field(
            default=None,
            description="Optional scope for extracted facts.",
        )

    # ------------------------------------------------------------------
    # Tool definitions
    # ------------------------------------------------------------------

    class NocturnusAIAssertTool(BaseTool):
        """LangChain tool for asserting facts into NocturnusAI.

        Asserts a predicate-argument fact into the knowledge base. Facts are
        the fundamental units of knowledge in NocturnusAI.

        Example invocation by an LLM agent::

            Action: nocturnusai_assert
            Action Input: {"predicate": "parent", "args": "[\"alice\", \"bob\"]"}
        """

        name: str = "nocturnusai_assert"
        description: str = (
            "Assert a fact into the NocturnusAI knowledge base. "
            "Facts are predicate-argument structures representing knowledge. "
            "Use this to store information like 'parent(alice, bob)' or "
            "'likes(alice, pizza)'. Arguments should be a JSON array of strings."
        )
        args_schema: type[BaseModel] = AssertFactInput
        client: Any = None  # SyncNocturnusAIClient, typed as Any for Pydantic compat

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
                return "Error: NocturnusAI client not configured."
            parsed_args = _parse_json_list(args)
            try:
                result = self.client.assert_fact(
                    predicate=predicate,
                    args=parsed_args,
                    scope=scope,
                    negated=negated,
                )
                return str(result.get("result", result))
            except Exception as e:
                return f"Error asserting fact: {e}"

        async def _arun(
            self,
            predicate: str,
            args: str,
            scope: str | None = None,
            negated: bool = False,
        ) -> str:
            """Async execution — runs sync client in a thread executor."""
            loop = asyncio.get_running_loop()
            return await loop.run_in_executor(
                None,
                functools.partial(
                    self._run,
                    predicate=predicate, args=args, scope=scope, negated=negated,
                ),
            )

    class NocturnusAIQueryTool(BaseTool):
        """LangChain tool for querying facts from NocturnusAI.

        Queries the knowledge base for facts matching a pattern. Use
        ``?``-prefixed variables for wildcard positions.

        Example invocation by an LLM agent::

            Action: nocturnusai_query
            Action Input: {"predicate": "parent", "args": "[\"?who\", \"bob\"]"}
        """

        name: str = "nocturnusai_query"
        description: str = (
            "Query facts from the NocturnusAI knowledge base matching a pattern. "
            "Use ?-prefixed variables (like ?x, ?who) for wildcard positions. "
            "Returns all matching facts. Arguments should be a JSON array of strings."
        )
        args_schema: type[BaseModel] = QueryInput
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
                return "Error: NocturnusAI client not configured."
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
            """Async execution — runs sync client in a thread executor."""
            loop = asyncio.get_running_loop()
            return await loop.run_in_executor(
                None,
                functools.partial(
                    self._run, predicate=predicate, args=args, scope=scope,
                ),
            )

    class NocturnusAIInferTool(BaseTool):
        """LangChain tool for running logical inference on NocturnusAI.

        Runs backward-chaining SLD resolution to derive conclusions from
        facts and rules. Unlike query, infer applies rules for multi-step
        deductive reasoning.

        Example invocation by an LLM agent::

            Action: nocturnusai_infer
            Action Input: {"predicate": "grandparent", "args": "[\"?who\", \"charlie\"]"}
        """

        name: str = "nocturnusai_infer"
        description: str = (
            "Run logical inference on the NocturnusAI knowledge base. "
            "Unlike query (which only matches stored facts), infer applies rules "
            "to derive new conclusions through multi-step deductive reasoning. "
            "Use ?-prefixed variables for unknowns. Arguments should be a JSON "
            "array of strings."
        )
        args_schema: type[BaseModel] = InferInput
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
                return "Error: NocturnusAI client not configured."
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
                        _format_proof_node(pt.proof, lines, indent=4)
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
            """Async execution — runs sync client in a thread executor."""
            loop = asyncio.get_running_loop()
            return await loop.run_in_executor(
                None,
                functools.partial(
                    self._run,
                    predicate=predicate, args=args,
                    scope=scope, with_proof=with_proof,
                ),
            )

    class NocturnusAIContextTool(BaseTool):
        """LangChain tool for retrieving the NocturnusAI context window.

        Gets the most salient (relevant) facts for the current reasoning
        context, ranked by a composite score of recency, access frequency,
        and priority.

        Example invocation by an LLM agent::

            Action: nocturnusai_context
            Action Input: {"max_facts": 50, "min_salience": 0.1}
        """

        name: str = "nocturnusai_context"
        description: str = (
            "Get a general overview of the most relevant facts from NocturnusAI, "
            "ranked by salience (recency, frequency, priority). Use this when you "
            "need broad situational awareness without a specific reasoning goal. "
            "For goal-directed reasoning, use nocturnusai_optimize instead."
        )
        args_schema: type[BaseModel] = ContextInput
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
                return "Error: NocturnusAI client not configured."

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
            """Async execution — runs sync client in a thread executor."""
            loop = asyncio.get_running_loop()
            return await loop.run_in_executor(
                None,
                functools.partial(
                    self._run,
                    max_facts=max_facts, min_salience=min_salience,
                    predicates=predicates, scope=scope,
                ),
            )

    class NocturnusAIOptimizeTool(BaseTool):
        """LangChain tool for goal-driven optimized context from NocturnusAI.

        Uses backward chaining to find facts reachable from goals,
        deduplicates, detects contradictions, and applies relevance buckets
        to return the minimal set of facts needed for reasoning.

        Example invocation by an LLM agent::

            Action: nocturnusai_optimize
            Action Input: {
                "goals": "[{\"predicate\": \"recommend\", \"args\": [\"?product\"]}]",
                "max_facts": 30
            }
        """

        name: str = "nocturnusai_optimize"
        description: str = (
            "Get a goal-driven optimized context window from NocturnusAI. "
            "Unlike the basic context tool (which returns all facts ranked by "
            "salience), this uses backward chaining to find only facts reachable "
            "from your goals, deduplicates, and detects contradictions. "
            "Use this when you have specific reasoning goals; use "
            "nocturnusai_context when you want a general overview."
        )
        args_schema: type[BaseModel] = OptimizeInput
        client: Any = None

        model_config = {"arbitrary_types_allowed": True}

        def _run(
            self,
            goals: str | None = None,
            max_facts: int = 50,
            session_id: str | None = None,
            relevance_buckets: str | None = None,
            scope: str | None = None,
        ) -> str:
            """Execute the optimize context tool synchronously."""
            if self.client is None:
                return "Error: NocturnusAI client not configured."

            parsed_goals: list[dict[str, Any]] | None = None
            if goals:
                try:
                    parsed_goals = json.loads(goals)
                except (json.JSONDecodeError, TypeError):
                    return "Error: 'goals' must be a valid JSON array of goal specs."

            parsed_buckets: list[dict[str, Any]] | None = None
            if relevance_buckets:
                try:
                    parsed_buckets = json.loads(relevance_buckets)
                except (json.JSONDecodeError, TypeError):
                    return "Error: 'relevance_buckets' must be a valid JSON array."

            try:
                window = self.client.context(
                    goals=parsed_goals,
                    max_facts=max_facts,
                    session_id=session_id,
                    relevance_buckets=parsed_buckets,
                    scope=scope,
                )
                total = window.total_available
                size = window.window_size
                generation = window.knowledge_generation or 0
                found = window.contradictions_found or 0
                resolved = window.contradictions_resolved or 0
                rules = window.rules or []
                goal_label = "goal-driven" if parsed_goals else "global"

                lines = [
                    f"Optimized Context ({size}/{total} facts, generation={generation}):",
                    f"Goals: [{goal_label}]",
                    f"Contradictions: {found} found, {resolved} resolved",
                    f"Rules: {rules}",
                    "",
                    "Facts:",
                ]
                for scored in window.facts:
                    atom = scored.atom
                    neg = "NOT " if atom.negated else ""
                    lines.append(
                        f"  [salience={scored.salience:.2f}] "
                        f"{neg}{atom.predicate}({', '.join(atom.args)})"
                    )
                return "\n".join(lines)
            except Exception as e:
                return f"Error getting optimized context: {e}"

        async def _arun(
            self,
            goals: str | None = None,
            max_facts: int = 50,
            session_id: str | None = None,
            relevance_buckets: str | None = None,
            scope: str | None = None,
        ) -> str:
            """Async execution — runs sync client in a thread executor."""
            loop = asyncio.get_running_loop()
            return await loop.run_in_executor(
                None,
                functools.partial(
                    self._run,
                    goals=goals, max_facts=max_facts,
                    session_id=session_id,
                    relevance_buckets=relevance_buckets,
                    scope=scope,
                ),
            )

    class NocturnusAITeachTool(BaseTool):
        """LangChain tool for teaching logical rules to NocturnusAI.

        Define Horn clause rules like
        ``grandparent(?x,?z) :- parent(?x,?y), parent(?y,?z)``.
        Rules enable multi-step inference via backward chaining.

        Example invocation by an LLM agent::

            Action: nocturnusai_teach
            Action Input: {
                "head": "{\"predicate\": \"grandparent\", \"args\": [\"?x\", \"?z\"]}",
                "body": "[{\"predicate\": \"parent\", \"args\": [\"?x\", \"?y\"]}, {\"predicate\": \"parent\", \"args\": [\"?y\", \"?z\"]}]"
            }
        """

        name: str = "nocturnusai_teach"
        description: str = (
            "Teach a logical rule to NocturnusAI. Rules are Horn clauses: "
            "if all body conditions are true, the head conclusion is derived. "
            "This enables multi-step deductive reasoning via the infer tool. "
            "Head is a JSON object with 'predicate' and 'args'. Body is a JSON "
            "array of condition objects."
        )
        args_schema: type[BaseModel] = TeachInput
        client: Any = None

        model_config = {"arbitrary_types_allowed": True}

        def _run(
            self,
            head: str,
            body: str,
            scope: str | None = None,
        ) -> str:
            """Execute the teach tool synchronously."""
            if self.client is None:
                return "Error: NocturnusAI client not configured."
            try:
                parsed_head = json.loads(head)
                parsed_body = json.loads(body)
                result = self.client.assert_rule(
                    head=parsed_head,
                    body=parsed_body,
                    scope=scope,
                )
                h = parsed_head
                return (
                    f"Taught rule: {h['predicate']}"
                    f"({', '.join(h['args'])}) :- "
                    f"{', '.join(c['predicate'] + '(' + ', '.join(c['args']) + ')' for c in parsed_body)}"
                )
            except json.JSONDecodeError as e:
                return f"Error: head and body must be valid JSON. {e}"
            except Exception as e:
                return f"Error teaching rule: {e}"

        async def _arun(
            self,
            head: str,
            body: str,
            scope: str | None = None,
        ) -> str:
            """Async execution — runs sync client in a thread executor."""
            loop = asyncio.get_running_loop()
            return await loop.run_in_executor(
                None,
                functools.partial(
                    self._run, head=head, body=body, scope=scope,
                ),
            )

    class NocturnusAIExtractTool(BaseTool):
        """LangChain tool for extracting structured facts from raw text.

        Sends free-form text to NocturnusAI for LLM-powered extraction
        into structured predicate-argument facts. Optionally auto-asserts
        them into the knowledge base.

        Example invocation by an LLM agent::

            Action: nocturnusai_extract
            Action Input: {
                "text": "Alice likes pizza and Bob is her brother.",
                "assert_facts": true
            }
        """

        name: str = "nocturnusai_extract"
        description: str = (
            "Extract structured facts from raw text using LLM-powered extraction. "
            "Send free-form text (conversation transcript, document, tool output) "
            "and get back structured predicate-argument facts. Optionally auto-asserts "
            "them into the knowledge base. NOTE: requires an LLM provider (e.g. "
            "OPENAI_API_KEY) to be configured on the NocturnusAI server."
        )
        args_schema: type[BaseModel] = ExtractInput
        client: Any = None

        model_config = {"arbitrary_types_allowed": True}

        def _run(
            self,
            text: str,
            assert_facts: bool = True,
            scope: str | None = None,
        ) -> str:
            """Execute the extract tool synchronously."""
            if self.client is None:
                return "Error: NocturnusAI client not configured."
            try:
                result = self.client.extract_facts(
                    text=text,
                    assert_facts=assert_facts,
                    scope=scope,
                )
                facts = result.get("facts", [])
                asserted = "yes" if assert_facts else "no"
                lines = [f"Extracted {len(facts)} facts (asserted: {asserted}):"]
                for fact in facts:
                    predicate = fact.get("predicate", "?")
                    args = fact.get("args", [])
                    confidence = fact.get("confidence", 0.0)
                    lines.append(
                        f"  {predicate}({', '.join(args)})  "
                        f"[confidence={confidence:.2f}]"
                    )
                return "\n".join(lines)
            except Exception as e:
                return f"Error extracting facts: {e}"

        async def _arun(
            self,
            text: str,
            assert_facts: bool = True,
            scope: str | None = None,
        ) -> str:
            """Async execution — runs sync client in a thread executor."""
            loop = asyncio.get_running_loop()
            return await loop.run_in_executor(
                None,
                functools.partial(
                    self._run,
                    text=text, assert_facts=assert_facts, scope=scope,
                ),
            )


def get_nocturnusai_tools(client: Any) -> list[Any]:
    """Create and return all NocturnusAI LangChain tools configured with a client.

    This is the recommended way to create NocturnusAI tools for use with
    LangChain agents.

    Args:
        client: A :class:`~nocturnusai.client.SyncNocturnusAIClient` instance.

    Returns:
        A list of seven LangChain tools: assert, query, infer, teach, context,
        optimize, and extract.

    Raises:
        ImportError: If ``langchain-core`` is not installed.

    Example::

        from nocturnusai import SyncNocturnusAIClient
        from nocturnusai.langchain import get_nocturnusai_tools

        client = SyncNocturnusAIClient("http://localhost:9300")
        tools = get_nocturnusai_tools(client)

        # Use with LangChain
        from langchain.agents import AgentExecutor, create_tool_calling_agent
        agent = create_tool_calling_agent(llm, tools, prompt)
        executor = AgentExecutor(agent=agent, tools=tools)
    """
    _check_langchain()
    return [
        NocturnusAIAssertTool(client=client),
        NocturnusAIQueryTool(client=client),
        NocturnusAIInferTool(client=client),
        NocturnusAITeachTool(client=client),
        NocturnusAIContextTool(client=client),
        NocturnusAIOptimizeTool(client=client),
        NocturnusAIExtractTool(client=client),
    ]
