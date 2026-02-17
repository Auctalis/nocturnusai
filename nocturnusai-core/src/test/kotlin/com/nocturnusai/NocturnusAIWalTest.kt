package com.nocturnusai

import com.nocturnusai.core.Atom
import com.nocturnusai.core.Term
import kotlin.test.Test
import kotlin.test.assertEquals
import java.io.File

class NocturnusAIWalTest {
    @Test
    fun testPersistence() {
        val storageDir = File("build/test-db")
        if (storageDir.exists()) storageDir.deleteRecursively()
        storageDir.mkdirs()

        val db1 = NocturnusAI(storageDir)
        val fact = Atom("User", listOf(Term.Identifier("Alice")))
        db1.assertFact(fact)
        db1.close()

        println("DB1 Closed. Opening DB2...")

        val db2 = NocturnusAI(storageDir)
        val query = Atom("User", listOf(Term.Variable("x")))
        val results = db2.query(query).toList()
        
        assertEquals(1, results.size, "Should have 1 result after restart")
        assertEquals("Alice", (results[0].args[0] as Term.Identifier).name)
        db2.close()
    }

    @Test
    fun testSnapshotMetadata() {
        val storageDir = File("build/test-snapshot")
        if (storageDir.exists()) storageDir.deleteRecursively()
        storageDir.mkdirs()

        val db1 = NocturnusAI(storageDir)
        db1.assertFact(Atom("P", listOf(Term.Identifier("1"))))
        db1.createSnapshot() // Should clear WAL
        
        db1.assertFact(Atom("P", listOf(Term.Identifier("2"))))
        db1.close()

        // Restart
        val db2 = NocturnusAI(storageDir)
        val results = db2.query(Atom("P", listOf(Term.Variable("x")))).toList()
        assertEquals(2, results.size)
        db2.close()
    }
}
