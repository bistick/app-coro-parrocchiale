package it.parrocchia.coro.data.mixer.rode

import it.parrocchia.coro.domain.mixer.RodeInputKind
import it.parrocchia.coro.domain.mixer.RodeInputMode
import it.parrocchia.coro.domain.mixer.RodeInputVolumeChannel
import it.parrocchia.coro.domain.mixer.RodeVirtualFader
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class RodePro2Snapshot(
    val deviceName: String,
    val firmwareVersion: String?,
    val usbExpandedMode: Boolean,
    val inputs: List<RodeInputVolumeChannel>,
    val virtualFaders: List<RodeVirtualFader>,
    val faders: List<RodeVirtualFader> = virtualFaders,
    val monitorMuted: Boolean? = null,
    val monitorAutoMuteEnabled: Boolean? = null,
    val monitorAutoMuteActive: Boolean? = null
)

/**
 * Protocollo USB HID (Report 0x03 OUT / Report 0x04 IN) per RØDECaster Pro II.
 *
 * Regole di sicurezza hardware applicate in modo rigoroso:
 * - Nessun comando su Report ID 0x01 viene mai generato.
 * - Ogni scrittura su Report ID 0x03 passa dallo Stray Guard di [RodeTreeLayout.resolvePath].
 * - I fader fisici (indici 0..5) non sono motorizzati e non vengono mai scritti.
 */
object RodeHidProtocol {
    const val RODE_VENDOR_ID = 0x19F7
    const val PRO2_CONTROL_INTERFACE_ID = 9

    const val REPORT_MODE_ID: Byte = 0x01
    const val REPORT_ACK_ID: Byte = 0x02
    const val MODE_NORMAL_BYTE: Byte = 0x4E // 'N' = Normal/App mode (unico byte consentito su Report 0x01)
    const val MODE_PACKET_SIZE = 64

    const val REPORT_OUT_ID: Byte = 0x03
    const val REPORT_IN_ID: Byte = 0x04
    const val PACKET_SIZE = 256

    const val PROP_MIC_GAIN = "inputMicrophoneGain"
    const val PROP_INSTRUMENT_GAIN = "inputInstrumentGain"
    const val PROP_DIGITAL_GAIN = "inputDigitalGain"
    const val PROP_INPUT_TYPE = "inputType"
    const val PROP_CHANNEL_INPUT_SOURCE = "channelInputSource"
    const val PROP_CHANNEL_OUTPUT_MUTE = "channelOutputMute"
    const val PROP_FADER_LEVEL = "faderLevel"
    const val PROP_MIX_LEVEL_WITH_ANCHOR = "mixLevelWithAnchor"
    const val PROP_MIX_LINK = "mixLink"
    const val PROP_MIX_LINK_REQUEST = "mixLinkRequest"
    const val PROP_MIX_UNLINK_REQUEST = "mixUnlinkRequest"
    const val PROP_MIX_DISABLED = "mixDisabled"
    const val PROP_OUTPUT_MIX_MINUS = "outputMixMinus"
    const val PROP_OUTPUT_MULTI_MODE = "outputMultiMode"
    const val PROP_OUTPUT_MON_MUTE = "outputMonMute"
    const val PROP_OUTPUT_MON_AUTO_MUTE = "outputMonAutoMute"
    const val PROP_OUTPUT_MON_AUTO_MUTE_ACTIVE = "outputMonAutoMuteActive"

    const val OUTPUT_MODE_MAIN_MIX = 0
    const val OUTPUT_MODE_MIX_MINUS = 1
    const val OUTPUT_MODE_CUSTOM = 2

    const val OUTPUT_HEADPHONE_1 = 0
    const val OUTPUT_MONITOR = 4
    const val OUTPUT_REC = 5
    const val OUTPUT_USB1 = 7

    const val MIC_GAIN_MIN_DB = 0
    const val MIC_GAIN_MAX_DB = 76
    const val DIGITAL_GAIN_MIN_DB = -60
    const val DIGITAL_GAIN_MAX_DB = 12
    const val FADER_LEVEL_MIN = 0
    const val FADER_LEVEL_MAX = 127

    const val PRO2_PHYSICAL_FADER_COUNT = 6
    const val PRO2_TOTAL_FADER_COUNT = 9
    const val CROSS_POINT_OUTPUT_COUNT = 13
    const val UNASSIGNED_INPUT_SOURCE = 0xFF

