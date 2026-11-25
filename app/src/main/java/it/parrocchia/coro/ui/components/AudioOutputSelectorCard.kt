package it.parrocchia.coro.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.parrocchia.coro.domain.model.AudioOutputRoute
import it.parrocchia.coro.domain.model.AudioOutputStatus
import it.parrocchia.coro.ui.theme.CoroColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AudioOutputSelectorCard(
    status: AudioOutputStatus,
    onSelectRoute: (AudioOutputRoute) -> Unit,
    onRefreshDevices: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CoroColors.Surface),
        shape = RoundedCornerShape(16.dp),
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
                Text(
                    text = "Uscita audio (BT / USB)",
                    style = MaterialTheme.typography.titleMedium,
                    color = CoroColors.Gold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = onRefreshDevices) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Aggiorna dispositivi audio",
                        tint = CoroColors.TextSecondary
                    )
                }
            }

            Text(
                text = status.summaryLine(),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (status.isSelectedRouteAvailable) CoroColors.TextPrimary else CoroColors.FadeAmber
            )

            if (status.hasBluetooth || status.hasUsb) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    status.bluetoothDevice?.let { bt ->
                        DetectedDeviceBadge(
                            icon = Icons.Default.BluetoothAudio,
                            label = "BT: ${bt.productName}"
                        )
                    }
                    status.usbDevice?.let { usb ->
                        DetectedDeviceBadge(
                            icon = Icons.Default.Usb,
                            label = "USB: ${usb.productName}"
                        )
                    }
                }
            } else {
                Text(
                    text = "Nessun dispositivo Bluetooth o USB Audio collegato in questo momento.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CoroColors.TextSecondary
                )
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AudioOutputRoute.entries.forEach { route ->
                    val selected = status.selectedRoute == route
                    val icon = when (route) {
                        AudioOutputRoute.AUTO -> Icons.AutoMirrored.Filled.VolumeUp
                        AudioOutputRoute.BLUETOOTH -> Icons.Default.BluetoothAudio
                        AudioOutputRoute.USB -> Icons.Default.Usb
                        AudioOutputRoute.SPEAKER -> Icons.Default.Speaker
                    }
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectRoute(route) },
                        label = {
                            Text(
                                text = route.shortLabel,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CoroColors.GoldDim,
                            selectedLabelColor = CoroColors.Gold,
                            selectedLeadingIconColor = CoroColors.Gold
                        )
                    )
                }
            }

            Text(
                text = "Utile quando il telefono e collegato via USB al mixer per il controllo ma vuoi inviare l'audio su Bluetooth (o viceversa).",
                style = MaterialTheme.typography.bodyMedium,
                color = CoroColors.TextSecondary
            )
        }
    }
}

@Composable
private fun DetectedDeviceBadge(
    icon: ImageVector,
    label: String
) {
    Surface(
        color = CoroColors.SurfaceElevated,
        border = BorderStroke(1.dp, CoroColors.Border),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = CoroColors.PlayedAccent,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = CoroColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
