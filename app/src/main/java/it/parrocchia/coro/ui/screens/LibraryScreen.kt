package it.parrocchia.coro.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import it.parrocchia.coro.audio.PlaybackController
import it.parrocchia.coro.audio.PlaybackState
import it.parrocchia.coro.data.db.SongEntity
import it.parrocchia.coro.data.prefs.AppSettings
import it.parrocchia.coro.domain.model.AudioFormat
import it.parrocchia.coro.domain.model.TrackSettings
import it.parrocchia.coro.ui.theme.CoroColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    songs: List<SongEntity>,
    settings: AppSettings,
    isScanning: Boolean,
    pickerPhaseName: String?,
    playbackState: PlaybackState,
    playbackController: PlaybackController,
    onSelectFolder: (android.net.Uri, String?) -> Unit,
    onRescanFolder: () -> Unit,
    onPickSongForPhase: ((SongEntity) -> Unit)?,
    onSaveSongDefaults: (SongEntity, TrackSettings) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var formatFilterName by rememberSaveable { mutableStateOf<String?>(null) }
    var folderFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var editingSongId by rememberSaveable { mutableStateOf<Long?>(null) }

    val formatFilter = remember(formatFilterName) {
        formatFilterName?.let { AudioFormat.fromCode(it) }
    }
    val editingSong = remember(songs, editingSongId) {
        val id = editingSongId ?: return@remember null
        songs.firstOrNull { it.id == id }
    }

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val gridState = rememberLazyGridState()
    val filterRowState = rememberLazyListState()
    val containerFocusRequester = remember { FocusRequester() }
    val searchFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        containerFocusRequester.requestFocus()
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            val name = DocumentFile.fromTreeUri(context, uri)?.name
            onSelectFolder(uri, name)
        }
    }

    val folders = remember(songs) {
        songs.map { it.relativeFolder.ifBlank { "Principale" } }.distinct().sorted()
    }

    val filteredSongs = remember(songs, query, formatFilter, folderFilter) {
        val q = query.trim().lowercase()
        songs.filter { song ->
            val matchesQuery = q.isEmpty() ||
                song.title.lowercase().contains(q) ||
                song.fileName.lowercase().contains(q) ||
                song.relativeFolder.lowercase().contains(q)
            val matchesFormat = formatFilter == null || song.audioFormat == formatFilter
            val songFolder = song.relativeFolder.ifBlank { "Principale" }
            val matchesFolder = folderFilter == null || songFolder == folderFilter
            matchesQuery && matchesFormat && matchesFolder
        }
    }

    Scaffold(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .focusRequester(containerFocusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || event.nativeKeyEvent.repeatCount > 0) {
                    return@onPreviewKeyEvent false
                }
                when {
                    event.isCtrlPressed && !event.isAltPressed && !event.isShiftPressed && event.key == Key.F -> {
                        searchFocusRequester.requestFocus()
                        true
                    }
                    ((event.isCtrlPressed && !event.isAltPressed && !event.isShiftPressed && event.key == Key.R) ||
                        event.key == Key.F5) -> {
                        if (settings.libraryFolderUri != null && !isScanning) {
                            onRescanFolder()
                            true
                        } else {
                            false
                        }
                    }
                    event.isCtrlPressed && !event.isAltPressed && !event.isShiftPressed && event.key == Key.O -> {
                        if (!isScanning) {
                            folderPickerLauncher.launch(null)
                            true
                        } else {
                            false
                        }
                    }
                    event.key == Key.Escape && query.isNotEmpty() -> {
                        query = ""
                        true
                    }
                    else -> false
                }
            },
        containerColor = CoroColors.Background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (pickerPhaseName != null) "Scegli brano" else "Libreria musicale",
                            fontWeight = FontWeight.Bold
                        )
                        if (pickerPhaseName != null) {
                            Text(
                                text = "Per: $pickerPhaseName",
                                style = MaterialTheme.typography.bodyMedium,
                                color = CoroColors.Gold
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                    }
                },
                actions = {
                    if (settings.libraryFolderUri != null) {
                        IconButton(onClick = onRescanFolder, enabled = !isScanning) {
                            Icon(Icons.Default.Refresh, contentDescription = "Aggiorna libreria")
                        }
                    }
                    IconButton(onClick = { folderPickerLauncher.launch(null) }, enabled = !isScanning) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "Scegli cartella")
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Stato cartella libreria
            Card(
                colors = CardDefaults.cardColors(containerColor = CoroColors.Surface),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (settings.libraryFolderName != null)
                                "Cartella: ${settings.libraryFolderName}"
                            else
                                "Nessuna cartella collegata (attivi brani di prova)",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${songs.size} brani indicizzati (.mid, .kar, .mp3, .wav)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = CoroColors.TextSecondary
                        )
                    }
                    if (isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            color = CoroColors.Gold
                        )
                    } else {
                        OutlinedButton(onClick = { folderPickerLauncher.launch(null) }) {
                            Text(if (settings.libraryFolderUri == null) "Collega cartella" else "Cambia")
                        }
                    }
                }
            }

            // Ricerca rapida
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Cerca canto o sottocartella...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(searchFocusRequester)
            )

            // Filtri formato e sottocartelle
            LazyRow(
                state = filterRowState,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item(key = "filter_all") {
                    FilterChip(
                        selected = formatFilter == null,
                        onClick = { formatFilterName = null },
                        label = { Text("Tutti (${songs.size})") }
                    )
                }
                item(key = "filter_midi") {
                    FilterChip(
                        selected = formatFilter == AudioFormat.MIDI,
                        onClick = {
                            formatFilterName = if (formatFilter == AudioFormat.MIDI) null else AudioFormat.MIDI.name
                        },
                        label = { Text("MIDI") }
                    )
                }
                item(key = "filter_audio") {
                    FilterChip(
                        selected = formatFilter == AudioFormat.AUDIO,
                        onClick = {
                            formatFilterName = if (formatFilter == AudioFormat.AUDIO) null else AudioFormat.AUDIO.name
                        },
                        label = { Text("MP3 / Audio") }
                    )
                }
                if (folders.size > 1) {
                    items(folders, key = { "folder_$it" }) { folder ->
                        FilterChip(
                            selected = folderFilter == folder,
                            onClick = { folderFilter = if (folderFilter == folder) null else folder },
                            label = { Text(folder) }
                        )
                    }
                }
            }

            if (filteredSongs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Nessun brano corrisponde alla ricerca.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = CoroColors.TextSecondary
                    )
                }
            } else {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = 320.dp),
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(filteredSongs, key = { it.id }) { song ->
                        LibrarySongRow(
                            song = song,
                            isPickerMode = onPickSongForPhase != null,
                            onClick = {
                                if (onPickSongForPhase != null) {
                                    onPickSongForPhase(song)
                                } else {
                                    editingSongId = song.id
                                }
                            },
                            onEditDefaults = { editingSongId = song.id }
                        )
                    }
                }
            }
        }
    }

    editingSong?.let { song ->
        TrackSettingsSheet(
            song = song,
            initialSettings = song.defaultSettings,
            showSaveAsDefaultToggle = false,
            playbackState = playbackState,
            playbackController = playbackController,
            onSave = { newSettings, _ ->
                onSaveSongDefaults(song, newSettings)
                editingSongId = null
            },
            onDismiss = { editingSongId = null }
        )
    }
}