    private val DUMP_COMMAND_PAYLOAD = byteArrayOf(
        0xAD.toByte(),
        0x10,
        0xA7.toByte(),
        0xB0.toByte()
    )

    private data class Pro2ChannelSpec(
        val sourceCode: Int,
        val key: String,
        val standardLabel: String,
        val expandedLabel: String = standardLabel,
        val kind: RodeInputKind,
        val expandedOnly: Boolean = false
    )

    private val PRO2_CHANNELS = listOf(
        Pro2ChannelSpec(0, "Mic1", "Mic 1", kind = RodeInputKind.MIC),
        Pro2ChannelSpec(1, "Mic2", "Mic 2", kind = RodeInputKind.MIC),
        Pro2ChannelSpec(2, "Mic3", "Mic 3", kind = RodeInputKind.MIC),
        Pro2ChannelSpec(3, "Mic4", "Mic 4", kind = RodeInputKind.MIC),
        Pro2ChannelSpec(7, "USB1", "USB 1 (Main)", expandedLabel = "USB 1 (System)", kind = RodeInputKind.LINE),
        Pro2ChannelSpec(8, "Chat", "USB 1 Chat", kind = RodeInputKind.LINE),
        Pro2ChannelSpec(9, "USB2", "USB 2", kind = RodeInputKind.LINE),
        Pro2ChannelSpec(10, "Bluetooth", "Bluetooth", kind = RodeInputKind.LINE),
        Pro2ChannelSpec(11, "SMARTpads", "SMART Pads", kind = RodeInputKind.BUS),
        Pro2ChannelSpec(16, "CallMe1", "CallMe 1", kind = RodeInputKind.LINE),
        Pro2ChannelSpec(12, "Game", "Game (USB)", kind = RodeInputKind.LINE, expandedOnly = true),
        Pro2ChannelSpec(13, "Music", "Music (USB)", kind = RodeInputKind.LINE, expandedOnly = true),
        Pro2ChannelSpec(14, "VirtualA", "Virtual A", kind = RodeInputKind.LINE, expandedOnly = true),
        Pro2ChannelSpec(15, "VirtualB", "Virtual B", kind = RodeInputKind.LINE, expandedOnly = true)
    )

    private val STEREO_PAIR_LABELS = mapOf(
        4 to "Mic 1 + 2",
        5 to "Mic 2 + 3",
        6 to "Mic 3 + 4"
    )

    fun inputSourceKey(sourceCode: Int): RodeNodeKey =
        RodeNodeKey.rootChild("INPUTSOURCE", sourceCode)

    fun channelStripKey(stripIndex: Int): RodeNodeKey =
        RodeNodeKey.rootChild("CHANNEL", stripIndex)

    fun faderKey(faderIndex: Int): RodeNodeKey =
        RodeNodeKey.nested("PHYSICALINTERFACE", 0, "FADER", faderIndex)

    fun outputKey(): RodeNodeKey =
        RodeNodeKey.rootChild("OUTPUT", 0)

    fun outputMixMinusKey(outputIndex: Int): RodeNodeKey =
        RodeNodeKey.rootChild("MIXMINUSES", outputIndex)

    fun crossPointKey(outputIndex: Int, sourceIndex: Int): RodeNodeKey =
        RodeNodeKey.rootChild("MIX", CROSS_POINT_OUTPUT_COUNT * sourceIndex + outputIndex)

    /**
     * Espande il codice sorgente assegnato a una strip negli indici sorgente reali della matrice MIX
     * (gestendo le coppie stereo microfoniche Mic 1+2 = 4, Mic 2+3 = 5, Mic 3+4 = 6).
     */
    fun matrixSourceIndices(stripSourceCode: Int): List<Int> = when (stripSourceCode) {
        4 -> listOf(0, 1)
        5 -> listOf(1, 2)
        6 -> listOf(2, 3)
        else -> listOf(stripSourceCode)
    }

    /**
     * Formatta un livello 0..127 nel formato stringa richiesto da `mixLevelWithAnchor`
     * per un cross-point scollegato (`"<0..1>|0"`).
     */
    fun formatCrossPointLevelValue(level: Int): String {
        val clamped = level.coerceIn(FADER_LEVEL_MIN, FADER_LEVEL_MAX)
        val normalized = clamped.toDouble() / FADER_LEVEL_MAX.toDouble()
        val rounded = kotlin.math.round(normalized * 100.0) / 100.0
        val numText = String.format(java.util.Locale.US, "%.2f", rounded)
            .trimEnd('0')
            .trimEnd('.')
        return "$numText|0"
    }

