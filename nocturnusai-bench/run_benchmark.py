import os, json, time, datetime, urllib.request, urllib.error
import matplotlib; matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.ticker as mticker

ANTHROPIC_KEY = os.environ["ANTHROPIC_API_KEY"]
GOOGLE_KEY    = os.environ["GOOGLE_API_KEY"]
BASE          = os.environ.get("NOCTURNUS_URL", "http://localhost:9300")

SYSTEM = (
    "You are a helpful product support agent for NocturnusAI. "
    "Be concise. Ask clarifying questions when needed."
)

TURNS = [
    ("Hi, I'm having trouble with the API. Requests timeout after 30 seconds.",
     [("issue_type","api_timeout"),("timeout_threshold","30s")]),
    ("I'm on the Pro plan. We have about 500 concurrent users.",
     [("account_plan","pro"),("concurrent_users","500")]),
    ("We're using the Python SDK, version 0.3.8.",
     [("sdk_language","python"),("sdk_version","0.3.8")]),
    ("Errors started two days ago after we scaled to 500 users.",
     [("issue_started","2_days_ago"),("scale_trigger","500_users")]),
    ("We run on AWS EC2, t3.medium instances behind an ALB.",
     [("infra_provider","aws"),("instance_type","t3_medium"),("load_balancer","alb")]),
    ("The ALB timeout is 60 seconds. Should I increase it?",
     [("alb_timeout_config","60s"),("user_question_1","increase_alb_timeout")]),
    ("Logs show: 'Connection pool exhausted after 30000ms'. About 5% of requests.",
     [("error_message","connection_pool_exhausted"),("error_rate_pct","5"),("root_cause_candidate","pool_exhaustion")]),
    ("We have max_connections=10 in our NocturnusAI client config.",
     [("client_max_connections","10"),("config_issue_candidate","low_pool_size")]),
    ("How many connections should we set for 500 concurrent users?",
     [("user_question_2","recommended_pool_size")]),
    ("I updated max_connections to 100. Less timeouts but still some.",
     [("config_change","max_connections_100"),("partial_improvement","true")]),
    ("Remaining timeouts are on the /infer endpoint specifically.",
     [("affected_endpoint","infer"),("issue_narrowed","true")]),
    ("Our rules are complex — some have 8-10 body atoms with variables.",
     [("rule_complexity","high"),("body_atoms_count","8_to_10")]),
    ("We have about 50,000 facts in the knowledge base.",
     [("knowledge_base_size","50000_facts")]),
    ("How can I set a per-query timeout to avoid blocking the pool?",
     [("user_question_3","per_query_timeout")]),
    ("Can you summarize the issue and fixes we've applied so far?",
     []),
]

TENANT = f"bench_{datetime.datetime.now().strftime('%H%M%S')}"

def _post_raw(path, body_bytes, headers):
    req = urllib.request.Request(f"{BASE}{path}", data=body_bytes, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req) as r:
            return r.read()
    except urllib.error.HTTPError as e:
        return e.read()

def setup_tenant():
    _post_raw("/admin/databases/default/tenants",
              json.dumps({"tenantId": TENANT}).encode(),
              {"Content-Type": "application/json"})
    print(f"  Tenant: {TENANT}")

stored_predicates = []  # grows as we assert facts

def assert_fact(predicate, value):
    stored_predicates.append(predicate)
    body = json.dumps({"predicate": predicate, "args": ["user", value]}).encode()
    _post_raw("/assert/fact", body,
              {"Content-Type": "application/json", "X-Tenant-ID": TENANT})

def get_all_facts():
    atoms = []
    for pred in set(stored_predicates):
        body = json.dumps({"predicate": pred, "args": ["user", "?val"]}).encode()
        raw = _post_raw("/query", body,
                        {"Content-Type": "application/json", "X-Tenant-ID": TENANT})
        try:
            atoms.extend(json.loads(raw))
        except Exception:
            pass
    return atoms

