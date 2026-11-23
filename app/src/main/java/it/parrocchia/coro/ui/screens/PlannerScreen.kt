package it.parrocchia.coro.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.parrocchia.coro.audio.PlaybackController
import it.parrocchia.coro.audio.PlaybackState
import it.parrocchia.coro.data.db.MassPlanWithPhases
import it.parrocchia.coro.data.db.PhaseWithTracks
import it.parrocchia.coro.data.db.PlannedTrackWithSong
import it.parrocchia.coro.domain.model.AudioFormat
import it.parrocchia.coro.domain.model.TrackSettings
import it.parrocchia.coro.ui.theme.CoroColors
import kotlinx.coroutines.flow.StateFlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlannerScreen(
    massPlan: MassPlanWithPhases?,
    playbackStateFlow: StateFlow<PlaybackState>,
    playbackController: PlaybackController,
    onUpdateTitle: (planId: Long, newTitle: String) -> Unit,
    onTogglePhase: (phaseId: Long, enabled: Boolean) -> Unit,
    onAddCustomPhase: (planId: Long, label: String) -> Unit,
    onDeleteCustomPhase: (phaseId: Long) -> Unit,
    onAddSongForPhase: (phaseId: Long, phaseName: String) -> Unit,
    onRemoveTrack: (trackId: Long) -> Unit,
    onMoveTrack: (phaseId: Long, trackId: Long, moveUp: Boolean) -> Unit,
    onSaveTrackSettings: (trackId: Long, songId: Long, settings: TrackSettings, maxDurMs: Long, saveDefault: Boolean) -> Unit,
    onResetMass: (keepEnabledPhases: Boolean) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
    onEnterLiveMode: () -> Unit
) {
    var showPhasesSheet by rememberSaveable { mutableStateOf(false) }
    var showResetDialog by rememberSaveable { mutableStateOf(false) }
    var showRenameDialog by rememberSaveable { mutableStateOf(false) }
    var editingTrackId by rememberSaveable { mutableStateOf<Long?>(null) }

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val gridState = rememberLazyStaggeredGridState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val enabledPhases = massPlan?.enabledPhases.orEmpty()
    val totalPlannedTracks = remember(enabledPhases) { enabledPhases.sumOf { it.tracks.size } }
    val editingTrack = remember(enabledPhases, editingTrackId) {
        val id = editingTrackId ?: return@remember null
        enabledPhases.asSequence()
            .flatMap { it.tracks.asSequence() }
            .firstOrNull { it.track.id == id }
    }

    Scaffold(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || event.nativeKeyEvent.repeatCount > 0) {
                    return@onPreviewKeyEvent false
                }
                if (event.isCtrlPressed && !event.isAltPressed && !event.isShiftPressed) {
                    when (event.key) {
                        Key.N -> {
                            showResetDialog = true
                            true
                        }
                        Key.O -> {
                            onOpenLibrary()
                            true
                        }
                        Key.Enter -> {
                            if (totalPlannedTracks > 0) {
                                onEnterLiveMode()
                                true
                            } else {
                                false
                            }
                        }
                        else -> false
                    }
                } else {
                    false
                }
            },
        containerColor = CoroColors.Background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f, fill = false)) {
                            Text(
                                text = massPlan?.plan?.title ?: "Programmazione Messa",
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${enabledPhases.size} fasi attive · $totalPlannedTracks brani",
                                style = MaterialTheme.typography.bodyMedium,
                                color = CoroColors.TextSecondary
                            )
                        }
                        if (massPlan != null) {
                            IconButton(onClick = { showRenameDialog = true }) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = "Rinomina messa",
                                    tint = CoroColors.TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenLibrary) {
                        Icon(Icons.Default.LibraryMusic, contentDescription = "Libreria")
                    }
                    IconButton(onClick = { showResetDialog = true }) {
                        Icon(Icons.Default.RestartAlt, contentDescription = "Nuova messa")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Impostazioni")
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CoroColors.Surface,
                    scrolledContainerColor = CoroColors.Surface
                )
            )
        },
        bottomBar = {
            Surface(
                color = CoroColors.Surface,
                tonalElevation = 8.dp,
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(
                            WindowInsets.navigationBars.union(
                                WindowInsets.displayCutout.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                            )
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { showPhasesSheet = true },
                        modifier = Modifier.height(58.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.Checklist, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Fasi")
                    }
                    Button(
                        onClick = onEnterLiveMode,
                        enabled = totalPlannedTracks > 0,
                        modifier = Modifier
                            .weight(1f)
                            .height(58.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CoroColors.Gold,
                            contentColor = CoroColors.Background
                        )
                    ) {
                        Icon(Icons.Default.PlayCircleFilled, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "AVVIA ESECUZIONE",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        if (massPlan == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text("Caricamento scaletta...", color = CoroColors.TextSecondary)
            }
        } else if (enabledPhases.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Nessuna fase attiva per questa messa",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = { showPhasesSheet = true }) {
                        Icon(Icons.Default.Checklist, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Configura fasi della messa")
                    }
                }
            }
        } else {
            val layoutDirection = LocalLayoutDirection.current
            LazyVerticalStaggeredGrid(
                state = gridState,
                columns = StaggeredGridCells.Adaptive(minSize = 340.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .consumeWindowInsets(innerPadding),
                contentPadding = PaddingValues(
                    start = innerPadding.calculateStartPadding(layoutDirection) + 16.dp,
                    top = innerPadding.calculateTopPadding() + 16.dp,
                    end = innerPadding.calculateEndPadding(layoutDirection) + 16.dp,
                    bottom = innerPadding.calculateBottomPadding() + 16.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalItemSpacing = 12.dp
            ) {
                items(
                    items = enabledPhases,
                    key = { it.phase.id },
                    contentType = { "phase_card" }
                ) { phaseWithTracks ->
                    PhasePlannerCard(
                        phaseWithTracks = phaseWithTracks,
                        onAddSong = {
                            onAddSongForPhase(phaseWithTracks.phase.id, phaseWithTracks.phase.displayName)
                        },
                        onEditTrack = { editingTrackId = it.track.id },
                        onRemoveTrack = onRemoveTrack,
                        onMoveTrack = { trackId, up ->
                            onMoveTrack(phaseWithTracks.phase.id, trackId, up)
                        }
                    )
                }
            }
        }
    }

    if (showPhasesSheet && massPlan != null) {
        ConfigurePhasesSheet(
            phases = massPlan.orderedPhases,
            onTogglePhase = onTogglePhase,
            onAddCustomPhase = { label -> onAddCustomPhase(massPlan.plan.id, label) },
            onDeleteCustomPhase = onDeleteCustomPhase,
            onDismiss = { showPhasesSheet = false }
        )
    }

    editingTrack?.let { item ->
        TrackSettingsSheet(
            song = item.song,
            initialSettings = item.track.settings,
            showSaveAsDefaultToggle = true,
            playbackStateFlow = playbackStateFlow,
            playbackController = playbackController,
            onSave = { newSettings, saveDefault ->
                onSaveTrackSettings(
                    item.track.id,
                    item.song.id,
                    newSettings,
                    item.song.durationMs,
                    saveDefault
                )
                editingTrackId = null
            },
            onDismiss = { editingTrackId = null }
        )
    }

    if (showResetDialog) {
        var keepPhases by rememberSaveable { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            containerColor = CoroColors.Surface,
            title = { Text("Preparare una nuova messa?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Verranno rimossi tutti i brani dalla scaletta corrente. " +
                            "Le impostazioni predefinite salvate sui singoli brani in libreria resteranno intatte."
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = keepPhases, onCheckedChange = { keepPhases = it })
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Mantieni le fasi attualmente attive")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onResetMass(keepPhases)
                        showResetDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CoroColors.StopRed)
                ) {
                    Text("Svuota scaletta")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Annulla")
                }
            }
        )
    }

    if (showRenameDialog && massPlan != null) {
        var draftTitle by rememberSaveable(massPlan.plan.title) { mutableStateOf(massPlan.plan.title) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            containerColor = CoroColors.Surface,
            title = { Text("Titolo della messa") },
            text = {
                OutlinedTextField(
                    value = draftTitle,
                    onValueChange = { draftTitle = it },
                    singleLine = true,
                    label = { Text("Es. Domenica di Pasqua, Messa ore 10:30") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (draftTitle.isNotBlank()) {
                            onUpdateTitle(massPlan.plan.id, draftTitle)
                            showRenameDialog = false
                        }
                    }
                ) { Text("Salva") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Annulla") }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhasePlannerCard(
    phaseWithTracks: PhaseWithTracks,
    onAddSong: () -> Unit,
    onEditTrack: (PlannedTrackWithSong) -> Unit,
    onRemoveTrack: (Long) -> Unit,
    onMoveTrack: (Long, Boolean) -> Unit
) {
    val phase = phaseWithTracks.phase
    val tracks = phaseWithTracks.orderedTracks

    Card(
        colors = CardDefaults.cardColors(containerColor = CoroColors.Surface),
        border = BorderStroke(1.dp, CoroColors.Border),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = phase.standardType.section.label.uppercase(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = CoroColors.TextSecondary
                    )
                    Text(
                        text = phase.displayName,
                        style = MaterialTheme.typography.titleLarge,
                        color = CoroColors.Gold
                    )
                }
                OutlinedButton(
                    onClick = onAddSong,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (tracks.isEmpty()) "Scegli brano" else "Aggiungi")
                }
            }

            if (tracks.isEmpty()) {
                Text(
                    text = "Nessun brano assegnato a questa fase",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CoroColors.TextSecondary,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            } else {
                tracks.forEachIndexed { idx, item ->
                    if (idx > 0) {
                        HorizontalDivider(color = CoroColors.Border.copy(alpha = 0.5f))
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(CoroColors.SurfaceElevated)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (tracks.size > 1) {
                                    Text(
                                        text = "${idx + 1}.",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = CoroColors.Gold
                                    )
                                }
                                Text(
                                    text = item.song.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = CoroColors.TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                ParamPill(
                                    text = item.song.audioFormat.shortLabel,
                                    bg = if (item.song.audioFormat == AudioFormat.MIDI) CoroColors.MidiBadge else CoroColors.AudioBadge
                                )
                                if (item.track.transposeSemitones != 0) {
                                    ParamPill(
                                        text = TrackSettings.formatTranspose(item.track.transposeSemitones),
                                        bg = CoroColors.GoldDim,
                                        fg = CoroColors.Gold
                                    )
                                }
                                if (item.track.startOffsetMs > 0L) {
                                    ParamPill(
                                        text = "Attacco ${TrackSettings.formatTimeMs(item.track.startOffsetMs)}",
                                        bg = CoroColors.SurfaceHighlight
                                    )
                                }
                                ParamPill(
                                    text = "Sfuma ${TrackSettings.formatFadeSec(item.track.fadeOutMs)}",
                                    bg = CoroColors.SurfaceHighlight
                                )
                                if (item.track.volume != 1.0f) {
                                    ParamPill(
                                        text = "Vol ${TrackSettings.formatVolumePercent(item.track.volume)}",
                                        bg = CoroColors.SurfaceHighlight
                                    )
                                }
                            }
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Row {
                                if (tracks.size > 1) {
                                    IconButton(
                                        onClick = { onMoveTrack(item.track.id, true) },
                                        enabled = idx > 0
                                    ) {
                                        Icon(Icons.Default.ArrowUpward, contentDescription = "Sposta su")
                                    }
                                    IconButton(
                                        onClick = { onMoveTrack(item.track.id, false) },
                                        enabled = idx < tracks.lastIndex
                                    ) {
                                        Icon(Icons.Default.ArrowDownward, contentDescription = "Sposta giù")
                                    }
                                }
                                IconButton(onClick = { onEditTrack(item) }) {
                                    Icon(
                                        Icons.Default.Tune,
                                        contentDescription = "Impostazioni brano",
                                        tint = CoroColors.Gold
                                    )
                                }
                                IconButton(onClick = { onRemoveTrack(item.track.id) }) {
                                    Icon(
                                        Icons.Default.DeleteOutline,
                                        contentDescription = "Rimuovi brano",
                                        tint = CoroColors.StopRedFill
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ParamPill(
    text: String,
    bg: androidx.compose.ui.graphics.Color,
    fg: androidx.compose.ui.graphics.Color = CoroColors.TextPrimary
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = fg,
            fontWeight = FontWeight.Medium
        )
    }
}
