package it.parrocchia.coro.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.parrocchia.coro.domain.mixer.RodeAutomationConfig
import it.parrocchia.coro.domain.mixer.RodeConnectionStatus
import it.parrocchia.coro.domain.mixer.RodeMixerState
import it.parrocchia.coro.domain.mixer.RodeVirtualFader
import it.parrocchia.coro.domain.model.AudioOutputRoute
import it.parrocchia.coro.domain.model.AudioOutputStatus
import it.parrocchia.coro.ui.components.AudioOutputSelectorCard
import it.parrocchia.coro.ui.components.MixerMuteAutomationCard
import it.parrocchia.coro.ui.theme.CoroColors

private val MixerCompactButtonPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RodeMixerScreen(
    mixerState: RodeMixerState,
    onConnect: () -> Unit,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
    onSetChannelMute: (faderIndex: Int, muted: Boolean) -> Unit,
    onDismissError: () -> Unit,
    onBack: () -> Unit,
    onUpdateAutomationConfig: (RodeAutomationConfig) -> Unit = {},
    onMuteAllFaders: () -> Unit = {},
    onSetMonitorMute: (Boolean) -> Unit = {},
    onSetMonitorAutoMute: (Boolean) -> Unit = {},
    audioOutputStatus: AudioOutputStatus = AudioOutputStatus(),
    onSelectAudioOutputRoute: (AudioOutputRoute) -> Unit = {},
    onRefreshAudioOutputDevices: () -> Unit = {}
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val layoutDirection = LocalLayoutDirection.current

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CoroColors.Background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Mixer",
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (mixerState.isConnected) {
                            val sub = mixerState.firmwareVersion?.let { "FW $it · Live" } ?: "Connesso · Live"
                            Text(
                                text = sub,
                                style = MaterialTheme.typography.bodyMedium,
                                color = CoroColors.PlayedAccent,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
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
                    if (mixerState.isConnected) {
                        IconButton(
                            onClick = onRefresh,
                            enabled = !mixerState.isBusy
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Aggiorna canali dal mixer")
                        }
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding),
            contentPadding = PaddingValues(
                start = innerPadding.calculateStartPadding(layoutDirection) + 14.dp,
                end = innerPadding.calculateEndPadding(layoutDirection) + 14.dp,
                top = innerPadding.calculateTopPadding() + 12.dp,
                bottom = innerPadding.calculateBottomPadding() + 28.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "connection_card") {
                ConnectionStatusCard(
                    state = mixerState,
                    onConnect = onConnect,
                    onRefresh = onRefresh,
                    onDisconnect = onDisconnect,
                    onDismissError = onDismissError
                )
            }

            if (mixerState.isConnected && mixerState.hasMonitorControl) {
                item(key = "monitor_output_card") {
                    MonitorOutputCard(
                        monitorMuted = mixerState.monitorMuted,
                        monitorAutoMuteEnabled = mixerState.monitorAutoMuteEnabled,
                        monitorAutoMuteActive = mixerState.monitorAutoMuteActive,
                        onSetMonitorMute = onSetMonitorMute,
                        onSetMonitorAutoMute = onSetMonitorAutoMute
                    )
                }
            }

            if (mixerState.isConnected) {
                val activeFaders = mixerState.assignedFaders
                if (activeFaders.isNotEmpty()) {
                    item(key = "faders_header") {
                        SectionHeader(
                            title = "Canali configurati",
                            subtitle = "${activeFaders.size} attivi · solo Muto"
                        )
                    }
                    items(
                        items = activeFaders,
                        key = { "fader_${it.faderIndex}" }
                    ) { fader ->
                        ChannelMuteRowCard(
                            fader = fader,
                            onToggleMute = { muted -> onSetChannelMute(fader.faderIndex, muted) }
                        )
                    }
                } else {
                    item(key = "empty_faders") {
                        Surface(
                            color = CoroColors.Surface,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Nessuna sorgente assegnata ai canali del mixer.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = CoroColors.TextSecondary,
                                modifier = Modifier.padding(14.dp)
                            )
                        }
                    }
                }
            }

            item(key = "automation_card") {
                MixerMuteAutomationCard(
                    config = mixerState.automationConfig,
                    assignedFaders = mixerState.assignedFaders,
                    isConnected = mixerState.isConnected,
                    onUpdateConfig = onUpdateAutomationConfig,
                    onMuteAllFaders = onMuteAllFaders
                )
            }

            item(key = "audio_output_card") {
                AudioOutputSelectorCard(
                    status = audioOutputStatus,
                    onSelectRoute = onSelectAudioOutputRoute,
                    onRefreshDevices = onRefreshAudioOutputDevices
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnectionStatusCard(
    state: RodeMixerState,
    onConnect: () -> Unit,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
    onDismissError: () -> Unit
) {
    val (pillLabel, pillColor, pillTextColor) = when (state.status) {
        RodeConnectionStatus.CONNECTED -> Triple("Connesso", CoroColors.PlayGreen, Color.White)
        RodeConnectionStatus.CONNECTING -> Triple("Connessione...", CoroColors.GoldDim, CoroColors.Gold)
        RodeConnectionStatus.PERMISSION_REQUIRED -> Triple("Permesso USB", CoroColors.FadeAmber, Color.White)
        RodeConnectionStatus.ERROR -> Triple("Errore", CoroColors.StopRed, Color.White)
        RodeConnectionStatus.DISCONNECTED -> Triple("Non connesso", CoroColors.SurfaceElevated, CoroColors.TextSecondary)
    }

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
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Usb,
                        contentDescription = null,
                        tint = CoroColors.Gold,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = state.deviceName ?: "RØDECaster Pro II",
                        style = MaterialTheme.typography.titleMedium,
                        color = CoroColors.TextPrimary
                    )
                }
                Surface(
                    color = pillColor,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = pillLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = pillTextColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Text(
                text = state.statusMessage
                    ?: if (state.isConnected) {
                        "Controllo Muto sincronizzato in tempo reale"
                    } else {
                        "Collega il cavo USB-C alla porta USB 1 (accanto all'alimentazione)."
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = CoroColors.TextSecondary
            )

            state.errorMessage?.let { err ->
                Surface(
                    color = CoroColors.StopRed.copy(alpha = 0.18f),
                    border = BorderStroke(1.dp, CoroColors.StopRed),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodyMedium,
                            color = CoroColors.TextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onDismissError) {
                            Text("OK", color = CoroColors.Gold)
                        }
                    }
                }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!state.isConnected) {
                    Button(
                        onClick = onConnect,
                        enabled = !state.isBusy,
                        contentPadding = MixerCompactButtonPadding,
                        modifier = Modifier.heightIn(min = 44.dp)
                    ) {
                        if (state.isBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        } else {
                            Icon(Icons.Default.Usb, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(
                            text = if (state.status == RodeConnectionStatus.PERMISSION_REQUIRED) {
                                "Concedi permesso USB"
                            } else {
                                "Collega mixer"
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = onRefresh,
                        enabled = !state.isBusy,
                        contentPadding = MixerCompactButtonPadding,
                        modifier = Modifier.heightIn(min = 44.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Aggiorna", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = onDisconnect,
                        enabled = !state.isBusy,
                        contentPadding = MixerCompactButtonPadding,
                        modifier = Modifier.heightIn(min = 44.dp)
                    ) {
                        Icon(Icons.Default.LinkOff, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Disconnetti", maxLines = 1)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SectionHeader(title: String, subtitle: String) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = CoroColors.Gold
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = CoroColors.TextSecondary
        )
    }
}

@Composable
private fun ChannelMuteRowCard(
    fader: RodeVirtualFader,
    onToggleMute: (Boolean) -> Unit
) {
    val isMuted = fader.isMuted == true

    Card(
        colors = CardDefaults.cardColors(containerColor = CoroColors.Surface),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (isMuted) CoroColors.StopRed.copy(alpha = 0.5f) else CoroColors.Border
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "Fader ${fader.displayNumber} · ${fader.assignedLabel}",
                    style = MaterialTheme.typography.titleMedium,
                    color = CoroColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (fader.isPhysical) "Canale fisico" else "Canale virtuale",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CoroColors.TextSecondary,
                    maxLines = 1
                )
            }

            if (isMuted) {
                Button(
                    onClick = { onToggleMute(false) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CoroColors.StopRed,
                        contentColor = Color.White
                    ),
                    contentPadding = MixerCompactButtonPadding,
                    modifier = Modifier.heightIn(min = 42.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.VolumeOff,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Muto", maxLines = 1)
                }
            } else {
                OutlinedButton(
                    onClick = { onToggleMute(true) },
                    contentPadding = MixerCompactButtonPadding,
                    modifier = Modifier.heightIn(min = 42.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Attivo", maxLines = 1)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MonitorOutputCard(
    monitorMuted: Boolean?,
    monitorAutoMuteEnabled: Boolean?,
    monitorAutoMuteActive: Boolean?,
    onSetMonitorMute: (Boolean) -> Unit,
    onSetMonitorAutoMute: (Boolean) -> Unit
) {
    val isManuallyMuted = monitorMuted == true
    val isAutoMutedNow = monitorAutoMuteActive == true
    val (statusLabel, statusBg, statusFg) = when {
        isManuallyMuted -> Triple("Casse in Muto", CoroColors.StopRed, Color.White)
        isAutoMutedNow -> Triple("Auto-Muto attivo (mic aperto)", CoroColors.FadeAmber, Color.White)
        else -> Triple("Casse attive", CoroColors.PlayGreen, Color.White)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = CoroColors.Surface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            width = 1.dp,
            color = when {
                isManuallyMuted -> CoroColors.StopRed.copy(alpha = 0.7f)
                isAutoMutedNow || monitorAutoMuteEnabled == true -> CoroColors.FadeAmber.copy(alpha = 0.7f)
                else -> CoroColors.Border
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Uscita casse esterne (Monitor)",
                    style = MaterialTheme.typography.titleMedium,
                    color = CoroColors.Gold,
                    fontWeight = FontWeight.Bold
                )
                Surface(
                    color = statusBg,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = statusFg,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            if (monitorMuted != null) {
                if (isManuallyMuted) {
                    Button(
                        onClick = { onSetMonitorMute(false) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CoroColors.StopRed,
                            contentColor = Color.White
                        ),
                        contentPadding = MixerCompactButtonPadding,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Riattiva casse", maxLines = 1)
                    }
                } else {
                    OutlinedButton(
                        onClick = { onSetMonitorMute(true) },
                        contentPadding = MixerCompactButtonPadding,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.VolumeOff,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Muto casse", maxLines = 1)
                    }
                }
            }

            if (monitorAutoMuteEnabled != null) {
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
                            text = "Auto-Muto casse su microfoni (mixer)",
                            style = MaterialTheme.typography.labelLarge,
                            color = CoroColors.TextPrimary
                        )
                        Text(
                            text = "Se attivo sul RØDECaster, aprire un microfono silenzia tutte le casse esterne.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = CoroColors.TextSecondary
                        )
                    }
                    Switch(
                        checked = monitorAutoMuteEnabled,
                        onCheckedChange = onSetMonitorAutoMute
                    )
                }
            }
        }
    }
}
