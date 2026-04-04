# Python SDK Demos

## Setup

```bash
pip install nocturnusai
# or from source during development:
pip install -e ../../../sdks/python
```

Make sure the server is running at `http://localhost:9300`.

## Running

```bash
python 01_basics.py
python 02_rules_and_inference.py
python 03_memory_management.py
python 04_transactions.py
python 05_auth_and_keys.py    # requires AUTH_ENABLED=true for the full demo
python 06_dsl_execute.py
python 07_langchain_tools.py  # requires LangChain packages
python 08_agent_workflow.py
```

## Files

| File | Concepts |
|------|----------|
| `01_basics.py` | `assert_fact`, `query`, `infer`, `retract`, negated facts |
| `02_rules_and_inference.py` | `assert_rule`, multi-hop inference, proof trees, transitive closure |
| `03_memory_management.py` | `context_window`, `temporal_query`, `set_priority`, `consolidate`, `decay`, TTL |
| `04_transactions.py` | `begin_transaction`, `commit_transaction`, `rollback_transaction` |
| `05_auth_and_keys.py` | `auth_status`, `bootstrap`, `create_key`, `list_keys`, `revoke_key`, `whoami` |
| `06_dsl_execute.py` | `execute` and `predicates()` |
| `07_langchain_tools.py` | LangChain tool wrappers |
| `08_agent_workflow.py` | End-to-end agent memory lifecycle |
