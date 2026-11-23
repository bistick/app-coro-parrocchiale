package it.parrocchia.coro.domain.model

enum class AudioFormat(val shortLabel: String) {
    MIDI("MIDI"),
    AUDIO("AUDIO");

    companion object {
        private val midiExtensions = setOf("mid", "midi", "kar")
        private val audioExtensions = setOf("mp3", "wav", "m4a", "ogg", "flac", "aac")

        fun fromFileName(fileName: String): AudioFormat? {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return when {
                ext in midiExtensions -> MIDI
                ext in audioExtensions -> AUDIO
                else -> null
            }
        }

        fun stripExtension(fileName: String): String {
            val idx = fileName.lastIndexOf('.')
            return if (idx > 0) fileName.substring(0, idx) else fileName
        }

        fun fromCode(code: String): AudioFormat =
            entries.firstOrNull { it.name == code } ?: AUDIO
    }
}
