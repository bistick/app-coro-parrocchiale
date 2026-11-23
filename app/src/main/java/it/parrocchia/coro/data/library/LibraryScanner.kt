package it.parrocchia.coro.data.library

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import it.parrocchia.coro.audio.midi.NativeMidiSynth
import it.parrocchia.coro.data.db.CoroDao
import it.parrocchia.coro.data.db.SongEntity
import it.parrocchia.coro.domain.model.AudioFormat
import it.parrocchia.coro.domain.model.TrackSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ScanSummary(
    val totalIndexed: Int,
    val addedCount: Int,
    val updatedCount: Int,
    val removedCount: Int
)

class LibraryScanner(
    private val context: Context,
    private val dao: CoroDao
) {
    /**
     * Indicizza i brani di prova inclusi negli asset dell'app (assets/demo_tracks/).
     */
    suspend fun ensureDemoSongsIndexed(defaultFadeOutMs: Int): List<SongEntity> = withContext(Dispatchers.IO) {
        val assetFiles = context.assets.list(DEMO_ASSET_DIR)?.sorted().orEmpty()
        val result = mutableListOf<SongEntity>()

        for (fileName in assetFiles) {
            val format = AudioFormat.fromFileName(fileName) ?: continue
            val uriString = "$ASSET_URI_PREFIX$DEMO_ASSET_DIR/$fileName"
            val existing = dao.getSongByUri(uriString)
            if (existing != null) {
                result += existing
                continue
            }

            val durationMs = probeAssetDurationMs("$DEMO_ASSET_DIR/$fileName", format)
            val cleanTitle = formatDemoTitle(fileName)
            val demoOffset = if (fileName.startsWith("01_")) 5_700L else 0L

            val entity = SongEntity(
                uri = uriString,
                title = cleanTitle,
                fileName = fileName,
                relativeFolder = "Brani di prova",
                format = format.name,
                durationMs = durationMs,
                defaultFadeOutMs = defaultFadeOutMs,
                defaultStartOffsetMs = demoOffset,
                defaultTransposeSemitones = 0,
                defaultVolume = TrackSettings.DEFAULT_VOLUME,
                isDemo = true,
                lastModifiedMs = 0L
            )
            val id = dao.insertSong(entity)
            if (id > 0L) {
                result += entity.copy(id = id)
            } else {
                dao.getSongByUri(uriString)?.let { result += it }
            }
        }
        result
    }

    /**
     * Scansiona ricorsivamente la cartella SAF selezionata dall'utente preservando
     * le impostazioni predefinite (sfumatura, attacco, tonalità, volume) dei brani esistenti.
     */
    suspend fun scanFolderTree(treeUri: Uri, defaultFadeOutMs: Int): ScanSummary = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("Cartella non accessibile. Selezionala di nuovo dalle Impostazioni.")

        val existingByUri = dao.getAllSongsOnce()
            .filter { !it.isDemo }
            .associateBy { it.uri }

        val discoveredUris = mutableSetOf<String>()
        var added = 0
        var updated = 0

        suspend fun traverse(dir: DocumentFile, relativePath: String) {
            val children = dir.listFiles()
            for (child in children) {
                val name = child.name ?: continue
                if (name.startsWith(".")) continue

                if (child.isDirectory) {
                    val nextPath = if (relativePath.isBlank()) name else "$relativePath/$name"
                    traverse(child, nextPath)
                } else if (child.isFile) {
                    val format = AudioFormat.fromFileName(name) ?: continue
                    val uriStr = child.uri.toString()
                    discoveredUris += uriStr
                    val lastMod = child.lastModified()
                    val existing = existingByUri[uriStr]

                    if (existing == null) {
                        val duration = probeContentDurationMs(child.uri, format)
                        val title = AudioFormat.stripExtension(name).replace('_', ' ').trim()
                        dao.insertSong(
                            SongEntity(
                                uri = uriStr,
                                title = title.ifBlank { name },
                                fileName = name,
                                relativeFolder = relativePath,
                                format = format.name,
                                durationMs = duration,
                                defaultFadeOutMs = defaultFadeOutMs,
                                defaultStartOffsetMs = 0L,
                                defaultTransposeSemitones = 0,
                                defaultVolume = TrackSettings.DEFAULT_VOLUME,
                                isDemo = false,
                                lastModifiedMs = lastMod
                            )
                        )
                        added++
                    } else if (existing.lastModifiedMs != lastMod || existing.relativeFolder != relativePath || existing.durationMs <= 0L) {
                        val duration = if (existing.durationMs <= 0L || existing.lastModifiedMs != lastMod) {
                            probeContentDurationMs(child.uri, format)
                        } else {
                            existing.durationMs
                        }
                        dao.updateSong(
                            existing.copy(
                                fileName = name,
                                relativeFolder = relativePath,
                                format = format.name,
                                durationMs = duration,
                                lastModifiedMs = lastMod
                            )
                        )
                        updated++
                    }
                }
            }
        }

        traverse(root, "")

        val removed = existingByUri.keys.count { it !in discoveredUris }
        if (discoveredUris.isEmpty()) {
            dao.deleteAllNonDemoSongs()
        } else {
            // Room SQLite ha limite ~999 parametri IN: se la libreria è enorme rimuoviamo a blocchi
            val missingUris = existingByUri.keys.filter { it !in discoveredUris }
            if (missingUris.isNotEmpty()) {
                val activeList = discoveredUris.toList()
                if (activeList.size <= 900) {
                    dao.deleteMissingNonDemoSongs(activeList)
                } else {
                    // Fallback sicuro per librerie > 900 file
                    for (song in existingByUri.values) {
                        if (song.uri !in discoveredUris) {
                            dao.updateSong(song.copy(relativeFolder = "__REMOVED__"))
                        }
                    }
                }
            }
        }

        ScanSummary(
            totalIndexed = discoveredUris.size,
            addedCount = added,
            updatedCount = updated,
            removedCount = removed
        )
    }

    private fun probeAssetDurationMs(assetPath: String, format: AudioFormat): Long {
        return runCatching {
            when (format) {
                AudioFormat.MIDI -> {
                    val bytes = context.assets.open(assetPath).use { it.readBytes() }
                    NativeMidiSynth.probeMidiDurationMs(bytes)
                }
                AudioFormat.AUDIO -> {
                    val retriever = MediaMetadataRetriever()
                    try {
                        context.assets.openFd(assetPath).use { afd ->
                            retriever.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                        }
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    } finally {
                        retriever.release()
                    }
                }
            }
        }.getOrDefault(0L)
    }

    private fun probeContentDurationMs(uri: Uri, format: AudioFormat): Long {
        return runCatching {
            when (format) {
                AudioFormat.MIDI -> {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return 0L
                    NativeMidiSynth.probeMidiDurationMs(bytes)
                }
                AudioFormat.AUDIO -> {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, uri)
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    } finally {
                        retriever.release()
                    }
                }
            }
        }.getOrDefault(0L)
    }

    private fun formatDemoTitle(fileName: String): String {
        val noExt = AudioFormat.stripExtension(fileName)
        return noExt.replaceFirst(Regex("^\\d+_"), "").replace('_', ' ').trim()
    }

    companion object {
        const val DEMO_ASSET_DIR = "demo_tracks"
        const val ASSET_URI_PREFIX = "asset:///"
    }
}
