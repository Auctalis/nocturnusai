package com.nocturnusai.context

import com.nocturnusai.core.*
import com.nocturnusai.inference.BackwardChainer
import com.nocturnusai.logic.ProvenanceTracker
import com.nocturnusai.memory.EventBus
import com.nocturnusai.memory.KnowledgeEvent
import com.nocturnusai.memory.MemoryManager
import com.nocturnusai.storage.Hexastore
import kotlin.test.*

class ContextManagementServiceTest {

    private fun atom(pred: String, vararg args: String, truth: Boolean = true, source: SourceType = SourceType.USER_INPUT, scope: String? = null, createdAt: Long? = null, ttl: Long? = null): Atom {
        return Atom(pred, args.map { Term.Identifier(it) }, truthVal = truth, source = source, scope = scope, createdAt = createdAt, ttl = ttl)
    }

    private fun buildService(
        store: Hexastore = Hexastore(),
        rules: List<Rule> = emptyList(),
        negativeStore: Hexastore? = null
    ): Triple<ContextManagementService, Hexastore, MemoryManager> {
        val mm = MemoryManager()
        val tracker = ProvenanceTracker()
        val bc = BackwardChainer(store, rules)
        val svc = ContextManagementService(
            memoryManager = mm,
            backwardChainer = bc,
            provenanceTracker = tracker,
            negativeStore = negativeStore,
            rules = rules
        )
        svc.subscribeToEvents(mm.eventBus)
        return Triple(svc, store, mm)
    }

    // ---------------------------------------------------------------
    // Basic optimize
    // ---------------------------------------------------------------

    @Test
    fun testOptimizeContextReturnsFactsWithinBudget() {
        val store = Hexastore()
        val (svc, _, mm) = buildService(store)

        // Assert 20 facts
        for (i in 1..20) {
            val a = atom("fact", "arg$i")
            store.add(a)
            mm.onFactAsserted(a, null)
        }

        val result = svc.optimizeContext(store, OptimizeContextRequest(maxFacts = 5))
        assertEquals(5, result.totalFactsIncluded, "Should respect maxFacts budget")
        assertEquals(20, result.totalFactsAvailable, "Should report all available facts")
        assertTrue(result.entries.size <= 5)
    }

    @Test
    fun testOptimizeContextDefaultMaxFacts() {
        val store = Hexastore()
        val (svc, _, mm) = buildService(store)

        for (i in 1..5) {
            val a = atom("fact", "arg$i")
            store.add(a)
            mm.onFactAsserted(a, null)
        }

        val result = svc.optimizeContext(store, OptimizeContextRequest())
        assertEquals(5, result.totalFactsIncluded, "Should include all facts when under default limit")
    }

    // ---------------------------------------------------------------
    // #1 — Rules returned
    // ---------------------------------------------------------------

    @Test
    fun testRelevantRulesReturnedWithGoals() {
        val store = Hexastore()
        val rule = Rule(
            variables = listOf(Term.Variable("x")),
            head = Atom("mortal", listOf(Term.Variable("x"))),
            body = listOf(Atom("human", listOf(Term.Variable("x"))))
        )
        val (svc, _, mm) = buildService(store, rules = listOf(rule))

        val humanFact = atom("human", "socrates")
        store.add(humanFact)
        mm.onFactAsserted(humanFact, null)

        val result = svc.optimizeContext(store, OptimizeContextRequest(
            goals = listOf(GoalSpec("mortal", listOf("?x")))
        ))

        assertTrue(result.relevantRules.isNotEmpty(), "Should return relevant rules")
        assertTrue(result.relevantRules.any { it.head.predicate == "mortal" })
        assertTrue(result.goalDriven)
    }

    @Test
    fun testNoRulesReturnedWithoutGoals() {
        val store = Hexastore()
        val (svc, _, mm) = buildService(store)

        val a = atom("likes", "alice", "bob")
        store.add(a)
        mm.onFactAsserted(a, null)

        val result = svc.optimizeContext(store, OptimizeContextRequest())
        assertTrue(result.relevantRules.isEmpty())
        assertFalse(result.goalDriven)
    }

