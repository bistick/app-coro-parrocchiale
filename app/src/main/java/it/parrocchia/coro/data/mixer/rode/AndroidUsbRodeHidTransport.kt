package it.parrocchia.coro.data.mixer.rode

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Trasporto USB HID su Android Host API per RØDECaster Pro II.
 *
 * Seleziona esplicitamente:
 * - Vendor ID `0x19F7` (RØDE Microphones)
 * - Interfaccia HID numero 9 (`MI_09`, classe `USB_CLASS_HID`), evitando l'interfaccia 3 silenziosa.
 *
 * Utilizza esclusivamente Report ID `0x03` (OUT) e Report ID `0x04` (IN) da 256 byte.
 */
class AndroidUsbRodeHidTransport(
    private val context: Context,
    private val usbManager: UsbManager? = context.getSystemService(Context.USB_SERVICE) as? UsbManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val dumpInitialTimeoutMs: Long = 2_500L,
    private val dumpIdleTimeoutMs: Long = 350L,
    private val dumpMaxDurationMs: Long = 8_000L
) : RodeHidTransport {

    private val lock = Any()
    private val epInLock = Any()
    private val epOutLock = Any()
    private val dumpInProgress = AtomicBoolean(false)
    private var activeDevice: UsbDevice? = null
    private var activeInterface: UsbInterface? = null
    private var activeConnection: UsbDeviceConnection? = null
    private var inEndpoint: UsbEndpoint? = null
    private var outEndpoint: UsbEndpoint? = null

    private var receiver: BroadcastReceiver? = null

    override val isConnected: Boolean
        get() = synchronized(lock) {
            activeConnection != null && activeInterface != null && inEndpoint != null
        }

    override val supportsLivePush: Boolean = true

    override fun startMonitoring(
        onPermissionGranted: () -> Unit,
        onDeviceDetached: () -> Unit,
        onDeviceAttached: () -> Unit
    ) {
        synchronized(lock) {
            if (receiver != null) return
            val br = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    when (intent?.action) {
                        ACTION_USB_PERMISSION -> {
                            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) ||
                                hasGrantedPro2Permission()
                            if (granted) {
                                onPermissionGranted()
                            }
                        }

                        UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                            val attached = extractUsbDevice(intent)
                            val isRodeAttached = if (attached != null) {
                                attached.vendorId == RodeHidProtocol.RODE_VENDOR_ID
                            } else {
                                findPro2ControlTarget(usbManager?.deviceList?.values.orEmpty()) != null
                            }
                            if (isRodeAttached) {
                                onDeviceAttached()
                            }
                        }

                        UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                            val detached = extractUsbDevice(intent)
                            val current = synchronized(lock) { activeDevice }
                            if (detached == null || current == null || detached.deviceId == current.deviceId) {
                                disconnect()
                                onDeviceDetached()
                            }
                        }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(ACTION_USB_PERMISSION)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(br, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(br, filter)
            }
            receiver = br
        }
    }

    override fun stopMonitoring() {
        synchronized(lock) {
            val br = receiver ?: return
            runCatching { context.unregisterReceiver(br) }
            receiver = null
        }
    }

    override suspend fun connect(): RodeConnectResult = withContext(ioDispatcher) {
        val manager = usbManager
            ?: return@withContext RodeConnectResult.Error("USB Host non disponibile su questo dispositivo")

        val candidates = manager.deviceList?.values.orEmpty()
        Log.d(
            TAG,
            "Scansione USB (${candidates.size} dispositivi): " + candidates.joinToString("; ") { d ->
                val hidIds = (0 until d.interfaceCount)
                    .map { d.getInterface(it) }
                    .filter { it.interfaceClass == UsbConstants.USB_CLASS_HID }
                    .map { it.id }
                "${d.productName ?: d.deviceName}(VID=0x${d.vendorId.toString(16)},PID=0x${d.productId.toString(16)},intfs=${d.interfaceCount},hid=$hidIds)"
            }
        )

        val match = findPro2ControlTarget(candidates)
        if (match == null) {
            val rodeWithoutControl = candidates.firstOrNull { it.vendorId == RodeHidProtocol.RODE_VENDOR_ID }
            if (rodeWithoutControl != null) {
                Log.w(
                    TAG,
                    "Dispositivo RØDE rilevato senza interfaccia HID #9 (PID=0x${rodeWithoutControl.productId.toString(16)}): probabile porta USB 2"
                )
                return@withContext RodeConnectResult.Error(
                    "RØDECaster rilevato senza interfaccia di controllo (PID 0x${rodeWithoutControl.productId.toString(16)}). " +
                        "Sposta il cavo dalla porta USB 2 alla porta USB 1."
                )
            }
            return@withContext RodeConnectResult.DeviceNotFound
        }

        val (device, controlInterface) = match
        val label = device.productName?.takeIf { it.isNotBlank() } ?: "RØDECaster Pro II"

        if (!manager.hasPermission(device)) {
            Log.d(TAG, "Richiesta permesso USB per $label (VID=0x${device.vendorId.toString(16)}, PID=0x${device.productId.toString(16)})")
            requestUsbPermission(manager, device)
            return@withContext RodeConnectResult.PermissionRequired(label)
        }

        val epIn = findInterruptEndpoint(controlInterface, UsbConstants.USB_DIR_IN)
            ?: return@withContext RodeConnectResult.Error(
                "Endpoint HID IN mancante sull'interfaccia di controllo ${controlInterface.id}"
            )
        val epOut = findInterruptEndpoint(controlInterface, UsbConstants.USB_DIR_OUT)

        val connection = manager.openDevice(device)
            ?: return@withContext RodeConnectResult.Error("Impossibile aprire la connessione USB verso $label")

        if (!connection.claimInterface(controlInterface, true)) {
            connection.close()
            return@withContext RodeConnectResult.Error(
                "Impossibile acquisire l'interfaccia HID ${controlInterface.id} di $label"
            )
        }

        synchronized(lock) {
            disconnectLocked()
            activeDevice = device
            activeInterface = controlInterface
            activeConnection = connection
            inEndpoint = epIn
            outEndpoint = epOut
        }

        Log.d(
            TAG,
            "Connesso a $label (VID=0x${device.vendorId.toString(16)}, PID=0x${device.productId.toString(16)}, " +
                "intf=${controlInterface.id}, epIn=0x${epIn.address.toString(16)}/max=${epIn.maxPacketSize}, " +
                "epOut=0x${epOut?.address?.toString(16)}/max=${epOut?.maxPacketSize})"
        )
        logDeviceDiagnostics(device, connection, controlInterface)
        RodeConnectResult.Connected(label)
    }

    override fun disconnect() {
        synchronized(lock) {
            disconnectLocked()
        }
    }

    override suspend fun requestModelDump(): ByteArray = withContext(ioDispatcher) {
        dumpInProgress.set(true)
        try {
            synchronized(epInLock) {
                val (connection, intf, epIn, epOut) = requireActiveSession()

                // Tentativo 1: handshake 'N' (64B) + dump Report 0x03 (256B) su endpoint Interrupt OUT
                val firstAttempt = performHandshakeAndCollectDump(
                    connection = connection,
                    usbInterface = intf,
                    epIn = epIn,
                    epOut = epOut,
                    useControlTransfer = (epOut == null)
                )
                if (firstAttempt.isNotEmpty()) {
                    return@withContext firstAttempt
                }

                // Tentativo 2: fallback via HID SET_REPORT control transfer se Interrupt OUT non ha prodotto risposta
                if (epOut != null) {
                    Log.d(TAG, "Nessuna risposta via Interrupt OUT; ritento con HID SET_REPORT control transfer")
                    val secondAttempt = performHandshakeAndCollectDump(
                        connection = connection,
                        usbInterface = intf,
                        epIn = epIn,
                        epOut = epOut,
                        useControlTransfer = true
                    )
                    if (secondAttempt.isNotEmpty()) {
                        return@withContext secondAttempt
                    }
                }

                throw IOException(
                    "Nessuna risposta dal RØDECaster Pro II. Verifica che il cavo sia sulla porta USB 1."
                )
            }
        } finally {
            dumpInProgress.set(false)
        }
    }

    override suspend fun readNextPushReport(timeoutMs: Int): ByteArray? = withContext(ioDispatcher) {
        if (dumpInProgress.get()) return@withContext null
        synchronized(epInLock) {
            if (dumpInProgress.get()) return@withContext null
            val session = synchronized(lock) {
                val conn = activeConnection
                val intf = activeInterface
                val epIn = inEndpoint
                if (conn == null || intf == null || epIn == null) null
                else ActiveSession(conn, intf, epIn, outEndpoint)
            } ?: return@withContext null
            val step = resolveInPacketStep(session.epIn)
            readNextHidReport(session.connection, session.epIn, ByteArray(step), initialTimeoutMs = timeoutMs)
        }
    }

    override suspend fun writeReport(packet: ByteArray) = withContext(ioDispatcher) {
        require(RodeHidProtocol.isSafeOutboundPacket(packet)) {
            "Pacchetto HID non autorizzato dalle guardie di sicurezza hardware"
        }
        val (connection, intf, _, epOut) = requireActiveSession()
        sendReportLocked(connection, intf, epOut, packet, forceControlTransfer = useControlForWrites)
    }

    @Volatile
    private var useControlForWrites: Boolean = false

    private data class ActiveSession(
        val connection: UsbDeviceConnection,
        val usbInterface: UsbInterface,
        val epIn: UsbEndpoint,
        val epOut: UsbEndpoint?
    )

    private fun requireActiveSession(): ActiveSession = synchronized(lock) {
        val conn = activeConnection
        val intf = activeInterface
        val epIn = inEndpoint
        if (conn == null || intf == null || epIn == null) {
            throw IOException("RØDECaster Pro II non connesso")
        }
        ActiveSession(conn, intf, epIn, outEndpoint)
    }

    private fun performHandshakeAndCollectDump(
        connection: UsbDeviceConnection,
        usbInterface: UsbInterface,
        epIn: UsbEndpoint,
        epOut: UsbEndpoint?,
        useControlTransfer: Boolean
    ): ByteArray {
        val packetStep = resolveInPacketStep(epIn)
        val usbChunkBuf = ByteArray(packetStep)

        // Report 0x01 'N' e sempre 64 byte (1 byte Report ID + 63 byte payload)
        val modePacket = RodeHidProtocol.buildNormalModePacket(RodeHidProtocol.MODE_PACKET_SIZE)
        val modeResult = runCatching {
            sendReportLocked(connection, usbInterface, epOut, modePacket, forceControlTransfer = useControlTransfer)
        }
        Log.d(TAG, "Handshake Report 0x01 'N' (ctrl=$useControlTransfer, len=${modePacket.size}): $modeResult")

        // Drena eventuale ACK su Report 0x02 (64B) leggendo a pacchetti da epIn.maxPacketSize
        val ackReport = readNextHidReport(connection, epIn, usbChunkBuf, initialTimeoutMs = 200)
        if (ackReport != null) {
            Log.d(TAG, "Risposta handshake (${ackReport.size}B): ${formatHexPreview(ackReport, ackReport.size)}")
        }

        val dumpPacket = RodeHidProtocol.buildDumpRequestPacket()
        val dumpSendRes = runCatching {
            sendReportLocked(connection, usbInterface, epOut, dumpPacket, forceControlTransfer = useControlTransfer)
        }
        Log.d(TAG, "Invio dump Report 0x03 (ctrl=$useControlTransfer, len=${dumpPacket.size}): $dumpSendRes")
        if (dumpSendRes.isFailure) return ByteArray(0)

        val assembled = ByteArrayOutputStream(160 * 1024)
        val startMs = clockMs()
        var lastPacketMs = 0L
        var gotAnyFrame = false
        var expectedTotalBytes: Int? = null
        var frameCount = 0
        var skippedPushes = 0

        while (true) {
            val now = clockMs()
            if (now - startMs >= dumpMaxDurationMs) break
            if (gotAnyFrame && (now - lastPacketMs) >= dumpIdleTimeoutMs) break
            if (!gotAnyFrame && (now - startMs) >= dumpInitialTimeoutMs) break

            val timeout = if (gotAnyFrame) READ_POLL_TIMEOUT_MS else READ_INITIAL_POLL_MS
            val report = readNextHidReport(connection, epIn, usbChunkBuf, initialTimeoutMs = timeout)
            if (report != null && report.isNotEmpty()) {
                if (frameCount < 5) {
                    Log.d(TAG, "Report IN #$frameCount (${report.size}B): ${formatHexPreview(report, report.size, maxBytes = 24)}")
                }
                if (report[0] == RodeHidProtocol.REPORT_ACK_ID) {
                    // ACK tardivo di Report 0x01: ignora e continua
                    continue
                }
                if (RodeHidProtocol.isAsyncPushFrame(report, report.size)) {
                    skippedPushes++
                    continue
                }
                val chunk = RodeHidProtocol.extractReportInPayload(report, report.size)
                if (chunk.isNotEmpty()) {
                    assembled.write(chunk)
                    frameCount++
                    gotAnyFrame = true
                    lastPacketMs = clockMs()

                    if (expectedTotalBytes == null && assembled.size() >= 4) {
                        val declared = RodeHidProtocol.readDeclaredDumpLength(assembled.toByteArray())
                        if (declared != null) {
                            expectedTotalBytes = 4 + declared
                        }
                    }
                    val target = expectedTotalBytes
                    if (target != null && assembled.size() >= target) {
                        break
                    }
                }
            }
        }

        val bytes = assembled.toByteArray()
        Log.d(
            TAG,
            "Dump (ctrl=$useControlTransfer, step=$packetStep): ${bytes.size}B in $frameCount frame " +
                "(push=$skippedPushes, attesi=${expectedTotalBytes ?: -1}, tempo=${clockMs() - startMs}ms)"
        )
        if (bytes.isNotEmpty()) {
            useControlForWrites = useControlTransfer
        }
        return bytes
    }

    /**
     * Legge un singolo report HID completo dall'endpoint Interrupt IN, riassemblando pacchetti USB
     * da [UsbEndpoint.getMaxPacketSize] (es. 63B sul RØDECaster Pro II: 63+63+63+63+4 = 256B per
     * Report 0x04, oppure 63+1 = 64B per Report 0x02) senza mai perdere dati per timeout parziali.
     */
    internal fun readNextHidReport(
        connection: UsbDeviceConnection,
        epIn: UsbEndpoint,
        chunkBuffer: ByteArray,
        initialTimeoutMs: Int
    ): ByteArray? {
        val step = chunkBuffer.size
        val firstRead = connection.bulkTransfer(epIn, chunkBuffer, step, initialTimeoutMs)
        if (firstRead <= 0) return null

        val expectedSize = when (chunkBuffer[0]) {
            RodeHidProtocol.REPORT_IN_ID -> RodeHidProtocol.PACKET_SIZE
            RodeHidProtocol.REPORT_ACK_ID -> RodeHidProtocol.MODE_PACKET_SIZE
            else -> step
        }

        if (firstRead < step || firstRead >= expectedSize) {
            return chunkBuffer.copyOf(firstRead)
        }

        val report = ByteArrayOutputStream(expectedSize)
        report.write(chunkBuffer, 0, firstRead)
        while (report.size() < expectedSize) {
            val nextRead = connection.bulkTransfer(epIn, chunkBuffer, step, READ_CONTINUATION_TIMEOUT_MS)
            if (nextRead <= 0) break
            report.write(chunkBuffer, 0, nextRead)
            if (nextRead < step) break
        }
        return report.toByteArray()
    }

    private fun sendReportLocked(
        connection: UsbDeviceConnection,
        usbInterface: UsbInterface,
        epOut: UsbEndpoint?,
        packet: ByteArray,
        forceControlTransfer: Boolean = false
    ) = synchronized(epOutLock) {
        val reportId = packet[0].toInt() and 0xFF
        val useInterruptOut = !forceControlTransfer && epOut != null
        val transferred = if (useInterruptOut) {
            connection.bulkTransfer(epOut, packet, packet.size, WRITE_TIMEOUT_MS)
        } else {
            // HID Class SET_REPORT (Output Report ID = packet[0])
            val wValue = (HID_REPORT_TYPE_OUTPUT shl 8) or reportId
            connection.controlTransfer(
                HID_SET_REPORT_REQUEST_TYPE,
                HID_SET_REPORT_REQUEST,
                wValue,
                usbInterface.id,
                packet,
                packet.size,
                WRITE_TIMEOUT_MS
            )
        }
        Log.d(TAG, "TX HID report=0x${reportId.toString(16)} len=${packet.size} ctrl=${!useInterruptOut} -> $transferred")
        if (transferred < 0) {
            throw IOException("Invio pacchetto USB HID fallito (report=0x${reportId.toString(16)}, codice $transferred)")
        }
    }

    private fun logDeviceDiagnostics(
        device: UsbDevice,
        connection: UsbDeviceConnection,
        controlInterface: UsbInterface
    ) {
        runCatching {
            val sb = StringBuilder("USB interfaces (${device.interfaceCount}): ")
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass == UsbConstants.USB_CLASS_HID) {
                    sb.append("[id=${intf.id} alt=${intf.alternateSetting} eps=${intf.endpointCount}: ")
                    for (e in 0 until intf.endpointCount) {
                        val ep = intf.getEndpoint(e)
                        val dir = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                        sb.append("0x${ep.address.toString(16)}($dir/t=${ep.type}/max=${ep.maxPacketSize}/int=${ep.interval}) ")
                    }
                    sb.append("] ")
                }
            }
            Log.d(TAG, sb.toString())

            val hidDesc = ByteArray(256)
            val len = connection.controlTransfer(
                0x81, // Standard/Interface IN
                0x06, // GET_DESCRIPTOR
                0x2200, // HID Report Descriptor
                controlInterface.id,
                hidDesc,
                hidDesc.size,
                500
            )
            if (len > 0) {
                Log.d(TAG, "HID Report Desc (${len}B): ${formatHexPreview(hidDesc, len, maxBytes = len)}")
            } else {
                Log.d(TAG, "HID Report Desc GET_DESCRIPTOR len=$len")
            }
        }
    }

    private fun formatHexPreview(data: ByteArray, length: Int, maxBytes: Int = 20): String {
        val count = minOf(length, data.size, maxBytes)
        return (0 until count).joinToString(" ") { idx ->
            "%02x".format(data[idx].toInt() and 0xFF)
        }
    }

    private fun disconnectLocked() {
        val conn = activeConnection
        val intf = activeInterface
        if (conn != null && intf != null) {
            runCatching { conn.releaseInterface(intf) }
        }
        if (conn != null) {
            runCatching { conn.close() }
        }
        activeConnection = null
        activeInterface = null
        activeDevice = null
        inEndpoint = null
        outEndpoint = null
        useControlForWrites = false
    }

    private fun hasGrantedPro2Permission(): Boolean {
        val manager = usbManager ?: return false
        val match = findPro2ControlTarget(manager.deviceList?.values.orEmpty()) ?: return false
        return manager.hasPermission(match.first)
    }

    private fun requestUsbPermission(manager: UsbManager, device: UsbDevice) {
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getBroadcast(context, USB_PERMISSION_REQ_CODE, intent, flags)
        manager.requestPermission(device, pendingIntent)
    }

    companion object {
        private const val TAG = "RodeUsb"
        const val ACTION_USB_PERMISSION = "it.parrocchia.coro.action.RODE_USB_PERMISSION"
        private const val USB_PERMISSION_REQ_CODE = 4209

        private const val WRITE_TIMEOUT_MS = 1_000
        private const val READ_INITIAL_POLL_MS = 250
        private const val READ_POLL_TIMEOUT_MS = 80
        private const val READ_CONTINUATION_TIMEOUT_MS = 50

        private const val HID_SET_REPORT_REQUEST_TYPE = 0x21
        private const val HID_SET_REPORT_REQUEST = 0x09
        private const val HID_REPORT_TYPE_OUTPUT = 0x02

        internal fun resolveInPacketStep(epIn: UsbEndpoint): Int {
            val maxPkt = epIn.maxPacketSize
            return if (maxPkt in 1 until RodeHidProtocol.PACKET_SIZE) {
                maxPkt
            } else {
                RodeHidProtocol.PACKET_SIZE
            }
        }

        internal fun findPro2ControlTarget(devices: Collection<UsbDevice>): Pair<UsbDevice, UsbInterface>? {
            for (device in devices) {
                if (device.vendorId != RodeHidProtocol.RODE_VENDOR_ID) continue
                val intf = findControlInterface(device)
                if (intf != null) return device to intf
            }
            return null
        }

        internal fun findControlInterface(device: UsbDevice): UsbInterface? {
            for (i in 0 until device.interfaceCount) {
                val candidate = device.getInterface(i)
                if (candidate.id == RodeHidProtocol.PRO2_CONTROL_INTERFACE_ID &&
                    candidate.interfaceClass == UsbConstants.USB_CLASS_HID
                ) {
                    return candidate
                }
            }
            return null
        }

        internal fun findInterruptEndpoint(usbInterface: UsbInterface, direction: Int): UsbEndpoint? {
            for (i in 0 until usbInterface.endpointCount) {
                val ep = usbInterface.getEndpoint(i)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT && ep.direction == direction) {
                    return ep
                }
            }
            return null
        }

        @Suppress("DEPRECATION")
        private fun extractUsbDevice(intent: Intent): UsbDevice? {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
        }
    }
}
