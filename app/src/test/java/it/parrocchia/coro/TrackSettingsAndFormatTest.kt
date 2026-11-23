package it.parrocchia.coro

import it.parrocchia.coro.domain.model.AudioFormat
import it.parrocchia.coro.domain.model.TrackSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackSettingsAndFormatTest {

    @Test
    fun audioFormatDetection_supportsMidiKarAndCommonAudio() {
        assertEquals(AudioFormat.MIDI, AudioFormat.fromFileName("Gloria.mid"))
        assertEquals(AudioFormat.MIDI, AudioFormat.fromFileName("SANTO.MIDI"))
        assertEquals(AudioFormat.MIDI, AudioFormat.fromFileName("Astro_del_Ciel.kar"))
        assertEquals(AudioFormat.AUDIO, AudioFormat.fromFileName("Comunione.mp3"))
        assertEquals(AudioFormat.AUDIO, AudioFormat.fromFileName("Offertorio.WAV"))
        assertEquals(AudioFormat.AUDIO, AudioFormat.fromFileName("Canto.m4a"))
        assertNull(AudioFormat.fromFileName("Testo_canti.pdf"))
        assertNull(AudioFormat.fromFileName("README.txt"))
    }

    @Test
    fun trackSettingsNormalization_clampsOutOfRangeValues() {
        val raw = TrackSettings(
            fadeOutMs = 99_000,
            startOffsetMs = 45_000L,
            transposeSemitones = 20,
            volume = 3.0f
        )
        val norm = raw.normalized(maxDurationMs = 30_000L)
        assertEquals(TrackSettings.MAX_FADE_OUT_MS, norm.fadeOutMs)
        assertEquals(29_000L, norm.startOffsetMs)
        assertEquals(TrackSettings.MAX_TRANSPOSE, norm.transposeSemitones)
        assertEquals(TrackSettings.MAX_VOLUME, norm.volume, 1e-5f)
    }

    @Test
    fun formattingHelpers_produceReadableItalianLabels() {
        assertEquals("0:00", TrackSettings.formatTimeMs(0L))
        assertEquals("0:06", TrackSettings.formatTimeMs(6_000L))
        assertEquals("2:15", TrackSettings.formatTimeMs(135_000L))
        assertEquals("+2 st", TrackSettings.formatTranspose(2))
        assertEquals("-3 st", TrackSettings.formatTranspose(-3))
        assertEquals("Orig.", TrackSettings.formatTranspose(0))
        assertEquals("3s", TrackSettings.formatFadeSec(3000))
        assertEquals("2,5s", TrackSettings.formatFadeSec(2500))
        assertEquals("100%", TrackSettings.formatVolumePercent(1.0f))
        assertEquals("125%", TrackSettings.formatVolumePercent(1.25f))
    }
}