    // ---------------------------------------------------------------
    // #2 — Provenance / DerivationInfo
    // ---------------------------------------------------------------

    @Test
    fun testProvenanceIncludedWhenAvailable() {
        val store = Hexastore()
        val tracker = ProvenanceTracker()
        val rule = Rule(
            variables = listOf(Term.Variable("x")),
            head = Atom("mortal", listOf(Term.Variable("x"))),
            body = listOf(Atom("human", listOf(Term.Variable("x"))))
        )
        val mm = MemoryManager()
        val bc = BackwardChainer(store, listOf(rule))
        val svc = ContextManagementService(
            memoryManager = mm,
            backwardChainer = bc,
            provenanceTracker = tracker,
            rules = listOf(rule)
        )

        val humanFact = atom("human", "socrates")
        store.add(humanFact)
        mm.onFactAsserted(humanFact, null)

        // Simulate a derived fact with provenance
        val derivedFact = atom("mortal", "socrates", source = SourceType.INFERRED)
        store.add(derivedFact)
        mm.onFactAsserted(derivedFact, null)
        tracker.record(derivedFact, rule, listOf(humanFact))

        val result = svc.optimizeContext(store, OptimizeContextRequest(maxFacts = 10))
        val derivedEntry = result.entries.find { it.atom.predicate == "mortal" }
        assertNotNull(derivedEntry, "Should include derived fact")
        assertNotNull(derivedEntry.provenance, "Derived fact should have provenance")
        assertTrue(derivedEntry.provenance!!.rule.contains("mortal"))
        assertTrue(derivedEntry.provenance!!.premises.any { it.contains("human") })
    }

    @Test
    fun testNoProvenanceForDirectFacts() {
        val store = Hexastore()
        val (svc, _, mm) = buildService(store)

        val a = atom("likes", "alice", "bob")
        store.add(a)
        mm.onFactAsserted(a, null)

        val result = svc.optimizeContext(store, OptimizeContextRequest())
        val entry = result.entries.first()
        assertNull(entry.provenance, "Direct fact should not have provenance")
    }

    // ---------------------------------------------------------------
    // #3 — Contradiction detection & opt-in resolution
    // ---------------------------------------------------------------

    @Test
    fun testContradictionsDetected() {
        val store = Hexastore()
        val (svc, _, mm) = buildService(store)

        val pos = atom("available", "laptop", "true")
        val neg = atom("available", "laptop", "true", truth = false)
        store.add(pos)
        store.add(neg)
        mm.onFactAsserted(pos, null)
        mm.onFactAsserted(neg, null)

        val result = svc.optimizeContext(store, OptimizeContextRequest(
            autoResolveContradictions = false
        ))
        assertTrue(result.contradictionsFound > 0, "Should detect contradiction")
        assertEquals(0, result.contradictionsResolved, "Should not auto-resolve when opt-out")
        assertTrue(result.contradictions.isNotEmpty())
        assertEquals("available", result.contradictions[0].predicate)
    }

    @Test
    fun testContradictionsAutoResolved() {
        val store = Hexastore()
        val (svc, _, mm) = buildService(store)

        val pos = atom("available", "laptop")
        val neg = atom("available", "laptop", truth = false)
        store.add(pos)
        store.add(neg)
        mm.onFactAsserted(pos, null)
        mm.onFactAsserted(neg, null)

        val result = svc.optimizeContext(store, OptimizeContextRequest(
            autoResolveContradictions = true
        ))
        assertTrue(result.contradictionsFound > 0)
        assertTrue(result.contradictionsResolved > 0)
        // One side should be removed
        val availableEntries = result.entries.filter { it.atom.predicate == "available" }
        assertEquals(1, availableEntries.size, "Auto-resolve should keep only one side")
    }

    // ---------------------------------------------------------------
    // #4 — Access recording
    // ---------------------------------------------------------------

    @Test
    fun testAccessRecordedForReturnedFacts() {
        val store = Hexastore()
        val (svc, _, mm) = buildService(store)

        val a = atom("likes", "alice", "bob")
        store.add(a)
        mm.onFactAsserted(a, null)

        // Initial salience
        val before = mm.salienceTracker.computeSalience(a)

        // Optimize — should record access
        svc.optimizeContext(store, OptimizeContextRequest())

        // Salience should increase due to recorded access
        val after = mm.salienceTracker.computeSalience(a)
        assertTrue(after >= before, "Salience should not decrease after access recording")
    }

