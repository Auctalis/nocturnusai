package com.axiombase.core

import com.axiombase.AxiomBase
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import java.io.File

object Persistence {
    private val json = Json { prettyPrint = true }

    fun save(db: AxiomBase, file: File) {
        // Dump all facts (simplest way is to query everything ? ? ?)
        // Hexastore implementation has redundant indices, so better to have a 'getAllFacts' method
        // or just query query(Variable("s"), Variable("p"), Variable("o"))?
        // Let's assume we can query everything.
        
        // Querying all triples:
        // We need a wildcard pattern. 
        // Logic: P(?p, ?s, ?o)? No, we don't store "P" as the predicate for everything.
        // Hexastore query: match(Atom(..., ..., ...))?
        // We'll iterate the store via reflection or add a method.
        // For now, let's add a `getAllAtoms()` to Hexastore.
        
        val allFacts = db.getStore().getAllAtoms().toList()
        val data = json.encodeToString(allFacts)
        file.writeText(data)
    }

    fun load(db: AxiomBase, file: File) {
        if (!file.exists()) return
        val data = file.readText()
        val facts = json.decodeFromString<List<Atom>>(data)
        for (fact in facts) {
             // Load directly, bypassing checks? Or re-assert?
             // Re-asserting is safer to trigger Rete, but slower.
             // If we load snapshots, maybe direct add is better.
             // But to ensure Rete memories are hot, we need to assert.
             try {
                 db.assertFact(fact)
             } catch (e: Exception) {
                 println("Failed to load fact: $fact - ${e.message}")
             }
        }
    }
}