def facts_context(atoms):
    if not atoms:
        return ""
    lines = ["Facts about this user:"]
    for a in atoms:
        lines.append(f"  {a['predicate']}={a['args'][-1]}")
    return "\n".join(lines)

# ── Claude ─────────────────────────────────────────────────────────────────────
import anthropic
cl = anthropic.Anthropic(api_key=ANTHROPIC_KEY)
MODEL = "claude-opus-4-6"

def claude_naive():
    print("\n[Claude Opus 4 — naive] Full history replay:")
    tokens, history = [], []
    for i, (msg, _) in enumerate(TURNS):
        history.append({"role": "user", "content": msg})
        r = cl.messages.create(model=MODEL, max_tokens=150, system=SYSTEM, messages=history)
        reply = next((b.text for b in r.content if hasattr(b,"text")), "")
        history.append({"role": "assistant", "content": reply})
        t = r.usage.input_tokens
        tokens.append(t)
        print(f"  T{i+1:02d}: {t:>5,} tokens")
        time.sleep(0.35)
    print(f"  Σ {sum(tokens):,}  avg {sum(tokens)//len(tokens):,}")
    return tokens

def claude_nocturnus():
    global stored_predicates; stored_predicates = []
    print("\n[Claude Opus 4 — NocturnusAI] Selective retrieval:")
    tokens = []
    for i, (msg, facts) in enumerate(TURNS):
        for pred, val in facts:
            assert_fact(pred, val)
        atoms = get_all_facts()
        ctx   = facts_context(atoms)
        body  = (f"{ctx}\n\nUser: {msg}" if ctx else f"User: {msg}")
        r = cl.messages.create(model=MODEL, max_tokens=150, system=SYSTEM,
                               messages=[{"role":"user","content":body}])
        t = r.usage.input_tokens
        tokens.append(t)
        print(f"  T{i+1:02d}: {t:>5,} tokens  ({len(atoms)} facts)")
        time.sleep(0.35)
    print(f"  Σ {sum(tokens):,}  avg {sum(tokens)//len(tokens):,}")
    return tokens

# ── Gemini ─────────────────────────────────────────────────────────────────────
from google import genai as gai
gc = gai.Client(api_key=GOOGLE_KEY)
GM = "gemini-2.0-flash"

def gtokens(text):
    return gc.models.count_tokens(model=GM, contents=text).total_tokens

def gemini_naive():
    print("\n[Gemini 2.0 Flash — naive] Full history replay:")
    tokens, hist = [], ""
    for i, (msg, _) in enumerate(TURNS):
        prompt = SYSTEM + hist + f"\nUser: {msg}\nAssistant:"
        t = gtokens(prompt)
        r = gc.models.generate_content(model=GM, contents=prompt)
        hist += f"\nUser: {msg}\nAssistant: {r.text}"
        tokens.append(t)
        print(f"  T{i+1:02d}: {t:>5,} tokens")
        time.sleep(0.25)
    print(f"  Σ {sum(tokens):,}  avg {sum(tokens)//len(tokens):,}")
    return tokens

TENANT2 = ""
stored2  = []

def gemini_nocturnus():
    global TENANT2, stored2
    TENANT2 = TENANT + "_g"
    stored2 = []
    _post_raw("/admin/databases/default/tenants",
              json.dumps({"tenantId": TENANT2}).encode(),
              {"Content-Type": "application/json"})
    print("\n[Gemini 2.0 Flash — NocturnusAI] Selective retrieval:")
    tokens = []
    for i, (msg, facts) in enumerate(TURNS):
        for pred, val in facts:
            stored2.append(pred)
            _post_raw("/assert/fact",
                      json.dumps({"predicate": pred, "args": ["user", val]}).encode(),
                      {"Content-Type": "application/json", "X-Tenant-ID": TENANT2})
        atoms = []
        for pred in set(stored2):
            raw = _post_raw("/query",
                            json.dumps({"predicate": pred, "args": ["user","?val"]}).encode(),
                            {"Content-Type": "application/json", "X-Tenant-ID": TENANT2})
            try: atoms.extend(json.loads(raw))
            except: pass
        ctx    = facts_context(atoms)
        prompt = SYSTEM + (f"\n\n{ctx}" if ctx else "") + f"\n\nUser: {msg}\nAssistant:"
        t = gtokens(prompt)
        gc.models.generate_content(model=GM, contents=prompt)
        tokens.append(t)
        print(f"  T{i+1:02d}: {t:>5,} tokens  ({len(atoms)} facts)")
        time.sleep(0.25)
    print(f"  Σ {sum(tokens):,}  avg {sum(tokens)//len(tokens):,}")
    return tokens

