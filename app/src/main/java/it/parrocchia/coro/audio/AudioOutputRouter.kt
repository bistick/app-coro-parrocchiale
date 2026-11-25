package it.parrocchia.coro.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import it.parrocchia.coro.domain.model.AudioOutputDeviceSummary
import it.parrocchia.coro.domain.model.AudioOutputRoute
import it.parrocchia.coro.domain.model.AudioOutputStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Descrittore leggero di un dispositivo audio di uscita per permettere test unitari
 * deterministici senza dipendere dal costruttore package-private di [AudioDeviceInfo].
 */
data class AudioDeviceDescriptor(
    val id: Int,
    val type: Int,
    val productName: String,
    val platformDevice: AudioDeviceInfo? = null
)

/**
 * Gestisce il rilevamento live dei dispositivi di uscita (Bluetooth, USB Audio, Altoparlante)
 * e la risoluzione del dispositivo preferito da applicare sia ad [android.media.AudioTrack]
 * (FluidSynth MIDI) sia a [androidx.media3.exoplayer.ExoPlayer] (MP3/WAV).
 */
class AudioOutputRouter(
    private val audioManager: AudioManager?,
    private val deviceProvider: () -> List<AudioDeviceDescriptor> = {
        readPlatformOutputDevices(audioManager)
    },
    private val registerSystemCallback: Boolean = true
) {
    constructor(context: Context) : this(
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    )

    private var selectedRoute: AudioOutputRoute = AudioOutputRoute.AUTO
    private var cachedDevices: List<AudioDeviceDescriptor> = emptyList()
    private var listener: ((AudioDeviceInfo?) -> Unit)? = null

    private val _status = MutableStateFlow(AudioOutputStatus())
    val status: StateFlow<AudioOutputStatus> = _status.asStateFlow()

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            refreshDevices()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            refreshDevices()
        }
    }

    init {
        if (registerSystemCallback && audioManager != null) {
            runCatching {
                audioManager?.registerAudioDeviceCallback(
                    deviceCallback,
                    Handler(Looper.getMainLooper())
                )
            }
        }
        refreshDevices()
    }

    fun setOnPreferredDeviceChangedListener(callback: ((AudioDeviceInfo?) -> Unit)?) {
        listener = callback
        callback?.invoke(resolvePreferredAudioDevice())
    }

    fun setPreferredRoute(route: AudioOutputRoute) {
        if (selectedRoute == route) {
            listener?.invoke(resolvePreferredAudioDevice())
            return
        }
        selectedRoute = route
        publishStatusAndNotify()
    }

    fun refreshDevices() {
        cachedDevices = runCatching { deviceProvider() }.getOrDefault(emptyList())
        publishStatusAndNotify()
    }

    fun resolvePreferredAudioDevice(): AudioDeviceInfo? {
        return resolveDescriptor(selectedRoute, cachedDevices)?.platformDevice
    }

    fun release() {
        listener = null
        if (registerSystemCallback && audioManager != null) {
            runCatching { audioManager?.unregisterAudioDeviceCallback(deviceCallback) }
        }
    }

    private fun publishStatusAndNotify() {
        val bt = resolveDescriptor(AudioOutputRoute.BLUETOOTH, cachedDevices)?.toSummary(AudioOutputRoute.BLUETOOTH)
        val usb = resolveDescriptor(AudioOutputRoute.USB, cachedDevices)?.toSummary(AudioOutputRoute.USB)
        val spk = resolveDescriptor(AudioOutputRoute.SPEAKER, cachedDevices)?.toSummary(AudioOutputRoute.SPEAKER)

        _status.value = AudioOutputStatus(
            selectedRoute = selectedRoute,
            bluetoothDevice = bt,
            usbDevice = usb,
            speakerDevice = spk
        )
        listener?.invoke(resolvePreferredAudioDevice())
    }

    private fun AudioDeviceDescriptor.toSummary(route: AudioOutputRoute): AudioOutputDeviceSummary =
        AudioOutputDeviceSummary(
            id = id,
            productName = productName.ifBlank { route.displayLabel },
            route = route
        )

    companion object {
        fun classifyRoute(deviceType: Int): AudioOutputRoute? {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (deviceType == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    deviceType == AudioDeviceInfo.TYPE_BLE_SPEAKER
                ) {
                    return AudioOutputRoute.BLUETOOTH
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                deviceType == AudioDeviceInfo.TYPE_HEARING_AID
            ) {
                return AudioOutputRoute.BLUETOOTH
            }
            return when (deviceType) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> AudioOutputRoute.BLUETOOTH
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_ACCESSORY -> AudioOutputRoute.USB
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioOutputRoute.SPEAKER
                else -> null
            }
        }

        fun resolveDescriptor(
            route: AudioOutputRoute,
            devices: List<AudioDeviceDescriptor>
        ): AudioDeviceDescriptor? {
            if (route == AudioOutputRoute.AUTO) return null
            val matching = devices.filter { classifyRoute(it.type) == route }
            if (matching.isEmpty()) return null
            return when (route) {
                AudioOutputRoute.BLUETOOTH ->
                    matching.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP } ?: matching.first()
                AudioOutputRoute.USB ->
                    matching.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE }
                        ?: matching.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
                        ?: matching.first()
                AudioOutputRoute.SPEAKER -> matching.first()
                AudioOutputRoute.AUTO -> null
            }
        }

        private fun readPlatformOutputDevices(audioManager: AudioManager?): List<AudioDeviceDescriptor> {
            val raw = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: return emptyList()
            return raw.map { info ->
                AudioDeviceDescriptor(
                    id = info.id,
                    type = info.type,
                    productName = info.productName?.toString().orEmpty().trim(),
                    platformDevice = info
                )
            }
        }
    }
}