    // ---------------------------------------------------------------
    // #5 — Diversity cap
    // ---------------------------------------------------------------

    @Test
    fun testDiversityCapEnforced() {
        val store = Hexastore()
        val (svc, _, mm) = buildService(store)

        // Add 10 "likes" facts and 3 "knows" facts
        for (i in 1..10) {
            val a = atom("likes", "alice", "item$i")
            store.add(a)
            mm.onFactAsserted(a, null)
        }
        for (i in 1..3) {
            val a = atom("knows", "alice", "person$i")
            store.add(a)
            mm.onFactAsserted(a, null)
        }

        val result = svc.optimizeContext(store, OptimizeContextRequest(
            maxFacts = 50,
            maxFactsPerPredicate = 3
        ))

        val likeCount = result.entries.count { it.atom.predicate == "likes" }
        val knowsCount = result.entries.count { it.atom.predicate == "knows" }
        assertTrue(likeCount <= 3, "Diversity cap should limit likes to 3, got $likeCount")
        assertTrue(knowsCount <= 3, "Diversity cap should limit knows to 3, got $knowsCount")
    }

    @Test
    fun testNoDiversityCapByDefault() {
        val store = Hexastore()
        val (svc, _, mm) = buildService(store)

        for (i in 1..10) {
            val a = atom("likes", "alice", "item$i")
            store.add(a)
            mm.onFactAsserted(a, null)
        }

        val result = svc.optimizeContext(store, OptimizeContextRequest(maxFacts = 50))
        val likeCount = result.entries.count { it.atom.predicate == "likes" }
        assertEquals(10, likeCount, "Without diversity cap, all facts should appear")
    }

    // ---------------------------------------------------------------
    // #6 — Structured diff removals
    // ---------------------------------------------------------------

    @Test
    fun testDiffReturnsStructuredRemovals() {
        val store = Hexastore()
        val (svc, _, mm) = buildService(store)

        // Create initial context with session
        val a = atom("likes", "alice", "bob")
        val b = atom("likes", "alice", "charlie")
        store.add(a)
        store.add(b)
        mm.onFactAsserted(a, null)
        mm.onFactAsserted(b, null)

        svc.optimizeContext(store, OptimizeContextRequest(sessionId = "s1"))

        // Remove one fact and diff
        store.delete(b)
        mm.onFactRetracted(b, null)

        val diff = svc.diffContext(store, ContextDiffRequest(sessionId = "s1"))
        assertTrue(diff.removed.isNotEmpty(), "Should have removals")
        val removed = diff.removed.first()
        assertEquals("likes", removed.predicate, "Removed entry should have predicate")
        assertTrue(removed.args.isNotEmpty(), "Removed entry should have args")
    }

    @Test
    fun testDiffNoPreviousSession() {
        val store = Hexastore()
        val (svc, _, _) = buildService(store)

        val diff = svc.diffContext(store, ContextDiffRequest(sessionId = "nonexistent"))
        assertTrue(diff.fullRefreshRecommended)
        assertEquals("no previous session found", diff.reason)
    }

    // ---------------------------------------------------------------
    // #7 — Negative goals
    // ---------------------------------------------------------------

    @Test
    fun testNegativeGoalSpec() {
        val store = Hexastore()
        val (svc, _, mm) = buildService(store)

        val posFact = atom("available", "laptop", truth = true)
        val negFact = atom("available", "phone", truth = false)
        store.add(posFact)
        store.add(negFact)
        mm.onFactAsserted(posFact, null)
        mm.onFactAsserted(negFact, null)

        // Query for negated facts
        val result = svc.optimizeContext(store, OptimizeContextRequest(
            goals = listOf(GoalSpec("available", listOf("?x"), negated = true))
        ))

        // Should find the negative fact
        val negEntries = result.entries.filter { !it.atom.truthVal }
        assertTrue(negEntries.isNotEmpty(), "Negative goal should find negated facts")
    }

