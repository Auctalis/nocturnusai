import requests
import sys
import time

API_URL = "http://localhost:9300"

def wait_for_server():
    print("Waiting for server to be ready...")
    for i in range(10):
        try:
            r = requests.get(f"{API_URL}/health")
            if r.status_code == 200:
                print("Server is ready!")
                return
        except:
            pass
        time.sleep(1)
    print("Server failed to allow connection.")
    sys.exit(1)

def assert_fact(predicate, args):
    print(f"Asserting Fact: {predicate}{args}")
    resp = requests.post(f"{API_URL}/assert/fact", json={
        "predicate": predicate,
        "args": args
    })
    print(f"Response: {resp.status_code} - {resp.text}")

def assert_rule(head, head_args, body, body_args):
    print(f"Asserting Rule: {head}{head_args} <- {body}{body_args}")
    resp = requests.post(f"{API_URL}/assert/rule", json={
        "headPred": head, "headArgs": head_args,
        "bodyPred": body, "bodyArgs": body_args
    })
    print(f"Response: {resp.status_code} - {resp.text}")

def query(predicate, args):
    print(f"Querying: {predicate}{args}")
    response = requests.post(f"{API_URL}/infer", json={
        "predicate": predicate,
        "args": args
    })
    print(f"Result: {response.json()}")
    return response.json()

if __name__ == "__main__":
    wait_for_server()

    # --- SCENARIO ---

    # 1. Teach the database a Rule (Syllogism)
    # "If x is a Man, then x is Mortal"
    # Rule(Atom("Mortal", [?x]), [Atom("Man", [?x])])
    assert_rule("Mortal", ["?x"], "Man", ["?x"])

    # 2. Add a Fact
    # "Socrates is a Man"
    assert_fact("Man", ["Socrates"])

    # 3. Query the derived truth
    # "Who is Mortal?"
    results = query("Mortal", ["?who"])
    
    expected = "Mortal(Socrates)"
    if any(expected in r for r in results):
        print("SUCCESS: LogiQL deduced that Socrates is Mortal.")
    else:
        print("FAILURE: Did not find expected result.")
