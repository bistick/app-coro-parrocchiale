package it.parrocchia.coro.data.repository

import android.net.Uri
import it.parrocchia.coro.data.db.CoroDao
import it.parrocchia.coro.data.db.MassPhaseEntity
import it.parrocchia.coro.data.db.MassPlanEntity
import it.parrocchia.coro.data.db.MassPlanWithPhases
import it.parrocchia.coro.data.db.PlannedTrackEntity
import it.parrocchia.coro.data.db.SongEntity
import it.parrocchia.coro.data.library.LibraryScanner
import it.parrocchia.coro.data.library.ScanSummary
import it.parrocchia.coro.data.library.SoundFontManager
import it.parrocchia.coro.data.prefs.AppSettings
import it.parrocchia.coro.data.prefs.AppSettingsStore
import it.parrocchia.coro.domain.model.StandardPhaseType
import it.parrocchia.coro.domain.model.TrackSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class CoroRepository(
    private val dao: CoroDao,
    private val scanner: LibraryScanner,
    private val settingsStore: AppSettingsStore,
    private val soundFontManager: SoundFontManager
) {
    val allSongs: Flow<List<SongEntity>> = dao.observeAllSongs()
    val activeMassPlan: Flow<MassPlanWithPhases?> = dao.observeActiveMassPlan()
    val appSettings: Flow<AppSettings> = settingsStore.settings

    /**
     * Inizializzazione al primo avvio: garantisce che i brani di prova siano indicizzati
     * e che esista una messa attiva pronta (al primo avvio pre-compilata con i brani demo
     * per permettere test immediati su Motorola G85).
     */
    suspend fun ensureInitialized() {
        val settings = settingsStore.settings.first()
        val demoSongs = scanner.ensureDemoSongsIndexed(settings.defaultFadeOutMs)

        val existingMass = dao.getActiveMassPlanOnce()
        if (existingMass == null) {
            val planId = createFreshActiveMassPlan(defaultMassTitle())
            prepopulateSampleMassIfAvailable(planId, demoSongs)
        }
    }

    private suspend fun createFreshActiveMassPlan(title: String): Long {
        dao.deactivateAllMassPlans()
        val planId = dao.insertMassPlan(
            MassPlanEntity(
                title = title,
                dateEpochDay = LocalDate.now().toEpochDay(),
                isTemplate = false,
                isActive = true
            )
        )
        val phases = StandardPhaseType.standardPhases.map { type ->
            MassPhaseEntity(
                massPlanId = planId,
                phaseType = type.name,
                customLabel = null,
                sortOrder = type.defaultOrder,
                isEnabled = type.defaultEnabled
            )
        }
        dao.insertPhases(phases)
        return planId
    }

    private suspend fun prepopulateSampleMassIfAvailable(planId: Long, demoSongs: List<SongEntity>) {
        if (demoSongs.isEmpty()) return
        val active = dao.getActiveMassPlanOnce() ?: return
        val phaseByType = active.phases.associate { it.phase.standardType to it.phase }

        suspend fun attach(phaseType: StandardPhaseType, filePrefix: String) {
            val phase = phaseByType[phaseType] ?: return
            val song = demoSongs.firstOrNull { it.fileName.startsWith(filePrefix) } ?: return
            addSongToPhase(phase.id, song)
        }

        attach(StandardPhaseType.INGRESSO, "01_")
        attach(StandardPhaseType.GLORIA, "02_")
        attach(StandardPhaseType.SALMO, "03_")
        attach(StandardPhaseType.ALLELUIA, "04_")
        attach(StandardPhaseType.OFFERTORIO, "05_")
        attach(StandardPhaseType.SANTO, "06_")
        // Comunione con 2 brani per dimostrare l'avvio manuale multi-brano
        attach(StandardPhaseType.COMUNIONE, "07_")
        attach(StandardPhaseType.COMUNIONE, "08_")
        attach(StandardPhaseType.FINALE, "09_")
    }

    suspend fun scanUserLibraryFolder(treeUri: Uri, folderDisplayName: String?): ScanSummary {
        val settings = settingsStore.settings.first()
        settingsStore.setLibraryFolder(treeUri.toString(), folderDisplayName)
        return scanner.scanFolderTree(treeUri, settings.defaultFadeOutMs)
    }

    suspend fun rescanCurrentLibraryFolder(): Result<ScanSummary> = runCatching {
        val settings = settingsStore.settings.first()
        val uriStr = settings.libraryFolderUri
            ?: error("Nessuna cartella libreria configurata")
        scanner.scanFolderTree(Uri.parse(uriStr), settings.defaultFadeOutMs)
    }

    suspend fun reloadDemoSongs() {
        val settings = settingsStore.settings.first()
        scanner.ensureDemoSongsIndexed(settings.defaultFadeOutMs)
    }

    suspend fun updateMassTitle(planId: Long, newTitle: String) {
        val cleaned = newTitle.trim()
        if (cleaned.isNotEmpty()) {
            dao.updateMassTitle(planId, cleaned)
        }
    }

    suspend fun resetMassForNewDay(keepEnabledPhases: Boolean = true) {
        val active = dao.getActiveMassPlanOnce()
        if (active == null) {
            createFreshActiveMassPlan(defaultMassTitle())
            return
        }
        dao.clearAllTracksInPlan(active.plan.id)
        dao.updateMassTitle(active.plan.id, defaultMassTitle())
        if (!keepEnabledPhases) {
            for (phaseWithTracks in active.phases) {
                val p = phaseWithTracks.phase
                if (p.standardType == StandardPhaseType.CUSTOM) {
                    dao.deletePhase(p.id)
                } else {
                    dao.setPhaseEnabled(p.id, p.standardType.defaultEnabled)
                }
            }
        }
    }

    suspend fun togglePhaseEnabled(phaseId: Long, enabled: Boolean) {
        dao.setPhaseEnabled(phaseId, enabled)
    }

    suspend fun addCustomPhase(planId: Long, label: String): Long {
        val cleaned = label.trim()
        require(cleaned.isNotEmpty()) { "Il nome della fase non può essere vuoto" }
        val nextOrder = dao.getMaxPhaseOrder(planId) + 10
        return dao.insertPhase(
            MassPhaseEntity(
                massPlanId = planId,
                phaseType = StandardPhaseType.CUSTOM.name,
                customLabel = cleaned,
                sortOrder = nextOrder,
                isEnabled = true
            )
        )
    }

    suspend fun deleteCustomPhase(phaseId: Long) {
        dao.deletePhase(phaseId)
    }

    suspend fun addSongToPhase(phaseId: Long, song: SongEntity): Long {
        val nextOrder = dao.getMaxTrackOrderInPhase(phaseId) + 1
        return dao.insertPlannedTrack(
            PlannedTrackEntity(
                phaseId = phaseId,
                songId = song.id,
                sortOrder = nextOrder,
                fadeOutMs = song.defaultFadeOutMs,
                startOffsetMs = song.defaultStartOffsetMs,
                transposeSemitones = song.defaultTransposeSemitones,
                volume = song.defaultVolume,
                playedInSession = false
            )
        )
    }

    suspend fun removePlannedTrack(trackId: Long) {
        dao.deletePlannedTrack(trackId)
    }

    suspend fun moveTrackInPhase(phaseId: Long, trackId: Long, moveUp: Boolean) {
        val tracks = dao.getTracksInPhase(phaseId).toMutableList()
        val index = tracks.indexOfFirst { it.id == trackId }
        if (index < 0) return
        val targetIndex = if (moveUp) index - 1 else index + 1
        if (targetIndex !in tracks.indices) return

        val item = tracks.removeAt(index)
        tracks.add(targetIndex, item)
        tracks.forEachIndexed { idx, track ->
            dao.updatePlannedTrackSortOrder(track.id, idx)
        }
    }

    suspend fun saveTrackSettings(
        trackId: Long,
        songId: Long,
        settings: TrackSettings,
        maxDurationMs: Long,
        saveAsSongDefault: Boolean
    ) {
        val safe = settings.normalized(maxDurationMs)
        dao.saveTrackSettingsAtomic(
            trackId = trackId,
            songId = songId,
            fadeOutMs = safe.fadeOutMs,
            startOffsetMs = safe.startOffsetMs,
            transposeSemitones = safe.transposeSemitones,
            volume = safe.volume,
            saveAsSongDefault = saveAsSongDefault
        )
    }

    suspend fun updateSongDefaultsDirect(songId: Long, settings: TrackSettings, maxDurationMs: Long) {
        val safe = settings.normalized(maxDurationMs)
        dao.updateSongDefaults(
            songId = songId,
            fadeOutMs = safe.fadeOutMs,
            startOffsetMs = safe.startOffsetMs,
            transposeSemitones = safe.transposeSemitones,
            volume = safe.volume
        )
    }

    suspend fun markTrackPlayed(trackId: Long, played: Boolean) {
        dao.setTrackPlayedInSession(trackId, played)
    }

    suspend fun resetSessionProgress(planId: Long) {
        dao.resetSessionPlayedFlags(planId)
    }

    // --- SoundFont & Settings ---
    suspend fun getActiveSoundFontPath(): String {
        val settings = settingsStore.settings.first()
        return soundFontManager.resolveActiveSoundFontPath(settings.customSoundFontLocalPath)
    }

    suspend fun importCustomSoundFont(uri: Uri): Result<String> {
        return soundFontManager.importCustomSoundFont(uri).map { (localPath, displayName) ->
            settingsStore.setCustomSoundFont(localPath, displayName)
            displayName
        }
    }

    suspend fun resetToBundledSoundFont() {
        soundFontManager.clearCustomSoundFont()
        settingsStore.setCustomSoundFont(null, null)
    }

    suspend fun setHoldDurationMs(durationMs: Int) = settingsStore.setHoldDurationMs(durationMs)
    suspend fun setDefaultFadeOutMs(fadeMs: Int) = settingsStore.setDefaultFadeOutMs(fadeMs)
    suspend fun setKeepScreenOnInLive(enabled: Boolean) = settingsStore.setKeepScreenOnInLive(enabled)

    companion object {
        fun defaultMassTitle(date: LocalDate = LocalDate.now()): String {
            val formatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ITALIAN)
            val formatted = date.format(formatter).replaceFirstChar { it.uppercaseChar() }
            return "Messa · $formatted"
        }
    }
}
