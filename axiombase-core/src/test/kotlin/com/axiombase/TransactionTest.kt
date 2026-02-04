package com.axiombase

import com.axiombase.core.Atom
import com.axiombase.core.Term
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.io.File

class TransactionTest {
    @Test
    fun testAtomicity() {
        val dir = File("build/test-tx")
        if (dir.exists()) dir.deleteRecursively()
        
        val db = AxiomBase(dir)
        val tm = db.transactionManager
        
        val txId = tm.begin()
        val fact = Atom("TxTest", listOf(Term.Identifier("1")))
        tm.assertFact(txId, fact)
        
        // Should not be visible yet (Isolation level: Read Committed / Snapshot?)
        // Actually, our query reads direct from store.
        // Pending changes are in TM buffer, not in store.
        val resultsInTx = db.query(fact).toList()
        assertEquals(0, resultsInTx.size, "Pending fact should not be visible before commit")
        
        tm.commit(txId)
        
        val resultsAfter = db.query(fact).toList()
        assertEquals(1, resultsAfter.size, "Fact should be visible after commit")
        
        db.close()
    }
    
    @Test
    fun testRollback() {
        val dir = File("build/test-tx-rollback")
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()
        
        val db = AxiomBase(dir)
        val tm = db.transactionManager
        
        val txId = tm.begin()
        val fact = Atom("RollbackTest", listOf(Term.Identifier("1")))
        tm.assertFact(txId, fact)
        tm.rollback(txId)
        
        val results = db.query(fact).toList()
        assertEquals(0, results.size, "Fact should not exist after rollback")

        // Ensure we can't commit rolled back tx
        assertFailsWith<IllegalArgumentException> {
            tm.commit(txId)
        }
        db.close()
    }
}
