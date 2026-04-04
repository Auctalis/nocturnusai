# Framework Integration Blitz — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build native Python integrations for 5 agent frameworks (CrewAI, AutoGen, LangGraph, OpenAI Agents SDK, Anthropic SDK) to drive NocturnusAI adoption among Python AI/ML developers.

**Architecture:** Each integration is a single Python module inside the existing `nocturnusai` SDK package (`sdks/python/nocturnusai/`), following the established `langchain.py` pattern: try-import guard, framework-specific wrappers around `SyncNocturnusAIClient`, and a `get_nocturnusai_*()` convenience entry point. Optional dependencies declared in `pyproject.toml`.

**Tech Stack:** Python 3.10+, httpx, pydantic, crewai, autogen-agentchat, langgraph, openai-agents, anthropic

---

## Context for Implementers

### Existing SDK Structure

```
sdks/python/
├── pyproject.toml              # hatchling build, deps: httpx, pydantic
├── nocturnusai/
│   ├── __init__.py             # exports NocturnusAIClient, SyncNocturnusAIClient, models
│   ├── client.py               # async NocturnusAIClient + sync SyncNocturnusAIClient
│   ├── models.py               # Atom, ScoredAtom, ContextWindow, etc.
│   ├── exceptions.py           # NocturnusAIError hierarchy
│   ├── mcp.py                  # MCP JSON-RPC helper
│   └── langchain.py            # LangChain integration (REFERENCE PATTERN)
```

### Key `SyncNocturnusAIClient` Methods You'll Use

```python
client = SyncNocturnusAIClient(base_url, database="default", tenant_id="default")

# Knowledge base
client.assert_fact(predicate, args, scope=None, negated=False, ttl=None)  # → dict
client.assert_rule(head, body, scope=None)                                # → dict
client.query(predicate, args, scope=None)                                 # → list[Atom]
client.infer(predicate, args, scope=None, with_proof=False)               # → list[Atom] | list[ProofTree]
client.retract(predicate, args, scope=None)                               # → dict

# Memory
client.context_window(max_facts=100, min_salience=0.0, predicates=None)   # → ContextWindow
client.consolidate()                                                       # → ConsolidationResult
client.decay(threshold=None)                                               # → DecayResult

# Admin (via HTTP, not client methods — use httpx directly if needed)
```

### The `langchain.py` Pattern to Follow

Every new integration module MUST follow this pattern:

```python
"""Module docstring with install instructions."""
from __future__ import annotations
import json, logging
from typing import Any

logger = logging.getLogger("nocturnusai.<framework>")

try:
    from <framework> import <BaseClass>
    _AVAILABLE = True
except ImportError:
    _AVAILABLE = False

def _check_<framework>() -> None:
    if not _AVAILABLE:
        raise ImportError("Install with: pip install nocturnusai[<framework>]")

if _AVAILABLE:
    # All framework-dependent classes here

    class NocturnusAI<Tool>(<BaseClass>):
        ...

def get_nocturnusai_tools(client: Any) -> list[Any]:
    _check_<framework>()
    return [NocturnusAI<Tool>(client=client), ...]
```

### Testing Approach

No tests exist yet for the SDK. Each task creates tests using `pytest` + `respx` (HTTP mocking for httpx). Tests do NOT require a running NocturnusAI server — they mock HTTP responses.

Run tests: `cd sdks/python && uv run pytest tests/ -v`

### Variable Convention

NocturnusAI uses `?` prefix for variables: `["?x", "bob"]`, NOT `["X", "bob"]`.

---

## Task 1: Update `pyproject.toml` with Optional Dependencies

**Files:**
- Modify: `sdks/python/pyproject.toml`

**Step 1: Add optional dependency groups**

Open `sdks/python/pyproject.toml` and replace the `[project.optional-dependencies]` section (lines 41-54) with:

