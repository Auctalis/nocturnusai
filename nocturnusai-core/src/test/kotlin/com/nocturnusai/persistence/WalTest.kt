package com.nocturnusai.persistence

import com.nocturnusai.core.Atom
import com.nocturnusai.core.Term
import kotlin.test.Test
import kotlin.test.assertEquals
import java.io.File

class WalTest {
    @Test
    fun testWalAppendAndReplay() {
        val testFile = File("build/test-wal.log")
        if (testFile.exists()) testFile.delete()
        
        val wal = WriteAheadLog(testFile)
        val atom1 = Atom("Test", listOf(Term.Identifier("A")))
        val atom2 = Atom("Test", listOf(Term.Identifier("B")))
        
        wal.append(WalOperation.ASSERT, WalData.FactData(atom1))
        wal.append(WalOperation.RETRACT, WalData.FactData(atom2))
        wal.close()
        
        val recovered = mutableListOf<Pair<WalOperation, WalData>>()
        val wal2 = WriteAheadLog(testFile)
        wal2.replay { op, data, _ -> 
            recovered.add(op to data)
        }
        wal2.close()
        
        assertEquals(2, recovered.size)
        assertEquals(WalOperation.ASSERT, recovered[0].first)
        val data1 = recovered[0].second as WalData.FactData
        assertEquals(atom1.predicate, data1.atom.predicate)
        
        assertEquals(WalOperation.RETRACT, recovered[1].first)
        val data2 = recovered[1].second as WalData.FactData
        assertEquals(atom2.predicate, data2.atom.predicate)
    }

    @Test
    fun testWalBatch() {
        val testFile = File("build/test-wal-batch.log")
        if (testFile.exists()) testFile.delete()

        val wal = WriteAheadLog(testFile)
        val atom1 = Atom("A", listOf(Term.Identifier("1")))
        val atom2 = Atom("B", listOf(Term.Identifier("2")))

        val batchItems = listOf(
            WalBatchItem(WalOperation.ASSERT, WalData.FactData(atom1)),
            WalBatchItem(WalOperation.ASSERT, WalData.FactData(atom2))
        )
        
        wal.append(WalOperation.ASSERT, WalData.TransactionData(batchItems))
        wal.close()

        val wal2 = WriteAheadLog(testFile)
        var batchCount = 0
        wal2.replay { op, data, _ ->
           if (data is WalData.TransactionData) {
               batchCount++
               assertEquals(2, data.batch.size)
               assertEquals(atom1.predicate, (data.batch[0].data as WalData.FactData).atom.predicate)
           }
        }
        assertEquals(1, batchCount)
    }
}
