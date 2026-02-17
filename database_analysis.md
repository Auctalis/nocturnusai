# Gap Analysis: NocturnusAI as a Database Layer

This document analyzes the current state of `NocturnusAI` and identifies critical features missing for it to function as a reliable, production-ready database layer.

## 1. ACID Compliance & Transaction Management
The current implementation lacks the fundamental guarantees of a transactional system (Atomicity, Consistency, Isolation, Durability).

- **Atomicity:** There is no mechanism to group multiple operations (assert/retract) into a single atomic unit. If a batch process fails halfway, the database is left in an inconsistent partial state.
- **Isolation:** While `ConcurrentHashMap` provides low-level thread safety for individual maps, there is no isolation level (e.g., Read Committed, Serializable). Concurrent reads/writes can lead to race conditions where a query sees a partially updated state (e.g., seeing a fact but not its inferential consequences yet).
- **Durability:** Persistence is currently manual and snapshot-based (`Persistence.kt`).
    - **Missing:** Write-Ahead Logging (WAL) or an append-only journal.
    - **Risk:** Any server crash between snapshots results in total data loss of recent memory-only changes.
- **Consistency:** `ConsistencyGuard` provides some logical consistency, but it is not transactional.

## 2. Storage Engine & Scalability
The system is entirely in-memory (`Hexastore` uses `HashMap`), which limits dataset size to available RAM.

- **Disk-Based Storage:** No mechanism to spill to disk. A B-Tree, LSM Tree, or similar on-disk structure is needed for datasets larger than RAM.
- **Buffer Management:** No page cache or buffer pool to manage memory/disk data movement.
- **Indexing Strategy:** The 6-Way Index (Hexastore) is excellent for triple queries but expensive in memory (6x overhead). Optimization strategies (like specialized compression or hybrid indexes) are missing.

## 3. Query Language & Optimization
- **Query Language:** The current method is direct API calls (`match`, `infer`) or a basic Logiql parser. A standardized, declarative query language (like SPARQL, Datalog, or a SQL subset) with a full parser/optimizer is needed.
- **Query Optimizer:** There is no cost-based optimizer using statistics (e.g., "selectivity" of a predicate) to choose the best index or join order.
- **Schema/Type System:** The system is "schema-less" (everything is a `Term`). Datalog/SQL databases usually enforce schemas for predicates (arity, argument types) to prevent data corruption.

## 4. Security & Access Control
- **Authentication/Authorization:** The server (`Application.kt`) has no login, token verification, or role-based access control (RBAC). Anyone with network access can generic `assert` or `retract`.
- **Network Security:** No TLS/SSL configuration is evident in the basic Netty server setup.

## 5. Operational Reliability
- **Backup & Recovery:** `Persistence.save` is a manual blocking operation. Point-in-time recovery (PITR) is impossible without a transaction log.
- **High Availability:** No replication, clustering, or consensus algorithm (like Raft/Paxos) to handle node failures.
- **Monitoring/Metrics:** No telemetry (Prometheus endpoints) to track query latency, memory usage, or rule firing counts.

## 6. Recommendations for "Database-ification"
To transform `NocturnusAI` into a proper database layer, we recommend the following roadmap:

1.  **Implement a WAL (Write-Ahead Log):** Append every command (Assert/Retract) to a file *before* applying to memory. This solves Durability.
2.  **Add `BEGIN`, `COMMIT`, `ROLLBACK`:** Implement a Transaction Manager that locks affected predicates or uses MVCC (Multi-Version Concurrency Control) to provide Isolation.
3.  **Standardize Persistence:** Integrate `Persistence.kt` into `NocturnusAI` core to auto-save snapshots and replay WAL on startup.
4.  **Enhance API:** Add Authorization middleware to Ktor.
