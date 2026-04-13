"""AutoGen integration for NocturnusAI.

Provides tool functions and a Memory implementation for AutoGen agents.

Requires the ``autogen`` extra::

    pip install nocturnusai[autogen]

Usage::

    from nocturnusai import SyncNocturnusAIClient
    from nocturnusai.autogen import get_nocturnusai_tools, NocturnusAIMemory

    client = SyncNocturnusAIClient("http://localhost:9300")
    tools = get_nocturnusai_tools(client)
"""
from __future__ import annotations

import json
import logging
from typing import Any

logger = logging.getLogger("nocturnusai.autogen")

try:
    from autogen_agentchat.tools import FunctionTool  # noqa: F401

    _AUTOGEN_AVAILABLE = True
except ImportError:
    _AUTOGEN_AVAILABLE = False


def _check_autogen() -> None:
    """Raise ImportError if autogen-agentchat is not installed."""
    if not _AUTOGEN_AVAILABLE:
        raise ImportError(
            "AutoGen integration requires 'autogen-agentchat'. "
            "Install with: pip install nocturnusai[autogen]"
        )


def _parse_json_list(value: str) -> list[str]:
    try:
        parsed = json.loads(value)
        if isinstance(parsed, list):
            return [str(item) for item in parsed]
    except (json.JSONDecodeError, TypeError):
        pass
    return [s.strip().strip("\"'") for s in value.split(",") if s.strip()]


def _make_tools(client: Any) -> list[Any]:
    """Create plain Python functions that wrap NocturnusAI client calls."""

    def nocturnusai_tell(predicate: str, args: str, scope: str | None = None) -> str:
        """Assert a fact into NocturnusAI. Args is a JSON array like '["alice", "bob"]'."""
        parsed = _parse_json_list(args)
        try:
            client.assert_fact(predicate=predicate, args=parsed, scope=scope)
            return f"Asserted: {predicate}({', '.join(parsed)})"
        except Exception as e:
            return f"Error: {e}"

    def nocturnusai_ask(predicate: str, args: str, scope: str | None = None) -> str:
        """Query NocturnusAI using inference. Use ?-prefixed variables for unknowns."""
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
        """Teach a logical rule. Head and body are JSON objects."""
        try:
            client.assert_rule(head=json.loads(head), body=json.loads(body), scope=scope)
            return "Rule taught successfully."
        except Exception as e:
            return f"Error: {e}"

    def nocturnusai_forget(predicate: str, args: str, scope: str | None = None) -> str:
        """Retract a fact from NocturnusAI."""
        parsed = _parse_json_list(args)
        try:
            client.retract(predicate=predicate, args=parsed, scope=scope)
            return f"Retracted: {predicate}({', '.join(parsed)})"
        except Exception as e:
            return f"Error: {e}"

    def nocturnusai_context(max_facts: int = 50, min_salience: float = 0.0) -> str:
        """Get the most relevant facts ranked by salience."""
        try:
            window = client.context_window(max_facts=max_facts, min_salience=min_salience)
            if not window.facts:
                return "Context empty."
            lines = [f"Context ({window.window_size}/{window.total_available}):"]
            for s in window.facts:
                a = s.atom
                lines.append(f"  [{s.salience:.3f}] {a.predicate}({', '.join(a.args)})")
            return "\n".join(lines)
        except Exception as e:
            return f"Error: {e}"

    return [nocturnusai_tell, nocturnusai_ask, nocturnusai_teach,
            nocturnusai_forget, nocturnusai_context]


class NocturnusAIMemory:
    """AutoGen Memory protocol backed by NocturnusAI.

    Implements add/query/update_context/clear/close for AutoGen agents.

    Accepts either a ``NocturnusAIClient`` (async, recommended) or a
    ``SyncNocturnusAIClient`` (sync — will be run in a thread executor
    to avoid blocking the event loop).

    Example::

        from nocturnusai import NocturnusAIClient
        from nocturnusai.autogen import NocturnusAIMemory

        client = NocturnusAIClient("http://localhost:9300")
        memory = NocturnusAIMemory(client)
    """

    def __init__(self, client: Any) -> None:
        self._client = client
        # Detect whether we got an async or sync client.
        self._is_async = hasattr(client, "__aenter__")

    async def _assert_fact(self, predicate: str, args: list[str]) -> Any:
        if self._is_async:
            return await self._client.assert_fact(predicate=predicate, args=args)
        return self._client.assert_fact(predicate=predicate, args=args)

    async def _query(self, predicate: str, args: list[str]) -> Any:
        if self._is_async:
            return await self._client.query(predicate=predicate, args=args)
        return self._client.query(predicate=predicate, args=args)

    async def _retract(self, predicate: str, args: list[str]) -> Any:
        if self._is_async:
            return await self._client.retract(predicate=predicate, args=args)
        return self._client.retract(predicate=predicate, args=args)

    async def _context(self, **kwargs: Any) -> Any:
        if self._is_async:
            return await self._client.context(**kwargs)
        return self._client.context(**kwargs)

    async def add(self, messages: list[Any]) -> None:
        """Store messages as NocturnusAI facts."""
        for msg in messages:
            content = msg.get("content", str(msg)) if isinstance(msg, dict) else str(msg)
            try:
                await self._assert_fact(
                    predicate="agent_memory", args=[content],
                )
            except Exception:
                logger.exception("Failed to add memory")

    async def query(self, query: str, limit: int = 10) -> list[Any]:
        """Query memory for relevant items."""
        try:
            results = await self._query(
                predicate="agent_memory", args=["?content"],
            )
            matched = [
                {"content": ", ".join(a.args)}
                for a in results
                if query.lower() in ", ".join(a.args).lower()
            ]
            return matched[:limit]
        except Exception:
            logger.exception("Failed to query memory")
            return []

    async def update_context(self, context: dict[str, Any]) -> None:
        """Inject top salient facts into context."""
        try:
            window = await self._context(max_facts=20, min_salience=0.1)
            facts = [
                f"{s.atom.predicate}({', '.join(s.atom.args)})"
                for s in window.facts
            ]
            context["nocturnusai_memory"] = facts
        except Exception:
            logger.exception("Failed to update context")

    async def clear(self) -> None:
        """Clear all agent memory facts."""
        try:
            results = await self._query(
                predicate="agent_memory", args=["?x"],
            )
            for atom in results:
                await self._retract(predicate="agent_memory", args=atom.args)
        except Exception:
            logger.exception("Failed to clear memory")

    async def close(self) -> None:
        """No-op — NocturnusAI client is stateless HTTP."""
        pass


def get_nocturnusai_tools(client: Any) -> list[Any]:
    """Create NocturnusAI tool functions for AutoGen.

    Returns plain Python functions (not FunctionTool instances) so they
    work with or without autogen-agentchat installed.

    Args:
        client: A SyncNocturnusAIClient instance.

    Returns:
        List of 5 tool functions: tell, ask, teach, forget, context.
    """
    return _make_tools(client)
