package it.parrocchia.coro.ui.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.parrocchia.coro.domain.mixer.RodeAutomationConfig
import it.parrocchia.coro.domain.mixer.RodeInputVolumeChannel
import it.parrocchia.coro.domain.mixer.RodeVirtualFader
import it.parrocchia.coro.ui.theme.CoroColors

private val AutomationButtonPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MixerMuteAutomationCard(
    config: RodeAutomationConfig,
    assignedFaders: List<RodeVirtualFader>,
    isConnected: Boolean,
    onUpdateConfig: (RodeAutomationConfig) -> Unit,
    onMuteAllFaders: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val faderChoices: List<Pair<Int, String>> = remember(assignedFaders) {
        if (assignedFaders.isNotEmpty()) {
            assignedFaders.map { f -> f.faderIndex to "F${f.displayNumber} · ${f.assignedLabel}" }
        } else {
            (0 until RodeInputVolumeChannel.TOTAL_FADER_COUNT).map { idx -> idx to "Fader ${idx + 1}" }
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = CoroColors.Surface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (config.enabled) CoroColors.Gold.copy(alpha = 0.55f) else CoroColors.Border
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 10.dp)
                ) {
                    Text(
                        text = "Automazione Muto (RØDECaster)",
                        style = MaterialTheme.typography.titleMedium,
                        color = CoroColors.Gold,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Apre automaticamente il canale musica (e i microfoni scelti sul brano) all'avvio e rimette in Muto alla fine.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CoroColors.TextSecondary
                    )
                }
                Switch(
                    checked = config.enabled,
                    onCheckedChange = { enabled ->
                        onUpdateConfig(config.copy(enabled = enabled))
                    }
                )
            }

            if (!config.enabled) {
                Surface(
                    color = CoroColors.FadeAmber.copy(alpha = 0.18f),
                    border = BorderStroke(1.dp, CoroColors.FadeAmber),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Automazione Muto DISATTIVATA: attiva l'interruttore qui sopra per aprire e chiudere automaticamente i canali durante i brani.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CoroColors.TextPrimary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
            }

            if (isConnected && onMuteAllFaders != null) {
                Button(
                    onClick = onMuteAllFaders,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CoroColors.StopRed,
                        contentColor = Color.White
                    ),
                    contentPadding = AutomationButtonPadding,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeOff,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Metti tutti i canali in Muto ora",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Canale musica da aprire sempre all'avvio",
                    style = MaterialTheme.typography.labelLarge,
                    color = CoroColors.TextPrimary
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    faderChoices.forEach { (idx, label) ->
                        val selected = idx in config.musicFaderIndices
                        FilterChip(
                            selected = selected,
                            onClick = {
                                val next = if (selected) {
                                    config.musicFaderIndices - idx
                                } else {
                                    config.musicFaderIndices + idx
                                }
                                onUpdateConfig(
                                    config.copy(
                                        enabled = if (!selected) true else config.enabled,
                                        musicFaderIndices = next
                                    )
                                )
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
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Canali da mettere sempre in Muto a fine brano",
                    style = MaterialTheme.typography.labelLarge,
                    color = CoroColors.TextPrimary
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    faderChoices.forEach { (idx, label) ->
                        val selected = idx in config.alwaysMuteOnStopFaderIndices
                        FilterChip(
                            selected = selected,
                            onClick = {
                                val next = if (selected) {
                                    config.alwaysMuteOnStopFaderIndices - idx
                                } else {
                                    config.alwaysMuteOnStopFaderIndices + idx
                                }
                                onUpdateConfig(
                                    config.copy(
                                        enabled = if (!selected) true else config.enabled,
                                        alwaysMuteOnStopFaderIndices = next
                                    )
                                )
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
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 10.dp)
                ) {
                    Text(
                        text = "Proteggi uscita casse esterne",
                        style = MaterialTheme.typography.labelLarge,
                        color = CoroColors.TextPrimary
                    )
                    Text(
                        text = "Disattiva l'Auto-Muto casse del mixer sui microfoni e mantiene attiva l'uscita Monitor durante i brani.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CoroColors.TextSecondary
                    )
                }
                Switch(
                    checked = config.keepMonitorSpeakersUnmuted,
                    onCheckedChange = { keepUnmuted ->
                        onUpdateConfig(config.copy(keepMonitorSpeakersUnmuted = keepUnmuted))
                    }
                )
            }

            Text(
                text = "I microfoni scelti sui singoli canti si aprono all'avvio del brano e tornano automaticamente in Muto alla fine (in Esecuzione Live).",
                style = MaterialTheme.typography.bodyMedium,
                color = CoroColors.TextSecondary
            )
        }
    }
}
