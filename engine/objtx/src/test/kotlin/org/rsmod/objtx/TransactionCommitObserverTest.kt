package org.rsmod.objtx

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TransactionCommitObserverTest {
    @Test
    fun `observers run once after all output inventories commit`() {
        val first = arrayOf<Int?>(1)
        val second = arrayOf<Int?>(2)
        var notifications = 0
        lateinit var result: TransactionResultList<Int>
        val observer = {
            assertTrue(result.commited)
            assertEquals(2, first[0])
            assertEquals(1, second[0])
            notifications++
            Unit
        }
        result = result(
            inventory(first, 2, observer),
            inventory(second, 1, observer),
        )
        assertEquals(0, notifications)
        assertEquals(1, first[0])
        result.commitAll()
        assertEquals(2, notifications)
        assertThrows(IllegalStateException::class.java) { result.commitAll() }
        assertEquals(2, notifications)
    }

    @Test
    fun `failed transactions cannot commit contents or notify`() {
        val output = arrayOf<Int?>(1)
        var notifications = 0
        val result = TransactionResultList(
            output = { obj: TransactionObj? -> obj?.id },
            inventories = listOf(inventory(output, 2) { notifications++ }),
            results = listOf(TransactionResult.Ok(1, 1), TransactionResult.NotEnoughSpace),
        )
        assertThrows(IllegalStateException::class.java) { result.commitAll() }
        assertEquals(1, output[0])
        assertFalse(result.commited)
        assertEquals(0, notifications)
    }

    @Test
    fun `observer failure cannot revert a committed transaction or stop later observers`() {
        val first = arrayOf<Int?>(1)
        val second = arrayOf<Int?>(2)
        var laterNotified = false
        val result = result(
            inventory(first, 2) { error("observer failure") },
            inventory(second, 1) { laterNotified = true },
        )
        assertDoesNotThrow { result.commitAll() }
        assertTrue(result.success)
        assertTrue(result.commited)
        assertTrue(laterNotified)
        assertEquals(2, first[0])
        assertEquals(1, second[0])
    }

    @Test
    fun `non-strict partial transfers remain successful and notify after moving available count`() {
        val source = arrayOf<TransactionObj?>(TransactionObj(1, 5))
        val target = arrayOf<TransactionObj?>(TransactionObj(1, Int.MAX_VALUE - 2))
        var notifications = 0
        val transaction = Transaction<TransactionObj>(input = { it }, output = { it })
        transaction.stackableLookup = setOf(1)
        val from = transaction.register(TransactionInventory(
            TransactionInventory.AlwaysStack, source, source.copyOf(), onCommit = { notifications++ },
        ))
        val into = transaction.register(TransactionInventory(
            TransactionInventory.AlwaysStack, target, target.copyOf(), onCommit = { notifications++ },
        ))
        transaction.transfer {
            this.from = from
            this.into = into
            fromSlot = 0
            count = 5
            strict = false
        }
        val result = transaction.results()
        assertTrue(result.success)
        assertEquals(2, result.completed())
        assertEquals(0, notifications)
        result.commitAll()
        assertEquals(3, source[0]!!.count)
        assertEquals(Int.MAX_VALUE, target[0]!!.count)
        assertEquals(2, notifications)
    }

    @Test
    fun `successful virtual-storage result without physical inventories can still commit`() {
        val result = TransactionResultList<Int>(
            output = { null }, inventories = emptyList(),
            results = listOf(TransactionResult.Ok(1, 1)),
        )
        result.commitAll()
        assertTrue(result.commited)
        assertTrue(result.success)
    }

    @Test
    fun `unchanged inventories do not notify and observers cannot recursively recommit`() {
        var notifications = 0
        lateinit var result: TransactionResultList<Int>
        result = result(
            inventory(arrayOf(1), 1) { error("unchanged inventory notified") },
            inventory(arrayOf(2), 3) {
                notifications++
                assertThrows(IllegalStateException::class.java) { result.commitAll() }
            },
        )
        result.commitAll()
        assertEquals(1, notifications)
    }

    private fun inventory(output: Array<Int?>, next: Int, observer: () -> Unit): TransactionInventory<Int> =
        TransactionInventory(
            stackType = TransactionInventory.NormalStack,
            output = output,
            image = arrayOf(TransactionObj(next, 1)),
            onCommit = observer,
        )

    private fun result(vararg inventories: TransactionInventory<Int>): TransactionResultList<Int> =
        TransactionResultList(
            output = { it?.id },
            inventories = inventories.toList(),
            results = listOf(TransactionResult.Ok(1, 1)),
        )
}