    /**
     * Pacchetto Report 0x01 sicuro con il solo comando `'N'` (0x4E = Normal/App Mode),
     * richiesto per l'handshake iniziale su dispositivi USB nuovi prima del dump Report 0x03.
     * Non invia mai 0x4D ('M') ne 0x55 ('U').
     */
    fun buildNormalModePacket(size: Int = MODE_PACKET_SIZE): ByteArray {
        val safeSize = size.coerceIn(2, MODE_PACKET_SIZE)
        val packet = ByteArray(safeSize)
        packet[0] = REPORT_MODE_ID
        packet[1] = MODE_NORMAL_BYTE
        return packet
    }

    /**
     * Verifica che un pacchetto in uscita sia sicuro per l'hardware:
     * - Report 0x03 da 256 byte, oppure
     * - Report 0x01 esclusivamente con comando `'N'` (0x4E) e resto zero.
     */
    fun isSafeOutboundPacket(packet: ByteArray): Boolean {
        if (packet.isEmpty()) return false
        return when (packet[0]) {
            REPORT_OUT_ID -> packet.size == PACKET_SIZE
            REPORT_MODE_ID -> packet.size in 2..MODE_PACKET_SIZE &&
                packet[1] == MODE_NORMAL_BYTE &&
                (2 until packet.size).all { packet[it] == 0.toByte() }
            else -> false
        }
    }

    /**
     * Pacchetto Report 0x03 da 256 byte per richiedere il dump completo dello stato del mixer.
     */
    fun buildDumpRequestPacket(): ByteArray = frameReportOut(DUMP_COMMAND_PAYLOAD)

    /**
     * Costruisce un pacchetto Report 0x03 da 256 byte per scrivere una singola proprieta,
     * risolvendo dinamicamente l'indirizzo tramite [layout] e verificando lo Stray Guard.
     */
    fun buildPropertyWritePacket(
        layout: RodeTreeLayout,
        key: RodeNodeKey,
        propertyName: String,
        value: RodeValue,
        compactZero: Boolean = false
    ): ByteArray {
        val path = layout.resolvePath(key, requiredProperty = propertyName)
        val addressBytes = RodeValueTreeCodec.encodeAddressPath(path, compactZero = compactZero)
        val nameBytes = propertyName.toByteArray(Charsets.US_ASCII)
        val valueBytes = RodeValueTreeCodec.encodeValue(value)

        val payload = ByteArrayOutputStream(addressBytes.size + nameBytes.size + 1 + valueBytes.size)
        payload.write(addressBytes)
        payload.write(nameBytes)
        payload.write(0x00)
        payload.write(valueBytes)
        return frameReportOut(payload.toByteArray())
    }

    fun buildInputGainWritePacket(
        layout: RodeTreeLayout,
        channel: RodeInputVolumeChannel,
        gainDb: Int
    ): Pair<Int, ByteArray> {
        val property = channel.gainProperty
            ?: throw IllegalArgumentException("Il canale ${channel.label} non supporta il controllo gain diretto")
        val clamped = gainDb.coerceIn(channel.minGainDb, channel.maxGainDb)
        val packet = buildPropertyWritePacket(
            layout = layout,
            key = inputSourceKey(channel.sourceCode),
            propertyName = property,
            value = RodeValue.Int32(clamped),
            compactZero = false
        )
        return clamped to packet
    }

    /**
     * Costruisce un pacchetto Report 0x03 per impostare il livello di un fader virtuale (indici 6..8).
     * I fader fisici (0..5) sono potenziometri analogici non motorizzati: il firmware del Pro II
     * ignora eventuali scritture su `FADER[0..5].faderLevel`.
     */
    fun buildFaderLevelWritePacket(
        layout: RodeTreeLayout,
        faderIndex: Int,
        level: Int
    ): Pair<Int, ByteArray> {
        require(faderIndex in PRO2_PHYSICAL_FADER_COUNT until PRO2_TOTAL_FADER_COUNT) {
            "Il fader ${faderIndex + 1} e un fader fisico non motorizzato (regolabili solo i fader virtuali 7–9)"
        }
        val clamped = level.coerceIn(FADER_LEVEL_MIN, FADER_LEVEL_MAX)
        val packet = buildPropertyWritePacket(
            layout = layout,
            key = faderKey(faderIndex),
            propertyName = PROP_FADER_LEVEL,
            value = RodeValue.Int32(clamped),
            compactZero = true
        )
        return clamped to packet
    }

