# Phase 1 — Core Correctness & Security Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix all correctness bugs in the core inference engine and harden the server for multi-tenant SaaS deployment.

**Architecture:** Phase 1A (`nocturnusai-core`) and Phase 1B (`nocturnusai-server`) are independent Gradle modules and can be executed in parallel by separate developers. Phase 1A patches the logic engine; Phase 1B patches the HTTP server. Both must be complete before Phase 2 begins.

**Tech Stack:** Kotlin 1.9, Ktor 2.3.7, Gradle (Kotlin DSL), JUnit 5, kotlinx-serialization. Tests run with `./gradlew :module:test`. The project has a standard Gradle wrapper — always use `./gradlew`, never `gradle` directly.

**Spec reference:** `docs/superpowers/specs/2026-03-23-nocturnusai-quality-and-features-design.md`

**This plan covers:** Phase 1A (8 items) + Phase 1B (8 items)
**Next plans:** `2026-03-23-phase2-implementation-depth.md`, `2026-03-23-phase3-features.md` (written after this plan ships)

---

## File Map

### Phase 1A — Files Modified (nocturnusai-core)

| File | Change |
|------|--------|
| `nocturnusai-core/src/main/kotlin/com/nocturnusai/parser/Parser.kt` | Add RETRACT command; typed `ParseException`; error recovery |
| `nocturnusai-core/src/main/kotlin/com/nocturnusai/inference/BackwardChainer.kt` | Add `solveWithProof()`; fix memo scoping; add `HttpBuiltinConfig` with timeout/retry |
| `nocturnusai-core/src/main/kotlin/com/nocturnusai/inference/ReteEngine.kt` | Add NAF reverse index; re-evaluate on NAF-blocking fact arrival |
| `nocturnusai-core/src/main/kotlin/com/nocturnusai/logic/ConsistencyGuard.kt` | Add `visited` set + depth cap to constraint solver |
| `nocturnusai-core/src/main/kotlin/com/nocturnusai/NocturnusAI.kt` | Add `ReentrantReadWriteLock` around scope DAG mutations |
| `nocturnusai-core/src/main/kotlin/com/nocturnusai/persistence/SnapshotManager.kt` | Add SHA-256 checksum write/validate; `SnapshotCorruptionException` |
| `nocturnusai-core/src/main/kotlin/com/nocturnusai/memory/EventBus.kt` | Log subscriber errors; auto-unsubscribe flaky subscribers |
| `nocturnusai-core/src/test/kotlin/com/nocturnusai/parser/ParserTest.kt` | Add RETRACT tests, ParseException tests, error recovery tests |
| `nocturnusai-core/src/test/kotlin/com/nocturnusai/inference/BackwardChainerProofTest.kt` | New: proof tree tests |
| `nocturnusai-core/src/test/kotlin/com/nocturnusai/inference/HttpBuiltinTest.kt` | New: HTTP_GET_JSON timeout/retry tests |
| `nocturnusai-core/src/test/kotlin/com/nocturnusai/NafOrderingTest.kt` | New: ordering-independent NAF tests |
| `nocturnusai-core/src/test/kotlin/com/nocturnusai/logic/ConsistencyGuardCycleTest.kt` | New: cycle detection tests |
| `nocturnusai-core/src/test/kotlin/com/nocturnusai/ScopeDagConcurrencyTest.kt` | New: concurrent DAG tests |
| `nocturnusai-core/src/test/kotlin/com/nocturnusai/persistence/SnapshotIntegrityTest.kt` | New: checksum tests |
| `nocturnusai-core/src/test/kotlin/com/nocturnusai/memory/EventBusErrorTest.kt` | New: subscriber error tests |

### Phase 1B — Files Modified (nocturnusai-server)

| File | Change |
|------|--------|
| `nocturnusai-server/src/main/kotlin/com/nocturnusai/server/plugins/TenantRequired.kt` | **New:** Ktor route-scoped plugin that enforces X-Tenant-ID |
| `nocturnusai-server/src/main/kotlin/com/nocturnusai/server/routes/AdminRoutes.kt` | Wrap tenant-scoped routes in `requireTenant {}` |
| `nocturnusai-server/src/main/kotlin/com/nocturnusai/server/validation/ResourceValidator.kt` | **New:** `validateResourceName()` + `ComplexityLimits` |
| `nocturnusai-server/src/main/kotlin/com/nocturnusai/server/routes/ScopeRoutes.kt` | Apply `validateResourceName` to scope name inputs |
| `nocturnusai-server/src/main/kotlin/com/nocturnusai/server/auth/TokenBucketRateLimiter.kt` | **New:** Per-key token bucket (replaces IP-only limiter for normal endpoints) |
| `nocturnusai-server/src/main/kotlin/com/nocturnusai/server/Application.kt` | Wire `TokenBucketRateLimiter`; startup credential warning |
| `nocturnusai-server/src/main/kotlin/com/nocturnusai/server/audit/AuditEvent.kt` | **New:** `AuditEvent`, `AuditActor`, `AuditAction`, `AuditOutcome`, `AuditResource` |
| `nocturnusai-server/src/main/kotlin/com/nocturnusai/server/audit/AuditService.kt` | **New:** Async-channel write + `query()` + rotation |
| `nocturnusai-server/src/main/kotlin/com/nocturnusai/server/routes/AuthRoutes.kt` | Emit audit events; default-creds warning; key expiry in `authenticate()` |
| `nocturnusai-server/src/main/kotlin/com/nocturnusai/server/auth/AuthInterceptor.kt` | Replace `PERMISSION_MAP` list with compiled map; add `/predicates`; expiry check; startup assertion |
| `nocturnusai-server/src/main/kotlin/com/nocturnusai/server/ServerConfig.kt` | Add rate-limit config env vars |
| `nocturnusai-server/src/test/kotlin/com/nocturnusai/server/TestHelpers.kt` | Add `withTestApp(authEnabled, readerRpm)` and `withTestApp(auditService)` overloads |
| `nocturnusai-server/src/test/kotlin/com/nocturnusai/server/TenantEnforcementTest.kt` | **New:** all routes return 400 without header |
| `nocturnusai-server/src/test/kotlin/com/nocturnusai/server/ResourceValidationTest.kt` | **New:** path traversal, length, reserved name tests |
| `nocturnusai-server/src/test/kotlin/com/nocturnusai/server/EndpointRateLimitTest.kt` | **New:** per-key token bucket tests |
| `nocturnusai-server/src/test/kotlin/com/nocturnusai/server/AuditLogTest.kt` | **New:** audit event capture and query tests |
| `nocturnusai-server/src/test/kotlin/com/nocturnusai/server/ComplexityLimitsTest.kt` | **New:** over-limit request tests |
| `nocturnusai-server/src/test/kotlin/com/nocturnusai/server/AuthHardeningTest.kt` | **New:** /predicates auth, expired key, startup assertion |

---

## Phase 1A Tasks

---

### Task 1: Parser — RETRACT command + typed ParseException

> **Scope note:** `Parser.kt` is 319 lines and already implements 6 commands: ASSERT, INFER, RESTRICT, EXPLAIN, TEST, EXTRACT. The grammar is substantially complete. This task adds only two things: the missing RETRACT command and a typed `ParseException`. Do **not** rewrite the parser — it works.

The parser handles 6 commands but is missing RETRACT. It also uses raw `RuntimeException` — callers can't distinguish parse errors from runtime errors. Fix both.

**Files:**
- Modify: `nocturnusai-core/src/main/kotlin/com/nocturnusai/parser/Parser.kt`
- Modify: `nocturnusai-core/src/test/kotlin/com/nocturnusai/parser/ParserTest.kt`

- [ ] **Step 1: Write failing tests**

Add to `ParserTest.kt`:
```kotlin
@Test
fun `RETRACT command parses correctly`() {
    val tokens = Tokenizer("RETRACT Likes(alice, bob);").tokenize()
    val commands = Parser(tokens).parse()
    assertEquals(1, commands.size)
    val cmd = commands[0] as Command.RetractFact
    assertEquals("Likes", cmd.fact.predicate)
    assertEquals(listOf(Term.Identifier("alice"), Term.Identifier("bob")), cmd.fact.args)
}

@Test
fun `parse errors throw ParseException not RuntimeException`() {
    val tokens = Tokenizer("INFER ;").tokenize()  // missing atom before semicolon
    val ex = assertThrows<ParseException> { Parser(tokens).parse() }
    assertTrue(ex.message!!.contains("Expected predicate"))
}

@Test
fun `unknown command throws ParseException`() {
    val tokens = Tokenizer("FROBULATE foo(x);").tokenize()
    assertThrows<ParseException> { Parser(tokens).parse() }
}
```

- [ ] **Step 2: Run tests — expect failure**

```bash
./gradlew :nocturnusai-core:test --tests "com.nocturnusai.parser.ParserTest" -i 2>&1 | tail -30
```
Expected: `FAILED` — `ParseException` class not found, RETRACT not in enum/when.

- [ ] **Step 3: Add `ParseException` and RETRACT**

At top of `Parser.kt`, before the `Command` sealed class:
```kotlin
class ParseException(message: String, val line: Int = 0, val col: Int = 0) :
    Exception("Parse error at $line:$col — $message")
```

Add to `Command` sealed class:
```kotlin
data class RetractFact(val fact: Atom) : Command()
```

In `parseCommand()` `when` block, add:
```kotlin
TokenType.RETRACT -> {
    val fact = parseAtom()
    consume(TokenType.SEMICOLON, "Expected ';' after retract")
    Command.RetractFact(fact)
}
```

Replace the `error()` helper at bottom of `Parser.kt`:
```kotlin
private fun error(token: Token, message: String): ParseException =
    ParseException(message, token.line, token.col)
```
Update all `throw error(...)` calls — they already call this helper, so the type change propagates automatically. Change every `RuntimeException` in the file signature (function return types, catch clauses in tests) to `ParseException`.

- [ ] **Step 4: Run tests — expect pass**

```bash
./gradlew :nocturnusai-core:test --tests "com.nocturnusai.parser.ParserTest" -i 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`, all parser tests pass.

- [ ] **Step 5: Run full core test suite to check no regressions**

```bash
./gradlew :nocturnusai-core:test 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add nocturnusai-core/src/main/kotlin/com/nocturnusai/parser/Parser.kt \
        nocturnusai-core/src/test/kotlin/com/nocturnusai/parser/ParserTest.kt
git commit -m "fix(parser): add RETRACT command and typed ParseException

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

### Task 2: BackwardChainer — `solveWithProof()`

`TestRunner.kt` calls `backwardChainer.solveWithProof(goal)` but the method doesn't exist. This makes the entire `TEST` block in DSL programs non-functional.

**Files:**
- Modify: `nocturnusai-core/src/main/kotlin/com/nocturnusai/inference/BackwardChainer.kt`
- Create: `nocturnusai-core/src/test/kotlin/com/nocturnusai/inference/BackwardChainerProofTest.kt`

- [ ] **Step 1: Write failing tests**

Create `BackwardChainerProofTest.kt`:
```kotlin
package com.nocturnusai.inference

import com.nocturnusai.core.*
import com.nocturnusai.storage.Hexastore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File
import java.nio.file.Files

class BackwardChainerProofTest {

    private fun makeChainer(vararg facts: Atom, rules: List<Rule> = emptyList()): BackwardChainer {
        val dir = Files.createTempDirectory("proof-test").toFile()
        val store = Hexastore()
        facts.forEach { store.add(it) }
        return BackwardChainer(store, rules)
    }

    private fun atom(pred: String, vararg args: String) =
        Atom(pred, args.map { Term.Identifier(it) })

    @Test
    fun `solveWithProof returns FactMatch for direct fact`() {
        val chainer = makeChainer(atom("human", "socrates"))
        val results = chainer.solveWithProof(atom("human", "socrates")).toList()
        assertEquals(1, results.size)
        val proof = results[0].proof
        val step = proof.root.steps[0]
        assertTrue(step is ProofStep.FactMatch)
    }

    @Test
    fun `solveWithProof returns RuleApplication for derived fact`() {
        val socrates = atom("human", "socrates")
        val rule = Rule(
            variables = listOf("?x"),
            head = Atom("mortal", listOf(Term.Variable("?x"))),
            body  = listOf(Atom("human", listOf(Term.Variable("?x"))))
        )
        val chainer = makeChainer(socrates, rules = listOf(rule))
        val results = chainer.solveWithProof(atom("mortal", "socrates")).toList()
        assertEquals(1, results.size)
        val step = results[0].proof.root.steps[0]
        assertTrue(step is ProofStep.RuleApplication)
        val ra = step as ProofStep.RuleApplication
        assertEquals("mortal", ra.rule.head.predicate)
        assertEquals(1, ra.subProofs.size)
    }

