package it.parrocchia.coro.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import it.parrocchia.coro.audio.media.AudioPlaybackEngine
import it.parrocchia.coro.audio.media.ExoAudioPlayer
import it.parrocchia.coro.audio.midi.MidiAudioPlayer
import it.parrocchia.coro.audio.midi.MidiPlaybackEngine
import it.parrocchia.coro.data.db.PlannedTrackWithSong
import it.parrocchia.coro.data.db.SongEntity
import it.parrocchia.coro.data.repository.CoroRepository
import it.parrocchia.coro.domain.mixer.MixerController
import it.parrocchia.coro.domain.model.AudioFormat
import it.parrocchia.coro.domain.model.TrackSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Controller unificato per l'esecuzione live in chiesa e per l'anteprima in programmazione.
 * Garantisce mutua esclusione in modalità live (impossibile avviare un secondo brano mentre uno suona),
 * instradamento uscita audio (Bluetooth / USB / Altoparlante) e sincronizzazione MediaSession/lock screen.
 */
class PlaybackController(
    private val context: Context,
    private val repository: CoroRepository,
    private val mixerController: MixerController,
    private val midiPlayer: MidiPlaybackEngine = MidiAudioPlayer(context),
    private val exoPlayer: AudioPlaybackEngine = ExoAudioPlayer(context),
    val audioOutputRouter: AudioOutputRouter? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val startForegroundService: (title: String, phaseLabel: String) -> Unit = { title, phase ->
        PlaybackForegroundService.start(context, title, phase)
    },
    private val updateForegroundService: (
        title: String,
        phaseLabel: String,
        positionMs: Long,
        durationMs: Long,
        isFading: Boolean
    ) -> Unit = { title, phase, positionMs, durationMs, isFading ->
        PlaybackForegroundService.update(context, title, phase, positionMs, durationMs, isFading)
    },
    private val stopForegroundService: () -> Unit = {
        PlaybackForegroundService.stop(context)
    },
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var audioFocusRequest: AudioFocusRequest? = null
    private var startJob: Job? = null
    private var activeFormat: AudioFormat? = null
    private var activeMixerJson: String? = null
    private var trackStopNotified: Boolean = false
    private var trackPlayedMarked: Boolean = false
    private var lastMediaNotificationUpdateMs: Long = 0L
    private var lastNotifiedDurationMs: Long = 0L

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    init {
        audioOutputRouter?.setOnPreferredDeviceChangedListener { preferredDevice ->
            midiPlayer.setPreferredOutputDevice(preferredDevice)
            exoPlayer.setPreferredOutputDevice(preferredDevice)
        }
        PlaybackForegroundService.registerTransportCallbacks(
            onFadeOut = { fadeOutCurrent() },
            onStop = { stopImmediateCurrent() }
        )
    }

    /**
     * Avvia un brano pianificato in Modalità Esecuzione.
     * Se un altro brano è già in corso o in sfumatura, la richiesta viene ignorata (protezione live).
     */
    fun playLiveTrack(phaseLabel: String, item: PlannedTrackWithSong): Boolean {
        val current = _state.value
        if (current.isBusy) {
            return false
        }

        val song = item.song
        val track = item.track
        val format = song.audioFormat

        _state.value = PlaybackState(
            status = PlaybackStatus.LOADING,
            activeTrackId = track.id,
            lastLiveTrackId = current.lastLiveTrackId,
            songId = song.id,
            title = song.title,
            phaseLabel = phaseLabel,
            format = format,
            positionMs = track.startOffsetMs,
            durationMs = song.durationMs,
            fadeOutMs = track.fadeOutMs,
            isPreview = false
        )

        activeFormat = format
        activeMixerJson = track.mixerConfigJson
        trackStopNotified = false
        trackPlayedMarked = false
        lastMediaNotificationUpdateMs = 0L
        lastNotifiedDurationMs = 0L

        startJob?.cancel()
        startJob = scope.launch {
            applyCurrentPreferredOutputDevice()
            requestAudioFocus()
            startForegroundService(song.title, phaseLabel)
            updateForegroundService(
                song.title,
                phaseLabel,
                track.startOffsetMs,
                song.durationMs,
                false
            )
            lastMediaNotificationUpdateMs = clockMs()
            lastNotifiedDurationMs = song.durationMs
            mixerController.onTrackStart(track.id, track.mixerConfigJson)
            startEngine(
                uri = song.uri,
                format = format,
                settings = track.settings,
                isPreview = false,
                trackIdForCompletion = track.id
            )
        }
        return true
    }

    /**
     * Avvia l'anteprima audio nell'editor impostazioni del brano (Modalità Programmazione).
     */
    fun startPreview(song: SongEntity, settings: TrackSettings) {
        val preservedLastLiveId = _state.value.lastLiveTrackId
        stopInternal(markLivePlayed = false)

        val format = song.audioFormat
        activeFormat = format
        activeMixerJson = null

        _state.value = PlaybackState(
            status = PlaybackStatus.LOADING,
            activeTrackId = null,
            lastLiveTrackId = preservedLastLiveId,
            songId = song.id,
            title = song.title,
            phaseLabel = "Anteprima",
            format = format,
            positionMs = settings.startOffsetMs,
            durationMs = song.durationMs,
            fadeOutMs = settings.fadeOutMs,
            isPreview = true
        )

        startJob = scope.launch {
            applyCurrentPreferredOutputDevice()
            requestAudioFocus()
            startEngine(
                uri = song.uri,
                format = format,
                settings = settings,
                isPreview = true,
                trackIdForCompletion = null
            )
        }
    }

    fun updatePreviewTranspose(semitones: Int) {
        if (!_state.value.isPreview || !_state.value.isPlayingOrFading) return
        when (activeFormat) {
            AudioFormat.MIDI -> midiPlayer.updateLiveTranspose(semitones)
            AudioFormat.AUDIO -> exoPlayer.updateLiveTranspose(semitones)
            null -> Unit
        }
    }

    fun updatePreviewVolume(volume: Float) {
        if (!_state.value.isPreview || !_state.value.isPlayingOrFading) return
        when (activeFormat) {
            AudioFormat.MIDI -> midiPlayer.updateLiveVolume(volume)
            AudioFormat.AUDIO -> exoPlayer.updateLiveVolume(volume)
            null -> Unit
        }
    }

    fun seekPreview(positionMs: Long) {
        if (!_state.value.isPreview || !_state.value.isPlayingOrFading) return
        val clamped = positionMs.coerceAtLeast(0L)
        _state.update { it.copy(positionMs = clamped) }
        when (activeFormat) {
            AudioFormat.MIDI -> midiPlayer.seekLive(clamped)
            AudioFormat.AUDIO -> exoPlayer.seekLive(clamped)
            null -> Unit
        }
    }

    fun fadeOutCurrent() {
        val current = _state.value
        if (current.status != PlaybackStatus.PLAYING) return

        val trackId = current.activeTrackId
        _state.update {
            it.copy(
                status = PlaybackStatus.FADING_OUT,
                fadeProgress = 0f,
                lastLiveTrackId = if (!it.isPreview && trackId != null) trackId else it.lastLiveTrackId
            )
        }

        if (!current.isPreview) {
            updateForegroundService(
                current.title,
                current.phaseLabel,
                current.positionMs,
                current.durationMs,
                true
            )
            lastMediaNotificationUpdateMs = clockMs()
        }

        if (!current.isPreview && trackId != null) {
            scope.launch {
                markLiveTrackPlayedOnce(trackId)
                mixerController.onTrackFade(trackId, current.fadeOutMs, activeMixerJson)
            }
        }

        when (activeFormat) {
            AudioFormat.MIDI -> midiPlayer.fadeOut(current.fadeOutMs)
            AudioFormat.AUDIO -> exoPlayer.fadeOut(current.fadeOutMs)
            null -> finalizeStop(markLivePlayed = !current.isPreview)
        }
    }

    fun stopImmediateCurrent() {
        val current = _state.value
        if (!current.isBusy) return

        val trackId = current.activeTrackId
        val mixerJsonSnapshot = activeMixerJson
        if (!current.isPreview && trackId != null) {
            _state.update { it.copy(lastLiveTrackId = trackId) }
            scope.launch {
                markLiveTrackPlayedOnce(trackId)
                notifyMixerTrackStopOnce(trackId, mixerJsonSnapshot)
            }
        }

        if (current.status == PlaybackStatus.LOADING) {
            stopInternal(markLivePlayed = !current.isPreview)
            return
        }

        when (activeFormat) {
            AudioFormat.MIDI -> midiPlayer.stopWithAntiClick()
            AudioFormat.AUDIO -> exoPlayer.stopWithAntiClick()
            null -> finalizeStop(markLivePlayed = !current.isPreview)
        }
    }

    fun stopPreviewIfActive() {
        if (_state.value.isPreview) {
            stopInternal(markLivePlayed = false)
        }
    }

    fun resetLastLiveTrack() {
        _state.update { it.copy(lastLiveTrackId = null) }
    }

    fun clearError() {
        if (_state.value.status == PlaybackStatus.ERROR) {
            _state.value = PlaybackState(lastLiveTrackId = _state.value.lastLiveTrackId)
        }
    }

    private fun applyCurrentPreferredOutputDevice() {
        val router = audioOutputRouter ?: return
        val preferred = router.resolvePreferredAudioDevice()
        midiPlayer.setPreferredOutputDevice(preferred)
        exoPlayer.setPreferredOutputDevice(preferred)
    }

    private suspend fun startEngine(
        uri: String,
        format: AudioFormat,
        settings: TrackSettings,
        isPreview: Boolean,
        trackIdForCompletion: Long?
    ) {
        val commonOnProgress: (Long, Long, Boolean, Float) -> Unit = { pos, dur, fading, fadeProg ->
            var updatedSnapshot: PlaybackState? = null
            _state.update { current ->
                if (!current.isBusy) return@update current
                val next = current.copy(
                    status = if (fading) PlaybackStatus.FADING_OUT else PlaybackStatus.PLAYING,
                    positionMs = pos,
                    durationMs = if (dur > 0L) dur else current.durationMs,
                    fadeProgress = fadeProg
                )
                updatedSnapshot = next
                next
            }
            val snap = updatedSnapshot
            if (snap != null && !snap.isPreview) {
                val now = clockMs()
                val durationChanged = snap.durationMs > 0L && snap.durationMs != lastNotifiedDurationMs
                if (durationChanged || now - lastMediaNotificationUpdateMs >= MEDIA_SESSION_UPDATE_INTERVAL_MS) {
                    lastMediaNotificationUpdateMs = now
                    lastNotifiedDurationMs = snap.durationMs
                    updateForegroundService(
                        snap.title,
                        snap.phaseLabel,
                        snap.positionMs,
                        snap.durationMs,
                        fading
                    )
                }
            }
        }

        val commonOnCompleted: () -> Unit = {
            val mixerJsonSnapshot = activeMixerJson
            scope.launch {
                if (!isPreview && trackIdForCompletion != null) {
                    notifyMixerTrackStopOnce(trackIdForCompletion, mixerJsonSnapshot)
                    markLiveTrackPlayedOnce(trackIdForCompletion)
                }
                finalizeStop(markLivePlayed = false)
            }
        }

        val commonOnError: (String) -> Unit = { msg ->
            val mixerJsonSnapshot = activeMixerJson
            val preservedLastLiveId = _state.value.lastLiveTrackId
            scope.launch {
                if (!isPreview && trackIdForCompletion != null) {
                    notifyMixerTrackStopOnce(trackIdForCompletion, mixerJsonSnapshot)
                }
                stopForegroundService()
                abandonAudioFocus()
                activeFormat = null
                activeMixerJson = null
                _state.value = PlaybackState(
                    status = PlaybackStatus.ERROR,
                    lastLiveTrackId = preservedLastLiveId,
                    errorMessage = msg,
                    isPreview = isPreview
                )
            }
        }

        when (format) {
            AudioFormat.MIDI -> {
                val sfPath = try {
                    repository.getActiveSoundFontPath()
                } catch (e: Exception) {
                    commonOnError("Banco suoni SoundFont non disponibile: ${e.message}")
                    return
                }
                _state.update { it.copy(status = PlaybackStatus.PLAYING) }
                midiPlayer.start(
                    uriString = uri,
                    soundFontPath = sfPath,
                    startOffsetMs = settings.startOffsetMs,
                    transposeSemitones = settings.transposeSemitones,
                    volume = settings.volume,
                    listener = object : MidiAudioPlayer.Listener {
                        override fun onProgress(positionMs: Long, durationMs: Long, isFading: Boolean, fadeProgress: Float) =
                            commonOnProgress(positionMs, durationMs, isFading, fadeProgress)
                        override fun onCompleted() = commonOnCompleted()
                        override fun onError(message: String) = commonOnError(message)
                    }
                )
            }

            AudioFormat.AUDIO -> {
                _state.update { it.copy(status = PlaybackStatus.PLAYING) }
                exoPlayer.start(
                    uriString = uri,
                    startOffsetMs = settings.startOffsetMs,
                    transposeSemitones = settings.transposeSemitones,
                    volume = settings.volume,
                    listener = object : ExoAudioPlayer.Listener {
                        override fun onProgress(positionMs: Long, durationMs: Long, isFading: Boolean, fadeProgress: Float) =
                            commonOnProgress(positionMs, durationMs, isFading, fadeProgress)
                        override fun onCompleted() = commonOnCompleted()
                        override fun onError(message: String) = commonOnError(message)
                    }
                )
            }
        }
    }

    private suspend fun markLiveTrackPlayedOnce(trackId: Long) {
        if (trackPlayedMarked) return
        trackPlayedMarked = true
        repository.markTrackPlayed(trackId, true)
    }

    private suspend fun notifyMixerTrackStopOnce(trackId: Long, mixerJson: String?) {
        if (trackStopNotified) return
        trackStopNotified = true
        mixerController.onTrackStop(trackId, mixerJson)
    }

    private fun stopInternal(markLivePlayed: Boolean) {
        startJob?.cancel()
        startJob = null
        midiPlayer.stopImmediateSync()
        exoPlayer.stopImmediate()
        finalizeStop(markLivePlayed)
    }

    private fun finalizeStop(markLivePlayed: Boolean) {
        val prev = _state.value
        val updatedLastLiveId = if (!prev.isPreview && prev.activeTrackId != null) {
            prev.activeTrackId
        } else {
            prev.lastLiveTrackId
        }
        if (markLivePlayed && !prev.isPreview && prev.activeTrackId != null) {
            val trackId = prev.activeTrackId
            scope.launch {
                markLiveTrackPlayedOnce(trackId)
            }
        }
        stopForegroundService()
        abandonAudioFocus()
        activeFormat = null
        activeMixerJson = null
        lastMediaNotificationUpdateMs = 0L
        lastNotifiedDurationMs = 0L
        _state.value = PlaybackState(lastLiveTrackId = updatedLastLiveId)
    }

    private fun requestAudioFocus() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { /* In chiesa non interrompiamo automaticamente la base */ }
            .build()
        audioFocusRequest = req
        audioManager.requestAudioFocus(req)
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
    }

    companion object {
        private const val MEDIA_SESSION_UPDATE_INTERVAL_MS = 1000L
    }
}
