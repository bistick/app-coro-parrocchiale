package it.parrocchia.coro

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import it.parrocchia.coro.data.mixer.rode.AndroidUsbRodeHidTransport
import it.parrocchia.coro.data.mixer.rode.RodeConnectResult
import it.parrocchia.coro.data.mixer.rode.RodeHidProtocol
import it.parrocchia.coro.data.mixer.rode.RodeHidTransport
import it.parrocchia.coro.data.mixer.rode.RodeMixerController
import it.parrocchia.coro.data.mixer.rode.RodeNode
import it.parrocchia.coro.data.mixer.rode.RodeTreeLayout
import it.parrocchia.coro.data.mixer.rode.RodeValue
import it.parrocchia.coro.data.mixer.rode.RodeValueTreeCodec
import it.parrocchia.coro.domain.mixer.RodeAutomationConfig
import it.parrocchia.coro.domain.mixer.RodeConnectionStatus
import it.parrocchia.coro.domain.mixer.RodeMixerPreset
import it.parrocchia.coro.domain.mixer.RodeMixerPresetCodec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RodeMixerControllerAndUsbTest {

    private class FakeRodeHidTransport(
        var connectBehavior: () -> RodeConnectResult = { RodeConnectResult.Connected("RØDECaster Pro II") },
        var dumpProvider: () -> ByteArray = {
            RodeValueTreeCodec.encodeNode(RodeValueTreeAndProtocolTest.buildRealisticPro2Tree())
        },
        var writeError: Throwable? = null,
        override val supportsLivePush: Boolean = false
    ) : RodeHidTransport {
        override var isConnected: Boolean = false
        val writtenPackets = mutableListOf<ByteArray>()
        val pushQueue = ArrayDeque<ByteArray>()
        var onPermGranted: (() -> Unit)? = null
        var onDetached: (() -> Unit)? = null
        var onAttached: (() -> Unit)? = null
        var stoppedMonitoring = false

        override suspend fun readNextPushReport(timeoutMs: Int): ByteArray? {
            return pushQueue.removeFirstOrNull()
        }

        override fun startMonitoring(
            onPermissionGranted: () -> Unit,
            onDeviceDetached: () -> Unit,
            onDeviceAttached: () -> Unit
        ) {
            onPermGranted = onPermissionGranted
            onDetached = onDeviceDetached
            onAttached = onDeviceAttached
        }

        override fun stopMonitoring() {
            stoppedMonitoring = true
        }

        override suspend fun connect(): RodeConnectResult {
            val res = connectBehavior()
            isConnected = res is RodeConnectResult.Connected
            return res
        }

        override fun disconnect() {
            isConnected = false
        }

        override suspend fun requestModelDump(): ByteArray {
            if (!isConnected) throw IOException("Not connected")
            return dumpProvider()
        }

        override suspend fun writeReport(packet: ByteArray) {
            if (!isConnected) throw IOException("Not connected")
            writeError?.let { throw it }
            writtenPackets.add(packet.copyOf())
        }
    }

    @Test
    fun controller_connectsReadsAssignedChannelsAndControlsMuteOnly() = runTest {
        var nowMs = 1_000L
        val transport = FakeRodeHidTransport()
        val controller = RodeMixerController(
            transport = transport,
            scope = TestScope(StandardTestDispatcher(testScheduler)),
            writeSpacingMs = 60L,
            clockMs = { nowMs }
        )

        // Prima della connessione, operazioni falliscono con errore chiaro
        assertTrue(controller.refresh().isFailure)
        assertTrue(controller.setChannelMute(0, true).isFailure)
        assertTrue(controller.muteAllAssignedFaders().isFailure)

        // Connessione riuscita + dump
        assertTrue(controller.connect().isSuccess)
        val connectedState = controller.state.value
        assertEquals(RodeConnectionStatus.CONNECTED, connectedState.status)
        assertEquals("RØDECaster Pro II", connectedState.deviceName)
        assertEquals("1.7.7", connectedState.firmwareVersion)
        assertEquals(9, connectedState.assignedFaders.size)
        assertEquals(3, connectedState.virtualFaders.size)

        // Toggle Muto su fader fisico 1 (indice 0) e virtuale 7 (indice 6) invia esattamente 1 pacchetto ciascuno
        assertEquals(true, controller.setChannelMute(0, true).getOrThrow())
        assertEquals(true, controller.state.value.faders.first { it.faderIndex == 0 }.isMuted)
        assertEquals(true, controller.state.value.inputs.first { it.sourceCode == 0 }.isMuted)
        assertEquals(1, transport.writtenPackets.size)

        // Seconda scrittura entro finestra writeSpacingMs -> verifica pacing
        nowMs += 10L
        assertEquals(false, controller.setChannelMute(0, false).getOrThrow())
        assertEquals(false, controller.state.value.faders.first { it.faderIndex == 0 }.isMuted)
        assertEquals(2, transport.writtenPackets.size)

        nowMs += 100L
        assertEquals(true, controller.setChannelMute(6, true).getOrThrow())
        assertEquals(true, controller.state.value.faders.first { it.faderIndex == 6 }.isMuted)

        // Indice fader non valido rifiutato con errorMessage
        val invalidFaderErr = controller.setChannelMute(9, true)
        assertTrue(invalidFaderErr.isFailure)
        assertNotNull(controller.state.value.errorMessage)
        controller.clearError()
        assertNull(controller.state.value.errorMessage)

        // Refresh rilegge il modello dal mixer
        assertTrue(controller.refresh().isSuccess)
        assertEquals(false, controller.state.value.faders.first { it.faderIndex == 0 }.isMuted)

        // Errore in scrittura USB aggiorna errorMessage
        transport.writeError = IOException("USB write timeout")
        assertTrue(controller.setChannelMute(0, true).isFailure)
        assertEquals("USB write timeout", controller.state.value.errorMessage)
        transport.writeError = null

        // Hook non usati (onPhaseSelected, onTrackFade) sono no-op sicuri
        controller.onPhaseSelected(1L, null)
        controller.onTrackFade(10L, 2000, null)

        // Release pulisce monitoring e disconnette
        controller.release()
        assertTrue(transport.stoppedMonitoring)
        assertEquals(RodeConnectionStatus.DISCONNECTED, controller.state.value.status)
    }

    @Test
    fun controller_handlesConnectErrorsPermissionAndCallbacks() = runTest {
        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        val transport = FakeRodeHidTransport(connectBehavior = { RodeConnectResult.DeviceNotFound })
        val controller = RodeMixerController(transport = transport, scope = testScope, writeSpacingMs = 0L)

        // DeviceNotFound
        assertTrue(controller.connect().isFailure)
        assertEquals(RodeConnectionStatus.DISCONNECTED, controller.state.value.status)

        // PermissionRequired -> poi permesso concesso via callback
        transport.connectBehavior = { RodeConnectResult.PermissionRequired("RODECaster Pro II") }
        assertTrue(controller.connect().isFailure)
        assertEquals(RodeConnectionStatus.PERMISSION_REQUIRED, controller.state.value.status)

        transport.connectBehavior = { RodeConnectResult.Connected("RODECaster Pro II") }
        transport.onPermGranted?.invoke()
        testScope.advanceUntilIdle()
        assertEquals(RodeConnectionStatus.CONNECTED, controller.state.value.status)

        // Detach esterno
        transport.onDetached?.invoke()
        testScope.advanceUntilIdle()
        assertEquals(RodeConnectionStatus.DISCONNECTED, controller.state.value.status)

        // Connect Error
        transport.connectBehavior = { RodeConnectResult.Error("Errore USB") }
        assertTrue(controller.connect().isFailure)
        assertEquals(RodeConnectionStatus.ERROR, controller.state.value.status)
        assertEquals("Errore USB", controller.state.value.errorMessage)

        // Dump valido ma senza ingressi Pro II
        transport.connectBehavior = { RodeConnectResult.Connected("Altro") }
        transport.dumpProvider = { RodeValueTreeCodec.encodeNode(RodeNode("EMPTY")) }
        assertTrue(controller.connect().isFailure)
        assertEquals(RodeConnectionStatus.ERROR, controller.state.value.status)
        assertFalse(transport.isConnected)
    }

    @Test
    fun controller_processesLivePushFramesForMutesAndLayoutChanges() = runTest {
        val tree = RodeValueTreeAndProtocolTest.buildRealisticPro2Tree()
        val encodedTree = RodeValueTreeCodec.encodeNode(tree)
        val layout = RodeTreeLayout(RodeValueTreeCodec.parse(encodedTree))

        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        var dumpCount = 0
        val transport = FakeRodeHidTransport(
            dumpProvider = {
                dumpCount++
                encodedTree
            }
        )
        val controller = RodeMixerController(transport = transport, scope = testScope, writeSpacingMs = 0L)
        assertTrue(controller.connect().isSuccess)
        assertEquals(1, dumpCount)

        // 1) Push CHANNEL[0].channelOutputMute = true -> aggiorna muto Fader 1 live
        val mutePush = buildPushFrame(
            path = layout.resolvePath(RodeHidProtocol.channelStripKey(0)),
            propName = RodeHidProtocol.PROP_CHANNEL_OUTPUT_MUTE,
            value = RodeValue.Bool(true)
        )
        assertTrue(controller.handlePushReport(mutePush))
        assertEquals(true, controller.state.value.faders.first { it.faderIndex == 0 }.isMuted)
        assertEquals(true, controller.state.value.inputs.first { it.sourceCode == 0 }.isMuted)

        // 2) Push CHANNEL[0].channelOutputMute = false -> unmute live
        val unmutePush = buildPushFrame(
            path = layout.resolvePath(RodeHidProtocol.channelStripKey(0)),
            propName = RodeHidProtocol.PROP_CHANNEL_OUTPUT_MUTE,
            value = RodeValue.Bool(false)
        )
        assertTrue(controller.handlePushReport(unmutePush))
        assertEquals(false, controller.state.value.faders.first { it.faderIndex == 0 }.isMuted)

        // 3) Altri push (es. faderLevel o gain) vengono ignorati dal controller mute-only
        val faderPush = buildPushFrame(
            path = layout.resolvePath(RodeHidProtocol.faderKey(6)),
            propName = RodeHidProtocol.PROP_FADER_LEVEL,
            value = RodeValue.Int32(44),
            compactZero = true
        )
        assertFalse(controller.handlePushReport(faderPush))

        // 4) Push CHANNEL[0].channelInputSource = 10 -> pianifica refresh automatico
        val layoutChangePush = buildPushFrame(
            path = layout.resolvePath(RodeHidProtocol.channelStripKey(0)),
            propName = RodeHidProtocol.PROP_CHANNEL_INPUT_SOURCE,
            value = RodeValue.Int32(10)
        )
        val dumpsBeforeLayoutPush = dumpCount
        assertTrue(controller.handlePushReport(layoutChangePush))
        testScope.advanceUntilIdle()
        assertEquals(dumpsBeforeLayoutPush + 1, dumpCount)

        // Frame non valido ignorato senza errori
        assertFalse(controller.handlePushReport(ByteArray(10)))
    }

    private fun buildPushFrame(
        path: List<Int>,
        propName: String,
        value: RodeValue,
        compactZero: Boolean = false
    ): ByteArray {
        val record = RodeValueTreeCodec.encodeAddressPath(path, compactZero = compactZero) +
            propName.toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00) +
            RodeValueTreeCodec.encodeValue(value)
        val frame = ByteArray(RodeHidProtocol.PACKET_SIZE)
        frame[0] = RodeHidProtocol.REPORT_IN_ID
        java.nio.ByteBuffer.wrap(frame, 1, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(record.size)
        record.copyInto(frame, destinationOffset = 5)
        return frame
    }

    @Test
    fun androidUsbTransport_discoversInterface9HandlesIoAndBroadcasts() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val usbManager = mockk<UsbManager>(relaxed = true)

        // Dispositivo non RØDE + dispositivo RØDE solo interfaccia 3 (deve essere ignorato!)
        val otherDevice = mockUsbDevice(vendorId = 0x1234, deviceId = 1, interfaces = listOf(mockHidInterface(9)))
        val wrongIntfRode = mockUsbDevice(vendorId = RodeHidProtocol.RODE_VENDOR_ID, deviceId = 2, interfaces = listOf(mockHidInterface(3)))

        val epIn = mockEndpoint(UsbConstants.USB_ENDPOINT_XFER_INT, UsbConstants.USB_DIR_IN)
        val epOut = mockEndpoint(UsbConstants.USB_ENDPOINT_XFER_INT, UsbConstants.USB_DIR_OUT)
        val intf9WithOut = mockHidInterface(9, listOf(epIn, epOut))
        val pro2Device = mockUsbDevice(
            vendorId = RodeHidProtocol.RODE_VENDOR_ID,
            deviceId = 3,
            productName = "RODECaster Pro II",
            interfaces = listOf(mockHidInterface(3), intf9WithOut)
        )

        // 1. UsbManager null -> Error
        val nullMgrTransport = AndroidUsbRodeHidTransport(context = context, usbManager = null)
        assertTrue(nullMgrTransport.connect() is RodeConnectResult.Error)

        // 2. Solo dispositivi non RØDE -> DeviceNotFound; RØDE solo interfaccia 3 (USB 2) -> Error esplicito
        every { usbManager.deviceList } returns hashMapOf("a" to otherDevice)
        var clock = 0L
        val transport = AndroidUsbRodeHidTransport(
            context = context,
            usbManager = usbManager,
            clockMs = { clock },
            dumpInitialTimeoutMs = 200L,
            dumpIdleTimeoutMs = 50L,
            dumpMaxDurationMs = 1_000L
        )
        assertEquals(RodeConnectResult.DeviceNotFound, transport.connect())

        every { usbManager.deviceList } returns hashMapOf("a" to otherDevice, "b" to wrongIntfRode)
        assertTrue(transport.connect() is RodeConnectResult.Error)

        // 3. Permesso mancante -> PermissionRequired + requestPermission
        every { usbManager.deviceList } returns hashMapOf("rode" to pro2Device)
        every { usbManager.hasPermission(pro2Device) } returns false
        val permRes = transport.connect()
        assertTrue(permRes is RodeConnectResult.PermissionRequired)
        verify { usbManager.requestPermission(pro2Device, any()) }

        // 4. openDevice null -> Error
        every { usbManager.hasPermission(pro2Device) } returns true
        every { usbManager.openDevice(pro2Device) } returns null
        assertTrue(transport.connect() is RodeConnectResult.Error)

        // 5. claimInterface false -> Error + close()
        val conn = mockk<UsbDeviceConnection>(relaxed = true)
        every { usbManager.openDevice(pro2Device) } returns conn
        every { conn.claimInterface(intf9WithOut, true) } returns false
        assertTrue(transport.connect() is RodeConnectResult.Error)
        verify { conn.close() }

        // 6. Connessione valida + dump multi-frame con epIn.maxPacketSize = 63 (reassembly 63+63+63+63+4 = 256B + ACK 63+1 = 64B)
        every { epIn.maxPacketSize } returns 63
        every { epOut.maxPacketSize } returns 63
        every { conn.claimInterface(intf9WithOut, true) } returns true
        every { conn.controlTransfer(0x81, 0x06, 0x2200, 9, any(), any(), any()) } answers {
            val descBuf = arg<ByteArray>(4)
            descBuf[0] = 0x06
            descBuf[1] = 0x00
            2
        }
        val connected = transport.connect()
        assertTrue(connected is RodeConnectResult.Connected)
        assertTrue(transport.isConnected)

        val rawDump = RodeValueTreeCodec.encodeNode(RodeValueTreeAndProtocolTest.buildRealisticPro2Tree())
        val usbInQueue = ArrayDeque<ByteArray>()
        // Report 0x02 ACK da 64 byte spezzato in 63 + 1
        val ack64 = ByteArray(64).also {
            it[0] = RodeHidProtocol.REPORT_ACK_ID
            it[1] = 'A'.code.toByte()
        }
        usbInQueue.addLast(ack64.copyOfRange(0, 63))
        usbInQueue.addLast(ack64.copyOfRange(63, 64))
        // Report 0x02 ACK tardivo nel loop dump
        usbInQueue.addLast(ack64.copyOfRange(0, 63))
        usbInQueue.addLast(ack64.copyOfRange(63, 64))
        // Frame dump Report 0x04 da 256 byte spezzati in 63+63+63+63+4
        var off = 0
        while (off < rawDump.size) {
            val end = minOf(off + 255, rawDump.size)
            val fullReport = ByteArray(RodeHidProtocol.PACKET_SIZE)
            fullReport[0] = RodeHidProtocol.REPORT_IN_ID
            rawDump.copyInto(fullReport, destinationOffset = 1, startIndex = off, endIndex = end)
            var p = 0
            while (p < fullReport.size) {
                val pEnd = minOf(p + 63, fullReport.size)
                usbInQueue.addLast(fullReport.copyOfRange(p, pEnd))
                p = pEnd
            }
            off = end
        }
        every { conn.bulkTransfer(epOut, any(), RodeHidProtocol.MODE_PACKET_SIZE, any()) } returns RodeHidProtocol.MODE_PACKET_SIZE
        every { conn.bulkTransfer(epOut, any(), RodeHidProtocol.PACKET_SIZE, any()) } returns RodeHidProtocol.PACKET_SIZE
        every { conn.bulkTransfer(epIn, any(), 63, any()) } answers {
            val buf = secondArg<ByteArray>()
            val next = usbInQueue.removeFirstOrNull()
            if (next != null) {
                next.copyInto(buf)
                clock += 2L
                next.size
            } else {
                clock += 100L
                -1
            }
        }
        val received = transport.requestModelDump()
        assertTrue(received.size >= rawDump.size)
        assertEquals("RODECASTER_MODEL", RodeValueTreeCodec.parse(received).name)

        // readNextPushReport legge un frame push asincrono (e ritorna null in caso di timeout)
        assertTrue(transport.supportsLivePush)
        assertNull(transport.readNextPushReport(timeoutMs = 20))
        val singlePush = ByteArray(RodeHidProtocol.PACKET_SIZE).also { it[0] = RodeHidProtocol.REPORT_IN_ID; it[1] = 0x10 }
        usbInQueue.addLast(singlePush.copyOfRange(0, 63))
        usbInQueue.addLast(singlePush.copyOfRange(63, 126))
        usbInQueue.addLast(singlePush.copyOfRange(126, 189))
        usbInQueue.addLast(singlePush.copyOfRange(189, 252))
        usbInQueue.addLast(singlePush.copyOfRange(252, 256))
        val pushed = transport.readNextPushReport(timeoutMs = 20)
        assertNotNull(pushed)
        assertEquals(RodeHidProtocol.PACKET_SIZE, pushed!!.size)

        // writeReport valido e guardie di sicurezza (rifiuta Report 0x01 non 'N' o lunghezza errata!)
        transport.writeReport(RodeHidProtocol.buildDumpRequestPacket())
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { transport.writeReport(ByteArray(10)) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            val forbiddenReport1 = ByteArray(RodeHidProtocol.MODE_PACKET_SIZE).also {
                it[0] = 0x01
                it[1] = 'M'.code.toByte()
            }
            kotlinx.coroutines.runBlocking { transport.writeReport(forbiddenReport1) }
        }

        // Write failure -> IOException
        every { conn.bulkTransfer(epOut, any(), RodeHidProtocol.PACKET_SIZE, any()) } returns -1
        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking { transport.writeReport(RodeHidProtocol.buildDumpRequestPacket()) }
        }

        // Monitoring broadcasts (permission granted via extra or UsbManager, USB attach & detach)
        var permCallbackCount = 0
        var detachCallbackCount = 0
        var attachCallbackCount = 0
        transport.startMonitoring(
            onPermissionGranted = { permCallbackCount++ },
            onDeviceDetached = { detachCallbackCount++ },
            onDeviceAttached = { attachCallbackCount++ }
        )
        // Idempotent second call
        transport.startMonitoring({}, {})

        // Immutable PendingIntent fallback: senza EXTRA_PERMISSION_GRANTED controlla usbManager.hasPermission
        every { usbManager.hasPermission(pro2Device) } returns true
        context.sendBroadcast(
            Intent(AndroidUsbRodeHidTransport.ACTION_USB_PERMISSION)
                .setPackage(context.packageName)
        )
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals(1, permCallbackCount)

        every { usbManager.hasPermission(pro2Device) } returns false
        context.sendBroadcast(
            Intent(AndroidUsbRodeHidTransport.ACTION_USB_PERMISSION)
                .setPackage(context.packageName)
        )
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals(1, permCallbackCount)

        context.sendBroadcast(
            Intent(AndroidUsbRodeHidTransport.ACTION_USB_PERMISSION)
                .setPackage(context.packageName)
                .putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, true)
        )
        every { usbManager.deviceList } returns hashMapOf("other" to otherDevice)
        context.sendBroadcast(
            Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        )
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals(0, attachCallbackCount)

        every { usbManager.deviceList } returns hashMapOf("rode" to pro2Device)
        context.sendBroadcast(
            Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        )
        context.sendBroadcast(
            Intent(UsbManager.ACTION_USB_DEVICE_DETACHED)
                .putExtra(UsbManager.EXTRA_DEVICE, pro2Device)
        )
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals(2, permCallbackCount)
        assertEquals(1, attachCallbackCount)
        assertEquals(1, detachCallbackCount)
        assertFalse(transport.isConnected)
        transport.stopMonitoring()
        transport.stopMonitoring()

        // 7. Fallback controlTransfer SET_REPORT quando manca epOut + empty dump timeout
        every { epIn.maxPacketSize } returns 0
        val intf9NoOut = mockHidInterface(9, listOf(epIn))
        val pro2ControlOnly = mockUsbDevice(
            vendorId = RodeHidProtocol.RODE_VENDOR_ID,
            deviceId = 4,
            interfaces = listOf(intf9NoOut)
        )
        every { usbManager.deviceList } returns hashMapOf("rode2" to pro2ControlOnly)
        every { usbManager.hasPermission(pro2ControlOnly) } returns true
        every { usbManager.openDevice(pro2ControlOnly) } returns conn
        every { conn.claimInterface(intf9NoOut, true) } returns true
        every { conn.controlTransfer(0x21, 0x09, any(), 9, any(), any(), any()) } returns RodeHidProtocol.PACKET_SIZE
        every { conn.bulkTransfer(epIn, any(), RodeHidProtocol.PACKET_SIZE, any()) } answers {
            clock += 300L
            -1
        }
        assertTrue(transport.connect() is RodeConnectResult.Connected)
        transport.writeReport(RodeHidProtocol.buildDumpRequestPacket())
        verify { conn.controlTransfer(0x21, 0x09, 0x0203, 9, any(), RodeHidProtocol.PACKET_SIZE, any()) }

        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking { transport.requestModelDump() }
        }
        transport.disconnect()

        // 8. Missing IN endpoint -> Error
        val intfNoIn = mockHidInterface(9, emptyList())
        val pro2NoIn = mockUsbDevice(
            vendorId = RodeHidProtocol.RODE_VENDOR_ID,
            deviceId = 5,
            interfaces = listOf(intfNoIn)
        )
        every { usbManager.deviceList } returns hashMapOf("rode3" to pro2NoIn)
        every { usbManager.hasPermission(pro2NoIn) } returns true
        assertTrue(transport.connect() is RodeConnectResult.Error)
    }

    @Test
    fun controller_muteAutomationWorkflow_unmutesMusicAndSongMicsAndReMutesUnconditionallyOnStop() = runTest {
        val transport = FakeRodeHidTransport()
        val controller = RodeMixerController(
            transport = transport,
            scope = TestScope(StandardTestDispatcher(testScheduler)),
            writeSpacingMs = 0L
        )

        val autoConfig = RodeAutomationConfig(
            enabled = true,
            musicFaderIndices = setOf(6),
            alwaysMuteOnStopFaderIndices = setOf(0, 1)
        )
        controller.setAutomationConfig(autoConfig)
        assertEquals(autoConfig, controller.state.value.automationConfig)

        // Disconnesso -> muteAllAssignedFaders fallisce, hook track non crashano
        assertTrue(controller.muteAllAssignedFaders().isFailure)
        controller.onTrackStart(1L, null)
        controller.onTrackStop(1L, null)

        // Connessione: config automazione preservata
        assertTrue(controller.connect().isSuccess)
        assertEquals(autoConfig, controller.state.value.automationConfig)

        // Metti tutti i canali in Muto ora
        val mutedRes = controller.muteAllAssignedFaders()
        assertTrue(mutedRes.isSuccess)
        assertEquals(9, mutedRes.getOrNull())
        assertTrue(controller.state.value.assignedFaders.all { it.isMuted == true })

        // 1. Brano senza microfoni extra: apre solo Musica (idx 6), Mic 1 (idx 0) e Mic 2 (idx 1) restano Muto
        val packetsBeforeStart1 = transport.writtenPackets.size
        controller.onTrackStart(10L, null)
        assertEquals(false, controller.state.value.faders.first { it.faderIndex == 6 }.isMuted)
        assertEquals(true, controller.state.value.faders.first { it.faderIndex == 0 }.isMuted)
        assertEquals(packetsBeforeStart1 + 1, transport.writtenPackets.size)

        // Fine brano -> invia Muto incondizionatamente a musica (6) + alwaysMuteOnStop (0, 1)
        val packetsBeforeStop1 = transport.writtenPackets.size
        controller.onTrackStop(10L, null)
        assertEquals(true, controller.state.value.faders.first { it.faderIndex == 6 }.isMuted)
        assertEquals(true, controller.state.value.faders.first { it.faderIndex == 0 }.isMuted)
        assertEquals(true, controller.state.value.faders.first { it.faderIndex == 1 }.isMuted)
        assertEquals(packetsBeforeStop1 + 3, transport.writtenPackets.size)

        // 2. Brano con microfono extra (idx 2, non in alwaysMuteOnStop): apre Musica (6) + Mic (2)
        val songMicsJson = RodeMixerPresetCodec.encode(RodeMixerPreset(unmuteFaderIndices = setOf(2)))
        controller.onTrackStart(11L, songMicsJson)
        assertEquals(false, controller.state.value.faders.first { it.faderIndex == 6 }.isMuted)
        assertEquals(false, controller.state.value.faders.first { it.faderIndex == 2 }.isMuted)
        assertEquals(true, controller.state.value.faders.first { it.faderIndex == 0 }.isMuted)

        // Fine brano anche passando null: activeTrackUnmutedFaders garantisce che idx 2 venga rimutato!
        controller.onTrackStop(11L, null)
        assertEquals(true, controller.state.value.faders.first { it.faderIndex == 6 }.isMuted)
        assertEquals(true, controller.state.value.faders.first { it.faderIndex == 2 }.isMuted)
        assertEquals(true, controller.state.value.faders.first { it.faderIndex == 0 }.isMuted)
        assertEquals(true, controller.state.value.faders.first { it.faderIndex == 1 }.isMuted)

        // 3. Anche se la cache pensa già che idx 6 sia unmuted, onTrackStart invia comunque il pacchetto USB!
        controller.setChannelMute(6, false)
        val packetsBeforeRedundantStart = transport.writtenPackets.size
        controller.onTrackStart(12L, null)
        assertEquals(packetsBeforeRedundantStart + 1, transport.writtenPackets.size)

        // 4. Automazione disabilitata -> non invia pacchetti
        controller.setAutomationConfig(autoConfig.copy(enabled = false))
        val writesBeforeDisabled = transport.writtenPackets.size
        controller.onTrackStart(15L, songMicsJson)
        controller.onTrackStop(15L, songMicsJson)
        assertEquals(writesBeforeDisabled, transport.writtenPackets.size)

        controller.disconnect()
        assertEquals(autoConfig.copy(enabled = false), controller.state.value.automationConfig)
    }

    @Test
    fun controller_monitorSpeakerProtection_disablesAutoMuteAndUnmutesExternalSpeakers() = runTest {
        val treeWithAutoMuteAndMutedSpeakers = RodeValueTreeAndProtocolTest.buildRealisticPro2Tree(
            monitorMuted = true,
            monitorAutoMute = true,
            monitorAutoMuteActive = true
        )
        val layout = RodeTreeLayout(treeWithAutoMuteAndMutedSpeakers)
        val transport = FakeRodeHidTransport(
            dumpProvider = { RodeValueTreeCodec.encodeNode(treeWithAutoMuteAndMutedSpeakers) }
        )
        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        val controller = RodeMixerController(
            transport = transport,
            scope = testScope,
            writeSpacingMs = 0L
        )
        controller.setAutomationConfig(
            RodeAutomationConfig(
                enabled = true,
                musicFaderIndices = setOf(6),
                alwaysMuteOnStopFaderIndices = setOf(0),
                keepMonitorSpeakersUnmuted = true
            )
        )

        // Disconnesso -> controlli monitor falliscono
        assertTrue(controller.setMonitorMute(false).isFailure)
        assertTrue(controller.setMonitorAutoMute(false).isFailure)

        // Connessione: disattiva automaticamente outputMonAutoMute = false sul mixer
        assertTrue(controller.connect().isSuccess)
        assertEquals(false, controller.state.value.monitorAutoMuteEnabled)
        assertEquals(false, controller.state.value.monitorAutoMuteActive)
        assertEquals(true, controller.state.value.monitorMuted)
        assertTrue(
            transport.writtenPackets.any { pkt ->
                val parsed = RodeHidProtocol.parsePushFrame(
                    pkt.copyOf().also { it[0] = RodeHidProtocol.REPORT_IN_ID }
                )
                parsed?.propertyName == RodeHidProtocol.PROP_OUTPUT_MON_AUTO_MUTE &&
                    parsed.value == RodeValue.Bool(false)
            }
        )

        // Avvio brano con microfono: sblocca prima outputMonMute = false, poi apre i canali
        val beforeStartCount = transport.writtenPackets.size
        val songMicsJson = RodeMixerPresetCodec.encode(RodeMixerPreset(unmuteFaderIndices = setOf(0)))
        controller.onTrackStart(20L, songMicsJson)
        assertEquals(false, controller.state.value.monitorMuted)
        assertEquals(false, controller.state.value.faders.first { it.faderIndex == 6 }.isMuted)
        assertEquals(false, controller.state.value.faders.first { it.faderIndex == 0 }.isMuted)
        val firstStartWrite = RodeHidProtocol.parsePushFrame(
            transport.writtenPackets[beforeStartCount].copyOf().also { it[0] = RodeHidProtocol.REPORT_IN_ID }
        )
        assertEquals(RodeHidProtocol.PROP_OUTPUT_MON_MUTE, firstStartWrite?.propertyName)
        assertEquals(RodeValue.Bool(false), firstStartWrite?.value)

        // Controlli manuali monitor
        assertTrue(controller.setMonitorMute(true).isSuccess)
        assertEquals(true, controller.state.value.monitorMuted)
        assertTrue(controller.setMonitorAutoMute(true).isSuccess)
        assertEquals(true, controller.state.value.monitorAutoMuteEnabled)

        // Fine brano: con keepMonitorSpeakersUnmuted=true riattiva anche monitorMuted se risultava muto
        controller.onTrackStop(20L, songMicsJson)
        assertEquals(false, controller.state.value.monitorMuted)
        assertEquals(true, controller.state.value.faders.first { it.faderIndex == 6 }.isMuted)
        assertEquals(true, controller.state.value.faders.first { it.faderIndex == 0 }.isMuted)

        // Push live dal mixer su OUTPUT[0] aggiorna lo stato locale in tempo reale
        val pushMonMute = RodeHidProtocol.buildMonitorMuteWritePacket(layout, muted = true)
            .also { it[0] = RodeHidProtocol.REPORT_IN_ID }
        val pushAutoMute = RodeHidProtocol.buildMonitorAutoMuteWritePacket(layout, enabled = false)
            .also { it[0] = RodeHidProtocol.REPORT_IN_ID }
        val pushAutoActive = RodeHidProtocol.buildPropertyWritePacket(
            layout = layout,
            key = RodeHidProtocol.outputKey(),
            propertyName = RodeHidProtocol.PROP_OUTPUT_MON_AUTO_MUTE_ACTIVE,
            value = RodeValue.Bool(true)
        ).also { it[0] = RodeHidProtocol.REPORT_IN_ID }
        assertTrue(controller.handlePushReport(pushMonMute))
        assertTrue(controller.handlePushReport(pushAutoMute))
        assertTrue(controller.handlePushReport(pushAutoActive))
        assertEquals(true, controller.state.value.monitorMuted)
        assertEquals(false, controller.state.value.monitorAutoMuteEnabled)
        assertEquals(true, controller.state.value.monitorAutoMuteActive)

        // Errore di scrittura su controlli monitor -> fallisce con messaggio chiaro
        transport.writeError = IOException("USB write failed")
        assertTrue(controller.setMonitorMute(false).isFailure)
        assertNotNull(controller.state.value.errorMessage)
        assertTrue(controller.setMonitorAutoMute(false).isFailure)
        transport.writeError = null

        // Con protezione casse disattivata, onTrackStart non invia scritture monitor
        controller.setAutomationConfig(
            controller.state.value.automationConfig.copy(keepMonitorSpeakersUnmuted = false)
        )
        val beforeUnprotectedStart = transport.writtenPackets.size
        controller.onTrackStart(21L, null)
        val unprotectedWrites = transport.writtenPackets.drop(beforeUnprotectedStart)
            .mapNotNull { pkt ->
                RodeHidProtocol.parsePushFrame(
                    pkt.copyOf().also { it[0] = RodeHidProtocol.REPORT_IN_ID }
                )?.propertyName
            }
        assertFalse(RodeHidProtocol.PROP_OUTPUT_MON_MUTE in unprotectedWrites)
        assertFalse(RodeHidProtocol.PROP_OUTPUT_MON_AUTO_MUTE in unprotectedWrites)

        controller.disconnect()
    }

    @Test
    fun controller_automationSkipsUnassignedFadersOnMixer() = runTest {
        val treeUnassigned = RodeValueTreeAndProtocolTest.buildRealisticPro2Tree(unassigned255 = true)
        val transport = FakeRodeHidTransport(
            dumpProvider = { RodeValueTreeCodec.encodeNode(treeUnassigned) }
        )
        val controller = RodeMixerController(
            transport = transport,
            scope = TestScope(StandardTestDispatcher(testScheduler)),
            writeSpacingMs = 0L
        )
        // idx 3 e' non assegnato (255), mentre idx 6 (USB 1) e idx 0 (Mic 1) sono assegnati
        controller.setAutomationConfig(
            RodeAutomationConfig(
                enabled = true,
                musicFaderIndices = setOf(6, 3),
                alwaysMuteOnStopFaderIndices = setOf(0, 3)
            )
        )
        assertTrue(controller.connect().isSuccess)
        val beforeStart = transport.writtenPackets.size
        controller.onTrackStart(30L, null)
        // Solo idx 6 viene inviato (idx 3 ignorato perche' non assegnato)
        assertEquals(beforeStart + 1, transport.writtenPackets.size)

        val beforeStop = transport.writtenPackets.size
        controller.onTrackStop(30L, null)
        // Solo idx 0 e idx 6 vengono inviati (idx 3 ignorato)
        assertEquals(beforeStop + 2, transport.writtenPackets.size)
        controller.disconnect()
    }

    @Test
    fun controller_autoConnectOnInitAndAttachAndDetectDefaultMusicFader() = runTest {
        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        var dumps = 0
        val transport = FakeRodeHidTransport(
            dumpProvider = {
                dumps++
                RodeValueTreeCodec.encodeNode(RodeValueTreeAndProtocolTest.buildRealisticPro2Tree())
            }
        )
        val controller = RodeMixerController(
            transport = transport,
            scope = testScope,
            writeSpacingMs = 0L,
            autoConnectOnInit = true
        )
        testScope.advanceUntilIdle()
        assertEquals(RodeConnectionStatus.CONNECTED, controller.state.value.status)
        assertEquals(1, dumps)

        // connectIfDisconnected quando già connesso è no-op immediato (nessun nuovo dump)
        assertTrue(controller.connectIfDisconnected().isSuccess)
        assertEquals(1, dumps)

        // Detach e ri-attach automatico via callback USB + triggerAutoConnect
        transport.onDetached?.invoke()
        testScope.advanceUntilIdle()
        assertEquals(RodeConnectionStatus.DISCONNECTED, controller.state.value.status)

        transport.onAttached?.invoke()
        testScope.advanceUntilIdle()
        assertEquals(RodeConnectionStatus.CONNECTED, controller.state.value.status)
        assertEquals(2, dumps)

        controller.disconnect()
        transport.connectBehavior = { throw RuntimeException("USB crash") }
        controller.triggerAutoConnect()
        testScope.advanceUntilIdle()
        assertEquals(RodeConnectionStatus.ERROR, controller.state.value.status)
        assertEquals("USB crash", controller.state.value.errorMessage)

        // Verifica priorità detectDefaultMusicFader (USB 1 Main -> Music -> Chat -> Bluetooth; oppure Bluetooth first)
        assertNull(RodeAutomationConfig.detectDefaultMusicFader(emptyList()))
        val faders = listOf(
            it.parrocchia.coro.domain.mixer.RodeVirtualFader(0, 0, "Mic 1", 100),
            it.parrocchia.coro.domain.mixer.RodeVirtualFader(4, 10, "Bluetooth", 100),
            it.parrocchia.coro.domain.mixer.RodeVirtualFader(5, 8, "USB 1 Chat", 100),
            it.parrocchia.coro.domain.mixer.RodeVirtualFader(6, 7, "USB 1 (Main)", 100)
        )
        assertEquals(6, RodeAutomationConfig.detectDefaultMusicFader(faders, preferBluetooth = false)?.faderIndex)
        assertEquals(4, RodeAutomationConfig.detectDefaultMusicFader(faders, preferBluetooth = true)?.faderIndex)
        val onlyMics = listOf(it.parrocchia.coro.domain.mixer.RodeVirtualFader(0, 0, "Mic 1", 100))
        assertNull(RodeAutomationConfig.detectDefaultMusicFader(onlyMics))
    }

    private fun mockEndpoint(type: Int, direction: Int): UsbEndpoint {
        val ep = mockk<UsbEndpoint>(relaxed = true)
        every { ep.type } returns type
        every { ep.direction } returns direction
        return ep
    }

    private fun mockHidInterface(id: Int, endpoints: List<UsbEndpoint> = emptyList()): UsbInterface {
        val intf = mockk<UsbInterface>(relaxed = true)
        every { intf.id } returns id
        every { intf.interfaceClass } returns UsbConstants.USB_CLASS_HID
        every { intf.endpointCount } returns endpoints.size
        endpoints.forEachIndexed { idx, ep -> every { intf.getEndpoint(idx) } returns ep }
        return intf
    }

    private fun mockUsbDevice(
        vendorId: Int,
        deviceId: Int,
        productName: String? = null,
        interfaces: List<UsbInterface>
    ): UsbDevice {
        val dev = mockk<UsbDevice>(relaxed = true)
        every { dev.vendorId } returns vendorId
        every { dev.deviceId } returns deviceId
        every { dev.productName } returns productName
        every { dev.interfaceCount } returns interfaces.size
        interfaces.forEachIndexed { idx, intf -> every { dev.getInterface(idx) } returns intf }
        return dev
    }
}
