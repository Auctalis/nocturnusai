package com.axiombase.memory

import com.axiombase.core.Atom
import com.axiombase.core.Rule
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Event bus for reactive subscriptions to knowledge base changes.
 *
 * Agents can subscribe to fact assertions, retractions, and rule changes
 * filtered by predicate patterns. This enables real-time multi-agent
 * coordination where Agent B is notified when Agent A asserts relevant facts.
 */

sealed class KnowledgeEvent {
    abstract val timestamp: Long
    abstract val tenantId: String?
    abstract val eventId: Long

    data class FactAsserted(
        val atom: Atom,
        override val tenantId: String?,
        override val timestamp: Long = System.currentTimeMillis(),
        override val eventId: Long = 0
    ) : KnowledgeEvent()

    data class FactRetracted(
        val atom: Atom,
        override val tenantId: String?,
        override val timestamp: Long = System.currentTimeMillis(),
        override val eventId: Long = 0
    ) : KnowledgeEvent()

    data class RuleAsserted(
        val rule: Rule,
        override val tenantId: String?,
        override val timestamp: Long = System.currentTimeMillis(),
        override val eventId: Long = 0
    ) : KnowledgeEvent()

    data class FactExpired(
        val atom: Atom,
        override val tenantId: String?,
        override val timestamp: Long = System.currentTimeMillis(),
        override val eventId: Long = 0
    ) : KnowledgeEvent()

    data class ConsolidationOccurred(
        val consolidatedFact: Atom,
        val sourceCount: Int,
        override val tenantId: String?,
        override val timestamp: Long = System.currentTimeMillis(),
        override val eventId: Long = 0
    ) : KnowledgeEvent()
}

data class Subscription(
    val id: String,
    val predicatePattern: String?, // null = all predicates, "parent" = exact match, "parent*" = prefix
    val eventTypes: Set<String>, // "fact_asserted", "fact_retracted", "rule_asserted", "fact_expired", "consolidation"
    val tenantId: String?, // null = all tenants
    val callback: (KnowledgeEvent) -> Unit
)

class EventBus {
    private val subscriptions = ConcurrentHashMap<String, Subscription>()
    private val eventIdCounter = AtomicLong(0)
    private val recentEvents = CopyOnWriteArrayList<KnowledgeEvent>()
    private val maxRecentEvents = 1000

    /** Subscribe to knowledge events. Returns subscription ID. */
    fun subscribe(
        predicatePattern: String? = null,
        eventTypes: Set<String> = setOf("fact_asserted", "fact_retracted"),
        tenantId: String? = null,
        callback: (KnowledgeEvent) -> Unit
    ): String {
        val id = "sub_${System.currentTimeMillis()}_${eventIdCounter.incrementAndGet()}"
        subscriptions[id] = Subscription(id, predicatePattern, eventTypes, tenantId, callback)
        return id
    }

    /** Unsubscribe from events. */
    fun unsubscribe(subscriptionId: String) {
        subscriptions.remove(subscriptionId)
    }

    /** Publish an event to all matching subscribers. */
    fun publish(event: KnowledgeEvent) {
        val eventWithId = assignEventId(event)

        // Store in recent events buffer
        recentEvents.add(eventWithId)
        while (recentEvents.size > maxRecentEvents) {
            recentEvents.removeAt(0)
        }

        // Dispatch to matching subscribers
        for ((_, sub) in subscriptions) {
            if (matches(sub, eventWithId)) {
                try {
                    sub.callback(eventWithId)
                } catch (_: Exception) {
                    // Don't let subscriber errors crash the bus
                }
            }
        }
    }

    /** Get recent events since a given event ID. */
    fun getEventsSince(sinceEventId: Long): List<KnowledgeEvent> {
        return recentEvents.filter { it.eventId > sinceEventId }
    }

    /** Get all active subscription IDs. */
    fun getSubscriptions(): Set<String> = subscriptions.keys.toSet()

    fun clear() {
        subscriptions.clear()
        recentEvents.clear()
    }

    private fun assignEventId(event: KnowledgeEvent): KnowledgeEvent {
        val id = eventIdCounter.incrementAndGet()
        return when (event) {
            is KnowledgeEvent.FactAsserted -> event.copy(eventId = id)
            is KnowledgeEvent.FactRetracted -> event.copy(eventId = id)
            is KnowledgeEvent.RuleAsserted -> event.copy(eventId = id)
            is KnowledgeEvent.FactExpired -> event.copy(eventId = id)
            is KnowledgeEvent.ConsolidationOccurred -> event.copy(eventId = id)
        }
    }

    private fun matches(sub: Subscription, event: KnowledgeEvent): Boolean {
        // Check event type
        val eventType = when (event) {
            is KnowledgeEvent.FactAsserted -> "fact_asserted"
            is KnowledgeEvent.FactRetracted -> "fact_retracted"
            is KnowledgeEvent.RuleAsserted -> "rule_asserted"
            is KnowledgeEvent.FactExpired -> "fact_expired"
            is KnowledgeEvent.ConsolidationOccurred -> "consolidation"
        }
        if (eventType !in sub.eventTypes) return false

        // Check tenant
        if (sub.tenantId != null && event.tenantId != sub.tenantId) return false

        // Check predicate pattern
        if (sub.predicatePattern != null) {
            val predicate = when (event) {
                is KnowledgeEvent.FactAsserted -> event.atom.predicate
                is KnowledgeEvent.FactRetracted -> event.atom.predicate
                is KnowledgeEvent.RuleAsserted -> event.rule.head.predicate
                is KnowledgeEvent.FactExpired -> event.atom.predicate
                is KnowledgeEvent.ConsolidationOccurred -> event.consolidatedFact.predicate
            }
            if (sub.predicatePattern.endsWith("*")) {
                val prefix = sub.predicatePattern.dropLast(1)
                if (!predicate.startsWith(prefix)) return false
            } else {
                if (predicate != sub.predicatePattern) return false
            }
        }

        return true
    }
}
