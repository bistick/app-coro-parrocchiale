package it.parrocchia.coro.domain.mixer

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

enum class RodeConnectionStatus {
    DISCONNECTED,
    PERMISSION_REQUIRED,
    CONNECTING,
    CONNECTED,
    ERROR
}

enum class RodeInputKind {
    MIC,
    LINE,
    BUS
}

enum class RodeInputMode(val displayLabel: String) {
    MIC_PREAMP("Microfono"),
    INSTRUMENT("Strumento"),
    LINE_IN("Linea"),
    DIGITAL("Digitale / USB"),
    BUS("Bus")
}

/**
 * Regole globali di automazione Muto sul RØDECaster Pro II durante l'esecuzione live:
 * - [musicFaderIndices]: canali musica (es. USB 1 Chat / BT) a cui togliere sempre il Muto
 *   all'avvio di qualsiasi brano e rimettere il Muto a fine brano.
 * - [alwaysMuteOnStopFaderIndices]: canali aggiuntivi da mettere sempre in Muto al termine
 *   di qualsiasi brano (oltre ai canali musica e ai microfoni aperti dal singolo brano).
 */
data class RodeAutomationConfig(
    val enabled: Boolean = true,
    val musicFaderIndices: Set<Int> = emptySet(),
    val alwaysMuteOnStopFaderIndices: Set<Int> = emptySet(),
    val keepMonitorSpeakersUnmuted: Boolean = true
) {
    val allConfiguredFaderIndices: Set<Int>
        get() = (musicFaderIndices + alwaysMuteOnStopFaderIndices)
            .filter { it in 0 until RodeInputVolumeChannel.TOTAL_FADER_COUNT }
            .toSet()

    fun fadersToUnmuteOnStart(preset: RodeMixerPreset): List<Int> =
        (musicFaderIndices + preset.unmuteFaderIndices)
            .filter { it in 0 until RodeInputVolumeChannel.TOTAL_FADER_COUNT }
            .sorted()

    fun fadersToMuteOnStop(
        preset: RodeMixerPreset,
        previouslyOpenedFaders: Set<Int> = emptySet()
    ): List<Int> =
        (musicFaderIndices + alwaysMuteOnStopFaderIndices + preset.unmuteFaderIndices + previouslyOpenedFaders)
            .filter { it in 0 until RodeInputVolumeChannel.TOTAL_FADER_COUNT }
            .sorted()
}

object RodeAutomationConfigCodec {
    private const val KEY_ENABLED = "enabled"
    private const val KEY_MUSIC_FADERS = "musicFaderIndices"
    private const val KEY_ALWAYS_MUTE_ON_STOP = "alwaysMuteOnStopFaderIndices"
    private const val KEY_KEEP_MONITOR_UNMUTED = "keepMonitorSpeakersUnmuted"

    fun encode(config: RodeAutomationConfig): String {
        val root = JSONObject()
        root.put(KEY_ENABLED, config.enabled)
        root.put(KEY_MUSIC_FADERS, encodeFaderIndexArray(config.musicFaderIndices))
        root.put(KEY_ALWAYS_MUTE_ON_STOP, encodeFaderIndexArray(config.alwaysMuteOnStopFaderIndices))
        root.put(KEY_KEEP_MONITOR_UNMUTED, config.keepMonitorSpeakersUnmuted)
        return root.toString()
    }

    fun decode(json: String?): RodeAutomationConfig {
        if (json.isNullOrBlank()) return RodeAutomationConfig()
        return runCatching {
            val root = JSONObject(json)
            RodeAutomationConfig(
                enabled = root.optBoolean(KEY_ENABLED, true),
                musicFaderIndices = parseFaderIndexArray(root.optJSONArray(KEY_MUSIC_FADERS)),
                alwaysMuteOnStopFaderIndices = parseFaderIndexArray(root.optJSONArray(KEY_ALWAYS_MUTE_ON_STOP)),
                keepMonitorSpeakersUnmuted = root.optBoolean(KEY_KEEP_MONITOR_UNMUTED, true)
            )
        }.getOrDefault(RodeAutomationConfig())
    }

    internal fun encodeFaderIndexArray(indices: Set<Int>): JSONArray {
        val arr = JSONArray()
        indices
            .filter { it in 0 until RodeInputVolumeChannel.TOTAL_FADER_COUNT }
            .sorted()
            .forEach { arr.put(it) }
        return arr
    }

    internal fun parseFaderIndexArray(array: JSONArray?): Set<Int> {
        if (array == null) return emptySet()
        val out = mutableSetOf<Int>()
        for (i in 0 until array.length()) {
            val value = array.opt(i)
            if (value is Number) {
                val idx = value.toInt()
                if (idx in 0 until RodeInputVolumeChannel.TOTAL_FADER_COUNT) {
                    out.add(idx)
                }
            }
        }
        return out
    }
}

