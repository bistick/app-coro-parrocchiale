package it.parrocchia.coro.domain.model

enum class AudioOutputRoute(
    val displayLabel: String,
    val shortLabel: String
) {
    AUTO(displayLabel = "Automatica (Android)", shortLabel = "Auto"),
    BLUETOOTH(displayLabel = "Bluetooth", shortLabel = "Bluetooth"),
    USB(displayLabel = "USB Audio", shortLabel = "USB"),
    SPEAKER(displayLabel = "Altoparlante", shortLabel = "Altoparlante");

    companion object {
        fun fromStoredName(raw: String?): AudioOutputRoute =
            entries.firstOrNull { it.name == raw } ?: AUTO
    }
}

data class AudioOutputDeviceSummary(
    val id: Int,
    val productName: String,
    val route: AudioOutputRoute
)

data class AudioOutputStatus(
    val selectedRoute: AudioOutputRoute = AudioOutputRoute.AUTO,
    val bluetoothDevice: AudioOutputDeviceSummary? = null,
    val usbDevice: AudioOutputDeviceSummary? = null,
    val speakerDevice: AudioOutputDeviceSummary? = null
) {
    val hasBluetooth: Boolean get() = bluetoothDevice != null
    val hasUsb: Boolean get() = usbDevice != null
    val hasBothBluetoothAndUsb: Boolean get() = hasBluetooth && hasUsb

    /** Ritorna il dispositivo attualmente preferito in base alla scelta dell'utente (null = AUTO o non disponibile). */
    val activeTargetDevice: AudioOutputDeviceSummary?
        get() = when (selectedRoute) {
            AudioOutputRoute.AUTO -> null
            AudioOutputRoute.BLUETOOTH -> bluetoothDevice
            AudioOutputRoute.USB -> usbDevice
            AudioOutputRoute.SPEAKER -> speakerDevice
        }

    val isSelectedRouteAvailable: Boolean
        get() = when (selectedRoute) {
            AudioOutputRoute.AUTO -> true
            AudioOutputRoute.BLUETOOTH -> hasBluetooth
            AudioOutputRoute.USB -> hasUsb
            AudioOutputRoute.SPEAKER -> speakerDevice != null
        }

    fun summaryLine(): String {
        return when (selectedRoute) {
            AudioOutputRoute.AUTO -> {
                when {
                    hasBothBluetoothAndUsb -> "Automatica (BT + USB connessi)"
                    hasBluetooth -> "Automatica · ${bluetoothDevice?.productName}"
                    hasUsb -> "Automatica · ${usbDevice?.productName}"
                    else -> "Automatica (sistema Android)"
                }
            }

            AudioOutputRoute.BLUETOOTH -> {
                bluetoothDevice?.let { "Forzata su Bluetooth (${it.productName})" }
                    ?: "Bluetooth selezionato (dispositivo non connesso)"
            }

            AudioOutputRoute.USB -> {
                usbDevice?.let { "Forzata su USB Audio (${it.productName})" }
                    ?: "USB Audio selezionato (dispositivo non connesso)"
            }

            AudioOutputRoute.SPEAKER -> {
                "Forzata su Altoparlante interno"
            }
        }
    }
}
