package it.parrocchia.coro

import android.net.Uri
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import it.parrocchia.coro.audio.PlaybackController
import it.parrocchia.coro.audio.PlaybackState
import it.parrocchia.coro.data.db.MassPlanWithPhases
import it.parrocchia.coro.data.db.PlannedTrackEntity
import it.parrocchia.coro.data.db.PlannedTrackWithSong
import it.parrocchia.coro.data.db.SongEntity
import it.parrocchia.coro.data.library.ScanSummary
import it.parrocchia.coro.data.prefs.AppSettings
import it.parrocchia.coro.data.repository.CoroRepository
import it.parrocchia.coro.domain.model.AudioFormat
import it.parrocchia.coro.domain.model.MassPresetCatalog
import it.parrocchia.coro.domain.model.TrackSettings
import it.parrocchia.coro.ui.CoroViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoroViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var repository: CoroRepository
    private lateinit var playbackController: PlaybackController
    private lateinit var vm: CoroViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        playbackController = mockk(relaxed = true)

        every { repository.activeMassPlan } returns MutableStateFlow<MassPlanWithPhases?>(null)
        every { repository.savedTemplates } returns MutableStateFlow<List<MassPlanWithPhases>>(emptyList())
        every { repository.allSongs } returns MutableStateFlow<List<SongEntity>>(emptyList())
        every { repository.appSettings } returns MutableStateFlow(AppSettings())
        every { playbackController.state } returns MutableStateFlow(PlaybackState())

        vm = CoroViewModel(repository, playbackController)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun init_callsEnsureInitializedAndExposesStateFlows() = runTest {
        coVerify { repository.ensureInitialized() }
        assertNull(vm.activeMassPlan.value)
        assertTrue(vm.savedTemplates.value.isEmpty())
        assertTrue(vm.songs.value.isEmpty())
        assertEquals(AppSettings(), vm.settings.value)
        assertEquals(PlaybackState(), vm.playbackState.value)
        assertFalse(vm.isScanning.value)
    }

    @Test
    fun massPlanningAndPresetActions_delegateAndEmitToasts() = runTest {
        val song = SongEntity(id = 7L, uri = "u", title = "Alleluia", fileName = "a.mid", format = AudioFormat.MIDI.name)

        vm.updateMassTitle(1L, "Messa Domenica")
        coVerify { repository.updateMassTitle(1L, "Messa Domenica") }

        vm.resetMass(keepEnabledPhases = false)
        verify { playbackController.stopPreviewIfActive() }
        coVerify { repository.resetMassForNewDay(false) }
        assertTrue(vm.toastMessage.value!!.contains("Scaletta azzerata"))
        vm.consumeToast()
        assertNull(vm.toastMessage.value)

        vm.applyLiturgicalPreset(MassPresetCatalog.NATALE, clearTracks = true)
        coVerify { repository.applyLiturgicalPreset(MassPresetCatalog.NATALE, true) }
        assertTrue(vm.toastMessage.value!!.contains("Messa di Natale"))

        // Save template: blank ignored, success & failure paths
        vm.consumeToast()
        vm.saveCurrentMassAsTemplate("   ")
        assertNull(vm.toastMessage.value)

        coEvery { repository.saveActivePlanAsTemplate("Festa") } returns 5L
        vm.saveCurrentMassAsTemplate(" Festa ")
        assertEquals("Preset salvato: Festa", vm.toastMessage.value)

        coEvery { repository.saveActivePlanAsTemplate("Err") } throws IllegalStateException("Boom")
        vm.saveCurrentMassAsTemplate("Err")
        assertEquals("Boom", vm.toastMessage.value)

        coEvery { repository.saveActivePlanAsTemplate("ErrNull") } throws IllegalStateException()
        vm.saveCurrentMassAsTemplate("ErrNull")
        assertEquals("Impossibile salvare il preset", vm.toastMessage.value)

        vm.applySavedTemplate(5L, "Festa")
        coVerify { repository.applySavedTemplate(5L) }
        assertEquals("Preset caricato: Festa", vm.toastMessage.value)

        vm.deleteSavedTemplate(5L)
        coVerify { repository.deleteSavedTemplate(5L) }
        assertEquals("Preset eliminato", vm.toastMessage.value)

        // Phases & tracks
        vm.togglePhase(2L, true)
        coVerify { repository.togglePhaseEnabled(2L, true) }

        vm.consumeToast()
        vm.addCustomPhase(1L, "  ")
        assertNull(vm.toastMessage.value)
        vm.addCustomPhase(1L, "Processione")
        coVerify { repository.addCustomPhase(1L, "Processione") }
        assertTrue(vm.toastMessage.value!!.contains("Processione"))

        vm.deleteCustomPhase(2L)
        coVerify { repository.deleteCustomPhase(2L) }

        vm.addSongToPhase(3L, song)
        coVerify { repository.addSongToPhase(3L, song) }
        assertEquals("Aggiunto: Alleluia", vm.toastMessage.value)

        vm.removeTrack(9L)
        coVerify { repository.removePlannedTrack(9L) }

        vm.moveTrack(3L, 9L, moveUp = true)
        coVerify { repository.moveTrackInPhase(3L, 9L, true) }

        // Track settings (with & without saveAsDefault)
        val settings = TrackSettings(fadeOutMs = 4000)
        vm.savePlannedTrackSettings(9L, 7L, settings, 60_000L, saveAsDefault = true)
        assertTrue(vm.toastMessage.value!!.contains("default"))
        vm.savePlannedTrackSettings(9L, 7L, settings, 60_000L, saveAsDefault = false)
        assertTrue(vm.toastMessage.value!!.contains("questa messa"))

        vm.saveSongDefaultsOnly(7L, settings, 60_000L)
        coVerify { repository.updateSongDefaultsDirect(7L, settings, 60_000L) }
    }

    @Test
    fun librarySoundFontSettingsAndLiveControls_handleSuccessAndErrorPaths() = runTest {
        val uri = Uri.parse("content://tree/canti")

        // Library folder scan success & error
        coEvery { repository.scanUserLibraryFolder(uri, "Canti") } returns ScanSummary(12, 12, 0, 0)
        vm.selectLibraryFolder(uri, "Canti")
        assertFalse(vm.isScanning.value)
        assertTrue(vm.toastMessage.value!!.contains("12 brani"))

        coEvery { repository.scanUserLibraryFolder(uri, "Bad") } throws RuntimeException("Permesso negato")
        vm.selectLibraryFolder(uri, "Bad")
        assertTrue(vm.toastMessage.value!!.contains("Permesso negato"))

        coEvery { repository.scanUserLibraryFolder(uri, "BadNull") } throws RuntimeException()
        vm.selectLibraryFolder(uri, "BadNull")
        assertTrue(vm.toastMessage.value!!.contains("riprova"))

        // Rescan success & failure
        coEvery { repository.rescanCurrentLibraryFolder() } returns Result.success(ScanSummary(15, 3, 0, 0))
        vm.rescanLibraryFolder()
        assertTrue(vm.toastMessage.value!!.contains("+3 nuovi"))

        coEvery { repository.rescanCurrentLibraryFolder() } returns Result.failure(IllegalStateException("Nessuna cartella"))
        vm.rescanLibraryFolder()
        assertEquals("Nessuna cartella", vm.toastMessage.value)

        coEvery { repository.rescanCurrentLibraryFolder() } returns Result.failure(IllegalStateException())
        vm.rescanLibraryFolder()
        assertTrue(vm.toastMessage.value!!.contains("Seleziona prima"))

        vm.ensureDemoTracks()
        coVerify { repository.reloadDemoSongs() }

        // SoundFont import success, failure with message & without message, reset
        val sfUri = Uri.parse("content://sf/FluidR3.sf2")
        coEvery { repository.importCustomSoundFont(sfUri) } returns Result.success("FluidR3.sf2")
        vm.importCustomSoundFont(sfUri)
        assertEquals("SoundFont attivo: FluidR3.sf2", vm.toastMessage.value)

        coEvery { repository.importCustomSoundFont(sfUri) } returns Result.failure(IllegalArgumentException("Non valido"))
        vm.importCustomSoundFont(sfUri)
        assertEquals("Non valido", vm.toastMessage.value)

        coEvery { repository.importCustomSoundFont(sfUri) } returns Result.failure(IllegalArgumentException())
        vm.importCustomSoundFont(sfUri)
        assertEquals("File SoundFont non valido", vm.toastMessage.value)

        vm.resetSoundFont()
        coVerify { repository.resetToBundledSoundFont() }

        // Settings setters
        vm.setHoldDurationMs(500)
        vm.setDefaultFadeOutMs(3500)
        vm.setKeepScreenOnInLive(false)
        coVerify { repository.setHoldDurationMs(500) }
        coVerify { repository.setDefaultFadeOutMs(3500) }
        coVerify { repository.setKeepScreenOnInLive(false) }

        // Live controls
        val item = PlannedTrackWithSong(
            PlannedTrackEntity(id = 1L, phaseId = 1L, songId = 1L, sortOrder = 0),
            SongEntity(id = 1L, uri = "u", title = "T", fileName = "t.mid", format = "MIDI")
        )
        vm.playLiveTrack("Ingresso", item)
        vm.fadeOutLive()
        vm.stopImmediateLive()
        vm.resetSessionPlayed(10L)
        vm.dismissPlaybackError()

        verify { playbackController.playLiveTrack("Ingresso", item) }
        verify { playbackController.fadeOutCurrent() }
        verify { playbackController.stopImmediateCurrent() }
        coVerify { repository.resetSessionProgress(10L) }
        verify { playbackController.clearError() }
    }

    @Test
    fun factory_createsCoroViewModelFromContainer() {
        val container = mockk<AppContainer>()
        every { container.repository } returns repository
        every { container.playbackController } returns playbackController

        val created = CoroViewModel.Factory(container).create(CoroViewModel::class.java)
        assertNotNull(created)
    }
}