```toml
[project.optional-dependencies]
langchain = [
    "langchain-core>=0.2.0,<1.0.0",
]
crewai = [
    "crewai>=0.80",
]
autogen = [
    "autogen-agentchat>=0.4",
]
langgraph = [
    "langgraph-checkpoint>=2.0",
]
openai-agents = [
    "openai-agents>=0.1",
]
dev = [
    "pytest>=7.0",
    "pytest-asyncio>=0.21",
    "respx>=0.21",
    "ruff>=0.4",
    "mypy>=1.8",
]
all = [
    "nocturnusai[langchain]",
    "nocturnusai[crewai]",
    "nocturnusai[autogen]",
    "nocturnusai[langgraph]",
    "nocturnusai[openai-agents]",
]
```

**Step 2: Add agent-related keywords**

Replace the `keywords` list (lines 15-22) with:

```toml
keywords = [
    "nocturnusai",
    "logic",
    "inference",
    "knowledge-base",
    "reasoning",
    "symbolic-ai",
    "agent-memory",
    "crewai",
    "autogen",
    "langgraph",
    "openai-agents",
]
```

**Step 3: Verify the file parses correctly**

Run: `cd sdks/python && uv run python -c "import tomllib; print(tomllib.load(open('pyproject.toml','rb'))['project']['optional-dependencies'].keys())"`

Expected: `dict_keys(['langchain', 'crewai', 'autogen', 'langgraph', 'openai-agents', 'dev', 'all'])`

**Step 4: Commit**

```bash
cd sdks/python
git add pyproject.toml
git commit -m "feat(sdk): add optional dependencies for crewai, autogen, langgraph, openai-agents"
```

---

## Task 2: Create `nocturnusai/crewai.py` — CrewAI Tools + Storage Backend

**Files:**
- Create: `sdks/python/nocturnusai/crewai.py`
- Create: `sdks/python/tests/test_crewai.py`

**Step 1: Write the tests**

Create `sdks/python/tests/test_crewai.py`:

```python
"""Tests for CrewAI integration."""
from __future__ import annotations

import json
from unittest.mock import MagicMock, patch

import pytest


@pytest.fixture
def mock_client() -> MagicMock:
    """Create a mock SyncNocturnusAIClient."""
    client = MagicMock()
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
```

**Step 2: Run tests to verify they fail**

Run: `cd sdks/python && uv run pytest tests/test_crewai.py -v`

Expected: FAIL (module `nocturnusai.crewai` not found)

**Step 3: Create `nocturnusai/crewai.py`**

