"""
04_transactions.py — ACID transactions: commit and rollback

Demonstrates:
  - Manual transaction lifecycle (begin → operations → commit)
  - Rollback on failure
  - Isolation: changes not visible until commit (illustrative)
"""

from nocturnusai import SyncNocturnusAIClient
from nocturnusai.exceptions import NocturnusAIError

SERVER = "http://localhost:9300"


def main():
    with SyncNocturnusAIClient(SERVER, database="demo-txn") as client:

        print("=== 1. Successful transaction (commit) ===")
        tx_id = client.begin_transaction()
        print(f"  Started transaction: {tx_id}")

        client.assert_fact("account", ["alice", "1000"], transaction_id=tx_id)
        client.assert_fact("account", ["bob", "500"], transaction_id=tx_id)
        client.commit_transaction(tx_id)
        print("  Committed: account(alice,1000), account(bob,500)")

        accounts = client.query("account", ["?name", "?balance"])
        print(f"  Visible after commit: {len(accounts)} account(s)")
        for a in accounts:
            print(f"    account({', '.join(a.args)})")

        print("\n=== 2. Failed transaction (rollback) ===")
        tx_id2 = client.begin_transaction()
        print(f"  Started transaction: {tx_id2}")

        # Perform writes inside transaction
        client.assert_fact("account", ["charlie", "9999"], transaction_id=tx_id2)

        # Simulate detecting an error before commit
        try:
            raise ValueError("Validation failed — rolling back")
        except ValueError as e:
            print(f"  Error detected: {e}")
            client.rollback_transaction(tx_id2)
            print("  Rolled back transaction")

        charlie = client.query("account", ["charlie", "?bal"])
        print(f"  account(charlie,...) after rollback: {len(charlie)} result(s) (expected 0)")

        print("\n=== 3. Retract inside a transaction ===")
        tx_id3 = client.begin_transaction()
        client.retract("account", ["bob", "500"], transaction_id=tx_id3)
        client.commit_transaction(tx_id3)
        print("  Committed retraction of account(bob,500)")

        accounts_after = client.query("account", ["?name", "?balance"])
        print(f"  Remaining accounts: {len(accounts_after)}")
        for a in accounts_after:
            print(f"    account({', '.join(a.args)})")

        print("\nDone.")


if __name__ == "__main__":
    main()
