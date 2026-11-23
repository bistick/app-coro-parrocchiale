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
     * Limiter morbido continuo (C¹) per evitare clipping digitale sull'impianto della chiesa
     * quando il volume relativo del brano supera il 100% o su accordi molto densi.
     * - Lineare (trasparente) entro ±0.85
     * - Raccordo iperbolico liscio senza scalini verso ±0.99 (margine anti true-peak clipping)
     */
    fun softLimit(sample: Float): Float {
        val threshold = 0.85f
        val ceiling = 0.99f
        val absSample = kotlin.math.abs(sample)
        if (absSample <= threshold) return sample
        val knee = ceiling - threshold
        val shaped = threshold + knee * tanh(((absSample - threshold) / knee).toDouble()).toFloat()
        return if (sample >= 0f) shaped else -shaped
    }
}
