package it.parrocchia.coro

import it.parrocchia.coro.audio.FadeCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FadeCurveAndMidiTest {

    @Test
    fun fadeOutGain_startsAtOne_endsAtZero_andDecreasesSmoothly() {
        assertEquals(1.0f, FadeCurve.fadeOutGain(0f), 1e-5f)
        assertEquals(0.25f, FadeCurve.fadeOutGain(0.5f), 1e-5f)
        assertEquals(0.0f, FadeCurve.fadeOutGain(1f), 1e-5f)
        assertEquals(1.0f, FadeCurve.fadeOutGain(-0.2f), 1e-5f)
        assertEquals(0.0f, FadeCurve.fadeOutGain(1.5f), 1e-5f)

        var prev = 1.0f
        for (i in 1..20) {
            val g = FadeCurve.fadeOutGain(i / 20f)
            assertTrue("La curva deve decrescere", g <= prev)
            prev = g
        }
    }

    @Test
    fun transposeMidiNote_shiftsMelodicChannelsAndClamps() {
        assertEquals(62, FadeCurve.transposeMidiNote(channel = 0, key = 60, semitones = 2))
        assertEquals(57, FadeCurve.transposeMidiNote(channel = 3, key = 60, semitones = -3))
        assertEquals(127, FadeCurve.transposeMidiNote(channel = 0, key = 124, semitones = 8))
        assertEquals(0, FadeCurve.transposeMidiNote(channel = 1, key = 3, semitones = -10))
    }

    @Test
    fun transposeMidiNote_preservesDrumChannel10() {
        // Canale 10 General MIDI = indice 9 (0-based)
        assertEquals(36, FadeCurve.transposeMidiNote(channel = 9, key = 36, semitones = 5))
        assertEquals(42, FadeCurve.transposeMidiNote(channel = 9, key = 42, semitones = -6))
        assertEquals(49, FadeCurve.transposeMidiNote(channel = 9, key = 49, semitones = 12))
    }

    @Test
    fun semitonesToPitchFactor_matchesEqualTemperament() {
        assertEquals(1.0f, FadeCurve.semitonesToPitchFactor(0), 1e-4f)
        assertEquals(2.0f, FadeCurve.semitonesToPitchFactor(12), 1e-4f)
        assertEquals(0.5f, FadeCurve.semitonesToPitchFactor(-12), 1e-4f)
        assertEquals(1.12246f, FadeCurve.semitonesToPitchFactor(2), 1e-3f)
    }

    @Test
    fun softLimit_keepsCleanSignalsLinearAndCapsPeaks() {
        assertEquals(0.5f, FadeCurve.softLimit(0.5f), 1e-6f)
        assertEquals(-0.8f, FadeCurve.softLimit(-0.8f), 1e-6f)
        assertTrue(FadeCurve.softLimit(2.5f) < 1.0f)
        assertTrue(FadeCurve.softLimit(-2.5f) > -1.0f)
    }

    @Test
    fun softLimit_isContinuousAndMonotonicWithoutCrackleSteps() {
        var prev = FadeCurve.softLimit(-3.0f)
        for (i in -299..300) {
            val x = i / 100f
            val y = FadeCurve.softLimit(x)
            assertTrue("Deve restare monotona crescente a x=$x ($prev -> $y)", y >= prev)
            assertTrue("Nessun salto brusco a x=$x (delta=${y - prev})", (y - prev) <= 0.011f)
            assertTrue("Sempre entro (-1, 1) a x=$x", y in -1.0f..1.0f)
            prev = y
        }
    }
}
