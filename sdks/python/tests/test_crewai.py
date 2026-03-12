"""Tests for CrewAI integration."""
from __future__ import annotations

import json
from unittest.mock import MagicMock, patch

import pytest


@pytest.fixture
def mock_client() -> MagicMock:
    """Create a mock SyncNocturnusAIClient."""
    client = MagicMock(unsafe=True)
    client.assert_fact.return_value = {"status": "ok"}
    client.assert_rule.return_value = {"status": "ok"}
    client.retract.return_value = {"status": "ok"}
    client.infer.return_value = []
    client.query.return_value = []
    return client


class TestCrewAIImportGuard:
    """Test that the module handles missing crewai gracefully."""

    def test_check_crewai_raises_without_package(self) -> None:
        """_check_crewai raises ImportError when crewai is not installed."""
        from nocturnusai.crewai import _check_crewai, _CREWAI_AVAILABLE

        if not _CREWAI_AVAILABLE:
            with pytest.raises(ImportError, match="pip install nocturnusai\\[crewai\\]"):
                _check_crewai()


class TestCrewAITools:
    """Test CrewAI tool wrappers."""

    def test_get_tools_returns_five(self, mock_client: MagicMock) -> None:
        """get_nocturnusai_tools returns 5 tools."""
        pytest.importorskip("crewai")
        from nocturnusai.crewai import get_nocturnusai_tools

        tools = get_nocturnusai_tools(mock_client)
        assert len(tools) == 5

    def test_tell_tool_calls_assert_fact(self, mock_client: MagicMock) -> None:
        """TellTool delegates to client.assert_fact."""
        pytest.importorskip("crewai")
        from nocturnusai.crewai import get_nocturnusai_tools

        tools = get_nocturnusai_tools(mock_client)
        tell_tool = [t for t in tools if "tell" in t.name.lower()][0]
        result = tell_tool._run(predicate="likes", args='["alice", "bob"]')
        mock_client.assert_fact.assert_called_once_with(
            predicate="likes", args=["alice", "bob"], scope=None, negated=False,
        )
        assert "ok" in result.lower() or "assert" in result.lower()

    def test_ask_tool_calls_infer(self, mock_client: MagicMock) -> None:
        """AskTool delegates to client.infer."""
        pytest.importorskip("crewai")
        from nocturnusai.crewai import get_nocturnusai_tools

        tools = get_nocturnusai_tools(mock_client)
        ask_tool = [t for t in tools if "ask" in t.name.lower()][0]
        ask_tool._run(predicate="likes", args='["alice", "?who"]')
        mock_client.infer.assert_called_once()

    def test_teach_tool_calls_assert_rule(self, mock_client: MagicMock) -> None:
        """TeachTool delegates to client.assert_rule."""
        pytest.importorskip("crewai")
        from nocturnusai.crewai import get_nocturnusai_tools

        tools = get_nocturnusai_tools(mock_client)
        teach_tool = [t for t in tools if "teach" in t.name.lower()][0]
        head = '{"predicate": "mortal", "args": ["?x"]}'
        body = '[{"predicate": "human", "args": ["?x"]}]'
        teach_tool._run(head=head, body=body)
        mock_client.assert_rule.assert_called_once()

    def test_forget_tool_calls_retract(self, mock_client: MagicMock) -> None:
        """ForgetTool delegates to client.retract."""
        pytest.importorskip("crewai")
        from nocturnusai.crewai import get_nocturnusai_tools

        tools = get_nocturnusai_tools(mock_client)
        forget_tool = [t for t in tools if "forget" in t.name.lower()][0]
        forget_tool._run(predicate="likes", args='["alice", "bob"]')
        mock_client.retract.assert_called_once()

    def test_context_tool_calls_context_window(self, mock_client: MagicMock) -> None:
        """ContextTool delegates to client.context_window."""
        pytest.importorskip("crewai")
        from nocturnusai.crewai import get_nocturnusai_tools
        from nocturnusai.models import ContextWindow

        mock_client.context_window.return_value = ContextWindow(
            facts=[], totalAvailable=0, windowSize=0,
            predicateDistribution={}, generatedAt=0,
        )
        tools = get_nocturnusai_tools(mock_client)
        ctx_tool = [t for t in tools if "context" in t.name.lower()][0]
        ctx_tool._run()
        mock_client.context_window.assert_called_once()


class TestCrewAIStorage:
    """Test CrewAI storage backend."""

    def test_save_calls_assert_fact(self, mock_client: MagicMock) -> None:
        """Storage.save delegates to client.assert_fact."""
        pytest.importorskip("crewai")
        from nocturnusai.crewai import NocturnusAIStorage

        storage = NocturnusAIStorage(client=mock_client)
        storage.save(value="Alice prefers dark mode", metadata={"agent": "researcher"})
        mock_client.assert_fact.assert_called_once()

    def test_search_calls_query(self, mock_client: MagicMock) -> None:
        """Storage.search delegates to client.query or context_window."""
        pytest.importorskip("crewai")
        from nocturnusai.crewai import NocturnusAIStorage

        mock_client.query.return_value = []
        storage = NocturnusAIStorage(client=mock_client)
        storage.search(query="dark mode")
        # Should call either query or context_window
        assert mock_client.query.called or mock_client.context_window.called

    def test_reset_calls_retract(self, mock_client: MagicMock) -> None:
        """Storage.reset clears memory."""
        pytest.importorskip("crewai")
        from nocturnusai.crewai import NocturnusAIStorage

        storage = NocturnusAIStorage(client=mock_client)
        storage.reset()
        # Should attempt to clear facts
        assert mock_client.retract.called or mock_client.query.called
