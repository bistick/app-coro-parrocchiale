package it.parrocchia.coro

import android.os.Bundle
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import android.view.Menu
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import it.parrocchia.coro.ui.CoroViewModel
import it.parrocchia.coro.ui.screens.LibraryScreen
import it.parrocchia.coro.ui.screens.LiveExecutionScreen
import it.parrocchia.coro.ui.screens.PlannerScreen
import it.parrocchia.coro.ui.screens.SettingsScreen
import it.parrocchia.coro.ui.theme.CoroColors
import it.parrocchia.coro.ui.theme.CoroTheme
import java.net.URLDecoder
import java.net.URLEncoder

class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val appContainer = (application as CoroApplication).container

        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            CoroTheme {
                val vm: CoroViewModel = viewModel(factory = CoroViewModel.Factory(appContainer))
                CoroRootApp(
                    viewModel = vm,
                    windowSizeClass = windowSizeClass
                )
            }
        }
    }

    override fun onProvideKeyboardShortcuts(
        data: MutableList<KeyboardShortcutGroup>?,
        menu: Menu?,
        deviceId: Int
    ) {
        data ?: return
        data.add(
            KeyboardShortcutGroup(
                "Esecuzione Live",
                listOf(
                    KeyboardShortcutInfo("Avvia prossimo / Sfuma", KeyEvent.KEYCODE_SPACE, 0),
                    KeyboardShortcutInfo("Stop immediato", KeyEvent.KEYCODE_SPACE, KeyEvent.META_SHIFT_ON),
                    KeyboardShortcutInfo("Chiudi avviso errore", KeyEvent.KEYCODE_ESCAPE, 0)
                )
            )
        )
        data.add(
            KeyboardShortcutGroup(
                "Programmazione Messa",
                listOf(
                    KeyboardShortcutInfo("Nuova messa", KeyEvent.KEYCODE_N, KeyEvent.META_CTRL_ON),
                    KeyboardShortcutInfo("Apri libreria", KeyEvent.KEYCODE_O, KeyEvent.META_CTRL_ON),
                    KeyboardShortcutInfo("Avvia esecuzione live", KeyEvent.KEYCODE_ENTER, KeyEvent.META_CTRL_ON)
                )
            )
        )
        data.add(
            KeyboardShortcutGroup(
                "Libreria",
                listOf(
                    KeyboardShortcutInfo("Cerca brano", KeyEvent.KEYCODE_F, KeyEvent.META_CTRL_ON),
                    KeyboardShortcutInfo("Aggiorna libreria", KeyEvent.KEYCODE_R, KeyEvent.META_CTRL_ON),
                    KeyboardShortcutInfo("Scegli cartella", KeyEvent.KEYCODE_O, KeyEvent.META_CTRL_ON),
                    KeyboardShortcutInfo("Pulisci ricerca", KeyEvent.KEYCODE_ESCAPE, 0)
                )
            )
        )
    }
}

private object Routes {
    const val PLANNER = "planner"
    const val LIVE = "live"
    const val SETTINGS = "settings"
    const val LIBRARY_BASE = "library"
    const val LIBRARY_ROUTE = "library?phaseId={phaseId}&phaseName={phaseName}"

    fun libraryForPhase(phaseId: Long, phaseName: String): String {
        val encoded = URLEncoder.encode(phaseName, Charsets.UTF_8.name())
        return "$LIBRARY_BASE?phaseId=$phaseId&phaseName=$encoded"
    }
}

