"""Tests for LangGraph checkpoint saver integration."""
from __future__ import annotations

import json
from unittest.mock import MagicMock

import pytest

from nocturnusai.models import Atom


@pytest.fixture
def mock_client() -> MagicMock:
    client = MagicMock(unsafe=True)
    client.assert_fact.return_value = {"status": "ok"}
    client.query.return_value = []
    client.retract.return_value = {"status": "ok"}
    return client


class TestLangGraphImportGuard:
    def test_check_raises_without_package(self) -> None:
        from nocturnusai.langgraph import _LANGGRAPH_AVAILABLE, _check_langgraph
        if not _LANGGRAPH_AVAILABLE:
            with pytest.raises(ImportError, match="pip install nocturnusai\\[langgraph\\]"):
                _check_langgraph()


class TestCheckpointSaver:
    def test_put_stores_checkpoint(self, mock_client: MagicMock) -> None:
        from nocturnusai.langgraph import NocturnusAICheckpointSaver

        saver = NocturnusAICheckpointSaver(client=mock_client)
        config = {"configurable": {"thread_id": "thread-1"}}
        checkpoint = {"values": {"count": 5}, "id": "cp-1"}
        metadata = {"source": "loop", "step": 1}

        result = saver.put(config, checkpoint, metadata)
        mock_client.assert_fact.assert_called_once()
        assert "thread_id" in result["configurable"]

    def test_get_tuple_returns_none_when_empty(self, mock_client: MagicMock) -> None:
        from nocturnusai.langgraph import NocturnusAICheckpointSaver

        saver = NocturnusAICheckpointSaver(client=mock_client)
        config = {"configurable": {"thread_id": "thread-1"}}
        result = saver.get_tuple(config)
        assert result is None

    def test_get_tuple_returns_checkpoint(self, mock_client: MagicMock) -> None:
        from nocturnusai.langgraph import NocturnusAICheckpointSaver

        state = json.dumps({"values": {"count": 5}, "id": "cp-1"})
        meta = json.dumps({"source": "loop", "step": 1})
        mock_client.query.return_value = [
            Atom(predicate="lg_checkpoint", args=["thread-1", state, meta]),
        ]

        saver = NocturnusAICheckpointSaver(client=mock_client)
        config = {"configurable": {"thread_id": "thread-1"}}
        result = saver.get_tuple(config)
        assert result is not None
        assert result["checkpoint"]["values"]["count"] == 5

    def test_list_returns_checkpoints(self, mock_client: MagicMock) -> None:
        from nocturnusai.langgraph import NocturnusAICheckpointSaver

        saver = NocturnusAICheckpointSaver(client=mock_client)
        config = {"configurable": {"thread_id": "thread-1"}}
        result = list(saver.list(config))
        assert isinstance(result, list)
