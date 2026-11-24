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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Controller unificato per l'esecuzione live in chiesa e per l'anteprima in programmazione.
 * Garantisce mutua esclusione in modalità live (impossibile avviare un secondo brano mentre uno suona).
 */
class PlaybackController(
    private val context: Context,
    private val repository: CoroRepository,
    private val mixerController: MixerController,
    private val midiPlayer: MidiPlaybackEngine = MidiAudioPlayer(context),
    private val exoPlayer: AudioPlaybackEngine = ExoAudioPlayer(context),
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val startForegroundService: (title: String, phaseLabel: String) -> Unit = { title, phase ->
        PlaybackForegroundService.start(context, title, phase)
    },
    private val stopForegroundService: () -> Unit = {
        PlaybackForegroundService.stop(context)
    }
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var audioFocusRequest: AudioFocusRequest? = null
    private var activeFormat: AudioFormat? = null
    private var activeMixerJson: String? = null

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

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

        scope.launch {
            requestAudioFocus()
            startForegroundService(song.title, phaseLabel)
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
        stopInternal(markLivePlayed = false)

        val format = song.audioFormat
        activeFormat = format
        activeMixerJson = null

        _state.value = PlaybackState(
            status = PlaybackStatus.LOADING,
            activeTrackId = null,
            songId = song.id,
            title = song.title,
            phaseLabel = "Anteprima",
            format = format,
            positionMs = settings.startOffsetMs,
            durationMs = song.durationMs,
            fadeOutMs = settings.fadeOutMs,
            isPreview = true
        )

        scope.launch {
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

        _state.update { it.copy(status = PlaybackStatus.FADING_OUT, fadeProgress = 0f) }

        val trackId = current.activeTrackId
        if (!current.isPreview && trackId != null) {
            scope.launch {
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
        if (!current.isPreview && trackId != null) {
            scope.launch {
                mixerController.onTrackStop(trackId, activeMixerJson)
            }
        }

        if (current.status == PlaybackStatus.LOADING) {
            stopInternal(markLivePlayed = false)
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

    fun clearError() {
        if (_state.value.status == PlaybackStatus.ERROR) {
            _state.value = PlaybackState()
        }
    }

    private suspend fun startEngine(
        uri: String,
        format: AudioFormat,
        settings: TrackSettings,
        isPreview: Boolean,
        trackIdForCompletion: Long?
    ) {
        val commonOnProgress: (Long, Long, Boolean, Float) -> Unit = { pos, dur, fading, fadeProg ->
            _state.update { current ->
                if (!current.isBusy) return@update current
                current.copy(
                    status = if (fading) PlaybackStatus.FADING_OUT else PlaybackStatus.PLAYING,
                    positionMs = pos,
                    durationMs = if (dur > 0L) dur else current.durationMs,
                    fadeProgress = fadeProg
                )
            }
        }

        val commonOnCompleted: () -> Unit = {
            scope.launch {
                if (!isPreview && trackIdForCompletion != null) {
                    mixerController.onTrackStop(trackIdForCompletion, activeMixerJson)
                    repository.markTrackPlayed(trackIdForCompletion, true)
                }
                finalizeStop(markLivePlayed = false)
            }
        }

        val commonOnError: (String) -> Unit = { msg ->
            scope.launch {
                stopForegroundService()
                abandonAudioFocus()
                activeFormat = null
                _state.value = PlaybackState(
                    status = PlaybackStatus.ERROR,
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

    private fun stopInternal(markLivePlayed: Boolean) {
        midiPlayer.stopImmediateSync()
        exoPlayer.stopImmediate()
        finalizeStop(markLivePlayed)
    }

    private fun finalizeStop(markLivePlayed: Boolean) {
        val prev = _state.value
        if (markLivePlayed && !prev.isPreview && prev.activeTrackId != null) {
            scope.launch {
                repository.markTrackPlayed(prev.activeTrackId, true)
            }
        }
        stopForegroundService()
        abandonAudioFocus()
        activeFormat = null
        activeMixerJson = null
        _state.value = PlaybackState()
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
}
