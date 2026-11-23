package it.parrocchia.coro.domain.model

import java.util.Locale
import kotlin.math.roundToInt

data class TrackSettings(
    val fadeOutMs: Int = DEFAULT_FADE_OUT_MS,
    val startOffsetMs: Long = 0L,
    val transposeSemitones: Int = 0,
    val volume: Float = DEFAULT_VOLUME
) {
    fun normalized(maxDurationMs: Long = Long.MAX_VALUE): TrackSettings {
        val clampedFade = fadeOutMs.coerceIn(MIN_FADE_OUT_MS, MAX_FADE_OUT_MS)
        val safeMaxStart = if (maxDurationMs > 2000L) maxDurationMs - 1000L else maxDurationMs.coerceAtLeast(0L)
        val clampedStart = startOffsetMs.coerceIn(0L, safeMaxStart)
        val clampedTranspose = transposeSemitones.coerceIn(MIN_TRANSPOSE, MAX_TRANSPOSE)
        val clampedVolume = volume.coerceIn(MIN_VOLUME, MAX_VOLUME)
        return copy(
            fadeOutMs = clampedFade,
            startOffsetMs = clampedStart,
            transposeSemitones = clampedTranspose,
            volume = clampedVolume
        )
    }

    companion object {
        const val MIN_FADE_OUT_MS = 500
        const val MAX_FADE_OUT_MS = 15_000
        const val DEFAULT_FADE_OUT_MS = 3_000

        const val MIN_TRANSPOSE = -12
        const val MAX_TRANSPOSE = 12

        const val MIN_VOLUME = 0.2f
        const val MAX_VOLUME = 1.5f
        const val DEFAULT_VOLUME = 1.0f

        fun formatTimeMs(ms: Long): String {
            val totalSec = (ms.coerceAtLeast(0L) / 1000L).toInt()
            val minutes = totalSec / 60
            val seconds = totalSec % 60
            return String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
        }

        fun formatTranspose(semitones: Int): String = when {
            semitones > 0 -> "+$semitones st"
            semitones < 0 -> "$semitones st"
            else -> "Orig."
        }

        fun formatFadeSec(ms: Int): String {
            val sec = ms / 1000.0
            return if (ms % 1000 == 0) "${ms / 1000}s" else String.format(Locale.ITALIAN, "%.1fs", sec)
        }

        fun formatVolumePercent(volume: Float): String = "${(volume * 100f).roundToInt()}%"
    }
}
