# Framework Integration Blitz — Design Document

**Goal**: Drive NocturnusAI adoption among Python AI/ML developers by building native integrations for the top 5 agent frameworks, then listing NocturnusAI in their ecosystems.

**Target audience**: Python AI/ML developers building agentic systems who don't know NocturnusAI exists yet.

**Strategy**: "Show up where they already are" — framework ecosystem pages, PyPI discoverability, runnable examples.

---

## Architecture

### Package Structure

All integrations live inside the existing `nocturnusai` Python SDK as optional submodules, following the established `nocturnusai.langchain` pattern:

```
sdks/python/nocturnusai/
├── __init__.py          # existing
├── client.py            # existing async + sync clients
├── models.py            # existing Pydantic models
├── langchain.py         # existing (keep as-is)
├── crewai.py            # NEW — CrewAI BaseTool + Storage backend
├── autogen.py           # NEW — AutoGen FunctionTool + Memory protocol
├── langgraph.py         # NEW — LangGraph BaseCheckpointSaver
├── openai_agents.py     # NEW — OpenAI Agents SDK @function_tool
└── anthropic_tools.py   # NEW — Anthropic JSON schema tool defs + dispatcher
```

### Optional Dependencies

```toml
[project.optional-dependencies]
langchain = ["langchain-core>=0.2"]           # existing
crewai = ["crewai>=0.80"]
autogen = ["autogen-agentchat>=0.4"]
langgraph = ["langgraph>=0.2"]
openai-agents = ["openai-agents>=0.1"]
all = ["langchain-core>=0.2", "crewai>=0.80", "autogen-agentchat>=0.4", "langgraph>=0.2", "openai-agents>=0.1"]
```

Install: `pip install nocturnusai[crewai]` or `pip install nocturnusai[all]`

### Integration Pattern

Each module follows the same pattern as `langchain.py`:

1. Try-import with `_AVAILABLE` flag
2. `_check_*()` guard function
3. Framework-specific tool/memory/checkpoint classes
4. `get_nocturnusai_*()` convenience entry point

---

## Integration Specifications

### 1. CrewAI (`nocturnusai.crewai`) — HIGHEST PRIORITY

**Download volume**: ~2M/month | **Ecosystem page**: Yes (tools listing)

**Tools** (subclass `crewai.tools.BaseTool`):
- `NocturnusAITellTool` — assert facts
- `NocturnusAIAskTool` — query/infer via backward chaining
- `NocturnusAITeachTool` — define Horn clause rules
- `NocturnusAIContextTool` — salience-ranked retrieval for context window
- `NocturnusAIForgetTool` — retract facts
- `get_nocturnusai_tools(client)` — returns all 5

**Memory Backend** (implement `Storage` interface):
- `NocturnusAIStorage` — CrewAI shared memory backed by NocturnusAI
  - `save()` → `tell` with structured predicate extraction
  - `search()` → `ask` with salience ranking
  - `reset()` → tenant nuke

**Usage**:
```python
from nocturnusai import SyncNocturnusAIClient
from nocturnusai.crewai import get_nocturnusai_tools, NocturnusAIStorage

client = SyncNocturnusAIClient("http://localhost:9300")
tools = get_nocturnusai_tools(client)

storage = NocturnusAIStorage(base_url="http://localhost:9300", tenant_id="crew-1")
crew = Crew(agents=[...], tools=tools, memory=True,
            long_term_memory=LongTermMemory(storage=storage))
```

### 2. LangGraph (`nocturnusai.langgraph`)

**Download volume**: ~10-38M/month (LangChain ecosystem) | **Ecosystem**: LangChain integrations page

**Checkpoint Saver** (implement `BaseCheckpointSaver`):
- `NocturnusAICheckpointSaver`
  - `put()` → serialize checkpoint as fact with `scope = thread_id`
  - `get_tuple()` → query by thread_id scope
  - `list()` → list scopes
  - `delete_thread()` → delete scope

Natural fit: NocturnusAI scopes map cleanly to LangGraph threads.

**Tools**: Re-export existing `nocturnusai.langchain` tools for discoverability.

**Usage**:
```python
from nocturnusai.langgraph import NocturnusAICheckpointSaver

saver = NocturnusAICheckpointSaver(base_url="http://localhost:9300", tenant_id="app")
app = graph.compile(checkpointer=saver)
```

