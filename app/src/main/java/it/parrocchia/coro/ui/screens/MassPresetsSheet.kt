package it.parrocchia.coro.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import it.parrocchia.coro.data.db.MassPlanWithPhases
import it.parrocchia.coro.data.repository.CoroRepository
import it.parrocchia.coro.domain.model.MassPreset
import it.parrocchia.coro.domain.model.MassPresetCatalog
import it.parrocchia.coro.domain.model.MassPresetCategory
import it.parrocchia.coro.ui.theme.CoroColors

private const val FILTER_ALL = "ALL"
private const val FILTER_SAVED = "SAVED"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MassPresetsSheet(
    activePresetId: String?,
    currentMassTitle: String,
    hasCurrentTracks: Boolean,
    initialClearTracks: Boolean,
    savedTemplates: List<MassPlanWithPhases>,
    onApplyPreset: (preset: MassPreset, clearTracks: Boolean) -> Unit,
    onSaveCurrentAsTemplate: (name: String) -> Unit,
    onApplySavedTemplate: (templateId: Long, templateTitle: String) -> Unit,
    onDeleteSavedTemplate: (templateId: Long) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()
    val chipsRowState = rememberLazyListState()

    var selectedFilter by rememberSaveable { mutableStateOf(FILTER_ALL) }
    var clearTracks by rememberSaveable(initialClearTracks) { mutableStateOf(initialClearTracks) }
    var showSaveDialog by rememberSaveable { mutableStateOf(false) }
    var deletingTemplateId by rememberSaveable { mutableStateOf<Long?>(null) }

    val effectiveFilter = if (selectedFilter == FILTER_SAVED && savedTemplates.isEmpty()) {
        FILTER_ALL
    } else {
        selectedFilter
    }

    val groupedBuiltIns = remember(effectiveFilter) {
        when (effectiveFilter) {
            FILTER_SAVED -> emptyList()
            FILTER_ALL -> MassPresetCategory.entries.map { cat ->
                cat to MassPresetCatalog.all.filter { it.category == cat }
            }.filter { it.second.isNotEmpty() }
            else -> {
                val cat = MassPresetCategory.entries.firstOrNull { it.name == effectiveFilter }
                if (cat != null) {
                    listOf(cat to MassPresetCatalog.all.filter { it.category == cat })
                } else {
                    emptyList()
                }
            }
        }
    }

    val deletingTemplate = remember(savedTemplates, deletingTemplateId) {
        val id = deletingTemplateId ?: return@remember null
        savedTemplates.firstOrNull { it.plan.id == id }
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
                text = "Preset di Messa",
                style = MaterialTheme.typography.headlineSmall,
                color = CoroColors.Gold
            )
            Text(
                text = "Applica subito la struttura liturgica o salva la tua scaletta attuale per riutilizzarla.",
                style = MaterialTheme.typography.bodyMedium,
                color = CoroColors.TextSecondary
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = { showSaveDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    Icons.Default.BookmarkAdd,
                    contentDescription = null,
                    tint = CoroColors.Gold
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Salva scaletta attuale come preset",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (hasCurrentTracks) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { clearTracks = !clearTracks }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = clearTracks,
                        onCheckedChange = { clearTracks = it }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Svuota i brani già inseriti cambiando preset",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CoroColors.TextPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            LazyRow(
                state = chipsRowState,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                item(key = FILTER_ALL, contentType = "category_chip") {
                    PresetFilterChip(
                        label = "Tutti",
                        selected = effectiveFilter == FILTER_ALL,
                        onClick = { selectedFilter = FILTER_ALL }
                    )
                }
                if (savedTemplates.isNotEmpty()) {
                    item(key = FILTER_SAVED, contentType = "category_chip") {
                        PresetFilterChip(
                            label = "I miei (${savedTemplates.size})",
                            selected = effectiveFilter == FILTER_SAVED,
                            onClick = { selectedFilter = FILTER_SAVED }
                        )
                    }
                }
                items(
                    items = MassPresetCategory.entries,
                    key = { it.name },
                    contentType = { "category_chip" }
                ) { category ->
                    PresetFilterChip(
                        label = category.label,
                        selected = effectiveFilter == category.name,
                        onClick = { selectedFilter = category.name }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = CoroColors.Border)

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                contentPadding = PaddingValues(top = 10.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if ((effectiveFilter == FILTER_ALL || effectiveFilter == FILTER_SAVED) && savedTemplates.isNotEmpty()) {
                    item(key = "header_saved", contentType = "section_header") {
                        Text(
                            text = "I MIEI PRESET SALVATI",
                            style = MaterialTheme.typography.labelLarge,
                            color = CoroColors.Gold,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    items(
                        items = savedTemplates,
                        key = { "saved_${it.plan.id}" },
                        contentType = { "saved_template_card" }
                    ) { template ->
                        val isActive = activePresetId == "${CoroRepository.CUSTOM_TEMPLATE_PREFIX}${template.plan.title}"
                        SavedTemplateCard(
                            template = template,
                            isActive = isActive,
                            onApply = {
                                onApplySavedTemplate(template.plan.id, template.plan.title)
                                onDismiss()
                            },
                            onDelete = { deletingTemplateId = template.plan.id }
                        )
                    }
                }

                groupedBuiltIns.forEach { (category, presets) ->
                    item(key = "header_${category.name}", contentType = "section_header") {
                        Text(
                            text = category.label.uppercase(),
                            style = MaterialTheme.typography.labelLarge,
                            color = CoroColors.Gold,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    items(
                        items = presets,
                        key = { "builtin_${it.id}" },
                        contentType = { "builtin_preset_card" }
                    ) { preset ->
                        LiturgicalPresetCard(
                            preset = preset,
                            isActive = activePresetId == preset.id,
                            onSelect = {
                                onApplyPreset(preset, clearTracks)
                                onDismiss()
                            }
                        )
                    }
                }
            }
        }
    }

    if (showSaveDialog) {
        val suggestedName = remember(currentMassTitle) {
            currentMassTitle.substringBefore(" · ").trim().ifBlank { "Messa personalizzata" }
        }
        var draftName by rememberSaveable(suggestedName) { mutableStateOf(suggestedName) }

        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            containerColor = CoroColors.Surface,
            title = { Text("Salva preset personalizzato") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Salva le fasi attive e i brani della scaletta attuale per ricaricarli con un tocco.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CoroColors.TextSecondary
                    )
                    OutlinedTextField(
                        value = draftName,
                        onValueChange = { draftName = it },
                        label = { Text("Nome del preset") },
                        placeholder = { Text("Es. Festa Patronale, Messa Famiglie...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (draftName.isNotBlank()) {
                            onSaveCurrentAsTemplate(draftName.trim())
                            showSaveDialog = false
                        }
                    },
                    enabled = draftName.isNotBlank()
                ) {
                    Text("Salva")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text("Annulla")
                }
            }
        )
    }

    deletingTemplate?.let { tpl ->
        AlertDialog(
            onDismissRequest = { deletingTemplateId = null },
            containerColor = CoroColors.Surface,
            title = { Text("Eliminare il preset?") },
            text = {
                Text("Il preset salvato \"${tpl.plan.title}\" verrà rimosso definitivamente.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteSavedTemplate(tpl.plan.id)
                        deletingTemplateId = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CoroColors.StopRed)
                ) {
                    Text("Elimina")
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingTemplateId = null }) {
                    Text("Annulla")
                }
            }
        )
    }
}

@Composable
private fun PresetFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiturgicalPresetCard(
    preset: MassPreset,
    isActive: Boolean,
    onSelect: () -> Unit
) {
    val phasePreview = remember(preset.id) {
        preset.orderedPhaseLabels().joinToString(" · ")
    }

    Card(
        onClick = onSelect,
        colors = CardDefaults.cardColors(containerColor = CoroColors.SurfaceElevated),
        border = BorderStroke(
            width = if (isActive) 1.5.dp else 1.dp,
            color = if (isActive) CoroColors.Gold else CoroColors.Border
        ),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = preset.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isActive) CoroColors.Gold else CoroColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(8.dp))
                if (isActive) {
                    ActivePresetBadge()
                }
            }

            Text(
                text = preset.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = CoroColors.TextSecondary
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                PresetInfoPill(text = "${preset.totalPhasesCount} fasi")
                if (preset.customPhases.isNotEmpty()) {
                    PresetInfoPill(
                        text = "+${preset.customPhases.size} proprie",
                        highlighted = true
                    )
                }
            }

            Text(
                text = phasePreview,
                style = MaterialTheme.typography.bodySmall,
                color = CoroColors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SavedTemplateCard(
    template: MassPlanWithPhases,
    isActive: Boolean,
    onApply: () -> Unit,
    onDelete: () -> Unit
) {
    val enabledPhases = template.enabledPhases
    val totalTracks = remember(enabledPhases) { enabledPhases.sumOf { it.tracks.size } }
    val phasePreview = remember(enabledPhases) {
        enabledPhases.joinToString(" · ") { it.phase.displayName }
    }

    Card(
        onClick = onApply,
        colors = CardDefaults.cardColors(containerColor = CoroColors.SurfaceElevated),
        border = BorderStroke(
            width = if (isActive) 1.5.dp else 1.dp,
            color = if (isActive) CoroColors.Gold else CoroColors.Border
        ),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = template.plan.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isActive) CoroColors.Gold else CoroColors.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isActive) {
                        ActivePresetBadge()
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    PresetInfoPill(text = "${enabledPhases.size} fasi")
                    PresetInfoPill(
                        text = "$totalTracks brani",
                        highlighted = totalTracks > 0
                    )
                }

                if (phasePreview.isNotBlank()) {
                    Text(
                        text = phasePreview,
                        style = MaterialTheme.typography.bodySmall,
                        color = CoroColors.TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = "Elimina preset salvato",
                    tint = CoroColors.StopRedFill
                )
            }
        }
    }
}

@Composable
private fun ActivePresetBadge() {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(CoroColors.GoldDim)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            Icons.Default.Check,
            contentDescription = null,
            tint = CoroColors.Gold,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = "ATTIVO",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = CoroColors.Gold,
            maxLines = 1
        )
    }
}

@Composable
private fun PresetInfoPill(
    text: String,
    highlighted: Boolean = false
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (highlighted) CoroColors.GoldDim else CoroColors.SurfaceHighlight)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (highlighted) CoroColors.Gold else CoroColors.TextPrimary,
            maxLines = 1
        )
    }
}
