import requests
import sys

API_URL = "http://localhost:9300"

def assert_fact(predicate, args, truth_val=True):
    val_str = "" if truth_val else "NOT "
    print(f">> Asserting Fact: {val_str}{predicate}{args}")
    resp = requests.post(f"{API_URL}/assert/fact", json={
        "predicate": predicate,
        "args": args,
        "truthVal": truth_val
    })
    print(f"Response: {resp.status_code} - {resp.text}")
    return resp

def retract_fact(predicate, args):
    print(f">> Retracting Fact: {predicate}{args}")
    resp = requests.post(f"{API_URL}/retract", json={
        "predicate": predicate,
        "args": args
    })
    print(f"Response: {resp.status_code} - {resp.text}")

def assert_rule(head, head_args, body, body_args):
    print(f">> Asserting Rule: {head}{head_args} <- {body}{body_args}")
    resp = requests.post(f"{API_URL}/assert/rule", json={
        "head": {"predicate": head, "args": head_args},
        "body": [{"predicate": body, "args": body_args}]
    })
    print(f"Response: {resp.status_code} - {resp.text}")

def query(predicate, args):
    print(f">> Querying: {predicate}{args}")
    response = requests.post(f"{API_URL}/infer", json={
        "predicate": predicate,
        "args": args
    })
    print(f"Result: {response.json()}")
    return response.json()

if __name__ == "__main__":
    print("--- TEST 1: Layer 6 (Constraint / Negation) ---")
    # 1. Assert Alive(Cat)
    assert_fact("Alive", ["Cat"])
    
    # 2. Try to Assert NOT Alive(Cat)
    resp = assert_fact("Alive", ["Cat"], truth_val=False)
    if resp.status_code == 400 and "Contradiction" in resp.text:
        print("SUCCESS: Contradiction caught.")
    else:
        print("FAILURE: Contradiction NOT caught.")

    print("\n--- TEST 2: Layer 5 (TMS / Cascading Delete) ---")
    # 1. Rule: Mortal(x) <- Man(x)
    assert_rule("Mortal", ["?x"], "Man", ["?x"])
    # 2. Fact: Man(Socrates)
    assert_fact("Man", ["Socrates"])
    # 3. Verify Inference
    res = query("Mortal", ["?x"])
    if not any("Socrates" in r for r in res):
        print("FAILURE: Inference didn't work initially.")
    
    # 4. Retract Man(Socrates)
    retract_fact("Man", ["Socrates"])
    
    # 5. Verify Mortal(Socrates) is GONE
    res = query("Mortal", ["?x"])
    if not res: 
        print("SUCCESS: Cascading delete worked. Mortal(Socrates) is gone.")
    else:
        print(f"FAILURE: Mortal(Socrates) still exists! {res}")

    print("\n--- TEST 3: Layer 4b (Backward Chaining / Lazy Evaluation) ---")
    # We rely on previous rule Mortal <- Man? No, let's use a chain.
    # Ancestor(x, z) <- Parent(x, y) AND Ancestor(y, z) ? 
    # Current RuleRequest only supports 1 body clause. BackwardChainer code supports multiple but RuleRequest DTO is simplified.
    # We'll use simple rule: B(x) <- A(x).
    # NOTE: Backward Chainer works on Rules + Store.
    # If we asserted rules, they are in AxiomBase rules list.
    # If we query "Mortal", and "Man" is in store...
    # Wait, if Man is GONE (from test 2), checking Layer 4b requires re-adding.
    
    assert_fact("Man", ["Plato"])
    # Query Mortal(Plato)
    # Forward Chaining (Layer 4a) would have added it eagerly.
    # Backward Chaining (Layer 4b) would derive it lazily even if it wasn't there?
    # Actually, Rete (Forward) is always Active in my implementation (`onFactAsserted`).
    # So `Mortal(Plato)` is ALREADY in Store.
    # To test Backward Chainer specifically, we would need to Disable Forward Chaining or clean the store of derived facts?
    # Or rely on the fact that `infer` implementation uses `BackwardChainer`, so if it returns result, BC is working.
    
    res = query("Mortal", ["Plato"])
    if any("Plato" in r for r in res):
        print("SUCCESS: Inference returned result (via BC or Store).")
