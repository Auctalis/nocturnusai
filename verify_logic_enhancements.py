import requests
import sys
import time
import json

API_URL = "http://localhost:9300"

def assert_fact(predicate, args, negated=False):
    prefix = "NOT " if negated else ""
    print(f">> Asserting Fact: {prefix}{predicate}({', '.join(args)})")
    resp = requests.post(f"{API_URL}/assert/fact", json={
        "predicate": predicate,
        "args": args,
        "negated": negated
    })
    print(f"Response: {resp.status_code} - {resp.text}")
    return resp

def assert_rule(head_pred, head_args, body_pred, body_args, head_negated=False, body_negated=False):
    h_prefix = "NOT " if head_negated else ""
    b_prefix = "NOT " if body_negated else ""
    print(f">> Asserting Rule: {h_prefix}{head_pred}({', '.join(head_args)}) <- {b_prefix}{body_pred}({', '.join(body_args)})")
    
    resp = requests.post(f"{API_URL}/assert/rule", json={
        "head": {"predicate": head_pred, "args": head_args, "negated": head_negated},
        "body": [{"predicate": body_pred, "args": body_args, "negated": body_negated}]
    })
    print(f"Response: {resp.status_code} - {resp.text}")

def query(predicate, args, negated=False):
    prefix = "NOT " if negated else ""
    print(f">> Querying: {prefix}{predicate}({args})")
    # Query logic in `infer` uses `AtomDto` which now has `negated`.
    # Let's see if backward chaining supports negative goals.
    response = requests.post(f"{API_URL}/infer", json={
        "predicate": predicate,
        "args": args,
        "negated": negated
    })
    results = response.json()
    print(f"Result: {results}")
    return results

def test_native_negation():
    print("\n--- TEST: Explicit Native Negation (Storage) ---")
    # Assert NOT Man(Superman)
    assert_fact("Man", ["Superman"], negated=True)
    
    # Query NOT Man(Superman)
    res = query("Man", ["Superman"], negated=True)
    
    # Expected: ["NOT Man(Superman)"] or clean JSON
    if any("NOT Man(Superman)" in str(r) for r in res):
        print("PASS: NOT Man(Superman) stored and retrieved.")
    else:
        print(f"FAIL: NOT Man(Superman) NOT found. Got: {res}")

def test_native_modus_tollens():
    print("\n--- TEST: Native Modus Tollens (Negative Rules) ---")
    # Rule: NOT Man(?x) <- NOT Mortal(?x)
    assert_rule("Man", ["?x"], "Mortal", ["?x"], head_negated=True, body_negated=True)
    
    # Fact: NOT Mortal(Zeus)
    assert_fact("Mortal", ["Zeus"], negated=True)
    
    # Query: NOT Man(Zeus)
    res = query("Man", ["Zeus"], negated=True)
    if any("NOT Man(Zeus)" in str(r) for r in res):
        print("PASS: NOT Man(Zeus) inferred via Negative Rule.")
    else:
        print(f"FAIL: NOT Man(Zeus) NOT inferred. Got: {res}")

def test_conflict_safety():
    print("\n--- TEST: Unification Safety (Positive vs Negative) ---")
    # Fact: Dog(Fido)
    assert_fact("Dog", ["Fido"], negated=False)
    
    # Query: NOT Dog(Fido) -> Should be Empty
    res = query("Dog", ["Fido"], negated=True)
    if not res:
        print("PASS: Dog(Fido) did not satisfy NOT Dog(Fido).")
    else:
        print(f"FAIL: False positive match! Got: {res}")

if __name__ == "__main__":
    test_native_negation()
    test_native_modus_tollens()
    test_conflict_safety()
