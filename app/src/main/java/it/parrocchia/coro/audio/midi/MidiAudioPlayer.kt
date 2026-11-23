package it.parrocchia.coro.audio.midi

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Process
import it.parrocchia.coro.audio.FadeCurve
import it.parrocchia.coro.data.library.LibraryScanner
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * Motore di riproduzione MIDI basato su TinySoundFont + AudioTrack PCM Float Stereo.
 * Gestisce punto di attacco esatto, trasposizione (escludendo ch. 10 percussioni),
 * volume per brano, sfumatura fluida e micro-rampa anti-click sullo stop immediato.
 */
class MidiAudioPlayer(private val context: Context) {

    interface Listener {
        fun onProgress(positionMs: Long, durationMs: Long, isFading: Boolean, fadeProgress: Float)
        fun onCompleted()
        fun onError(message: String)
    }

    private val lock = Any()
    private var renderThread: Thread? = null

    private val stopRequested = AtomicBoolean(false)
    private val fadeDurationSamples = AtomicLong(0L)
    private val fadeElapsedSamples = AtomicLong(0L)
    private val isFadingOut = AtomicBoolean(false)

    @Volatile
    private var targetVolume: Float = 1.0f

    private val pendingTranspose = AtomicInteger(Int.MIN_VALUE)
    private val pendingSeekMs = AtomicLong(-1L)

