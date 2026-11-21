package com.microwifi.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class VideoCameraTest {
    @Test
    fun prefiereRangoVariableDe30ParaNoLimitarLaExposicion() {
        assertEquals(15..30, preferredVideoFpsRange(listOf(30..30, 24..24, 15..30, 15..24, 30..60), 30))
        assertEquals(24..24, preferredVideoFpsRange(listOf(15..15, 24..24, 30..30), 24))
    }

    @Test
    fun limitaLaCadenciaPorElTamanoSinInventarRangos() {
        assertEquals(15..30, preferredVideoFpsRange(listOf(30..30, 15..30, 15..15), 20))
        assertEquals(15..15, preferredVideoFpsRange(listOf(30..30, 15..15), 20))
        assertNull(preferredVideoFpsRange(listOf(30..30, 30..60), 20))
    }

    @Test
    fun copiaLuminanciaRecortadaSinLeerRellenoAlFinal() {
        val y = ByteBuffer.wrap(byteArrayOf(99, 1, 2, 3, 4, 99, 99, 5, 6, 7, 8)).apply { position(1) }
        val target = ByteArray(4)
        copyVideoPlane(y, 6, 1, 2, 0, 2, 2, target, 0, 1)
        assertArrayEquals(byteArrayOf(3, 4, 7, 8), target)
        assertEquals(1, y.position())
    }

    @Test
    fun copiaCromaEnOrdenNv21DesdePlanosConPasosDiferentes() {
        val u = ByteBuffer.wrap(byteArrayOf(1, 2, 99, 3, 4))
        val v = ByteBuffer.wrap(byteArrayOf(5, 99, 6, 99, 7, 99, 8))
        val target = ByteArray(8)
        copyVideoPlane(u, 3, 1, 0, 0, 2, 2, target, 1, 2)
        copyVideoPlane(v, 4, 2, 0, 0, 2, 2, target, 0, 2)
        assertArrayEquals(byteArrayOf(5, 1, 6, 2, 7, 3, 8, 4), target)
    }
}