    @Test
    fun `solveWithProof returns empty for unprovable goal`() {
        val chainer = makeChainer()
        val results = chainer.solveWithProof(atom("human", "nobody")).toList()
        assertTrue(results.isEmpty())
    }

    @Test
    fun `solve and solveWithProof return same atoms`() {
        val socrates = atom("human", "socrates")
        val chainer = makeChainer(socrates)
        val solveResults = chainer.solve(atom("human", "socrates")).toList()
        val proofResults = chainer.solveWithProof(atom("human", "socrates")).toList()
        assertEquals(solveResults.map { it.predicate }, proofResults.map { it.atom.predicate })
        assertEquals(solveResults.map { it.args }, proofResults.map { it.atom.args })
    }
}
```

- [ ] **Step 2: Run tests — expect failure**

```bash
./gradlew :nocturnusai-core:test \
  --tests "com.nocturnusai.inference.BackwardChainerProofTest" -i 2>&1 | tail -20
```
Expected: `FAILED` — unresolved reference: `solveWithProof`, `ProofResult`.

- [ ] **Step 3: Add `ProofResult` data class and `solveWithProof()` to BackwardChainer**

At top of `BackwardChainer.kt`, add import:
```kotlin
import com.nocturnusai.core.ProofNode
import com.nocturnusai.core.ProofStep
import com.nocturnusai.core.ProofTree
```

Add data class before the `BackwardChainer` class:
```kotlin
data class ProofResult(
    val atom: Atom,
    val substitution: Substitution,
    val confidence: Double?,
    val proof: ProofTree
)
```

Add method to `BackwardChainer`:
```kotlin
fun solveWithProof(goal: Atom, scope: String? = null): Sequence<ProofResult> {
    val rulesByPredicate = rules.groupBy { it.head.predicate }
    val memo = HashMap<Atom, List<Atom>>()
    return solveWithProofInternal(
        goals = listOf(goal),
        index = 0,
        subst = emptyMap(),
        confidence = null,
        depth = 0,
        rulesByPredicate = rulesByPredicate,
        memo = memo
    ).map { (subst, conf, proofNode) ->
        val resultAtom = Unifier.substitute(goal, subst)
            .let { if (conf != null) it.copy(confidence = conf) else it }
        ProofResult(resultAtom, subst, conf, ProofTree(proofNode))
    }.distinctBy { it.atom.args }
}

// Returns triples of (substitution, confidence, ProofNode for the resolved goal list)
private fun solveWithProofInternal(
    goals: List<Atom>,
    index: Int,
    subst: Substitution,
    confidence: Double?,
    depth: Int,
    rulesByPredicate: Map<String, List<Rule>>,
    memo: HashMap<Atom, List<Atom>>
): Sequence<Triple<Substitution, Double?, ProofNode>> = sequence {
    if (index >= goals.size) {
        // All goals resolved — build a leaf proof node
        yield(Triple(subst, confidence, ProofNode(
            goal = if (goals.isEmpty()) Atom("true", emptyList()) else Unifier.substitute(goals[index - 1], subst),
            steps = emptyList()
        )))
        return@sequence
    }
    if (depth > maxDepth) return@sequence

    val currentGoal = Unifier.substitute(goals[index], subst)

    // Handle NAF
    if (currentGoal.naf) {
        val provable = solve(currentGoal.copy(naf = false)).any()
        if (!provable) {
            val nafStep = ProofStep.FactMatch(currentGoal)
            yieldAll(solveWithProofInternal(goals, index + 1, subst, confidence, depth, rulesByPredicate, memo)
                .map { (s, c, node) -> Triple(s, c, ProofNode(currentGoal, listOf(nafStep))) })
        }
        return@sequence
    }

    // Try direct fact matches
    store.match(currentGoal).forEach { matchedFact ->
        val unified = Unifier.unify(currentGoal, matchedFact) ?: return@forEach
        val mergedSubst = subst + unified
        val newConf = minOfNullable(confidence, matchedFact.confidence)
        val factStep = ProofStep.FactMatch(matchedFact)
        solveWithProofInternal(goals, index + 1, mergedSubst, newConf, depth, rulesByPredicate, memo)
            .forEach { (s, c, _) ->
                yield(Triple(s, c, ProofNode(currentGoal, listOf(factStep))))
            }
    }

    // Try rules
    rulesByPredicate[currentGoal.predicate]?.forEach { rule ->
        val renamed = renameVariables(rule)
        val unified = Unifier.unify(currentGoal, Unifier.substitute(renamed.head, subst)) ?: return@forEach
        val mergedSubst = subst + unified
        val bodyGoals = renamed.body
        solveWithProofInternal(bodyGoals, 0, mergedSubst, confidence, depth + 1, rulesByPredicate, memo)
            .forEach { (s, c, bodyNode) ->
                val ruleStep = ProofStep.RuleApplication(rule, s, listOf(bodyNode))
                solveWithProofInternal(goals, index + 1, s, c, depth, rulesByPredicate, memo)
                    .forEach { (s2, c2, _) ->
                        yield(Triple(s2, c2, ProofNode(currentGoal, listOf(ruleStep))))
                    }
            }
    }
}

private fun minOfNullable(a: Double?, b: Double?): Double? = when {
    a == null -> b
    b == null -> a
    else -> minOf(a, b)
}
```

> **Note on `renameVariables`**: This function should already exist in `BackwardChainer` (it's needed by `solve()` too — search for it and reuse it). If it's inlined in `solveRecursiveWithConfidence`, extract it as a private function first.

- [ ] **Step 4: Run tests — expect pass**

```bash
./gradlew :nocturnusai-core:test \
  --tests "com.nocturnusai.inference.BackwardChainerProofTest" -i 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`, 4 tests passing.

- [ ] **Step 5: Run full core suite**

```bash
./gradlew :nocturnusai-core:test 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add nocturnusai-core/src/main/kotlin/com/nocturnusai/inference/BackwardChainer.kt \
        nocturnusai-core/src/test/kotlin/com/nocturnusai/inference/BackwardChainerProofTest.kt
git commit -m "feat(inference): add solveWithProof() to BackwardChainer

Enables the TEST block in DSL programs to produce proof trees.
ProofResult carries atom, substitution, confidence, and full ProofTree.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

### Task 3: ReteEngine — NAF Ordering Independence

Currently, Rete's NAF check fires at the moment a rule triggers. If the NAF-blocking fact arrives *after* the triggering fact, the derived atom is never retracted. Fix: when a fact is asserted whose predicate appears in a NAF condition of any rule, re-evaluate all complete matches involving that NAF condition.

**Files:**
- Modify: `nocturnusai-core/src/main/kotlin/com/nocturnusai/inference/ReteEngine.kt`
- Create: `nocturnusai-core/src/test/kotlin/com/nocturnusai/NafOrderingTest.kt`

- [ ] **Step 1: Write failing tests**

Create `NafOrderingTest.kt`:
```kotlin
package com.nocturnusai

import com.nocturnusai.core.*
import com.nocturnusai.storage.Hexastore
import com.nocturnusai.inference.ReteEngine
import com.nocturnusai.logic.ProvenanceTracker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class NafOrderingTest {

    private fun setup(): Triple<Hexastore, ReteEngine, ProvenanceTracker> {
        val store = Hexastore()
        val tracker = ProvenanceTracker()
        val rete = ReteEngine(store, tracker)
        return Triple(store, rete, tracker)
    }

    // Rule: canFly(?x) :- bird(?x), NOT penguin(?x)
    private fun canFlyRule() = Rule(
        variables = listOf("?x"),
        head = Atom("canFly", listOf(Term.Variable("?x"))),
        body = listOf(
            Atom("bird",    listOf(Term.Variable("?x")), naf = false),
            Atom("penguin", listOf(Term.Variable("?x")), naf = true)
        )
    )

    @Test
    fun `blocking fact BEFORE trigger - canFly never derived`() {
        val (store, rete, _) = setup()
        rete.addRule(canFlyRule())
        store.add(Atom("penguin", listOf(Term.Identifier("tweety"))))
        rete.onFactAsserted(Atom("penguin", listOf(Term.Identifier("tweety"))))
        store.add(Atom("bird", listOf(Term.Identifier("tweety"))))
        rete.onFactAsserted(Atom("bird", listOf(Term.Identifier("tweety"))))
        val canFly = store.match(Atom("canFly", listOf(Term.Variable("?x")))).toList()
        assertTrue(canFly.isEmpty(), "canFly(tweety) should not be derived when penguin fact exists")
    }

    @Test
    fun `blocking fact AFTER trigger - derived fact retracted`() {
        val (store, rete, _) = setup()
        rete.addRule(canFlyRule())
        // Assert triggering fact first
        store.add(Atom("bird", listOf(Term.Identifier("tweety"))))
        rete.onFactAsserted(Atom("bird", listOf(Term.Identifier("tweety"))))
        // canFly(tweety) should be derived at this point
        val before = store.match(Atom("canFly", listOf(Term.Variable("?x")))).toList()
        assertEquals(1, before.size, "canFly(tweety) should be derived when no penguin fact yet")
        // Now assert the blocking fact
        store.add(Atom("penguin", listOf(Term.Identifier("tweety"))))
        rete.onFactAsserted(Atom("penguin", listOf(Term.Identifier("tweety"))))
        // canFly(tweety) must be retracted
        val after = store.match(Atom("canFly", listOf(Term.Variable("?x")))).toList()
        assertTrue(after.isEmpty(), "canFly(tweety) must be retracted when penguin fact arrives later")
    }

    @Test
    fun `blocking fact for one value does not block another`() {
        val (store, rete, _) = setup()
        rete.addRule(canFlyRule())
        store.add(Atom("bird",    listOf(Term.Identifier("tweety"))))
        store.add(Atom("bird",    listOf(Term.Identifier("parrot"))))
        rete.onFactAsserted(Atom("bird", listOf(Term.Identifier("tweety"))))
        rete.onFactAsserted(Atom("bird", listOf(Term.Identifier("parrot"))))
        // Block only tweety
        store.add(Atom("penguin", listOf(Term.Identifier("tweety"))))
        rete.onFactAsserted(Atom("penguin", listOf(Term.Identifier("tweety"))))
        val canFly = store.match(Atom("canFly", listOf(Term.Variable("?x")))).toList()
        assertEquals(1, canFly.size)
        assertEquals("parrot", (canFly[0].args[0] as Term.Identifier).value)
    }
}
```

- [ ] **Step 2: Run tests — expect failure**

```bash
./gradlew :nocturnusai-core:test --tests "com.nocturnusai.NafOrderingTest" -i 2>&1 | tail -20
```
Expected: `FAILED` — "blocking fact AFTER trigger" test fails because canFly is not retracted.

- [ ] **Step 3: Add NAF reverse index and re-evaluation to ReteEngine**

In `ReteEngine`, add:
```kotlin
// Map: predicate → list of (rule, nafConditionAtom) pairs
// When a fact with predicate P arrives, check if P is a NAF blocker for any rule.
private val nafBlockerIndex = mutableMapOf<String, MutableList<Pair<Rule, Atom>>>()

// Track which derived facts exist, keyed by (rule, substitution)
// Used to find and retract invalidated derivations.
private val derivedFacts = mutableMapOf<Pair<Rule, Map<String, Term>>, Atom>()
```

In `addRule()`, after the existing alpha node indexing:
```kotlin
rule.body.filter { it.naf }.forEach { nafAtom ->
    nafBlockerIndex.computeIfAbsent(nafAtom.predicate) { mutableListOf() }
        .add(rule to nafAtom)
}
```

In `onFactAsserted()`, after existing rule-triggering logic, add:
```kotlin
// Check if this fact blocks any NAF conditions — re-evaluate affected derivations
val nafRules = nafBlockerIndex[fact.predicate] ?: emptyList()
for ((rule, nafCondition) in nafRules) {
    // Find all substitutions that could make nafCondition unify with 'fact'
    val toRetract = derivedFacts.entries
        .filter { (key, _) -> key.first == rule }
        .filter { (key, _) ->
            val subst = key.second
            val groundedNaf = Unifier.substitute(nafCondition.copy(naf = false), subst)
            Unifier.unify(groundedNaf, fact) != null
        }
    for ((key, derivedFact) in toRetract) {
        store.delete(derivedFact)
        tracker?.retract(derivedFact)
        derivedFacts.remove(key)
    }
}
```

When a derivation is successfully created (production node fires), record it in `derivedFacts`:
```kotlin
// In the production node / rule-fire section, after asserting the head:
derivedFacts[rule to finalSubst] = derivedHead
```

> **Tip:** Look at `onFactAsserted()` — find where `store.add(derivedHead)` is called and add the `derivedFacts` record immediately after.

- [ ] **Step 4: Run tests — expect pass**

```bash
./gradlew :nocturnusai-core:test --tests "com.nocturnusai.NafOrderingTest" -i 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`, all 3 NAF ordering tests pass.

