"""Tests for core HTTP client behavior."""
from __future__ import annotations

import pytest

from nocturnusai.client import NocturnusAIClient


@pytest.mark.asyncio
async def test_ingest_and_optimize_uses_context_ingest_endpoint(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    client = NocturnusAIClient("http://localhost:9300")
    captured: dict[str, object] = {}

    async def fake_request(
        method: str,
        path: str,
        *,
        json_body: object | None = None,
        params: dict[str, object] | None = None,
        expect_json: bool = True,
    ) -> object:
        captured["method"] = method
        captured["path"] = path
        captured["json_body"] = json_body
        captured["params"] = params
        captured["expect_json"] = expect_json
        return {
            "extracted": [
                {"predicate": "customer", "args": ["acme"], "confidence": 1.0},
            ],
            "context": {
                "windowId": "window-1",
                "entries": [
                    {
                        "predicate": "customer",
                        "args": ["acme"],
                        "negated": False,
                        "scope": "demo-scope",
                        "salience": 0.9,
                        "category": "recent",
                        "charCount": 14,
                        "provenance": None,
                        "createdAt": None,
                        "validFrom": None,
                        "validUntil": None,
                        "metadata": {},
                    }
                ],
                "relevantRules": [],
                "totalFactsAvailable": 1,
                "totalFactsIncluded": 1,
                "deduplicationSavings": 0,
                "contradictionsFound": 0,
                "contradictionsResolved": 0,
                "contradictions": [],
                "bucketStats": {},
                "totalCharCount": 14,
                "goalDriven": False,
                "knowledgeGeneration": 1,
                "generatedAt": 1,
            },
        }

    monkeypatch.setattr(client, "_request", fake_request)

    result = await client.ingest_and_optimize(
        text="customer(acme)",
        goals=[{"predicate": "customer", "args": ["acme"]}],
        max_facts=10,
        session_id="session-1",
        relevance_buckets=[{"name": "accounts", "predicates": ["customer"], "weight": 2.0}],
        scope="demo-scope",
        context_hint="tenant status notes",
    )

    assert captured["method"] == "POST"
    assert captured["path"] == "/context/ingest"
    assert captured["json_body"] == {
        "text": "customer(acme)",
        "goals": [{"predicate": "customer", "args": ["acme"]}],
        "maxFacts": 10,
        "sessionId": "session-1",
        "relevanceBuckets": [{"name": "accounts", "predicates": ["customer"], "weight": 2.0}],
        "scope": "demo-scope",
        "contextHint": "tenant status notes",
    }
    assert result.total_facts_included == 1
    assert result.entries[0].predicate == "customer"