    fun buildVirtualFaderWritePacket(
        layout: RodeTreeLayout,
        faderIndex: Int,
        level: Int
    ): Pair<Int, ByteArray> = buildFaderLevelWritePacket(layout, faderIndex, level)

    /**
     * Costruisce un pacchetto Report 0x03 per attivare o disattivare il Muto di qualsiasi strip fader
     * (sia fisici 0..5 sia virtuali 6..8) tramite `CHANNEL[stripIndex].channelOutputMute`.
     */
    fun buildChannelMuteWritePacket(
        layout: RodeTreeLayout,
        stripIndex: Int,
        muted: Boolean
    ): ByteArray {
        require(stripIndex in 0 until PRO2_TOTAL_FADER_COUNT) {
            "Indice fader non valido: $stripIndex (consentiti 0..${PRO2_TOTAL_FADER_COUNT - 1})"
        }
        return buildPropertyWritePacket(
            layout = layout,
            key = channelStripKey(stripIndex),
            propertyName = PROP_CHANNEL_OUTPUT_MUTE,
            value = RodeValue.Bool(muted),
            compactZero = false
        )
    }

    /**
     * Attiva (`muted = true`) o disattiva (`muted = false`) il Muto sull'uscita casse esterne
     * (Monitor Out) tramite `OUTPUT[0].outputMonMute`.
     */
    fun buildMonitorMuteWritePacket(
        layout: RodeTreeLayout,
        muted: Boolean
    ): ByteArray = buildPropertyWritePacket(
        layout = layout,
        key = outputKey(),
        propertyName = PROP_OUTPUT_MON_MUTE,
        value = RodeValue.Bool(muted),
        compactZero = false
    )

    /**
     * Attiva (`enabled = true`) o disattiva (`enabled = false`) l'Auto-Mute delle casse esterne
     * quando si apre un microfono tramite `OUTPUT[0].outputMonAutoMute`.
     */
    fun buildMonitorAutoMuteWritePacket(
        layout: RodeTreeLayout,
        enabled: Boolean
    ): ByteArray = buildPropertyWritePacket(
        layout = layout,
        key = outputKey(),
        propertyName = PROP_OUTPUT_MON_AUTO_MUTE,
        value = RodeValue.Bool(enabled),
        compactZero = false
    )

    /**
     * Imposta la modalita di routing di un bus di uscita (`MIXMINUSES[outputIndex].outputMixMinus`):
     * `0` = Main Mix, `1` = Mix-Minus, `2` = Custom.
     */
    fun buildOutputRoutingModeWritePacket(
        layout: RodeTreeLayout,
        outputIndex: Int,
        mode: Int
    ): ByteArray {
        require(outputIndex in 0 until CROSS_POINT_OUTPUT_COUNT) {
            "Indice uscita non valido: $outputIndex"
        }
        require(mode in OUTPUT_MODE_MAIN_MIX..OUTPUT_MODE_CUSTOM) {
            "Modalita routing non valida: $mode"
        }
        return buildPropertyWritePacket(
            layout = layout,
            key = outputMixMinusKey(outputIndex),
            propertyName = PROP_OUTPUT_MIX_MINUS,
            value = RodeValue.Int32(mode),
            compactZero = false
        )
    }

    /**
     * Collega (`linked = true`, `mixLinkRequest`) o scollega (`linked = false`, `mixUnlinkRequest`)
     * un cross-point `(outputIndex, sourceIndex)` dal fader fisico di canale.
     */
    fun buildCrossPointLinkWritePacket(
        layout: RodeTreeLayout,
        outputIndex: Int,
        sourceIndex: Int,
        linked: Boolean
    ): ByteArray {
        require(outputIndex in 0 until CROSS_POINT_OUTPUT_COUNT) {
            "Indice uscita non valido: $outputIndex"
        }
        val property = if (linked) PROP_MIX_LINK_REQUEST else PROP_MIX_UNLINK_REQUEST
        return buildPropertyWritePacket(
            layout = layout,
            key = crossPointKey(outputIndex, sourceIndex),
            propertyName = property,
            value = RodeValue.Composite(listOf(RodeValue.Bool(true), RodeValue.Bool(true))),
            compactZero = false
        )
    }

