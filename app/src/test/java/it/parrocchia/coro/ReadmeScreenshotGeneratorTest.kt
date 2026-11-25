package it.parrocchia.coro

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.AudioManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import it.parrocchia.coro.audio.PlaybackController
import it.parrocchia.coro.audio.PlaybackState
import it.parrocchia.coro.audio.PlaybackStatus
import it.parrocchia.coro.data.db.MassPhaseEntity
import it.parrocchia.coro.data.db.MassPlanEntity
import it.parrocchia.coro.data.db.MassPlanWithPhases
import it.parrocchia.coro.data.db.PhaseWithTracks
import it.parrocchia.coro.data.db.PlannedTrackEntity
import it.parrocchia.coro.data.db.PlannedTrackWithSong
import it.parrocchia.coro.data.db.SongEntity
import it.parrocchia.coro.data.prefs.AppSettings
import it.parrocchia.coro.domain.mixer.RodeAutomationConfig
import it.parrocchia.coro.domain.mixer.RodeConnectionStatus
import it.parrocchia.coro.domain.mixer.RodeMixerState
import it.parrocchia.coro.domain.mixer.RodeVirtualFader
import it.parrocchia.coro.domain.model.AppThemeMode
import it.parrocchia.coro.domain.model.AudioFormat
import it.parrocchia.coro.domain.model.AudioOutputDeviceSummary
import it.parrocchia.coro.domain.model.AudioOutputRoute
import it.parrocchia.coro.domain.model.AudioOutputStatus
import it.parrocchia.coro.domain.model.MassPresetCatalog
import it.parrocchia.coro.domain.model.StandardPhaseType
import it.parrocchia.coro.ui.screens.LiveExecutionScreen
import it.parrocchia.coro.ui.screens.PlannerScreen
import it.parrocchia.coro.ui.screens.RodeMixerScreen
import it.parrocchia.coro.ui.theme.CoroTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.io.FileOutputStream

