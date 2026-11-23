package it.parrocchia.coro.audio

import it.parrocchia.coro.domain.model.AudioFormat

enum class PlaybackStatus {
    IDLE,
    LOADING,
    PLAYING,
    FADING_OUT,
    ERROR
}

data class PlaybackState(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val activeTrackId: Long? = null,
    val songId: Long? = null,
    val title: String = "",
    val phaseLabel: String = "",
    val format: AudioFormat? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val fadeOutMs: Int = 3000,
    val fadeProgress: Float = 0f,
    val isPreview: Boolean = false,
    val errorMessage: String? = null
) {
    val isBusy: Boolean
        get() = status == PlaybackStatus.LOADING ||
            status == PlaybackStatus.PLAYING ||
            status == PlaybackStatus.FADING_OUT

    val isPlayingOrFading: Boolean
        get() = status == PlaybackStatus.PLAYING || status == PlaybackStatus.FADING_OUT

    val progressFraction: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
}