- [ ] **Step 5: Run full NAF test suite to ensure no regressions**

```bash
./gradlew :nocturnusai-core:test --tests "com.nocturnusai.NafTest" 2>&1 | tail -5
./gradlew :nocturnusai-core:test 2>&1 | tail -5
```
Both expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add nocturnusai-core/src/main/kotlin/com/nocturnusai/inference/ReteEngine.kt \
        nocturnusai-core/src/test/kotlin/com/nocturnusai/NafOrderingTest.kt
git commit -m "fix(rete): NAF ordering independence — retract derived facts when blocker arrives

Previously canFly(tweety) would persist if bird(tweety) was asserted before
penguin(tweety). Now Rete retracts via ProvenanceTracker when a NAF-blocking
fact arrives regardless of assertion order.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

### Task 4: HTTP_GET_JSON — Timeout, Retry, Config

The `HTTP_GET_JSON` built-in in `BackwardChainer` makes raw blocking HTTP calls with no timeout. One slow external service hangs inference indefinitely.

**Files:**
- Modify: `nocturnusai-core/src/main/kotlin/com/nocturnusai/inference/BackwardChainer.kt`
- Create: `nocturnusai-core/src/test/kotlin/com/nocturnusai/inference/HttpBuiltinTest.kt`

- [ ] **Step 1: Write failing tests using a local mock HTTP server**

Add to `nocturnusai-core/build.gradle.kts` if not already present:
```kotlin
testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
```

Create `HttpBuiltinTest.kt`:
```kotlin
package com.nocturnusai.inference

import com.nocturnusai.core.*
import com.nocturnusai.storage.Hexastore
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.util.concurrent.TimeUnit

class HttpBuiltinTest {
    private val server = MockWebServer()

    @BeforeEach fun start() { server.start() }
    @AfterEach  fun stop()  { server.shutdown() }

    private fun httpGetAtom(urlVar: String, resultVar: String) = Atom(
        "HTTP_GET_JSON",
        listOf(Term.StringLit(server.url("/data").toString()), Term.Variable(resultVar))
    )

    private fun chainer(timeoutMs: Long = 2000, maxRetries: Int = 0): BackwardChainer {
        val config = HttpBuiltinConfig(timeoutMs = timeoutMs, maxRetries = maxRetries)
        return BackwardChainer(Hexastore(), emptyList(), httpConfig = config)
    }

    @Test
    fun `success - 200 with JSON array returns result atoms`() {
        server.enqueue(MockResponse().setBody("""[{"predicate":"fact","args":["a"]}]"""))
        val results = chainer().solve(httpGetAtom("url", "?r")).toList()
        assertEquals(1, results.size)
    }

    @Test
    fun `timeout - slow server causes predicate to fail cleanly`() {
        server.enqueue(MockResponse().setBodyDelay(5, TimeUnit.SECONDS).setBody("[]"))
        val start = System.currentTimeMillis()
        val results = chainer(timeoutMs = 500).solve(httpGetAtom("url", "?r")).toList()
        val elapsed = System.currentTimeMillis() - start
        assertTrue(results.isEmpty(), "Should return empty on timeout")
        assertTrue(elapsed < 2000, "Should timeout well under 2s, was ${elapsed}ms")
    }

    @Test
    fun `retry - 503 twice then 200 succeeds after retries`() {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody("""[{"predicate":"fact","args":["a"]}]"""))
        val results = chainer(maxRetries = 2).solve(httpGetAtom("url", "?r")).toList()
        assertEquals(1, results.size)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `no retry on 404`() {
        server.enqueue(MockResponse().setResponseCode(404))
        chainer(maxRetries = 3).solve(httpGetAtom("url", "?r")).toList()
        assertEquals(1, server.requestCount) // only one attempt
    }
}
```

- [ ] **Step 2: Run tests — expect failure**

```bash
./gradlew :nocturnusai-core:test --tests "com.nocturnusai.inference.HttpBuiltinTest" -i 2>&1 | tail -20
```
Expected: `FAILED` — `HttpBuiltinConfig` not found; timeout test hangs.

- [ ] **Step 3: Add `HttpBuiltinConfig` and refactor HTTP call in BackwardChainer**

In `BackwardChainer.kt`, add data class and update constructor:
```kotlin
data class HttpBuiltinConfig(
    val timeoutMs: Long = 5_000,
    val maxRetries: Int = 2,
    val retryDelayMs: Long = 500
)

class BackwardChainer(
    private val store: Hexastore,
    private val rules: List<Rule>,
    private val maxDepth: Int = 100,
    private val semanticContext: SemanticContext = DummySemanticContext,
    private val httpConfig: HttpBuiltinConfig = HttpBuiltinConfig()
)
```

Replace the existing `HTTP_GET_JSON` block in `solveRecursiveWithConfidence` (where it makes the URL connection):
```kotlin
"HTTP_GET_JSON" -> {
    val urlTerm = Unifier.substitute(currentGoal.args[0], subst)
    val urlStr = when (urlTerm) {
        is Term.StringLit -> urlTerm.value
        is Term.Identifier -> urlTerm.value
        else -> return@sequence
    }
    val jsonResult = fetchWithRetry(urlStr) ?: return@sequence
    // parse jsonResult into atoms and yield solutions as before
    // ... (keep existing JSON→Atom parsing logic)
}
```

Add `fetchWithRetry` private function:
```kotlin
private fun fetchWithRetry(url: String): String? {
    var lastError: Exception? = null
    repeat(httpConfig.maxRetries + 1) { attempt ->
        try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = httpConfig.timeoutMs.toInt()
            conn.readTimeout = httpConfig.timeoutMs.toInt()
            conn.connect()
            val code = conn.responseCode
            if (code in 500..599) {
                lastError = IOException("HTTP $code")
                if (attempt < httpConfig.maxRetries) {
                    Thread.sleep(httpConfig.retryDelayMs * (attempt + 1))
                }
                return@repeat
            }
            if (code != 200) return null  // non-retryable error (e.g. 404)
            return conn.inputStream.bufferedReader().readText()
        } catch (e: java.io.IOException) {
            lastError = e
            if (attempt < httpConfig.maxRetries) {
                Thread.sleep(httpConfig.retryDelayMs * (attempt + 1))
            }
        }
    }
    log.warn("HTTP_GET_JSON: all attempts failed for $url — ${lastError?.message}")
    return null
}
```

- [ ] **Step 4: Run tests — expect pass**

```bash
./gradlew :nocturnusai-core:test --tests "com.nocturnusai.inference.HttpBuiltinTest" -i 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`, all 4 tests pass.

- [ ] **Step 5: Run full core suite**

```bash
./gradlew :nocturnusai-core:test 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add nocturnusai-core/src/main/kotlin/com/nocturnusai/inference/BackwardChainer.kt \
        nocturnusai-core/src/test/kotlin/com/nocturnusai/inference/HttpBuiltinTest.kt
git commit -m "fix(inference): HTTP_GET_JSON timeout, retry, and 503 backoff

Adds HttpBuiltinConfig with configurable timeoutMs, maxRetries, retryDelayMs.
5xx responses are retried; 4xx are not. Default: 5s timeout, 2 retries.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

### Task 5: ConsistencyGuard — Cycle Detection

A circular constraint (`foo(?x) :- foo(?x)`) triggers a `StackOverflowError`. Add a visited-set and depth cap.

**Files:**
- Modify: `nocturnusai-core/src/main/kotlin/com/nocturnusai/logic/ConsistencyGuard.kt`
- Create: `nocturnusai-core/src/test/kotlin/com/nocturnusai/logic/ConsistencyGuardCycleTest.kt`

- [ ] **Step 1: Write failing tests**

Create `ConsistencyGuardCycleTest.kt`:
```kotlin
package com.nocturnusai.logic

import com.nocturnusai.core.*
import com.nocturnusai.storage.Hexastore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ConsistencyGuardCycleTest {

    @Test
    fun `self-referential constraint does not throw StackOverflow`() {
        val store = Hexastore()
        val guard = ConsistencyGuard(store)
        // constraint: foo(?x) :- foo(?x)  — trivially circular
        val circularConstraint = Constraint(
            listOf(Atom("foo", listOf(Term.Variable("?x"))))
        )
        guard.addConstraint(circularConstraint)
        // Should return without throwing
        assertDoesNotThrow {
            guard.check(Atom("foo", listOf(Term.Identifier("bar"))))
        }
    }

    @Test
    fun `mutual cycle A-B does not throw`() {
        val store = Hexastore()
        val guard = ConsistencyGuard(store)
        // constraint: a(?x) :- b(?x); b(?x) :- a(?x)
        guard.addConstraint(Constraint(listOf(
            Atom("a", listOf(Term.Variable("?x"))),
            Atom("b", listOf(Term.Variable("?x")))
        )))
        assertDoesNotThrow {
            store.add(Atom("a", listOf(Term.Identifier("x"))))
            guard.check(Atom("b", listOf(Term.Identifier("x"))))
        }
    }

    @Test
    fun `valid non-cyclic constraint still enforced`() {
        val store = Hexastore()
        val guard = ConsistencyGuard(store)
        store.add(Atom("alive", listOf(Term.Identifier("alice"))))
        // constraint: alive(?x) AND dead(?x) -> contradiction
        guard.addConstraint(Constraint(listOf(
            Atom("alive", listOf(Term.Variable("?x"))),
            Atom("dead",  listOf(Term.Variable("?x")))
        )))
        val ex = assertThrows(Exception::class.java) {
            guard.check(Atom("dead", listOf(Term.Identifier("alice"))))
        }
        assertTrue(ex.message?.contains("contradiction") == true || ex is ConsistencyViolationException)
    }
}
```

- [ ] **Step 2: Run — expect failure or StackOverflow**

```bash
./gradlew :nocturnusai-core:test \
  --tests "com.nocturnusai.logic.ConsistencyGuardCycleTest" -i 2>&1 | tail -20
```
Expected: `FAILED` — `StackOverflowError` or test class cannot compile.

- [ ] **Step 3: Add visited set and depth cap to ConsistencyGuard**

Find the recursive solver function in `ConsistencyGuard.kt` (called something like `checkConstraint`, `solveConstraint`, or `canSatisfy`). Add two parameters:
```kotlin
private fun solveConstraint(
    goals: List<Atom>,
    substitution: Map<String, Term>,
    depth: Int = 0,
    visited: MutableSet<String> = mutableSetOf()
): Boolean {
    if (depth > MAX_CONSTRAINT_DEPTH) {
        log.warn("Constraint solver depth limit exceeded — treating as unsatisfied")
        return false
    }
    if (goals.isEmpty()) return true
    val currentGoal = applySubstitution(goals[0], substitution)
    val goalKey = "${currentGoal.predicate}(${currentGoal.args.joinToString(",")})"
    if (goalKey in visited) return false   // cycle detected
    visited.add(goalKey)
    // ... rest of existing logic unchanged, pass (depth+1, visited) to recursive calls
}

companion object {
    private const val MAX_CONSTRAINT_DEPTH = 50
}
```

- [ ] **Step 4: Run — expect pass**

```bash
./gradlew :nocturnusai-core:test \
  --tests "com.nocturnusai.logic.ConsistencyGuardCycleTest" 2>&1 | tail -10
./gradlew :nocturnusai-core:test --tests "com.nocturnusai.logic.ConsistencyGuardTest" 2>&1 | tail -5
```
Both expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add nocturnusai-core/src/main/kotlin/com/nocturnusai/logic/ConsistencyGuard.kt \
        nocturnusai-core/src/test/kotlin/com/nocturnusai/logic/ConsistencyGuardCycleTest.kt
git commit -m "fix(logic): ConsistencyGuard cycle detection — visited set + depth cap 50

Circular constraints no longer throw StackOverflowError.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

### Task 6: Scope DAG — Concurrent Write Safety

`setScopeParent()` modifies `scopeParents` without a lock. Concurrent callers can corrupt the map or silently create cycles.

**Files:**
- Modify: `nocturnusai-core/src/main/kotlin/com/nocturnusai/NocturnusAI.kt`
- Create: `nocturnusai-core/src/test/kotlin/com/nocturnusai/ScopeDagConcurrencyTest.kt`

- [ ] **Step 1: Write failing tests**

Create `ScopeDagConcurrencyTest.kt`:
```kotlin
package com.nocturnusai

