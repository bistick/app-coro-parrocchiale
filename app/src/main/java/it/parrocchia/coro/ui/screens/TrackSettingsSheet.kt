package it.parrocchia.coro.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.parrocchia.coro.audio.PlaybackController
import it.parrocchia.coro.audio.PlaybackState
import it.parrocchia.coro.data.db.SongEntity
import it.parrocchia.coro.domain.mixer.RodeAutomationConfig
import it.parrocchia.coro.domain.mixer.RodeInputVolumeChannel
import it.parrocchia.coro.domain.mixer.RodeMixerPreset
import it.parrocchia.coro.domain.mixer.RodeMixerPresetCodec
import it.parrocchia.coro.domain.mixer.RodeVirtualFader
import it.parrocchia.coro.domain.model.AudioFormat
import it.parrocchia.coro.domain.model.TrackSettings
import it.parrocchia.coro.ui.theme.CoroColors
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.roundToInt

private val SheetCompactButtonPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
private val StepButtonPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TrackSettingsSheet(
    song: SongEntity,
    initialSettings: TrackSettings,
    showSaveAsDefaultToggle: Boolean,
    playbackStateFlow: StateFlow<PlaybackState>,
    playbackController: PlaybackController,
    onSave: (settings: TrackSettings, saveAsDefault: Boolean) -> Unit,
    onDismiss: () -> Unit,
    availableMixerFaders: List<RodeVirtualFader> = emptyList(),
    automationConfig: RodeAutomationConfig = RodeAutomationConfig()
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val initialPreset = remember(initialSettings.mixerConfigJson) {
        RodeMixerPresetCodec.decode(initialSettings.mixerConfigJson) ?: RodeMixerPreset()
    }

    var fadeOutMs by rememberSaveable(song.id, initialSettings.fadeOutMs) {
        mutableIntStateOf(initialSettings.fadeOutMs)
    }
    var startOffsetMs by rememberSaveable(song.id, initialSettings.startOffsetMs) {
        mutableLongStateOf(initialSettings.startOffsetMs)
    }
    var transpose by rememberSaveable(song.id, initialSettings.transposeSemitones) {
        mutableIntStateOf(initialSettings.transposeSemitones)
    }
    var volume by rememberSaveable(song.id, initialSettings.volume) {
        mutableFloatStateOf(initialSettings.volume)
    }
    var unmuteFadersList by rememberSaveable(song.id, initialSettings.mixerConfigJson) {
        mutableStateOf(initialPreset.unmuteFaderIndices.sorted())
    }
    var saveAsDefault by rememberSaveable(song.id) { mutableStateOf(true) }

    val faderChoices: List<Pair<Int, String>> = remember(availableMixerFaders) {
        if (availableMixerFaders.isNotEmpty()) {
            availableMixerFaders.map { f -> f.faderIndex to "F${f.displayNumber} · ${f.assignedLabel}" }
        } else {
            (0 until RodeInputVolumeChannel.TOTAL_FADER_COUNT).map { idx -> idx to "Fader ${idx + 1}" }
        }
    }
    val faderLabelByIndex = remember(faderChoices) { faderChoices.toMap() }

    val effectiveDurationMs = maxOf(song.durationMs, 10_000L)

    DisposableEffect(Unit) {
        onDispose {
            playbackController.stopPreviewIfActive()
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            playbackController.stopPreviewIfActive()
            onDismiss()
        },
        sheetState = sheetState,
        containerColor = CoroColors.Surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Intestazione brano
            Column {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = CoroColors.Gold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${song.audioFormat.shortLabel} · Durata ${TrackSettings.formatTimeMs(song.durationMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CoroColors.TextSecondary
                )
            }

            // Box Anteprima Audio isolato: raccoglie playbackStateFlow qui così i controlli sotto
            // non si ricompongono ad ogni tick di avanzamento della traccia.
            TrackPreviewBox(
                song = song,
                startOffsetMs = startOffsetMs,
                fadeOutMs = fadeOutMs,
                transpose = transpose,
                volume = volume,
                playbackStateFlow = playbackStateFlow,
                playbackController = playbackController,
                onStartOffsetChange = { startOffsetMs = it }
            )

            // 1. Punto di attacco
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SheetSectionHeader(
                    title = "Punto di attacco",
                    valueText = TrackSettings.formatTimeMs(startOffsetMs)
                )
                val maxStart = (effectiveDurationMs - 1000L).coerceAtLeast(0L)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StepOutlinedButton(
                        label = "-5 s",
                        onClick = { startOffsetMs = (startOffsetMs - 5000L).coerceAtLeast(0L) }
                    )
                    StepOutlinedButton(
                        label = "-1 s",
                        onClick = { startOffsetMs = (startOffsetMs - 1000L).coerceAtLeast(0L) }
                    )
                    StepOutlinedButton(
                        label = "+1 s",
                        onClick = { startOffsetMs = (startOffsetMs + 1000L).coerceAtMost(maxStart) }
                    )
                    StepOutlinedButton(
                        label = "+5 s",
                        onClick = { startOffsetMs = (startOffsetMs + 5000L).coerceAtMost(maxStart) }
                    )
                    StepOutlinedButton(
                        label = "Inizio (0:00)",
                        enabled = startOffsetMs > 0L,
                        onClick = { startOffsetMs = 0L }
                    )
                }
            }

            HorizontalDivider(color = CoroColors.Border)

            // 2. Cambio tonalità (semitoni)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SheetSectionHeader(
                    title = "Cambio tonalità",
                    subtitle = if (song.audioFormat == AudioFormat.MIDI)
                        "Preserva automaticamente il canale 10 (percussioni)"
                    else
                        "Varia la tonalità mantenendo la velocità originale",
                    valueText = TrackSettings.formatTranspose(transpose)
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StepOutlinedButton(
                        label = "-1 semitono",
                        enabled = transpose > TrackSettings.MIN_TRANSPOSE,
                        onClick = {
                            transpose = (transpose - 1).coerceAtLeast(TrackSettings.MIN_TRANSPOSE)
                            playbackController.updatePreviewTranspose(transpose)
                        }
                    )
                    StepOutlinedButton(
                        label = "+1 semitono",
                        enabled = transpose < TrackSettings.MAX_TRANSPOSE,
                        onClick = {
                            transpose = (transpose + 1).coerceAtMost(TrackSettings.MAX_TRANSPOSE)
                            playbackController.updatePreviewTranspose(transpose)
                        }
                    )
                    StepOutlinedButton(
                        label = "Originale",
                        enabled = transpose != 0,
                        onClick = {
                            transpose = 0
                            playbackController.updatePreviewTranspose(0)
                        }
                    )
                }
            }

            HorizontalDivider(color = CoroColors.Border)

            // 3. Durata sfumatura
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SheetSectionHeader(
                    title = "Durata sfumatura (Fade-out)",
                    valueText = TrackSettings.formatFadeSec(fadeOutMs)
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(1000, 2000, 3000, 5000, 8000, 10000).forEach { preset ->
                        FilterChip(
                            selected = fadeOutMs == preset,
                            onClick = { fadeOutMs = preset },
                            label = {
                                Text(
                                    text = TrackSettings.formatFadeSec(preset),
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        )
                    }
                }
                Slider(
                    value = fadeOutMs.toFloat(),
                    onValueChange = { v ->
                        fadeOutMs = ((v / 500f).roundToInt() * 500).coerceIn(
                            TrackSettings.MIN_FADE_OUT_MS,
                            TrackSettings.MAX_FADE_OUT_MS
                        )
                    },
                    valueRange = TrackSettings.MIN_FADE_OUT_MS.toFloat()..TrackSettings.MAX_FADE_OUT_MS.toFloat()
                )
            }

            HorizontalDivider(color = CoroColors.Border)

            // 4. Volume relativo brano
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SheetSectionHeader(
                    title = "Volume relativo brano",
                    subtitle = "Bilancia questo brano rispetto agli altri (100% = normale)",
                    valueText = TrackSettings.formatVolumePercent(volume)
                )
                Slider(
                    value = volume,
                    onValueChange = { newVol ->
                        val snapped = ((newVol * 20f).roundToInt() / 20f).coerceIn(
                            TrackSettings.MIN_VOLUME,
                            TrackSettings.MAX_VOLUME
                        )
                        volume = snapped
                        playbackController.updatePreviewVolume(snapped)
                    },
                    valueRange = TrackSettings.MIN_VOLUME..TrackSettings.MAX_VOLUME
                )
                OutlinedButton(
                    onClick = {
                        volume = 1.0f
                        playbackController.updatePreviewVolume(1.0f)
                    },
                    enabled = abs(volume - 1.0f) > 0.005f,
                    contentPadding = StepButtonPadding,
                    modifier = Modifier.heightIn(min = 42.dp)
                ) {
                    Icon(Icons.Default.RestartAlt, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Ripristina volume a 100%",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            HorizontalDivider(color = CoroColors.Border)

            // 5. Canali da aprire sul mixer (Muto)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SheetSectionHeader(
                    title = "Canali da aprire sul mixer (Muto)",
                    subtitle = "Seleziona i microfoni/canali a cui togliere il Muto all'avvio di questo brano (verranno rimessi in Muto automaticamente alla fine)",
                    valueText = if (unmuteFadersList.isEmpty()) "Solo musica" else "${unmuteFadersList.size} extra"
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    faderChoices.forEach { (idx, label) ->
                        val selected = idx in unmuteFadersList
                        FilterChip(
                            selected = selected,
                            onClick = {
                                unmuteFadersList = if (selected) {
                                    unmuteFadersList.filterNot { it == idx }
                                } else {
                                    (unmuteFadersList + idx).distinct().sorted()
                                }
                            },
                            label = {
                                Text(
                                    text = label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CoroColors.GoldDim,
                                selectedLabelColor = CoroColors.Gold
                            )
                        )
                    }
                }

                val previewPreset = RodeMixerPreset(unmuteFaderIndices = unmuteFadersList.toSet())
                val openOnStart = automationConfig.fadersToUnmuteOnStart(previewPreset)
                val muteOnStop = automationConfig.fadersToMuteOnStop(previewPreset)
                val openLabels = openOnStart.joinToString(", ") { idx ->
                    faderLabelByIndex[idx] ?: "Fader ${idx + 1}"
                }
                val muteLabels = muteOnStop.joinToString(", ") { idx ->
                    faderLabelByIndex[idx] ?: "Fader ${idx + 1}"
                }
                val summaryBase = buildString {
                    if (openOnStart.isNotEmpty()) {
                        append("Apre all'avvio: $openLabels")
                    } else {
                        append("Nessun canale da aprire (scegli il canale musica nelle Impostazioni o i microfoni qui sopra)")
                    }
                    if (muteOnStop.isNotEmpty()) {
                        append("\nMuta a fine brano: $muteLabels")
                    }
                }
                val summaryText = if (!automationConfig.enabled) {
                    "$summaryBase\n(Attiva l'Automazione Muto nelle Impostazioni per applicarla live)"
                } else {
                    summaryBase
                }
                Text(
                    text = summaryText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = CoroColors.TextSecondary
                )
            }

            if (showSaveAsDefaultToggle) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(CoroColors.SurfaceElevated)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 10.dp)
                    ) {
                        Text(
                            text = "Salva come default di questo brano",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Alla prossima messa ritroverai queste impostazioni già pronte",
                            style = MaterialTheme.typography.bodyMedium,
                            color = CoroColors.TextSecondary
                        )
                    }
                    Switch(
                        checked = saveAsDefault,
                        onCheckedChange = { saveAsDefault = it }
                    )
                }
            }

            // Bottoni azione
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        playbackController.stopPreviewIfActive()
                        onDismiss()
                    },
                    contentPadding = SheetCompactButtonPadding,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 52.dp)
                ) {
                    Text(
                        text = "Annulla",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Button(
                    onClick = {
                        playbackController.stopPreviewIfActive()
                        val updatedPreset = RodeMixerPreset(unmuteFaderIndices = unmuteFadersList.toSet())
                        onSave(
                            TrackSettings(
                                fadeOutMs = fadeOutMs,
                                startOffsetMs = startOffsetMs,
                                transposeSemitones = transpose,
                                volume = volume,
                                mixerConfigJson = RodeMixerPresetCodec.encodeOrNull(updatedPreset)
                            ),
                            saveAsDefault
                        )
                    },
                    contentPadding = SheetCompactButtonPadding,
                    modifier = Modifier
                        .weight(1.3f)
                        .heightIn(min = 52.dp)
                ) {
                    Text(
                        text = "Salva",
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetSectionHeader(
    title: String,
    valueText: String,
    subtitle: String? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = CoroColors.TextSecondary
                )
            }
        }
        Text(
            text = valueText,
            style = MaterialTheme.typography.titleLarge,
            color = CoroColors.Gold,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun StepOutlinedButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = StepButtonPadding,
        modifier = Modifier.heightIn(min = 44.dp)
    ) {
        Text(
            text = label,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun TrackPreviewBox(
    song: SongEntity,
    startOffsetMs: Long,
    fadeOutMs: Int,
    transpose: Int,
    volume: Float,
    playbackStateFlow: StateFlow<PlaybackState>,
    playbackController: PlaybackController,
    onStartOffsetChange: (Long) -> Unit
) {
    val playbackState by playbackStateFlow.collectAsStateWithLifecycle()
    val isPreviewPlayingThisSong = playbackState.isPreview &&
        playbackState.songId == song.id &&
        playbackState.isPlayingOrFading

    val effectiveDurationMs = maxOf(
        song.durationMs,
        if (isPreviewPlayingThisSong) playbackState.durationMs else 0L,
        10_000L
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CoroColors.SurfaceElevated)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Anteprima ascolto",
                style = MaterialTheme.typography.titleMedium,
                color = CoroColors.TextPrimary,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = {
                    if (isPreviewPlayingThisSong) {
                        playbackController.stopPreviewIfActive()
                    } else {
                        playbackController.startPreview(
                            song = song,
                            settings = TrackSettings(
                                fadeOutMs = fadeOutMs,
                                startOffsetMs = startOffsetMs,
                                transposeSemitones = transpose,
                                volume = volume
                            )
                        )
                    }
                },
                contentPadding = SheetCompactButtonPadding,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isPreviewPlayingThisSong) CoroColors.StopRed else CoroColors.PlayGreen
                ),
                modifier = Modifier.heightIn(min = 44.dp)
            ) {
                Icon(
                    imageVector = if (isPreviewPlayingThisSong) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isPreviewPlayingThisSong) "Ferma" else "Ascolta",
                    maxLines = 1,
                    softWrap = false
                )
            }
        }

        val currentPos = if (isPreviewPlayingThisSong) playbackState.positionMs else startOffsetMs
        Slider(
            value = currentPos.toFloat().coerceIn(0f, effectiveDurationMs.toFloat()),
            onValueChange = { newMs ->
                val target = newMs.toLong()
                if (isPreviewPlayingThisSong) {
                    playbackController.seekPreview(target)
                } else {
                    onStartOffsetChange(target)
                }
            },
            valueRange = 0f..effectiveDurationMs.toFloat()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = TrackSettings.formatTimeMs(currentPos),
                style = MaterialTheme.typography.bodyMedium,
                color = CoroColors.Gold,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Text(
                text = TrackSettings.formatTimeMs(effectiveDurationMs),
                style = MaterialTheme.typography.bodyMedium,
                color = CoroColors.TextSecondary,
                maxLines = 1
            )
        }

        OutlinedButton(
            onClick = {
                onStartOffsetChange(currentPos.coerceIn(0L, (effectiveDurationMs - 1000L).coerceAtLeast(0L)))
            },
            enabled = isPreviewPlayingThisSong,
            contentPadding = SheetCompactButtonPadding,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 46.dp)
        ) {
            Icon(Icons.Default.Flag, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Imposta punto di attacco qui",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
