package it.parrocchia.coro.audio.media

import android.content.Context
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import it.parrocchia.coro.audio.FadeCurve
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Motore di riproduzione per file audio (MP3, WAV, M4A, OGG, FLAC) tramite Media3 ExoPlayer.
 * Supporta punto di attacco, trasposizione pitch (senza cambiare velocità), volume per brano,
 * sfumatura fluida e micro-rampa anti-click allo stop immediato.
 */
class ExoAudioPlayer(private val context: Context) {

    interface Listener {
        fun onProgress(positionMs: Long, durationMs: Long, isFading: Boolean, fadeProgress: Float)
        fun onCompleted()
        fun onError(message: String)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var exoPlayer: ExoPlayer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var progressJob: Job? = null
    private var fadeJob: Job? = null

    private var baseVolume: Float = 1.0f
    private var isFading: Boolean = false
    private var currentFadeProgress: Float = 0f
    private var activeListener: Listener? = null

    fun start(
        uriString: String,
        startOffsetMs: Long,
        transposeSemitones: Int,
        volume: Float,
        listener: Listener
    ) {
        scope.launch {
            stopInternal()
            activeListener = listener
            baseVolume = volume.coerceIn(0.05f, 1.5f)
            isFading = false
            currentFadeProgress = 0f

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()

            val player = ExoPlayer.Builder(context)
                .setAudioAttributes(audioAttributes, false)
                .build()

            exoPlayer = player

            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        applyVolumeAndBoost(player, baseVolume)
                    } else if (playbackState == Player.STATE_ENDED) {
                        val cb = activeListener
                        stopInternal()
                        cb?.onCompleted()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    val cb = activeListener
                    stopInternal()
                    cb?.onError("Errore riproduzione audio: ${error.localizedMessage ?: "file non leggibile"}")
                }
            })

            val mediaItem = MediaItem.fromUri(Uri.parse(uriString))
            player.setMediaItem(mediaItem)
            player.playbackParameters = PlaybackParameters(
                1.0f,
                FadeCurve.semitonesToPitchFactor(transposeSemitones)
            )
            applyVolumeAndBoost(player, baseVolume)
            player.prepare()
            if (startOffsetMs > 0L) {
                player.seekTo(startOffsetMs)
            }
            player.playWhenReady = true

            startProgressLoop(player)
        }
    }

    fun fadeOut(durationMs: Int) {
        scope.launch {
            val player = exoPlayer ?: return@launch
            if (isFading) return@launch
            runFadeOut(player, durationMs.coerceAtLeast(100))
        }
    }

    fun stopWithAntiClick() {
        scope.launch {
            val player = exoPlayer ?: return@launch
            fadeJob?.cancel()
            runFadeOut(player, ANTI_CLICK_STOP_MS)
        }
    }

    fun stopImmediate() {
        scope.launch {
            stopInternal()
        }
    }

    fun updateLiveTranspose(semitones: Int) {
        scope.launch {
            val player = exoPlayer ?: return@launch
            player.playbackParameters = PlaybackParameters(
                1.0f,
                FadeCurve.semitonesToPitchFactor(semitones)
            )
        }
    }

    fun updateLiveVolume(volume: Float) {
        scope.launch {
            baseVolume = volume.coerceIn(0.05f, 1.5f)
            val player = exoPlayer ?: return@launch
            if (!isFading) {
                applyVolumeAndBoost(player, baseVolume)
            }
        }
    }

    fun seekLive(positionMs: Long) {
        scope.launch {
            exoPlayer?.seekTo(positionMs.coerceAtLeast(0L))
        }
    }

    private fun runFadeOut(player: ExoPlayer, durationMs: Int) {
        isFading = true
        currentFadeProgress = 0f
        val startVolume = baseVolume

        fadeJob?.cancel()
        fadeJob = scope.launch {
            val startTimeNs = System.nanoTime()
            val durationNs = durationMs.toLong() * 1_000_000L

            while (isActive && exoPlayer === player) {
                val elapsedNs = System.nanoTime() - startTimeNs
                val progress = (elapsedNs.toFloat() / durationNs.toFloat()).coerceIn(0f, 1f)
                currentFadeProgress = progress
                val gain = FadeCurve.fadeOutGain(progress)
                applyVolumeAndBoost(player, startVolume * gain)

                if (progress >= 1f) {
                    val cb = activeListener
                    stopInternal()
                    cb?.onCompleted()
                    break
                }
                delay(16L)
            }
        }
    }

    private fun startProgressLoop(player: ExoPlayer) {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive && exoPlayer === player) {
                val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
                val pos = player.currentPosition.coerceAtLeast(0L)
                activeListener?.onProgress(pos, dur, isFading, currentFadeProgress)
                delay(60L)
            }
        }
    }

    private fun applyVolumeAndBoost(player: ExoPlayer, volume: Float) {
        val clamped = volume.coerceAtLeast(0f)
        if (clamped <= 1.0f) {
            player.volume = clamped
            setBoostMillibels(player, 0)
        } else {
            player.volume = 1.0f
            // Boost lineare -> dB -> millibel (es. 1.5x ~ +3.5 dB = 352 mB)
            val db = 20.0 * (ln(clamped.toDouble()) / ln(10.0))
            val millibels = (db * 100.0).roundToInt().coerceIn(0, 600)
            setBoostMillibels(player, millibels)
        }
    }

    private fun setBoostMillibels(player: ExoPlayer, millibels: Int) {
        val sessionId = player.audioSessionId
        if (sessionId == C.AUDIO_SESSION_ID_UNSET || sessionId <= 0) return

        try {
            if (millibels <= 0) {
                loudnessEnhancer?.enabled = false
            } else {
                val enhancer = loudnessEnhancer ?: LoudnessEnhancer(sessionId).also {
                    loudnessEnhancer = it
                }
                enhancer.setTargetGain(millibels)
                enhancer.enabled = true
            }
        } catch (_: Exception) {
            // Dispositivi senza supporto LoudnessEnhancer degradano silenziosamente a 1.0x
        }
    }

    private fun stopInternal() {
        fadeJob?.cancel()
        fadeJob = null
        progressJob?.cancel()
        progressJob = null
        isFading = false
        currentFadeProgress = 0f
        activeListener = null

        try {
            loudnessEnhancer?.release()
        } catch (_: Exception) {
            // ignore
        }
        loudnessEnhancer = null

        try {
            exoPlayer?.stop()
            exoPlayer?.release()
        } catch (_: Exception) {
            // ignore
        }
        exoPlayer = null
    }

    companion object {
        private const val ANTI_CLICK_STOP_MS = 22
    }
}
