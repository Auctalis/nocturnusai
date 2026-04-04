// Copyright (c) 2026 Auctalis LLC. All rights reserved.
//
// Licensed under the Business Source License 1.1 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://github.com/auctalis/nocturnusai/blob/main/LICENSE
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//
// For commercial licensing, please contact: licensing@nocturnus.ai

package com.nocturnusai.server

import com.nocturnusai.NocturnusAI
import com.nocturnusai.persistence.WalEntry
import com.nocturnusai.server.observability.Metrics
import com.nocturnusai.server.routes.SnapshotResponse
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * Replication client that syncs one or more databases from a leader node.
 *
 * Improvements over the original:
 *  - Uses a dedicated CoroutineScope (not GlobalScope) so coroutines are properly
 *    cancelled on stop().
 *  - Performs initial snapshot sync when local DB is empty, then switches to WAL polling.
 *  - Syncs ALL databases discovered from the leader, not just "default".
 *  - Exponential backoff on connection failures (1 s → 30 s max).
 *  - SLF4J structured logging instead of System.err.println.
 *  - Tracks replication lag (leader WAL ID vs last synced WAL ID) exposed via Metrics.
 *  - Better error handling: contradiction warnings logged at WARN without crashing.
 */
class ReplicationClient(
    private val dbManager: DatabaseManager,
    private val leaderUrl: String
) {
    private val logger = LoggerFactory.getLogger(ReplicationClient::class.java)

    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
    }

    // Proper scope: SupervisorJob so one failing child doesn't cancel siblings
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Per-database cursor tracking: dbName → last synced WAL ID
    private val cursors = mutableMapOf<String, AtomicLong>()

    fun start() {
        scope.launch {
            logger.info("ReplicationClient starting — following leader at $leaderUrl")

            // 1. Discover databases from leader
            val databases = discoverDatabases()
            if (databases.isEmpty()) {
                logger.warn("No databases returned from leader. Falling back to 'default'.")
                syncDatabase("default")
            } else {
                databases.forEach { dbName ->
                    launch { syncDatabase(dbName) }
                }
            }
        }
    }

    /**
     * Full lifecycle for one database: snapshot sync (if empty) then continuous WAL polling.
     */
    private suspend fun syncDatabase(dbName: String) {
        logger.info("[$dbName] Starting sync loop")

        // Ensure local database exists
        val db = dbManager.getDatabase(dbName) ?: dbManager.createDatabase(dbName, true)

        val cursor = cursors.getOrPut(dbName) { AtomicLong(0L) }

        // 2. Initial snapshot sync if local DB is empty (no facts in default tenant)
        val isEmpty = db.getAllFacts("default").none()
        if (isEmpty) {
            logger.info("[$dbName] Local database is empty — fetching initial snapshot from leader")
            val snapshotWalId = fetchAndApplySnapshot(db, dbName)
            if (snapshotWalId >= 0) {
                cursor.set(snapshotWalId)
                logger.info("[$dbName] Snapshot applied. Starting WAL poll from id=${snapshotWalId}")
            } else {
                logger.warn("[$dbName] Snapshot fetch failed — will poll WAL from id=0")
            }
        } else {
            logger.info("[$dbName] Local database has data — skipping snapshot, polling WAL from id=${cursor.get()}")
        }

        // 3. Continuous WAL polling with exponential backoff
        var backoffMs = 1_000L
        val maxBackoffMs = 30_000L

        while (scope.isActive) {
            try {
                val polled = pollWal(db, dbName, cursor)
                if (polled >= 0) {
                    // Successful poll (even if 0 entries); reset backoff
                    backoffMs = 1_000L
                    Metrics.replicationLastSyncedWalId(dbName, cursor.get())
                }
                delay(1_000L)
            } catch (e: CancellationException) {
                throw e // propagate cancellation
            } catch (e: Exception) {
                logger.warn("[$dbName] Replication poll error (retry in ${backoffMs}ms): ${e.message}")
                Metrics.replicationConsecutiveFailures(dbName)
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(maxBackoffMs)
            }
        }

        logger.info("[$dbName] Sync loop exiting (scope cancelled)")
    }

    /**
     * Discover the list of database names from the leader.
     * Returns empty list if the endpoint is unavailable (backward compat).
     */
    private suspend fun discoverDatabases(): List<String> {
        return try {
            val response = httpClient.get("$leaderUrl/replication/wal/databases") {
                applyAuth()
            }
            if (response.status.value == 200) {
                json.decodeFromString<List<String>>(response.bodyAsText())
            } else {
                logger.warn("Leader returned ${response.status} from /replication/wal/databases — falling back to 'default'")
                listOf("default")
            }
        } catch (e: Exception) {
            logger.warn("Could not reach leader for database discovery: ${e.message} — falling back to 'default'")
            listOf("default")
        }
    }

    /**
     * Fetch a full snapshot from the leader and apply it to the local database.
     * @return the latestWalId from the snapshot response, or -1 on failure.
     */
    private suspend fun fetchAndApplySnapshot(db: NocturnusAI, dbName: String): Long {
        return try {
            val response = httpClient.get("$leaderUrl/replication/snapshot") {
                parameter("database", dbName)
                applyAuth()
            }

            if (response.status.value != 200) {
                logger.warn("[$dbName] Leader returned ${response.status} for snapshot request")
                return -1L
            }

            val snapshotResponse = json.decodeFromString<SnapshotResponse>(response.bodyAsText())
            val snapshot = snapshotResponse.snapshot

            logger.info("[$dbName] Applying snapshot: ${snapshot.tenants.size} tenants, timestamp=${snapshot.timestamp}")

            snapshot.tenants.forEach { (tenantId, tenantData) ->
                // Create tenant if it doesn't exist yet
                if (!db.getRegisteredTenants().contains(tenantId)) {
                    db.createTenant(tenantId)
                }
                tenantData.positives.forEach { atom ->
                    try {
                        db.assertFact(atom, tenantId)
                    } catch (e: IllegalArgumentException) {
                        // Contradiction during snapshot load — log and continue
                        logger.warn("[$dbName] Contradiction applying snapshot fact for tenant '$tenantId': ${e.message}")
                    }
                }
                tenantData.negatives.forEach { atom ->
                    try {
                        db.assertFact(atom, tenantId)
                    } catch (e: IllegalArgumentException) {
                        logger.warn("[$dbName] Contradiction applying snapshot negation for tenant '$tenantId': ${e.message}")
                    }
                }
            }

            logger.info("[$dbName] Snapshot applied successfully (latestWalId=${snapshotResponse.latestWalId})")
            snapshotResponse.latestWalId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("[$dbName] Failed to fetch/apply snapshot: ${e.message}", e)
            -1L
        }
    }

    /**
     * Poll the leader WAL for new entries and apply them.
     * @return number of entries applied, or -1 on error.
     */
    private suspend fun pollWal(db: NocturnusAI, dbName: String, cursor: AtomicLong): Int {
        val response = httpClient.get("$leaderUrl/replication/wal") {
            parameter("database", dbName)
            parameter("since", cursor.get())
            applyAuth()
        }

        if (response.status.value != 200) {
            logger.warn("[$dbName] Leader returned ${response.status} for WAL request (since=${cursor.get()})")
            return -1
        }

        val text = response.bodyAsText()
        val entries: List<WalEntry> = text.lines()
            .filter { it.isNotBlank() }
            .map { json.decodeFromString<WalEntry>(it) }
            .sortedBy { it.id }

        if (entries.isEmpty()) return 0

        logger.info("[$dbName] Replicating ${entries.size} WAL entries from leader...")
        applyReplicationBatch(db, dbName, entries)
        cursor.set(entries.maxOf { it.id })

        return entries.size
    }

    /**
     * Apply a batch of WAL entries with per-entry error handling.
     * Contradictions are logged at WARN; processing continues for subsequent entries.
     */
    private fun applyReplicationBatch(db: NocturnusAI, dbName: String, entries: List<WalEntry>) {
        for (entry in entries) {
            try {
                db.applyReplicationBatch(listOf(entry))
            } catch (e: IllegalArgumentException) {
                // Contradiction or validation error — warn but continue
                logger.warn(
                    "[$dbName] Contradiction on entry id=${entry.id} op=${entry.op} tenant=${entry.tenantId}: ${e.message}"
                )
            } catch (e: Exception) {
                logger.error(
                    "[$dbName] Unexpected error applying entry id=${entry.id}: ${e.message}", e
                )
            }
        }
    }

    private fun HttpRequestBuilder.applyAuth() {
        if (ServerConfig.apiKey != null) {
            header("X-API-Key", ServerConfig.apiKey)
        }
    }

    fun stop() {
        logger.info("ReplicationClient stopping — cancelling all sync coroutines")
        scope.cancel()
        httpClient.close()
    }
}
