"""Tests for OpenAI Agents SDK integration."""
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


class TestOpenAIAgentsImportGuard:
    def test_check_raises_without_package(self) -> None:
        from nocturnusai.openai_agents import _OPENAI_AGENTS_AVAILABLE, _check_openai_agents
        if not _OPENAI_AGENTS_AVAILABLE:
            with pytest.raises(ImportError, match="pip install nocturnusai\\[openai-agents\\]"):
                _check_openai_agents()


class TestOpenAIAgentsTools:
    def test_get_tools_returns_five(self, mock_client: MagicMock) -> None:
        from nocturnusai.openai_agents import get_nocturnusai_tools
        tools = get_nocturnusai_tools(mock_client)
        assert len(tools) == 5

    def test_tell_function(self, mock_client: MagicMock) -> None:
        from nocturnusai.openai_agents import get_nocturnusai_tools
        tools = get_nocturnusai_tools(mock_client)
        tell = tools[0]  # tell is first
        result = tell(predicate="likes", args='["alice", "bob"]')
        mock_client.assert_fact.assert_called_once()
        assert "assert" in result.lower() or "likes" in result.lower()
