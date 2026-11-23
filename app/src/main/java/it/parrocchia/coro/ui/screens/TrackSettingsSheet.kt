package it.parrocchia.coro.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.parrocchia.coro.audio.PlaybackController
import it.parrocchia.coro.audio.PlaybackState
import it.parrocchia.coro.data.db.SongEntity
import it.parrocchia.coro.domain.model.AudioFormat
import it.parrocchia.coro.domain.model.TrackSettings
import it.parrocchia.coro.ui.theme.CoroColors
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TrackSettingsSheet(
    song: SongEntity,
    initialSettings: TrackSettings,
    showSaveAsDefaultToggle: Boolean,
    playbackState: PlaybackState,
    playbackController: PlaybackController,
    onSave: (settings: TrackSettings, saveAsDefault: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
    var saveAsDefault by rememberSaveable(song.id) { mutableStateOf(true) }

    val isPreviewPlayingThisSong = playbackState.isPreview &&
        playbackState.songId == song.id &&
        playbackState.isPlayingOrFading

    val effectiveDurationMs = maxOf(
        song.durationMs,
        if (isPreviewPlayingThisSong) playbackState.durationMs else 0L,
        10_000L
    )

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
                    color = CoroColors.Gold
                )
                Text(
                    text = "${song.audioFormat.shortLabel} · Durata ${TrackSettings.formatTimeMs(song.durationMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CoroColors.TextSecondary
                )
            }

            // Box Anteprima Audio
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
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Anteprima ascolto",
                        style = MaterialTheme.typography.titleMedium,
                        color = CoroColors.TextPrimary
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
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isPreviewPlayingThisSong) CoroColors.StopRed else CoroColors.PlayGreen
                        )
                    ) {
                        Icon(
                            imageVector = if (isPreviewPlayingThisSong) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isPreviewPlayingThisSong) "Ferma" else "Ascolta")
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
                            startOffsetMs = target
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
                        text = "${TrackSettings.formatTimeMs(currentPos)} / ${TrackSettings.formatTimeMs(effectiveDurationMs)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CoroColors.TextSecondary
                    )
                    OutlinedButton(
                        onClick = {
                            startOffsetMs = currentPos.coerceIn(0L, (effectiveDurationMs - 1000L).coerceAtLeast(0L))
                        },
                        enabled = isPreviewPlayingThisSong
                    ) {
                        Icon(Icons.Default.Flag, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Imposta attacco qui")
                    }
                }
            }

            // 1. Punto di attacco
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Punto di attacco", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = TrackSettings.formatTimeMs(startOffsetMs),
                        style = MaterialTheme.typography.titleLarge,
                        color = CoroColors.Gold
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val maxStart = (effectiveDurationMs - 1000L).coerceAtLeast(0L)
                    OutlinedButton(
                        onClick = { startOffsetMs = (startOffsetMs - 5000L).coerceAtLeast(0L) },
                        modifier = Modifier.weight(1f)
                    ) { Text("-5s") }
                    OutlinedButton(
                        onClick = { startOffsetMs = (startOffsetMs - 1000L).coerceAtLeast(0L) },
                        modifier = Modifier.weight(1f)
                    ) { Text("-1s") }
                    OutlinedButton(
                        onClick = { startOffsetMs = 0L },
                        modifier = Modifier.weight(1f)
                    ) { Text("0:00") }
                    OutlinedButton(
                        onClick = { startOffsetMs = (startOffsetMs + 1000L).coerceAtMost(maxStart) },
                        modifier = Modifier.weight(1f)
                    ) { Text("+1s") }
                    OutlinedButton(
                        onClick = { startOffsetMs = (startOffsetMs + 5000L).coerceAtMost(maxStart) },
                        modifier = Modifier.weight(1f)
                    ) { Text("+5s") }
                }
            }

            HorizontalDivider(color = CoroColors.Border)

            // 2. Cambio tonalità (semitoni)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Cambio tonalità", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = if (song.audioFormat == AudioFormat.MIDI)
                                "Preserva automaticamente il canale 10 (percussioni)"
                            else
                                "Varia la tonalità mantenendo la velocità originale",
                            style = MaterialTheme.typography.bodyMedium,
                            color = CoroColors.TextSecondary
                        )
                    }
                    Text(
                        text = TrackSettings.formatTranspose(transpose),
                        style = MaterialTheme.typography.titleLarge,
                        color = CoroColors.Gold
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            transpose = (transpose - 1).coerceAtLeast(TrackSettings.MIN_TRANSPOSE)
                            playbackController.updatePreviewTranspose(transpose)
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("-1 semitono") }
                    OutlinedButton(
                        onClick = {
                            transpose = 0
                            playbackController.updatePreviewTranspose(0)
                        },
                        modifier = Modifier.weight(0.8f)
                    ) { Text("Orig.") }
                    OutlinedButton(
                        onClick = {
                            transpose = (transpose + 1).coerceAtMost(TrackSettings.MAX_TRANSPOSE)
                            playbackController.updatePreviewTranspose(transpose)
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("+1 semitono") }
                }
            }

            HorizontalDivider(color = CoroColors.Border)

            // 3. Durata sfumatura
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Durata sfumatura (Fade-out)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = TrackSettings.formatFadeSec(fadeOutMs),
                        style = MaterialTheme.typography.titleLarge,
                        color = CoroColors.Gold
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1000, 2000, 3000, 5000, 8000, 10000).forEach { preset ->
                        FilterChip(
                            selected = fadeOutMs == preset,
                            onClick = { fadeOutMs = preset },
                            label = { Text(TrackSettings.formatFadeSec(preset)) }
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
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Volume relativo brano", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = TrackSettings.formatVolumePercent(volume),
                            style = MaterialTheme.typography.titleLarge,
                            color = CoroColors.Gold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(onClick = {
                            volume = 1.0f
                            playbackController.updatePreviewVolume(1.0f)
                        }) { Text("100%") }
                    }
                }
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
                    Column(modifier = Modifier.weight(1f)) {
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
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        playbackController.stopPreviewIfActive()
                        onDismiss()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp)
                ) {
                    Text("Annulla")
                }
                Button(
                    onClick = {
                        playbackController.stopPreviewIfActive()
                        onSave(
                            TrackSettings(
                                fadeOutMs = fadeOutMs,
                                startOffsetMs = startOffsetMs,
                                transposeSemitones = transpose,
                                volume = volume
                            ),
                            saveAsDefault
                        )
                    },
                    modifier = Modifier
                        .weight(1.4f)
                        .height(54.dp)
                ) {
                    Text("Salva impostazioni", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