import com.nocturnusai.core.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class ScopeDagConcurrencyTest {

    private fun makeDb(): NocturnusAI {
        val dir = Files.createTempDirectory("scope-dag-test").toFile()
        return NocturnusAI(dir, multiTenant = true).also {
            it.createTenant("t1")
        }
    }

    @Test
    fun `concurrent setScopeParent calls do not corrupt map`() {
        val db = makeDb()
        val pool = Executors.newFixedThreadPool(20)
        val barrier = CyclicBarrier(20)
        val errors = AtomicInteger(0)
        val futures = (1..20).map { i ->
            pool.submit {
                barrier.await()
                try {
                    db.setScopeParent("child$i", "root", "t1")
                } catch (e: Exception) {
                    errors.incrementAndGet()
                }
            }
        }
        futures.forEach { it.get() }
        pool.shutdown()
        assertEquals(0, errors.get(), "No exceptions expected from concurrent setScopeParent")
        // All 20 children should now have root as parent
        val dag = db.getScopeDag("t1")
        assertEquals(20, dag.count { it.value == "root" })
    }

    @Test
    fun `cycle detection is atomic with mutation`() {
        val db = makeDb()
        db.setScopeParent("B", "A", "t1")
        // A -> B exists; now try to set B -> A (would create cycle)
        val ex = assertThrows(IllegalArgumentException::class.java) {
            db.setScopeParent("A", "B", "t1")
        }
        assertTrue(ex.message!!.contains("cycle"))
    }
}
```

- [ ] **Step 2: Run — expect failure**

```bash
./gradlew :nocturnusai-core:test \
  --tests "com.nocturnusai.ScopeDagConcurrencyTest" -i 2>&1 | tail -20
```
Expected: `FAILED` — concurrent test corrupts map or produces wrong count; cycle test may or may not pass depending on timing.

- [ ] **Step 3: Add `ReentrantReadWriteLock` to NocturnusAI**

In `NocturnusAI.kt`, find the `scopeParents` field. Add a lock field near it:
```kotlin
private val scopeDagLock = java.util.concurrent.locks.ReentrantReadWriteLock()
```

Wrap `setScopeParent()`:
```kotlin
fun setScopeParent(child: String, parent: String, tenantId: String) {
    scopeDagLock.writeLock().lock()
    try {
        val ancestors = getScopeAncestorsInternal(tenantId, parent)
        if (child in ancestors || child == parent) {
            throw IllegalArgumentException("Setting $child -> $parent would create a cycle in scope DAG")
        }
        scopeParents.getOrPut(tenantId) { mutableMapOf() }[child] = parent
    } finally {
        scopeDagLock.writeLock().unlock()
    }
}
```

Wrap `getScopeAncestors()` (and the internal helper it delegates to):
```kotlin
fun getScopeAncestors(scope: String, tenantId: String): List<String> {
    scopeDagLock.readLock().lock()
    try {
        return getScopeAncestorsInternal(tenantId, scope)
    } finally {
        scopeDagLock.readLock().unlock()
    }
}
```

`getScopeAncestorsInternal` must be called only while holding the lock (mark with `@GuardedBy("scopeDagLock")`).

- [ ] **Step 4: Run — expect pass**

```bash
./gradlew :nocturnusai-core:test \
  --tests "com.nocturnusai.ScopeDagConcurrencyTest" 2>&1 | tail -5
./gradlew :nocturnusai-core:test 2>&1 | tail -5
```
Both expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add nocturnusai-core/src/main/kotlin/com/nocturnusai/NocturnusAI.kt \
        nocturnusai-core/src/test/kotlin/com/nocturnusai/ScopeDagConcurrencyTest.kt
git commit -m "fix(core): scope DAG write lock — atomic cycle detection and mutation

setScopeParent now holds write lock during cycle check + map update.
getScopeAncestors uses read lock for consistent views under concurrency.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

### Task 7: SnapshotManager — Checksum Integrity

Snapshots are restored without any validation. A partially-written or corrupted snapshot loads silently. Add SHA-256 checksum on save and validate on load.

**Files:**
- Modify: `nocturnusai-core/src/main/kotlin/com/nocturnusai/persistence/SnapshotManager.kt`
- Create: `nocturnusai-core/src/test/kotlin/com/nocturnusai/persistence/SnapshotIntegrityTest.kt`

- [ ] **Step 1: Write failing tests**

Create `SnapshotIntegrityTest.kt`:
```kotlin
package com.nocturnusai.persistence

import com.nocturnusai.core.*
import com.nocturnusai.NocturnusAI
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File
import java.nio.file.Files

class SnapshotIntegrityTest {

    private fun makeDb(dir: File): NocturnusAI = NocturnusAI(dir, multiTenant = true).also {
        it.createTenant("t1")
    }

    @Test
    fun `snapshot roundtrip preserves all facts`() {
        val dir = Files.createTempDirectory("snap-test").toFile()
        val db = makeDb(dir)
        db.assertFact(Atom("likes", listOf(Term.Identifier("a"), Term.Identifier("b"))), "t1")
        db.createSnapshot()
        db.close()
        val db2 = makeDb(dir)
        val facts = db2.getStore("t1").getAllAtoms().toList()
        assertEquals(1, facts.size)
        assertEquals("likes", facts[0].predicate)
    }

    @Test
    fun `corrupted snapshot throws SnapshotCorruptionException`() {
        val dir = Files.createTempDirectory("snap-corrupt").toFile()
        val db = makeDb(dir)
        db.assertFact(Atom("test", listOf(Term.Identifier("x"))), "t1")
        db.createSnapshot()
        db.close()
        // Corrupt the snapshot file
        val snapshotFile = dir.listFiles { f -> f.name.endsWith(".json") && !f.name.contains("wal") }?.firstOrNull()
            ?: dir.resolve("snapshot.json")
        val content = snapshotFile.readText()
        snapshotFile.writeText(content.dropLast(10) + "CORRUPTED}")
        assertThrows(SnapshotCorruptionException::class.java) {
            NocturnusAI(dir, multiTenant = true)
        }
    }

    @Test
    fun `legacy snapshot without checksum loads with warning`() {
        val dir = Files.createTempDirectory("snap-legacy").toFile()
        // Write a snapshot file in old format (no checksum field)
        val legacyJson = """{"version":1,"tenants":{"t1":{"positives":[],"negatives":[]}}}"""
        dir.resolve("snapshot.json").writeText(legacyJson)
        // Should NOT throw — just warn
        assertDoesNotThrow {
            val db = NocturnusAI(dir, multiTenant = true)
            db.close()
        }
    }
}
```

- [ ] **Step 2: Run — expect failure**

```bash
./gradlew :nocturnusai-core:test \
  --tests "com.nocturnusai.persistence.SnapshotIntegrityTest" -i 2>&1 | tail -20
```
Expected: `FAILED` — `SnapshotCorruptionException` class not found; corruption test doesn't throw.

- [ ] **Step 3: Add checksum to SnapshotManager**

In `SnapshotManager.kt`:

1. Add exception class:
```kotlin
class SnapshotCorruptionException(message: String, cause: Throwable? = null) : Exception(message, cause)
```

2. Add wrapper class:
```kotlin
@Serializable
data class SnapshotFile(
    val version: Int = 2,
    val checksum: String,
    val data: SnapshotData
)
```

3. In `saveSnapshot()`, wrap `SnapshotData` in `SnapshotFile`:
```kotlin
val dataJson = Json.encodeToString(SnapshotData.serializer(), snapshotData)
val checksum = sha256Hex(dataJson)
val snapshotFile = SnapshotFile(version = 2, checksum = checksum, data = snapshotData)
val fileJson = Json.encodeToString(SnapshotFile.serializer(), snapshotFile)
// write fileJson to disk (keep existing atomic write logic)
```

4. In `loadSnapshot()`:
```kotlin
val raw = snapshotFile.readText()
// Try new format first
try {
    val file = Json.decodeFromString(SnapshotFile.serializer(), raw)
    if (file.version >= 2) {
        val dataJson = Json.encodeToString(SnapshotData.serializer(), file.data)
        val actual = sha256Hex(dataJson)
        if (actual != file.checksum) {
            throw SnapshotCorruptionException(
                "Snapshot checksum mismatch: expected ${file.checksum}, got $actual"
            )
        }
    } else {
        log.warn("Loading legacy snapshot (version ${file.version}) — no checksum validation")
    }
    return file.data
} catch (e: SerializationException) {
    // Try legacy format (SnapshotData directly, no wrapper)
    log.warn("Snapshot is in legacy format — loading without checksum validation")
    return Json.decodeFromString(SnapshotData.serializer(), raw)
}
```

5. Add helper:
```kotlin
private fun sha256Hex(input: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
}
```

6. In `NocturnusAI.init()`, catch `SnapshotCorruptionException`:
```kotlin
try {
    snapshotManager.loadSnapshot()?.let { restoreFromSnapshot(it) }
} catch (e: SnapshotCorruptionException) {
    log.warn("Snapshot corrupt (${e.message}) — falling back to WAL-only recovery")
}
```

- [ ] **Step 4: Run — expect pass**

```bash
./gradlew :nocturnusai-core:test \
  --tests "com.nocturnusai.persistence.SnapshotIntegrityTest" 2>&1 | tail -5
./gradlew :nocturnusai-core:test 2>&1 | tail -5
```
Both expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add nocturnusai-core/src/main/kotlin/com/nocturnusai/persistence/SnapshotManager.kt \
        nocturnusai-core/src/test/kotlin/com/nocturnusai/persistence/SnapshotIntegrityTest.kt
git commit -m "fix(persistence): snapshot SHA-256 checksum — detect corruption on load

Saves SnapshotFile wrapper with version=2 and SHA-256 of data.
Corrupt snapshots throw SnapshotCorruptionException; NocturnusAI falls back
to WAL-only recovery. Legacy v1 snapshots load with a warning.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

### Task 8: EventBus — Subscriber Error Handling

Exceptions thrown by subscribers are silently swallowed. Add ERROR-level logging, per-subscriber error counting, and auto-unsubscribe after 5 consecutive errors.

**Files:**
- Modify: `nocturnusai-core/src/main/kotlin/com/nocturnusai/memory/EventBus.kt`
- Create: `nocturnusai-core/src/test/kotlin/com/nocturnusai/memory/EventBusErrorTest.kt`

- [ ] **Step 1: Write failing tests**

Create `EventBusErrorTest.kt`:
```kotlin
package com.nocturnusai.memory

import com.nocturnusai.core.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.util.concurrent.CopyOnWriteArrayList

class EventBusErrorTest {

    private fun makeEvent() = KnowledgeEvent.FactAsserted(
        Atom("test", listOf(Term.Identifier("x"))), "db", "t1"
    )

    @Test
    fun `throwing subscriber does not prevent other subscribers from receiving event`() {
        val bus = EventBus()
        val received = CopyOnWriteArrayList<String>()
        bus.subscribe("bad") { throw RuntimeException("I always throw") }
        bus.subscribe("good") { received.add("got it") }
        bus.publish(makeEvent())
        assertEquals(listOf("got it"), received.toList())
    }

    @Test
    fun `subscriber auto-unsubscribed after 5 errors`() {
        val bus = EventBus()
        val callCount = java.util.concurrent.atomic.AtomicInteger(0)
        bus.subscribe("flaky") {
            callCount.incrementAndGet()
            throw RuntimeException("error")
        }
        // Publish 10 events — subscriber should be removed after 5
        repeat(10) { bus.publish(makeEvent()) }
        assertTrue(callCount.get() <= 5, "Subscriber should be removed after 5 errors, got ${callCount.get()} calls")
    }

    @Test
    fun `error count is per-subscriber — good subscriber not affected`() {
        val bus = EventBus()
        val goodCount = java.util.concurrent.atomic.AtomicInteger(0)
        bus.subscribe("bad") { throw RuntimeException() }
        bus.subscribe("good") { goodCount.incrementAndGet() }
        repeat(10) { bus.publish(makeEvent()) }
        assertEquals(10, goodCount.get(), "Good subscriber should receive all 10 events")
    }
}
```

- [ ] **Step 2: Run — expect failure**

```bash
./gradlew :nocturnusai-core:test \
  --tests "com.nocturnusai.memory.EventBusErrorTest" -i 2>&1 | tail -20
```
Expected: `FAILED` — the throwing subscriber test may pass but auto-unsubscribe test will fail (subscriber still called > 5 times).

- [ ] **Step 3: Update EventBus**

Find the `Subscription` data class in `EventBus.kt`. Add error tracking:
```kotlin
private data class Subscription(
    val id: String,
    val callback: (KnowledgeEvent) -> Unit,
    val errorCount: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger(0)
)

companion object {
    private const val MAX_SUBSCRIBER_ERRORS = 5
}
```

Replace the callback invocation (wherever `sub.callback(event)` is called):
```kotlin
private fun notifySubscriber(sub: Subscription, event: KnowledgeEvent) {
    try {
        sub.callback(event)
    } catch (e: Exception) {
        val errors = sub.errorCount.incrementAndGet()
        log.error("EventBus subscriber '${sub.id}' threw on ${event::class.simpleName}: ${e.message}", e)
        if (errors >= MAX_SUBSCRIBER_ERRORS) {
            log.warn("EventBus: subscriber '${sub.id}' exceeded error threshold, auto-unsubscribing")
            unsubscribe(sub.id)
        }
    }
}
```

Update all call sites that invoke `sub.callback(event)` directly to use `notifySubscriber(sub, event)` instead.

The `subscribe(id, callback)` API signature must accept an `id: String` parameter. If the current API doesn't have an `id` parameter, add it (check `EventBus` for the current signature and update accordingly).

- [ ] **Step 4: Run — expect pass**

```bash
./gradlew :nocturnusai-core:test \
  --tests "com.nocturnusai.memory.EventBusErrorTest" 2>&1 | tail -5
