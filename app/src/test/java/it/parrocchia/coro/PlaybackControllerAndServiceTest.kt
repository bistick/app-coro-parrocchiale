package it.parrocchia.coro

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import android.media.AudioDeviceInfo
import it.parrocchia.coro.audio.AudioDeviceDescriptor
import it.parrocchia.coro.audio.AudioOutputRouter
import it.parrocchia.coro.audio.PlaybackController
import it.parrocchia.coro.audio.PlaybackForegroundService
import it.parrocchia.coro.audio.PlaybackStatus
import it.parrocchia.coro.domain.model.AudioOutputRoute
import it.parrocchia.coro.audio.media.AudioPlaybackEngine
import it.parrocchia.coro.audio.media.ExoAudioPlayer
import it.parrocchia.coro.audio.midi.MidiAudioPlayer
import it.parrocchia.coro.audio.midi.MidiPlaybackEngine
import it.parrocchia.coro.data.db.PlannedTrackEntity
import it.parrocchia.coro.data.db.PlannedTrackWithSong
import it.parrocchia.coro.data.db.SongEntity
import it.parrocchia.coro.data.repository.CoroRepository
import it.parrocchia.coro.domain.mixer.MixerController
import it.parrocchia.coro.domain.model.AudioFormat
import it.parrocchia.coro.domain.model.TrackSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackControllerAndServiceTest {

    private lateinit var context: Context
    private lateinit var repository: CoroRepository
    private lateinit var mixer: FakeMixerController
    private lateinit var midiEngine: FakeMidiEngine
    private lateinit var audioEngine: FakeAudioEngine
    private var serviceStartedCount = 0
    private var serviceUpdatedCount = 0
    private var serviceStoppedCount = 0
    private var clockMs = 1_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = mockk(relaxed = true)
        coEvery { repository.getActiveSoundFontPath() } returns "/tmp/TimGM6mb.sf2"
        mixer = FakeMixerController()
        midiEngine = FakeMidiEngine()
        audioEngine = FakeAudioEngine()
        serviceStartedCount = 0
        serviceUpdatedCount = 0
        serviceStoppedCount = 0
        clockMs = 1_000L
    }

    private fun createController(
        dispatcher: kotlinx.coroutines.CoroutineDispatcher = UnconfinedTestDispatcher(),
        audioOutputRouter: AudioOutputRouter? = null
    ) = PlaybackController(
        context = context,
        repository = repository,
        mixerController = mixer,
        midiPlayer = midiEngine,
        exoPlayer = audioEngine,
        audioOutputRouter = audioOutputRouter,
        dispatcher = dispatcher,
        startForegroundService = { _, _ -> serviceStartedCount++ },
        updateForegroundService = { _, _, _, _, _ -> serviceUpdatedCount++ },
        stopForegroundService = { serviceStoppedCount++ },
        clockMs = { clockMs }
    )

    private fun midiItem(trackId: Long = 10L) = PlannedTrackWithSong(
        track = PlannedTrackEntity(
            id = trackId,
            phaseId = 1L,
            songId = 100L,
            sortOrder = 0,
            fadeOutMs = 3000,
            startOffsetMs = 1500L,
            transposeSemitones = 2,
            volume = 1.1f,
            mixerConfigJson = "{\"ch\":1}"
        ),
        song = SongEntity(
            id = 100L,
            uri = "asset:///demo/01.mid",
            title = "Gloria MIDI",
            fileName = "01.mid",
            format = AudioFormat.MIDI.name,
            durationMs = 120_000L
        )
    )

    private fun audioItem(trackId: Long = 20L) = PlannedTrackWithSong(
        track = PlannedTrackEntity(
            id = trackId,
            phaseId = 2L,
            songId = 200L,
            sortOrder = 0,
            fadeOutMs = 4000,
            startOffsetMs = 500L,
            transposeSemitones = -1,
            volume = 0.9f
        ),
        song = SongEntity(
            id = 200L,
            uri = "content://audio/comunione.mp3",
            title = "Comunione MP3",
            fileName = "comunione.mp3",
            format = AudioFormat.AUDIO.name,
            durationMs = 180_000L
        )
    )

    @Test
    fun liveMidiPlayback_enforcesMutualExclusionFadesAndMarksPlayedOnComplete() = runTest {
        val controller = createController()
        val item = midiItem()

        assertTrue(controller.playLiveTrack("Gloria", item))
        // Mutual exclusion: second play ignored while busy
        assertFalse(controller.playLiveTrack("Comunione", audioItem()))

        assertEquals(PlaybackStatus.PLAYING, controller.state.value.status)
        assertEquals(1, serviceStartedCount)
        assertEquals(listOf("start:10"), mixer.events)
        assertEquals("/tmp/TimGM6mb.sf2", midiEngine.lastSoundFont)

        // Progress updates (playing + fading)
        midiEngine.listener!!.onProgress(15_000L, 120_000L, false, 0f)
        assertEquals(15_000L, controller.state.value.positionMs)

        // Live preview operations are ignored during live playback
        controller.updatePreviewTranspose(4)
        controller.updatePreviewVolume(0.5f)
        controller.seekPreview(5000L)
        assertEquals(0, midiEngine.transposeCalls)

        // Fade out immediately marks track played & sets lastLiveTrackId
        controller.fadeOutCurrent()
        assertEquals(PlaybackStatus.FADING_OUT, controller.state.value.status)
        assertEquals(10L, controller.state.value.lastLiveTrackId)
        assertEquals(3000, midiEngine.lastFadeOutMs)
        assertTrue(mixer.events.contains("fade:10:3000"))
        coVerify(exactly = 1) { repository.markTrackPlayed(10L, true) }

        // Second fadeOut ignored when already FADING_OUT
        controller.fadeOutCurrent()

        midiEngine.listener!!.onProgress(18_000L, 0L, true, 0.5f)
        assertEquals(0.5f, controller.state.value.fadeProgress, 1e-4f)
        assertEquals(120_000L, controller.state.value.durationMs) // dur <= 0 keeps previous duration

        // Completion resets to IDLE while preserving lastLiveTrackId (and without duplicating markTrackPlayed)
        midiEngine.listener!!.onCompleted()
        assertEquals(PlaybackStatus.IDLE, controller.state.value.status)
        assertEquals(10L, controller.state.value.lastLiveTrackId)
        assertTrue(mixer.events.contains("stop:10"))
        coVerify(exactly = 1) { repository.markTrackPlayed(10L, true) }
        assertTrue(serviceStoppedCount >= 1)

        controller.resetLastLiveTrack()
        assertNull(controller.state.value.lastLiveTrackId)

        // Progress after IDLE is safely ignored
        midiEngine.listener!!.onProgress(99_000L, 120_000L, false, 0f)
        assertEquals(PlaybackStatus.IDLE, controller.state.value.status)
    }

    @Test
    fun liveAudioPlayback_supportsStopWithAntiClickAndErrorRecovery() = runTest {
        val controller = createController()
        val item = audioItem()

        assertTrue(controller.playLiveTrack("Comunione", item))
        assertEquals(PlaybackStatus.PLAYING, controller.state.value.status)
        audioEngine.listener!!.onProgress(20_000L, 180_000L, false, 0f)
        assertEquals(20_000L, controller.state.value.positionMs)

        // Stop immediate on live audio marks track played immediately and sets lastLiveTrackId
        controller.stopImmediateCurrent()
        assertEquals(1, audioEngine.stopWithAntiClickCalls)
        assertEquals(20L, controller.state.value.lastLiveTrackId)
        coVerify(exactly = 1) { repository.markTrackPlayed(20L, true) }
        assertEquals(listOf(item.track.mixerConfigJson), mixer.stopMixerJsons)

        // Error transitions to ERROR and clearError returns to IDLE preserving lastLiveTrackId
        audioEngine.listener!!.onError("File non trovato")
        assertEquals(PlaybackStatus.ERROR, controller.state.value.status)
        assertEquals("File non trovato", controller.state.value.errorMessage)
        assertEquals(20L, controller.state.value.lastLiveTrackId)
        // onTrackStop was already fired on stopImmediateCurrent and must not fire twice
        assertEquals(1, mixer.stopMixerJsons.size)
        controller.clearError()
        assertEquals(PlaybackStatus.IDLE, controller.state.value.status)
        assertEquals(20L, controller.state.value.lastLiveTrackId)

        // clearError / stopImmediate / fadeOut when IDLE are safe no-ops
        controller.clearError()
        controller.stopImmediateCurrent()
        controller.fadeOutCurrent()
    }

    @Test
    fun stopImmediateDuringLoading_stopsSynchronouslyAndMarksLiveTrackPlayed() = runTest {
        val testDispatcher = StandardTestDispatcher()
        val controller = createController(dispatcher = testDispatcher)

        assertTrue(controller.playLiveTrack("Gloria", midiItem()))
        assertEquals(PlaybackStatus.LOADING, controller.state.value.status)

        controller.stopImmediateCurrent()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(PlaybackStatus.IDLE, controller.state.value.status)
        assertEquals(10L, controller.state.value.lastLiveTrackId)
        coVerify(exactly = 1) { repository.markTrackPlayed(10L, true) }
        assertEquals(1, midiEngine.stopImmediateSyncCalls)
        assertEquals(1, audioEngine.stopImmediateCalls)
    }

    @Test
    fun midiSoundFontFailure_transitionsToErrorState() = runTest {
        coEvery { repository.getActiveSoundFontPath() } throws IllegalStateException("SF2 mancante")
        val controller = createController()

        controller.playLiveTrack("Gloria", midiItem())
        assertEquals(PlaybackStatus.ERROR, controller.state.value.status)
        assertTrue(controller.state.value.errorMessage!!.contains("SF2 mancante"))
    }

    @Test
    fun previewMode_supportsMidiAndAudioControlsAndStopPreview() = runTest {
        val controller = createController()
        val midiSong = midiItem().song
        val audioSong = audioItem().song

        // MIDI preview
        controller.startPreview(midiSong, TrackSettings(fadeOutMs = 2000, startOffsetMs = 1000L))
        assertTrue(controller.state.value.isPreview)
        assertEquals(PlaybackStatus.PLAYING, controller.state.value.status)

        controller.updatePreviewTranspose(3)
        controller.updatePreviewVolume(1.2f)
        controller.seekPreview(-500L)
        assertEquals(0L, controller.state.value.positionMs)
        assertEquals(1, midiEngine.transposeCalls)
        assertEquals(1, midiEngine.volumeCalls)
        assertEquals(1, midiEngine.seekCalls)

        // MIDI stopImmediate during PLAYING calls stopWithAntiClick
        controller.stopImmediateCurrent()
        assertEquals(1, midiEngine.stopWithAntiClickCalls)

        // Starting AUDIO preview stops MIDI preview
        controller.startPreview(audioSong, TrackSettings())
        assertEquals(PlaybackStatus.PLAYING, controller.state.value.status)
        controller.updatePreviewTranspose(-2)
        controller.updatePreviewVolume(0.7f)
        controller.seekPreview(12_000L)
        assertEquals(1, audioEngine.transposeCalls)
        assertEquals(1, audioEngine.volumeCalls)
        assertEquals(1, audioEngine.seekCalls)

        // Preview completion does NOT mark track played
        audioEngine.listener!!.onCompleted()
        assertEquals(PlaybackStatus.IDLE, controller.state.value.status)
        coVerify(exactly = 0) { repository.markTrackPlayed(any(), any()) }

        // stopPreviewIfActive stops active preview and ignores live tracks
        controller.startPreview(midiSong, TrackSettings())
        controller.stopPreviewIfActive()
        assertEquals(PlaybackStatus.IDLE, controller.state.value.status)

        controller.playLiveTrack("Gloria", midiItem())
        controller.stopPreviewIfActive()
        assertEquals(PlaybackStatus.PLAYING, controller.state.value.status)

        // MIDI listener error callback
        midiEngine.listener!!.onError("Errore FluidSynth")
        assertEquals(PlaybackStatus.ERROR, controller.state.value.status)
        assertEquals("Errore FluidSynth", controller.state.value.errorMessage)
        controller.clearError()
        assertEquals(PlaybackStatus.IDLE, controller.state.value.status)

        // Idle guards are safe no-ops
        controller.clearError()
        controller.fadeOutCurrent()
        controller.stopImmediateCurrent()
        controller.updatePreviewTranspose(2)
        controller.updatePreviewVolume(0.5f)
        controller.seekPreview(1000L)
        assertEquals(PlaybackStatus.IDLE, controller.state.value.status)
    }

    @Test
    fun playbackForegroundService_handlesMediaSessionLockScreenActionsAndLifecycle() {
        var fadeCallbacks = 0
        var stopCallbacks = 0
        PlaybackForegroundService.registerTransportCallbacks(
            onFadeOut = { fadeCallbacks++ },
            onStop = { stopCallbacks++ }
        )

        val serviceController = Robolectric.buildService(PlaybackForegroundService::class.java)
        val service = serviceController.create().get()
        assertNull(service.onBind(Intent()))

        // Start with title, phase, duration & position
        val startIntent = Intent(context, PlaybackForegroundService::class.java).apply {
            action = PlaybackForegroundService.ACTION_START
            putExtra(PlaybackForegroundService.EXTRA_TITLE, "Gloria")
            putExtra(PlaybackForegroundService.EXTRA_PHASE, "Riti di Introduzione")
            putExtra(PlaybackForegroundService.EXTRA_POSITION_MS, 5_000L)
            putExtra(PlaybackForegroundService.EXTRA_DURATION_MS, 120_000L)
            putExtra(PlaybackForegroundService.EXTRA_IS_FADING, false)
        }
        service.onStartCommand(startIntent, 0, 1)

        // Live progress update & fading update
        val updateIntent = Intent(context, PlaybackForegroundService::class.java).apply {
            action = PlaybackForegroundService.ACTION_UPDATE
            putExtra(PlaybackForegroundService.EXTRA_TITLE, "Gloria")
            putExtra(PlaybackForegroundService.EXTRA_PHASE, "Riti di Introduzione")
            putExtra(PlaybackForegroundService.EXTRA_POSITION_MS, 30_000L)
            putExtra(PlaybackForegroundService.EXTRA_DURATION_MS, 120_000L)
            putExtra(PlaybackForegroundService.EXTRA_IS_FADING, true)
        }
        service.onStartCommand(updateIntent, 0, 2)

        // Lock screen notification action buttons: Sfuma & Stop
        service.onStartCommand(
            Intent(context, PlaybackForegroundService::class.java).apply {
                action = PlaybackForegroundService.ACTION_FADE_OUT
            },
            0,
            3
        )
        service.onStartCommand(
            Intent(context, PlaybackForegroundService::class.java).apply {
                action = PlaybackForegroundService.ACTION_STOP_PLAYBACK
            },
            0,
            4
        )
        assertEquals(1, fadeCallbacks)
        assertEquals(1, stopCallbacks)

        // Start with blank title and no phase (fallback text)
        val blankIntent = Intent(context, PlaybackForegroundService::class.java)
        service.onStartCommand(blankIntent, 0, 5)

        // Stop action + destroy releases MediaSession cleanly
        val stopIntent = Intent(context, PlaybackForegroundService::class.java).apply {
            action = PlaybackForegroundService.ACTION_STOP
        }
        service.onStartCommand(stopIntent, 0, 6)
        serviceController.destroy()

        // Companion helpers execute cleanly
        PlaybackForegroundService.start(context, "Santo", "Liturgia Eucaristica", 1000L, 90_000L, false)
        PlaybackForegroundService.update(context, "Santo", "Liturgia Eucaristica", 5000L, 90_000L, true)
        PlaybackForegroundService.stop(context)
        PlaybackForegroundService.registerTransportCallbacks(null, null)
    }

    @Test
    fun audioOutputRouter_propagatesPreferredDeviceToBothEngines() = runTest {
        val btInfo = mockk<AudioDeviceInfo>(relaxed = true)
        val router = AudioOutputRouter(
            audioManager = null,
            deviceProvider = {
                listOf(AudioDeviceDescriptor(1, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Mixer BT", btInfo))
            },
            registerSystemCallback = false
        )
        val controller = createController(audioOutputRouter = router)
        router.setPreferredRoute(AudioOutputRoute.BLUETOOTH)
        assertEquals(btInfo, midiEngine.lastPreferredDevice)
        assertEquals(btInfo, audioEngine.lastPreferredDevice)

        controller.playLiveTrack("Ingresso", midiItem())
        clockMs += 1_500L
        midiEngine.listener!!.onProgress(10_000L, 120_000L, false, 0f)
        assertTrue(serviceUpdatedCount >= 2)
    }

    private class FakeMixerController : MixerController {
        val events = mutableListOf<String>()
        val stopMixerJsons = mutableListOf<String?>()
        override suspend fun onPhaseSelected(phaseId: Long, mixerConfigJson: String?) {
            events += "phase:$phaseId"
        }
        override suspend fun onTrackStart(trackId: Long, mixerConfigJson: String?) {
            events += "start:$trackId"
        }
        override suspend fun onTrackFade(trackId: Long, fadeOutMs: Int, mixerConfigJson: String?) {
            events += "fade:$trackId:$fadeOutMs"
        }
        override suspend fun onTrackStop(trackId: Long, mixerConfigJson: String?) {
            events += "stop:$trackId"
            stopMixerJsons += mixerConfigJson
        }
    }

    private class FakeMidiEngine : MidiPlaybackEngine {
        var listener: MidiAudioPlayer.Listener? = null
        var lastSoundFont: String? = null
        var lastFadeOutMs: Int = 0
        var stopWithAntiClickCalls = 0
        var stopImmediateSyncCalls = 0
        var transposeCalls = 0
        var volumeCalls = 0
        var seekCalls = 0
        var lastPreferredDevice: AudioDeviceInfo? = null

        override fun start(
            uriString: String,
            soundFontPath: String,
            startOffsetMs: Long,
            transposeSemitones: Int,
            volume: Float,
            listener: MidiAudioPlayer.Listener
        ) {
            this.lastSoundFont = soundFontPath
            this.listener = listener
        }
        override fun fadeOut(durationMs: Int) { lastFadeOutMs = durationMs }
        override fun stopWithAntiClick() { stopWithAntiClickCalls++ }
        override fun stopImmediateSync() { stopImmediateSyncCalls++ }
        override fun updateLiveTranspose(semitones: Int) { transposeCalls++ }
        override fun updateLiveVolume(volume: Float) { volumeCalls++ }
        override fun seekLive(positionMs: Long) { seekCalls++ }
        override fun setPreferredOutputDevice(device: AudioDeviceInfo?) { lastPreferredDevice = device }
    }

    private class FakeAudioEngine : AudioPlaybackEngine {
        var listener: ExoAudioPlayer.Listener? = null
        var lastFadeOutMs: Int = 0
        var stopWithAntiClickCalls = 0
        var stopImmediateCalls = 0
        var transposeCalls = 0
        var volumeCalls = 0
        var seekCalls = 0
        var lastPreferredDevice: AudioDeviceInfo? = null

        override fun start(
            uriString: String,
            startOffsetMs: Long,
            transposeSemitones: Int,
            volume: Float,
            listener: ExoAudioPlayer.Listener
        ) {
            this.listener = listener
        }
        override fun fadeOut(durationMs: Int) { lastFadeOutMs = durationMs }
        override fun stopWithAntiClick() { stopWithAntiClickCalls++ }
        override fun stopImmediate() { stopImmediateCalls++ }
        override fun updateLiveTranspose(semitones: Int) { transposeCalls++ }
        override fun updateLiveVolume(volume: Float) { volumeCalls++ }
        override fun seekLive(positionMs: Long) { seekCalls++ }
        override fun setPreferredOutputDevice(device: AudioDeviceInfo?) { lastPreferredDevice = device }
    }
}
