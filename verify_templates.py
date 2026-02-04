import requests
import sys
import time
import json

API_URL = "http://localhost:9300"

def assert_template(template_type, predicates, args):
    print(f">> Asserting Template: {template_type} with {predicates}")
    resp = requests.post(f"{API_URL}/assert/template", json={
        "type": template_type,
        "predicates": predicates,
        "args": args
    })
    print(f"Response: {resp.status_code} - {resp.text}")
    return resp

def assert_fact(predicate, args, negated=False):
    prefix = "NOT " if negated else ""
    print(f">> Asserting Fact: {prefix}{predicate}({', '.join(args)})")
    resp = requests.post(f"{API_URL}/assert/fact", json={
        "predicate": predicate,
        "args": args,
        "negated": negated
    })
    return resp

def query(predicate, args, negated=False):
    response = requests.post(f"{API_URL}/infer", json={
        "predicate": predicate,
        "args": args,
        "negated": negated
    })
    return response.json()

def test_syllogism_template():
    print("\n--- TEST: Syllogism Template ---")
    # All Greeks are Human: Human(x) <- Greek(x)
    assert_template("SYLLOGISM", {"P": "Greek", "Q": "Human"}, ["x"])
    
    assert_fact("Greek", ["Plato"])
    
    res = query("Human", ["Plato"])
    if any("Human(Plato)" in str(r) for r in res):
        print("PASS: Plato is Human (Syllogism)")
    else:
        print(f"FAIL: Plato is NOT Human. Got: {res}")

def test_modus_tollens_template():
    print("\n--- TEST: Modus Tollens Template ---")
    # Logic: If Raining(x) -> Wet(x). 
    # With Modus Tollens template, we also get NOT Raining(x) <- NOT Wet(x).
    assert_template("MODUS_TOLLENS", {"P": "Raining", "Q": "Wet"}, ["y"])
    
    # Assert NOT Wet(London)
    assert_fact("Wet", ["London"], negated=True)
    
    # Query NOT Raining(London)
    res = query("Raining", ["London"], negated=True)
    if any("NOT Raining(London)" in str(r) for r in res):
        print("PASS: It is NOT Raining in London (Modus Tollens)")
    else:
        print(f"FAIL: It might be raining. Got: {res}")

if __name__ == "__main__":
    test_syllogism_template()
    test_modus_tollens_template()

def test_advanced_templates():
    print("\n--- TEST: Disjunctive Syllogism ---")
    # P or Q. NOT P -> Q.
    assert_template("DISJUNCTIVE_SYLLOGISM", {"P": "Sunny", "Q": "Cloudy"}, ["d"])
    assert_fact("Sunny", ["Today"], negated=True) # NOT Sunny(Today)
    res = query("Cloudy", ["Today"]) # Should be true via rule Q <- NOT P
    if any("Cloudy(Today)" in str(r) for r in res):
         print("PASS: Cloudy(Today) inferred from NOT Sunny(Today)")
    else:
         print(f"FAIL: Cloudy(Today) NOT found. Got: {res}")

    print("\n--- TEST: Constructive Dilemma ---")
    # P -> R, Q -> S, (P or Q implies R or S logic)
    # Rules generated: R<-P, S<-Q, Q<-!P, P<-!Q
    assert_template("CONSTRUCTIVE_DILEMMA", {"P": "Study", "Q": "Cheats", "R": "Pass", "S": "Expelled"}, ["std"])
    # Scenario: Student didn't study (!Study).
    # Logic: !Study -> Cheats (from Disjunction). Cheats -> Expelled.
    assert_fact("Study", ["Tom"], negated=True)
    res = query("Expelled", ["Tom"])
    if any("Expelled(Tom)" in str(r) for r in res):
        print("PASS: Expelled(Tom) inferred from NOT Study(Tom)")
    else:
        print(f"FAIL: Expelled(Tom) NOT found. Got: {res}")

    print("\n--- TEST: Practical Argument ---")
    # Guilty <- Fingerprints, NOT Forged.
    assert_template("PRACTICAL_ARGUMENT", {"CONCLUSION": "Guilty", "EVIDENCE": "Fingerprints", "EXCEPTION": "Forged"}, ["s"])
    assert_fact("Fingerprints", ["Suspect1"])
    assert_fact("Forged", ["Suspect1"], negated=True) # Assert NOT Forged explicit
    res = query("Guilty", ["Suspect1"])
    if any("Guilty(Suspect1)" in str(r) for r in res):
        print("PASS: Guilty(Suspect1) inferred")
    else:
        print(f"FAIL: Guilty NOT found. Got: {res}")

test_advanced_templates()
