package org.rsmod.objtx

public data class TransactionResultList<T>(
    public val output: (TransactionObj?) -> T?,
    public val inventories: List<TransactionInventory<T>>,
    public val results: List<TransactionResult>,
    public val err: TransactionResult.Err? = results.findErr(),
) {
    public var commited: Boolean = false
        private set

    public val size: Int
        get() = results.size

    public val success: Boolean
        get() = err == null && results.isNotEmpty()

    public val failure: Boolean
        get() = !success

    public fun commitAll() {
        check(!commited) { "Transaction already commited!" }
        check(success) { "Cannot commit an unsuccessful transaction." }
        val changed = inventories.filter { it.commit() }
        commited = true
        for (inventory in changed) {
            try {
                inventory.onCommit?.invoke()
            } catch (error: Exception) {
                // Item moves have already committed. Observer failure must not report rollback.
                System.getLogger(TransactionResultList::class.java.name)
                    .log(System.Logger.Level.ERROR, "Inventory transaction observer failed after commit", error)
            }
        }
    }

    public fun single(): TransactionResult = results.single()

    public fun completed(): Int = results.sumOf { if (it.isOk()) it.completed else 0 }

    public fun anyCompleted(): Boolean = results.any { it.isOk() && it.completed > 0 }

    public fun noneCompleted(): Boolean = !anyCompleted()

    public inline fun <reified T : TransactionResult> resolve(index: Int): T? {
        val result = results.getOrNull(index) ?: return null
        return result as? T
    }

    /**
     * Gets the [TransactionResult] in index [index] within the ordered [results] collection _only
     * if_ the respective [TransactionResult] is an instance of [TransactionResult.Ok].
     *
     * [TransactionResult]s of all applicable types can be retrieved via [get] function or the
     * [results] list.
     *
     * @return `null` if the [index] is out of bounds, or if the retrieved entry is _not_ a
     *   [TransactionResult.Ok].
     */
    public fun asOk(index: Int): TransactionResult.Ok? {
        val result = results.getOrNull(index) ?: return null
        return result as? TransactionResult.Ok
    }

    public operator fun get(index: Int): TransactionResult? = results.getOrNull(index)

    private fun TransactionInventory<T>.commit(): Boolean {
        var changed = false
        for (i in output.indices) {
            val converted = output(this[i])
            if (output[i] != converted) changed = true
            output[i] = converted
        }
        return changed
    }

    override fun toString(): String =
        "TransactionResultList(" +
            "error=$err, " +
            "results=${results.map(TransactionResult::toString)}, " +
            "invs=${inventories.map(TransactionInventory<T>::toString)}" +
            ")"

    private companion object {
        fun List<TransactionResult>.findErr(): TransactionResult.Err? {
            return filterIsInstance<TransactionResult.Err>().firstOrNull()
        }
    }
}
