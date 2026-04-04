# Advanced / Deep-Dive Demos

These demos cover the lower-level SDK mechanics behind the context workflow.

Use them when you need to understand the backend pieces directly: fact assertion, rules, inference, salience, temporal queries, transactions, auth, and SSE events.

## Python SDK

```bash
cd demos/advanced/python
pip install nocturnusai
python 01_basics.py
python 02_rules_and_inference.py
python 03_memory_management.py
python 04_transactions.py
python 05_auth_and_keys.py
python 06_dsl_execute.py
python 07_langchain_tools.py
python 08_agent_workflow.py
```

| File | Topic |
|------|-------|
| `01_basics.py` | `assert_fact`, `query`, `infer`, `retract` |
| `02_rules_and_inference.py` | `assert_rule`, multi-hop inference, proof trees |
| `03_memory_management.py` | `context_window`, `temporal_query`, `set_priority`, `consolidate`, `decay`, TTL |
| `04_transactions.py` | begin/commit/rollback transactions |
| `05_auth_and_keys.py` | auth bootstrap and key management |
| `06_dsl_execute.py` | Logiql DSL and predicate discovery |
| `07_langchain_tools.py` | LangChain tools in isolation |
| `08_agent_workflow.py` | End-to-end agent lifecycle |

## TypeScript SDK

```bash
cd demos/advanced/typescript
npm install
npx ts-node 01_basics.ts
npx ts-node 03_memory_management.ts
npx ts-node 06_events_sse.ts
npx ts-node 07_agent_workflow.ts
```

| File | Topic |
|------|-------|
| `01_basics.ts` | `assertFact`, `query`, `infer`, `retract` |
| `02_rules_and_inference.ts` | `assertRule`, multi-hop inference, proof trees |
| `03_memory_management.ts` | `contextWindow`, `temporalQuery`, `setPriority`, `consolidate`, `decay` |
| `04_transactions.ts` | begin/commit/rollback transactions |
| `05_auth_and_keys.ts` | auth bootstrap and key management |
| `06_events_sse.ts` | `subscribeEvents()` real-time stream |
| `07_agent_workflow.ts` | End-to-end TypeScript workflow |

## See also

- [../llm/](../llm/) - featured agent demos
- [../curl/](../curl/) - raw HTTP examples