def main():
    print("=" * 60)
    print("NocturnusAI Live Benchmark: 15-turn support conversation")
    print("=" * 60)
    setup_tenant()

    cn = claude_naive()
    cnn = claude_nocturnus()
    gn = gemini_naive()
    gnn = gemini_nocturnus()

    # Summary
    print("\n" + "=" * 60)
    print("MEASURED RESULTS")
    print("=" * 60)

    SCALE = 50_000
    for name, naive, noct, price in [
        ("Claude Opus 4 ($15/1M)",    cn,  cnn, 15.00),
        ("Gemini 2.0 Flash ($0.10/1M)", gn, gnn,  0.10),
    ]:
        avg_n  = sum(naive) / len(naive)
        avg_nn = sum(noct)  / len(noct)
        ratio  = avg_n / avg_nn
        cost_n  = SCALE * avg_n  * price / 1_000_000
        cost_nn = SCALE * avg_nn * price / 1_000_000
        print(f"\n{name}")
        print(f"  Naive avg:        {avg_n:>6,.0f} tok/turn  →  ${cost_n:>8,.2f}/mo @ 50k turns")
        print(f"  NocturnusAI avg:  {avg_nn:>6,.0f} tok/turn  →  ${cost_nn:>8,.2f}/mo @ 50k turns")
        print(f"  Reduction:        {ratio:.1f}×  ({(1-avg_nn/avg_n)*100:.0f}% savings)")

    # Graph
    turns = list(range(1, len(TURNS) + 1))
    fig, axes = plt.subplots(1, 2, figsize=(13, 5))
    for ax, (name, naive, noct, color) in zip(axes, [
        ("Claude Opus 4",    cn,  cnn, "#ff6b35"),
        ("Gemini 2.0 Flash", gn, gnn, "#4285f4"),
    ]):
        ax.plot(turns, naive, color="#e55", lw=2.5, marker="o", ms=5, label="Naive (full history)")
        ax.plot(turns, noct,  color=color,  lw=2.5, marker="s", ms=5, label="NocturnusAI")
        ax.fill_between(turns, noct, naive, alpha=0.12, color="#e55")
        ratio = sum(naive)/sum(noct)
        ax.set_title(f"{name}: {ratio:.1f}× fewer tokens", fontweight="bold")
        ax.set_xlabel("Turn"); ax.set_ylabel("Input tokens")
        ax.yaxis.set_major_formatter(mticker.FuncFormatter(lambda v,_: f"{v:,.0f}"))
        ax.legend(frameon=False, fontsize=9)
    plt.suptitle("Measured input tokens: Naive vs NocturnusAI (15-turn conversation)", fontsize=12)
    plt.tight_layout()
    out_dir = "/Users/johnwinn/Dev/logic-server/nocturnusai-bench/results"
    os.makedirs(out_dir, exist_ok=True)
    plt.savefig(f"{out_dir}/04_live_token_usage.png", bbox_inches="tight")
    print(f"\nGraph → results/04_live_token_usage.png")

    with open(f"{out_dir}/benchmark_results.json","w") as f:
        json.dump({"timestamp": datetime.datetime.now().isoformat(),
                   "scenario": "product_support_15_turns",
                   "claude_naive": cn, "claude_noct": cnn,
                   "gemini_naive": gn, "gemini_noct": gnn}, f, indent=2)
    print("JSON  → results/benchmark_results.json")

if __name__ == "__main__":
    main()
