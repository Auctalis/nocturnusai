"""Tests for Anthropic tool definitions."""
from __future__ import annotations

from unittest.mock import MagicMock

import pytest


@pytest.fixture
def mock_client() -> MagicMock:
    client = MagicMock(unsafe=True)
    client.assert_fact.return_value = {"status": "ok"}
    client.assert_rule.return_value = {"status": "ok"}
    client.retract.return_value = {"status": "ok"}
    client.infer.return_value = []
    return client


class TestToolDefinitions:
    def test_returns_five_definitions(self) -> None:
        from nocturnusai.anthropic_tools import get_nocturnusai_tool_definitions
        defs = get_nocturnusai_tool_definitions()
        assert len(defs) == 5

    def test_definitions_have_required_fields(self) -> None:
        from nocturnusai.anthropic_tools import get_nocturnusai_tool_definitions
        for tool_def in get_nocturnusai_tool_definitions():
            assert "name" in tool_def
            assert "description" in tool_def
            assert "input_schema" in tool_def
            assert tool_def["input_schema"]["type"] == "object"
            assert "properties" in tool_def["input_schema"]

    def test_tool_names_are_unique(self) -> None:
        from nocturnusai.anthropic_tools import get_nocturnusai_tool_definitions
        names = [d["name"] for d in get_nocturnusai_tool_definitions()]
        assert len(names) == len(set(names))


class TestToolDispatcher:
    def test_handle_tell(self, mock_client: MagicMock) -> None:
        from nocturnusai.anthropic_tools import handle_tool_call
        result = handle_tool_call(
            mock_client, "nocturnusai_tell",
            {"predicate": "likes", "args": ["alice", "bob"]},
        )
        mock_client.assert_fact.assert_called_once()
        assert isinstance(result, str)

    def test_handle_unknown_tool(self, mock_client: MagicMock) -> None:
        from nocturnusai.anthropic_tools import handle_tool_call
        result = handle_tool_call(mock_client, "unknown_tool", {})
        assert "unknown" in result.lower() or "error" in result.lower()