@Ignore("Generatore manuale delle schermate per il README in docs/screenshots/")
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadmeScreenshotGeneratorTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setNiceMediaVolume() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, (max * 0.85f).toInt().coerceAtLeast(1), 0)
    }

    private fun screenshotsDir(): File {
        val root = if (File("docs").exists()) File(".") else File("..")
        return File(root.canonicalFile, "docs/screenshots").apply { mkdirs() }
    }

    private fun saveCurrentWindow(fileName: String) {
        composeRule.mainClock.advanceTimeBy(250L)
        ShadowLooper.idleMainLooper()
        val contentView = composeRule.activity.findViewById<android.view.View>(android.R.id.content)
        val bitmap = Bitmap.createBitmap(contentView.width, contentView.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        contentView.draw(canvas)
        val outFile = File(screenshotsDir(), fileName)
        FileOutputStream(outFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 92, out)
        }
        bitmap.recycle()
    }

    private fun richMassPlan(firstPlayed: Boolean = false, secondPlayed: Boolean = false): MassPlanWithPhases {
        val songIngresso = SongEntity(
            id = 1L,
            uri = "asset:///demo/01.mid",
            title = "Noi canteremo gloria a Te",
            fileName = "01_ingresso.mid",
            format = AudioFormat.MIDI.name,
            durationMs = 175_000L
        )
        val songGloria = SongEntity(
            id = 2L,
            uri = "asset:///demo/02.mid",
            title = "Gloria a Dio nell'alto dei cieli",
            fileName = "02_gloria.mid",
            format = AudioFormat.MIDI.name,
            durationMs = 192_000L
        )
        val songSalmo = SongEntity(
            id = 3L,
            uri = "asset:///demo/03.mid",
            title = "Il Signore è il mio pastore",
            fileName = "03_salmo.mid",
            format = AudioFormat.MIDI.name,
            durationMs = 148_000L
        )
        val songAlleluia = SongEntity(
            id = 4L,
            uri = "asset:///demo/04.mid",
            title = "Alleluia — Canto per Cristo",
            fileName = "04_alleluia.mid",
            format = AudioFormat.MIDI.name,
            durationMs = 95_000L
        )
        val songOffertorio = SongEntity(
            id = 5L,
            uri = "content://canti/offertorio.mp3",
            title = "Frutto della nostra terra",
            fileName = "05_offertorio.mp3",
            format = AudioFormat.AUDIO.name,
            durationMs = 205_000L
        )
        val songSanto = SongEntity(
            id = 6L,
            uri = "asset:///demo/06.mid",
            title = "Santo (Gen Rosso)",
            fileName = "06_santo.mid",
            format = AudioFormat.MIDI.name,
            durationMs = 132_000L
        )
        val songComunione1 = SongEntity(
            id = 7L,
            uri = "content://canti/pane_del_cielo.mp3",
            title = "Pane del cielo",
            fileName = "07_comunione.mp3",
            format = AudioFormat.AUDIO.name,
            durationMs = 228_000L
        )
        val songComunione2 = SongEntity(
            id = 8L,
            uri = "asset:///demo/08.mid",
            title = "Symbolum '77 (Tu sei la mia vita)",
            fileName = "08_symbolum.mid",
            format = AudioFormat.MIDI.name,
            durationMs = 242_000L
        )

        return MassPlanWithPhases(
            plan = MassPlanEntity(
                id = 1L,
                title = "Messa Domenicale · Ore 10:30",
                dateEpochDay = 20_400L,
                templateCategory = MassPresetCatalog.DOMENICALE.id
            ),
            phases = listOf(
                PhaseWithTracks(
                    MassPhaseEntity(10L, 1L, StandardPhaseType.INGRESSO.name, null, 10, true),
                    listOf(
                        PlannedTrackWithSong(
                            PlannedTrackEntity(
                                id = 101L,
                                phaseId = 10L,
                                songId = 1L,
                                sortOrder = 0,
                                fadeOutMs = 3500,
                                playedInSession = firstPlayed
                            ),
                            songIngresso
                        )
                    )
                ),
                PhaseWithTracks(
                    MassPhaseEntity(20L, 1L, StandardPhaseType.GLORIA.name, null, 20, true),
                    listOf(
                        PlannedTrackWithSong(
                            PlannedTrackEntity(
                                id = 102L,
                                phaseId = 20L,
                                songId = 2L,
                                sortOrder = 0,
                                transposeSemitones = 2,
                                fadeOutMs = 3000,
                                playedInSession = secondPlayed
                            ),
                            songGloria
                        )
                    )
                ),
                PhaseWithTracks(
                    MassPhaseEntity(30L, 1L, StandardPhaseType.SALMO.name, null, 30, true),
                    listOf(
                        PlannedTrackWithSong(
                            PlannedTrackEntity(
                                id = 103L,
                                phaseId = 30L,
                                songId = 3L,
                                sortOrder = 0,
                                fadeOutMs = 3000,
                                mixerConfigJson = """{"unmuteFaderIndices":[0]}"""
                            ),
                            songSalmo
                        )
                    )
                ),
                PhaseWithTracks(
                    MassPhaseEntity(40L, 1L, StandardPhaseType.ALLELUIA.name, null, 40, true),
                    listOf(
                        PlannedTrackWithSong(
                            PlannedTrackEntity(
                                id = 104L,
                                phaseId = 40L,
                                songId = 4L,
                                sortOrder = 0,
                                startOffsetMs = 2000L,
                                fadeOutMs = 2500
                            ),
                            songAlleluia
                        )
                    )
                ),
                PhaseWithTracks(
                    MassPhaseEntity(50L, 1L, StandardPhaseType.OFFERTORIO.name, null, 50, true),
                    listOf(
                        PlannedTrackWithSong(
                            PlannedTrackEntity(
                                id = 105L,
                                phaseId = 50L,
                                songId = 5L,
                                sortOrder = 0,
                                fadeOutMs = 4500,
                                volume = 0.9f
                            ),
                            songOffertorio
                        )
                    )
                ),
                PhaseWithTracks(
                    MassPhaseEntity(60L, 1L, StandardPhaseType.SANTO.name, null, 60, true),
                    listOf(
                        PlannedTrackWithSong(
                            PlannedTrackEntity(
                                id = 106L,
                                phaseId = 60L,
                                songId = 6L,
                                sortOrder = 0,
                                transposeSemitones = -1,
                                fadeOutMs = 3000
                            ),
                            songSanto
                        )
                    )
                ),
                PhaseWithTracks(
                    MassPhaseEntity(70L, 1L, StandardPhaseType.COMUNIONE.name, null, 70, true),
                    listOf(
                        PlannedTrackWithSong(
                            PlannedTrackEntity(
                                id = 107L,
                                phaseId = 70L,
                                songId = 7L,
                                sortOrder = 0,
                                fadeOutMs = 5000
                            ),
                            songComunione1
                        ),
                        PlannedTrackWithSong(
                            PlannedTrackEntity(
                                id = 108L,
                                phaseId = 70L,
                                songId = 8L,
                                sortOrder = 1,
                                fadeOutMs = 5000
                            ),
                            songComunione2
                        )
                    )
                )
            )
        )
    }

    private fun sampleFaders() = listOf(
        RodeVirtualFader(faderIndex = 0, assignedSourceCode = 0, assignedLabel = "Mic 1 (Solista)", level = 88, isMuted = true),
        RodeVirtualFader(faderIndex = 1, assignedSourceCode = 1, assignedLabel = "Mic 2 (Coro SX)", level = 84, isMuted = false),
        RodeVirtualFader(faderIndex = 2, assignedSourceCode = 2, assignedLabel = "Mic 3 (Coro DX)", level = 84, isMuted = false),
        RodeVirtualFader(faderIndex = 6, assignedSourceCode = 7, assignedLabel = "USB 1 (Main)", level = 92, isMuted = false)
    )

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
    fun generateMobilePlannerDark() {
        val controller = mockk<PlaybackController>(relaxed = true)
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CoroTheme(themeMode = AppThemeMode.DARK) {
                PlannerScreen(
                    massPlan = richMassPlan(),
                    savedTemplates = emptyList(),
                    playbackStateFlow = MutableStateFlow(PlaybackState()),
                    playbackController = controller,
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
                    onEnterLiveMode = {},
                    isMixerConnected = true,
                    availableMixerFaders = sampleFaders(),
                    automationConfig = RodeAutomationConfig(enabled = true, musicFaderIndices = setOf(6))
                )
            }
        }
        saveCurrentWindow("mobile-planner.png")
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
    fun generateMobileLiveDark() {
        val windowClass = WindowSizeClass.calculateFromSize(DpSize(411.dp, 891.dp))
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CoroTheme(themeMode = AppThemeMode.DARK) {
                LiveExecutionScreen(
                    massPlan = richMassPlan(firstPlayed = true),
                    settings = AppSettings(keepScreenOnInLive = false),
                    playbackStateFlow = MutableStateFlow(
                        PlaybackState(
                            status = PlaybackStatus.PLAYING,
                            activeTrackId = 102L,
                            lastLiveTrackId = 101L,
                            title = "Gloria a Dio nell'alto dei cieli",
                            phaseLabel = "Gloria",
                            positionMs = 48_000L,
                            durationMs = 192_000L,
                            fadeOutMs = 3000,
                            isPreview = false
                        )
                    ),
                    windowSizeClass = windowClass,
                    onPlayTrack = { _, _ -> },
                    onFadeOut = {},
                    onStopImmediate = {},
                    onResetPlayedFlags = {},
                    onDismissError = {},
                    onExitLiveMode = {},
                    isMixerConnected = true
                )
            }
        }
        saveCurrentWindow("mobile-live.png")
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
    fun generateMobileLiveLight() {
        val windowClass = WindowSizeClass.calculateFromSize(DpSize(411.dp, 891.dp))
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CoroTheme(themeMode = AppThemeMode.LIGHT) {
                LiveExecutionScreen(
                    massPlan = richMassPlan(firstPlayed = true, secondPlayed = true),
                    settings = AppSettings(keepScreenOnInLive = false, themeMode = AppThemeMode.LIGHT),
                    playbackStateFlow = MutableStateFlow(
                        PlaybackState(
                            status = PlaybackStatus.IDLE,
                            lastLiveTrackId = 102L
                        )
                    ),
                    windowSizeClass = windowClass,
                    onPlayTrack = { _, _ -> },
                    onFadeOut = {},
                    onStopImmediate = {},
                    onResetPlayedFlags = {},
                    onDismissError = {},
                    onExitLiveMode = {},
                    isMixerConnected = true
                )
            }
        }
        saveCurrentWindow("mobile-live-light.png")
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
    fun generateMobileMixerDark() {
        val autoCfg = RodeAutomationConfig(
            enabled = true,
            musicFaderIndices = setOf(6),
            alwaysMuteOnStopFaderIndices = setOf(0)
        )
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CoroTheme(themeMode = AppThemeMode.DARK) {
                RodeMixerScreen(
                    mixerState = RodeMixerState(
                        status = RodeConnectionStatus.CONNECTED,
                        deviceName = "RØDECaster Pro II",
                        firmwareVersion = "1.7.7",
                        monitorMuted = false,
                        monitorAutoMuteEnabled = true,
                        monitorAutoMuteActive = true,
                        faders = sampleFaders(),
                        automationConfig = autoCfg
                    ),
                    onConnect = {},
                    onRefresh = {},
                    onDisconnect = {},
                    onSetChannelMute = { _, _ -> },
                    onDismissError = {},
                    onBack = {},
                    audioOutputStatus = AudioOutputStatus(
                        selectedRoute = AudioOutputRoute.USB,
                        usbDevice = AudioOutputDeviceSummary(1, "RØDECaster Pro II Main", AudioOutputRoute.USB)
                    )
                )
            }
        }
        saveCurrentWindow("mobile-mixer.png")
    }

    @Test
    @Config(sdk = [34], qualifiers = "w1180dp-h760dp-hdpi")
    fun generateTabletPlannerDark() {
        val controller = mockk<PlaybackController>(relaxed = true)
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CoroTheme(themeMode = AppThemeMode.DARK) {
                PlannerScreen(
                    massPlan = richMassPlan(),
                    savedTemplates = emptyList(),
                    playbackStateFlow = MutableStateFlow(PlaybackState()),
                    playbackController = controller,
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
                    onEnterLiveMode = {},
                    isMixerConnected = true,
                    availableMixerFaders = sampleFaders(),
                    automationConfig = RodeAutomationConfig(enabled = true, musicFaderIndices = setOf(6))
                )
            }
        }
        saveCurrentWindow("tablet-planner.png")
    }

    @Test
    @Config(sdk = [34], qualifiers = "w1180dp-h760dp-hdpi")
    fun generateTabletLiveDark() {
        val windowClass = WindowSizeClass.calculateFromSize(DpSize(1180.dp, 760.dp))
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CoroTheme(themeMode = AppThemeMode.DARK) {
                LiveExecutionScreen(
                    massPlan = richMassPlan(firstPlayed = true),
                    settings = AppSettings(keepScreenOnInLive = false),
                    playbackStateFlow = MutableStateFlow(
                        PlaybackState(
                            status = PlaybackStatus.PLAYING,
                            activeTrackId = 102L,
                            lastLiveTrackId = 101L,
                            title = "Gloria a Dio nell'alto dei cieli",
                            phaseLabel = "Gloria",
                            positionMs = 64_000L,
                            durationMs = 192_000L,
                            fadeOutMs = 3000,
                            isPreview = false
                        )
                    ),
                    windowSizeClass = windowClass,
                    onPlayTrack = { _, _ -> },
                    onFadeOut = {},
                    onStopImmediate = {},
                    onResetPlayedFlags = {},
                    onDismissError = {},
                    onExitLiveMode = {},
                    isMixerConnected = true
                )
            }
        }
        saveCurrentWindow("tablet-live.png")
    }
}
