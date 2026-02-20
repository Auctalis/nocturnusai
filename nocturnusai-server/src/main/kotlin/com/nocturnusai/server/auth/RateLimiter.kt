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

package com.nocturnusai.server.auth

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Simple in-memory rate limiter for sensitive endpoints.
 *
 * Tracks attempts per key (typically client IP). After [maxAttempts] failures
 * within [windowMs] milliseconds the key is locked out for [lockoutMs] milliseconds.
 *
 * Thread-safe: uses ConcurrentHashMap + AtomicInteger + synchronized per-bucket
 * reset to avoid TOCTOU races on window expiry.
 */
class RateLimiter(
    private val maxAttempts: Int = 5,
    private val windowMs: Long = 60_000L,    // 1-minute sliding window
    private val lockoutMs: Long = 300_000L   // 5-minute lockout after excess failures
) {
    private inner class Bucket {
        val count = AtomicInteger(0)
        @Volatile var windowStart: Long = System.currentTimeMillis()
        @Volatile var lockedUntil: Long = 0L
    }

    private val buckets = ConcurrentHashMap<String, Bucket>()

    sealed class Result {
        object Allowed : Result()
        data class LockedOut(val retryAfterSeconds: Long) : Result()
    }

    /**
     * Record a failed attempt for [key].
     * Returns [Result.Allowed] if the caller may proceed, or [Result.LockedOut]
     * with the seconds until the lockout expires.
     *
     * Call [reset] when an attempt succeeds so the counter doesn't carry over.
     */
    fun check(key: String): Result {
        val now = System.currentTimeMillis()
        val bucket = buckets.getOrPut(key) { Bucket() }

        // Already locked out?
        if (now < bucket.lockedUntil) {
            val remaining = (bucket.lockedUntil - now + 999) / 1000   // ceil to seconds
            return Result.LockedOut(remaining)
        }

        // Reset window if it has expired (synchronized to avoid concurrent resets)
        synchronized(bucket) {
            if (now - bucket.windowStart > windowMs) {
                bucket.count.set(0)
                bucket.windowStart = now
            }
        }

        val attempts = bucket.count.incrementAndGet()
        return if (attempts > maxAttempts) {
            bucket.lockedUntil = now + lockoutMs
            bucket.count.set(0)
            Result.LockedOut(lockoutMs / 1000)
        } else {
            Result.Allowed
        }
    }

    /** Clear the counter for [key] after a successful attempt. */
    fun reset(key: String) {
        buckets.remove(key)
    }

    /** Returns seconds remaining in lockout for [key], or 0 if not locked. */
    fun lockoutSecondsRemaining(key: String): Long {
        val bucket = buckets[key] ?: return 0L
        val remaining = bucket.lockedUntil - System.currentTimeMillis()
        return if (remaining > 0) (remaining + 999) / 1000 else 0L
    }
}
