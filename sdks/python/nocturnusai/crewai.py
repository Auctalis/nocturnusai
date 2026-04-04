"""CrewAI integration for NocturnusAI.

Provides CrewAI-compatible tools and a storage backend for long-term memory.

Requires the ``crewai`` extra::

    pip install nocturnusai[crewai]

Usage::

    from nocturnusai import SyncNocturnusAIClient
    from nocturnusai.crewai import get_nocturnusai_tools, NocturnusAIStorage

    client = SyncNocturnusAIClient("http://localhost:9300")
    tools = get_nocturnusai_tools(client)
"""
from __future__ import annotations

import json
import logging
from typing import Any

logger = logging.getLogger("nocturnusai.crewai")

try:
    from crewai.tools import BaseTool
    from pydantic import BaseModel, Field

    _CREWAI_AVAILABLE = True
except ImportError:
    _CREWAI_AVAILABLE = False


def _check_crewai() -> None:
    """Raise ImportError if crewai is not installed."""
    if not _CREWAI_AVAILABLE:
        raise ImportError(
            "CrewAI integration requires the 'crewai' package. "
            "Install it with: pip install nocturnusai[crewai]"
        )


def _parse_json_list(value: str) -> list[str]:
    """Parse a JSON array string into a list of strings."""
    try:
        parsed = json.loads(value)
        if isinstance(parsed, list):
            return [str(item) for item in parsed]
    except (json.JSONDecodeError, TypeError):
        pass
    return [s.strip().strip("\"'") for s in value.split(",") if s.strip()]