@Composable
private fun CoroRootApp(
    viewModel: CoroViewModel,
    windowSizeClass: WindowSizeClass
) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    val massPlan by viewModel.activeMassPlan.collectAsStateWithLifecycle()
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val toastMessage by viewModel.toastMessage.collectAsStateWithLifecycle()

    LaunchedEffect(toastMessage) {
        val msg = toastMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.consumeToast()
    }

    Scaffold(
        containerColor = CoroColors.Background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { outerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(outerPadding)
        ) {
            NavHost(
                navController = navController,
                startDestination = Routes.PLANNER
            ) {
                composable(Routes.PLANNER) {
                    PlannerScreen(
                        massPlan = massPlan,
                        playbackState = playbackState,
                        playbackController = viewModel.playbackController,
                        onUpdateTitle = viewModel::updateMassTitle,
                        onTogglePhase = viewModel::togglePhase,
                        onAddCustomPhase = viewModel::addCustomPhase,
                        onDeleteCustomPhase = viewModel::deleteCustomPhase,
                        onAddSongForPhase = { phaseId, phaseName ->
                            navController.navigate(Routes.libraryForPhase(phaseId, phaseName))
                        },
                        onRemoveTrack = viewModel::removeTrack,
                        onMoveTrack = viewModel::moveTrack,
                        onSaveTrackSettings = viewModel::savePlannedTrackSettings,
                        onResetMass = viewModel::resetMass,
                        onOpenLibrary = { navController.navigate(Routes.LIBRARY_BASE) },
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                        onEnterLiveMode = {
                            viewModel.playbackController.stopPreviewIfActive()
                            navController.navigate(Routes.LIVE)
                        }
                    )
                }

                composable(Routes.LIVE) {
                    LiveExecutionScreen(
                        massPlan = massPlan,
                        settings = settings,
                        playbackState = playbackState,
                        windowSizeClass = windowSizeClass,
                        onPlayTrack = viewModel::playLiveTrack,
                        onFadeOut = viewModel::fadeOutLive,
                        onStopImmediate = viewModel::stopImmediateLive,
                        onResetPlayedFlags = viewModel::resetSessionPlayed,
                        onDismissError = viewModel::dismissPlaybackError,
                        onExitLiveMode = { navController.popBackStack() }
                    )
                }

                composable(
                    route = Routes.LIBRARY_ROUTE,
                    arguments = listOf(
                        navArgument("phaseId") {
                            type = NavType.LongType
                            defaultValue = -1L
                        },
                        navArgument("phaseName") {
                            type = NavType.StringType
                            defaultValue = ""
                        }
                    )
                ) { backStackEntry ->
                    val phaseId = backStackEntry.arguments?.getLong("phaseId") ?: -1L
                    val rawPhaseName = backStackEntry.arguments?.getString("phaseName").orEmpty()
                    val phaseName = if (rawPhaseName.isNotBlank()) {
                        runCatching { URLDecoder.decode(rawPhaseName, Charsets.UTF_8.name()) }.getOrDefault(rawPhaseName)
                    } else null

                    LibraryScreen(
                        songs = songs,
                        settings = settings,
                        isScanning = isScanning,
                        pickerPhaseName = if (phaseId > 0L) phaseName else null,
                        playbackState = playbackState,
                        playbackController = viewModel.playbackController,
                        onSelectFolder = viewModel::selectLibraryFolder,
                        onRescanFolder = viewModel::rescanLibraryFolder,
                        onPickSongForPhase = if (phaseId > 0L) { song ->
                            viewModel.addSongToPhase(phaseId, song)
                            navController.popBackStack()
                        } else null,
                        onSaveSongDefaults = { song, newSettings ->
                            viewModel.saveSongDefaultsOnly(song.id, newSettings, song.durationMs)
                        },
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        settings = settings,
                        isScanning = isScanning,
                        onSelectLibraryFolder = viewModel::selectLibraryFolder,
                        onRescanLibrary = viewModel::rescanLibraryFolder,
                        onReloadDemoTracks = viewModel::ensureDemoTracks,
                        onImportCustomSoundFont = viewModel::importCustomSoundFont,
                        onResetSoundFont = viewModel::resetSoundFont,
                        onSetHoldDurationMs = viewModel::setHoldDurationMs,
                        onSetDefaultFadeOutMs = viewModel::setDefaultFadeOutMs,
                        onSetKeepScreenOn = viewModel::setKeepScreenOnInLive,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }
    }
}