./gradlew :nocturnusai-core:test 2>&1 | tail -5
```
Both expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add nocturnusai-core/src/main/kotlin/com/nocturnusai/memory/EventBus.kt \
        nocturnusai-core/src/test/kotlin/com/nocturnusai/memory/EventBusErrorTest.kt
git commit -m "fix(memory): EventBus logs subscriber errors, auto-unsubscribes after 5

Previously, subscriber exceptions were swallowed silently. Now they are
logged at ERROR with full stack trace and the subscriber is removed after
5 consecutive failures to prevent a broken subscriber from accumulating.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Phase 1B Tasks

---

### Task 9: Uniform X-Tenant-ID Enforcement

`AdminRoutes` passes null `tenantId` to `getStore()` when the header is missing, potentially leaking data across tenants. Add a Ktor route-scoped plugin that enforces the header on all tenant-scoped routes.

**Files:**
- Create: `nocturnusai-server/src/main/kotlin/com/nocturnusai/server/plugins/TenantRequired.kt`
- Modify: `nocturnusai-server/src/main/kotlin/com/nocturnusai/server/routes/AdminRoutes.kt`
- Create: `nocturnusai-server/src/test/kotlin/com/nocturnusai/server/TenantEnforcementTest.kt`

- [ ] **Step 1: Write failing tests**

Create `TenantEnforcementTest.kt`:
```kotlin
package com.nocturnusai.server

