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

import java.util.Properties

object BuildInfo {
    val version: String by lazy {
        try {
            val props = Properties()
            val stream = BuildInfo::class.java.getResourceAsStream("/version/version.properties")
            if (stream != null) {
                props.load(stream)
                props.getProperty("version", "dev")
            } else {
                "dev"
            }
        } catch (_: Exception) {
            "dev"
        }
    }
}
