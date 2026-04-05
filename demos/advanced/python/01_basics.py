"""
01_basics.py — Assert facts, query, and retract

Demonstrates:
  - SyncNocturnusAIClient (no async needed for simple scripts)
  - assert_fact()
  - query()
  - infer()
  - retract()
"""

from nocturnusai import SyncNocturnusAIClient

SERVER = "http://localhost:9300"


def main():
    with SyncNocturnusAIClient(SERVER, database="demo-basics") as client:
        client.ensure_database()

        print("=== 1. Assert Facts ===")
        client.assert_fact("human", ["socrates"])
        client.assert_fact("human", ["plato"])
        client.assert_fact("human", ["aristotle"])
        client.assert_fact("teacher", ["socrates", "plato"])
        client.assert_fact("teacher", ["plato", "aristotle"])
        print("Asserted: human(socrates), human(plato), human(aristotle)")
        print("Asserted: teacher(socrates, plato), teacher(plato, aristotle)")

        print("\n=== 2. Query (exact pattern match) ===")
        humans = client.query("human", ["?x"])
        for atom in humans:
            print(f"  {atom.predicate}({', '.join(atom.args)})")

        print("\n=== 3. Query with bound argument ===")
        students_of_socrates = client.query("teacher", ["socrates", "?who"])
        for atom in students_of_socrates:
            print(f"  {atom.predicate}({', '.join(atom.args)})")

        print("\n=== 4. Infer (backward chaining — no rules yet, same as query) ===")
        results = client.infer("human", ["?x"])
        print(f"  Inferred {len(results)} human(s)")

        print("\n=== 5. Retract a fact ===")
        client.retract("teacher", ["socrates", "plato"])
        remaining = client.query("teacher", ["?x", "?y"])
        print(f"  After retraction, {len(remaining)} teacher relationship(s) remain")
        for atom in remaining:
            print(f"  {atom.predicate}({', '.join(atom.args)})")

        print("\n=== 6. Negated fact ===")
        client.assert_fact("mortal", ["gods"], negated=True)
        neg_results = client.query("mortal", ["gods"])
        if neg_results:
            a = neg_results[0]
            print(f"  mortal(gods) negated={a.negated}")

        print("\nDone.")


if __name__ == "__main__":
    main()
