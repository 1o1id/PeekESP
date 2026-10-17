package com.rhshourav.peekesp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Add next to PairingTest. The shared test vector there stays the source of truth for derive(). */
class PairingGenerateTest {

    @Test
    fun generatedCodesAreAlwaysValid() {
        repeat(5_000) { assertTrue(Pairing.isValid(Pairing.generate())) }
    }

    @Test
    fun generatedCodesSurviveFormatAndNormalise() {
        repeat(500) {
            val code = Pairing.generate()
            assertEquals(code, Pairing.normalise(Pairing.format(code)))
        }
    }

    @Test
    fun generatedCodesDeriveKeys() {
        val keys = Pairing.derive(Pairing.generate())
        assertEquals(16, keys.stream.length)
        assertEquals(48, keys.read.length)
    }

    @Test
    fun generatedCodesDoNotRepeat() {
        // 50 bits: a collision in 1,000 draws means the RNG is broken, not unlucky.
        val seen = HashSet<String>()
        repeat(1_000) { assertTrue(seen.add(Pairing.generate())) }
        assertNotEquals(Pairing.generate(), Pairing.generate())
    }
}