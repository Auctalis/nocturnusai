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

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header

/**
 * Extracts the client IP for rate-limiting decisions.
 *
 * By default returns the TCP peer (`remoteHost`). When the environment variable
 * `TRUSTED_PROXY_IPS` is set to a comma-separated list of proxy IPs — e.g.
 * `TRUSTED_PROXY_IPS=10.0.0.1,10.0.0.2` — and the TCP peer is one of those
 * proxies, the left-most entry of the `X-Forwarded-For` header is used instead.
 *
 * This prevents two problems with using the raw TCP peer behind a reverse proxy:
 *   1. All traffic appears to come from the proxy's IP, so a single attacker
 *      can lock out every legitimate user by exhausting the shared bucket.
 *   2. An attacker can rotate source IPs upstream of the proxy and evade the
 *      limit entirely.
 *
 * Only the X-Forwarded-For header from a trusted proxy is honoured — arbitrary
 * clients cannot spoof their IP by setting the header themselves.
 */
object ClientIp {
    private val trustedProxies: Set<String> by lazy {
        System.getenv("TRUSTED_PROXY_IPS")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()
    }

    fun of(call: ApplicationCall): String {
        val peer = call.request.local.remoteHost
        if (trustedProxies.isEmpty() || peer !in trustedProxies) return peer
        val fwd = call.request.header("X-Forwarded-For") ?: return peer
        // Left-most entry is the original client per RFC 7239.
        val first = fwd.split(",").firstOrNull()?.trim() ?: return peer
        return first.ifEmpty { peer }
    }
}
