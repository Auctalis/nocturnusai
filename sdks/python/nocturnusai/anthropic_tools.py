"""Anthropic SDK tool definitions for NocturnusAI.

Provides JSON schema tool definitions and a dispatcher for the Anthropic
messages API. No framework dependency required — just JSON schemas.

Usage::

    from nocturnusai import SyncNocturnusAIClient
    from nocturnusai.anthropic_tools import (
        get_nocturnusai_tool_definitions,
        handle_tool_call,
    )

    client = SyncNocturnusAIClient("http://localhost:9300")
    tools = get_nocturnusai_tool_definitions()

    # Pass to anthropic.messages.create(tools=tools, ...)
    # In your tool handling loop:
    result = handle_tool_call(client, tool_name, tool_input)
"""
from __future__ import annotations

import logging
from typing import Any

logger = logging.getLogger("nocturnusai.anthropic_tools")


def get_nocturnusai_tool_definitions() -> list[dict[str, Any]]:
    """Return Anthropic-compatible JSON schema tool definitions.

    These can be passed directly to ``anthropic.messages.create(tools=...)``.

    Returns:
        List of 5 tool definition dicts: tell, ask, teach, forget, context.
    """
    return [
        {
            "name": "nocturnusai_tell",
            "description": (
                "Assert a fact into the NocturnusAI knowledge base. "
                "Facts are predicate-argument structures like 'likes(alice, bob)'. "
                "Use this to store knowledge."
            ),
            "input_schema": {
                "type": "object",
                "properties": {
                    "predicate": {
                        "type": "string",
                        "description": "Predicate name (e.g., 'likes', 'parent').",
                    },
                    "args": {
                        "type": "array",
                        "items": {"type": "string"},
                        "description": "Arguments to the predicate (e.g., ['alice', 'bob']).",
                    },
                    "scope": {
                        "type": "string",
                        "description": "Optional scope for fact isolation.",
                    },
                },
                "required": ["predicate", "args"],
            },
        },
        {
            "name": "nocturnusai_ask",
            "description": (
                "Query NocturnusAI using logical inference. Applies rules for "
                "multi-step deductive reasoning. Use ?-prefixed variables "
                "(like ?x, ?who) for unknowns."
            ),
            "input_schema": {
                "type": "object",
                "properties": {
                    "predicate": {
                        "type": "string",
                        "description": "Predicate to query.",
                    },
                    "args": {
                        "type": "array",
                        "items": {"type": "string"},
                        "description": "Arguments with ?-prefixed variables for unknowns.",
                    },
                    "scope": {
                        "type": "string",
                        "description": "Optional scope filter.",
                    },
                },
                "required": ["predicate", "args"],
            },
        },
        {
            "name": "nocturnusai_teach",
            "description": (
                "Teach a logical rule to NocturnusAI. Rules are Horn clauses: "
                "if all body conditions are true, the head is derived."
            ),
            "input_schema": {
                "type": "object",
                "properties": {
                    "head": {
                        "type": "object",
                        "description": "Rule head with 'predicate' and 'args'.",
                        "properties": {
                            "predicate": {"type": "string"},
                            "args": {"type": "array", "items": {"type": "string"}},
                        },
                        "required": ["predicate", "args"],
                    },
                    "body": {
                        "type": "array",
                        "description": "Array of condition objects.",
                        "items": {
                            "type": "object",
                            "properties": {
                                "predicate": {"type": "string"},
                                "args": {"type": "array", "items": {"type": "string"}},
                            },
                            "required": ["predicate", "args"],
                        },
                    },
                    "scope": {
                        "type": "string",
                        "description": "Optional scope.",
                    },
                },
                "required": ["head", "body"],
            },
        },
        {
            "name": "nocturnusai_forget",
            "description": (
                "Retract a fact from NocturnusAI. Derived knowledge is "
                "automatically retracted via truth maintenance."
            ),
            "input_schema": {
                "type": "object",
                "properties": {
                    "predicate": {
                        "type": "string",
                        "description": "Predicate to retract.",
                    },
                    "args": {
                        "type": "array",
                        "items": {"type": "string"},
                        "description": "Arguments of the fact to retract.",
                    },
                    "scope": {
                        "type": "string",
                        "description": "Optional scope.",
                    },
                },
                "required": ["predicate", "args"],
            },
        },
        {
            "name": "nocturnusai_context",
            "description": (
                "Get the most relevant facts from NocturnusAI ranked by salience "
                "(recency, frequency, priority). Use to populate context."
            ),
            "input_schema": {
                "type": "object",
                "properties": {
                    "max_facts": {
                        "type": "integer",
                        "description": "Max facts to return (default: 50).",
                    },
                    "min_salience": {
                        "type": "number",
                        "description": "Min salience score 0.0-1.0 (default: 0.0).",
                    },
                },
            },
        },
    ]


def handle_tool_call(
    client: Any,
    tool_name: str,
    tool_input: dict[str, Any],
) -> str:
    """Dispatch a tool call to the appropriate NocturnusAI client method.

    Args:
        client: A SyncNocturnusAIClient instance.
        tool_name: The tool name from the ``tool_use`` block.
        tool_input: The input dict from the ``tool_use`` block.

    Returns:
        String result to include in the ``tool_result`` response.
    """
    try:
        if tool_name == "nocturnusai_tell":
            client.assert_fact(
                predicate=tool_input["predicate"],
                args=tool_input["args"],
                scope=tool_input.get("scope"),
            )
            p, a = tool_input["predicate"], tool_input["args"]
            return f"Asserted: {p}({', '.join(a)})"

        elif tool_name == "nocturnusai_ask":
            results = client.infer(
                predicate=tool_input["predicate"],
                args=tool_input["args"],
                scope=tool_input.get("scope"),
            )
            if not results:
                return "No results found."
            lines = [f"Found {len(results)} result(s):"]
            for atom in results:
                neg = "NOT " if atom.negated else ""
                lines.append(f"  {neg}{atom.predicate}({', '.join(atom.args)})")
            return "\n".join(lines)

        elif tool_name == "nocturnusai_teach":
            client.assert_rule(
                head=tool_input["head"],
                body=tool_input["body"],
                scope=tool_input.get("scope"),
            )
            h = tool_input["head"]
            return f"Taught rule: {h['predicate']}({', '.join(h['args'])})"

        elif tool_name == "nocturnusai_forget":
            client.retract(
                predicate=tool_input["predicate"],
                args=tool_input["args"],
                scope=tool_input.get("scope"),
            )
            p, a = tool_input["predicate"], tool_input["args"]
            return f"Retracted: {p}({', '.join(a)})"

        elif tool_name == "nocturnusai_context":
            window = client.context_window(
                max_facts=tool_input.get("max_facts", 50),
                min_salience=tool_input.get("min_salience", 0.0),
            )
            if not window.facts:
                return "Context empty."
            lines = [f"Context ({window.window_size}/{window.total_available}):"]
            for s in window.facts:
                a = s.atom
                lines.append(f"  [{s.salience:.3f}] {a.predicate}({', '.join(a.args)})")
            return "\n".join(lines)

        else:
            return f"Unknown tool: {tool_name}"

    except Exception as e:
        return f"Error handling {tool_name}: {e}"