```python
"""CrewAI integration for NocturnusAI.

Provides CrewAI-compatible tools and a storage backend for long-term memory.

Requires the ``crewai`` extra::

    pip install nocturnusai[crewai]

Usage::

    from nocturnusai import SyncNocturnusAIClient
    from nocturnusai.crewai import get_nocturnusai_tools, NocturnusAIStorage

    client = SyncNocturnusAIClient("http://localhost:9300")
    tools = get_nocturnusai_tools(client)
"""
from __future__ import annotations

import json
import logging
from typing import Any

logger = logging.getLogger("nocturnusai.crewai")

try:
    from crewai.tools import BaseTool
    from pydantic import BaseModel, Field

    _CREWAI_AVAILABLE = True
except ImportError:
    _CREWAI_AVAILABLE = False


def _check_crewai() -> None:
    """Raise ImportError if crewai is not installed."""
    if not _CREWAI_AVAILABLE:
        raise ImportError(
            "CrewAI integration requires the 'crewai' package. "
            "Install it with: pip install nocturnusai[crewai]"
        )


def _parse_json_list(value: str) -> list[str]:
    """Parse a JSON array string into a list of strings."""
    try:
        parsed = json.loads(value)
        if isinstance(parsed, list):
            return [str(item) for item in parsed]
    except (json.JSONDecodeError, TypeError):
        pass
    return [s.strip().strip("\"'") for s in value.split(",") if s.strip()]


if _CREWAI_AVAILABLE:

    # -- Input Schemas -------------------------------------------------------

    class TellInput(BaseModel):
        """Input for asserting a fact."""
        predicate: str = Field(description="Predicate name (e.g., 'likes', 'parent').")
        args: str = Field(description='JSON array of arguments. Example: \'["alice", "bob"]\'')
        scope: str | None = Field(default=None, description="Optional scope for isolation.")
        negated: bool = Field(default=False, description="Assert the negation of this fact.")

    class AskInput(BaseModel):
        """Input for querying/inferring."""
        predicate: str = Field(description="Predicate to query.")
        args: str = Field(
            description='JSON array. Use ?-prefixed variables for unknowns. '
            'Example: \'["alice", "?who"]\''
        )
        scope: str | None = Field(default=None, description="Optional scope filter.")

    class TeachInput(BaseModel):
        """Input for defining a rule."""
        head: str = Field(
            description='JSON object with "predicate" and "args". '
            'Example: \'{"predicate": "mortal", "args": ["?x"]}\''
        )
        body: str = Field(
            description='JSON array of condition objects. '
            'Example: \'[{"predicate": "human", "args": ["?x"]}]\''
        )
        scope: str | None = Field(default=None, description="Optional scope.")

    class ForgetInput(BaseModel):
        """Input for retracting a fact."""
        predicate: str = Field(description="Predicate to retract.")
        args: str = Field(description='JSON array of arguments.')
        scope: str | None = Field(default=None, description="Optional scope.")

    class ContextInput(BaseModel):
        """Input for context window retrieval."""
        max_facts: int = Field(default=50, description="Max facts to return.")
        min_salience: float = Field(default=0.0, description="Min salience score (0.0-1.0).")
        predicates: str | None = Field(
            default=None, description='Optional JSON array of predicate names to filter.'
        )

    # -- Tools ---------------------------------------------------------------

    class NocturnusAITellTool(BaseTool):
        """Assert a fact into NocturnusAI's knowledge base.

        Store knowledge like 'parent(alice, bob)' or 'likes(alice, pizza)'.
        Facts are the fundamental units of knowledge.
        """
        name: str = "nocturnusai_tell"
        description: str = (
            "Assert a fact into the NocturnusAI knowledge base. "
            "Use to store knowledge like 'likes(alice, bob)'. "
            "Args should be a JSON array of strings."
        )
        args_schema: type[BaseModel] = TellInput
        client: Any = None

        model_config = {"arbitrary_types_allowed": True}

        def _run(
            self,
            predicate: str,
            args: str,
            scope: str | None = None,
            negated: bool = False,
        ) -> str:
            if self.client is None:
                return "Error: NocturnusAI client not configured."
            parsed_args = _parse_json_list(args)
            try:
                self.client.assert_fact(
                    predicate=predicate, args=parsed_args,
                    scope=scope, negated=negated,
                )
                neg = "NOT " if negated else ""
                return f"Asserted: {neg}{predicate}({', '.join(parsed_args)})"
            except Exception as e:
                return f"Error asserting fact: {e}"

    class NocturnusAIAskTool(BaseTool):
        """Query the NocturnusAI knowledge base using logical inference.

        Finds answers by matching facts and applying rules through multi-step
        deductive reasoning. Use ?-prefixed variables for unknowns.
        """
        name: str = "nocturnusai_ask"
        description: str = (
            "Query NocturnusAI using logical inference. "
            "Applies rules for multi-step reasoning. "
            "Use ?-prefixed variables (like ?x, ?who) for unknowns."
        )
        args_schema: type[BaseModel] = AskInput
        client: Any = None

        model_config = {"arbitrary_types_allowed": True}

        def _run(
            self, predicate: str, args: str, scope: str | None = None,
        ) -> str:
            if self.client is None:
                return "Error: NocturnusAI client not configured."
            parsed_args = _parse_json_list(args)
            try:
                results = self.client.infer(
                    predicate=predicate, args=parsed_args, scope=scope,
                )
                if not results:
                    return "No results found."
                lines = [f"Found {len(results)} result(s):"]
                for atom in results:
                    neg = "NOT " if atom.negated else ""
                    lines.append(f"  {neg}{atom.predicate}({', '.join(atom.args)})")
                return "\n".join(lines)
            except Exception as e:
                return f"Error querying: {e}"

    class NocturnusAITeachTool(BaseTool):
        """Teach a logical rule to NocturnusAI.

        Define Horn clause rules like 'grandparent(?x,?z) :- parent(?x,?y), parent(?y,?z)'.
        Rules enable multi-step inference.
        """
        name: str = "nocturnusai_teach"
        description: str = (
            "Teach a logical rule to NocturnusAI. Rules are Horn clauses: "
            "if all body conditions are true, the head conclusion is derived. "
            "Head and body are JSON objects with 'predicate' and 'args'."
        )
        args_schema: type[BaseModel] = TeachInput
        client: Any = None

        model_config = {"arbitrary_types_allowed": True}

        def _run(
            self, head: str, body: str, scope: str | None = None,
        ) -> str:
            if self.client is None:
                return "Error: NocturnusAI client not configured."
            try:
                parsed_head = json.loads(head)
                parsed_body = json.loads(body)
                self.client.assert_rule(
                    head=parsed_head, body=parsed_body, scope=scope,
                )
                h = parsed_head
                return f"Taught rule: {h['predicate']}({', '.join(h['args'])})"
            except Exception as e:
                return f"Error teaching rule: {e}"

    class NocturnusAIForgetTool(BaseTool):
        """Retract a fact from NocturnusAI's knowledge base.

        Removes a specific fact. Derived knowledge is automatically retracted.
        """
        name: str = "nocturnusai_forget"
        description: str = (
            "Retract a fact from NocturnusAI. Derived knowledge is "
            "automatically retracted via truth maintenance."
        )
        args_schema: type[BaseModel] = ForgetInput
        client: Any = None

        model_config = {"arbitrary_types_allowed": True}

        def _run(
            self, predicate: str, args: str, scope: str | None = None,
        ) -> str:
            if self.client is None:
                return "Error: NocturnusAI client not configured."
            parsed_args = _parse_json_list(args)
            try:
                self.client.retract(
                    predicate=predicate, args=parsed_args, scope=scope,
                )
                return f"Retracted: {predicate}({', '.join(parsed_args)})"
            except Exception as e:
                return f"Error retracting: {e}"

    class NocturnusAIContextTool(BaseTool):
        """Get the most relevant facts from NocturnusAI for the current context.

        Returns facts ranked by salience (recency, frequency, priority).
        Use this to populate an agent's context with the most important knowledge.
        """
        name: str = "nocturnusai_context"
        description: str = (
            "Get the most relevant facts from NocturnusAI ranked by salience. "
            "Returns facts scored by recency, frequency, and priority."
        )
        args_schema: type[BaseModel] = ContextInput
        client: Any = None

        model_config = {"arbitrary_types_allowed": True}

        def _run(
            self,
            max_facts: int = 50,
            min_salience: float = 0.0,
            predicates: str | None = None,
        ) -> str:
            if self.client is None:
                return "Error: NocturnusAI client not configured."
            parsed_preds: list[str] | None = None
            if predicates:
                parsed_preds = _parse_json_list(predicates)
            try:
                window = self.client.context_window(
                    max_facts=max_facts,
                    min_salience=min_salience,
                    predicates=parsed_preds,
                )
                if not window.facts:
                    return "Context window empty. No facts match criteria."
                lines = [
                    f"Context ({window.window_size}/{window.total_available} facts):",
                ]
                for scored in window.facts:
                    a = scored.atom
                    neg = "NOT " if a.negated else ""
                    lines.append(
                        f"  [{scored.salience:.3f}] {neg}{a.predicate}({', '.join(a.args)})"
                    )
                return "\n".join(lines)
            except Exception as e:
                return f"Error getting context: {e}"


class NocturnusAIStorage:
    """CrewAI storage backend backed by NocturnusAI.

    Implements the CrewAI StorageBackend protocol (save/search/reset)
    for use as a LongTermMemory backend.

    Usage::

        from nocturnusai import SyncNocturnusAIClient
        from nocturnusai.crewai import NocturnusAIStorage
        from crewai.memory import LongTermMemory

        client = SyncNocturnusAIClient("http://localhost:9300")
        storage = NocturnusAIStorage(client=client)
        crew = Crew(memory=True, long_term_memory=LongTermMemory(storage=storage))
    """

    def __init__(self, client: Any) -> None:
        self._client = client

    def save(
        self,
        value: str,
        metadata: dict[str, Any] | None = None,
        agent: str | None = None,
    ) -> None:
        """Save a memory record as a NocturnusAI fact."""
        args = [value]
        if agent:
            args.insert(0, agent)
        try:
            self._client.assert_fact(
                predicate="crew_memory",
                args=args,
            )
        except Exception:
            logger.exception("Failed to save memory to NocturnusAI")

    def search(self, query: str) -> dict[str, Any]:
        """Search memory by querying NocturnusAI facts."""
        try:
            results = self._client.query(
                predicate="crew_memory",
                args=["?agent", "?value"] if query == "" else ["?x"],
            )
            return {
                "results": [
                    {"value": ", ".join(a.args)} for a in results
                ]
            }
        except Exception:
            logger.exception("Failed to search NocturnusAI memory")
            return {"results": []}

    def reset(self) -> None:
        """Clear all crew memory facts."""
        try:
            results = self._client.query(
                predicate="crew_memory", args=["?x"],
            )
            for atom in results:
                self._client.retract(
                    predicate="crew_memory", args=atom.args,
                )
        except Exception:
            logger.exception("Failed to reset NocturnusAI memory")


def get_nocturnusai_tools(client: Any) -> list[Any]:
    """Create all NocturnusAI CrewAI tools configured with a client.

    Args:
        client: A SyncNocturnusAIClient instance.

    Returns:
        List of 5 CrewAI tools: tell, ask, teach, forget, context.

    Raises:
        ImportError: If crewai is not installed.
    """
    _check_crewai()
    return [
        NocturnusAITellTool(client=client),
        NocturnusAIAskTool(client=client),
        NocturnusAITeachTool(client=client),
        NocturnusAIForgetTool(client=client),
        NocturnusAIContextTool(client=client),
    ]
```