import io.ktor.client.request.*
import io.ktor.http.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class TenantEnforcementTest {

    @Test
    fun `GET admin facts without X-Tenant-ID returns 400`() = withTestApp {
        val resp = client.get("/admin/databases/default/facts") {
            // deliberately omit X-Tenant-ID
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(resp.bodyAsText().contains("X-Tenant-ID"))
    }

    @Test
    fun `GET admin rules without X-Tenant-ID returns 400`() = withTestApp {
        val resp = client.get("/admin/databases/default/rules") {
            // no header
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `POST tell without X-Tenant-ID returns 400`() = withTestApp {
        val resp = client.post("/tell") {
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"likes","args":["a","b"]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(resp.bodyAsText().contains("X-Tenant-ID"))
    }

    @Test
    fun `POST tell WITH X-Tenant-ID header succeeds (tenant must exist)`() = withTestApp {
        client.post("/admin/databases/default/tenants") {
            contentType(ContentType.Application.Json)
            setBody("""{"tenantId":"test"}""")
        }
        val resp = client.post("/tell") {
            header("X-Tenant-ID", "test")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"likes","args":["a","b"]}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
    }
}
```

- [ ] **Step 2: Run — expect failure**

```bash
./gradlew :nocturnusai-server:test \
  --tests "com.nocturnusai.server.TenantEnforcementTest" -i 2>&1 | tail -20
```
Expected: `FAILED` — admin facts/rules routes return 200 with null tenant (or 500).

- [ ] **Step 3: Create TenantRequired plugin**

Create `TenantRequired.kt`:
```kotlin
package com.nocturnusai.server.plugins

import com.nocturnusai.server.ErrorResponse
import com.nocturnusai.server.ValidationException
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*

val TenantRequired = createRouteScopedPlugin("TenantRequired") {
    onCall { call ->
        val tenantId = call.request.headers["X-Tenant-ID"]
        if (tenantId.isNullOrBlank()) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("MISSING_TENANT", "X-Tenant-ID header is required")
            )
            finish()
        }
    }
}

/** Convenience extension for getting the validated tenant ID in route handlers. */
fun ApplicationCall.tenantId(): String =
    request.headers["X-Tenant-ID"]!!  // safe — TenantRequired plugin already validated it
```

Apply it in `AdminRoutes.kt`. Change:
```kotlin
get("/admin/databases/{name}/facts") {
    val tenantId = call.request.header("X-Tenant-ID")
    ...
```
to:
```kotlin
route("/admin/databases/{name}") {
    install(TenantRequired)
    get("/facts") {
        val tenantId = call.tenantId()
        ...
    }
    get("/rules") {
        val tenantId = call.tenantId()
        ...
    }
}
```

All other routes that already call `requireTenantId()` can be converted to `install(TenantRequired)` at the route group level too, but the minimum required for this task is fixing `AdminRoutes`.

- [ ] **Step 4: Run — expect pass**

```bash
./gradlew :nocturnusai-server:test \
  --tests "com.nocturnusai.server.TenantEnforcementTest" 2>&1 | tail -5
./gradlew :nocturnusai-server:test 2>&1 | tail -5
```
Both expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add nocturnusai-server/src/main/kotlin/com/nocturnusai/server/plugins/TenantRequired.kt \
        nocturnusai-server/src/main/kotlin/com/nocturnusai/server/routes/AdminRoutes.kt \
        nocturnusai-server/src/test/kotlin/com/nocturnusai/server/TenantEnforcementTest.kt
git commit -m "fix(server): enforce X-Tenant-ID on admin routes via TenantRequired plugin

AdminRoutes /facts and /rules were passing null tenantId to getStore().
TenantRequired is a route-scoped Ktor plugin that returns 400 when the
header is missing or blank before the handler runs.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

### Task 10: Input Sanitization — Resource Names

Scope names, database names, and tenant IDs go into file paths and map keys without validation beyond a blank check. Path traversal and overly-long names must be rejected.

**Files:**
- Create: `nocturnusai-server/src/main/kotlin/com/nocturnusai/server/validation/ResourceValidator.kt`
- Modify: `nocturnusai-server/src/main/kotlin/com/nocturnusai/server/routes/ScopeRoutes.kt`
- Modify: `nocturnusai-server/src/main/kotlin/com/nocturnusai/server/routes/AdminRoutes.kt`
- Create: `nocturnusai-server/src/test/kotlin/com/nocturnusai/server/ResourceValidationTest.kt`

- [ ] **Step 1: Write failing tests**

Create `ResourceValidationTest.kt`:
```kotlin
package com.nocturnusai.server

import io.ktor.client.request.*
import io.ktor.http.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ResourceValidationTest {

    @Test
    fun `path traversal in scope name returns 400`() = withTestApp {
        client.post("/admin/databases/default/tenants") {
            contentType(ContentType.Application.Json)
            setBody("""{"tenantId":"test"}""")
        }
        val resp = client.post("/scope/fork") {
            header("X-Tenant-ID", "test")
            contentType(ContentType.Application.Json)
            setBody("""{"sourceScope":"valid","targetScope":"../etc/passwd"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(resp.bodyAsText().contains("invalid"))
    }

    @Test
    fun `scope name with dots and hyphens is valid`() = withTestApp {
        client.post("/admin/databases/default/tenants") {
            contentType(ContentType.Application.Json)
            setBody("""{"tenantId":"test"}""")
        }
        val resp = client.post("/scope/fork") {
            header("X-Tenant-ID", "test")
            contentType(ContentType.Application.Json)
            setBody("""{"sourceScope":"v1.0","targetScope":"v1.1-beta"}""")
        }
        // May return 404/400 for unknown source scope but NOT due to name validation
        assertNotEquals(HttpStatusCode.BadRequest, resp.status)  // no validation error
    }

    @Test
    fun `database name over 128 chars returns 400`() = withTestApp {
        val longName = "a".repeat(129)
        val resp = client.post("/admin/databases") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$longName"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `reserved scope name returns 400`() = withTestApp {
        client.post("/admin/databases/default/tenants") {
            contentType(ContentType.Application.Json)
            setBody("""{"tenantId":"test"}""")
        }
        val resp = client.post("/scope/fork") {
            header("X-Tenant-ID", "test")
            contentType(ContentType.Application.Json)
            setBody("""{"sourceScope":"valid","targetScope":".."}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }
}
```

- [ ] **Step 2: Run — expect failure**

```bash
./gradlew :nocturnusai-server:test \
  --tests "com.nocturnusai.server.ResourceValidationTest" -i 2>&1 | tail -20
```
Expected: `FAILED` — path traversal passes through.

- [ ] **Step 3: Create ResourceValidator**

Create `ResourceValidator.kt`:
```kotlin
package com.nocturnusai.server.validation

import com.nocturnusai.server.ValidationException

object ResourceValidator {
    private val SAFE_NAME = Regex("^[a-zA-Z0-9][a-zA-Z0-9_\\-.]{0,127}$")
    private val RESERVED = setOf("default", "admin", "system", "root", "..", ".")

    fun validateResourceName(name: String, type: String = "name") {
        if (name.isBlank())
            throw ValidationException("$type cannot be blank")
        if (!SAFE_NAME.matches(name))
            throw ValidationException(
                "$type '${name.take(32)}' is invalid — must match [a-zA-Z0-9][a-zA-Z0-9_.\\-]{0,127}"
            )
        if (name.lowercase() in RESERVED)
            throw ValidationException("'$name' is a reserved $type")
    }
}
```

Apply in `ScopeRoutes.kt` — inside the handler for `POST /scope/fork`, add before forwarding to engine:
```kotlin
ResourceValidator.validateResourceName(req.sourceScope, "scope")
ResourceValidator.validateResourceName(req.targetScope, "scope")
```

Apply similarly in `POST /scope/merge`, `POST /scope/parent`, `POST /admin/databases/{name}/tenants` (tenant ID), and the existing `validateDatabaseName()` in `AdminRoutes` (replace with `ResourceValidator.validateResourceName(name, "database")`).

- [ ] **Step 4: Run — expect pass**

```bash
./gradlew :nocturnusai-server:test \
  --tests "com.nocturnusai.server.ResourceValidationTest" 2>&1 | tail -5
./gradlew :nocturnusai-server:test 2>&1 | tail -5
```
Both expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add nocturnusai-server/src/main/kotlin/com/nocturnusai/server/validation/ResourceValidator.kt \
        nocturnusai-server/src/main/kotlin/com/nocturnusai/server/routes/ScopeRoutes.kt \
        nocturnusai-server/src/main/kotlin/com/nocturnusai/server/routes/AdminRoutes.kt \
        nocturnusai-server/src/test/kotlin/com/nocturnusai/server/ResourceValidationTest.kt
git commit -m "fix(server): input sanitization for scope/database/tenant names

Rejects path traversal (../), reserved names, and names over 128 chars.
Allows alphanumeric, hyphens, dots, underscores (matching files-safe chars).

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

### Task 11: Per-Key Rate Limiting on All Endpoints

Only auth endpoints are rate-limited today. A single API key can flood `/tell`, `/ask`, or any other endpoint without restriction. Add a per-key token bucket limiter applied to all authenticated endpoints.

**Files:**
- Create: `nocturnusai-server/src/main/kotlin/com/nocturnusai/server/auth/TokenBucketRateLimiter.kt`
- Modify: `nocturnusai-server/src/main/kotlin/com/nocturnusai/server/auth/AuthInterceptor.kt`
- Modify: `nocturnusai-server/src/main/kotlin/com/nocturnusai/server/ServerConfig.kt`
- Create: `nocturnusai-server/src/test/kotlin/com/nocturnusai/server/EndpointRateLimitTest.kt`

- [ ] **Step 1: Update TestHelpers.kt to support auth and rate-limit config**

`withTestApp` needs to accept `authEnabled` and `readerRpm` so tests can set a tight rate limit without env-var gymnastics. Add an overload to `TestHelpers.kt`:

```kotlin
/**
 * Overload for tests that need auth and/or a non-default rate limit.
 *
 * @param authEnabled  true → AUTH_ENABLED=true for this test app instance
 * @param readerRpm    READER per-minute token bucket capacity (default 120)
 */
fun withTestApp(
    authEnabled: Boolean = false,
    readerRpm: Int = 120,
    block: suspend ApplicationTestBuilder.() -> Unit
) {
    val tmpDir = Files.createTempDirectory("nocturnusai-test-").toFile()
    try {
        testApplication {
            application {
                moduleWithStorageDir(tmpDir, authEnabled = authEnabled, readerRpm = readerRpm)
            }
            block()
        }
    } finally {
        tmpDir.deleteRecursively()
    }
}
```

> `moduleWithStorageDir` must accept `authEnabled: Boolean = false` and `readerRpm: Int = 120` and pass them to `ServerConfig` / `TokenBucketRateLimiter`. The default no-arg `withTestApp` stays unchanged — it calls the 0-arg overload.

- [ ] **Step 2: Write failing tests**

Create `EndpointRateLimitTest.kt`:
```kotlin
package com.nocturnusai.server

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class EndpointRateLimitTest {

    @Test
    fun `exceeding READER rate limit returns 429 with Retry-After header`() =
        withTestApp(authEnabled = true, readerRpm = 5) {
            // 1. Bootstrap admin
            val bootstrapResp = client.post("/auth/bootstrap") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"admin","password":"nocturnusai"}""")
            }
            assertEquals(HttpStatusCode.OK, bootstrapResp.status)
            val adminKey = Json.parseToJsonElement(bootstrapResp.bodyAsText())
                .jsonObject["apiKey"]!!.jsonPrimitive.content

            // 2. Create a READER key
            val keyResp = client.post("/auth/keys") {
                header("X-API-Key", adminKey)
                header("X-Database", "default")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"reader-key","role":"READER","databaseId":"default"}""")
            }
            assertEquals(HttpStatusCode.OK, keyResp.status)
            val readerKey = Json.parseToJsonElement(keyResp.bodyAsText())
                .jsonObject["key"]!!.jsonPrimitive.content

            // 3. Create tenant so /ask doesn't 404
            client.post("/admin/databases/default/tenants") {
                header("X-API-Key", adminKey)
                header("X-Database", "default")
                contentType(ContentType.Application.Json)
                setBody("""{"tenantId":"test"}""")
            }

            // 4. Make 5 requests — all should succeed (bucket capacity = readerRpm = 5)
            repeat(5) { i ->
                val r = client.post("/ask") {
                    header("X-API-Key", readerKey)
                    header("X-Tenant-ID", "test")
                    contentType(ContentType.Application.Json)
                    setBody("""{"predicate":"foo","args":[]}""")
                }
                assertNotEquals(HttpStatusCode.TooManyRequests, r.status, "Request $i should not be rate-limited")
            }

            // 5. 6th request must be rate-limited
            val limited = client.post("/ask") {
                header("X-API-Key", readerKey)
                header("X-Tenant-ID", "test")
                contentType(ContentType.Application.Json)
                setBody("""{"predicate":"foo","args":[]}""")
            }
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertNotNull(limited.headers["Retry-After"])
            assertEquals("0", limited.headers["X-RateLimit-Remaining"])
        }

    @Test
    fun `health endpoint is NOT rate-limited`() = withTestApp {
        repeat(200) {
            assertEquals(HttpStatusCode.OK, client.get("/health").status)
        }
    }

    @Test
    fun `AUTH_ENABLED=false disables rate limiting`() = withTestApp(authEnabled = false) {
        // Tenant must exist first
        client.post("/admin/databases/default/tenants") {
            contentType(ContentType.Application.Json)
            setBody("""{"tenantId":"test"}""")
        }
        repeat(100) {
            val r = client.post("/tell") {
                header("X-Tenant-ID", "test")
                contentType(ContentType.Application.Json)
                setBody("""{"predicate":"x","args":[]}""")
            }
            assertNotEquals(HttpStatusCode.TooManyRequests, r.status)
        }
    }
}
```

- [ ] **Step 3: Run — expect failure**

```bash
./gradlew :nocturnusai-server:test \
  --tests "com.nocturnusai.server.EndpointRateLimitTest" -i 2>&1 | tail -20
```
Expected: `FAILED` — no 429 response on 6th request.

- [ ] **Step 4: Create TokenBucketRateLimiter**

Create `TokenBucketRateLimiter.kt`:
```kotlin
package com.nocturnusai.server.auth

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * Per-key token bucket rate limiter for authenticated API endpoints.
 *
 * Each key gets its own bucket. Tokens refill continuously at [tokensPerMs] rate.
 * Burst up to [bucketCapacity] tokens. Inactive keys are evicted after [evictAfterMs].
 */
class TokenBucketRateLimiter(
    private val requestsPerMinute: Int,
    private val bucketCapacity: Int = requestsPerMinute,  // start full (1 min burst)
    private val evictAfterMs: Long = 10 * 60 * 1000L  // 10 min inactivity
) {
    private val tokensPerMs: Double = requestsPerMinute / 60_000.0

    private inner class Bucket {
        val tokens = AtomicLong((bucketCapacity * 1000).toLong())  // *1000 for integer precision
        @Volatile var lastRefill: Long = System.currentTimeMillis()
        @Volatile var lastAccess: Long = System.currentTimeMillis()
    }

    private val buckets = ConcurrentHashMap<String, Bucket>()

    data class Result(
        val allowed: Boolean,
        val remaining: Int,
        val retryAfterSeconds: Long = 0,
        val resetAtEpochSeconds: Long = 0
    )

    fun tryConsume(keyId: String): Result {
        val now = System.currentTimeMillis()
        val bucket = buckets.getOrPut(keyId) { Bucket() }
        bucket.lastAccess = now

        synchronized(bucket) {
            // Refill
            val elapsed = now - bucket.lastRefill
            val newTokens = (elapsed * tokensPerMs * 1000).toLong()
            val maxTokens = (bucketCapacity * 1000).toLong()
            bucket.tokens.set(min(bucket.tokens.get() + newTokens, maxTokens))
            bucket.lastRefill = now

            return if (bucket.tokens.get() >= 1000L) {
                bucket.tokens.addAndGet(-1000L)
                val remaining = (bucket.tokens.get() / 1000).toInt()
                Result(allowed = true, remaining = remaining)
            } else {
                val msUntilToken = (1000.0 / tokensPerMs).toLong()
                Result(
                    allowed = false,
                    remaining = 0,
                    retryAfterSeconds = (msUntilToken / 1000) + 1,
                    resetAtEpochSeconds = (now + msUntilToken) / 1000
                )
            }
        }
    }

    /** Called periodically to evict inactive key buckets. */
    fun evictInactive() {
        val cutoff = System.currentTimeMillis() - evictAfterMs
        buckets.entries.removeIf { it.value.lastAccess < cutoff }
    }
}
```

Wire into `AuthInterceptor`: after successful auth (key found and valid), call `tokenBucketLimiter.tryConsume(key.id)`. If not allowed, return 429 with headers:
```kotlin
call.response.headers.append("Retry-After", result.retryAfterSeconds.toString())
call.response.headers.append("X-RateLimit-Limit", requestsPerMinute.toString())
call.response.headers.append("X-RateLimit-Remaining", "0")
call.response.headers.append("X-RateLimit-Reset", result.resetAtEpochSeconds.toString())
call.respond(HttpStatusCode.TooManyRequests,
    ErrorResponse("RATE_LIMITED", "Rate limit exceeded. Retry after ${result.retryAfterSeconds}s"))
return@intercept
```

Skip rate limiting for `PUBLIC_PATHS`. Skip if `AUTH_ENABLED=false`.

Add to `ServerConfig`:
```kotlin
val rateLimitReaderRpm: Int = System.getenv("RATE_LIMIT_READER_RPM")?.toIntOrNull() ?: 120
val rateLimitWriterRpm: Int = System.getenv("RATE_LIMIT_WRITER_RPM")?.toIntOrNull() ?: 300
val rateLimitAdminRpm: Int = System.getenv("RATE_LIMIT_ADMIN_RPM")?.toIntOrNull() ?: 600
```

Initialize separate `TokenBucketRateLimiter` instances per role in `AuthInterceptor`.

- [ ] **Step 5: Run — expect pass**

```bash
./gradlew :nocturnusai-server:test \
  --tests "com.nocturnusai.server.EndpointRateLimitTest" 2>&1 | tail -5
./gradlew :nocturnusai-server:test 2>&1 | tail -5
```
Both expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add nocturnusai-server/src/main/kotlin/com/nocturnusai/server/auth/TokenBucketRateLimiter.kt \
        nocturnusai-server/src/main/kotlin/com/nocturnusai/server/auth/AuthInterceptor.kt \
        nocturnusai-server/src/main/kotlin/com/nocturnusai/server/ServerConfig.kt \
        nocturnusai-server/src/test/kotlin/com/nocturnusai/server/TestHelpers.kt \
        nocturnusai-server/src/test/kotlin/com/nocturnusai/server/EndpointRateLimitTest.kt
git commit -m "feat(server): per-key token bucket rate limiting on all authenticated endpoints

Defaults: READER 120 rpm, WRITER 300 rpm, ADMIN 600 rpm (configurable via env).
Returns 429 with Retry-After, X-RateLimit-* headers. Health endpoint exempt.
Disabled when AUTH_ENABLED=false.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

### Task 12: Audit Log

No record exists of security-sensitive operations (key creation, tenant deletion, nukes). Implement an async append-only `AuditService` and wire it into key callsites.

**Files:**
- Create: `nocturnusai-server/src/main/kotlin/com/nocturnusai/server/audit/AuditEvent.kt`
- Create: `nocturnusai-server/src/main/kotlin/com/nocturnusai/server/audit/AuditService.kt`
- Modify: `nocturnusai-server/src/main/kotlin/com/nocturnusai/server/Application.kt`
- Modify: `nocturnusai-server/src/main/kotlin/com/nocturnusai/server/routes/AuthRoutes.kt`
- Modify: `nocturnusai-server/src/main/kotlin/com/nocturnusai/server/routes/AdminRoutes.kt`
- Modify: `nocturnusai-server/src/test/kotlin/com/nocturnusai/server/TestHelpers.kt`
- Create: `nocturnusai-server/src/test/kotlin/com/nocturnusai/server/AuditLogTest.kt`

> **DI strategy:** `AuditService` is created *before* the application starts and passed into `moduleWithStorageDir`. Tests create the instance themselves, pass it in, and call `auditService.query()` and `auditService.awaitEvent()` directly. No magic global or application-attribute lookup needed.

- [ ] **Step 1: Update TestHelpers.kt to thread AuditService through withTestApp**

Add a second `withTestApp` overload to `TestHelpers.kt` (alongside the no-arg one and the auth overload from Task 11):

```kotlin
import com.nocturnusai.server.audit.AuditService
import java.io.File

/**
 * Overload for tests that need a reference to the in-process AuditService.
 * The caller creates the AuditService, passes it in, and the app uses that same instance.
 */
fun withTestApp(
    auditService: AuditService,
    authEnabled: Boolean = true,
    block: suspend ApplicationTestBuilder.(audit: AuditService) -> Unit
) {
    val tmpDir = Files.createTempDirectory("nocturnusai-test-").toFile()
    try {
        testApplication {
            application {
                moduleWithStorageDir(tmpDir, authEnabled = authEnabled, auditService = auditService)
            }
            block(auditService)
        }
    } finally {
        auditService.close()
        tmpDir.deleteRecursively()
    }
}
```

> `moduleWithStorageDir` must accept `auditService: AuditService? = null`. When null, it creates one with the default storage dir (production path). When provided, it uses the injected instance (test path).

- [ ] **Step 2: Write failing tests**

Create `AuditLogTest.kt`:
```kotlin
package com.nocturnusai.server

import com.nocturnusai.server.audit.AuditService
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File
import java.nio.file.Files

class AuditLogTest {

    private fun tempAuditService() =
        AuditService(Files.createTempDirectory("audit-test-").toFile())

    @Test
    fun `bootstrap emits BOOTSTRAP_SUCCEEDED audit event`() {
        val audit = tempAuditService()
        withTestApp(auditService = audit) { auditService ->
            client.post("/auth/bootstrap") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"admin","password":"nocturnusai"}""")
            }
            runBlocking { auditService.awaitEvent("BOOTSTRAP_SUCCEEDED", timeoutMs = 2000) }
            val events = auditService.query(action = "BOOTSTRAP_SUCCEEDED")
            assertEquals(1, events.size)
            assertEquals("SUCCESS", events[0].outcome)
        }
    }

    @Test
    fun `tenant deletion emits TENANT_DELETED audit event with tenantId`() {
        val audit = tempAuditService()
        withTestApp(auditService = audit) { auditService ->
            // Get admin key from bootstrap
            val adminKey = Json.parseToJsonElement(
                client.post("/auth/bootstrap") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"username":"admin","password":"nocturnusai"}""")
                }.bodyAsText()
            ).jsonObject["apiKey"]!!.jsonPrimitive.content

            // Create then delete a tenant
            client.post("/admin/databases/default/tenants") {
                header("X-API-Key", adminKey)
                header("X-Database", "default")
                contentType(ContentType.Application.Json)
                setBody("""{"tenantId":"toDelete"}""")
            }
            client.delete("/admin/databases/default/tenants/toDelete") {
                header("X-API-Key", adminKey)
                header("X-Database", "default")
            }

            runBlocking { auditService.awaitEvent("TENANT_DELETED", timeoutMs = 2000) }
            val events = auditService.query(action = "TENANT_DELETED")
            assertEquals(1, events.size)
            assertEquals("toDelete", events[0].resource.id)
        }
    }

    @Test
    fun `database nuke emits DATABASE_NUKED audit event`() {
        val audit = tempAuditService()
        withTestApp(auditService = audit) { auditService ->
            val adminKey = Json.parseToJsonElement(
                client.post("/auth/bootstrap") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"username":"admin","password":"nocturnusai"}""")
                }.bodyAsText()
            ).jsonObject["apiKey"]!!.jsonPrimitive.content

            client.delete("/admin/databases/default") {
                header("X-API-Key", adminKey)
            }

            runBlocking { auditService.awaitEvent("DATABASE_NUKED", timeoutMs = 2000) }
            val events = auditService.query(action = "DATABASE_NUKED")
            assertEquals(1, events.size)
        }
    }
}
```

> The test creates an `AuditService` before the app, passes it in, and queries it directly after HTTP calls. No DI framework, no magic lookups.

- [ ] **Step 3: Run — expect failure**

```bash
./gradlew :nocturnusai-server:test --tests "com.nocturnusai.server.AuditLogTest" -i 2>&1 | tail -20
```
Expected: `FAILED` — `AuditEvent` class not found.

- [ ] **Step 4: Create AuditEvent.kt**

```kotlin
package com.nocturnusai.server.audit

import kotlinx.serialization.Serializable

@Serializable
data class AuditEvent(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val actor: AuditActor,
    val action: String,          // AuditAction enum name
    val resource: AuditResource,
    val outcome: String,         // AuditOutcome enum name
    val details: Map<String, String> = emptyMap()
)

@Serializable
data class AuditActor(
    val keyId: String? = null,
    val role: String? = null,
    val tenantId: String? = null,
    val databaseId: String? = null,
    val ipAddress: String? = null
)

@Serializable
data class AuditResource(val type: String, val id: String)

enum class AuditAction {
    KEY_CREATED, KEY_REVOKED, KEY_UPDATED, KEY_DELETED,
    BOOTSTRAP_ATTEMPTED, BOOTSTRAP_SUCCEEDED,
    AUTH_FAILED, AUTH_SUCCEEDED,
    TENANT_CREATED, TENANT_DELETED,
    DATABASE_CREATED, DATABASE_DELETED,
    DATABASE_NUKED, FACT_BULK_DELETED,
    SCOPE_DELETED, BACKUP_CREATED
}

enum class AuditOutcome { SUCCESS, FAILURE, DENIED }
```

- [ ] **Step 5: Create AuditService.kt**

```kotlin
package com.nocturnusai.server.audit

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.time.LocalDate

class AuditService(private val storageDir: File) {
    private val log = LoggerFactory.getLogger(AuditService::class.java)
    private val channel = Channel<AuditEvent>(capacity = 1000)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val events = java.util.concurrent.CopyOnWriteArrayList<AuditEvent>()  // in-memory for query

    init {
        scope.launch {
            for (event in channel) {
                try {
                    events.add(event)
                    val line = Json.encodeToString(event) + "\n"
                    currentLogFile().appendText(line)
                } catch (e: Exception) {
                    log.error("AuditService failed to write event: ${e.message}", e)
                }
            }
        }
    }

    fun record(event: AuditEvent) {
        val offered = channel.trySend(event).isSuccess
        if (!offered) log.warn("AuditService buffer full — dropping event ${event.action}")
    }

    fun query(
        action: String? = null,
        tenantId: String? = null,
        actorKeyId: String? = null,
        from: Long? = null,
        to: Long? = null,
        limit: Int = 100,
        offset: Int = 0
    ): List<AuditEvent> = events.asSequence()
        .filter { action == null || it.action == action }
        .filter { tenantId == null || it.actor.tenantId == tenantId }
        .filter { actorKeyId == null || it.actor.keyId == actorKeyId }
        .filter { from == null || it.timestamp >= from }
        .filter { to == null || it.timestamp <= to }
        .drop(offset)
        .take(limit)
        .toList()

    /** For tests: wait until an event with the given action appears. */
    suspend fun awaitEvent(action: String, timeoutMs: Long = 2000) {
        withTimeout(timeoutMs) {
            while (events.none { it.action == action }) delay(50)
        }
    }

    fun close() { scope.cancel(); channel.close() }

    private fun currentLogFile(): File {
        val date = LocalDate.now().toString()
        return storageDir.resolve("audit-$date.log").also { storageDir.mkdirs() }
    }
}
```

Wire `AuditService` into `Application.kt` — create one instance at startup, pass it to routes that need it.

In `AuthRoutes.kt`, add audit calls after bootstrap success/failure, key creation, key deletion.

In `AdminRoutes.kt`, add audit calls after tenant creation/deletion, database nuke.

- [ ] **Step 6: Run — expect pass**

```bash
./gradlew :nocturnusai-server:test --tests "com.nocturnusai.server.AuditLogTest" 2>&1 | tail -5
./gradlew :nocturnusai-server:test 2>&1 | tail -5
```

- [ ] **Step 7: Commit**

```bash
git add nocturnusai-server/src/main/kotlin/com/nocturnusai/server/audit/ \
        nocturnusai-server/src/main/kotlin/com/nocturnusai/server/Application.kt \
        nocturnusai-server/src/main/kotlin/com/nocturnusai/server/routes/AuthRoutes.kt \
        nocturnusai-server/src/main/kotlin/com/nocturnusai/server/routes/AdminRoutes.kt \
        nocturnusai-server/src/test/kotlin/com/nocturnusai/server/TestHelpers.kt \
        nocturnusai-server/src/test/kotlin/com/nocturnusai/server/AuditLogTest.kt
git commit -m "feat(server): async audit log for security-sensitive operations

AuditService writes JSON-lines to audit-YYYY-MM-DD.log via buffered channel.
Instruments: bootstrap, key CRUD, tenant CRUD, database nuke.
DI: instance created externally and injected into moduleWithStorageDir for testability.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

### Task 13: Default Credential Warning

If the server starts with factory default admin credentials, a startup warning must be logged. This is a two-line change plus one test.

**Files:**
- Modify: `nocturnusai-server/src/main/kotlin/com/nocturnusai/server/Application.kt`
- Modify: `nocturnusai-server/src/main/kotlin/com/nocturnusai/server/routes/AuthRoutes.kt`
- Create: `nocturnusai-server/src/test/kotlin/com/nocturnusai/server/DefaultCredentialTest.kt`

- [ ] **Step 1: Write failing tests**

> **Testing strategy:** The credential warning fires at startup (inside `module()` in `Application.kt`). To capture it, set up the Logback appender *before* calling `withTestApp`, then inspect logs after the app starts. Uses the `withTestApp(authEnabled = true)` overload from Task 11 — no new `withTestApp` overload needed. In a test environment, `NOCTURNUSAI_ADMIN_PASS` is unset, so defaults are used and the warning fires.

Create `DefaultCredentialTest.kt`:
```kotlin
package com.nocturnusai.server

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.slf4j.LoggerFactory

class DefaultCredentialTest {

    @Test
    fun `default credentials with AUTH_ENABLED triggers WARN log at startup`() {
        // Attach appender BEFORE starting the app — captures startup logs
        val logger = LoggerFactory.getLogger("com.nocturnusai.server") as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        try {
            withTestApp(authEnabled = true) {
                // App started. Startup logs captured in appender.
            }
        } finally {
            logger.detachAppender(appender)
        }
        val warnMessages = appender.list
            .filter { it.level.levelStr == "WARN" }
            .map { it.formattedMessage }
        assertTrue(
            warnMessages.any { "default" in it.lowercase() || "SECURITY WARNING" in it },
            "Expected a WARN about default credentials, got: $warnMessages"
        )
    }
}
```

> The negative case (custom credentials suppress the warning) requires env var injection which JVM tests cannot do cleanly. Omit it — the positive case is the critical safety gate. A comment in `Application.kt`'s warning block is sufficient documentation.

> **Logback dependency**: `ch.qos.logback:logback-classic` is already on the classpath (it's the SLF4J implementation used by the server). No new dependency needed.

- [ ] **Step 2: Run — expect failure**

```bash
./gradlew :nocturnusai-server:test \
  --tests "com.nocturnusai.server.DefaultCredentialTest" -i 2>&1 | tail -20
```
Expected: `FAILED` — no WARN log emitted.

- [ ] **Step 3: Add warning to Application.kt module init**

In `Application.kt`, inside the `module()` function (called at startup), add after reading `ServerConfig`:
```kotlin
if (config.authEnabled) {
    val defaultUser = "admin"
    val defaultPass = "nocturnusai"
    val isDefaultUser = (System.getenv("NOCTURNUSAI_ADMIN_USER") ?: defaultUser) == defaultUser
    val isDefaultPass = (System.getenv("NOCTURNUSAI_ADMIN_PASS") ?: defaultPass) == defaultPass
    if (isDefaultUser || isDefaultPass) {
        log.warn("""
            ⚠️  SECURITY WARNING: NocturnusAI is using default admin credentials.
            Set NOCTURNUSAI_ADMIN_USER and NOCTURNUSAI_ADMIN_PASS environment variables
            to secure values before deploying to production.
        """.trimIndent())
    }
}
```

- [ ] **Step 4: Run — expect pass**

```bash
./gradlew :nocturnusai-server:test \
  --tests "com.nocturnusai.server.DefaultCredentialTest" 2>&1 | tail -5
```

- [ ] **Step 5: Commit**

```bash
git add nocturnusai-server/src/main/kotlin/com/nocturnusai/server/Application.kt \
        nocturnusai-server/src/test/kotlin/com/nocturnusai/server/DefaultCredentialTest.kt
git commit -m "fix(server): WARN log when AUTH_ENABLED with default admin credentials

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

### Task 14: Request Complexity Limits

A rule with 33 body atoms or a fact with 65 args will cause exponential unification work. Reject these at the HTTP layer before they reach the engine.

**Files:**
- Create: `nocturnusai-server/src/main/kotlin/com/nocturnusai/server/validation/ComplexityLimits.kt`
- Modify existing `Validator` class (find it — likely in `ServerExtensions.kt` or a `Validator.kt` in routes)
- Create: `nocturnusai-server/src/test/kotlin/com/nocturnusai/server/ComplexityLimitsTest.kt`

- [ ] **Step 1: Locate existing Validator**

```bash
grep -r "fun validateDatabaseName\|object Validator\|class Validator" \
  nocturnusai-server/src/main/kotlin/ --include="*.kt" -l
```
This tells you which file contains existing validation logic. Note the file path.

- [ ] **Step 2: Write failing tests**

Create `ComplexityLimitsTest.kt`:
```kotlin
package com.nocturnusai.server

import io.ktor.client.request.*
import io.ktor.http.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ComplexityLimitsTest {

    @Test
    fun `fact with 65 args returns 400`() = withTestApp {
        client.post("/admin/databases/default/tenants") {
            contentType(ContentType.Application.Json); setBody("""{"tenantId":"test"}""")
        }
        val tooManyArgs = (1..65).map { "\"arg$it\"" }.joinToString(",")
        val resp = client.post("/tell") {
            header("X-Tenant-ID", "test")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"foo","args":[$tooManyArgs]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(resp.bodyAsText().contains("args"))
    }

    @Test
    fun `rule with 33 body atoms returns 400`() = withTestApp {
        client.post("/admin/databases/default/tenants") {
            contentType(ContentType.Application.Json); setBody("""{"tenantId":"test"}""")
        }
        val tooManyBodyAtoms = (1..33).map {
            """{"predicate":"cond$it","args":["?x"]}"""
        }.joinToString(",")
        val resp = client.post("/teach") {
            header("X-Tenant-ID", "test")
            contentType(ContentType.Application.Json)
            setBody("""{"head":{"predicate":"result","args":["?x"]},"body":[$tooManyBodyAtoms]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(resp.bodyAsText().contains("body"))
    }

    @Test
    fun `fact with exactly 64 args succeeds`() = withTestApp {
        client.post("/admin/databases/default/tenants") {
            contentType(ContentType.Application.Json); setBody("""{"tenantId":"test"}""")
        }
        val maxArgs = (1..64).map { "\"arg$it\"" }.joinToString(",")
        val resp = client.post("/tell") {
            header("X-Tenant-ID", "test")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"foo","args":[$maxArgs]}""")
        }
        assertNotEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `predicate name over 256 chars returns 400`() = withTestApp {
        client.post("/admin/databases/default/tenants") {
            contentType(ContentType.Application.Json); setBody("""{"tenantId":"test"}""")
        }
        val longPred = "p".repeat(257)
        val resp = client.post("/tell") {
            header("X-Tenant-ID", "test")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"$longPred","args":[]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }
}
```

- [ ] **Step 3: Create ComplexityLimits and add validation**

Create `ComplexityLimits.kt`:
```kotlin
package com.nocturnusai.server.validation

object ComplexityLimits {
    val MAX_ARGS_LENGTH      = System.getenv("MAX_ARGS_LENGTH")?.toIntOrNull() ?: 64
    val MAX_RULE_BODY_LENGTH = System.getenv("MAX_RULE_BODY_LENGTH")?.toIntOrNull() ?: 32
    val MAX_PREDICATE_LENGTH = System.getenv("MAX_PREDICATE_LENGTH")?.toIntOrNull() ?: 256
    val MAX_STRING_ARG_LEN   = System.getenv("MAX_STRING_ARG_LEN")?.toIntOrNull() ?: 4096
    val MAX_METADATA_ENTRIES = System.getenv("MAX_METADATA_ENTRIES")?.toIntOrNull() ?: 32
    val MAX_BULK_FACTS       = System.getenv("MAX_BULK_FACTS")?.toIntOrNull() ?: 1000
}
```

In your existing `Validator` object/class, add:
```kotlin
fun validateFactRequest(req: FactRequest) {
    with(ComplexityLimits) {
        if (req.predicate.length > MAX_PREDICATE_LENGTH)
            throw ValidationException("predicate too long (max $MAX_PREDICATE_LENGTH)")
        if (req.args.size > MAX_ARGS_LENGTH)
            throw ValidationException("args exceeds max $MAX_ARGS_LENGTH, got ${req.args.size}")
        req.args.forEach { arg ->
            if (arg.length > MAX_STRING_ARG_LEN)
                throw ValidationException("arg value too long (max $MAX_STRING_ARG_LEN chars)")
        }
        req.metadata?.let { meta ->
            if (meta.size > MAX_METADATA_ENTRIES)
                throw ValidationException("metadata exceeds max $MAX_METADATA_ENTRIES entries")
        }
    }
}

fun validateRuleRequest(req: RuleRequest) {
    with(ComplexityLimits) {
        // Validate head
        if (req.head.predicate.length > MAX_PREDICATE_LENGTH)
            throw ValidationException("rule head predicate too long")
        if (req.head.args.size > MAX_ARGS_LENGTH)
            throw ValidationException("rule head args exceeds max $MAX_ARGS_LENGTH")
        // Validate body
        if (req.body.size > MAX_RULE_BODY_LENGTH)
            throw ValidationException("rule body exceeds max $MAX_RULE_BODY_LENGTH, got ${req.body.size}")
        req.body.forEach { atom ->
            if (atom.predicate.length > MAX_PREDICATE_LENGTH)
                throw ValidationException("rule body atom predicate too long")
            if (atom.args.size > MAX_ARGS_LENGTH)
                throw ValidationException("rule body atom args exceeds max $MAX_ARGS_LENGTH")
            atom.args.forEach { arg ->
                if (arg.length > MAX_STRING_ARG_LEN)
                    throw ValidationException("rule body atom arg too long")
            }
        }
    }
}
```

Call `validateFactRequest(req)` in the handler for `/tell` and `/assert/fact`. Call `validateRuleRequest(req)` in `/teach` and `/assert/rule`.

- [ ] **Step 4: Run — expect pass**

```bash
./gradlew :nocturnusai-server:test \
  --tests "com.nocturnusai.server.ComplexityLimitsTest" 2>&1 | tail -5
./gradlew :nocturnusai-server:test 2>&1 | tail -5
```

- [ ] **Step 5: Commit**

```bash
git add nocturnusai-server/src/main/kotlin/com/nocturnusai/server/validation/ \
        nocturnusai-server/src/test/kotlin/com/nocturnusai/server/ComplexityLimitsTest.kt
git commit -m "fix(server): request complexity limits — max args 64, body 32, predicate 256

Configurable via env vars. Validated before facts/rules reach the engine
to prevent exponential unification work from crafted requests.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

### Task 15: AuthInterceptor — O(1) Lookup, Missing `/predicates`, Startup Assertion

The permission map is a sequential list scan. `/predicates` has no entry (passes without auth check). Add a compiled map and a startup assertion that every known route has an entry.

**Files:**
- Modify: `nocturnusai-server/src/main/kotlin/com/nocturnusai/server/auth/AuthInterceptor.kt`
- Create: `nocturnusai-server/src/test/kotlin/com/nocturnusai/server/AuthHardeningTest.kt`

- [ ] **Step 1: Add test helpers to TestHelpers.kt**

Add these `suspend` extension functions to `TestHelpers.kt` (they must be inside the `ApplicationTestBuilder` context since they use `client`):

```kotlin
import io.ktor.client.statement.*
import kotlinx.serialization.json.*

/**
 * Bootstraps with default admin creds and creates a READER key + "test" tenant.
 * Returns Pair(readerKey, "test").
 */
suspend fun ApplicationTestBuilder.bootstrapReaderKey(): Pair<String, String> {
    val adminKey = Json.parseToJsonElement(
        client.post("/auth/bootstrap") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"nocturnusai"}""")
        }.bodyAsText()
    ).jsonObject["apiKey"]!!.jsonPrimitive.content

    client.post("/admin/databases/default/tenants") {
        header("X-API-Key", adminKey)
        header("X-Database", "default")
        contentType(ContentType.Application.Json)
        setBody("""{"tenantId":"test"}""")
    }

    val readerKey = Json.parseToJsonElement(
        client.post("/auth/keys") {
            header("X-API-Key", adminKey)
            header("X-Database", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"reader-key","role":"READER","databaseId":"default"}""")
        }.bodyAsText()
    ).jsonObject["key"]!!.jsonPrimitive.content

    return readerKey to "test"
}

/**
 * Bootstraps and creates an API key with expiresAt in the past (1 second ago).
 * Returns the raw key string.
 */
suspend fun ApplicationTestBuilder.createExpiredKey(): String {
    val adminKey = Json.parseToJsonElement(
        client.post("/auth/bootstrap") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"nocturnusai"}""")
        }.bodyAsText()
    ).jsonObject["apiKey"]!!.jsonPrimitive.content

    val expiredAt = System.currentTimeMillis() - 1_000L  // 1 second ago
    return Json.parseToJsonElement(
        client.post("/auth/keys") {
            header("X-API-Key", adminKey)
            header("X-Database", "default")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"expired-key","role":"READER","databaseId":"default","expiresAt":$expiredAt}""")
        }.bodyAsText()
    ).jsonObject["key"]!!.jsonPrimitive.content
}
```

- [ ] **Step 2: Write failing tests**

Create `AuthHardeningTest.kt`:
```kotlin
package com.nocturnusai.server

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class AuthHardeningTest {

    @Test
    fun `GET predicates without auth in RBAC mode returns 401`() = withTestApp(authEnabled = true) {
        val resp = client.get("/predicates")
        // No X-API-Key header — should require auth
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `GET predicates with READER key returns 200`() = withTestApp(authEnabled = true) {
        val (readerKey, _) = bootstrapReaderKey()
        val resp = client.get("/predicates") {
            header("X-Tenant-ID", "test")
            header("X-API-Key", readerKey)
        }
        // Will be 200 (empty list) — the point is it's not 401/403
        assertNotEquals(HttpStatusCode.Unauthorized, resp.status)
        assertNotEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test
    fun `expired API key returns 401 with KEY_EXPIRED code`() = withTestApp(authEnabled = true) {
        val expiredKey = createExpiredKey()
        val resp = client.post("/tell") {
            header("X-API-Key", expiredKey)
            header("X-Tenant-ID", "test")
            contentType(ContentType.Application.Json)
            setBody("""{"predicate":"x","args":[]}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
        assertTrue(resp.bodyAsText().contains("KEY_EXPIRED"))
    }
}
```

- [ ] **Step 3: Run — expect failure**

```bash
./gradlew :nocturnusai-server:test \
  --tests "com.nocturnusai.server.AuthHardeningTest" -i 2>&1 | tail -20