data class RodeInputVolumeChannel(
    val sourceCode: Int,
    val key: String,
    val label: String,
    val kind: RodeInputKind,
    val mode: RodeInputMode,
    val gainProperty: String?,
    val gainDb: Int?,
    val minGainDb: Int,
    val maxGainDb: Int,
    val assignedFaderIndex: Int? = null,
    val faderLevel: Int? = null,
    val isMuted: Boolean? = null
) {
    val isGainControllable: Boolean
        get() = gainProperty != null && gainDb != null

    val assignedFaderNumber: Int?
        get() = assignedFaderIndex?.plus(1)

    val isOnPhysicalFader: Boolean
        get() = assignedFaderIndex != null && assignedFaderIndex < PHYSICAL_FADER_COUNT

    val isOnVirtualFader: Boolean
        get() = assignedFaderIndex != null && assignedFaderIndex in PHYSICAL_FADER_COUNT until TOTAL_FADER_COUNT

    val faderLevelPercent: Int?
        get() = faderLevel?.let { ((it.coerceIn(0, FADER_LEVEL_MAX) * 100f) / FADER_LEVEL_MAX).roundToInt() }

    companion object {
        const val PHYSICAL_FADER_COUNT = 6
        const val TOTAL_FADER_COUNT = 9
        const val FADER_LEVEL_MAX = 127
    }
}

data class RodeVirtualFader(
    val faderIndex: Int,
    val assignedSourceCode: Int?,
    val assignedLabel: String,
    val level: Int,
    val isMuted: Boolean? = null,
    val isSoftwareOverridden: Boolean = false,
    val physicalLevel: Int? = null
) {
    val displayNumber: Int
        get() = faderIndex + 1

    val isPhysical: Boolean
        get() = faderIndex < RodeInputVolumeChannel.PHYSICAL_FADER_COUNT

    val levelPercent: Int
        get() = ((level.coerceIn(0, RodeInputVolumeChannel.FADER_LEVEL_MAX) * 100f) /
            RodeInputVolumeChannel.FADER_LEVEL_MAX).roundToInt()
}

data class RodeMixerState(
    val status: RodeConnectionStatus = RodeConnectionStatus.DISCONNECTED,
    val deviceName: String? = null,
    val firmwareVersion: String? = null,
    val usbExpandedMode: Boolean = false,
    val inputs: List<RodeInputVolumeChannel> = emptyList(),
    val virtualFaders: List<RodeVirtualFader> = emptyList(),
    val faders: List<RodeVirtualFader> = virtualFaders,
    val monitorMuted: Boolean? = null,
    val monitorAutoMuteEnabled: Boolean? = null,
    val monitorAutoMuteActive: Boolean? = null,
    val automationConfig: RodeAutomationConfig = RodeAutomationConfig(),
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val isBusy: Boolean = false
) {
    val isConnected: Boolean
        get() = status == RodeConnectionStatus.CONNECTED

    /** Tutti i fader (1–9) assegnati a una sorgente sul mixer. */
    val assignedFaders: List<RodeVirtualFader>
        get() = faders.filter { it.assignedSourceCode != null }

    val hasMonitorControl: Boolean
        get() = monitorMuted != null || monitorAutoMuteEnabled != null || monitorAutoMuteActive != null

    val isMonitorOutputSilenced: Boolean
        get() = monitorMuted == true || monitorAutoMuteActive == true
}

/**
 * Configurazione Muto serializzabile per singolo brano (salvata in `mixerConfigJson`).
 * Specifica i canali aggiuntivi (es. microfoni) a cui togliere il Muto all'avvio del brano;
 * gli stessi canali verranno rimessi automaticamente in Muto al termine del brano.
 */
data class RodeMixerPreset(
    val unmuteFaderIndices: Set<Int> = emptySet()
) {
    val isEmpty: Boolean
        get() = unmuteFaderIndices.none { it in 0 until RodeInputVolumeChannel.TOTAL_FADER_COUNT }

    fun badgeLabel(): String? {
        val valid = unmuteFaderIndices
            .filter { it in 0 until RodeInputVolumeChannel.TOTAL_FADER_COUNT }
            .sorted()
        if (valid.isEmpty()) return null
        return "Apri: ${valid.joinToString(", ") { "F${it + 1}" }}"
    }
}

object RodeMixerPresetCodec {
    private const val KEY_UNMUTE_FADERS = "unmuteFaderIndices"
    private const val KEY_LEGACY_CUSTOM_UNMUTED = "customUnmutedFaders"

    fun encode(preset: RodeMixerPreset): String {
        val root = JSONObject()
        val arr = RodeAutomationConfigCodec.encodeFaderIndexArray(preset.unmuteFaderIndices)
        if (arr.length() > 0) {
            root.put(KEY_UNMUTE_FADERS, arr)
        }
        return root.toString()
    }

    fun encodeOrNull(preset: RodeMixerPreset): String? =
        if (preset.isEmpty) null else encode(preset)

    fun decode(json: String?): RodeMixerPreset? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val root = JSONObject(json)
            val indices = when {
                root.has(KEY_UNMUTE_FADERS) ->
                    RodeAutomationConfigCodec.parseFaderIndexArray(root.optJSONArray(KEY_UNMUTE_FADERS))
                root.has(KEY_LEGACY_CUSTOM_UNMUTED) ->
                    RodeAutomationConfigCodec.parseFaderIndexArray(root.optJSONArray(KEY_LEGACY_CUSTOM_UNMUTED))
                else -> emptySet()
            }
            RodeMixerPreset(unmuteFaderIndices = indices)
        }.getOrNull()
    }
}
