package it.parrocchia.coro

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import io.mockk.verify
import it.parrocchia.coro.audio.AudioDeviceDescriptor
import it.parrocchia.coro.audio.AudioOutputRouter
import it.parrocchia.coro.domain.model.AudioOutputRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioOutputRouterTest {

    @Test
    fun routeClassificationAndPrioritySelection_coverAllDeviceTypes() {
        assertEquals(AudioOutputRoute.AUTO, AudioOutputRoute.fromStoredName(null))
        assertEquals(AudioOutputRoute.AUTO, AudioOutputRoute.fromStoredName("UNKNOWN"))
        assertEquals(AudioOutputRoute.BLUETOOTH, AudioOutputRoute.fromStoredName("BLUETOOTH"))
        assertEquals(AudioOutputRoute.USB, AudioOutputRoute.fromStoredName("USB"))
        assertEquals(AudioOutputRoute.SPEAKER, AudioOutputRoute.fromStoredName("SPEAKER"))

        assertEquals(AudioOutputRoute.BLUETOOTH, AudioOutputRouter.classifyRoute(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertEquals(AudioOutputRoute.BLUETOOTH, AudioOutputRouter.classifyRoute(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertEquals(AudioOutputRoute.BLUETOOTH, AudioOutputRouter.classifyRoute(AudioDeviceInfo.TYPE_BLE_SPEAKER))
        assertEquals(AudioOutputRoute.BLUETOOTH, AudioOutputRouter.classifyRoute(AudioDeviceInfo.TYPE_HEARING_AID))
        assertEquals(AudioOutputRoute.USB, AudioOutputRouter.classifyRoute(AudioDeviceInfo.TYPE_USB_DEVICE))
        assertEquals(AudioOutputRoute.USB, AudioOutputRouter.classifyRoute(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertEquals(AudioOutputRoute.USB, AudioOutputRouter.classifyRoute(AudioDeviceInfo.TYPE_USB_ACCESSORY))
        assertEquals(AudioOutputRoute.SPEAKER, AudioOutputRouter.classifyRoute(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertNull(AudioOutputRouter.classifyRoute(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))

        val ble = AudioDeviceDescriptor(1, AudioDeviceInfo.TYPE_BLE_HEADSET, "BLE Cuffie")
        val a2dp = AudioDeviceDescriptor(2, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "RØDECaster BT")
        val usbHeadset = AudioDeviceDescriptor(3, AudioDeviceInfo.TYPE_USB_HEADSET, "USB Cuffie")
        val usbDev = AudioDeviceDescriptor(4, AudioDeviceInfo.TYPE_USB_DEVICE, "RØDECaster Pro II")
        val usbAcc = AudioDeviceDescriptor(5, AudioDeviceInfo.TYPE_USB_ACCESSORY, "USB Acc")
        val speaker = AudioDeviceDescriptor(6, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Altoparlante")

        // A2DP preferred over BLE; USB_DEVICE preferred over USB_HEADSET over USB_ACCESSORY
        assertEquals(a2dp, AudioOutputRouter.resolveDescriptor(AudioOutputRoute.BLUETOOTH, listOf(ble, a2dp)))
        assertEquals(ble, AudioOutputRouter.resolveDescriptor(AudioOutputRoute.BLUETOOTH, listOf(ble)))
        assertEquals(usbDev, AudioOutputRouter.resolveDescriptor(AudioOutputRoute.USB, listOf(usbAcc, usbHeadset, usbDev)))
        assertEquals(usbHeadset, AudioOutputRouter.resolveDescriptor(AudioOutputRoute.USB, listOf(usbAcc, usbHeadset)))
        assertEquals(usbAcc, AudioOutputRouter.resolveDescriptor(AudioOutputRoute.USB, listOf(usbAcc)))
        assertEquals(speaker, AudioOutputRouter.resolveDescriptor(AudioOutputRoute.SPEAKER, listOf(speaker)))
        assertNull(AudioOutputRouter.resolveDescriptor(AudioOutputRoute.AUTO, listOf(a2dp, usbDev, speaker)))
        assertNull(AudioOutputRouter.resolveDescriptor(AudioOutputRoute.USB, listOf(speaker)))
    }

    @Test
    fun router_tracksDevicesNotifiesListenerAndFormatsStatusSummaries() {
        val btInfo = mockk<AudioDeviceInfo>(relaxed = true)
        val usbInfo = mockk<AudioDeviceInfo>(relaxed = true)
        val spkInfo = mockk<AudioDeviceInfo>(relaxed = true)

        var currentDevices = listOf(
            AudioDeviceDescriptor(10, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "RODECaster BT", btInfo),
            AudioDeviceDescriptor(11, AudioDeviceInfo.TYPE_USB_DEVICE, "RØDECaster Pro II", usbInfo),
            AudioDeviceDescriptor(12, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "", spkInfo)
        )

        val notifications = mutableListOf<AudioDeviceInfo?>()
        val router = AudioOutputRouter(
            audioManager = null,
            deviceProvider = { currentDevices },
            registerSystemCallback = false
        )
        router.setOnPreferredDeviceChangedListener { notifications.add(it) }

        // Default AUTO -> null preferred device, both BT & USB detected
        assertNull(router.resolvePreferredAudioDevice())
        assertTrue(router.status.value.hasBothBluetoothAndUsb)
        assertTrue(router.status.value.isSelectedRouteAvailable)
        assertNull(router.status.value.activeTargetDevice)
        assertTrue(router.status.value.summaryLine().contains("BT + USB"))

        // Switch to BLUETOOTH -> resolves btInfo
        router.setPreferredRoute(AudioOutputRoute.BLUETOOTH)
        assertSame(btInfo, router.resolvePreferredAudioDevice())
        assertSame(btInfo, notifications.last())
        assertEquals("RODECaster BT", router.status.value.activeTargetDevice?.productName)
        assertTrue(router.status.value.summaryLine().contains("Forzata su Bluetooth"))

        // Idempotent call still notifies listener
        router.setPreferredRoute(AudioOutputRoute.BLUETOOTH)
        assertSame(btInfo, notifications.last())

        // Switch to USB -> resolves usbInfo
        router.setPreferredRoute(AudioOutputRoute.USB)
        assertSame(usbInfo, router.resolvePreferredAudioDevice())
        assertTrue(router.status.value.summaryLine().contains("Forzata su USB Audio"))

        // Switch to SPEAKER -> resolves spkInfo with fallback label
        router.setPreferredRoute(AudioOutputRoute.SPEAKER)
        assertSame(spkInfo, router.resolvePreferredAudioDevice())
        assertEquals(AudioOutputRoute.SPEAKER.displayLabel, router.status.value.speakerDevice?.productName)
        assertTrue(router.status.value.summaryLine().contains("Altoparlante"))

        // Remove USB device -> USB route reports unavailable & null device
        currentDevices = listOf(
            AudioDeviceDescriptor(10, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "RODECaster BT", btInfo)
        )
        router.setPreferredRoute(AudioOutputRoute.USB)
        router.refreshDevices()
        assertFalse(router.status.value.isSelectedRouteAvailable)
        assertNull(router.resolvePreferredAudioDevice())
        assertTrue(router.status.value.summaryLine().contains("non connesso"))

        // AUTO with only BT
        router.setPreferredRoute(AudioOutputRoute.AUTO)
        assertTrue(router.status.value.summaryLine().contains("RODECaster BT"))

        // Only USB
        currentDevices = listOf(
            AudioDeviceDescriptor(11, AudioDeviceInfo.TYPE_USB_DEVICE, "RØDECaster Pro II", usbInfo)
        )
        router.refreshDevices()
        assertTrue(router.status.value.summaryLine().contains("RØDECaster Pro II"))
        router.setPreferredRoute(AudioOutputRoute.BLUETOOTH)
        assertFalse(router.status.value.isSelectedRouteAvailable)
        assertTrue(router.status.value.summaryLine().contains("non connesso"))

        // No external devices
        currentDevices = emptyList()
        router.setPreferredRoute(AudioOutputRoute.AUTO)
        router.refreshDevices()
        assertTrue(router.status.value.summaryLine().contains("sistema Android"))

        router.release()
    }

    @Test
    fun contextConstructorAndSystemCallbackRegistration_workCleanly() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val realRouter = AudioOutputRouter(context)
        assertNotNull(realRouter.status.value)
        realRouter.release()

        val mockManager = mockk<AudioManager>(relaxed = true)
        val routerWithMock = AudioOutputRouter(audioManager = mockManager, registerSystemCallback = true)
        verify { mockManager.registerAudioDeviceCallback(any(), any()) }
        routerWithMock.release()
        verify { mockManager.unregisterAudioDeviceCallback(any()) }
    }
}