```
Expected: `FAILED` — `/predicates` returns 200 without auth; expired key may return 200.

- [ ] **Step 4: Refactor AuthInterceptor permission map**

Replace the `List<Pair<RouteMatch, Permission>>` with two structures:

```kotlin
// Exact matches: O(1) lookup
private val EXACT_PERMISSIONS: Map<Pair<String, String>, Permission> = buildMap {
    // Copy all entries from existing PERMISSION_MAP that have exact paths
    put("POST" to "/tell",       Permission.FACT_WRITE)
    put("POST" to "/ask",        Permission.INFERENCE)
    put("GET"  to "/predicates", Permission.FACT_READ)   // ← ADD THIS
    // ... all other exact-path entries from existing PERMISSION_MAP
}

// Pattern matches for paths with {params}: checked second
private val PATTERN_PERMISSIONS: List<Triple<String, Regex, Permission>> = listOf(
    Triple("GET",    Regex("/admin/databases/[^/]+/facts"),   Permission.FACT_READ),
    Triple("GET",    Regex("/admin/databases/[^/]+/rules"),   Permission.RULE_READ),
    // ... convert all dynamic entries
)

private fun findPermission(method: String, path: String): Permission? {
    return EXACT_PERMISSIONS[method to path]
        ?: PATTERN_PERMISSIONS.firstOrNull { (m, r, _) -> m == method && r.matches(path) }?.third
}
```

Replace the existing sequential loop in `checkPermission()` with `findPermission(method, path)`.

Add key expiry check in `authenticate()`:
```kotlin
val key = apiKeyManager.findByHash(hash) ?: return AuthResult.Invalid
if (key.isExpired()) {
    auditService?.record(AuditEvent(
        actor = AuditActor(keyId = key.id),
        action = AuditAction.AUTH_FAILED.name,
        resource = AuditResource("key", key.id),
        outcome = AuditOutcome.DENIED.name,
        details = mapOf("reason" to "key_expired")
    ))
    return AuthResult.Expired
}
```

Handle `AuthResult.Expired` in the intercept call:
```kotlin
AuthResult.Expired -> call.respond(HttpStatusCode.Unauthorized,
    ErrorResponse("KEY_EXPIRED", "API key has expired"))
