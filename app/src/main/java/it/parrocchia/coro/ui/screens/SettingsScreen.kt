package it.parrocchia.coro.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import it.parrocchia.coro.BuildConfig
import it.parrocchia.coro.data.prefs.AppSettings
import it.parrocchia.coro.domain.mixer.RodeAutomationConfig
import it.parrocchia.coro.domain.mixer.RodeVirtualFader
import it.parrocchia.coro.domain.model.AppThemeMode
import it.parrocchia.coro.domain.model.AudioOutputRoute
import it.parrocchia.coro.domain.model.AudioOutputStatus
import it.parrocchia.coro.domain.model.TrackSettings
import it.parrocchia.coro.ui.components.AudioOutputSelectorCard
import it.parrocchia.coro.ui.components.HoldToConfirmSurface
import it.parrocchia.coro.ui.components.MixerMuteAutomationCard
import it.parrocchia.coro.ui.theme.CoroColors
import kotlin.math.roundToInt

private val CompactButtonPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)

private data class RecommendedSoundFont(
    val name: String,
    val sizeLabel: String,
    val description: String,
    val downloadUrl: String
)

private val RecommendedSoundFonts = listOf(
    RecommendedSoundFont(
        name = "GeneralUser GS",
        sizeLabel = "31 MB · .sf2",
        description = "Leggero e bilanciato — sfrutta i modulatori di FluidSynth",
        downloadUrl = "https://github.com/mrbumpy409/GeneralUser-GS/raw/main/GeneralUser-GS.sf2"
    ),
    RecommendedSoundFont(
        name = "MuseScore General",
        sizeLabel = "38 MB · .sf3",
        description = "Suoni acustici e orchestrali ricchi in formato compatto (MIT)",
        downloadUrl = "https://ftp.osuosl.org/pub/musescore/soundfont/MuseScore_General/MuseScore_General.sf3"
    ),
    RecommendedSoundFont(
        name = "FluidR3 GM",
        sizeLabel = "142 MB · .sf2",
        description = "Classico MIT caldo su organo, piano e archi",
        downloadUrl = "https://github.com/pianobooster/fluid-soundfont/releases/download/v3.1/FluidR3_GM.sf2"
    )
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    isScanning: Boolean,
    onSelectLibraryFolder: (Uri, String?) -> Unit,
    onRescanLibrary: () -> Unit,
    onReloadDemoTracks: () -> Unit,
    onImportCustomSoundFont: (Uri) -> Unit,
    onResetSoundFont: () -> Unit,
    onSetHoldDurationMs: (Int) -> Unit,
    onSetDefaultFadeOutMs: (Int) -> Unit,
    onSetKeepScreenOn: (Boolean) -> Unit,
    onBack: () -> Unit,
    onSetThemeMode: (AppThemeMode) -> Unit = {},
    onOpenMixer: () -> Unit = {},
    isMixerConnected: Boolean = false,
    availableMixerFaders: List<RodeVirtualFader> = emptyList(),
    onUpdateRodeAutomationConfig: (RodeAutomationConfig) -> Unit = {},
    onMuteAllRodeFaders: (() -> Unit)? = null,
    audioOutputStatus: AudioOutputStatus = AudioOutputStatus(),
    onSelectAudioOutputRoute: (AudioOutputRoute) -> Unit = {},
    onRefreshAudioOutputDevices: () -> Unit = {}
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var testTriggers by rememberSaveable { mutableIntStateOf(0) }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val gridState = rememberLazyStaggeredGridState()

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val name = DocumentFile.fromTreeUri(context, uri)?.name
            onSelectLibraryFolder(uri, name)
        }
    }

    val sf2Picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onImportCustomSoundFont(uri)
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CoroColors.Background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Impostazioni",
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CoroColors.Surface,
                    scrolledContainerColor = CoroColors.Surface
                )
            )
        }
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        LazyVerticalStaggeredGrid(
            state = gridState,
            columns = StaggeredGridCells.Adaptive(minSize = 360.dp),
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding),
            contentPadding = PaddingValues(
                start = innerPadding.calculateStartPadding(layoutDirection) + 12.dp,
                top = innerPadding.calculateTopPadding() + 14.dp,
                end = innerPadding.calculateEndPadding(layoutDirection) + 12.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalItemSpacing = 12.dp
        ) {
            // 1. Cartella Libreria Musicale
            item(key = "library", contentType = "settings_card") {
                SettingsCard(title = "Libreria musicale") {
                    Text(
                        text = settings.libraryFolderName?.let { "Cartella attiva: $it" }
                            ?: "Nessuna cartella collegata (in uso i brani di prova)",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Copia la cartella MIDI/KAR/MP3 sul dispositivo e selezionala qui. Anche le sottocartelle vengono indicizzate.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CoroColors.TextSecondary
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { folderPicker.launch(null) },
                            enabled = !isScanning,
                            contentPadding = CompactButtonPadding
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Scegli cartella",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (settings.libraryFolderUri != null) {
                            OutlinedButton(
                                onClick = onRescanLibrary,
                                enabled = !isScanning,
                                contentPadding = CompactButtonPadding
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Aggiorna",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        OutlinedButton(
                            onClick = onReloadDemoTracks,
                            contentPadding = CompactButtonPadding
                        ) {
                            Text(
                                text = "Brani di prova",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // 2. Banco suoni MIDI (FluidSynth .sf2 / .sf3)
            item(key = "soundfont", contentType = "settings_card") {
                SettingsCard(title = "Sintetizzatore FluidSynth (.sf2 / .sf3)") {
                    Text(
                        text = settings.customSoundFontName?.let { "Banco personalizzato: $it" }
                            ?: "Banco attivo: General MIDI (TimGM6mb.sf2)",
                        style = MaterialTheme.typography.bodyLarge,
                        color = CoroColors.Gold
                    )
                    Text(
                        text = "Motore FluidSynth 2.6 con riverbero, chorus e 48 kHz nativo. Carica un file .sf2 o .sf3 per cambiare timbrica di organo e strumenti.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CoroColors.TextSecondary
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { sf2Picker.launch(arrayOf("*/*")) },
                            contentPadding = CompactButtonPadding
                        ) {
                            Icon(
                                imageVector = Icons.Default.MusicNote,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Carica .sf2/.sf3",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (settings.customSoundFontLocalPath != null) {
                            OutlinedButton(
                                onClick = onResetSoundFont,
                                contentPadding = CompactButtonPadding
                            ) {
                                Text(
                                    text = "Ripristina",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    HorizontalDivider(
                        color = CoroColors.Border.copy(alpha = 0.6f),
                        modifier = Modifier.padding(vertical = 2.dp)
                    )

                    Text(
                        text = "Banchi gratuiti consigliati (scarica e poi premi Carica .sf2/.sf3):",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CoroColors.TextSecondary
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        RecommendedSoundFonts.forEach { sf ->
                            Surface(
                                onClick = {
                                    runCatching { uriHandler.openUri(sf.downloadUrl) }
                                },
                                color = CoroColors.SurfaceElevated,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            Text(
                                                text = sf.name,
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.SemiBold,
                                                color = CoroColors.TextPrimary
                                            )
                                            Text(
                                                text = sf.sizeLabel,
                                                style = MaterialTheme.typography.labelLarge,
                                                color = CoroColors.Gold,
                                                maxLines = 1
                                            )
                                        }
                                        Text(
                                            text = sf.description,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = CoroColors.TextSecondary
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.Default.Download,
                                        contentDescription = "Scarica ${sf.name}",
                                        tint = CoroColors.Gold,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. Protezione tocchi accidentali (durata pressione prolungata + area test)
            item(key = "hold_protection", contentType = "settings_card") {
                SettingsCard(title = "Protezione tocchi accidentali") {
                    SettingSliderHeader(
                        label = "Durata pressione",
                        valueText = "${settings.holdDurationMs} ms"
                    )
                    Slider(
                        value = settings.holdDurationMs.toFloat(),
                        onValueChange = { v ->
                            val step = ((v / 50f).roundToInt() * 50).coerceIn(
                                AppSettings.MIN_HOLD_DURATION_MS,
                                AppSettings.MAX_HOLD_DURATION_MS
                            )
                            onSetHoldDurationMs(step)
                        },
                        valueRange = AppSettings.MIN_HOLD_DURATION_MS.toFloat()..AppSettings.MAX_HOLD_DURATION_MS.toFloat()
                    )
                    Text(
                        text = "Prova il tempo di pressione prima della messa:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CoroColors.TextSecondary
                    )
                    HoldToConfirmSurface(
                        holdDurationMs = settings.holdDurationMs,
                        onConfirmed = { testTriggers++ },
                        containerColor = CoroColors.PlayGreen,
                        progressFillColor = CoroColors.PlayGreenFill,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 58.dp)
                    ) { _ ->
                        Row(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.TouchApp,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (testTriggers == 0)
                                    "Prova pressione (${settings.holdDurationMs} ms)"
                                else
                                    "Attivato ($testTriggers) — riprova",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            // 4. Aspetto, Default nuovi brani & Schermo
            item(key = "general_prefs", contentType = "settings_card") {
                SettingsCard(title = "Preferenze generali") {
                    Text(
                        text = "Tema dell'app",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = settings.themeMode.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = CoroColors.TextSecondary
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AppThemeMode.entries.forEach { mode ->
                            val selected = settings.themeMode == mode
                            val icon = when (mode) {
                                AppThemeMode.DARK -> Icons.Default.DarkMode
                                AppThemeMode.LIGHT -> Icons.Default.LightMode
                                AppThemeMode.SYSTEM -> Icons.Default.BrightnessAuto
                            }
                            FilterChip(
                                selected = selected,
                                onClick = { onSetThemeMode(mode) },
                                label = {
                                    Text(
                                        text = mode.displayLabel,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = CoroColors.GoldDim,
                                    selectedLabelColor = CoroColors.Gold,
                                    selectedLeadingIconColor = CoroColors.Gold
                                )
                            )
                        }
                    }

                    HorizontalDivider(
                        color = CoroColors.Border.copy(alpha = 0.6f),
                        modifier = Modifier.padding(vertical = 2.dp)
                    )

                    SettingSliderHeader(
                        label = "Sfumatura predefinita",
                        valueText = TrackSettings.formatFadeSec(settings.defaultFadeOutMs)
                    )
                    Slider(
                        value = settings.defaultFadeOutMs.toFloat(),
                        onValueChange = { v ->
                            val snapped = ((v / 500f).roundToInt() * 500).coerceIn(
                                TrackSettings.MIN_FADE_OUT_MS,
                                TrackSettings.MAX_FADE_OUT_MS
                            )
                            onSetDefaultFadeOutMs(snapped)
                        },
                        valueRange = TrackSettings.MIN_FADE_OUT_MS.toFloat()..TrackSettings.MAX_FADE_OUT_MS.toFloat()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Schermo sempre acceso in Esecuzione",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Impedisce lo spegnimento automatico del display durante la messa",
                                style = MaterialTheme.typography.bodyMedium,
                                color = CoroColors.TextSecondary
                            )
                        }
                        Switch(
                            checked = settings.keepScreenOnInLive,
                            onCheckedChange = onSetKeepScreenOn
                        )
                    }
                }
            }

            // 5. Uscita audio (Bluetooth / USB Audio / Altoparlante)
            item(key = "audio_output", contentType = "settings_card") {
                AudioOutputSelectorCard(
                    status = audioOutputStatus,
                    onSelectRoute = onSelectAudioOutputRoute,
                    onRefreshDevices = onRefreshAudioOutputDevices
                )
            }

            // 6. Automazione Muto (RØDECaster Pro II)
            item(key = "rode_mute_automation", contentType = "settings_card") {
                MixerMuteAutomationCard(
                    config = settings.rodeAutomationConfig,
                    assignedFaders = availableMixerFaders,
                    isConnected = isMixerConnected,
                    onUpdateConfig = onUpdateRodeAutomationConfig,
                    onMuteAllFaders = onMuteAllRodeFaders
                )
            }

            // 7. Controllo Mixer RØDECaster Pro II
            item(key = "rode_mixer", contentType = "settings_card") {
                SettingsCard(title = "Mixer RØDECaster Pro II") {
                    Text(
                        text = if (isMixerConnected) {
                            "Connesso via USB 1 · Controllo Muto attivo."
                        } else {
                            "Collega il RØDECaster Pro II sulla porta USB 1 per gestire i Muto dei canali e l'automazione live."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isMixerConnected) CoroColors.PlayedAccent else CoroColors.TextSecondary
                    )
                    Button(
                        onClick = onOpenMixer,
                        contentPadding = CompactButtonPadding,
                        modifier = Modifier.heightIn(min = 44.dp)
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Apri controllo mixer", maxLines = 1)
                    }
                }
            }

            // 8. Info
            item(key = "info", contentType = "settings_card") {
                SettingsCard(title = "Informazioni") {
                    Text(
                        text = "Coro v${BuildConfig.VERSION_NAME} · 100% offline",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Motore MIDI FluidSynth (SF2/SF3) · Preset di Messa liturgici e personalizzati · Automazione Muto RØDECaster Pro II via USB.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CoroColors.TextSecondary
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingSliderHeader(
    label: String,
    valueText: String
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(end = 12.dp)
        )
        Text(
            text = valueText,
            style = MaterialTheme.typography.titleMedium,
            color = CoroColors.Gold,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CoroColors.Surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = CoroColors.Gold
            )
            content()
        }
    }
}
