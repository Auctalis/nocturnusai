package com.nocturnusai.memory

import com.nocturnusai.core.Atom
import com.nocturnusai.core.SourceType
import com.nocturnusai.core.Term
import com.nocturnusai.storage.Hexastore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryManagerTest {

    @Test
    fun testTemporalQueryFiltering() {
        val store = Hexastore()
        val mm = MemoryManager()

        val now = System.currentTimeMillis()

        // Fact valid from now to now+1hour
        val fact1 = Atom(
            "location", listOf(Term.Identifier("alice"), Term.Identifier("office")),
            createdAt = now, validFrom = now, validUntil = now + 3_600_000
        )
        // Fact valid from now-2hours to now-1hour (expired)
        val fact2 = Atom(
            "location", listOf(Term.Identifier("alice"), Term.Identifier("home")),
            createdAt = now - 7_200_000, validFrom = now - 7_200_000, validUntil = now - 3_600_000
        )
        // Fact with no temporal bounds (always valid)
        val fact3 = Atom(
            "location", listOf(Term.Identifier("bob"), Term.Identifier("gym"))
        )

        store.add(fact1)
        store.add(fact2)
        store.add(fact3)

        val pattern = Atom("location", listOf(Term.Variable("who"), Term.Variable("where")))

        // Query at current time: should see fact1 and fact3, not fact2
        val currentResults = mm.queryAtTime(store, pattern, now)
        assertEquals(2, currentResults.size, "Should see 2 facts valid at current time")
        assertTrue(currentResults.any { it.args[0] == Term.Identifier("alice") && it.args[1] == Term.Identifier("office") })
        assertTrue(currentResults.any { it.args[0] == Term.Identifier("bob") })

        // Query at now-90min: should see fact2 and fact3
        val pastResults = mm.queryAtTime(store, pattern, now - 5_400_000)
        assertEquals(2, pastResults.size, "Should see 2 facts valid 90min ago")
        assertTrue(pastResults.any { it.args[1] == Term.Identifier("home") })
    }

    @Test
    fun testTtlExpiration() {
        val now = System.currentTimeMillis()

        val factWithTtl = Atom(
            "session", listOf(Term.Identifier("user1"), Term.Identifier("active")),
            createdAt = now - 5000, ttl = 3000 // Created 5s ago with 3s TTL = expired
        )
        assertTrue(factWithTtl.isExpired(now), "Fact with elapsed TTL should be expired")

        val factNotExpired = Atom(
            "session", listOf(Term.Identifier("user2"), Term.Identifier("active")),
            createdAt = now - 1000, ttl = 5000 // Created 1s ago with 5s TTL = still valid
        )
        assertTrue(!factNotExpired.isExpired(now), "Fact within TTL should not be expired")
    }

    @Test
    fun testSalienceTracking() {
        val tracker = SalienceTracker()
        val atom = Atom("likes", listOf(Term.Identifier("alice"), Term.Identifier("pizza")))

        // New atom with no accesses should have low salience
        val initialSalience = tracker.computeSalience(atom)
        assertTrue(initialSalience < 0.2, "Untracked atom should have low salience")

        // Record creation and accesses
        tracker.recordCreation(atom)
        repeat(10) { tracker.recordAccess(atom) }

        val afterAccess = tracker.computeSalience(atom)
        assertTrue(afterAccess > initialSalience, "Frequently accessed atom should have higher salience")
        assertTrue(afterAccess > 0.3, "10x accessed atom should have meaningful salience")
    }

    @Test
    fun testSaliencePriority() {
        val tracker = SalienceTracker()
        val importantFact = Atom("goal", listOf(Term.Identifier("agent"), Term.Identifier("find_answer")))
        val trivialFact = Atom("log", listOf(Term.Identifier("agent"), Term.Identifier("step_1")))

        tracker.recordCreation(importantFact)
        tracker.recordCreation(trivialFact)

        // Boost the important fact
        tracker.setPriority(importantFact, 1.0)
        tracker.setPriority(trivialFact, 0.1)

        val importantScore = tracker.computeSalience(importantFact)
        val trivialScore = tracker.computeSalience(trivialFact)

        assertTrue(importantScore > trivialScore, "High-priority fact should have higher salience")
    }

    @Test
    fun testEventBusSubscription() {
        val bus = EventBus()
        val receivedEvents = mutableListOf<KnowledgeEvent>()

        // Subscribe to parent predicate events
        val subId = bus.subscribe(
            predicatePattern = "parent",
            eventTypes = setOf("fact_asserted")
        ) { event -> receivedEvents.add(event) }

        // Publish matching event
        bus.publish(KnowledgeEvent.FactAsserted(
            atom = Atom("parent", listOf(Term.Identifier("alice"), Term.Identifier("bob"))),
            tenantId = "default"
        ))

        // Publish non-matching event
        bus.publish(KnowledgeEvent.FactAsserted(
            atom = Atom("likes", listOf(Term.Identifier("alice"), Term.Identifier("pizza"))),
            tenantId = "default"
        ))

        assertEquals(1, receivedEvents.size, "Should only receive events matching predicate pattern")
        assertEquals("parent", (receivedEvents[0] as KnowledgeEvent.FactAsserted).atom.predicate)

        bus.unsubscribe(subId)
    }

    @Test
    fun testEventBusPrefixPattern() {
        val bus = EventBus()
        val receivedEvents = mutableListOf<KnowledgeEvent>()

        bus.subscribe(
            predicatePattern = "user_*",
            eventTypes = setOf("fact_asserted")
        ) { event -> receivedEvents.add(event) }

        bus.publish(KnowledgeEvent.FactAsserted(
            atom = Atom("user_preference", listOf(Term.Identifier("dark_mode"), Term.Identifier("true"))),
            tenantId = "default"
        ))
        bus.publish(KnowledgeEvent.FactAsserted(
            atom = Atom("user_location", listOf(Term.Identifier("alice"), Term.Identifier("office"))),
            tenantId = "default"
        ))
        bus.publish(KnowledgeEvent.FactAsserted(
            atom = Atom("system_status", listOf(Term.Identifier("healthy"))),
            tenantId = "default"
        ))

        assertEquals(2, receivedEvents.size, "Should match both user_* predicates")
    }

    @Test
    fun testContextWindow() {
        val store = Hexastore()
        val mm = MemoryManager()

        // Add a bunch of facts
        val facts = (1..20).map { i ->
            Atom("item", listOf(Term.Identifier("item_$i"), Term.Identifier("value_$i")),
                createdAt = System.currentTimeMillis())
        }
        facts.forEach { store.add(it); mm.onFactAsserted(it, "default") }

        // Access some facts more than others
        repeat(10) { mm.salienceTracker.recordAccess(facts[0]) }
        repeat(5) { mm.salienceTracker.recordAccess(facts[1]) }

        val window = mm.buildContextWindow(store, maxFacts = 5)
        assertEquals(5, window.windowSize, "Context window should respect maxFacts limit")
        assertTrue(window.totalAvailable >= 20, "Should report total available facts")

        // First fact in window should be the most accessed one
        assertEquals("item_1", window.facts[0].atom.args[0].toString(),
            "Most accessed fact should be first in context window")
    }

    @Test
    fun testDecayExpiresOldFacts() {
        val store = Hexastore()
        val mm = MemoryManager()
        val retracted = mutableListOf<Atom>()

        val now = System.currentTimeMillis()

        // Add an expired fact
        val expired = Atom("temp", listOf(Term.Identifier("data"), Term.Identifier("old")),
            createdAt = now - 10000, ttl = 5000)
        store.add(expired)

        // Add a valid fact
        val valid = Atom("temp", listOf(Term.Identifier("data"), Term.Identifier("new")),
            createdAt = now, ttl = 60000)
        store.add(valid)

        val result = mm.runDecay(store, { atom -> retracted.add(atom); store.delete(atom) }, "default")

        assertEquals(1, result.expiredCount, "Should expire 1 fact")
        assertTrue(retracted.any { it.args[1] == Term.Identifier("old") })
    }
}
