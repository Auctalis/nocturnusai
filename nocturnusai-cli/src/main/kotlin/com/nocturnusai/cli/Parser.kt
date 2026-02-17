package com.nocturnusai.cli

import kotlinx.serialization.json.*

/**
 * Parse predicate(arg1, arg2) into JSON.
 * Handles: predicate(args), NOT predicate(args), head :- body1, body2
 */
object Parser {

    fun atomToJson(text: String): String {
        val atom = parseAtom(text.trim())
        return buildJsonObject {
            put("predicate", atom.predicate)
            putJsonArray("args") { atom.args.forEach { add(it) } }
            if (atom.negated) put("negated", true)
        }.toString()
    }

    fun factToJson(text: String): String {
        val atom = parseAtom(text.trim())
        return buildJsonObject {
            put("predicate", atom.predicate)
            putJsonArray("args") { atom.args.forEach { add(it) } }
            put("truthVal", !atom.negated)
            if (atom.negated) put("negated", true)
        }.toString()
    }

    fun ruleToJson(text: String): String {
        val sep = text.indexOf(":-")
        if (sep == -1) error("Not a rule — missing \":-\"")
        val head = parseAtom(text.substring(0, sep))
        val bodyParts = splitOutsideParens(text.substring(sep + 2), ',')
        val body = bodyParts.map { parseAtom(it) }

        return buildJsonObject {
            putJsonObject("head") {
                put("predicate", head.predicate)
                putJsonArray("args") { head.args.forEach { add(it) } }
            }
            putJsonArray("body") {
                body.forEach { b ->
                    addJsonObject {
                        put("predicate", b.predicate)
                        putJsonArray("args") { b.args.forEach { add(it) } }
                        if (b.negated) put("negated", true)
                    }
                }
            }
        }.toString()
    }

    fun isRule(text: String) = text.contains(":-")

    // ── internals ──

    private data class Atom(val predicate: String, val args: List<String>, val negated: Boolean)

    private fun parseAtom(raw: String): Atom {
        var text = raw.trim()
        var negated = false
        if (text.uppercase().startsWith("NOT ")) {
            negated = true
            text = text.removePrefix("NOT ").removePrefix("not ").trimStart()
        }
        val paren = text.indexOf('(')
        if (paren == -1) return Atom(text, emptyList(), negated)

        val predicate = text.substring(0, paren).trim()
        val inner = text.substring(paren + 1, text.lastIndexOf(')'))
        val args = splitOutsideParens(inner, ',').map { it.trim() }.filter { it.isNotEmpty() }
        return Atom(predicate, args, negated)
    }

    private fun splitOutsideParens(text: String, delim: Char): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        for (ch in text) {
            when {
                ch == '(' -> { depth++; current.append(ch) }
                ch == ')' -> { depth--; current.append(ch) }
                ch == delim && depth == 0 -> { result.add(current.toString()); current.clear() }
                else -> current.append(ch)
            }
        }
        if (current.isNotBlank()) result.add(current.toString())
        return result
    }
}
