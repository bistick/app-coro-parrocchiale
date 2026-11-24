package it.parrocchia.coro

import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import io.mockk.mockk
import it.parrocchia.coro.audio.PlaybackController
import it.parrocchia.coro.audio.PlaybackState
import it.parrocchia.coro.data.db.MassPhaseEntity
import it.parrocchia.coro.data.db.MassPlanEntity
import it.parrocchia.coro.data.db.MassPlanWithPhases
import it.parrocchia.coro.data.db.PhaseWithTracks
import it.parrocchia.coro.data.db.PlannedTrackEntity
import it.parrocchia.coro.data.db.PlannedTrackWithSong
import it.parrocchia.coro.data.db.SongEntity
import it.parrocchia.coro.domain.model.AudioFormat
import it.parrocchia.coro.domain.model.MassPreset
import it.parrocchia.coro.domain.model.MassPresetCatalog
import it.parrocchia.coro.domain.model.StandardPhaseType
import it.parrocchia.coro.domain.model.TrackSettings
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import it.parrocchia.coro.audio.PlaybackStatus
import it.parrocchia.coro.data.prefs.AppSettings
import it.parrocchia.coro.ui.components.HoldToConfirmSurface
import it.parrocchia.coro.ui.screens.ConfigurePhasesSheet
import it.parrocchia.coro.ui.screens.LibraryScreen
import it.parrocchia.coro.ui.screens.LiveExecutionScreen
import it.parrocchia.coro.ui.screens.MassPresetsSheet
import it.parrocchia.coro.ui.screens.PlannerScreen
import it.parrocchia.coro.ui.screens.SettingsScreen
import it.parrocchia.coro.ui.screens.TrackSettingsSheet
import it.parrocchia.coro.ui.theme.CoroColors
import it.parrocchia.coro.ui.theme.CoroTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h915dp-xxhdpi")
class ComposeUiBehaviorTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun SemanticsNodeInteraction.clickSheetAction(): SemanticsNodeInteraction =
        performSemanticsAction(SemanticsActions.OnClick)

    private fun sampleSong(id: Long = 1L, title: String = "Gloria in Excelsis") = SongEntity(
        id = id,
        uri = "asset:///demo/02_gloria.mid",
        title = title,
        fileName = "02_gloria.mid",
        format = AudioFormat.MIDI.name,
        durationMs = 120_000L
    )

    private fun sampleMassPlan(withTracks: Boolean = true): MassPlanWithPhases {
        val song = sampleSong()
        val phase1 = MassPhaseEntity(
            id = 10L,
            massPlanId = 1L,
            phaseType = StandardPhaseType.INGRESSO.name,
            sortOrder = 10,
            isEnabled = true
        )
        val phase2 = MassPhaseEntity(
            id = 11L,
            massPlanId = 1L,
            phaseType = StandardPhaseType.CUSTOM.name,
            customLabel = "Canto Finale Extra",
            sortOrder = 200,
            isEnabled = true
        )
        val tracks = if (withTracks) {
            listOf(
                PlannedTrackWithSong(
                    track = PlannedTrackEntity(id = 100L, phaseId = 10L, songId = song.id, sortOrder = 0),
                    song = song
                )
            )
        } else {
            emptyList()
        }
        return MassPlanWithPhases(
            plan = MassPlanEntity(
                id = 1L,
                title = "Messa Domenicale · Domenica 24 Novembre",
                dateEpochDay = 20_000L,
                templateCategory = MassPresetCatalog.DOMENICALE.id
            ),
            phases = listOf(
                PhaseWithTracks(phase1, tracks),
                PhaseWithTracks(phase2, emptyList())
            )
        )
    }

    @Test
    fun holdToConfirmSurface_rendersLabelAndIgnoresShortTap() {
        var confirmed = false
        composeRule.setContent {
            CoroTheme {
                HoldToConfirmSurface(
                    holdDurationMs = 500,
                    onConfirmed = { confirmed = true },
                    containerColor = CoroColors.PlayGreen,
                    progressFillColor = CoroColors.PlayGreenFill,
                    contentDescriptionText = "Avvia brano"
                ) { isHolding ->
                    Text(if (isHolding) "Tieni premuto" else "AVVIA BRANO")
                }
            }
        }

        composeRule.onNodeWithText("AVVIA BRANO").assertIsDisplayed()
        composeRule.onNodeWithText("AVVIA BRANO").performClick()
        assertFalse("Un tocco breve non deve confermare l'azione live", confirmed)
    }

    @Test
    fun configurePhasesSheet_addsAndDeletesCustomPhase() {
        var addedLabel: String? = null
        var deletedId: Long? = null
        val plan = sampleMassPlan()

        composeRule.setContent {
            CoroTheme {
                ConfigurePhasesSheet(
                    phases = plan.orderedPhases,
                    onTogglePhase = { _, _ -> },
                    onAddCustomPhase = { addedLabel = it },
                    onDeleteCustomPhase = { deletedId = it },
                    onDismiss = {}
                )
            }
        }

        composeRule.onNodeWithText("Fasi della Messa").assertIsDisplayed()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("Adorazione")
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Aggiungi fase").clickSheetAction()
        composeRule.waitForIdle()
        assertEquals("Adorazione", addedLabel)

        composeRule.onNodeWithContentDescription("Elimina fase personalizzata").clickSheetAction()
        composeRule.waitForIdle()
        assertEquals(11L, deletedId)
    }

    @Test
    fun massPresetsSheet_filtersByCategoryAppliesPresetAndSavedTemplate() {
        var appliedPreset: MassPreset? = null
        var appliedClearTracks: Boolean? = null
        var appliedSavedId: Long? = null
        var appliedSavedTitle: String? = null

        val savedTemplate = sampleMassPlan(withTracks = true).let {
            it.copy(plan = it.plan.copy(id = 77L, title = "Messa Patronale", isTemplate = true))
        }

        composeRule.setContent {
            CoroTheme {
                MassPresetsSheet(
                    activePresetId = MassPresetCatalog.DOMENICALE.id,
                    currentMassTitle = "Messa Domenicale · Domenica",
                    hasCurrentTracks = true,
                    initialClearTracks = false,
                    savedTemplates = listOf(savedTemplate),
                    onApplyPreset = { preset, clear ->
                        appliedPreset = preset
                        appliedClearTracks = clear
                    },
                    onSaveCurrentAsTemplate = {},
                    onApplySavedTemplate = { id, title ->
                        appliedSavedId = id
                        appliedSavedTitle = title
                    },
                    onDeleteSavedTemplate = {},
                    onDismiss = {}
                )
            }
        }

        composeRule.onNodeWithText("Preset di Messa").assertIsDisplayed()
        composeRule.onNodeWithText("Salva scaletta attuale come preset").assertIsDisplayed()
        composeRule.onNodeWithText("I miei (1)").clickSheetAction()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Messa Patronale").assertIsDisplayed()
        composeRule.onNodeWithText("Messa Patronale").clickSheetAction()
        composeRule.waitForIdle()
        assertEquals(77L, appliedSavedId)
        assertEquals("Messa Patronale", appliedSavedTitle)

        composeRule.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("Triduo Pasquale"))
        composeRule.onNodeWithText("Triduo Pasquale").clickSheetAction()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Svuota i brani già inseriti cambiando preset").clickSheetAction()
        composeRule.onAllNodes(hasScrollAction())[1].performScrollToNode(hasText("Veglia Pasquale"))
        composeRule.onNodeWithText("Veglia Pasquale").assertIsDisplayed()
        composeRule.onNodeWithText("Veglia Pasquale").clickSheetAction()
        composeRule.waitForIdle()

        assertEquals(MassPresetCatalog.VEGLIA_PASQUALE, appliedPreset)
        assertEquals(true, appliedClearTracks)
    }

    @Test
    fun trackSettingsSheet_adjustsStartOffsetTransposeAndSaves() {
        val playbackController = mockk<PlaybackController>(relaxed = true)
        val stateFlow = MutableStateFlow(PlaybackState())
        var savedSettings: TrackSettings? = null
        var savedDefault: Boolean? = null

        composeRule.setContent {
            CoroTheme {
                TrackSettingsSheet(
                    song = sampleSong(),
                    initialSettings = TrackSettings(fadeOutMs = 3000, startOffsetMs = 0L, transposeSemitones = 0, volume = 1.0f),
                    showSaveAsDefaultToggle = true,
                    playbackStateFlow = stateFlow,
                    playbackController = playbackController,
                    onSave = { settings, asDefault ->
                        savedSettings = settings
                        savedDefault = asDefault
                    },
                    onDismiss = {}
                )
            }
        }

        composeRule.onNodeWithText("Punto di attacco").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("+5 s").performScrollTo().clickSheetAction()
        composeRule.onNodeWithText("+1 s").performScrollTo().clickSheetAction()
        composeRule.onNodeWithText("+1 semitono").performScrollTo().clickSheetAction()
        composeRule.onNodeWithText("Salva").performScrollTo().clickSheetAction()
        composeRule.waitForIdle()

        assertEquals(6_000L, savedSettings?.startOffsetMs)
        assertEquals(1, savedSettings?.transposeSemitones)
        assertEquals(true, savedDefault)
    }

    @Test
    fun plannerScreen_displaysPresetBannerAndOpensPresetsSheet() {
        val playbackController = mockk<PlaybackController>(relaxed = true)
        val stateFlow = MutableStateFlow(PlaybackState())
        var liveEntered = false

        composeRule.setContent {
            CoroTheme {
                PlannerScreen(
                    massPlan = sampleMassPlan(withTracks = true),
                    savedTemplates = emptyList(),
                    playbackStateFlow = stateFlow,
                    playbackController = playbackController,
                    onUpdateTitle = { _, _ -> },
                    onTogglePhase = { _, _ -> },
                    onAddCustomPhase = { _, _ -> },
                    onDeleteCustomPhase = {},
                    onAddSongForPhase = { _, _ -> },
                    onRemoveTrack = {},
                    onMoveTrack = { _, _, _ -> },
                    onSaveTrackSettings = { _, _, _, _, _ -> },
                    onResetMass = {},
                    onApplyPreset = { _, _ -> },
                    onSaveAsTemplate = {},
                    onApplySavedTemplate = { _, _ -> },
                    onDeleteSavedTemplate = {},
                    onOpenLibrary = {},
                    onOpenSettings = {},
                    onEnterLiveMode = { liveEntered = true }
                )
            }
        }

        composeRule.onNodeWithText("PRESET MESSA").assertIsDisplayed()
        composeRule.onAllNodesWithText("Messa Domenicale").onFirst().assertIsDisplayed()
        composeRule.onNodeWithText("Cambia").performClick()
        composeRule.onNodeWithText("Preset di Messa").assertIsDisplayed()

        // Tap live execution
        composeRule.onNodeWithText("AVVIA ESECUZIONE").performClick()
        assertTrue(liveEntered)
    }

    @Test
    fun libraryScreen_filtersSongsAndPicksForPhase() {
        val playbackController = mockk<PlaybackController>(relaxed = true)
        val stateFlow = MutableStateFlow(PlaybackState())
        val midiSong = sampleSong(1L, "Gloria in Excelsis").copy(relativeFolder = "Ordinario")
        val mp3Song = SongEntity(
            id = 2L,
            uri = "content://songs/alleluia.mp3",
            title = "Alleluia Pasquale",
            fileName = "alleluia.mp3",
            relativeFolder = "Pasqua",
            format = AudioFormat.AUDIO.name,
            durationMs = 95_000L
        )
        var pickedSong: SongEntity? = null
        var rescanned = false
        var backed = false

        composeRule.setContent {
            CoroTheme {
                LibraryScreen(
                    songs = listOf(midiSong, mp3Song),
                    settings = AppSettings(
                        libraryFolderUri = "content://tree/canti",
                        libraryFolderName = "Canti Parrocchia"
                    ),
                    isScanning = false,
                    pickerPhaseName = "Ingresso",
                    playbackStateFlow = stateFlow,
                    playbackController = playbackController,
                    onSelectFolder = { _, _ -> },
                    onRescanFolder = { rescanned = true },
                    onPickSongForPhase = { pickedSong = it },
                    onSaveSongDefaults = { _, _ -> },
                    onBack = { backed = true }
                )
            }
        }

        composeRule.onNodeWithText("Scegli brano").assertIsDisplayed()
        composeRule.onNodeWithText("Per: Ingresso").assertIsDisplayed()
        composeRule.onNodeWithText("Gloria in Excelsis").assertIsDisplayed()
        composeRule.onNodeWithText("Alleluia Pasquale").assertIsDisplayed()

        // Filter by search query
        composeRule.onNode(hasSetTextAction()).performTextReplacement("Alleluia")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Alleluia Pasquale").assertIsDisplayed()
        composeRule.onNodeWithText("Alleluia Pasquale").performClick()
        assertEquals(mp3Song, pickedSong)

        composeRule.onNodeWithContentDescription("Aggiorna libreria").performClick()
        assertTrue(rescanned)
        composeRule.onNodeWithContentDescription("Indietro").performClick()
        assertTrue(backed)
    }

    @Test
    fun settingsScreen_rendersCardsAndTriggersCallbacks() {
        var rescanned = false
        var reloadedDemo = false
        var resetSf = false
        var backed = false

        composeRule.setContent {
            CoroTheme {
                SettingsScreen(
                    settings = AppSettings(
                        libraryFolderUri = "content://tree/canti",
                        libraryFolderName = "Canti Coro",
                        customSoundFontLocalPath = "/data/sf/GeneralUser.sf3",
                        customSoundFontName = "GeneralUser.sf3",
                        holdDurationMs = 500,
                        defaultFadeOutMs = 3000,
                        keepScreenOnInLive = true
                    ),
                    isScanning = false,
                    onSelectLibraryFolder = { _, _ -> },
                    onRescanLibrary = { rescanned = true },
                    onReloadDemoTracks = { reloadedDemo = true },
                    onImportCustomSoundFont = {},
                    onResetSoundFont = { resetSf = true },
                    onSetHoldDurationMs = {},
                    onSetDefaultFadeOutMs = {},
                    onSetKeepScreenOn = {},
                    onBack = { backed = true }
                )
            }
        }

        composeRule.onNodeWithText("Impostazioni").assertIsDisplayed()
        composeRule.onNodeWithText("Cartella attiva: Canti Coro").assertIsDisplayed()
        composeRule.onNodeWithText("Aggiorna").performClick()
        composeRule.onNodeWithText("Brani di prova").performClick()
        assertTrue(rescanned)
        assertTrue(reloadedDemo)

        composeRule.onNodeWithText("Ripristina").performClick()
        assertTrue(resetSf)

        composeRule.onNodeWithContentDescription("Indietro").performClick()
        assertTrue(backed)
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    @Test
    fun liveExecutionScreen_rendersCompactLayoutWithErrorBannerAndActiveState() {
        val playbackFlow = MutableStateFlow(
            PlaybackState(
                status = PlaybackStatus.ERROR,
                errorMessage = "SoundFont non trovato"
            )
        )
        var dismissedError = false
        val compactClass = WindowSizeClass.calculateFromSize(DpSize(411.dp, 915.dp))

        // LiveExecutionScreen ha un LaunchedEffect ciclico (while(isActive) delay(1500)):
        // disabilitiamo autoAdvance così il test avanza il clock deterministicamente.
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CoroTheme {
                LiveExecutionScreen(
                    massPlan = sampleMassPlan(withTracks = true),
                    settings = AppSettings(keepScreenOnInLive = false),
                    playbackStateFlow = playbackFlow,
                    windowSizeClass = compactClass,
                    onPlayTrack = { _, _ -> },
                    onFadeOut = {},
                    onStopImmediate = {},
                    onResetPlayedFlags = {},
                    onDismissError = { dismissedError = true },
                    onExitLiveMode = {}
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(100L)

        composeRule.onNodeWithText("Gloria in Excelsis").assertIsDisplayed()
        composeRule.onNodeWithText("SoundFont non trovato").assertIsDisplayed()
        composeRule.onNodeWithText("OK").clickSheetAction()
        composeRule.mainClock.advanceTimeBy(100L)
        assertTrue(dismissedError)

        // Update flow to PLAYING and verify active playback controls render
        playbackFlow.value = PlaybackState(
            status = PlaybackStatus.PLAYING,
            activeTrackId = 100L,
            title = "Gloria in Excelsis",
            phaseLabel = "Ingresso",
            positionMs = 15_000L,
            durationMs = 120_000L,
            fadeOutMs = 3000,
            isPreview = false
        )
        composeRule.mainClock.advanceTimeBy(100L)
        composeRule.onNodeWithText("0:15 / 2:00").assertIsDisplayed()
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    @Test
    fun liveExecutionScreen_rendersExpandedTabletDeskLayout() {
        val playbackFlow = MutableStateFlow(
            PlaybackState(
                status = PlaybackStatus.PLAYING,
                activeTrackId = 100L,
                title = "Gloria in Excelsis",
                phaseLabel = "Ingresso",
                positionMs = 30_000L,
                durationMs = 120_000L,
                fadeOutMs = 3000,
                isPreview = false
            )
        )
        val expandedClass = WindowSizeClass.calculateFromSize(DpSize(1024.dp, 768.dp))

        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CoroTheme {
                LiveExecutionScreen(
                    massPlan = sampleMassPlan(withTracks = true),
                    settings = AppSettings(keepScreenOnInLive = false),
                    playbackStateFlow = playbackFlow,
                    windowSizeClass = expandedClass,
                    onPlayTrack = { _, _ -> },
                    onFadeOut = {},
                    onStopImmediate = {},
                    onResetPlayedFlags = {},
                    onDismissError = {},
                    onExitLiveMode = {}
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(100L)
        composeRule.onAllNodesWithText("Gloria in Excelsis").onFirst().assertIsDisplayed()
    }
}
