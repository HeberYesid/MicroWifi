package com.microwifi.android

import org.junit.Assert.*
import org.junit.Test

class VoiceIsolationTest {
    private val out = ShortArray(480)
    private fun frame(value: Short) = ShortArray(480) { value }

    @Test fun conmutarConservaElOrdenYElRetardoDeUnBloque() {
        var closed = 0
        val processor = VoiceIsolation {
            object : VoiceFilter {
                private var prior: Short = 0
                override fun process(input: ShortArray, output: ShortArray): Boolean {
                    output.fill((prior / 2).toShort())
                    prior = input[0]
                    return true
                }
                override fun close() { closed++ }
            }
        }
        fun step(input: Short, enabled: Boolean, expected: Short, mode: VoiceIsolation.Mode) {
            assertEquals(mode, processor.process(frame(input), out, enabled))
            assertArrayEquals(frame(expected), out)
        }
        step(100, false, 0, VoiceIsolation.Mode.BYPASS)
        step(200, true, 100, VoiceIsolation.Mode.WARMING)
        step(300, true, 100, VoiceIsolation.Mode.FILTERED)
        step(400, false, 300, VoiceIsolation.Mode.BYPASS)
        step(500, false, 400, VoiceIsolation.Mode.BYPASS)
        assertEquals(1, closed)
    }

    @Test fun falloNativoNoFiltraParcialmenteNiInterrumpeAudio() {
        var opens = 0
        var closes = 0
        val processor = VoiceIsolation {
            opens++
            object : VoiceFilter {
                override fun process(input: ShortArray, output: ShortArray): Boolean {
                    output.fill(99)
                    return false
                }
                override fun close() { closes++ }
            }
        }
        assertEquals(VoiceIsolation.Mode.UNAVAILABLE, processor.process(frame(5), out, true))
        assertArrayEquals(frame(0), out)
        assertEquals(VoiceIsolation.Mode.UNAVAILABLE, processor.process(frame(6), out, true))
        assertArrayEquals(frame(5), out)
        assertEquals(1, opens)
        assertEquals(1, closes)
        processor.close()
        assertEquals(1, closes)
    }
}
