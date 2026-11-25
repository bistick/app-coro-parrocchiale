package it.parrocchia.coro.data.mixer.rode

sealed interface RodeConnectResult {
    data class Connected(val deviceLabel: String) : RodeConnectResult
    data class PermissionRequired(val deviceLabel: String) : RodeConnectResult
    data object DeviceNotFound : RodeConnectResult
    data class Error(val message: String) : RodeConnectResult
}

/**
 * Astrazione di trasporto USB HID (Report 0x03 OUT / Report 0x04 IN) per consentire
 * sia l'uso hardware reale via Android UsbManager sia i test unitari deterministici.
 */
interface RodeHidTransport {
    val isConnected: Boolean

    val supportsLivePush: Boolean
        get() = false

    fun startMonitoring(
        onPermissionGranted: () -> Unit,
        onDeviceDetached: () -> Unit,
        onDeviceAttached: () -> Unit = {}
    ) = Unit

    fun stopMonitoring() = Unit

    suspend fun connect(): RodeConnectResult

    fun disconnect()

    suspend fun requestModelDump(): ByteArray

    suspend fun writeReport(packet: ByteArray)

    suspend fun readNextPushReport(timeoutMs: Int = 150): ByteArray? = null
}