@Composable
private fun LibrarySongRow(
    song: SongEntity,
    isPickerMode: Boolean,
    onClick: () -> Unit,
    onEditDefaults: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = CoroColors.Surface),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Badge formato
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (song.audioFormat == AudioFormat.MIDI) CoroColors.MidiBadge else CoroColors.AudioBadge
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = song.audioFormat.shortLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = CoroColors.TextPrimary
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = CoroColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val meta = buildList {
                    if (song.relativeFolder.isNotBlank()) add(song.relativeFolder)
                    if (song.durationMs > 0L) add(TrackSettings.formatTimeMs(song.durationMs))
                    if (song.defaultTransposeSemitones != 0) add(TrackSettings.formatTranspose(song.defaultTransposeSemitones))
                    if (song.defaultStartOffsetMs > 0L) add("Attacco ${TrackSettings.formatTimeMs(song.defaultStartOffsetMs)}")
                    add("Sfuma ${TrackSettings.formatFadeSec(song.defaultFadeOutMs)}")
                }.joinToString(" · ")
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodyMedium,
                    color = CoroColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (isPickerMode) {
                Icon(
                    imageVector = Icons.Default.AddCircleOutline,
                    contentDescription = "Aggiungi alla fase",
                    tint = CoroColors.Gold,
                    modifier = Modifier.padding(start = 8.dp)
                )
            } else {
                IconButton(onClick = onEditDefaults) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Impostazioni predefinite brano",
                        tint = CoroColors.Gold
                    )
                }
            }
        }
    }
}
