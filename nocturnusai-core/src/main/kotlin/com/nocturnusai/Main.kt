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