```

Add `ApiKey.isExpired()` extension if not present:
```kotlin
fun ApiKey.isExpired(): Boolean = expiresAt != null && System.currentTimeMillis() > expiresAt
```

- [ ] **Step 5: Run — expect pass**

```bash
./gradlew :nocturnusai-server:test \
  --tests "com.nocturnusai.server.AuthHardeningTest" 2>&1 | tail -5
./gradlew :nocturnusai-server:test 2>&1 | tail -5
```
Both expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add nocturnusai-server/src/main/kotlin/com/nocturnusai/server/auth/AuthInterceptor.kt \
        nocturnusai-server/src/test/kotlin/com/nocturnusai/server/TestHelpers.kt \
        nocturnusai-server/src/test/kotlin/com/nocturnusai/server/AuthHardeningTest.kt
git commit -m "fix(auth): O(1) permission map, add /predicates entry, expired key 401

EXACT_PERMISSIONS map replaces sequential list scan.
/predicates now requires FACT_READ permission (was unprotected).
Expired keys return 401 KEY_EXPIRED with audit event.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

### Task 16: Final Integration — Run Full Test Suite

Both modules complete. Run everything, fix any regressions, tag the Phase 1 completion.

**Files:** None (verification only)

- [ ] **Step 1: Run full core test suite**

```bash
./gradlew :nocturnusai-core:test 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`. Note any test failures and fix them before proceeding.

- [ ] **Step 2: Run full server test suite**

```bash
./gradlew :nocturnusai-server:test 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run complete build**

```bash
./gradlew build 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Verify Phase 1 acceptance criteria**

Work through the checklist in `docs/superpowers/specs/2026-03-23-nocturnusai-quality-and-features-design.md` under **Phase 1A — Done When** and **Phase 1B — Done When**. Each item should have a passing test proving it. If any item has no test yet, add it now.

- [ ] **Step 5: Commit final integration**

```bash
git add -A  # any remaining fixes from integration
git commit -m "chore: Phase 1 complete — all correctness and security tests passing

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Execution Notes

**Parallelism:** Tasks 1–8 (Phase 1A) and Tasks 9–15 (Phase 1B) can be worked simultaneously by two people. The only shared dependency is the final Task 16 integration step.

**Order within 1A:** Tasks 1–8 are independent of each other. The parser (Task 1) and proof trees (Task 2) are the highest-visibility fixes; do them first.

**Order within 1B:** Tasks 9 and 10 (tenant enforcement, input validation) are the highest security priority. Do those first. Task 12 (audit log) requires `AuditService` to be available before Task 15 (auth hardening) can reference `auditService` in `AuthInterceptor`.

**If a test helper doesn't exist:** `TestHelpers.kt` in the server test module has `withTestApp { }` and basic tenant/database setup. Add any missing helpers (`bootstrapAdminKey()`, `createExpiredKey()`, etc.) to that file before writing the test that needs them.

**Gradle tip:** Run a single test class with:
```bash
./gradlew :nocturnusai-core:test --tests "fully.qualified.TestClass" -i 2>&1 | grep -E "PASS|FAIL|ERROR" | head -30
```