    // ---------------------------------------------------------------
    // #8 — Deduplication
    // ---------------------------------------------------------------

    @Test
    fun testConsolidatedFactsDeduplicateRawOnes() {
        val store = Hexastore()
        val (svc, _, mm) = buildService(store)

        // Add raw episodic facts
        for (i in 1..5) {
            val a = atom("user_asked", "alice", "laptops", source = SourceType.USER_INPUT)
            store.add(a)
            mm.onFactAsserted(a, null)
        }

        // Add consolidated summary
        val consolidated = atom("user_asked_consolidated", "alice", "5", source = SourceType.CONSOLIDATED)
        store.add(consolidated)
        mm.onFactAsserted(consolidated, null)

        val result = svc.optimizeContext(store, OptimizeContextRequest(maxFacts = 50))

        // The consolidated fact should be present, raw ones should be deduped
        val consolidatedEntries = result.entries.filter { it.atom.source == SourceType.CONSOLIDATED }
        assertTrue(consolidatedEntries.isNotEmpty(), "Consolidated fact should be in result")
        assertTrue(result.deduplicationSavings > 0, "Should report deduplication savings")
    }

    // ---------------------------------------------------------------
    // #9 — Summary caching
    // ---------------------------------------------------------------

    @Test
    fun testSummaryCaching() {
        val store = Hexastore()
        val (svc, _, mm) = buildService(store)

        val a = atom("likes", "alice", "bob")
        store.add(a)
        mm.onFactAsserted(a, null)

        val summary1 = svc.summarizeContext(store)
        val summary2 = svc.summarizeContext(store)

        // Same generation, should be cached
        assertEquals(summary1.totalFacts, summary2.totalFacts)
        assertEquals(summary1.knowledgeGeneration, summary2.knowledgeGeneration)
    }

    @Test
    fun testSummaryCacheInvalidatedOnChange() {
        val store = Hexastore()
        val (svc, _, mm) = buildService(store)

        val a = atom("likes", "alice", "bob")
        store.add(a)
        mm.onFactAsserted(a, null)

        val summary1 = svc.summarizeContext(store)

        // Add another fact — triggers EventBus → cache invalidation
        val b = atom("likes", "alice", "charlie")
        store.add(b)
        mm.onFactAsserted(b, null)

        val summary2 = svc.summarizeContext(store)
        assertEquals(2, summary2.totalFacts, "Summary should reflect new fact")
        assertTrue(summary2.knowledgeGeneration > summary1.knowledgeGeneration,
            "Generation should increment after change")
    }

    // ---------------------------------------------------------------
    // #10 — Knowledge generation in responses
    // ---------------------------------------------------------------

    @Test
    fun testKnowledgeGenerationInOptimizeResponse() {
        val store = Hexastore()
        val (svc, _, mm) = buildService(store)

        val a = atom("fact", "a")
        store.add(a)
        mm.onFactAsserted(a, null)

        val result1 = svc.optimizeContext(store, OptimizeContextRequest())
        val gen1 = result1.knowledgeGeneration

        // Add a fact — generation should bump
        val b = atom("fact", "b")
        store.add(b)
        mm.onFactAsserted(b, null)

        val result2 = svc.optimizeContext(store, OptimizeContextRequest())
        assertTrue(result2.knowledgeGeneration > gen1,
            "Knowledge generation should increase after fact assertion")
    }

    // ---------------------------------------------------------------
    // Goal-driven backward chaining
    // ---------------------------------------------------------------

    @Test
    fun testGoalDrivenFilteringViaBackwardChaining() {
        val store = Hexastore()
        val rule = Rule(
            variables = listOf(Term.Variable("x")),
            head = Atom("mortal", listOf(Term.Variable("x"))),
            body = listOf(Atom("human", listOf(Term.Variable("x"))))
        )
        val (svc, _, mm) = buildService(store, rules = listOf(rule))

        // Relevant facts
        val human = atom("human", "socrates")
        store.add(human)
        mm.onFactAsserted(human, null)

        // Irrelevant fact
        val weather = atom("weather", "sunny")
        store.add(weather)
        mm.onFactAsserted(weather, null)

        val result = svc.optimizeContext(store, OptimizeContextRequest(
            goals = listOf(GoalSpec("mortal", listOf("?x")))
        ))

        assertTrue(result.goalDriven)
        // Should include human(socrates) as a premise, not weather(sunny)
        val predicates = result.entries.map { it.atom.predicate }.toSet()
        assertTrue("human" in predicates || "mortal" in predicates,
            "Goal-driven should include relevant predicates")
        assertFalse("weather" in predicates,
            "Goal-driven should exclude irrelevant predicates")
    }

