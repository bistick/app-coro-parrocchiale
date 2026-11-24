package it.parrocchia.coro

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.documentfile.provider.DocumentFile
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import it.parrocchia.coro.data.db.CoroDao
import it.parrocchia.coro.data.db.CoroDatabase
import it.parrocchia.coro.data.db.SongEntity
import it.parrocchia.coro.data.library.LibraryScanner
import it.parrocchia.coro.data.library.SoundFontManager
import it.parrocchia.coro.data.prefs.AppSettingsStore
import it.parrocchia.coro.data.repository.CoroRepository
import it.parrocchia.coro.domain.model.AudioFormat
import it.parrocchia.coro.domain.model.MassPresetCatalog
import it.parrocchia.coro.domain.model.StandardPhaseType
import it.parrocchia.coro.domain.model.TrackSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoroRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: CoroDatabase
    private lateinit var dao: CoroDao
    private lateinit var settingsStore: AppSettingsStore
    private lateinit var soundFontManager: SoundFontManager
    private lateinit var scanner: LibraryScanner
    private lateinit var repository: CoroRepository

    private var fakeDocRoot: DocumentFile? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "soundfonts").deleteRecursively()
        db = Room.inMemoryDatabaseBuilder(context, CoroDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.coroDao()
        settingsStore = AppSettingsStore(
            PreferenceDataStoreFactory.create { File(tempFolder.root, "repo_test.preferences_pb") }
        )
        soundFontManager = SoundFontManager(context)
        scanner = LibraryScanner(
            context = context,
            dao = dao,
            documentTreeResolver = { fakeDocRoot },
            midiDurationProber = { 100_000L }
        )
        repository = CoroRepository(dao, scanner, settingsStore, soundFontManager)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun ensureInitialized_createsSampleMassWithDemoSongsAndIsIdempotent() = runTest {
        repository.ensureInitialized()

        val active = repository.activeMassPlan.first()!!
        assertEquals(MassPresetCatalog.DOMENICALE.id, active.plan.templateCategory)
        assertTrue(active.enabledPhases.isNotEmpty())
        val totalTracks = active.enabledPhases.sumOf { it.tracks.size }
        assertTrue("Demo mass should be prepopulated", totalTracks > 0)

        // Second call does not recreate mass
        val planIdBefore = active.plan.id
        repository.ensureInitialized()
        assertEquals(planIdBefore, repository.activeMassPlan.first()!!.plan.id)
    }

    @Test
    fun titlePhasesTracksReorderSettingsAndSessionProgress_workEndToEnd() = runTest {
        repository.ensureInitialized()
        val plan = repository.activeMassPlan.first()!!
        val planId = plan.plan.id

        // Update title (ignores blank)
        repository.updateMassTitle(planId, "   ")
        val oldTitle = repository.activeMassPlan.first()!!.plan.title
        repository.updateMassTitle(planId, "  Messa Ore 10:30  ")
        assertEquals("Messa Ore 10:30", repository.activeMassPlan.first()!!.plan.title)
        assertTrue(oldTitle != "Messa Ore 10:30")

        // Custom phase add/toggle/delete
        var blankThrew = false
        try {
            repository.addCustomPhase(planId, "   ")
        } catch (_: IllegalArgumentException) {
            blankThrew = true
        }
        assertTrue(blankThrew)

        val customId = repository.addCustomPhase(planId, "Canto a Maria")
        repository.togglePhaseEnabled(customId, false)
        assertFalse(repository.activeMassPlan.first()!!.orderedPhases.first { it.phase.id == customId }.phase.isEnabled)
        repository.togglePhaseEnabled(customId, true)

        // Add songs & reorder
        val songs = repository.allSongs.first()
        val s1 = songs[0]
        val s2 = songs[1]
        val t1 = repository.addSongToPhase(customId, s1)
        val t2 = repository.addSongToPhase(customId, s2)

        // Out-of-bounds moves are safe no-ops
        repository.moveTrackInPhase(customId, t1, moveUp = true)
        repository.moveTrackInPhase(customId, t2, moveUp = false)
        repository.moveTrackInPhase(customId, -999L, moveUp = true)

        // Valid move up/down
        repository.moveTrackInPhase(customId, t2, moveUp = true)
        assertEquals(listOf(t2, t1), dao.getTracksInPhase(customId).map { it.id })
        repository.moveTrackInPhase(customId, t2, moveUp = false)
        assertEquals(listOf(t1, t2), dao.getTracksInPhase(customId).map { it.id })

        // Save track settings + direct song defaults
        repository.saveTrackSettings(
            trackId = t1,
            songId = s1.id,
            settings = TrackSettings(fadeOutMs = 5000, startOffsetMs = 2000L, transposeSemitones = 2, volume = 1.2f),
            maxDurationMs = 60_000L,
            saveAsSongDefault = true
        )
        assertEquals(5000, dao.getPlannedTrackById(t1)?.fadeOutMs)
        assertEquals(5000, dao.getSongById(s1.id)?.defaultFadeOutMs)

        repository.updateSongDefaultsDirect(
            songId = s2.id,
            settings = TrackSettings(fadeOutMs = 2500, startOffsetMs = 1000L, transposeSemitones = -3, volume = 0.75f),
            maxDurationMs = 60_000L
        )
        assertEquals(-3, dao.getSongById(s2.id)?.defaultTransposeSemitones)

        // Session played flags
        repository.markTrackPlayed(t1, true)
        assertTrue(dao.getPlannedTrackById(t1)!!.playedInSession)
        repository.resetSessionProgress(planId)
        assertFalse(dao.getPlannedTrackById(t1)!!.playedInSession)

        repository.removePlannedTrack(t2)
        assertEquals(1, dao.getTracksInPhase(customId).size)
        repository.deleteCustomPhase(customId)
        assertNull(repository.activeMassPlan.first()!!.orderedPhases.firstOrNull { it.phase.id == customId })
    }

    @Test
    fun resetMassForNewDay_coversNoActiveKeepPhasesAndResetPhases() = runTest {
        // When no active plan exists, creates a fresh one
        repository.resetMassForNewDay(keepEnabledPhases = true)
        assertNotNull(repository.activeMassPlan.first())

        val planId = repository.activeMassPlan.first()!!.plan.id
        val customId = repository.addCustomPhase(planId, "Extra")
        val songId = dao.insertSong(
            SongEntity(uri = "content://x", title = "X", fileName = "x.mid", format = AudioFormat.MIDI.name)
        )
        repository.addSongToPhase(customId, dao.getSongById(songId)!!)

        // Reset keeping phases
        repository.resetMassForNewDay(keepEnabledPhases = true)
        val afterKeep = repository.activeMassPlan.first()!!
        assertEquals(0, afterKeep.enabledPhases.sumOf { it.tracks.size })
        assertTrue(afterKeep.orderedPhases.any { it.phase.id == customId })

        // Reset restoring Sunday defaults
        repository.resetMassForNewDay(keepEnabledPhases = false)
        val afterReset = repository.activeMassPlan.first()!!
        assertFalse(afterReset.orderedPhases.any { it.phase.standardType == StandardPhaseType.CUSTOM })
        assertEquals(MassPresetCatalog.DOMENICALE.id, afterReset.plan.templateCategory)
    }

    @Test
    fun applyLiturgicalPreset_coversFreshCreationPreserveTracksAndClearTracks() = runTest {
        // Fresh creation when no active mass exists
        repository.applyLiturgicalPreset(MassPresetCatalog.NATALE, clearTracks = false)
        val natalePlan = repository.activeMassPlan.first()!!
        assertEquals(MassPresetCatalog.NATALE.id, natalePlan.plan.templateCategory)
        assertTrue(natalePlan.plan.title.startsWith(MassPresetCatalog.NATALE.name))
        assertTrue(natalePlan.enabledPhases.any { it.phase.displayName == "Annuncio della Nascita (Kalenda)" })

        // Add tracks to Ingresso (standard) and Kalenda (custom)
        val songId = dao.insertSong(
            SongEntity(uri = "content://natale", title = "Tu scendi dalle stelle", fileName = "tu.mid", format = "MIDI")
        )
        val song = dao.getSongById(songId)!!
        val ingressoPhase = natalePlan.enabledPhases.first { it.phase.standardType == StandardPhaseType.INGRESSO }
        val kalendaPhase = natalePlan.enabledPhases.first { it.phase.displayName == "Annuncio della Nascita (Kalenda)" }
        repository.addSongToPhase(ingressoPhase.phase.id, song)
        repository.addSongToPhase(kalendaPhase.phase.id, song)

        // Re-apply NATALE with clearTracks = false -> preserves BOTH standard and matching custom tracks
        repository.applyLiturgicalPreset(MassPresetCatalog.NATALE, clearTracks = false)
        val reapplied = repository.activeMassPlan.first()!!
        assertEquals(1, reapplied.enabledPhases.first { it.phase.displayName == "Annuncio della Nascita (Kalenda)" }.tracks.size)
        assertEquals(1, reapplied.enabledPhases.first { it.phase.standardType == StandardPhaseType.INGRESSO }.tracks.size)

        // Switch to GIOVEDI_SANTO with clearTracks = false -> removes Kalenda, adds Lavanda + Reposizione, preserves Ingresso track
        repository.applyLiturgicalPreset(MassPresetCatalog.GIOVEDI_SANTO, clearTracks = false)
        val giovedi = repository.activeMassPlan.first()!!
        assertEquals(MassPresetCatalog.GIOVEDI_SANTO.id, giovedi.plan.templateCategory)
        assertFalse(giovedi.orderedPhases.any { it.phase.displayName == "Annuncio della Nascita (Kalenda)" })
        assertTrue(giovedi.enabledPhases.any { it.phase.displayName == "Lavanda dei piedi" })
        assertEquals(1, giovedi.enabledPhases.first { it.phase.standardType == StandardPhaseType.INGRESSO }.tracks.size)

        // Switch to FERIALE with clearTracks = true -> removes all tracks and disables Gloria
        repository.applyLiturgicalPreset(MassPresetCatalog.FERIALE, clearTracks = true)
        val feriale = repository.activeMassPlan.first()!!
        assertEquals(0, feriale.enabledPhases.sumOf { it.tracks.size })
        assertFalse(feriale.enabledPhases.any { it.phase.standardType == StandardPhaseType.GLORIA })

        // Missing standard phase recovery check
        val credPhase = feriale.orderedPhases.first { it.phase.standardType == StandardPhaseType.CREDO }
        dao.deletePhase(credPhase.phase.id)
        repository.applyLiturgicalPreset(MassPresetCatalog.SOLENNE, clearTracks = false)
        assertTrue(repository.activeMassPlan.first()!!.enabledPhases.any { it.phase.standardType == StandardPhaseType.CREDO })
    }

    @Test
    fun savedTemplates_saveApplyDeleteAndValidation() = runTest {
        // Throws when blank or no active mass
        var threwBlank = false
        try {
            repository.saveActivePlanAsTemplate("  ")
        } catch (_: IllegalArgumentException) {
            threwBlank = true
        }
        assertTrue(threwBlank)

        var threwNoActive = false
        try {
            repository.saveActivePlanAsTemplate("Festa")
        } catch (_: IllegalStateException) {
            threwNoActive = true
        }
        assertTrue(threwNoActive)

        // Create active plan with a track marked played
        repository.ensureInitialized()
        val active = repository.activeMassPlan.first()!!
        val firstTrack = active.enabledPhases.first { it.tracks.isNotEmpty() }.orderedTracks.first()
        repository.markTrackPlayed(firstTrack.track.id, true)

        val templateId = repository.saveActivePlanAsTemplate("  Festa Patronale  ")
        val templates = repository.savedTemplates.first()
        assertEquals(1, templates.size)
        assertEquals("Festa Patronale", templates.first().plan.title)

        // Applying unknown template is a safe no-op
        repository.applySavedTemplate(-1L)

        // Apply saved template -> creates new active plan with custom: prefix and playedInSession = false
        repository.applySavedTemplate(templateId)
        val loaded = repository.activeMassPlan.first()!!
        assertEquals("${CoroRepository.CUSTOM_TEMPLATE_PREFIX}Festa Patronale", loaded.plan.templateCategory)
        assertTrue(loaded.plan.title.startsWith("Festa Patronale · "))
        assertTrue(loaded.enabledPhases.flatMap { it.tracks }.none { it.track.playedInSession })

        repository.deleteSavedTemplate(templateId)
        assertTrue(repository.savedTemplates.first().isEmpty())
    }

    @Test
    fun librarySoundFontSettingsAndCompanionHelpers() = runTest {
        // Rescan fails when no folder configured
        assertTrue(repository.rescanCurrentLibraryFolder().isFailure)

        // Scan & rescan with fake empty root
        fakeDocRoot = mockk<DocumentFile>(relaxed = true).also {
            every { it.listFiles() } returns emptyArray()
        }
        val scan = repository.scanUserLibraryFolder(Uri.parse("content://tree/canti"), "Canti")
        assertEquals(0, scan.totalIndexed)
        assertTrue(repository.rescanCurrentLibraryFolder().isSuccess)

        repository.reloadDemoSongs()
        assertTrue(repository.allSongs.first().isNotEmpty())

        // SoundFont & settings pass-through
        val sfPath = repository.getActiveSoundFontPath()
        assertTrue(File(sfPath).exists())
        repository.resetToBundledSoundFont()
        assertNull(repository.appSettings.first().customSoundFontLocalPath)

        // Import invalid URI returns failure
        assertTrue(repository.importCustomSoundFont(Uri.parse("content://invalid/sf2")).isFailure)

        repository.setHoldDurationMs(550)
        repository.setDefaultFadeOutMs(4000)
        repository.setKeepScreenOnInLive(false)
        val autoCfg = it.parrocchia.coro.domain.mixer.RodeAutomationConfig(
            enabled = true,
            musicFaderIndices = setOf(6),
            alwaysMuteOnStopFaderIndices = setOf(0)
        )
        repository.setRodeAutomationConfig(autoCfg)
        val s = repository.appSettings.first()
        assertEquals(550, s.holdDurationMs)
        assertEquals(4000, s.defaultFadeOutMs)
        assertFalse(s.keepScreenOnInLive)
        assertEquals(autoCfg, s.rodeAutomationConfig)

        // Default mixerConfigJson on song is inherited by addSongToPhase AND synced to existing active Mass tracks
        repository.ensureInitialized()
        val firstSong = repository.allSongs.first().first()
        val activeBeforeEdit = repository.activeMassPlan.first()!!
        val phaseForExisting = activeBeforeEdit.enabledPhases.first().phase.id
        val existingTrackId = repository.addSongToPhase(phaseForExisting, firstSong)
        val templateSnapshotId = repository.saveActivePlanAsTemplate("Snapshot Pre-Modifica")

        val soloistJson = """{"unmuteFaderIndices":[0]}"""
        repository.updateSongDefaultsDirect(
            songId = firstSong.id,
            settings = TrackSettings(
                fadeOutMs = 4500,
                startOffsetMs = 2000L,
                transposeSemitones = 2,
                volume = 1.15f,
                mixerConfigJson = soloistJson
            ),
            maxDurationMs = 60_000L
        )
        val updatedSong = repository.allSongs.first().first { it.id == firstSong.id }
        assertEquals(soloistJson, updatedSong.defaultMixerConfigJson)

        // Il brano già presente nella scaletta attiva viene sincronizzato subito
        val syncedExistingTrack = repository.activeMassPlan.first()!!
            .enabledPhases.flatMap { it.tracks }
            .first { it.track.id == existingTrackId }
        assertEquals(soloistJson, syncedExistingTrack.track.mixerConfigJson)
        assertEquals(4500, syncedExistingTrack.track.fadeOutMs)
        assertEquals(2000L, syncedExistingTrack.track.startOffsetMs)
        assertEquals(2, syncedExistingTrack.track.transposeSemitones)
        assertEquals(1.15f, syncedExistingTrack.track.volume, 1e-5f)

        // I template salvati restano immutati (snapshot congelato)
        val savedTemplateTrack = repository.savedTemplates.first()
            .first { it.plan.id == templateSnapshotId }
            .phases.flatMap { it.tracks }
            .first { it.song.id == firstSong.id }
        assertNull(savedTemplateTrack.track.mixerConfigJson)

        val activePlan = repository.activeMassPlan.first()!!
        val phaseId = activePlan.enabledPhases.first().phase.id
        val addedTrackId = repository.addSongToPhase(phaseId, updatedSong)
        val addedTrack = repository.activeMassPlan.first()!!
            .enabledPhases.flatMap { it.tracks }
            .first { it.track.id == addedTrackId }
        assertEquals(soloistJson, addedTrack.track.mixerConfigJson)

        // Companion helpers
        val title = CoroRepository.defaultMassTitle(LocalDate.of(2026, 12, 25), "Messa di Natale")
        assertTrue(title.startsWith("Messa di Natale · "))
        assertEquals("Personalizzata", CoroRepository.resolvePresetDisplayName(null))
        assertEquals("Personalizzata", CoroRepository.resolvePresetDisplayName("   "))
        assertEquals("Personalizzata", CoroRepository.resolvePresetDisplayName("unknown_id"))
        assertEquals("Personalizzata", CoroRepository.resolvePresetDisplayName("custom:"))
        assertEquals("Festa San Marco", CoroRepository.resolvePresetDisplayName("custom:Festa San Marco"))
        assertEquals("Messa di Natale", CoroRepository.resolvePresetDisplayName("natale"))
    }
}