### 3. AutoGen (`nocturnusai.autogen`)

**Download volume**: ~400K/month | **Ecosystem**: GitHub contrib samples

**Tools** (`FunctionTool` wrappers):
- Same 5 tools (tell, ask, teach, forget, context)
- Register via `agent.register_for_llm()`

**Memory Protocol** (implement `Memory` interface):
- `NocturnusAIMemory`
  - `add()` → `tell` with auto-predicate extraction
  - `query()` → `ask` + salience ranking
  - `update_context()` → inject top-K salient facts into messages
  - `clear()` → tenant nuke
  - `close()` → no-op (stateless HTTP)

**Usage**:
```python
from nocturnusai.autogen import get_nocturnusai_tools, NocturnusAIMemory

tools = get_nocturnusai_tools(client)
memory = NocturnusAIMemory(base_url="http://localhost:9300", tenant_id="autogen-1")
agent = ConversableAgent("assistant", memory=[memory])
```

### 4. OpenAI Agents SDK (`nocturnusai.openai_agents`)

**Download volume**: ~1.5M/month | **Ecosystem**: OpenAI docs

**Tools** (via `@function_tool` decorator):
- 5 tools (tell, ask, teach, forget, context)
- `get_nocturnusai_tools(client)` → list of decorated functions

**Usage**:
```python
from nocturnusai.openai_agents import get_nocturnusai_tools

tools = get_nocturnusai_tools(client)
agent = Agent(name="reasoner", tools=tools)
```

### 5. Anthropic SDK (`nocturnusai.anthropic_tools`)

**Download volume**: ~44M/month | **Ecosystem**: Anthropic docs

**Tool Definitions** (JSON schema for messages API):
- `get_nocturnusai_tool_definitions()` → list of JSON schema tool defs
- `handle_tool_call(client, tool_name, tool_input)` → dispatch + execute

Stateless integration — just schemas + dispatcher. No framework dependency.

**Usage**:
```python
from nocturnusai.anthropic_tools import get_nocturnusai_tool_definitions, handle_tool_call

tools = get_nocturnusai_tool_definitions()
# In tool handling loop:
result = handle_tool_call(client, tool_name, tool_input)
```

---

## Ecosystem Distribution Strategy

### PyPI Optimization
- Version bump to `0.2.0`
- Rich README with framework-specific quick starts
- Keywords: `agent memory`, `crewai`, `autogen`, `langgraph`, `reasoning`, `logic engine`
- Classifiers: `Framework :: Agent`, `Topic :: Scientific/Engineering :: Artificial Intelligence`

### Framework Ecosystem Listings
- **CrewAI**: Submit to tools ecosystem page (requires working integration + README)
- **AutoGen**: Submit example notebook to contrib samples
- **LangGraph**: Add checkpoint saver example to LangChain integrations docs

### Example Notebooks (`sdks/python/examples/`)
- `crewai_research_crew.py` — Multi-agent research crew with NocturnusAI shared memory
- `autogen_reasoning_agent.py` — AutoGen agent with logical reasoning
- `langgraph_stateful_workflow.py` — LangGraph workflow with NocturnusAI checkpointing
- `openai_agents_knowledge.py` — OpenAI Agents with NocturnusAI tools
- `anthropic_tool_use.py` — Anthropic messages API with NocturnusAI tool definitions

Each example runnable in <5 minutes with `pip install nocturnusai[<framework>]`.

---

## Implementation Priority

| Priority | Framework | Integration Surface | Effort | Ecosystem Leverage |
|----------|-----------|-------------------|--------|-------------------|
| 1 | CrewAI | 5 tools + Storage backend | Medium | High (ecosystem page) |
| 2 | LangGraph | Checkpoint saver + tool re-export | Medium | High (volume) |
| 3 | AutoGen | 5 tools + Memory protocol | Medium | Medium (growing) |
| 4 | OpenAI Agents | 5 tools | Low | Medium (brand) |
| 5 | Anthropic | Tool defs + dispatcher | Low | Medium (brand) |

---

## Success Metrics

- Listed on CrewAI ecosystem page within 30 days
- Example notebooks all runnable with <5 min setup
- PyPI page shows all framework extras clearly
- Each integration has at least 3 tests
