package org.rsmod.content.other.grandexchange

enum class GeKind(val id: Int) {
    Empty(0),
    Buy(1),
    Sell(2);

    companion object {
        fun of(id: Int): GeKind = entries.firstOrNull { it.id == id } ?: Empty
    }
}

enum class GeStatus(val id: Int) {
    Active(0),
    Complete(1),
    Aborted(2);

    companion object {
        fun of(id: Int): GeStatus = entries.firstOrNull { it.id == id } ?: Active
    }
}

data class GeOffer(
    val kind: GeKind,
    val item: Int,
    val quantity: Int,
    val price: Int,
    val filled: Int = 0,
    val spent: Int = 0,
    val claimItems: Int = 0,
    val claimCoins: Int = 0,
    val status: GeStatus = GeStatus.Active,
) {
    val isEmpty: Boolean
        get() = kind == GeKind.Empty

    val hasClaim: Boolean
        get() = claimItems > 0 || claimCoins > 0

    val canAbort: Boolean
        get() = !isEmpty && status == GeStatus.Active

    companion object {
        val EMPTY = GeOffer(GeKind.Empty, 0, 0, 0)
    }
}
