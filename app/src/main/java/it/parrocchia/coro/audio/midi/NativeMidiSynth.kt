package it.parrocchia.coro.audio.midi

/**
 * Bridge JNI verso TinySoundFont (tsf.h) + TinyMidiLoader (tml.h).
 */
object NativeMidiSynth {

    private val libraryLoaded: Boolean = try {
        System.loadLibrary("coro_midi")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    fun isAvailable(): Boolean = libraryLoaded

    fun create(soundFontPath: String, sampleRate: Int): Long {
        if (!libraryLoaded) return 0L
        return nativeCreate(soundFontPath, sampleRate)
    }

    fun loadMidi(handle: Long, midiBytes: ByteArray): Long {
        if (!libraryLoaded || handle == 0L) return -1L
        return nativeLoadMidi(handle, midiBytes)
    }

    fun seek(handle: Long, positionMs: Long) {
        if (!libraryLoaded || handle == 0L) return
        nativeSeek(handle, positionMs)
    }

    fun setTranspose(handle: Long, semitones: Int) {
        if (!libraryLoaded || handle == 0L) return
        nativeSetTranspose(handle, semitones)
    }

    fun allNotesOff(handle: Long) {
        if (!libraryLoaded || handle == 0L) return
        nativeAllNotesOff(handle)
    }

    fun render(handle: Long, outBuffer: FloatArray, frameCount: Int): Boolean {
        if (!libraryLoaded || handle == 0L) return true
        return nativeRender(handle, outBuffer, frameCount)
    }

    fun getPositionMs(handle: Long): Long {
        if (!libraryLoaded || handle == 0L) return 0L
        return nativeGetPositionMs(handle)
    }

    fun destroy(handle: Long) {
        if (!libraryLoaded || handle == 0L) return
        nativeDestroy(handle)
    }

    fun probeMidiDurationMs(midiBytes: ByteArray): Long {
        if (!libraryLoaded) return 0L
        return nativeProbeMidiDurationMs(midiBytes)
    }

    private external fun nativeCreate(soundFontPath: String, sampleRate: Int): Long
    private external fun nativeLoadMidi(handle: Long, midiBytes: ByteArray): Long
    private external fun nativeSeek(handle: Long, positionMs: Long)
    private external fun nativeSetTranspose(handle: Long, semitones: Int)
    private external fun nativeAllNotesOff(handle: Long)
    private external fun nativeRender(handle: Long, outBuffer: FloatArray, frameCount: Int): Boolean
    private external fun nativeGetPositionMs(handle: Long): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeProbeMidiDurationMs(midiBytes: ByteArray): Long
}