    fun start(
        uriString: String,
        soundFontPath: String,
        startOffsetMs: Long,
        transposeSemitones: Int,
        volume: Float,
        listener: Listener
    ) {
        stopImmediateSync()

        if (!NativeMidiSynth.isAvailable()) {
            listener.onError("Sintetizzatore MIDI nativo non disponibile su questo dispositivo.")
            return
        }

        val midiBytes = try {
            loadBytes(uriString)
        } catch (e: Exception) {
            listener.onError("Impossibile leggere il file MIDI: ${e.message ?: "errore I/O"}")
            return
        }

        stopRequested.set(false)
        isFadingOut.set(false)
        fadeDurationSamples.set(0L)
        fadeElapsedSamples.set(0L)
        targetVolume = volume.coerceIn(0.05f, 1.8f)
        pendingTranspose.set(Int.MIN_VALUE)
        pendingSeekMs.set(-1L)

        val thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            runRenderLoop(
                midiBytes = midiBytes,
                soundFontPath = soundFontPath,
                startOffsetMs = startOffsetMs.coerceAtLeast(0L),
                initialTranspose = transposeSemitones,
                listener = listener
            )
        }, "CoroMidiRenderThread")

        synchronized(lock) {
            renderThread = thread
        }
        thread.start()
    }

    fun fadeOut(durationMs: Int) {
        if (isFadingOut.get()) return
        val safeMs = durationMs.coerceAtLeast(100)
        fadeElapsedSamples.set(0L)
        fadeDurationSamples.set((safeMs.toLong() * SAMPLE_RATE) / 1000L)
        isFadingOut.set(true)
    }

    /**
     * Stop immediato con micro-rampa anti-click (15 ms) per non produrre "pop"
     * sull'impianto audio della chiesa.
     */
    fun stopWithAntiClick() {
        val microSamples = (ANTI_CLICK_STOP_MS * SAMPLE_RATE) / 1000L
        fadeElapsedSamples.set(0L)
        fadeDurationSamples.set(microSamples)
        isFadingOut.set(true)
    }

    fun stopImmediateSync() {
        stopRequested.set(true)
        val thread = synchronized(lock) {
            val t = renderThread
            renderThread = null
            t
        }
        if (thread != null && thread !== Thread.currentThread()) {
            try {
                thread.join(400L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    fun updateLiveTranspose(semitones: Int) {
        pendingTranspose.set(semitones.coerceIn(-12, 12))
    }

    fun updateLiveVolume(volume: Float) {
        targetVolume = volume.coerceIn(0.05f, 1.8f)
    }

    fun seekLive(positionMs: Long) {
        pendingSeekMs.set(positionMs.coerceAtLeast(0L))
    }

    private fun runRenderLoop(
        midiBytes: ByteArray,
        soundFontPath: String,
        startOffsetMs: Long,
        initialTranspose: Int,
        listener: Listener
    ) {
        val handle = NativeMidiSynth.create(soundFontPath, SAMPLE_RATE)
        if (handle == 0L) {
            listener.onError("Impossibile caricare il banco suoni SoundFont (.sf2).")
            return
        }

        var audioTrack: AudioTrack? = null
        try {
            val durationMs = NativeMidiSynth.loadMidi(handle, midiBytes)
            if (durationMs < 0L) {
                listener.onError("File MIDI non valido o danneggiato.")
                return
            }

            NativeMidiSynth.setTranspose(handle, initialTranspose)
            if (startOffsetMs > 0L) {
                NativeMidiSynth.seek(handle, startOffsetMs)
            }

            val minBufBytes = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AndroidAudioFormat.CHANNEL_OUT_STEREO,
                AndroidAudioFormat.ENCODING_PCM_FLOAT
            )
            val bufferBytes = max(minBufBytes * 2, BLOCK_FRAMES * 2 * 4 * 4)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AndroidAudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AndroidAudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(AndroidAudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack = track
            track.play()

            val pcm = FloatArray(BLOCK_FRAMES * 2)
            val fadeInSamples = if (startOffsetMs > 0L) (ANTI_CLICK_FADE_IN_MS * SAMPLE_RATE) / 1000L else 0L
            var renderedFrames = 0L
            var smoothedGain = targetVolume
            var lastProgressEmitNs = 0L

            while (!stopRequested.get()) {
                val newTranspose = pendingTranspose.getAndSet(Int.MIN_VALUE)
                if (newTranspose != Int.MIN_VALUE) {
                    NativeMidiSynth.setTranspose(handle, newTranspose)
                }

                val seekTarget = pendingSeekMs.getAndSet(-1L)
                if (seekTarget >= 0L) {
                    NativeMidiSynth.seek(handle, seekTarget)
                }

                val finishedMidi = NativeMidiSynth.render(handle, pcm, BLOCK_FRAMES)

                val fading = isFadingOut.get()
                val totalFadeSamples = fadeDurationSamples.get()
                var fadeFinished = false
                var currentFadeProgress = 0f

                for (frame in 0 until BLOCK_FRAMES) {
                    // Smoothing del volume per evitare scatti durante la regolazione in anteprima
                    smoothedGain += (targetVolume - smoothedGain) * 0.004f
                    var gain = smoothedGain

                    // Micro fade-in anti-click su attacco intermedio
                    if (fadeInSamples > 0L && renderedFrames < fadeInSamples) {
                        gain *= (renderedFrames.toFloat() / fadeInSamples.toFloat())
                    }

                    // Curva di sfumatura / anti-click stop
                    if (fading && totalFadeSamples > 0L) {
                        val elapsed = fadeElapsedSamples.incrementAndGet()
                        val progress = (elapsed.toFloat() / totalFadeSamples.toFloat()).coerceIn(0f, 1f)
                        currentFadeProgress = progress
                        gain *= FadeCurve.fadeOutGain(progress)
                        if (elapsed >= totalFadeSamples) {
                            fadeFinished = true
                        }
                    }

                    val idx = frame * 2
                    pcm[idx] = FadeCurve.softLimit(pcm[idx] * gain)
                    pcm[idx + 1] = FadeCurve.softLimit(pcm[idx + 1] * gain)
                    renderedFrames++
                }

                val written = track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                if (written < 0) {
                    listener.onError("Errore uscita audio (codice $written).")
                    return
                }

                val nowNs = System.nanoTime()
                if (nowNs - lastProgressEmitNs >= PROGRESS_INTERVAL_NS) {
                    lastProgressEmitNs = nowNs
                    val posMs = NativeMidiSynth.getPositionMs(handle)
                    listener.onProgress(posMs, durationMs, fading, currentFadeProgress)
                }

                if (fadeFinished) {
                    NativeMidiSynth.allNotesOff(handle)
                    break
                }

                if (finishedMidi) {
                    break
                }
            }

            if (!stopRequested.get()) {
                listener.onCompleted()
            }
        } catch (e: Exception) {
            if (!stopRequested.get()) {
                listener.onError("Errore riproduzione MIDI: ${e.message ?: "sconosciuto"}")
            }
        } finally {
            try {
                audioTrack?.pause()
                audioTrack?.flush()
                audioTrack?.stop()
                audioTrack?.release()
            } catch (_: Exception) {
                // ignore release errors
            }
            NativeMidiSynth.destroy(handle)
        }
    }

    private fun loadBytes(uriString: String): ByteArray {
        return if (uriString.startsWith(LibraryScanner.ASSET_URI_PREFIX)) {
            val assetPath = uriString.removePrefix(LibraryScanner.ASSET_URI_PREFIX)
            context.assets.open(assetPath).use { it.readBytes() }
        } else {
            context.contentResolver.openInputStream(Uri.parse(uriString))?.use { it.readBytes() }
                ?: error("Stream nullo per $uriString")
        }
    }

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val BLOCK_FRAMES = 512
        private const val ANTI_CLICK_FADE_IN_MS = 12L
        private const val ANTI_CLICK_STOP_MS = 18L
        private const val PROGRESS_INTERVAL_NS = 60_000_000L // ~16 fps per UI fluida
    }
}
