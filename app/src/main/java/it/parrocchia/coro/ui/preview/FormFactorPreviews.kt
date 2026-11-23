package it.parrocchia.coro.ui.preview

import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
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
import it.parrocchia.coro.domain.model.AudioFormat
import it.parrocchia.coro.domain.model.StandardPhaseType
import it.parrocchia.coro.ui.screens.LiveExecutionScreen
import it.parrocchia.coro.ui.screens.SettingsScreen
import it.parrocchia.coro.ui.theme.CoroTheme

@Preview(name = "Phone", device = "spec:width=411dp,height=891dp", showBackground = true)
@Preview(name = "Phone Landscape", device = "spec:width=891dp,height=411dp", showBackground = true)
@Preview(name = "Foldable", device = "spec:width=673dp,height=841dp", showBackground = true)
@Preview(name = "Tablet", device = "spec:width=1280dp,height=800dp", showBackground = true)
@Preview(name = "Desktop", device = "spec:width=1920dp,height=1080dp", showBackground = true)
annotation class FormFactorPreviews

private fun sampleMassPlan(): MassPlanWithPhases {
    val plan = MassPlanEntity(
        id = 1L,
        title = "Domenica — Messa ore 10:30",
        dateEpochDay = 20000L
    )
    val song1 = SongEntity(
        id = 1L,
        uri = "asset://demo_tracks/01.mid",
        title = "Ingresso Organo Corale",
        fileName = "01_Ingresso.mid",
        format = AudioFormat.MIDI.name,
        durationMs = 185_000L
    )
    val song2 = SongEntity(
        id = 2L,
        uri = "asset://demo_tracks/02.mid",
        title = "Gloria Ritornello Solenne",
        fileName = "02_Gloria.mid",
        format = AudioFormat.MIDI.name,
        durationMs = 210_000L
    )
    val song3 = SongEntity(
        id = 3L,
        uri = "asset://demo_tracks/07.wav",
        title = "Comunione Meditazione",
        fileName = "07_Comunione.wav",
        format = AudioFormat.AUDIO.name,
        durationMs = 240_000L
    )
    return MassPlanWithPhases(
        plan = plan,
        phases = listOf(
            PhaseWithTracks(
                phase = MassPhaseEntity(
                    id = 1L,
                    massPlanId = 1L,
                    phaseType = StandardPhaseType.INGRESSO.name,
                    sortOrder = 0,
                    isEnabled = true
                ),
                tracks = listOf(
                    PlannedTrackWithSong(
                        track = PlannedTrackEntity(
                            id = 10L,
                            phaseId = 1L,
                            songId = 1L,
                            sortOrder = 0,
                            playedInSession = true
                        ),
                        song = song1
                    )
                )
            ),
            PhaseWithTracks(
                phase = MassPhaseEntity(
                    id = 2L,
                    massPlanId = 1L,
                    phaseType = StandardPhaseType.GLORIA.name,
                    sortOrder = 1,
                    isEnabled = true
                ),
                tracks = listOf(
                    PlannedTrackWithSong(
                        track = PlannedTrackEntity(
                            id = 11L,
                            phaseId = 2L,
                            songId = 2L,
                            sortOrder = 0,
                            transposeSemitones = 2
                        ),
                        song = song2
                    )
                )
            ),
            PhaseWithTracks(
                phase = MassPhaseEntity(
                    id = 3L,
                    massPlanId = 1L,
                    phaseType = StandardPhaseType.COMUNIONE.name,
                    sortOrder = 2,
                    isEnabled = true
                ),
                tracks = listOf(
                    PlannedTrackWithSong(
                        track = PlannedTrackEntity(
                            id = 12L,
                            phaseId = 3L,
                            songId = 3L,
                            sortOrder = 0,
                            fadeOutMs = 5000
                        ),
                        song = song3
                    )
                )
            )
        )
    )
}

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@FormFactorPreviews
@Composable
private fun LiveExecutionScreenFormFactorPreview() {
    val configuration = LocalConfiguration.current
    val windowSizeClass = WindowSizeClass.calculateFromSize(
        DpSize(configuration.screenWidthDp.dp, configuration.screenHeightDp.dp)
    )
    CoroTheme {
        LiveExecutionScreen(
            massPlan = sampleMassPlan(),
            settings = AppSettings(),
            playbackStateFlow = remember {
                MutableStateFlow(
                    PlaybackState(
                        status = PlaybackStatus.IDLE
                    )
                )
            },
            windowSizeClass = windowSizeClass,
            onPlayTrack = { _, _ -> },
            onFadeOut = {},
            onStopImmediate = {},
            onResetPlayedFlags = {},
            onDismissError = {},
            onExitLiveMode = {}
        )
    }
}

@FormFactorPreviews
@Preview(
    name = "Phone Large Font",
    device = "spec:width=390dp,height=867dp",
    fontScale = 1.6f,
    showBackground = true
)
@Composable
private fun SettingsScreenFormFactorPreview() {
    CoroTheme {
        SettingsScreen(
            settings = AppSettings(
                libraryFolderUri = "content://demo/Midi",
                libraryFolderName = "Midi",
                customSoundFontName = "OrganoCattedrale.sf2",
                customSoundFontLocalPath = "/data/OrganoCattedrale.sf2"
            ),
            isScanning = false,
            onSelectLibraryFolder = { _, _ -> },
            onRescanLibrary = {},
            onReloadDemoTracks = {},
            onImportCustomSoundFont = {},
            onResetSoundFont = {},
            onSetHoldDurationMs = {},
            onSetDefaultFadeOutMs = {},
            onSetKeepScreenOn = {},
            onBack = {}
        )
    }
}