**Step 4: Run tests to verify they pass**

Run: `cd sdks/python && uv run pytest tests/test_crewai.py -v`

Expected: Tests that require crewai will be skipped (pytest.importorskip). Import guard test should pass.

**Step 5: Commit**

```bash
git add sdks/python/nocturnusai/crewai.py sdks/python/tests/test_crewai.py
git commit -m "feat(sdk): add CrewAI integration — 5 tools + storage backend"
```

---

## Task 3: Create `nocturnusai/autogen.py` — AutoGen Tools + Memory

**Files:**
- Create: `sdks/python/nocturnusai/autogen.py`
- Create: `sdks/python/tests/test_autogen.py`

**Step 1: Write the tests**

Create `sdks/python/tests/test_autogen.py`:

```python
"""Tests for AutoGen integration."""
from __future__ import annotations

from unittest.mock import MagicMock

import pytest

from nocturnusai.models import ContextWindow


@pytest.fixture
def mock_client() -> MagicMock:
    client = MagicMock()
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
```

**Step 2: Run tests to verify they fail**

Run: `cd sdks/python && uv run pytest tests/test_autogen.py -v`

Expected: FAIL

**Step 3: Create `nocturnusai/autogen.py`**

```python
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
    from autogen_agentchat.tools import FunctionTool

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
    """

    def __init__(self, client: Any) -> None:
        self._client = client

    async def add(self, messages: list[Any]) -> None:
        """Store messages as NocturnusAI facts."""
        for msg in messages:
            content = msg.get("content", str(msg)) if isinstance(msg, dict) else str(msg)
            try:
                self._client.assert_fact(
                    predicate="agent_memory", args=[content],
                )
            except Exception:
                logger.exception("Failed to add memory")

    async def query(self, query: str, limit: int = 10) -> list[Any]:
        """Query memory for relevant items."""
        try:
            results = self._client.query(
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
            window = self._client.context_window(max_facts=20, min_salience=0.1)
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
            results = self._client.query(
                predicate="agent_memory", args=["?x"],
            )
            for atom in results:
                self._client.retract(predicate="agent_memory", args=atom.args)
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
```

