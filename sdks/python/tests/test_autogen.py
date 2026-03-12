"""Tests for AutoGen integration."""
from __future__ import annotations

from unittest.mock import MagicMock

import pytest

from nocturnusai.models import ContextWindow


@pytest.fixture
def mock_client() -> MagicMock:
    client = MagicMock(unsafe=True)
    client.assert_fact.return_value = {"status": "ok"}
    client.assert_rule.return_value = {"status": "ok"}
    client.retract.return_value = {"status": "ok"}
    client.infer.return_value = []
    client.query.return_value = []
    client.context_window.return_value = ContextWindow(
        facts=[], totalAvailable=0, windowSize=0,
        predicateDistribution={}, generatedAt=0,
    )
    return client


class TestAutoGenImportGuard:
    def test_check_raises_without_package(self) -> None:
        from nocturnusai.autogen import _check_autogen, _AUTOGEN_AVAILABLE
        if not _AUTOGEN_AVAILABLE:
            with pytest.raises(ImportError, match="pip install nocturnusai\\[autogen\\]"):
                _check_autogen()


class TestAutoGenTools:
    def test_get_tools_returns_five(self, mock_client: MagicMock) -> None:
        from nocturnusai.autogen import get_nocturnusai_tools
        tools = get_nocturnusai_tools(mock_client)
        assert len(tools) == 5

    def test_tell_function(self, mock_client: MagicMock) -> None:
        from nocturnusai.autogen import get_nocturnusai_tools
        tools = get_nocturnusai_tools(mock_client)
        tell = [t for t in tools if "tell" in t.__name__][0]
        result = tell(predicate="likes", args='["alice", "bob"]')
        mock_client.assert_fact.assert_called_once()


class TestAutoGenMemory:
    def test_add_calls_assert_fact(self, mock_client: MagicMock) -> None:
        from nocturnusai.autogen import NocturnusAIMemory
        memory = NocturnusAIMemory(client=mock_client)
        import asyncio
        asyncio.get_event_loop().run_until_complete(
            memory.add([{"content": "Alice likes Bob"}])
        )
        mock_client.assert_fact.assert_called()

    def test_clear_calls_retract(self, mock_client: MagicMock) -> None:
        from nocturnusai.autogen import NocturnusAIMemory
        memory = NocturnusAIMemory(client=mock_client)
        import asyncio
        asyncio.get_event_loop().run_until_complete(memory.clear())