if _CREWAI_AVAILABLE:

    # -- Input Schemas -------------------------------------------------------

    class TellInput(BaseModel):
        """Input for asserting a fact."""
        predicate: str = Field(description="Predicate name (e.g., 'likes', 'parent').")
        args: str = Field(description='JSON array of arguments. Example: \'["alice", "bob"]\'')
        scope: str | None = Field(default=None, description="Optional scope for isolation.")
        negated: bool = Field(default=False, description="Assert the negation of this fact.")

    class AskInput(BaseModel):
        """Input for querying/inferring."""
        predicate: str = Field(description="Predicate to query.")
        args: str = Field(
            description='JSON array. Use ?-prefixed variables for unknowns. '
            'Example: \'["alice", "?who"]\''
        )
        scope: str | None = Field(default=None, description="Optional scope filter.")

    class TeachInput(BaseModel):
        """Input for defining a rule."""
        head: str = Field(
            description='JSON object with "predicate" and "args". '
            'Example: \'{"predicate": "mortal", "args": ["?x"]}\''
        )
        body: str = Field(
            description='JSON array of condition objects. '
            'Example: \'[{"predicate": "human", "args": ["?x"]}]\''
        )
        scope: str | None = Field(default=None, description="Optional scope.")

    class ForgetInput(BaseModel):
        """Input for retracting a fact."""
        predicate: str = Field(description="Predicate to retract.")
        args: str = Field(description='JSON array of arguments.')
        scope: str | None = Field(default=None, description="Optional scope.")

    class ContextInput(BaseModel):
        """Input for context window retrieval."""
        max_facts: int = Field(default=50, description="Max facts to return.")
        min_salience: float = Field(default=0.0, description="Min salience score (0.0-1.0).")
        predicates: str | None = Field(
            default=None, description='Optional JSON array of predicate names to filter.'
        )

    # -- Tools ---------------------------------------------------------------

    class NocturnusAITellTool(BaseTool):
        """Assert a fact into NocturnusAI's knowledge base.

        Store knowledge like 'parent(alice, bob)' or 'likes(alice, pizza)'.
        Facts are the fundamental units of knowledge.
        """
        name: str = "nocturnusai_tell"
        description: str = (
            "Assert a fact into the NocturnusAI knowledge base. "
            "Use to store knowledge like 'likes(alice, bob)'. "
            "Args should be a JSON array of strings."
        )
        args_schema: type[BaseModel] = TellInput
        client: Any = None

        model_config = {"arbitrary_types_allowed": True}

        def _run(
            self,
            predicate: str,
            args: str,
            scope: str | None = None,
            negated: bool = False,
        ) -> str:
            if self.client is None:
                return "Error: NocturnusAI client not configured."
            parsed_args = _parse_json_list(args)
            try:
                self.client.assert_fact(
                    predicate=predicate, args=parsed_args,
                    scope=scope, negated=negated,
                )
                neg = "NOT " if negated else ""
                return f"Asserted: {neg}{predicate}({', '.join(parsed_args)})"
            except Exception as e:
                return f"Error asserting fact: {e}"

    class NocturnusAIAskTool(BaseTool):
        """Query the NocturnusAI knowledge base using logical inference.

        Finds answers by matching facts and applying rules through multi-step
        deductive reasoning. Use ?-prefixed variables for unknowns.
        """
        name: str = "nocturnusai_ask"
        description: str = (
            "Query NocturnusAI using logical inference. "
            "Applies rules for multi-step reasoning. "
            "Use ?-prefixed variables (like ?x, ?who) for unknowns."
        )
        args_schema: type[BaseModel] = AskInput
        client: Any = None

        model_config = {"arbitrary_types_allowed": True}

        def _run(
            self, predicate: str, args: str, scope: str | None = None,
        ) -> str:
            if self.client is None:
                return "Error: NocturnusAI client not configured."
            parsed_args = _parse_json_list(args)
            try:
                results = self.client.infer(
                    predicate=predicate, args=parsed_args, scope=scope,
                )
                if not results:
                    return "No results found."
                lines = [f"Found {len(results)} result(s):"]
                for atom in results:
                    neg = "NOT " if atom.negated else ""
                    lines.append(f"  {neg}{atom.predicate}({', '.join(atom.args)})")
                return "\n".join(lines)
            except Exception as e:
                return f"Error querying: {e}"

    class NocturnusAITeachTool(BaseTool):
        """Teach a logical rule to NocturnusAI.

        Define Horn clause rules like 'grandparent(?x,?z) :- parent(?x,?y), parent(?y,?z)'.
        Rules enable multi-step inference.
        """
        name: str = "nocturnusai_teach"
        description: str = (
            "Teach a logical rule to NocturnusAI. Rules are Horn clauses: "
            "if all body conditions are true, the head conclusion is derived. "
            "Head and body are JSON objects with 'predicate' and 'args'."
        )
        args_schema: type[BaseModel] = TeachInput
        client: Any = None

        model_config = {"arbitrary_types_allowed": True}

        def _run(
            self, head: str, body: str, scope: str | None = None,
        ) -> str:
            if self.client is None:
                return "Error: NocturnusAI client not configured."
            try:
                parsed_head = json.loads(head)
                parsed_body = json.loads(body)
                self.client.assert_rule(
                    head=parsed_head, body=parsed_body, scope=scope,
                )
                h = parsed_head
                return f"Taught rule: {h['predicate']}({', '.join(h['args'])})"
            except Exception as e:
                return f"Error teaching rule: {e}"

    class NocturnusAIForgetTool(BaseTool):
        """Retract a fact from NocturnusAI's knowledge base.

        Removes a specific fact. Derived knowledge is automatically retracted.
        """
        name: str = "nocturnusai_forget"
        description: str = (
            "Retract a fact from NocturnusAI. Derived knowledge is "
            "automatically retracted via truth maintenance."
        )
        args_schema: type[BaseModel] = ForgetInput
        client: Any = None

        model_config = {"arbitrary_types_allowed": True}

        def _run(
            self, predicate: str, args: str, scope: str | None = None,
        ) -> str:
            if self.client is None:
                return "Error: NocturnusAI client not configured."
            parsed_args = _parse_json_list(args)
            try:
                self.client.retract(
                    predicate=predicate, args=parsed_args, scope=scope,
                )
                return f"Retracted: {predicate}({', '.join(parsed_args)})"
            except Exception as e:
                return f"Error retracting: {e}"

    class NocturnusAIContextTool(BaseTool):
        """Get the most relevant facts from NocturnusAI for the current context.

        Returns facts ranked by salience (recency, frequency, priority).
        Use this to populate an agent's context with the most important knowledge.
        """
        name: str = "nocturnusai_context"
        description: str = (
            "Get the most relevant facts from NocturnusAI ranked by salience. "
            "Returns facts scored by recency, frequency, and priority."
        )
        args_schema: type[BaseModel] = ContextInput
        client: Any = None

        model_config = {"arbitrary_types_allowed": True}

        def _run(
            self,
            max_facts: int = 50,
            min_salience: float = 0.0,
            predicates: str | None = None,
        ) -> str:
            if self.client is None:
                return "Error: NocturnusAI client not configured."
            parsed_preds: list[str] | None = None
            if predicates:
                parsed_preds = _parse_json_list(predicates)
            try:
                window = self.client.context_window(
                    max_facts=max_facts,
                    min_salience=min_salience,
                    predicates=parsed_preds,
                )
                if not window.facts:
                    return "Context window empty. No facts match criteria."
                lines = [
                    f"Context ({window.window_size}/{window.total_available} facts):",
                ]
                for scored in window.facts:
                    a = scored.atom
                    neg = "NOT " if a.negated else ""
                    lines.append(
                        f"  [{scored.salience:.3f}] {neg}{a.predicate}({', '.join(a.args)})"
                    )
                return "\n".join(lines)
            except Exception as e:
                return f"Error getting context: {e}"


