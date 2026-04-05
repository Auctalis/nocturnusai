from __future__ import annotations

import contextlib
import time
import uuid

from nocturnusai import SyncNocturnusAIClient

BASE_URL = "http://localhost:9300"


def main() -> None:
    run_id = uuid.uuid4().hex[:8]
    scope = f"site-context-demo-{run_id}"
    session_id = f"{scope}-session"
    ticket_id = f"ticket_{run_id}"
    account_id = f"acme_{run_id}"

    seed_text = f"""
    customer({account_id})
    issue({ticket_id}, sla_credit)
    account_for_ticket({ticket_id}, {account_id})
    contract_tier({account_id}, enterprise)
    """.strip()

    cleanup_facts = [
        ("customer", [account_id]),
        ("issue", [ticket_id, "sla_credit"]),
        ("account_for_ticket", [ticket_id, account_id]),
        ("contract_tier", [account_id, "enterprise"]),
        ("renewal_due", [account_id, "next_month"]),
    ]

    with SyncNocturnusAIClient(BASE_URL, tenant_id="default") as client:
        try:
            ctx = client.ingest_and_optimize(
                text=seed_text,
                max_facts=12,
                session_id=session_id,
                scope=scope,
            )

            window = client.context_window(max_facts=12, scope=scope)

            client.assert_fact("renewal_due", [account_id, "next_month"], scope=scope)
            diff = client.diff_context(session_id=session_id, max_facts=12, scope=scope)

            approx_tokens = max(1, ctx.total_char_count // 4)
            added_predicates = [entry.predicate for entry in diff.added]

            print(f"scope={scope}")
            print(f"session_id={session_id}")
            print(f"optimized_entries={ctx.total_facts_included}")
            print(f"context_window_size={window.window_size}")
            print(f"approx_tokens={approx_tokens}")
            print(f"diff_added={len(diff.added)}")
            print(f"diff_added_predicates={added_predicates}")

            if ctx.total_facts_included < 4:
                raise RuntimeError("Expected ingest_and_optimize to return the seeded facts.")
            if window.window_size < 4:
                raise RuntimeError("Expected context_window to return the scoped facts.")
            if "renewal_due" not in added_predicates:
                raise RuntimeError("Expected diff_context to include the newly asserted fact.")
        finally:
            client.clear_context_session(session_id)
            for predicate, args in cleanup_facts:
                with contextlib.suppress(Exception):
                    client.retract(predicate, args, scope=scope)


if __name__ == "__main__":
    start = time.time()
    main()
    print(f"status=ok elapsed_ms={int((time.time() - start) * 1000)}")
