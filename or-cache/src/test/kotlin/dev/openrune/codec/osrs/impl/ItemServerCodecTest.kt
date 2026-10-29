package dev.openrune.codec.osrs.impl

import dev.openrune.definition.codec.ItemCodec
import dev.openrune.definition.type.ItemType
import dev.openrune.types.ItemServerType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ItemServerCodecTest {
    @Test
    fun `native signed weights survive conversion and server cache serialization`() {
        for (grams in listOf(-32768, -6000, -5000, -4500, -4000, -3000, -1, 0, 600, 9979, 32767)) {
            val native = ItemCodec(241).loadData(1, byteArrayOf(75, (grams shr 8).toByte(), grams.toByte(), 0))
            val codec = ItemServerCodec(241, mapOf(native.id to native))
            val server = ItemServerType(id = native.id)
            with(codec) { server.createData() }
            val decoded = ItemServerCodec(241).loadData(server.id, codec.encodeToBuffer(server))
            assertEquals(grams.toDouble(), server.weight, "native weight $grams")
            assertEquals(grams.toDouble(), decoded.weight, "server weight $grams")
        }
    }

    @Test
    fun `already signed and fractional native overrides keep their values`() {
        for (grams in listOf(-6000.0, -0.5, 0.5, 32768.5, 65536.0)) {
            val native = ItemType(id = 1, weight = grams)
            val codec = ItemServerCodec(241, mapOf(native.id to native))
            val server = ItemServerType(id = native.id)
            with(codec) { server.createData() }
            assertEquals(grams, server.weight)
        }
    }
}