    /**
     * Imposta il livello software indipendente (0..127) di un cross-point scollegato
     * tramite `MIX[13*source + output].mixLevelWithAnchor = "<level>|0"`.
     */
    fun buildCrossPointLevelWritePacket(
        layout: RodeTreeLayout,
        outputIndex: Int,
        sourceIndex: Int,
        level: Int
    ): Pair<Int, ByteArray> {
        require(outputIndex in 0 until CROSS_POINT_OUTPUT_COUNT) {
            "Indice uscita non valido: $outputIndex"
        }
        val clamped = level.coerceIn(FADER_LEVEL_MIN, FADER_LEVEL_MAX)
        val packet = buildPropertyWritePacket(
            layout = layout,
            key = crossPointKey(outputIndex, sourceIndex),
            propertyName = PROP_MIX_LEVEL_WITH_ANCHOR,
            value = RodeValue.Str(formatCrossPointLevelValue(clamped)),
            compactZero = false
        )
        return clamped to packet
    }

    /**
     * Esclude o include una sorgente in un bus di uscita Custom tramite `mixDisabled`.
     */
    fun buildCrossPointExcludedWritePacket(
        layout: RodeTreeLayout,
        outputIndex: Int,
        sourceIndex: Int,
        excluded: Boolean
    ): ByteArray {
        require(outputIndex in 0 until CROSS_POINT_OUTPUT_COUNT) {
            "Indice uscita non valido: $outputIndex"
        }
        return buildPropertyWritePacket(
            layout = layout,
            key = crossPointKey(outputIndex, sourceIndex),
            propertyName = PROP_MIX_DISABLED,
            value = RodeValue.Bool(excluded),
            compactZero = false
        )
    }

    const val MIN_DECLARED_DUMP_BYTES = 4_096
    const val MAX_DECLARED_DUMP_BYTES = 4 * 1024 * 1024
    private const val MAX_ASYNC_PUSH_RECORD_BYTES = 200

    /**
     * Rimuove il byte iniziale Report ID `0x04` da ciascun frame ricevuto sul canale IN.
     */
    fun extractReportInPayload(frame: ByteArray, length: Int = frame.size): ByteArray {
        val safeLen = length.coerceIn(0, frame.size)
        if (safeLen == 0) return ByteArray(0)
        return if (frame[0] == REPORT_IN_ID) {
            frame.copyOfRange(1, safeLen)
        } else {
            frame.copyOfRange(0, safeLen)
        }
    }

