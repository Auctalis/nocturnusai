"""LangGraph checkpoint saver integration for NocturnusAI.

Persists LangGraph graph state as NocturnusAI facts using scopes for
thread isolation.

Requires the ``langgraph`` extra::

    pip install nocturnusai[langgraph]

Usage::

    from nocturnusai import SyncNocturnusAIClient
    from nocturnusai.langgraph import NocturnusAICheckpointSaver

    client = SyncNocturnusAIClient("http://localhost:9300")
    saver = NocturnusAICheckpointSaver(client=client)
    app = graph.compile(checkpointer=saver)
"""
from __future__ import annotations

import json
import logging
from typing import Any, Iterator

logger = logging.getLogger("nocturnusai.langgraph")

try:
    from langgraph.checkpoint.base import BaseCheckpointSaver

    _LANGGRAPH_AVAILABLE = True
except ImportError:
    _LANGGRAPH_AVAILABLE = False


def _check_langgraph() -> None:
    if not _LANGGRAPH_AVAILABLE:
        raise ImportError(
            "LangGraph integration requires 'langgraph-checkpoint'. "
            "Install with: pip install nocturnusai[langgraph]"
        )


_PREDICATE = "lg_checkpoint"


class NocturnusAICheckpointSaver:
    """LangGraph checkpoint saver backed by NocturnusAI.

    Maps LangGraph threads to NocturnusAI scopes. Each checkpoint is stored
    as a fact with predicate 'lg_checkpoint' and args [thread_id, state_json, metadata_json].

    Works with or without langgraph installed (does not inherit from
    BaseCheckpointSaver to avoid import dependency, but implements
    the same interface).
    """

    def __init__(self, client: Any) -> None:
        self._client = client

    def put(
        self,
        config: dict[str, Any],
        checkpoint: dict[str, Any],
        metadata: dict[str, Any],
        **kwargs: Any,
    ) -> dict[str, Any]:
        """Save a checkpoint."""
        thread_id = config["configurable"]["thread_id"]
        state_json = json.dumps(checkpoint)
        meta_json = json.dumps(metadata)

        # Retract previous checkpoint for this thread
        try:
            existing = self._client.query(
                predicate=_PREDICATE,
                args=[thread_id, "?state", "?meta"],
                scope=thread_id,
            )
            for atom in existing:
                self._client.retract(
                    predicate=_PREDICATE, args=atom.args, scope=thread_id,
                )
        except Exception:
            pass  # No existing checkpoint

        self._client.assert_fact(
            predicate=_PREDICATE,
            args=[thread_id, state_json, meta_json],
            scope=thread_id,
        )

        checkpoint_id = checkpoint.get("id", thread_id)
        return {
            "configurable": {
                "thread_id": thread_id,
                "checkpoint_id": checkpoint_id,
            }
        }

    def get_tuple(
        self, config: dict[str, Any],
    ) -> dict[str, Any] | None:
        """Retrieve the latest checkpoint for a thread."""
        thread_id = config["configurable"]["thread_id"]
        try:
            results = self._client.query(
                predicate=_PREDICATE,
                args=[thread_id, "?state", "?meta"],
                scope=thread_id,
            )
            if not results:
                return None

            latest = results[-1]
            checkpoint = json.loads(latest.args[1])
            metadata = json.loads(latest.args[2])

            return {
                "config": config,
                "checkpoint": checkpoint,
                "metadata": metadata,
                "parent_config": None,
            }
        except Exception:
            logger.exception("Failed to get checkpoint")
            return None

    def list(
        self,
        config: dict[str, Any],
        *,
        filter: dict[str, Any] | None = None,
        before: dict[str, Any] | None = None,
        limit: int | None = None,
    ) -> Iterator[dict[str, Any]]:
        """List checkpoints for a thread."""
        thread_id = config["configurable"]["thread_id"]
        try:
            results = self._client.query(
                predicate=_PREDICATE,
                args=[thread_id, "?state", "?meta"],
                scope=thread_id,
            )
            for atom in results[:limit]:
                checkpoint = json.loads(atom.args[1])
                metadata = json.loads(atom.args[2])
                yield {
                    "config": config,
                    "checkpoint": checkpoint,
                    "metadata": metadata,
                    "parent_config": None,
                }
        except Exception:
            logger.exception("Failed to list checkpoints")
