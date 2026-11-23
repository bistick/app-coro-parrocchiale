package it.parrocchia.coro.data.library

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class SoundFontManager(private val context: Context) {

    private val sfDir: File
        get() = File(context.filesDir, "soundfonts").apply { mkdirs() }

    /**
     * Garantisce che il SoundFont General MIDI integrato sia estratto su disco locale
     * e restituisce il percorso assoluto leggibile dal sintetizzatore nativo.
     */
    suspend fun ensureBundledSoundFontPath(): String = withContext(Dispatchers.IO) {
        val target = File(sfDir, BUNDLED_SF2_FILENAME)
        if (!target.exists() || target.length() < 1024L) {
            context.assets.open("soundfonts/$BUNDLED_SF2_FILENAME").use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
        }
        target.absolutePath
    }

    /**
     * Importa un file .sf2 scelto dall'utente via SAF nella memoria privata dell'app,
     * verificando l'intestazione RIFF/sfbk per evitare file corrotti o non validi.
     */
    suspend fun importCustomSoundFont(uri: Uri): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        runCatching {
            val displayName = resolveDisplayName(uri) ?: "custom_soundfont.sf2"
            val tempFile = File(sfDir, "custom_importing.sf2")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: error("Impossibile aprire il file SoundFont selezionato")

            // Verifica header SoundFont 2: bytes 0..3 = "RIFF", bytes 8..11 = "sfbk"
            val header = ByteArray(12)
            val readCount = tempFile.inputStream().use { it.read(header) }
            val isRiff = readCount == 12 &&
                header[0] == 'R'.code.toByte() &&
                header[1] == 'I'.code.toByte() &&
                header[2] == 'F'.code.toByte() &&
                header[3] == 'F'.code.toByte()
            val isSfbk = readCount == 12 &&
                header[8] == 's'.code.toByte() &&
                header[9] == 'f'.code.toByte() &&
                header[10] == 'b'.code.toByte() &&
                header[11] == 'k'.code.toByte()

            if (!isRiff || !isSfbk) {
                tempFile.delete()
                error("Il file selezionato non è un SoundFont (.sf2) valido")
            }

            val finalFile = File(sfDir, CUSTOM_SF2_FILENAME)
            if (finalFile.exists()) finalFile.delete()
            check(tempFile.renameTo(finalFile)) { "Errore nel salvataggio del SoundFont" }

            finalFile.absolutePath to displayName
        }
    }

    suspend fun clearCustomSoundFont() = withContext(Dispatchers.IO) {
        val customFile = File(sfDir, CUSTOM_SF2_FILENAME)
        if (customFile.exists()) {
            customFile.delete()
        }
    }

    suspend fun resolveActiveSoundFontPath(customLocalPath: String?): String {
        if (!customLocalPath.isNullOrBlank()) {
            val file = File(customLocalPath)
            if (file.exists() && file.length() > 1024L) {
                return file.absolutePath
            }
        }
        return ensureBundledSoundFontPath()
    }

    private fun resolveDisplayName(uri: Uri): String? {
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }

    companion object {
        const val BUNDLED_SF2_FILENAME = "TimGM6mb.sf2"
        private const val CUSTOM_SF2_FILENAME = "custom_active.sf2"
    }
}
