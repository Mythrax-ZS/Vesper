package org.rsmod.api.random

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GameRandomPickTest {
    @Test
    fun `five choices have five reachable outcomes including the upper boundary`() {
        val outcomes =
            (0 until 5).map { roll ->
                val random = object : GameRandom {
                    override fun of(maxExclusive: Int): Int {
                        assertEquals(5, maxExclusive)
                        return roll
                    }

                    override fun of(minInclusive: Int, maxInclusive: Int): Int =
                        error("Unexpected inclusive roll")

                    override fun randomDouble(): Double = error("Unexpected double roll")
                }
                random.pick("first", "second", "third", "fourth", "fifth")
            }

        assertEquals(listOf("first", "second", "third", "fourth", "fifth"), outcomes)
    }
}