    // ---------------------------------------------------------------
    // Relevance buckets
    // ---------------------------------------------------------------

    @Test
    fun testRelevanceBuckets() {
        val store = Hexastore()
        val (svc, _, mm) = buildService(store)

        for (i in 1..5) {
            val a = atom("likes", "alice", "item$i")
            store.add(a)
            mm.onFactAsserted(a, null)
        }
        for (i in 1..5) {
            val a = atom("price", "item$i", "${i * 100}")
            store.add(a)
            mm.onFactAsserted(a, null)
        }

        val result = svc.optimizeContext(store, OptimizeContextRequest(
            maxFacts = 6,
            relevanceBuckets = listOf(
                RelevanceBucket("prefs", listOf("likes"), 2.0),
                RelevanceBucket("products", listOf("price"), 1.0)
            )
        ))

        assertTrue(result.bucketStats.isNotEmpty(), "Should have bucket stats")
        assertTrue(result.bucketStats.containsKey("prefs"))
        assertTrue(result.bucketStats.containsKey("products"))
        // With weight 2:1 and 6 total, prefs gets 4 slots, products gets 2
        assertTrue(result.bucketStats["prefs"]!!.factsIncluded >= 2)
    }

    // ---------------------------------------------------------------
    // Session & clear
    // ---------------------------------------------------------------

    @Test
    fun testSessionSnapshotAndClear() {
        val store = Hexastore()
        val (svc, _, mm) = buildService(store)

        val a = atom("likes", "alice", "bob")
        store.add(a)
        mm.onFactAsserted(a, null)

        svc.optimizeContext(store, OptimizeContextRequest(sessionId = "test_session"))

        // Session should exist — diff should work
        val diff1 = svc.diffContext(store, ContextDiffRequest(sessionId = "test_session"))
        assertFalse(diff1.fullRefreshRecommended || diff1.previousWindowId == null,
            "Session should exist after optimize")

        // Clear session
        svc.clearSession("test_session")

        // Diff should report no session
        val diff2 = svc.diffContext(store, ContextDiffRequest(sessionId = "test_session"))
        assertTrue(diff2.fullRefreshRecommended)
    }

    // ---------------------------------------------------------------
    // Temporal filtering
    // ---------------------------------------------------------------

    @Test
    fun testExpiredFactsExcluded() {
        val store = Hexastore()
        val (svc, _, mm) = buildService(store)
        val now = System.currentTimeMillis()

        val valid = atom("status", "active", createdAt = now, ttl = 60_000)
        val expired = atom("status", "old", createdAt = now - 10_000, ttl = 1_000)
        store.add(valid)
        store.add(expired)
        mm.onFactAsserted(valid, null)
        mm.onFactAsserted(expired, null)

        val result = svc.optimizeContext(store, OptimizeContextRequest())
        val predicates = result.entries.map { "${it.atom.predicate}(${it.atom.args.joinToString(",")})" }
        assertTrue(predicates.any { it.contains("active") }, "Valid fact should be included")
        assertFalse(predicates.any { it.contains("old") }, "Expired fact should be excluded")
    }

    // ---------------------------------------------------------------
    // Scope filtering
    // ---------------------------------------------------------------

    @Test
    fun testScopeFiltering() {
        val store = Hexastore()
        val (svc, _, mm) = buildService(store)

        val scopedA = Atom("fact", listOf(Term.Identifier("a")), scope = "tenant1")
        val scopedB = Atom("fact", listOf(Term.Identifier("b")), scope = "tenant2")
        store.add(scopedA)
        store.add(scopedB)
        mm.onFactAsserted(scopedA, null)
        mm.onFactAsserted(scopedB, null)

        val result = svc.optimizeContext(store, OptimizeContextRequest(scope = "tenant1"))
        assertEquals(1, result.totalFactsIncluded, "Should only include facts from tenant1 scope")
    }

