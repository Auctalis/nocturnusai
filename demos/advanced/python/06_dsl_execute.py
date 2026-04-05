"""
06_dsl_execute.py — Logiql DSL execution via /execute

Demonstrates:
  - execute() — run raw Logiql DSL commands
  - predicates() — schema/fact-count discovery
  - Asserting facts and rules directly through DSL syntax
"""

from nocturnusai import SyncNocturnusAIClient

SERVER = "http://localhost:9300"


def main():
    with SyncNocturnusAIClient(SERVER, database="demo-dsl") as client:
        client.ensure_database()

        print("=== 1. Assert facts via DSL ===")
        commands = [
            "assert human(socrates)",
            "assert human(aristotle)",
            "assert human(plato)",
            "assert likes(socrates, philosophy)",
            "assert likes(plato, mathematics)",
            "assert likes(aristotle, biology)",
        ]
        for cmd in commands:
            result = client.execute(cmd)
            print(f"  > {cmd}")
            if result:
                print(f"    {result.strip()}")

        print("\n=== 2. Assert a rule via DSL ===")
        rule_cmd = "assert mortal(?x) :- human(?x)"
        result = client.execute(rule_cmd)
        print(f"  > {rule_cmd}")
        if result:
            print(f"    {result.strip()}")

        print("\n=== 3. Query via DSL ===")
        query_cmd = "query mortal(?who)"
        result = client.execute(query_cmd)
        print(f"  > {query_cmd}")
        print(f"    {result.strip()}")

        print("\n=== 4. Retract via DSL ===")
        retract_cmd = "retract human(aristotle)"
        result = client.execute(retract_cmd)
        print(f"  > {retract_cmd}")

        check = client.execute("query human(?x)")
        print(f"  Humans after retract: {check.strip()}")

        print("\n=== 5. Schema discovery ===")
        schema = client.predicates()
        print(f"  {schema.get('totalPredicates', 0)} predicate(s), "
              f"{schema.get('totalFacts', 0)} fact(s), "
              f"{schema.get('totalRules', 0)} rule(s):")
        for p in schema.get("predicates", []):
            print(f"    {p['predicate']}/{p['arity']}  "
                  f"facts={p['factCount']}  rules={p['ruleCount']}")

        print("\nDone.")


if __name__ == "__main__":
    main()
