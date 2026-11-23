package it.parrocchia.coro.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.parrocchia.coro.data.db.PhaseWithTracks
import it.parrocchia.coro.domain.model.LiturgicalSection
import it.parrocchia.coro.domain.model.StandardPhaseType
import it.parrocchia.coro.ui.theme.CoroColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigurePhasesSheet(
    phases: List<PhaseWithTracks>,
    onTogglePhase: (phaseId: Long, enabled: Boolean) -> Unit,
    onAddCustomPhase: (label: String) -> Unit,
    onDeleteCustomPhase: (phaseId: Long) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()
    var customName by rememberSaveable { mutableStateOf("") }

    val grouped = remember(phases) {
        LiturgicalSection.entries.mapNotNull { section ->
            val items = phases.filter { it.phase.standardType.section == section }
            if (items.isEmpty()) null else section to items
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CoroColors.Surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 20.dp)
        ) {
            Text(
                text = "Fasi della Messa",
                style = MaterialTheme.typography.headlineSmall,
                color = CoroColors.Gold
            )
            Text(
                text = "Attiva solo le parti cantate o suonate per questa celebrazione.",
                style = MaterialTheme.typography.bodyMedium,
                color = CoroColors.TextSecondary
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Aggiunta fase personalizzata
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = customName,
                    onValueChange = { customName = it },
                    label = { Text("Aggiungi fase personalizzata") },
                    placeholder = { Text("Es. Canto a Maria, Adorazione...") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (customName.isNotBlank()) {
                            onAddCustomPhase(customName.trim())
                            customName = ""
                        }
                    },
                    enabled = customName.isNotBlank()
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Aggiungi fase")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = CoroColors.Border)

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                grouped.forEach { (section, sectionPhases) ->
                    item(key = "header_${section.name}") {
                        Text(
                            text = section.label.uppercase(),
                            style = MaterialTheme.typography.labelLarge,
                            color = CoroColors.Gold,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                        )
                    }
                    items(sectionPhases, key = { it.phase.id }) { phaseWithTracks ->
                        val phase = phaseWithTracks.phase
                        val isCustom = phase.standardType == StandardPhaseType.CUSTOM
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = phase.displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (phase.isEnabled) CoroColors.TextPrimary else CoroColors.TextSecondary
                                )
                                val subtitle = buildString {
                                    if (phase.standardType.hint.isNotBlank()) append(phase.standardType.hint)
                                    if (phaseWithTracks.tracks.isNotEmpty()) {
                                        if (isNotEmpty()) append(" · ")
                                        append("${phaseWithTracks.tracks.size} brani")
                                    }
                                }
                                if (subtitle.isNotBlank()) {
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = CoroColors.TextSecondary
                                    )
                                }
                            }
                            if (isCustom) {
                                IconButton(onClick = { onDeleteCustomPhase(phase.id) }) {
                                    Icon(
                                        Icons.Default.DeleteOutline,
                                        contentDescription = "Elimina fase personalizzata",
                                        tint = CoroColors.StopRedFill
                                    )
                                }
                            }
                            Switch(
                                checked = phase.isEnabled,
                                onCheckedChange = { onTogglePhase(phase.id, it) }
                            )
                        }
                        HorizontalDivider(color = CoroColors.Border.copy(alpha = 0.45f))
                    }
                }
            }
        }
    }
}
