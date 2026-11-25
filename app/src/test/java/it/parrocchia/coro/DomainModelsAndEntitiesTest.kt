package it.parrocchia.coro

import it.parrocchia.coro.audio.PlaybackState
import it.parrocchia.coro.audio.PlaybackStatus
import it.parrocchia.coro.audio.midi.NativeMidiSynth
import it.parrocchia.coro.data.db.MassPhaseEntity
import it.parrocchia.coro.data.db.MassPlanEntity
import it.parrocchia.coro.data.db.MassPlanWithPhases
import it.parrocchia.coro.data.db.PhaseWithTracks
import it.parrocchia.coro.data.db.PlannedTrackEntity
import it.parrocchia.coro.data.db.PlannedTrackWithSong
import it.parrocchia.coro.data.db.SongEntity
import it.parrocchia.coro.domain.mixer.NoOpMixerController
import it.parrocchia.coro.domain.model.AudioFormat
import it.parrocchia.coro.domain.model.LiturgicalSection
import it.parrocchia.coro.domain.model.MassPresetCatalog
import it.parrocchia.coro.domain.model.MassPresetCategory
import it.parrocchia.coro.domain.model.StandardPhaseType
import it.parrocchia.coro.domain.model.TrackSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainModelsAndEntitiesTest {

    @Test
    fun audioFormat_coversAllExtensionsStripAndFromCode() {
        assertEquals(AudioFormat.MIDI, AudioFormat.fromFileName("canto.mid"))
        assertEquals(AudioFormat.MIDI, AudioFormat.fromFileName("canto.MIDI"))
        assertEquals(AudioFormat.MIDI, AudioFormat.fromFileName("canto.kar"))
        assertEquals(AudioFormat.AUDIO, AudioFormat.fromFileName("canto.mp3"))
        assertEquals(AudioFormat.AUDIO, AudioFormat.fromFileName("canto.wav"))
        assertEquals(AudioFormat.AUDIO, AudioFormat.fromFileName("canto.m4a"))
        assertEquals(AudioFormat.AUDIO, AudioFormat.fromFileName("canto.ogg"))
        assertEquals(AudioFormat.AUDIO, AudioFormat.fromFileName("canto.flac"))
        assertEquals(AudioFormat.AUDIO, AudioFormat.fromFileName("canto.aac"))
        assertNull(AudioFormat.fromFileName("senza_estensione"))
        assertNull(AudioFormat.fromFileName("documento.pdf"))

        assertEquals("Canto", AudioFormat.stripExtension("Canto.mid"))
        assertEquals("Canto.v2", AudioFormat.stripExtension("Canto.v2.mp3"))
        assertEquals("SenzaPunto", AudioFormat.stripExtension("SenzaPunto"))
        assertEquals(".nascosto", AudioFormat.stripExtension(".nascosto"))

        assertEquals(AudioFormat.MIDI, AudioFormat.fromCode("MIDI"))
        assertEquals(AudioFormat.AUDIO, AudioFormat.fromCode("AUDIO"))
        assertEquals(AudioFormat.AUDIO, AudioFormat.fromCode("UNKNOWN"))
        assertEquals("MIDI", AudioFormat.MIDI.shortLabel)
        assertEquals("AUDIO", AudioFormat.AUDIO.shortLabel)

        for (mode in it.parrocchia.coro.domain.model.AppThemeMode.entries) {
            assertEquals(mode, it.parrocchia.coro.domain.model.AppThemeMode.fromStoredName(mode.name))
            assertTrue(mode.displayLabel.isNotBlank())
            assertTrue(mode.description.isNotBlank())
        }
        assertEquals(
            it.parrocchia.coro.domain.model.AppThemeMode.DARK,
            it.parrocchia.coro.domain.model.AppThemeMode.fromStoredName(null)
        )
        assertEquals(
            it.parrocchia.coro.domain.model.AppThemeMode.DARK,
            it.parrocchia.coro.domain.model.AppThemeMode.fromStoredName("UNKNOWN")
        )
    }

    @Test
    fun standardPhaseType_coversFromCodeAndAllSections() {
        for (phase in StandardPhaseType.entries) {
            assertEquals(phase, StandardPhaseType.fromCode(phase.name))
            assertTrue(phase.displayName.isNotBlank())
            assertTrue(phase.section.label.isNotBlank())
        }
        assertEquals(StandardPhaseType.CUSTOM, StandardPhaseType.fromCode("INVALID_CODE"))
        assertEquals(5, LiturgicalSection.entries.size)
        assertTrue(StandardPhaseType.CUSTOM.hint.isNotBlank())
    }

    @Test
    fun massPresetCatalog_coversAllCategoriesAndNullUnknownLookup() {
        assertNull(MassPresetCatalog.findById(null))
        assertNull(MassPresetCatalog.findById("non_esiste"))
        for (cat in MassPresetCategory.entries) {
            assertTrue(cat.label.isNotBlank())
            assertTrue(MassPresetCatalog.all.any { it.category == cat })
        }
        assertEquals(MassPresetCatalog.FERIALE, MassPresetCatalog.findById("feriale"))
        assertEquals(MassPresetCatalog.NATALE, MassPresetCatalog.findById("natale"))
        assertEquals(MassPresetCatalog.PASQUA, MassPresetCatalog.findById("pasqua"))
        assertEquals(MassPresetCatalog.MATRIMONIO, MassPresetCatalog.findById("matrimonio"))
        assertEquals(MassPresetCatalog.FUNERALE, MassPresetCatalog.findById("funerale"))
    }

    @Test
    fun trackSettings_coversEdgeNormalizationAndFormatting() {
        val negative = TrackSettings(
            fadeOutMs = 0,
            startOffsetMs = -500L,
            transposeSemitones = -99,
            volume = -1.0f
        ).normalized(maxDurationMs = 1500L)
        assertEquals(TrackSettings.MIN_FADE_OUT_MS, negative.fadeOutMs)
        assertEquals(0L, negative.startOffsetMs)
        assertEquals(TrackSettings.MIN_TRANSPOSE, negative.transposeSemitones)
        assertEquals(TrackSettings.MIN_VOLUME, negative.volume, 1e-5f)

        val shortSong = TrackSettings(startOffsetMs = 5000L).normalized(maxDurationMs = 1500L)
        assertEquals(1500L, shortSong.startOffsetMs)

        val zeroDuration = TrackSettings(startOffsetMs = 1000L).normalized(maxDurationMs = -10L)
        assertEquals(0L, zeroDuration.startOffsetMs)

        val defaultNorm = TrackSettings().normalized()
        assertEquals(TrackSettings.DEFAULT_FADE_OUT_MS, defaultNorm.fadeOutMs)

        assertEquals("0:00", TrackSettings.formatTimeMs(-5000L))
        assertEquals("10:05", TrackSettings.formatTimeMs(605_000L))
        assertEquals("+5 st", TrackSettings.formatTranspose(5))
        assertEquals("-1 st", TrackSettings.formatTranspose(-1))
        assertEquals("Orig.", TrackSettings.formatTranspose(0))
        assertEquals("5s", TrackSettings.formatFadeSec(5000))
        assertEquals("0,5s", TrackSettings.formatFadeSec(500))
        assertEquals("20%", TrackSettings.formatVolumePercent(0.2f))
    }

    @Test
    fun entities_computedPropertiesAndOrderingWorkCorrectly() {
        val song = SongEntity(
            id = 1L,
            uri = "content://song/1",
            title = "Gloria",
            fileName = "Gloria.mid",
            format = AudioFormat.MIDI.name,
            durationMs = 120_000L,
            defaultFadeOutMs = 4000,
            defaultStartOffsetMs = 2000L,
            defaultTransposeSemitones = 2,
            defaultVolume = 1.2f
        )
        assertEquals(AudioFormat.MIDI, song.audioFormat)
        assertEquals(TrackSettings(4000, 2000L, 2, 1.2f), song.defaultSettings)

        val stdPhase = MassPhaseEntity(
            id = 10L,
            massPlanId = 1L,
            phaseType = StandardPhaseType.GLORIA.name,
            customLabel = null,
            sortOrder = 40,
            isEnabled = true
        )
        assertEquals(StandardPhaseType.GLORIA, stdPhase.standardType)
        assertEquals(StandardPhaseType.GLORIA.displayName, stdPhase.displayName)

        val blankLabelPhase = stdPhase.copy(customLabel = "   ")
        assertEquals(StandardPhaseType.GLORIA.displayName, blankLabelPhase.displayName)

        val customPhase = MassPhaseEntity(
            id = 11L,
            massPlanId = 1L,
            phaseType = StandardPhaseType.CUSTOM.name,
            customLabel = "Lavanda dei piedi",
            sortOrder = 85,
            isEnabled = false
        )
        assertEquals("Lavanda dei piedi", customPhase.displayName)

        val track2 = PlannedTrackEntity(
            id = 202L,
            phaseId = 10L,
            songId = 1L,
            sortOrder = 2,
            fadeOutMs = 2500,
            startOffsetMs = 1000L,
            transposeSemitones = -1,
            volume = 0.8f
        )
        val track1 = track2.copy(id = 201L, sortOrder = 0)
        assertEquals(TrackSettings(2500, 1000L, -1, 0.8f), track2.settings)

        val phaseWithTracks = PhaseWithTracks(
            phase = stdPhase,
            tracks = listOf(PlannedTrackWithSong(track2, song), PlannedTrackWithSong(track1, song))
        )
        assertEquals(listOf(201L, 202L), phaseWithTracks.orderedTracks.map { it.track.id })

        val plan = MassPlanEntity(id = 1L, title = "Messa", dateEpochDay = 20000L)
        val massWithPhases = MassPlanWithPhases(
            plan = plan,
            phases = listOf(
                PhaseWithTracks(customPhase, emptyList()),
                phaseWithTracks
            )
        )
        assertEquals(listOf(10L, 11L), massWithPhases.orderedPhases.map { it.phase.id })
        assertEquals(listOf(10L), massWithPhases.enabledPhases.map { it.phase.id })
    }

    @Test
    fun noOpMixerController_executesSafely() = runTest {
        val mixer = NoOpMixerController()
        mixer.onPhaseSelected(1L, "{}")
        mixer.onTrackStart(2L, null)
        mixer.onTrackFade(2L, 3000, "{}")
        mixer.onTrackStop(2L, null)
    }

    @Test
    fun playbackState_coversAllStatusesAndProgressBounds() {
        val loading = PlaybackState(status = PlaybackStatus.LOADING, positionMs = -10L, durationMs = 100L)
        assertTrue(loading.isBusy)
        assertFalse(loading.isPlayingOrFading)
        assertEquals(0f, loading.progressFraction, 1e-5f)

        val playing = PlaybackState(status = PlaybackStatus.PLAYING, positionMs = 200L, durationMs = 100L)
        assertTrue(playing.isBusy)
        assertTrue(playing.isPlayingOrFading)
        assertEquals(1f, playing.progressFraction, 1e-5f)

        val fading = PlaybackState(status = PlaybackStatus.FADING_OUT, positionMs = 50L, durationMs = 100L)
        assertTrue(fading.isBusy)
        assertTrue(fading.isPlayingOrFading)
        assertEquals(0.5f, fading.progressFraction, 1e-5f)

        val error = PlaybackState(status = PlaybackStatus.ERROR, errorMessage = "Errore")
        assertFalse(error.isBusy)
        assertFalse(error.isPlayingOrFading)
        assertEquals(0f, error.progressFraction, 1e-5f)
    }

    @Test
    fun nativeMidiSynth_fallsBackGracefullyWhenJniUnavailableOnJvm() {
        assertFalse(NativeMidiSynth.isAvailable())
        assertEquals(0L, NativeMidiSynth.create("/fake/path.sf2", 48000))
        assertEquals(-1L, NativeMidiSynth.loadMidi(0L, byteArrayOf(1, 2, 3)))
        assertEquals(-1L, NativeMidiSynth.loadMidi(123L, byteArrayOf(1, 2, 3)))
        NativeMidiSynth.seek(0L, 1000L)
        NativeMidiSynth.seek(123L, 1000L)
        NativeMidiSynth.setTranspose(0L, 2)
        NativeMidiSynth.setTranspose(123L, 2)
        NativeMidiSynth.allNotesOff(0L)
        NativeMidiSynth.allNotesOff(123L)
        assertTrue(NativeMidiSynth.render(0L, FloatArray(16), 8))
        assertTrue(NativeMidiSynth.render(123L, FloatArray(16), 8))
        assertEquals(0L, NativeMidiSynth.getPositionMs(0L))
        assertEquals(0L, NativeMidiSynth.getPositionMs(123L))
        NativeMidiSynth.destroy(0L)
        NativeMidiSynth.destroy(123L)
        NativeMidiSynth.clearSoundFontCache()
        assertEquals(0L, NativeMidiSynth.probeMidiDurationMs(byteArrayOf(0x4D, 0x54, 0x68, 0x64)))
    }
}
