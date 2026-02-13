package com.axiombase.server

import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class HealthStatus(
    val status: String, // "healthy", "degraded", "unhealthy"
    val checks: Map<String, CheckResult>
)

@Serializable
data class CheckResult(
    val status: String, // "pass", "warn", "fail"
    val message: String
)

object HealthChecker {

    fun check(dbManager: DatabaseManager, storageDir: File): HealthStatus {
        val checks = mutableMapOf<String, CheckResult>()

        // 1. WAL writable
        checks["wal_writable"] = checkWalWritable(storageDir)

        // 2. Disk space
        checks["disk_space"] = checkDiskSpace(storageDir)

        // 3. Memory
        checks["memory"] = checkMemory()

        // 4. Databases
        checks["databases"] = checkDatabases(dbManager)

        // 5. Transactions
        checks["transactions"] = checkTransactions(dbManager)

        val hasFailure = checks.values.any { it.status == "fail" }
        val hasWarning = checks.values.any { it.status == "warn" }
        val overallStatus = when {
            hasFailure -> "unhealthy"
            hasWarning -> "degraded"
            else -> "healthy"
        }

        return HealthStatus(status = overallStatus, checks = checks)
    }

    private fun checkWalWritable(storageDir: File): CheckResult {
        return try {
            val tempFile = File(storageDir, ".health_check_tmp")
            tempFile.writeText("health")
            tempFile.delete()
            CheckResult("pass", "Storage directory is writable")
        } catch (e: Exception) {
            CheckResult("fail", "Storage directory is not writable: ${e.message}")
        }
    }

    private fun checkDiskSpace(storageDir: File): CheckResult {
        val usable = storageDir.usableSpace
        val total = storageDir.totalSpace
        if (total == 0L) return CheckResult("warn", "Unable to determine disk space")

        val usedPercent = ((total - usable).toDouble() / total * 100).toInt()
        return when {
            usedPercent > 95 -> CheckResult("fail", "Disk usage critical: ${usedPercent}%")
            usedPercent > 85 -> CheckResult("warn", "Disk usage high: ${usedPercent}%")
            else -> CheckResult("pass", "Disk usage: ${usedPercent}%")
        }
    }

    private fun checkMemory(): CheckResult {
        val runtime = Runtime.getRuntime()
        val used = runtime.totalMemory() - runtime.freeMemory()
        val max = runtime.maxMemory()
        val usedPercent = (used.toDouble() / max * 100).toInt()
        return when {
            usedPercent > 90 -> CheckResult("warn", "Memory usage high: ${usedPercent}% (${used / 1024 / 1024}MB / ${max / 1024 / 1024}MB)")
            else -> CheckResult("pass", "Memory usage: ${usedPercent}% (${used / 1024 / 1024}MB / ${max / 1024 / 1024}MB)")
        }
    }

    private fun checkDatabases(dbManager: DatabaseManager): CheckResult {
        val count = dbManager.getDatabaseNames().size
        return CheckResult("pass", "$count database(s) loaded")
    }

    private fun checkTransactions(dbManager: DatabaseManager): CheckResult {
        var totalActive = 0
        for (name in dbManager.getDatabaseNames()) {
            val db = dbManager.getDatabase(name)
            if (db != null) {
                totalActive += db.transactionManager.getActiveTransactionCount()
            }
        }
        return when {
            totalActive > 50 -> CheckResult("warn", "$totalActive active transactions (high)")
            else -> CheckResult("pass", "$totalActive active transaction(s)")
        }
    }
}