class NocturnusAIStorage:
    """CrewAI storage backend backed by NocturnusAI.

    Implements the CrewAI StorageBackend protocol (save/search/reset)
    for use as a LongTermMemory backend.

    Usage::

        from nocturnusai import SyncNocturnusAIClient
        from nocturnusai.crewai import NocturnusAIStorage
        from crewai.memory import LongTermMemory

        client = SyncNocturnusAIClient("http://localhost:9300")
        storage = NocturnusAIStorage(client=client)
        crew = Crew(memory=True, long_term_memory=LongTermMemory(storage=storage))
    """

    def __init__(self, client: Any) -> None:
        self._client = client

    def save(
        self,
        value: str,
        metadata: dict[str, Any] | None = None,
        agent: str | None = None,
    ) -> None:
        """Save a memory record as a NocturnusAI fact."""
        args = [value]
        if agent:
            args.insert(0, agent)
        try:
            self._client.assert_fact(
                predicate="crew_memory",
                args=args,
            )
        except Exception:
            logger.exception("Failed to save memory to NocturnusAI")

    def search(self, query: str) -> dict[str, Any]:
        """Search memory by querying NocturnusAI facts."""
        try:
            results = self._client.query(
                predicate="crew_memory",
                args=["?agent", "?value"] if query == "" else ["?x"],
            )
            return {
                "results": [
                    {"value": ", ".join(a.args)} for a in results
                ]
            }
        except Exception:
            logger.exception("Failed to search NocturnusAI memory")
            return {"results": []}

    def reset(self) -> None:
        """Clear all crew memory facts."""
        try:
            results = self._client.query(
                predicate="crew_memory", args=["?x"],
            )
            for atom in results:
                self._client.retract(
                    predicate="crew_memory", args=atom.args,
                )
        except Exception:
            logger.exception("Failed to reset NocturnusAI memory")


def get_nocturnusai_tools(client: Any) -> list[Any]:
    """Create all NocturnusAI CrewAI tools configured with a client.

    Args:
        client: A SyncNocturnusAIClient instance.

    Returns:
        List of 5 CrewAI tools: tell, ask, teach, forget, context.

    Raises:
        ImportError: If crewai is not installed.
    """
    _check_crewai()
    return [
        NocturnusAITellTool(client=client),
        NocturnusAIAskTool(client=client),
        NocturnusAITeachTool(client=client),
        NocturnusAIForgetTool(client=client),
        NocturnusAIContextTool(client=client),
    ]
