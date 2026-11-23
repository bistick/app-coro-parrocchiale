package it.parrocchia.coro.audio

import kotlin.math.pow
import kotlin.math.tanh

object FadeCurve {
    const val DRUM_CHANNEL = 9

    /**
     * Curva di sfumatura percepita naturale (quadratica in ampiezza):
     * progress = 0.0 -> gain = 1.0
     * progress = 1.0 -> gain = 0.0
     */
    fun fadeOutGain(progress: Float): Float {
        val clamped = progress.coerceIn(0f, 1f)
        val remaining = 1f - clamped
        return remaining * remaining
    }

    /**
     * Trasposizione sicura per note MIDI:
     * preserva sempre intatto il canale 10 (indice 0-based = 9, percussioni General MIDI).
     */
    fun transposeMidiNote(channel: Int, key: Int, semitones: Int): Int {
        if (channel == DRUM_CHANNEL) return key.coerceIn(0, 127)
        return (key + semitones).coerceIn(0, 127)
    }

    /**
     * Fattore di pitch per player audio (MP3/WAV) in base ai semitoni.
     */
    fun semitonesToPitchFactor(semitones: Int): Float {
        val clamped = semitones.coerceIn(-12, 12)
        return 2.0.pow(clamped / 12.0).toFloat()
    }

    /**
     * Limiter morbido per evitare clipping digitale sull'impianto della chiesa
     * quando il volume relativo del brano supera il 100%.
     */
    fun softLimit(sample: Float): Float {
        if (sample in -0.92f..0.92f) return sample
        return tanh(sample.toDouble()).toFloat()
    }
}
