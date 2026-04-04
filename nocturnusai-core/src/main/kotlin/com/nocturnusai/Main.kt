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

package com.nocturnusai

import java.io.File

fun main(args: Array<String>) {
    val db = NocturnusAI()
    
    if (args.isNotEmpty()) {
        val file = File(args[0])
        if (file.exists()) {
            val content = file.readText()
            println(db.execute(content))
        } else {
            println("File not found: ${args[0]}")
        }
    } else {
        println("NocturnusAI shell. Type commands (ending with ;). Type 'exit' to quit.")
        val sb = StringBuilder()
        while (true) {
            print("> ")
            val line = readlnOrNull() ?: break
            if (line.trim() == "exit") break
            sb.append(line).append("\n")
            
            if (line.trim().endsWith(";")) {
                println(db.execute(sb.toString()))
                sb.clear()
            }
        }
    }
}
