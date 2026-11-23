package it.parrocchia.coro.ui.screens

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.parrocchia.coro.audio.PlaybackState
import it.parrocchia.coro.audio.PlaybackStatus
import it.parrocchia.coro.data.db.MassPlanWithPhases
import it.parrocchia.coro.data.db.PlannedTrackWithSong
import it.parrocchia.coro.data.prefs.AppSettings
import it.parrocchia.coro.domain.model.TrackSettings
import it.parrocchia.coro.ui.components.HoldToConfirmSurface
import it.parrocchia.coro.ui.theme.CoroColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private data class LiveTrackEntry(
    val phaseLabel: String,
    val indexInPhase: Int,
    val totalInPhase: Int,
    val item: PlannedTrackWithSong
)

@Composable
fun LiveExecutionScreen(
    massPlan: MassPlanWithPhases?,
    settings: AppSettings,
    playbackStateFlow: StateFlow<PlaybackState>,
    windowSizeClass: WindowSizeClass,
    onPlayTrack: (phaseLabel: String, item: PlannedTrackWithSong) -> Unit,
    onFadeOut: () -> Unit,
    onStopImmediate: () -> Unit,
    onResetPlayedFlags: (planId: Long) -> Unit,
    onDismissError: () -> Unit,
    onExitLiveMode: () -> Unit
) {
    val context = LocalContext.current
    var exitHintVisible by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    // Stato playback mantenuto come State<PlaybackState> per non ricomporre l'intera schermata
    // ad ogni tick di avanzamento (positionMs); i valori a bassa frequenza vengono derivati.
    val playbackStateHolder = playbackStateFlow.collectAsStateWithLifecycle()
    val isLiveBusy by remember {
        derivedStateOf {
            val s = playbackStateHolder.value
            !s.isPreview && s.isBusy
        }
    }
    val isLivePlaying by remember {
        derivedStateOf {
            val s = playbackStateHolder.value
            !s.isPreview && s.status == PlaybackStatus.PLAYING
        }
    }
    val isLiveFading by remember {
        derivedStateOf {
            val s = playbackStateHolder.value
            !s.isPreview && s.status == PlaybackStatus.FADING_OUT
        }
    }
    val activeLiveTrackId by remember {
        derivedStateOf {
            val s = playbackStateHolder.value
            if (!s.isPreview && s.isBusy) s.activeTrackId else null
        }
    }
    val liveErrorMessage by remember {
        derivedStateOf {
            val s = playbackStateHolder.value
            if (s.status == PlaybackStatus.ERROR) s.errorMessage else null
        }
    }

    // Mantieni schermo sempre acceso durante la Messa
    DisposableEffect(settings.keepScreenOnInLive) {
        val window = (context as? Activity)?.window
        if (settings.keepScreenOnInLive) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Protezione dal tasto Indietro di sistema
    BackHandler {
        exitHintVisible = true
    }

    if (exitHintVisible) {
        LaunchedEffect(Unit) {
            delay(3000L)
            exitHintVisible = false
        }
    }

    // Monitor periodico del volume multimediale + focus iniziale in un singolo LaunchedEffect
    var mediaVolumePercent by remember { mutableIntStateOf(readMediaVolumePercent(context)) }
    LaunchedEffect(context) {
        launch {
            focusRequester.requestFocus()
        }
        launch {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            while (isActive) {
                mediaVolumePercent = readMediaVolumePercent(audioManager)
                delay(1500L)
            }
        }
    }

    val liveEntries = remember(massPlan) {
        massPlan?.enabledPhases.orEmpty().flatMap { phaseWithTracks ->
            val ordered = phaseWithTracks.orderedTracks
            ordered.mapIndexed { idx, item ->
                LiveTrackEntry(
                    phaseLabel = phaseWithTracks.phase.displayName,
                    indexInPhase = idx + 1,
                    totalInPhase = ordered.size,
                    item = item
                )
            }
        }
    }

    val nextEntry = remember(liveEntries) {
        liveEntries.firstOrNull { !it.item.track.playedInSession } ?: liveEntries.firstOrNull()
    }
    val hasPlayedAny = remember(liveEntries) {
        liveEntries.any { it.item.track.playedInSession }
    }

    val isWideLayout = windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact
    val isCompactHeight = windowSizeClass.heightSizeClass == WindowHeightSizeClass.Compact
    val playbackStateProvider = remember(playbackStateHolder) { { playbackStateHolder.value } }

    Scaffold(
        modifier = Modifier
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || event.nativeKeyEvent.repeatCount > 0) {
                    return@onPreviewKeyEvent false
                }
                when {
                    event.key == Key.Spacebar && event.isShiftPressed && !event.isCtrlPressed && !event.isAltPressed -> {
                        if (isLiveBusy) {
                            onStopImmediate()
                            true
                        } else {
                            false
                        }
                    }
                    event.key == Key.Spacebar && !event.isShiftPressed && !event.isCtrlPressed && !event.isAltPressed -> {
                        when {
                            isLivePlaying -> {
                                onFadeOut()
                                true
                            }
                            !isLiveBusy && nextEntry != null -> {
                                onPlayTrack(nextEntry.phaseLabel, nextEntry.item)
                                true
                            }
                            else -> false
                        }
                    }
                    event.key == Key.Escape -> {
                        if (liveErrorMessage != null) {
                            onDismissError()
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            },
        containerColor = CoroColors.Background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            LiveTopHeader(
                title = massPlan?.plan?.title ?: "Esecuzione Live",
                mediaVolumePercent = mediaVolumePercent,
                holdDurationMs = settings.holdDurationMs,
                isLiveBusy = isLiveBusy,
                showExitHint = exitHintVisible,
                hasPlayedAny = hasPlayedAny,
                onResetPlayed = { massPlan?.plan?.id?.let(onResetPlayedFlags) },
                onExitLiveMode = onExitLiveMode
            )
        },
        bottomBar = {
            if (!isWideLayout) {
                LiveBottomTransportBar(
                    playbackStateProvider = playbackStateProvider,
                    isPlaying = isLivePlaying,
                    isFading = isLiveFading,
                    isBusy = isLiveBusy,
                    nextEntry = nextEntry,
                    holdDurationMs = settings.holdDurationMs,
                    onFadeOut = onFadeOut,
                    onStopImmediate = onStopImmediate
                )
            }
        }
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        val errorMsg = liveErrorMessage

        Column(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
        ) {
            if (errorMsg != null) {
                Surface(
                    color = CoroColors.StopRed,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = innerPadding.calculateTopPadding())
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = innerPadding.calculateStartPadding(layoutDirection) + 16.dp,
                                end = innerPadding.calculateEndPadding(layoutDirection) + 16.dp,
                                top = 10.dp,
                                bottom = 10.dp
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = errorMsg,
                            color = Color.White,
                            modifier = Modifier.weight(1f),
                            fontWeight = FontWeight.SemiBold
                        )
                        TextButton(onClick = onDismissError) {
                            Text("OK", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            val contentTopPadding = if (errorMsg != null) 0.dp else innerPadding.calculateTopPadding()

            if (isWideLayout) {
                val edgePad = if (isCompactHeight) 10.dp else 16.dp
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(
                            start = innerPadding.calculateStartPadding(layoutDirection) + edgePad,
                            end = innerPadding.calculateEndPadding(layoutDirection) + edgePad
                        ),
                    horizontalArrangement = Arrangement.spacedBy(if (isCompactHeight) 12.dp else 16.dp)
                ) {
                    LiveTrackList(
                        entries = liveEntries,
                        nextTrackId = nextEntry?.item?.track?.id,
                        activeTrackId = activeLiveTrackId,
                        isAnyLiveBusy = isLiveBusy,
                        isFadingOut = isLiveFading,
                        holdDurationMs = settings.holdDurationMs,
                        onPlayTrack = onPlayTrack,
                        contentPadding = PaddingValues(
                            top = contentTopPadding + edgePad,
                            bottom = innerPadding.calculateBottomPadding() + edgePad + 8.dp
                        ),
                        modifier = Modifier
                            .weight(1.35f)
                            .fillMaxHeight()
                    )
                    LiveSideControlDesk(
                        playbackStateProvider = playbackStateProvider,
                        isPlaying = isLivePlaying,
                        isFading = isLiveFading,
                        isBusy = isLiveBusy,
                        nextEntry = nextEntry,
                        holdDurationMs = settings.holdDurationMs,
                        isCompactHeight = isCompactHeight,
                        onPlayNext = { entry -> onPlayTrack(entry.phaseLabel, entry.item) },
                        onFadeOut = onFadeOut,
                        onStopImmediate = onStopImmediate,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(
                                top = contentTopPadding + edgePad,
                                bottom = innerPadding.calculateBottomPadding() + edgePad
                            )
                    )
                }
            } else {
                LiveTrackList(
                    entries = liveEntries,
                    nextTrackId = nextEntry?.item?.track?.id,
                    activeTrackId = activeLiveTrackId,
                    isAnyLiveBusy = isLiveBusy,
                    isFadingOut = isLiveFading,
                    holdDurationMs = settings.holdDurationMs,
                    onPlayTrack = onPlayTrack,
                    contentPadding = PaddingValues(
                        start = innerPadding.calculateStartPadding(layoutDirection) + 14.dp,
                        end = innerPadding.calculateEndPadding(layoutDirection) + 14.dp,
                        top = contentTopPadding + 10.dp,
                        bottom = innerPadding.calculateBottomPadding() + 20.dp
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun LiveTopHeader(
    title: String,
    mediaVolumePercent: Int,
    holdDurationMs: Int,
    isLiveBusy: Boolean,
    showExitHint: Boolean,
    hasPlayedAny: Boolean,
    onResetPlayed: () -> Unit,
    onExitLiveMode: () -> Unit
) {
    Surface(color = CoroColors.Surface, tonalElevation = 4.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.statusBars.union(
                        WindowInsets.displayCutout.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                    )
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = CoroColors.Gold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val lowVol = mediaVolumePercent < 25
                        Icon(
                            imageVector = when {
                                mediaVolumePercent == 0 -> Icons.AutoMirrored.Filled.VolumeOff
                                lowVol -> Icons.AutoMirrored.Filled.VolumeDown
                                else -> Icons.AutoMirrored.Filled.VolumeUp
                            },
                            contentDescription = null,
                            tint = if (lowVol) CoroColors.FadeAmberFill else CoroColors.TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = if (lowVol)
                                "Volume media basso ($mediaVolumePercent%)"
                            else
                                "Volume media $mediaVolumePercent% · Tieni premuto (${holdDurationMs}ms)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (lowVol) CoroColors.FadeAmberFill else CoroColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (hasPlayedAny && !isLiveBusy) {
                    HoldToConfirmSurface(
                        holdDurationMs = holdDurationMs,
                        onConfirmed = onResetPlayed,
                        enabled = !isLiveBusy,
                        containerColor = CoroColors.SurfaceElevated,
                        progressFillColor = CoroColors.Gold,
                        border = BorderStroke(1.dp, CoroColors.Border),
                        shape = RoundedCornerShape(10.dp),
                        contentDescriptionText = "Tieni premuto per azzerare le spunte dei brani eseguiti"
                    ) { _ ->
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                HoldToConfirmSurface(
                    holdDurationMs = holdDurationMs,
                    onConfirmed = onExitLiveMode,
                    enabled = !isLiveBusy,
                    containerColor = CoroColors.SurfaceElevated,
                    progressFillColor = CoroColors.Gold,
                    border = BorderStroke(1.dp, if (showExitHint) CoroColors.Gold else CoroColors.Border),
                    shape = RoundedCornerShape(10.dp),
                    contentDescriptionText = "Tieni premuto per uscire dalla modalità Esecuzione"
                ) { _ ->
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isLiveBusy) Icons.Default.Lock else Icons.AutoMirrored.Filled.ExitToApp,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Esci", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            if (showExitHint) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CoroColors.GoldDim)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = CoroColors.Gold,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isLiveBusy)
                            "Ferma o sfuma prima la musica per uscire dalla console live."
                        else
                            "Per evitare uscite accidentali, tieni premuto il tasto \"Esci\" in alto a destra.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CoroColors.Gold
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveTrackList(
    entries: List<LiveTrackEntry>,
    nextTrackId: Long?,
    activeTrackId: Long?,
    isAnyLiveBusy: Boolean,
    isFadingOut: Boolean,
    holdDurationMs: Int,
    onPlayTrack: (String, PlannedTrackWithSong) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    // Risolto una sola volta nel contenitore padre invece che dentro ogni item della LazyColumn
    val hapticFeedback = LocalHapticFeedback.current

    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = contentPadding
    ) {
        items(
            items = entries,
            key = { it.item.track.id },
            contentType = { "live_track" }
        ) { entry ->
            val track = entry.item.track
            val song = entry.item.song
            val isThisPlaying = isAnyLiveBusy && activeTrackId == track.id
            val isThisNext = !isAnyLiveBusy && nextTrackId == track.id
            val isPlayed = track.playedInSession && !isThisPlaying

            val containerColor = when {
                isThisPlaying && isFadingOut -> CoroColors.FadeAmber.copy(alpha = 0.28f)
                isThisPlaying -> CoroColors.PlayGreen.copy(alpha = 0.32f)
                isThisNext -> CoroColors.SurfaceHighlight
                isPlayed -> CoroColors.PlayedMuted
                else -> CoroColors.Surface
            }

            val borderColor = when {
                isThisPlaying && isFadingOut -> CoroColors.FadeAmberFill
                isThisPlaying -> CoroColors.PlayGreenFill
                isThisNext -> CoroColors.Gold
                isPlayed -> CoroColors.PlayedAccent.copy(alpha = 0.4f)
                else -> CoroColors.Border
            }

            HoldToConfirmSurface(
                holdDurationMs = holdDurationMs,
                onConfirmed = { onPlayTrack(entry.phaseLabel, entry.item) },
                enabled = !isAnyLiveBusy,
                containerColor = containerColor,
                progressFillColor = CoroColors.PlayGreenFill,
                border = BorderStroke(if (isThisPlaying || isThisNext) 2.dp else 1.dp, borderColor),
                shape = RoundedCornerShape(18.dp),
                contentDescriptionText = "Tieni premuto per avviare ${entry.phaseLabel} ${song.title}",
                hapticFeedback = hapticFeedback,
                modifier = Modifier.fillMaxWidth()
            ) { isHolding ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val phaseBadge = if (entry.totalInPhase > 1) {
                                "${entry.phaseLabel.uppercase()} · ${entry.indexInPhase}/${entry.totalInPhase}"
                            } else {
                                entry.phaseLabel.uppercase()
                            }
                            Text(
                                text = phaseBadge,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (isThisNext || isThisPlaying) CoroColors.Gold else CoroColors.TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (isThisNext) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(CoroColors.Gold)
                                        .padding(horizontal = 7.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "PROSSIMO",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = CoroColors.Background,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = song.title,
                            fontSize = 21.sp,
                            lineHeight = 26.sp,
                            fontWeight = FontWeight.Bold,
                            color = CoroColors.TextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        val details = remember(
                            song.audioFormat,
                            track.transposeSemitones,
                            track.startOffsetMs,
                            track.fadeOutMs,
                            track.volume
                        ) {
                            buildList {
                                add(song.audioFormat.shortLabel)
                                if (track.transposeSemitones != 0) {
                                    add(TrackSettings.formatTranspose(track.transposeSemitones))
                                }
                                if (track.startOffsetMs > 0L) {
                                    add("Attacco ${TrackSettings.formatTimeMs(track.startOffsetMs)}")
                                }
                                add("Sfuma ${TrackSettings.formatFadeSec(track.fadeOutMs)}")
                                if (track.volume != 1.0f) {
                                    add("Vol ${TrackSettings.formatVolumePercent(track.volume)}")
                                }
                            }.joinToString(" · ")
                        }

                        Text(
                            text = if (isHolding) "Tieni premuto per avviare..." else details,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isHolding) CoroColors.PlayGreenFill else CoroColors.TextSecondary
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                when {
                                    isThisPlaying -> CoroColors.PlayGreen
                                    isAnyLiveBusy -> CoroColors.SurfaceElevated
                                    isPlayed -> CoroColors.PlayedAccent.copy(alpha = 0.2f)
                                    isThisNext -> CoroColors.PlayGreen
                                    else -> CoroColors.SurfaceElevated
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when {
                                isThisPlaying -> Icons.Default.GraphicEq
                                isAnyLiveBusy -> Icons.Default.Lock
                                isPlayed -> Icons.Default.CheckCircle
                                else -> Icons.Default.PlayArrow
                            },
                            contentDescription = null,
                            tint = when {
                                isThisPlaying || isThisNext -> Color.White
                                isPlayed -> CoroColors.PlayedAccent
                                else -> CoroColors.TextPrimary
                            },
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveBottomTransportBar(
    playbackStateProvider: () -> PlaybackState,
    isPlaying: Boolean,
    isFading: Boolean,
    isBusy: Boolean,
    nextEntry: LiveTrackEntry?,
    holdDurationMs: Int,
    onFadeOut: () -> Unit,
    onStopImmediate: () -> Unit
) {
    val activeTitle by remember(playbackStateProvider) {
        derivedStateOf { playbackStateProvider().title }
    }
    val activeFadeOutMs by remember(playbackStateProvider) {
        derivedStateOf { playbackStateProvider().fadeOutMs }
    }

    Surface(
        color = CoroColors.Surface,
        tonalElevation = 10.dp,
        shadowElevation = 12.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.navigationBars.union(
                        WindowInsets.displayCutout.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                    )
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Riga stato corrente + avanzamento isolato
            LiveBottomProgressSection(
                playbackStateProvider = playbackStateProvider,
                isPlaying = isPlaying,
                isFading = isFading,
                isBusy = isBusy,
                activeTitle = activeTitle,
                nextEntry = nextEntry
            )

            // Tasti giganti SFUMA e STOP IMMEDIATO (non si ricompongono durante i tick di positionMs)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HoldToConfirmSurface(
                    holdDurationMs = holdDurationMs,
                    onConfirmed = onFadeOut,
                    enabled = isPlaying,
                    containerColor = CoroColors.FadeAmber,
                    progressFillColor = CoroColors.FadeAmberFill,
                    shape = RoundedCornerShape(16.dp),
                    contentDescriptionText = "Tieni premuto per sfumare la musica",
                    modifier = Modifier
                        .weight(1.15f)
                        .height(74.dp)
                ) { _ ->
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.TrendingDown,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "SFUMA",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )
                            Text(
                                text = if (isBusy) TrackSettings.formatFadeSec(activeFadeOutMs) else "Tieni premuto",
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.85f)
                            )
                        }
                    }
                }

                HoldToConfirmSurface(
                    holdDurationMs = holdDurationMs,
                    onConfirmed = onStopImmediate,
                    enabled = isBusy,
                    containerColor = CoroColors.StopRed,
                    progressFillColor = CoroColors.StopRedFill,
                    shape = RoundedCornerShape(16.dp),
                    contentDescriptionText = "Tieni premuto per fermare subito la musica",
                    modifier = Modifier
                        .weight(1f)
                        .height(74.dp)
                ) { _ ->
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text(
                                text = "STOP",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )
                            Text(
                                text = "Immediato",
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.85f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveBottomProgressSection(
    playbackStateProvider: () -> PlaybackState,
    isPlaying: Boolean,
    isFading: Boolean,
    isBusy: Boolean,
    activeTitle: String,
    nextEntry: LiveTrackEntry?
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = when {
                    isFading -> "SFUMATURA IN CORSO · $activeTitle"
                    isPlaying -> "IN RIPRODUZIONE · $activeTitle"
                    nextEntry != null -> "Pronto: ${nextEntry.phaseLabel} — ${nextEntry.item.song.title}"
                    else -> "Pronto"
                },
                style = MaterialTheme.typography.labelLarge,
                color = when {
                    isFading -> CoroColors.FadeAmberFill
                    isPlaying -> CoroColors.PlayGreenFill
                    else -> CoroColors.TextSecondary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (isBusy) {
                val state = playbackStateProvider()
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${TrackSettings.formatTimeMs(state.positionMs)} / ${TrackSettings.formatTimeMs(state.durationMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CoroColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        if (isBusy) {
            LinearProgressIndicator(
                progress = { playbackStateProvider().progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = if (isFading) CoroColors.FadeAmberFill else CoroColors.PlayGreenFill,
                trackColor = CoroColors.SurfaceElevated
            )
        }
    }
}

@Composable
private fun LiveSideControlDesk(
    playbackStateProvider: () -> PlaybackState,
    isPlaying: Boolean,
    isFading: Boolean,
    isBusy: Boolean,
    nextEntry: LiveTrackEntry?,
    holdDurationMs: Int,
    isCompactHeight: Boolean,
    onPlayNext: (LiveTrackEntry) -> Unit,
    onFadeOut: () -> Unit,
    onStopImmediate: () -> Unit,
    modifier: Modifier = Modifier
) {
    val deskScrollState = rememberScrollState()
    val activePhaseLabel by remember(playbackStateProvider) {
        derivedStateOf { playbackStateProvider().phaseLabel }
    }
    val activeTitle by remember(playbackStateProvider) {
        derivedStateOf { playbackStateProvider().title }
    }
    val activeFadeOutMs by remember(playbackStateProvider) {
        derivedStateOf { playbackStateProvider().fadeOutMs }
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = CoroColors.Surface),
        border = BorderStroke(1.dp, CoroColors.Border),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(deskScrollState)
                .padding(if (isCompactHeight) 14.dp else 20.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Stato e brano attivo / prossimo
            Column(verticalArrangement = Arrangement.spacedBy(if (isCompactHeight) 4.dp else 8.dp)) {
                Text(
                    text = when {
                        isFading -> "SFUMATURA IN CORSO"
                        isPlaying -> "IN RIPRODUZIONE"
                        else -> "PROSSIMO IN SCALETTA"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = when {
                        isFading -> CoroColors.FadeAmberFill
                        isPlaying -> CoroColors.PlayGreenFill
                        else -> CoroColors.Gold
                    }
                )
                Text(
                    text = if (isBusy) activePhaseLabel else nextEntry?.phaseLabel ?: "—",
                    style = MaterialTheme.typography.titleMedium,
                    color = CoroColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (isBusy) activeTitle else nextEntry?.item?.song?.title ?: "Nessun brano",
                    fontSize = if (isCompactHeight) 20.sp else 26.sp,
                    lineHeight = if (isCompactHeight) 24.sp else 32.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = CoroColors.TextPrimary,
                    maxLines = if (isCompactHeight) 2 else 3,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(if (isCompactHeight) 4.dp else 8.dp))

                LiveDeskProgressBar(
                    playbackStateProvider = playbackStateProvider,
                    isBusy = isBusy,
                    isFading = isFading,
                    isCompactHeight = isCompactHeight,
                    fallbackDurationMs = nextEntry?.item?.song?.durationMs ?: 0L
                )
            }

            Spacer(modifier = Modifier.height(if (isCompactHeight) 10.dp else 16.dp))

            // Controlli giganti adattivi per Tablet e Landscape (non si ricompongono sui tick di positionMs)
            val playButtonHeight = if (isCompactHeight) 56.dp else 88.dp
            val stopButtonHeight = if (isCompactHeight) 54.dp else 84.dp
            val actionFontSize = if (isCompactHeight) 16.sp else 20.sp
            val iconSize = if (isCompactHeight) 26.dp else 34.dp

            Column(verticalArrangement = Arrangement.spacedBy(if (isCompactHeight) 8.dp else 14.dp)) {
                HoldToConfirmSurface(
                    holdDurationMs = holdDurationMs,
                    onConfirmed = { nextEntry?.let(onPlayNext) },
                    enabled = !isBusy && nextEntry != null,
                    containerColor = CoroColors.PlayGreen,
                    progressFillColor = CoroColors.PlayGreenFill,
                    shape = RoundedCornerShape(18.dp),
                    contentDescriptionText = "Tieni premuto per avviare il prossimo brano in scaletta",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(playButtonHeight)
                ) { _ ->
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(if (isCompactHeight) 28.dp else 36.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "AVVIA PROSSIMO",
                            fontSize = actionFontSize,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                    }
                }

                HoldToConfirmSurface(
                    holdDurationMs = holdDurationMs,
                    onConfirmed = onFadeOut,
                    enabled = isPlaying,
                    containerColor = CoroColors.FadeAmber,
                    progressFillColor = CoroColors.FadeAmberFill,
                    shape = RoundedCornerShape(18.dp),
                    contentDescriptionText = "Tieni premuto per sfumare la musica",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(stopButtonHeight)
                ) { _ ->
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.TrendingDown,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(iconSize)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = if (isBusy) "SFUMA (${TrackSettings.formatFadeSec(activeFadeOutMs)})" else "SFUMA",
                            fontSize = actionFontSize,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                    }
                }

                HoldToConfirmSurface(
                    holdDurationMs = holdDurationMs,
                    onConfirmed = onStopImmediate,
                    enabled = isBusy,
                    containerColor = CoroColors.StopRed,
                    progressFillColor = CoroColors.StopRedFill,
                    shape = RoundedCornerShape(18.dp),
                    contentDescriptionText = "Tieni premuto per fermare subito la musica",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(stopButtonHeight)
                ) { _ ->
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(iconSize)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "STOP IMMEDIATO",
                            fontSize = actionFontSize,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveDeskProgressBar(
    playbackStateProvider: () -> PlaybackState,
    isBusy: Boolean,
    isFading: Boolean,
    isCompactHeight: Boolean,
    fallbackDurationMs: Long
) {
    Column(verticalArrangement = Arrangement.spacedBy(if (isCompactHeight) 4.dp else 8.dp)) {
        LinearProgressIndicator(
            progress = { if (isBusy) playbackStateProvider().progressFraction else 0f },
            modifier = Modifier
                .fillMaxWidth()
                .height(if (isCompactHeight) 8.dp else 10.dp)
                .clip(RoundedCornerShape(5.dp)),
            color = if (isFading) CoroColors.FadeAmberFill else CoroColors.PlayGreenFill,
            trackColor = CoroColors.SurfaceElevated
        )
        val state = if (isBusy) playbackStateProvider() else null
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (state != null) TrackSettings.formatTimeMs(state.positionMs) else "0:00",
                style = if (isCompactHeight) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                color = CoroColors.TextSecondary
            )
            Text(
                text = if (state != null)
                    TrackSettings.formatTimeMs(state.durationMs)
                else
                    TrackSettings.formatTimeMs(fallbackDurationMs),
                style = if (isCompactHeight) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                color = CoroColors.TextSecondary
            )
        }
    }
}

private fun readMediaVolumePercent(context: Context): Int {
    val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    return readMediaVolumePercent(am)
}

private fun readMediaVolumePercent(audioManager: AudioManager?): Int {
    val am = audioManager ?: return 100
    val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    val curVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
    return ((curVol * 100f) / maxVol).toInt().coerceIn(0, 100)
}
