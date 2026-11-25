package it.parrocchia.coro.audio.midi

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.Uri
import android.os.Process
import it.parrocchia.coro.audio.FadeCurve
import it.parrocchia.coro.data.library.LibraryScanner
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

interface MidiPlaybackEngine {
    fun start(
        uriString: String,
        soundFontPath: String,
        startOffsetMs: Long,
        transposeSemitones: Int,
        volume: Float,
        listener: MidiAudioPlayer.Listener
    )
    fun fadeOut(durationMs: Int)
    fun stopWithAntiClick()
    fun stopImmediateSync()
    fun updateLiveTranspose(semitones: Int)
    fun updateLiveVolume(volume: Float)
    fun seekLive(positionMs: Long)
    fun setPreferredOutputDevice(device: AudioDeviceInfo?) = Unit
}

/**
 * Motore di riproduzione MIDI basato su FluidSynth v2.6.2 + AudioTrack PCM Float Stereo
 * al sample rate nativo del dispositivo (es. 48 kHz senza resampling di sistema).
 * Gestisce punto di attacco esatto, trasposizione (escludendo ch. 10 percussioni),
 * volume per brano, sfumatura fluida, limiter C¹ anti-clipping e micro-rampa anti-click.
 */
class MidiAudioPlayer(private val context: Context) : MidiPlaybackEngine {

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

    @Volatile
    private var activeSampleRate: Int = DEFAULT_SAMPLE_RATE

    private val pendingTranspose = AtomicInteger(Int.MIN_VALUE)
    private val pendingSeekMs = AtomicLong(-1L)

    @Volatile
    private var preferredOutputDevice: AudioDeviceInfo? = null

    @Volatile
    private var activeAudioTrack: AudioTrack? = null

    override fun start(
        uriString: String,
        soundFontPath: String,
        startOffsetMs: Long,
        transposeSemitones: Int,
        volume: Float,
        listener: Listener
    ) {
        stopImmediateSync()

        if (!NativeMidiSynth.isAvailable()) {
            listener.onError("Sintetizzatore MIDI FluidSynth non disponibile su questo dispositivo.")
            return
        }

        val midiBytes = try {
            loadBytes(uriString)
        } catch (e: Exception) {
            listener.onError("Impossibile leggere il file MIDI: ${e.message ?: "errore I/O"}")
            return
        }

        val sampleRate = resolveNativeSampleRate()
        activeSampleRate = sampleRate

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
                sampleRate = sampleRate,
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

    override fun fadeOut(durationMs: Int) {
        if (isFadingOut.get()) return
        val safeMs = durationMs.coerceAtLeast(100)
        fadeElapsedSamples.set(0L)
        fadeDurationSamples.set((safeMs.toLong() * activeSampleRate) / 1000L)
        isFadingOut.set(true)
    }

    /**
     * Stop immediato con micro-rampa anti-click (18 ms) per non produrre "pop"
     * sull'impianto audio della chiesa.
     */
    override fun stopWithAntiClick() {
        val microSamples = (ANTI_CLICK_STOP_MS * activeSampleRate) / 1000L
        fadeElapsedSamples.set(0L)
        fadeDurationSamples.set(microSamples)
        isFadingOut.set(true)
    }

    override fun stopImmediateSync() {
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

    override fun updateLiveTranspose(semitones: Int) {
        pendingTranspose.set(semitones.coerceIn(-12, 12))
    }

    override fun updateLiveVolume(volume: Float) {
        targetVolume = volume.coerceIn(0.05f, 1.8f)
    }

    override fun seekLive(positionMs: Long) {
        pendingSeekMs.set(positionMs.coerceAtLeast(0L))
    }

    override fun setPreferredOutputDevice(device: AudioDeviceInfo?) {
        preferredOutputDevice = device
        runCatching { activeAudioTrack?.setPreferredDevice(device) }
    }

    private fun runRenderLoop(
        midiBytes: ByteArray,
        soundFontPath: String,
        sampleRate: Int,
        startOffsetMs: Long,
        initialTranspose: Int,
        listener: Listener
    ) {
        val handle = NativeMidiSynth.create(soundFontPath, sampleRate)
        if (handle == 0L) {
            listener.onError("Impossibile caricare il banco suoni SoundFont (.sf2/.sf3).")
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
                sampleRate,
                AndroidAudioFormat.CHANNEL_OUT_STEREO,
                AndroidAudioFormat.ENCODING_PCM_FLOAT
            )
            // Buffer generoso (~85ms) per azzerare qualsiasi underrun/crackle in esecuzione live
            val bufferBytes = max(minBufBytes * 3, BLOCK_FRAMES * 2 * 4 * 4)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AndroidAudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AndroidAudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(AndroidAudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
                .build()

            audioTrack = track
            activeAudioTrack = track
            runCatching { track.setPreferredDevice(preferredOutputDevice) }
            track.play()

            val pcm = FloatArray(BLOCK_FRAMES * 2)
            val fadeInSamples = if (startOffsetMs > 0L) (ANTI_CLICK_FADE_IN_MS * sampleRate) / 1000L else 0L
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
            activeAudioTrack = null
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

    private fun resolveNativeSampleRate(): Int {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val nativeRate = audioManager
            ?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toIntOrNull()
        return if (nativeRate != null && nativeRate in 44100..96000) {
            nativeRate
        } else {
            DEFAULT_SAMPLE_RATE
        }
    }

    companion object {
        private const val DEFAULT_SAMPLE_RATE = 48000
        private const val BLOCK_FRAMES = 1024
        private const val ANTI_CLICK_FADE_IN_MS = 12L
        private const val ANTI_CLICK_STOP_MS = 18L
        private const val PROGRESS_INTERVAL_NS = 60_000_000L // ~16 fps per UI fluida
    }
}
