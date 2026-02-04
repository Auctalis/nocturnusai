import requests
import sys
import time
import json

API_URL = "http://localhost:9300"

def assert_fact(predicate, args, truth_val=True):
    val_str = "" if truth_val else "NOT "
    print(f">> Asserting Fact: {val_str}{predicate}({', '.join(map(str, args))})")
    resp = requests.post(f"{API_URL}/assert/fact", json={
        "predicate": predicate,
        "args": args,
        "truthVal": truth_val
    })
    print(f"Response: {resp.status_code} - {resp.text}")
    return resp

def assert_rule(head_pred, head_args, body_pred, body_args):
    # API RuleRequest does NOT support truthVal in Head/Body atoms currently.
    # We must restrict to positive literals.
    print(f">> Asserting Rule: {head_pred}({head_args}) <- {body_pred}({body_args})")
    
    resp = requests.post(f"{API_URL}/assert/rule", json={
        "head": {"predicate": head_pred, "args": head_args},
        "body": [{"predicate": body_pred, "args": body_args}]
    })
    print(f"Response: {resp.text}")

def query(predicate, args):
    print(f">> Querying: {predicate}({args})")
    response = requests.post(f"{API_URL}/infer", json={
        "predicate": predicate,
        "args": args
    })
    results = response.json()
    print(f"Result: {results}")
    return results

def test_syllogism():
    print("\n--- TEST: Syllogism (Modus Ponens) ---")
    # All Men are Mortal: Mortal(x) <- Man(x)
    assert_rule("Mortal", ["?x"], "Man", ["?x"])
    
    # Socrates is a Man
    assert_fact("Man", ["Socrates"])
    
    # Check if Socrates is Mortal
    res = query("Mortal", ["Socrates"])
    # Result format: ["Mortal(Socrates)"] (String list) or JSON objects?
    # Application.kt says: results.map { it.toString() } -> List<String>
    
    if any("Mortal(Socrates)" in str(r) for r in res):
        print("PASS: Socrates is Mortal")
    else:
        print(f"FAIL: Socrates is NOT Mortal. Got: {res}")

def test_modus_tollens_workaround():
    print("\n--- TEST: Modus Tollens (via Semantic Negation) ---")
    # Native support for "NOT Man(x) <- NOT Mortal(x)" is missing in API.
    # Workaround: Use "NonMortal" and "NonMan" predicates.
    # NonMan(x) <- NonMortal(x)
    
    assert_rule("NonMan", ["?x"], "NonMortal", ["?x"])
    
    # Assert Zeus is NonMortal (equivalent to NOT Mortal)
    assert_fact("NonMortal", ["Zeus"])
    
    # Query NonMan(Zeus)
    res = query("NonMan", ["Zeus"])
    
    if any("NonMan(Zeus)" in str(r) for r in res):
        print("PASS: NonMan(Zeus) inferred (Modus Tollens simulated).")
    else:
        print(f"FAIL: NonMan(Zeus) NOT inferred. Got: {res}")

def test_tms_retraction():
    print("\n--- TEST: TMS Retraction (Modus Ponens Reversal) ---")
    # If we retract Man(Socrates), Mortal(Socrates) should disappear?
    # This requires Layer 5 (TMS) to be active.
    
    assert_fact("Man", ["Socrates"], truth_val=False) # Helper uses behavior of retract? 
    # Actually Application.kt /retract is separate endpoint.
    
    print(">> Retracting Man(Socrates)")
    requests.post(f"{API_URL}/retract", json={"predicate": "Man", "args": ["Socrates"]})
    
    res = query("Mortal", ["Socrates"])
    if not res:
        print("PASS: Mortal(Socrates) retracted.")
    else:
        print(f"FAIL: Mortal(Socrates) still exists. {res}")

if __name__ == "__main__":
    test_syllogism()
    test_modus_tollens_workaround()
    test_tms_retraction()
