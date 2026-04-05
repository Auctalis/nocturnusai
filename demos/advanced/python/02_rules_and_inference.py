"""
02_rules_and_inference.py — Define Horn clause rules and run backward-chaining inference

Demonstrates:
  - assert_rule()
  - infer() with and without proof trees
  - Variable syntax (?x, ?y, ?z)
  - Multi-hop reasoning
"""

from nocturnusai import SyncNocturnusAIClient

SERVER = "http://localhost:9300"


def main():
    with SyncNocturnusAIClient(SERVER, database="demo-rules") as client:
        client.ensure_database()

        print("=== 1. Classic Syllogism ===")
        # All humans are mortal
        client.assert_rule(
            head={"predicate": "mortal", "args": ["?x"]},
            body=[{"predicate": "human", "args": ["?x"]}],
        )
        client.assert_fact("human", ["socrates"])
        client.assert_fact("human", ["plato"])

        mortals = client.infer("mortal", ["?who"])
        print("mortal(?who) →")
        for a in mortals:
            print(f"  mortal({', '.join(a.args)})")

        print("\n=== 2. Multi-hop: grandparent rule ===")
        client.assert_rule(
            head={"predicate": "grandparent", "args": ["?x", "?z"]},
            body=[
                {"predicate": "parent", "args": ["?x", "?y"]},
                {"predicate": "parent", "args": ["?y", "?z"]},
            ],
        )
        client.assert_fact("parent", ["alice", "bob"])
        client.assert_fact("parent", ["bob", "charlie"])
        client.assert_fact("parent", ["bob", "diana"])

        grandparents = client.infer("grandparent", ["alice", "?grandchild"])
        print("grandparent(alice, ?grandchild) →")
        for a in grandparents:
            print(f"  grandparent({', '.join(a.args)})")

        print("\n=== 3. Ancestor rule (transitive closure) ===")
        # ancestor base case
        client.assert_rule(
            head={"predicate": "ancestor", "args": ["?x", "?y"]},
            body=[{"predicate": "parent", "args": ["?x", "?y"]}],
        )
        # ancestor recursive case
        client.assert_rule(
            head={"predicate": "ancestor", "args": ["?x", "?z"]},
            body=[
                {"predicate": "parent", "args": ["?x", "?y"]},
                {"predicate": "ancestor", "args": ["?y", "?z"]},
            ],
        )

        ancestors = client.infer("ancestor", ["alice", "?desc"])
        print("ancestor(alice, ?desc) →")
        for a in ancestors:
            print(f"  ancestor({', '.join(a.args)})")

        print("\n=== 4. Proof trees ===")
        proofs = client.infer("mortal", ["socrates"], with_proof=True)
        print("Proof that mortal(socrates):")
        for proof in proofs:
            _print_proof(proof, indent=2)

        print("\nDone.")


def _print_proof(node, indent=0):
    pad = " " * indent
    if hasattr(node, "atom"):
        a = node.atom
        print(f"{pad}{a.predicate}({', '.join(a.args)})")
        if hasattr(node, "children") and node.children:
            for child in node.children:
                _print_proof(child, indent + 2)
    elif isinstance(node, dict):
        pred = node.get("predicate", "?")
        args = node.get("args", [])
        print(f"{pad}{pred}({', '.join(args)})")
        for child in node.get("children", []):
            _print_proof(child, indent + 2)


if __name__ == "__main__":
    main()
