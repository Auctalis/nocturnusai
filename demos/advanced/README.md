# Advanced / Deep-Dive Demos

These demos cover the raw SDK mechanics in depth — the building blocks
that power the LLM integrations in [../llm/](../llm/).

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
| `01_basics.py` | assert_fact, query, infer, retract |
| `02_rules_and_inference.py` | assert_rule, multi-hop inference, proof trees |
| `03_memory_management.py` | context_window, temporal_query, set_priority, consolidate, decay, TTL |
| `04_transactions.py` | begin/commit/rollback transactions |
| `05_auth_and_keys.py` | auth_status, bootstrap, create/list/revoke keys |
| `06_dsl_execute.py` | Logiql DSL, predicates (schema) |
| `07_langchain_tools.py` | LangChain tool wrappers in isolation |
| `08_agent_workflow.py` | Full async agent workflow |

## TypeScript SDK

```bash
cd demos/advanced/typescript
npm install
npx ts-node 01_basics.ts
```

| File | Topic |
|------|-------|
| `01_basics.ts` | assertFact, query, infer, retract |
| `02_rules_and_inference.ts` | assertRule, multi-hop, proof trees |
| `03_memory_management.ts` | contextWindow, temporalQuery, setPriority, consolidate, decay |
| `04_transactions.ts` | beginTransaction, commitTransaction, rollbackTransaction |
| `05_auth_and_keys.ts` | authStatus, bootstrap, createKey, listKeys, revokeKey |
| `06_events_sse.ts` | subscribeEvents — real-time SSE |
| `07_agent_workflow.ts` | Full TypeScript agent lifecycle |

## See also

- [../llm/](../llm/) — LLM & agent integration (featured)
- [../curl/](../curl/) — Raw HTTP API examples