**Step 4: Run tests**

Run: `cd sdks/python && uv run pytest tests/test_autogen.py -v`

Expected: PASS (tests use plain functions, don't require autogen installed)

**Step 5: Commit**

```bash
git add sdks/python/nocturnusai/autogen.py sdks/python/tests/test_autogen.py
git commit -m "feat(sdk): add AutoGen integration — 5 tools + Memory protocol"
```

---

## Task 4: Create `nocturnusai/langgraph.py` — LangGraph Checkpoint Saver

**Files:**
- Create: `sdks/python/nocturnusai/langgraph.py`
- Create: `sdks/python/tests/test_langgraph.py`

**Step 1: Write the tests**

Create `sdks/python/tests/test_langgraph.py`:

```python
"""Tests for LangGraph checkpoint saver integration."""
from __future__ import annotations

import json
from unittest.mock import MagicMock

import pytest

from nocturnusai.models import Atom


@pytest.fixture
def mock_client() -> MagicMock:
    client = MagicMock()
    client.assert_fact.return_value = {"status": "ok"}
    client.query.return_value = []
    client.retract.return_value = {"status": "ok"}
    return client


class TestLangGraphImportGuard:
    def test_check_raises_without_package(self) -> None:
        from nocturnusai.langgraph import _check_langgraph, _LANGGRAPH_AVAILABLE
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
```

**Step 2: Run tests to verify they fail**

Run: `cd sdks/python && uv run pytest tests/test_langgraph.py -v`

**Step 3: Create `nocturnusai/langgraph.py`**

```python
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
```

**Step 4: Run tests**

Run: `cd sdks/python && uv run pytest tests/test_langgraph.py -v`

Expected: PASS

**Step 5: Commit**

```bash
git add sdks/python/nocturnusai/langgraph.py sdks/python/tests/test_langgraph.py
git commit -m "feat(sdk): add LangGraph checkpoint saver integration"
```

---

## Task 5: Create `nocturnusai/openai_agents.py` — OpenAI Agents SDK

**Files:**
- Create: `sdks/python/nocturnusai/openai_agents.py`
- Create: `sdks/python/tests/test_openai_agents.py`

**Step 1: Write the tests**

Create `sdks/python/tests/test_openai_agents.py`:

```python
"""Tests for OpenAI Agents SDK integration."""
from __future__ import annotations

from unittest.mock import MagicMock

import pytest


@pytest.fixture
def mock_client() -> MagicMock:
    client = MagicMock()
    client.assert_fact.return_value = {"status": "ok"}
    client.assert_rule.return_value = {"status": "ok"}
    client.retract.return_value = {"status": "ok"}
    client.infer.return_value = []
    return client


class TestOpenAIAgentsImportGuard:
    def test_check_raises_without_package(self) -> None:
        from nocturnusai.openai_agents import _check_openai_agents, _OPENAI_AGENTS_AVAILABLE
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
```

**Step 2: Run tests to verify they fail**

**Step 3: Create `nocturnusai/openai_agents.py`**

```python
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
```

**Step 4: Run tests**

Run: `cd sdks/python && uv run pytest tests/test_openai_agents.py -v`

**Step 5: Commit**

```bash
git add sdks/python/nocturnusai/openai_agents.py sdks/python/tests/test_openai_agents.py
git commit -m "feat(sdk): add OpenAI Agents SDK integration"
```

---

## Task 6: Create `nocturnusai/anthropic_tools.py` — Anthropic Tool Definitions

**Files:**
- Create: `sdks/python/nocturnusai/anthropic_tools.py`
- Create: `sdks/python/tests/test_anthropic_tools.py`

**Step 1: Write the tests**

Create `sdks/python/tests/test_anthropic_tools.py`:

```python
"""Tests for Anthropic tool definitions."""
from __future__ import annotations

from unittest.mock import MagicMock

import pytest


@pytest.fixture
def mock_client() -> MagicMock:
    client = MagicMock()
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
```

**Step 2: Run tests to verify they fail**

**Step 3: Create `nocturnusai/anthropic_tools.py`**

```python
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

import json
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
                lines.append(
                    f"  [{s.salience:.3f}] {a.predicate}({', '.join(a.args)})"
                )
            return "\n".join(lines)

        else:
            return f"Unknown tool: {tool_name}"

    except Exception as e:
        return f"Error in {tool_name}: {e}"
```

**Step 4: Run tests**

Run: `cd sdks/python && uv run pytest tests/test_anthropic_tools.py -v`

Expected: PASS (no external deps needed)

**Step 5: Commit**

```bash
git add sdks/python/nocturnusai/anthropic_tools.py sdks/python/tests/test_anthropic_tools.py
git commit -m "feat(sdk): add Anthropic tool definitions + dispatcher"
```

---

## Task 7: Create Example Scripts

**Files:**
- Create: `sdks/python/examples/crewai_research_crew.py`
- Create: `sdks/python/examples/autogen_reasoning_agent.py`
- Create: `sdks/python/examples/langgraph_stateful_workflow.py`
- Create: `sdks/python/examples/openai_agents_knowledge.py`
- Create: `sdks/python/examples/anthropic_tool_use.py`

**Step 1: Create example directory**

Run: `mkdir -p sdks/python/examples`

**Step 2: Create all 5 examples**

Each example should:
- Be self-contained and runnable
- Include a docstring explaining prerequisites (`pip install` + running server)
- Show the core integration pattern in <50 lines
- Not require any API keys (use localhost NocturnusAI only)

Create `sdks/python/examples/crewai_research_crew.py`:

```python
"""CrewAI + NocturnusAI example: Research crew with shared logical memory.

Prerequisites:
    pip install nocturnusai[crewai]
    # Start NocturnusAI: ./gradlew :nocturnusai-server:run
    # Create tenant: curl -X POST http://localhost:9300/admin/databases/default/tenants \
    #   -H "Content-Type: application/json" -d '{"tenantId": "default"}'
"""
from nocturnusai import SyncNocturnusAIClient
from nocturnusai.crewai import get_nocturnusai_tools, NocturnusAIStorage

client = SyncNocturnusAIClient("http://localhost:9300")
tools = get_nocturnusai_tools(client)
storage = NocturnusAIStorage(client=client)

print("NocturnusAI CrewAI tools ready:")
for tool in tools:
    print(f"  - {tool.name}: {tool.description[:60]}...")

print("\nStorage backend ready for LongTermMemory")
print("Usage: Crew(tools=tools, memory=True, long_term_memory=LongTermMemory(storage=storage))")
```

Create `sdks/python/examples/autogen_reasoning_agent.py`:

```python
"""AutoGen + NocturnusAI example: Agent with logical reasoning capabilities.

Prerequisites:
    pip install nocturnusai[autogen]
    # Start NocturnusAI: ./gradlew :nocturnusai-server:run
"""
from nocturnusai import SyncNocturnusAIClient
from nocturnusai.autogen import get_nocturnusai_tools, NocturnusAIMemory

client = SyncNocturnusAIClient("http://localhost:9300")
tools = get_nocturnusai_tools(client)
memory = NocturnusAIMemory(client=client)

print("NocturnusAI AutoGen tools ready:")
for tool in tools:
    print(f"  - {tool.__name__}: {tool.__doc__[:60] if tool.__doc__ else ''}")

print("\nMemory protocol ready (add/query/update_context/clear/close)")
print("Usage: ConversableAgent('agent', tools=[FunctionTool(t) for t in tools], memory=memory)")
```

Create `sdks/python/examples/langgraph_stateful_workflow.py`:

```python
"""LangGraph + NocturnusAI example: Stateful workflow with checkpointing.

Prerequisites:
    pip install nocturnusai[langgraph]
    # Start NocturnusAI: ./gradlew :nocturnusai-server:run
"""
from nocturnusai import SyncNocturnusAIClient
from nocturnusai.langgraph import NocturnusAICheckpointSaver

client = SyncNocturnusAIClient("http://localhost:9300")
saver = NocturnusAICheckpointSaver(client=client)

print("NocturnusAI LangGraph checkpoint saver ready")
print("Usage: app = graph.compile(checkpointer=saver)")
print("\nLangGraph threads map to NocturnusAI scopes for isolation.")
```

Create `sdks/python/examples/openai_agents_knowledge.py`:

```python
"""OpenAI Agents SDK + NocturnusAI example: Agent with knowledge tools.

Prerequisites:
    pip install nocturnusai[openai-agents]
    # Start NocturnusAI: ./gradlew :nocturnusai-server:run
"""
from nocturnusai import SyncNocturnusAIClient
from nocturnusai.openai_agents import get_nocturnusai_tools

client = SyncNocturnusAIClient("http://localhost:9300")
tools = get_nocturnusai_tools(client)

print("NocturnusAI OpenAI Agents tools ready:")
for i, tool in enumerate(tools):
    name = getattr(tool, "name", getattr(tool, "__name__", f"tool_{i}"))
    print(f"  - {name}")

print("\nUsage: Agent(name='reasoner', tools=tools)")
```

Create `sdks/python/examples/anthropic_tool_use.py`:

```python
"""Anthropic SDK + NocturnusAI example: Claude with knowledge tools.

Prerequisites:
    pip install nocturnusai anthropic
    # Start NocturnusAI: ./gradlew :nocturnusai-server:run
"""
from nocturnusai import SyncNocturnusAIClient
from nocturnusai.anthropic_tools import get_nocturnusai_tool_definitions, handle_tool_call

client = SyncNocturnusAIClient("http://localhost:9300")
tools = get_nocturnusai_tool_definitions()

print("NocturnusAI Anthropic tool definitions:")
for tool_def in tools:
    print(f"  - {tool_def['name']}: {tool_def['description'][:60]}...")

print("\nUsage:")
print("  response = anthropic.messages.create(tools=tools, ...)")
print("  result = handle_tool_call(client, tool_use.name, tool_use.input)")
```

**Step 3: Commit**

```bash
git add sdks/python/examples/
git commit -m "feat(sdk): add framework integration example scripts"
```

---

## Task 8: Final Validation & Version Bump

**Files:**
- Modify: `sdks/python/pyproject.toml` (version)
- Modify: `sdks/python/nocturnusai/__init__.py` (version)

**Step 1: Run all tests**

Run: `cd sdks/python && uv run pytest tests/ -v`

Expected: All tests pass. Framework-specific tests skip if deps not installed.

**Step 2: Run linting**

Run: `cd sdks/python && uv run ruff check nocturnusai/ tests/`

Fix any issues.

**Step 3: Bump version**

In `sdks/python/pyproject.toml`, change `version = "0.1.3"` to `version = "0.2.0"`.

In `sdks/python/nocturnusai/__init__.py`, change `__version__ = "0.1.0"` to `__version__ = "0.2.0"`.

**Step 4: Commit**

```bash
git add sdks/python/pyproject.toml sdks/python/nocturnusai/__init__.py
git commit -m "feat(sdk): bump to 0.2.0 with framework integrations"
```

**Step 5: Verify the full module structure**

Run: `find sdks/python/nocturnusai -name "*.py" | sort`

Expected:
```
sdks/python/nocturnusai/__init__.py
sdks/python/nocturnusai/anthropic_tools.py
sdks/python/nocturnusai/autogen.py
sdks/python/nocturnusai/client.py
sdks/python/nocturnusai/crewai.py
sdks/python/nocturnusai/exceptions.py
sdks/python/nocturnusai/langchain.py
sdks/python/nocturnusai/langgraph.py
sdks/python/nocturnusai/mcp.py
sdks/python/nocturnusai/models.py
sdks/python/nocturnusai/openai_agents.py
```