    /**
     * Decodifica un frame Report 0x04 di notifica asincrona (`04 <len32> <record> <zeros>`),
     * restituendo null se il frame e una fetta del dump o non e un record completo.
     */
    fun parsePushFrame(frame: ByteArray, length: Int = frame.size): RodeAddressedRecord? {
        val safeLen = length.coerceIn(0, frame.size)
        if (safeLen < 6 || frame[0] != REPORT_IN_ID) return null
        val declaredLen = ByteBuffer.wrap(frame, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (declaredLen !in 6..MAX_ASYNC_PUSH_RECORD_BYTES || 5 + declaredLen > safeLen) return null
        for (i in (5 + declaredLen) until safeLen) {
            if (frame[i] != 0.toByte()) return null
        }
        val record = frame.copyOfRange(5, 5 + declaredLen)
        return RodeValueTreeCodec.parseAddressedRecord(record)
    }

    /**
     * Riconosce un frame Report 0x04 di notifica asincrona (`04 <len32> <record> <zeros>`)
     * in modo da saltarlo durante la raccolta dello stream del dump completo.
     */
    fun isAsyncPushFrame(frame: ByteArray, length: Int = frame.size): Boolean =
        parsePushFrame(frame, length) != null

    /**
     * Decodifica `mixLevelWithAnchor` (`"<level>|<anchor>"`) restituendo `(level0..127, isLinked)`.
     */
    fun parseAnyMixLevel(rawValue: String): Pair<Int, Boolean>? {
        val sep = rawValue.indexOf('|')
        if (sep <= 0 || sep >= rawValue.lastIndex) return null
        val level = rawValue.substring(0, sep).toDoubleOrNull() ?: return null
        val anchor = rawValue.substring(sep + 1).toDoubleOrNull() ?: return null
        if (level !in 0.0..1.0) return null
        val intLevel = kotlin.math.round(level * FADER_LEVEL_MAX)
            .toInt()
            .coerceIn(FADER_LEVEL_MIN, FADER_LEVEL_MAX)
        val isLinked = kotlin.math.abs(level - anchor) <= 0.002
        return intLevel to isLinked
    }

    /**
     * Estrae il livello fader (0..127) da `mixLevelWithAnchor` (`"<level>|<anchor>"`)
     * solo quando il cross-point e collegato al fader (`level == anchor`), ignorando i send scollegati.
     */
    fun parseLinkedMixLevel(rawValue: String): Int? =
        parseAnyMixLevel(rawValue)?.takeIf { it.second }?.first

    internal fun sourceMatchesStripCode(stripSourceCode: Int, pushedSourceCode: Int): Boolean {
        if (stripSourceCode == pushedSourceCode) return true
        return when (stripSourceCode) {
            4 -> pushedSourceCode == 0 || pushedSourceCode == 1
            5 -> pushedSourceCode == 1 || pushedSourceCode == 2
            6 -> pushedSourceCode == 2 || pushedSourceCode == 3
            else -> false
        }
    }

    /**
     * Legge la lunghezza dichiarata dai primi 4 byte dello stream assemblato (little-endian),
     * oppure null se il valore non rientra nell'intervallo plausibile di un dump Pro II.
     */
    fun readDeclaredDumpLength(streamBytes: ByteArray): Int? {
        if (streamBytes.size < 4) return null
        val declared = ByteBuffer.wrap(streamBytes, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        return declared.takeIf { it in MIN_DECLARED_DUMP_BYTES..MAX_DECLARED_DUMP_BYTES }
    }

    fun assembleDumpFrames(frames: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream(frames.size * (PACKET_SIZE - 1))
        frames.forEach { frame ->
            if (isAsyncPushFrame(frame)) return@forEach
            val chunk = extractReportInPayload(frame)
            if (chunk.isNotEmpty()) {
                out.write(chunk)
            }
        }
        return out.toByteArray()
    }

    /**
     * Estrae lo stato dei canali di ingresso e dei fader virtuali da un albero Pro II parsato.
     */
    fun parsePro2Snapshot(layout: RodeTreeLayout): RodePro2Snapshot {
        val systemNode = layout.findNode(RodeNodeKey.rootChild("SYSTEM", 0))
        val outputNode = layout.findNode(RodeNodeKey.rootChild("OUTPUT", 0))

        val deviceName = systemNode?.stringProp("systemName")
            ?.takeIf { it.isNotBlank() }
            ?: "RØDECaster Pro II"

        val firmwareVersion = systemNode?.stringProp("systemFirmwareVersion")
            ?.takeIf { it.isNotBlank() }
            ?: systemNode?.stringProp("firmwareVersion")?.takeIf { it.isNotBlank() }

        val outputMultiMode = outputNode?.intProp(PROP_OUTPUT_MULTI_MODE) ?: 0
        val usbExpanded = outputMultiMode >= 3

        val faderCount = minOf(
            PRO2_TOTAL_FADER_COUNT,
            maxOf(
                layout.countRootBlock("CHANNEL"),
                layout.countNestedBlock("PHYSICALINTERFACE", 0, "FADER")
            )
        )

        val stripSources = IntArray(faderCount) { -1 }
        val stripMutes = arrayOfNulls<Boolean>(faderCount)
        val faderLevels = arrayOfNulls<Int>(faderCount)

        for (stripIdx in 0 until faderCount) {
            val chNode = layout.findNode(channelStripKey(stripIdx))
            val rawSource = chNode?.intProp(PROP_CHANNEL_INPUT_SOURCE) ?: -1
            stripSources[stripIdx] = if (rawSource < 0 || rawSource == UNASSIGNED_INPUT_SOURCE) {
                -1
            } else {
                rawSource
            }
            stripMutes[stripIdx] = chNode?.boolProp(PROP_CHANNEL_OUTPUT_MUTE)
            val faderNode = layout.findNode(faderKey(stripIdx))
            faderLevels[stripIdx] = faderNode?.intProp(PROP_FADER_LEVEL)
        }

        val sourceToStrip = mutableMapOf<Int, Int>()
        for (stripIdx in stripSources.indices) {
            val code = stripSources[stripIdx]
            if (code >= 0 && code !in sourceToStrip) {
                sourceToStrip[code] = stripIdx
            }
        }

        val labelByCode = mutableMapOf<Int, String>()
        PRO2_CHANNELS.forEach { spec ->
            labelByCode[spec.sourceCode] = if (usbExpanded) spec.expandedLabel else spec.standardLabel
        }
        labelByCode.putAll(STEREO_PAIR_LABELS)

        val inputs = mutableListOf<RodeInputVolumeChannel>()
        PRO2_CHANNELS.forEach { spec ->
            if (spec.expandedOnly && !usbExpanded) return@forEach
            val inputNode = layout.findNode(inputSourceKey(spec.sourceCode)) ?: return@forEach
            val label = if (usbExpanded) spec.expandedLabel else spec.standardLabel

            val directStrip = sourceToStrip[spec.sourceCode]
            val stereoPairStrip = if (directStrip == null && spec.kind == RodeInputKind.MIC) {
                findStereoPairStrip(spec.sourceCode, stripSources)
            } else {
                null
            }
            val assignedStrip = directStrip ?: stereoPairStrip

            val (mode, gainProp, gainDb, minDb, maxDb) = resolveGainSpec(spec, inputNode)
            inputs.add(
                RodeInputVolumeChannel(
                    sourceCode = spec.sourceCode,
                    key = spec.key,
                    label = label,
                    kind = spec.kind,
                    mode = mode,
                    gainProperty = gainProp,
                    gainDb = gainDb,
                    minGainDb = minDb,
                    maxGainDb = maxDb,
                    assignedFaderIndex = assignedStrip,
                    faderLevel = assignedStrip?.let { faderLevels.getOrNull(it) },
                    isMuted = assignedStrip?.let { stripMutes.getOrNull(it) }
                )
            )
        }

        val monitorIsCustom = layout.findNode(outputMixMinusKey(OUTPUT_MONITOR))
            ?.intProp(PROP_OUTPUT_MIX_MINUS) == OUTPUT_MODE_CUSTOM

        val allFaders = mutableListOf<RodeVirtualFader>()
        for (faderIdx in 0 until faderCount) {
            val rawPhysicalLevel = faderLevels.getOrNull(faderIdx)?.coerceIn(FADER_LEVEL_MIN, FADER_LEVEL_MAX)
                ?: continue
            val assignedCode = stripSources.getOrNull(faderIdx)?.takeIf { it >= 0 }
            val assignedLabel = assignedCode?.let { code ->
                labelByCode[code] ?: "Sorgente $code"
            } ?: "Vuoto"

            var effectiveLevel = rawPhysicalLevel
            var isOverridden = false
            if (faderIdx < PRO2_PHYSICAL_FADER_COUNT && assignedCode != null && monitorIsCustom) {
                val primarySrc = matrixSourceIndices(assignedCode).first()
                val mixNode = layout.findNode(crossPointKey(OUTPUT_MONITOR, primarySrc))
                val mixLinked = mixNode?.boolProp(PROP_MIX_LINK)
                val parsedMix = mixNode?.stringProp(PROP_MIX_LEVEL_WITH_ANCHOR)?.let(::parseAnyMixLevel)
                if (mixLinked == false || (parsedMix != null && !parsedMix.second)) {
                    isOverridden = true
                    if (parsedMix != null) {
                        effectiveLevel = parsedMix.first
                    }
                }
            }

            allFaders.add(
                RodeVirtualFader(
                    faderIndex = faderIdx,
                    assignedSourceCode = assignedCode,
                    assignedLabel = assignedLabel,
                    level = effectiveLevel,
                    isMuted = stripMutes.getOrNull(faderIdx),
                    isSoftwareOverridden = isOverridden,
                    physicalLevel = if (faderIdx < PRO2_PHYSICAL_FADER_COUNT) rawPhysicalLevel else null
                )
            )
        }
        val virtualFaders = allFaders.filter { it.faderIndex >= PRO2_PHYSICAL_FADER_COUNT }

        return RodePro2Snapshot(
            deviceName = deviceName,
            firmwareVersion = firmwareVersion,
            usbExpandedMode = usbExpanded,
            inputs = inputs,
            virtualFaders = virtualFaders,
            faders = allFaders,
            monitorMuted = outputNode?.boolProp(PROP_OUTPUT_MON_MUTE),
            monitorAutoMuteEnabled = outputNode?.boolProp(PROP_OUTPUT_MON_AUTO_MUTE),
            monitorAutoMuteActive = outputNode?.boolProp(PROP_OUTPUT_MON_AUTO_MUTE_ACTIVE)
        )
    }

    private data class ResolvedGain(
        val mode: RodeInputMode,
        val property: String?,
        val gainDb: Int?,
        val minDb: Int,
        val maxDb: Int
    )

    private fun resolveGainSpec(spec: Pro2ChannelSpec, inputNode: RodeNode): ResolvedGain {
        return when (spec.kind) {
            RodeInputKind.MIC -> {
                val inputType = inputNode.intProp(PROP_INPUT_TYPE) ?: 0
                when (inputType) {
                    1 -> {
                        val instGain = inputNode.intProp(PROP_INSTRUMENT_GAIN)
                        if (instGain != null) {
                            ResolvedGain(
                                mode = RodeInputMode.INSTRUMENT,
                                property = PROP_INSTRUMENT_GAIN,
                                gainDb = instGain.coerceIn(MIC_GAIN_MIN_DB, MIC_GAIN_MAX_DB),
                                minDb = MIC_GAIN_MIN_DB,
                                maxDb = MIC_GAIN_MAX_DB
                            )
                        } else {
                            ResolvedGain(
                                mode = RodeInputMode.MIC_PREAMP,
                                property = PROP_MIC_GAIN.takeIf { inputNode.intProp(it) != null },
                                gainDb = inputNode.intProp(PROP_MIC_GAIN)?.coerceIn(MIC_GAIN_MIN_DB, MIC_GAIN_MAX_DB),
                                minDb = MIC_GAIN_MIN_DB,
                                maxDb = MIC_GAIN_MAX_DB
                            )
                        }
                    }

                    2 -> ResolvedGain(
                        mode = RodeInputMode.LINE_IN,
                        property = PROP_MIC_GAIN.takeIf { inputNode.intProp(it) != null },
                        gainDb = inputNode.intProp(PROP_MIC_GAIN)?.coerceIn(MIC_GAIN_MIN_DB, MIC_GAIN_MAX_DB),
                        minDb = MIC_GAIN_MIN_DB,
                        maxDb = MIC_GAIN_MAX_DB
                    )

                    else -> ResolvedGain(
                        mode = RodeInputMode.MIC_PREAMP,
                        property = PROP_MIC_GAIN.takeIf { inputNode.intProp(it) != null },
                        gainDb = inputNode.intProp(PROP_MIC_GAIN)?.coerceIn(MIC_GAIN_MIN_DB, MIC_GAIN_MAX_DB),
                        minDb = MIC_GAIN_MIN_DB,
                        maxDb = MIC_GAIN_MAX_DB
                    )
                }
            }

            RodeInputKind.LINE -> {
                val digitalGain = inputNode.intProp(PROP_DIGITAL_GAIN)
                ResolvedGain(
                    mode = RodeInputMode.DIGITAL,
                    property = PROP_DIGITAL_GAIN.takeIf { digitalGain != null },
                    gainDb = digitalGain?.coerceIn(DIGITAL_GAIN_MIN_DB, DIGITAL_GAIN_MAX_DB),
                    minDb = DIGITAL_GAIN_MIN_DB,
                    maxDb = DIGITAL_GAIN_MAX_DB
                )
            }

            RodeInputKind.BUS -> ResolvedGain(
                mode = RodeInputMode.BUS,
                property = null,
                gainDb = null,
                minDb = 0,
                maxDb = 0
            )
        }
    }

    private fun findStereoPairStrip(micCode: Int, stripSources: IntArray): Int? {
        val pairCodes = when (micCode) {
            0 -> intArrayOf(4)
            1 -> intArrayOf(4, 5)
            2 -> intArrayOf(5, 6)
            3 -> intArrayOf(6)
            else -> return null
        }
        return stripSources.indexOfFirst { it in pairCodes }.takeIf { it >= 0 }
    }

    private fun frameReportOut(payload: ByteArray): ByteArray {
        require(payload.size <= PACKET_SIZE - 5) {
            "Payload Report 0x03 troppo grande: ${payload.size} byte (massimo ${PACKET_SIZE - 5})"
        }
        val packet = ByteArray(PACKET_SIZE)
        packet[0] = REPORT_OUT_ID
        ByteBuffer.wrap(packet, 1, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(payload.size)
        payload.copyInto(packet, destinationOffset = 5)
        return packet
    }
}
