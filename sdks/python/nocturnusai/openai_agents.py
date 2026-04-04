"""OpenAI Agents SDK integration for NocturnusAI.

Provides tool functions compatible with the OpenAI Agents SDK @function_tool decorator.

Requires the ``openai-agents`` extra::

    pip install nocturnusai[openai-agents]

Usage::

    from nocturnusai import SyncNocturnusAIClient
    from nocturnusai.openai_agents import get_nocturnusai_tools

    client = SyncNocturnusAIClient("http://localhost:9300")
    tools = get_nocturnusai_tools(client)

    from agents import Agent
    agent = Agent(name="reasoner", tools=tools)
"""
from __future__ import annotations

import json
import logging
from typing import Any

logger = logging.getLogger("nocturnusai.openai_agents")

try:
    from agents import function_tool

    _OPENAI_AGENTS_AVAILABLE = True
except ImportError:
    _OPENAI_AGENTS_AVAILABLE = False


def _check_openai_agents() -> None:
    if not _OPENAI_AGENTS_AVAILABLE:
        raise ImportError(
            "OpenAI Agents SDK integration requires 'openai-agents'. "
            "Install with: pip install nocturnusai[openai-agents]"
        )


def _parse_json_list(value: str) -> list[str]:
    try:
        parsed = json.loads(value)
        if isinstance(parsed, list):
            return [str(item) for item in parsed]
    except (json.JSONDecodeError, TypeError):
        pass
    return [s.strip().strip("\"'") for s in value.split(",") if s.strip()]


def get_nocturnusai_tools(client: Any) -> list[Any]:
    """Create NocturnusAI tool functions for the OpenAI Agents SDK.

    Returns plain Python functions that can be passed directly to Agent(tools=[...]).
    If openai-agents is installed, functions are decorated with @function_tool.

    Args:
        client: A SyncNocturnusAIClient instance.

    Returns:
        List of 5 tool functions.
    """

    def nocturnusai_tell(predicate: str, args: str, scope: str | None = None) -> str:
        """Assert a fact into NocturnusAI's knowledge base.

        Args:
            predicate: The predicate name (e.g., 'likes', 'parent').
            args: JSON array of arguments (e.g., '["alice", "bob"]').
            scope: Optional scope for fact isolation.
        """
        parsed = _parse_json_list(args)
        try:
            client.assert_fact(predicate=predicate, args=parsed, scope=scope)
            return f"Asserted: {predicate}({', '.join(parsed)})"
        except Exception as e:
            return f"Error: {e}"

    def nocturnusai_ask(predicate: str, args: str, scope: str | None = None) -> str:
        """Query NocturnusAI using logical inference.

        Args:
            predicate: The predicate to query.
            args: JSON array with ?-prefixed variables for unknowns (e.g., '["alice", "?who"]').
            scope: Optional scope filter.
        """
        parsed = _parse_json_list(args)
        try:
            results = client.infer(predicate=predicate, args=parsed, scope=scope)
            if not results:
                return "No results found."
            lines = [f"Found {len(results)} result(s):"]
            for atom in results:
                neg = "NOT " if atom.negated else ""
                lines.append(f"  {neg}{atom.predicate}({', '.join(atom.args)})")
            return "\n".join(lines)
        except Exception as e:
            return f"Error: {e}"

    def nocturnusai_teach(head: str, body: str, scope: str | None = None) -> str:
        """Teach a logical rule to NocturnusAI.

        Args:
            head: JSON object with 'predicate' and 'args' for the rule head.
            body: JSON array of condition objects for the rule body.
            scope: Optional scope.
        """
        try:
            client.assert_rule(
                head=json.loads(head), body=json.loads(body), scope=scope,
            )
            return "Rule taught successfully."
        except Exception as e:
            return f"Error: {e}"

    def nocturnusai_forget(predicate: str, args: str, scope: str | None = None) -> str:
        """Retract a fact from NocturnusAI.

        Args:
            predicate: The predicate to retract.
            args: JSON array of arguments.
            scope: Optional scope.
        """
        parsed = _parse_json_list(args)
        try:
            client.retract(predicate=predicate, args=parsed, scope=scope)
            return f"Retracted: {predicate}({', '.join(parsed)})"
        except Exception as e:
            return f"Error: {e}"

    def nocturnusai_context(max_facts: int = 50, min_salience: float = 0.0) -> str:
        """Get the most relevant facts from NocturnusAI.

        Args:
            max_facts: Maximum number of facts to return.
            min_salience: Minimum salience score (0.0-1.0).
        """
        try:
            window = client.context_window(
                max_facts=max_facts, min_salience=min_salience,
            )
            if not window.facts:
                return "Context empty."
            lines = [f"Context ({window.window_size}/{window.total_available}):"]
            for s in window.facts:
                a = s.atom
                lines.append(f"  [{s.salience:.3f}] {a.predicate}({', '.join(a.args)})")
            return "\n".join(lines)
        except Exception as e:
            return f"Error: {e}"

    tools = [nocturnusai_tell, nocturnusai_ask, nocturnusai_teach,
             nocturnusai_forget, nocturnusai_context]

    # Decorate with @function_tool if available
    if _OPENAI_AGENTS_AVAILABLE:
        tools = [function_tool(t) for t in tools]

    return tools
