package it.parrocchia.coro.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import it.parrocchia.coro.BuildConfig
import it.parrocchia.coro.data.prefs.AppSettings
import it.parrocchia.coro.domain.model.TrackSettings
import it.parrocchia.coro.ui.components.HoldToConfirmSurface
import it.parrocchia.coro.ui.theme.CoroColors
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
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
    onBack: () -> Unit
) {
    val context = LocalContext.current
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
                title = { Text("Impostazioni", fontWeight = FontWeight.Bold) },
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
                start = innerPadding.calculateStartPadding(layoutDirection) + 16.dp,
                top = innerPadding.calculateTopPadding() + 16.dp,
                end = innerPadding.calculateEndPadding(layoutDirection) + 16.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalItemSpacing = 14.dp
        ) {
            // 1. Cartella Libreria Musicale
            item(key = "library", contentType = "settings_card") {
                SettingsCard(title = "Libreria musicale sul dispositivo") {
                    Text(
                        text = settings.libraryFolderName?.let { "Cartella attiva: $it" }
                            ?: "Nessuna cartella collegata (in uso i brani di prova integrati)",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Copia la cartella di file MIDI/KAR/MP3 dal PC sul dispositivo e selezionala qui. Anche le sottocartelle verranno indicizzate.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CoroColors.TextSecondary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { folderPicker.launch(null) },
                            enabled = !isScanning
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Scegli cartella")
                        }
                        if (settings.libraryFolderUri != null) {
                            OutlinedButton(
                                onClick = onRescanLibrary,
                                enabled = !isScanning
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Aggiorna")
                            }
                        }
                    }
                    OutlinedButton(onClick = onReloadDemoTracks) {
                        Text("Assicura brani di prova in libreria")
                    }
                }
            }

            // 2. Banco suoni MIDI (SoundFont .sf2)
            item(key = "soundfont", contentType = "settings_card") {
                SettingsCard(title = "Sintetizzatore MIDI (SoundFont .sf2)") {
                    Text(
                        text = settings.customSoundFontName?.let { "Banco suoni personalizzato: $it" }
                            ?: "Banco suoni attivo: General MIDI integrato (TimGM6mb.sf2)",
                        style = MaterialTheme.typography.bodyLarge,
                        color = CoroColors.Gold
                    )
                    Text(
                        text = "Puoi usare il banco General MIDI pronto all'uso oppure caricare un file .sf2 personalizzato per cambiare la timbrica di organo, piano e strumenti.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CoroColors.TextSecondary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { sf2Picker.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Default.MusicNote, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Carica .sf2")
                        }
                        if (settings.customSoundFontLocalPath != null) {
                            OutlinedButton(onClick = onResetSoundFont) {
                                Text("Ripristina integrato")
                            }
                        }
                    }
                }
            }

            // 3. Protezione tocchi accidentali (durata pressione prolungata + area test)
            item(key = "hold_protection", contentType = "settings_card") {
                SettingsCard(title = "Protezione tocchi accidentali") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Durata pressione prolungata", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "${settings.holdDurationMs} ms",
                            style = MaterialTheme.typography.titleMedium,
                            color = CoroColors.Gold
                        )
                    }
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
                        text = "Prova qui sotto il tempo di pressione prima della messa:",
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
                            .height(62.dp)
                    ) { _ ->
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.TouchApp, contentDescription = null, tint = Color.White)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (testTriggers == 0)
                                        "Tieni premuto per provare (${settings.holdDurationMs} ms)"
                                    else
                                        "Attivato! ($testTriggers) — riprova",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // 4. Default nuovi brani & Schermo
            item(key = "general_prefs", contentType = "settings_card") {
                SettingsCard(title = "Preferenze generali") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Sfumatura predefinita nuovi brani", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = TrackSettings.formatFadeSec(settings.defaultFadeOutMs),
                            style = MaterialTheme.typography.titleMedium,
                            color = CoroColors.Gold
                        )
                    }
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

            // 5. Info e predisposizioni future
            item(key = "info", contentType = "settings_card") {
                SettingsCard(title = "Informazioni") {
                    Text(
                        text = "Coro v${BuildConfig.VERSION_NAME} · 100% offline",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Architettura predisposta per future estensioni: Template di Messa salvabili e controllo mixer RØDECaster Pro II.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CoroColors.TextSecondary
                    )
                }
            }
        }
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
                .padding(16.dp),
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