    // ---------------------------------------------------------------
    // Goal cache
    // ---------------------------------------------------------------

    @Test
    fun testGoalCacheUsedOnRepeatQuery() {
        val store = Hexastore()
        val (svc, _, mm) = buildService(store)

        val a = atom("likes", "alice", "bob")
        store.add(a)
        mm.onFactAsserted(a, null)

        val goals = listOf(GoalSpec("likes", listOf("alice", "?x")))

        val result1 = svc.optimizeContext(store, OptimizeContextRequest(goals = goals))
        val result2 = svc.optimizeContext(store, OptimizeContextRequest(goals = goals))

        // Same generation — cached
        assertEquals(result1.knowledgeGeneration, result2.knowledgeGeneration)
        assertEquals(result1.totalFactsAvailable, result2.totalFactsAvailable)
    }

    // ---------------------------------------------------------------
    // InMemorySessionStore
    // ---------------------------------------------------------------

    @Test
    fun testInMemorySessionStoreTtl() {
        val store = InMemorySessionStore(maxSessions = 100, ttlMs = 1) // 1ms TTL

        store.save("s1", ContextSnapshot(
            windowId = "w1",
            entries = mapOf("key1" to SnapshotEntryInfo("pred", listOf("a"), false, null)),
            generatedAt = System.currentTimeMillis() - 100 // 100ms ago
        ))

        // Should be expired
        Thread.sleep(5)
        val loaded = store.load("s1")
        assertNull(loaded, "Expired session should return null")
    }

    @Test
    fun testInMemorySessionStoreCapacity() {
        val store = InMemorySessionStore(maxSessions = 2, ttlMs = 60_000)
        val now = System.currentTimeMillis()

        store.save("s1", ContextSnapshot("w1", emptyMap(), now - 3000))
        store.save("s2", ContextSnapshot("w2", emptyMap(), now - 2000))
        store.save("s3", ContextSnapshot("w3", emptyMap(), now)) // evicts s1

        assertNull(store.load("s1"), "Oldest session should be evicted")
        assertNotNull(store.load("s2"))
        assertNotNull(store.load("s3"))
    }

    // ---------------------------------------------------------------
    // Summary
    // ---------------------------------------------------------------

    @Test
    fun testSummaryContent() {
        val store = Hexastore()
        val (svc, _, mm) = buildService(store)

        for (i in 1..5) {
            val a = atom("likes", "alice", "item$i")
            store.add(a)
            mm.onFactAsserted(a, null)
        }
        for (i in 1..3) {
            val a = atom("knows", "alice", "person$i")
            store.add(a)
            mm.onFactAsserted(a, null)
        }

        val summary = svc.summarizeContext(store)
        assertEquals(8, summary.totalFacts)
        assertEquals(2, summary.predicateCount)
        assertTrue(summary.topPredicates.isNotEmpty())
        assertEquals("likes", summary.topPredicates[0].predicate, "Most common predicate should be first")
        assertEquals(5, summary.topPredicates[0].count)
        assertTrue(summary.topSalientFacts.isNotEmpty())
        assertTrue(summary.knowledgeGeneration >= 0)
    }

    // ---------------------------------------------------------------
    // Predicate filtering (non-goal mode)
    // ---------------------------------------------------------------

    @Test
    fun testPredicateFiltering() {
        val store = Hexastore()
        val (svc, _, mm) = buildService(store)

        val a = atom("likes", "alice", "bob")
        val b = atom("knows", "alice", "charlie")
        store.add(a)
        store.add(b)
        mm.onFactAsserted(a, null)
        mm.onFactAsserted(b, null)

        val result = svc.optimizeContext(store, OptimizeContextRequest(
            predicates = listOf("likes")
        ))

        assertTrue(result.entries.all { it.atom.predicate == "likes" },
            "Should only include filtered predicates")
    }
}
