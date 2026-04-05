"""
06_dsl_execute.py — Logiql DSL execution via /execute

Demonstrates:
  - execute() — run raw Logiql DSL commands
  - predicates() — schema/fact-count discovery
  - Asserting facts and rules directly through DSL syntax

DSL syntax reference:
  - Keywords are UPPERCASE: ASSERT, INFER, RESTRICT, EXPLAIN
  - Statements end with semicolons: ASSERT fact(args);
  - Variables use ? prefix: ?x, ?who
  - Rules use FORALL: ASSERT FORALL ?x { head(?x) <- body(?x) };
  - Queries use INFER: INFER predicate(?var);
  - The DSL has no retract command — use the SDK's retract() method
"""

from nocturnusai import SyncNocturnusAIClient

SERVER = "http://localhost:9300"


def main():
    with SyncNocturnusAIClient(SERVER, database="demo-dsl") as client:
        client.ensure_database()

        print("=== 1. Assert facts via DSL ===")
        commands = [
            "ASSERT human(socrates);",
            "ASSERT human(aristotle);",
            "ASSERT human(plato);",
            "ASSERT likes(socrates, philosophy);",
            "ASSERT likes(plato, mathematics);",
            "ASSERT likes(aristotle, biology);",
        ]
        for cmd in commands:
            result = client.execute(cmd)
            print(f"  > {cmd}")
            if result:
                print(f"    {result.strip()}")

        print("\n=== 2. Assert a rule via DSL ===")
        rule_cmd = "ASSERT FORALL ?x { mortal(?x) <- human(?x) };"
        result = client.execute(rule_cmd)
        print(f"  > {rule_cmd}")
        if result:
            print(f"    {result.strip()}")

        print("\n=== 3. Infer via DSL ===")
        infer_cmd = "INFER mortal(?who);"
        result = client.execute(infer_cmd)
        print(f"  > {infer_cmd}")
        print(f"    {result.strip()}")

        print("\n=== 4. Retract via SDK (DSL has no retract) ===")
        client.retract("human", ["aristotle"])
        print("  Retracted human(aristotle) via SDK")

        check = client.execute("INFER human(?x);")
        print(f"  > INFER human(?x);")
        print(f"    {check.strip()}")

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
