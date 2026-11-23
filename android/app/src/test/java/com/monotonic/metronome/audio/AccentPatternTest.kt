package com.monotonic.metronome.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AccentPatternTest {

    @Test
    fun testDefaultPatternAndCycling() {
        assertEquals(listOf(2), AccentPattern.defaultPattern(1))
        assertEquals(listOf(2, 1, 1, 1), AccentPattern.defaultPattern(4))
        assertEquals(16, AccentPattern.defaultPattern(20).size)

        assertEquals(1, AccentPattern.cycleLevel(2))
        assertEquals(0, AccentPattern.cycleLevel(1))
        assertEquals(2, AccentPattern.cycleLevel(0))
    }

    @Test
    fun testAdaptPatternPreservesOverlapAndDefaultsNewBeatsToNormal() {
        val custom4 = listOf(2, 0, 2, 0)
        val grown7 = AccentPattern.adaptPattern(custom4, 7)
        assertEquals(listOf(2, 0, 2, 0, 1, 1, 1), grown7)

        val shrunk3 = AccentPattern.adaptPattern(grown7, 3)
        assertEquals(listOf(2, 0, 2), shrunk3)

        // Adapting empty list initializes default accent on beat 1
        assertEquals(listOf(2, 1, 1, 1, 1), AccentPattern.adaptPattern(emptyList(), 5))
    }

    @Test
    fun testBitmaskPackingAndUnpackingAll16Beats() {
        // Put level 2 on beat 15 (sets bit 31, making the signed Int negative)
        val pattern = listOf(2, 0, 1, 2, 0, 1, 1, 2, 0, 2, 1, 0, 1, 2, 0, 2)
        val mask = AccentPattern.packToBitmask(pattern)
        val unpacked = AccentPattern.unpackFromBitmask(mask, 16)
        assertEquals(pattern, unpacked)

        // Verify individual bit positions match C++ `(mask >> (beat * 2)) & 0x3u`
        for (beat in pattern.indices) {
            val extracted = (mask ushr (beat * 2)) and 0x3
            assertEquals("Beat $beat mismatch", pattern[beat], extracted)
        }
    }

    @Test
    fun testSerializeDeserializeRoundtrip() {
        val original = listOf(0, 2, 1, 2, 0, 1)
        val encoded = AccentPattern.serialize(original)
        assertEquals(original, AccentPattern.deserialize(encoded, 6))
        assertEquals(listOf(0, 2, 1, 2, 0, 1, 1, 1), AccentPattern.deserialize(encoded, 8))
        assertEquals(listOf(2, 1, 1, 1), AccentPattern.deserialize(null, 4))
    }
}
