package it.parrocchia.coro

import it.parrocchia.coro.data.mixer.rode.RodeAddressGuardException
import it.parrocchia.coro.data.mixer.rode.RodeHidProtocol
import it.parrocchia.coro.data.mixer.rode.RodeNode
import it.parrocchia.coro.data.mixer.rode.RodeNodeKey
import it.parrocchia.coro.data.mixer.rode.RodeProperty
import it.parrocchia.coro.data.mixer.rode.RodeTreeLayout
import it.parrocchia.coro.data.mixer.rode.RodeTreeParseException
import it.parrocchia.coro.data.mixer.rode.RodeValue
import it.parrocchia.coro.data.mixer.rode.RodeValueTreeCodec
import it.parrocchia.coro.domain.mixer.RodeConnectionStatus
import it.parrocchia.coro.domain.mixer.RodeInputKind
import it.parrocchia.coro.domain.mixer.RodeInputMode
import it.parrocchia.coro.domain.mixer.RodeMixerPreset
import it.parrocchia.coro.domain.mixer.RodeMixerPresetCodec
import it.parrocchia.coro.domain.mixer.RodeMixerState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
class RodeValueTreeAndProtocolTest {

    @Test
    fun valueTreeCodec_roundTripsAllValueTypesIncludingLongStrings() {
        val longPreset = "P".repeat(320) // > 255 byte -> verifica count a 2 byte
        val rawValue = RodeValue.Raw(0x7F, byteArrayOf(0x0A, 0x0B))
        assertEquals(rawValue, RodeValue.Raw(0x7F, byteArrayOf(0x0A, 0x0B)))
        assertEquals(rawValue.hashCode(), RodeValue.Raw(0x7F, byteArrayOf(0x0A, 0x0B)).hashCode())
        assertNotEquals(rawValue, RodeValue.Raw(0x7E, byteArrayOf(0x0A, 0x0B)))
        assertNotEquals(rawValue, "other")

        val tree = RodeNode(
            name = "ROOT",
            properties = listOf(
                RodeProperty("i32Neg", RodeValue.Int32(-42)),
                RodeProperty("i64Big", RodeValue.Int64(9_876_543_210L)),
                RodeProperty("f64Val", RodeValue.Float64(0.75)),
                RodeProperty("bTrue", RodeValue.Bool(true)),
                RodeProperty("bFalse", RodeValue.Bool(false)),
                RodeProperty("strLong", RodeValue.Str(longPreset)),
                RodeProperty("strUtf8", RodeValue.Str("RØDECaster Pro II")),
                RodeProperty(
                    "pairVal",
                    RodeValue.Composite(listOf(RodeValue.Int32(7), RodeValue.Bool(true)))
                ),
                RodeProperty("rawVal", rawValue),
                RodeProperty("emptyVal", RodeValue.Empty)
            ),
            children = listOf(
                RodeNode(
                    name = "CHILD",
                    properties = listOf(RodeProperty("k", RodeValue.Int32(1))),
                    children = emptyList()
                )
            )
        )

        val encoded = RodeValueTreeCodec.encodeNode(tree)
        val decoded = RodeValueTreeCodec.parse(encoded)

        assertEquals("ROOT", decoded.name)
        assertEquals(-42, decoded.intProp("i32Neg"))
        assertEquals(RodeValue.Int64(9_876_543_210L), decoded.property("i64Big"))
        assertEquals(0.75, decoded.doubleProp("f64Val")!!, 0.0001)
        assertEquals(true, decoded.boolProp("bTrue"))
        assertEquals(false, decoded.boolProp("bFalse"))
        assertEquals(longPreset, decoded.stringProp("strLong"))
        assertEquals("RØDECaster Pro II", decoded.stringProp("strUtf8"))
        assertEquals(
            RodeValue.Composite(listOf(RodeValue.Int32(7), RodeValue.Bool(true))),
            decoded.property("pairVal")
        )
        assertEquals(rawValue, decoded.property("rawVal"))
        assertEquals(RodeValue.Empty, decoded.property("emptyVal"))
        assertEquals(1, decoded.children.size)
        assertEquals("CHILD", decoded.children[0].name)
        assertEquals(0, decoded.children[0].indexInParent)
    }

    @Test
    fun valueTreeCodec_encodeCountAndAddressPath_matchWireFormat() {
        assertArrayEquals(byteArrayOf(0x00), RodeValueTreeCodec.encodeCount(0))
        assertArrayEquals(byteArrayOf(0x01, 0x1C), RodeValueTreeCodec.encodeCount(0x1C))
        assertArrayEquals(byteArrayOf(0x02, 0x6E, 0x02), RodeValueTreeCodec.encodeCount(0x026E))
        assertArrayEquals(
            byteArrayOf(0x04, 0x01, 0x00, 0x01, 0x00),
            RodeValueTreeCodec.encodeCount(0x00010001)
        )
        assertThrows(IllegalArgumentException::class.java) {
            RodeValueTreeCodec.encodeCount(-1)
        }

        // Standard path: INPUTSOURCE[0] at root child 0x026E
        assertArrayEquals(
            byteArrayOf(0x01, 0x01, 0x01, 0x02, 0x6E, 0x02),
            RodeValueTreeCodec.encodeAddressPath(listOf(0x026E), compactZero = false)
        )
        // Standard path with zero index: 01 00
        assertArrayEquals(
            byteArrayOf(0x01, 0x01, 0x02, 0x01, 0x00, 0x01, 0x0A),
            RodeValueTreeCodec.encodeAddressPath(listOf(0, 10), compactZero = false)
        )
        // Compact zero path for faderLevel: 01 01 02 00 01 0A
        assertArrayEquals(
            byteArrayOf(0x01, 0x01, 0x02, 0x00, 0x01, 0x0A),
            RodeValueTreeCodec.encodeAddressPath(listOf(0, 10), compactZero = true)
        )
        // 4-byte index in path
        assertArrayEquals(
            byteArrayOf(0x01, 0x01, 0x01, 0x04, 0x00, 0x00, 0x01, 0x00),
            RodeValueTreeCodec.encodeAddressPath(listOf(0x10000), compactZero = false)
        )
        assertThrows(IllegalArgumentException::class.java) {
            RodeValueTreeCodec.encodeAddressPath(emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            RodeValueTreeCodec.encodeAddressPath(listOf(-1))
        }
    }

    @Test
    fun valueTreeCodec_rejectsMalformedOrTruncatedBlobs() {
        assertThrows(RodeTreeParseException::class.java) {
            RodeValueTreeCodec.parse(ByteArray(0))
        }
        // Missing null terminator on root name
        assertThrows(RodeTreeParseException::class.java) {
            RodeValueTreeCodec.parse("ROOT".toByteArray())
        }
        // Missing count after name
        assertThrows(RodeTreeParseException::class.java) {
            RodeValueTreeCodec.parse(byteArrayOf('R'.code.toByte(), 0x00))
        }
        // Invalid count byte length (5)
        assertThrows(RodeTreeParseException::class.java) {
            RodeValueTreeCodec.parse(byteArrayOf('R'.code.toByte(), 0x00, 0x05))
        }
        // Truncated Int32 payload (vlen=2, type=0x01, only 1 byte)
        assertThrows(RodeTreeParseException::class.java) {
            RodeValueTreeCodec.parse(
                byteArrayOf(
                    'R'.code.toByte(), 0x00,
                    0x01, 0x01, // 1 prop
                    'p'.code.toByte(), 0x00,
                    0x01, 0x02, 0x01, 0x00 // vlen=2, type=Int32
                )
            )
        }
        // Truncated Float64 payload
        assertThrows(RodeTreeParseException::class.java) {
            RodeValueTreeCodec.parse(
                byteArrayOf(
                    'R'.code.toByte(), 0x00,
                    0x01, 0x01,
                    'p'.code.toByte(), 0x00,
                    0x01, 0x03, 0x04, 0x00, 0x00
                )
            )
        }
        // Truncated Int64 payload
        assertThrows(RodeTreeParseException::class.java) {
            RodeValueTreeCodec.parse(
                byteArrayOf(
                    'R'.code.toByte(), 0x00,
                    0x01, 0x01,
                    'p'.code.toByte(), 0x00,
                    0x01, 0x03, 0x06, 0x00, 0x00
                )
            )
        }
        // Truncated value bytes past EOF
        assertThrows(RodeTreeParseException::class.java) {
            RodeValueTreeCodec.parse(
                byteArrayOf(
                    'R'.code.toByte(), 0x00,
                    0x01, 0x01,
                    'p'.code.toByte(), 0x00,
                    0x01, 0x10, 0x01, 0x00
                )
            )
        }
    }

    @Test
    fun treeLayout_resolvesDynamicPathsAndEnforcesStrayGuard() {
        val root = buildRealisticPro2Tree(usbExpanded = false)
        val parsed = RodeValueTreeCodec.parse(RodeValueTreeCodec.encodeNode(root))
        val layout = RodeTreeLayout(parsed)

        assertEquals(9, layout.countRootBlock("CHANNEL"))
        assertEquals(9, layout.countNestedBlock("PHYSICALINTERFACE", 0, "FADER"))
        assertEquals(0, layout.countRootBlock("NON_EXISTENT"))
        assertEquals(0, layout.countNestedBlock("PHYSICALINTERFACE", 0, "NON_EXISTENT"))

        val mic1Key = RodeHidProtocol.inputSourceKey(0)
        assertTrue(layout.hasProperty(mic1Key, RodeHidProtocol.PROP_MIC_GAIN))
        assertFalse(layout.hasProperty(mic1Key, "nonExistentProp"))
        assertFalse(layout.hasProperty(RodeNodeKey.rootChild("MISSING", 0), "x"))

        val mic1Path = layout.resolvePath(mic1Key, RodeHidProtocol.PROP_MIC_GAIN)
        assertEquals(mic1Key, layout.keyAtPath(mic1Path))

        val fader7Key = RodeHidProtocol.faderKey(6)
        assertEquals("PHYSICALINTERFACE[0].FADER[6]", fader7Key.format())
        val fader7Path = layout.resolvePath(fader7Key, RodeHidProtocol.PROP_FADER_LEVEL)
        assertEquals(listOf(0, 10), fader7Path) // 4 POT + 6 physical FADER before FADER[6]

        // Stray guard: unknown property on existing node MUST throw
        assertThrows(RodeAddressGuardException::class.java) {
            layout.resolvePath(mic1Key, "inventedPropertyName")
        }
        // Stray guard: missing node MUST throw
        assertThrows(RodeAddressGuardException::class.java) {
            layout.resolvePath(RodeHidProtocol.inputSourceKey(99), RodeHidProtocol.PROP_MIC_GAIN)
        }
    }

    @Test
    fun protocol_dumpRequestAndFrameAssemblyAndSnapshotParsing() {
        val dumpReq = RodeHidProtocol.buildDumpRequestPacket()
        assertEquals(RodeHidProtocol.PACKET_SIZE, dumpReq.size)
        assertEquals(RodeHidProtocol.REPORT_OUT_ID, dumpReq[0])
        assertEquals(4, ByteBuffer.wrap(dumpReq, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int)
        assertEquals(0xAD.toByte(), dumpReq[5])
        assertEquals(0x10.toByte(), dumpReq[6])
        assertEquals(0xA7.toByte(), dumpReq[7])
        assertEquals(0xB0.toByte(), dumpReq[8])

        // Multi-frame Report 0x04 stripping
        val rawBlob = RodeValueTreeCodec.encodeNode(buildRealisticPro2Tree(usbExpanded = false))
        val frames = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < rawBlob.size) {
            val end = minOf(offset + 255, rawBlob.size)
            val frame = ByteArray(1 + (end - offset))
            frame[0] = RodeHidProtocol.REPORT_IN_ID
            rawBlob.copyInto(frame, destinationOffset = 1, startIndex = offset, endIndex = end)
            frames.add(frame)
            offset = end
        }
        val reassembled = RodeHidProtocol.assembleDumpFrames(frames)
        assertArrayEquals(rawBlob, reassembled)

        // Edge cases for extractReportInPayload
        assertEquals(0, RodeHidProtocol.extractReportInPayload(ByteArray(0)).size)
        assertArrayEquals(
            byteArrayOf(0x11, 0x22),
            RodeHidProtocol.extractReportInPayload(byteArrayOf(0x11, 0x22))
        )

        val layout = RodeTreeLayout(RodeValueTreeCodec.parse(reassembled))
        val snapshot = RodeHidProtocol.parsePro2Snapshot(layout)

        assertEquals("RØDECaster Pro II", snapshot.deviceName)
        assertEquals("1.7.7", snapshot.firmwareVersion)
        assertFalse(snapshot.usbExpandedMode)
        assertEquals(10, snapshot.inputs.size) // 4 Mic + USB1 + Chat + USB2 + BT + SMART + CallMe1
        assertEquals(3, snapshot.virtualFaders.size) // Faders 7, 8, 9

        val mic1 = snapshot.inputs.first { it.sourceCode == 0 }
        assertEquals("Mic 1", mic1.label)
        assertEquals(RodeInputKind.MIC, mic1.kind)
        assertEquals(RodeInputMode.MIC_PREAMP, mic1.mode)
        assertEquals(42, mic1.gainDb)
        assertEquals(0, mic1.assignedFaderIndex)
        assertEquals(1, mic1.assignedFaderNumber)
        assertTrue(mic1.isOnPhysicalFader)
        assertFalse(mic1.isOnVirtualFader)
        assertEquals(false, mic1.isMuted)

        val mic2 = snapshot.inputs.first { it.sourceCode == 1 }
        assertEquals(RodeInputMode.INSTRUMENT, mic2.mode)
        assertEquals(RodeHidProtocol.PROP_INSTRUMENT_GAIN, mic2.gainProperty)
        assertEquals(20, mic2.gainDb)

        val mic3 = snapshot.inputs.first { it.sourceCode == 2 }
        assertEquals(RodeInputMode.LINE_IN, mic3.mode)

        val usb1 = snapshot.inputs.first { it.sourceCode == 7 }
        assertEquals("USB 1 (Main)", usb1.label)
        assertEquals(-12, usb1.gainDb)
        assertEquals(6, usb1.assignedFaderIndex)
        assertTrue(usb1.isOnVirtualFader)
        assertEquals(79, usb1.faderLevelPercent)

        val smartPads = snapshot.inputs.first { it.sourceCode == 11 }
        assertFalse(smartPads.isGainControllable)
        assertEquals(RodeInputMode.BUS, smartPads.mode)

        // Expanded mode includes Game, Music, Virtual A, Virtual B & relabels USB 1
        val expandedTree = buildRealisticPro2Tree(usbExpanded = true, stereoLink12 = true)
        val expandedLayout = RodeTreeLayout(
            RodeValueTreeCodec.parse(RodeValueTreeCodec.encodeNode(expandedTree))
        )
        val expandedSnap = RodeHidProtocol.parsePro2Snapshot(expandedLayout)
        assertTrue(expandedSnap.usbExpandedMode)
        assertEquals(14, expandedSnap.inputs.size)
        assertEquals("USB 1 (System)", expandedSnap.inputs.first { it.sourceCode == 7 }.label)
        assertNotNull(expandedSnap.inputs.firstOrNull { it.sourceCode == 14 && it.label == "Virtual A" })
        // Mic 1 & Mic 2 stereo-linked on strip 0
        assertEquals(0, expandedSnap.inputs.first { it.sourceCode == 0 }.assignedFaderIndex)
        assertEquals(0, expandedSnap.inputs.first { it.sourceCode == 1 }.assignedFaderIndex)
        // Virtual fader 9 vacated -> "Vuoto"
        assertEquals("Vuoto", expandedSnap.virtualFaders.first { it.faderIndex == 8 }.assignedLabel)
    }

    @Test
    fun protocol_buildWritePackets_clampsValuesAndRejectsPhysicalFaders() {
        val layout = RodeTreeLayout(
            RodeValueTreeCodec.parse(RodeValueTreeCodec.encodeNode(buildRealisticPro2Tree()))
        )
        val snapshot = RodeHidProtocol.parsePro2Snapshot(layout)
        val mic1 = snapshot.inputs.first { it.sourceCode == 0 }
        val usb1 = snapshot.inputs.first { it.sourceCode == 7 }
        val smart = snapshot.inputs.first { it.sourceCode == 11 }

        val (clampedMic, micPacket) = RodeHidProtocol.buildInputGainWritePacket(layout, mic1, 99)
        assertEquals(76, clampedMic)
        assertEquals(RodeHidProtocol.PACKET_SIZE, micPacket.size)
        assertEquals(RodeHidProtocol.REPORT_OUT_ID, micPacket[0])

        val (clampedUsb, usbPacket) = RodeHidProtocol.buildInputGainWritePacket(layout, usb1, -99)
        assertEquals(-60, clampedUsb)
        assertEquals(RodeHidProtocol.REPORT_OUT_ID, usbPacket[0])

        assertThrows(IllegalArgumentException::class.java) {
            RodeHidProtocol.buildInputGainWritePacket(layout, smart, 0)
        }

        // Virtual fader 7 (index 6) uses compactZero address: 01 01 02 00 01 0A
        val (clampedFader, faderPacket) = RodeHidProtocol.buildVirtualFaderWritePacket(layout, 6, 200)
        assertEquals(127, clampedFader)
        assertEquals(RodeHidProtocol.REPORT_OUT_ID, faderPacket[0])
        assertArrayEquals(
            byteArrayOf(0x01, 0x01, 0x02, 0x00, 0x01, 0x0A),
            faderPacket.copyOfRange(5, 11)
        )

        // Physical faders 0..5 are non-motorized analog pots and MUST be rejected for faderLevel writes
        for (physIdx in 0..5) {
            assertThrows(IllegalArgumentException::class.java) {
                RodeHidProtocol.buildFaderLevelWritePacket(layout, physIdx, 90)
            }
        }

        // Channel mute works on ALL 9 faders (physical 0..5 and virtual 6..8)
        for (stripIdx in 0..8) {
            val mutePacket = RodeHidProtocol.buildChannelMuteWritePacket(layout, stripIdx, muted = stripIdx % 2 == 0)
            assertEquals(RodeHidProtocol.PACKET_SIZE, mutePacket.size)
            assertEquals(RodeHidProtocol.REPORT_OUT_ID, mutePacket[0])
            assertTrue(RodeHidProtocol.isSafeOutboundPacket(mutePacket))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RodeHidProtocol.buildChannelMuteWritePacket(layout, -1, true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RodeHidProtocol.buildChannelMuteWritePacket(layout, 9, true)
        }

        // Out-of-range fader indices MUST be rejected
        assertThrows(IllegalArgumentException::class.java) {
            RodeHidProtocol.buildFaderLevelWritePacket(layout, -1, 50)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RodeHidProtocol.buildFaderLevelWritePacket(layout, 9, 50)
        }

        // Linked & unlinked mixLevelWithAnchor parser, cross-point level formatting & matrixSourceIndices
        assertEquals(64, RodeHidProtocol.parseLinkedMixLevel("0.5|0.5"))
        assertEquals(127, RodeHidProtocol.parseLinkedMixLevel("1|1"))
        assertEquals(0, RodeHidProtocol.parseLinkedMixLevel("0|0"))
        assertNull(RodeHidProtocol.parseLinkedMixLevel("0.8|0.2")) // unlinked send
        assertEquals(102 to false, RodeHidProtocol.parseAnyMixLevel("0.8|0"))
        assertNull(RodeHidProtocol.parseLinkedMixLevel("1.5|1.5")) // out of range
        assertNull(RodeHidProtocol.parseLinkedMixLevel("invalid"))
        assertNull(RodeHidProtocol.parseLinkedMixLevel("|0.5"))
        assertNull(RodeHidProtocol.parseLinkedMixLevel("0.5|"))

        assertEquals("0|0", RodeHidProtocol.formatCrossPointLevelValue(0))
        assertEquals("0.5|0", RodeHidProtocol.formatCrossPointLevelValue(64))
        assertEquals("1|0", RodeHidProtocol.formatCrossPointLevelValue(127))
        assertEquals(listOf(0, 1), RodeHidProtocol.matrixSourceIndices(4))
        assertEquals(listOf(1, 2), RodeHidProtocol.matrixSourceIndices(5))
        assertEquals(listOf(2, 3), RodeHidProtocol.matrixSourceIndices(6))
        assertEquals(listOf(10), RodeHidProtocol.matrixSourceIndices(10))

        // MIXMINUSES & MIX cross-point packet builders (Stray Guard + exact composite link payload)
        val modePacket = RodeHidProtocol.buildOutputRoutingModeWritePacket(
            layout,
            RodeHidProtocol.OUTPUT_MONITOR,
            RodeHidProtocol.OUTPUT_MODE_CUSTOM
        )
        assertTrue(RodeHidProtocol.isSafeOutboundPacket(modePacket))
        assertThrows(IllegalArgumentException::class.java) {
            RodeHidProtocol.buildOutputRoutingModeWritePacket(layout, 99, RodeHidProtocol.OUTPUT_MODE_CUSTOM)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RodeHidProtocol.buildOutputRoutingModeWritePacket(layout, RodeHidProtocol.OUTPUT_MONITOR, 9)
        }

        val unlinkPacket = RodeHidProtocol.buildCrossPointLinkWritePacket(layout, 4, 0, linked = false)
        assertTrue(RodeHidProtocol.isSafeOutboundPacket(unlinkPacket))
        val linkPacket = RodeHidProtocol.buildCrossPointLinkWritePacket(layout, 4, 0, linked = true)
        assertTrue(RodeHidProtocol.isSafeOutboundPacket(linkPacket))
        val (clampedCp, cpLevelPacket) = RodeHidProtocol.buildCrossPointLevelWritePacket(layout, 4, 0, 200)
        assertEquals(127, clampedCp)
        assertTrue(RodeHidProtocol.isSafeOutboundPacket(cpLevelPacket))
        val excludedPacket = RodeHidProtocol.buildCrossPointExcludedWritePacket(layout, 4, 0, excluded = true)
        assertTrue(RodeHidProtocol.isSafeOutboundPacket(excludedPacket))

        assertThrows(IllegalArgumentException::class.java) {
            RodeHidProtocol.buildCrossPointLinkWritePacket(layout, -1, 0, true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RodeHidProtocol.buildCrossPointLevelWritePacket(layout, 13, 0, 50)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RodeHidProtocol.buildCrossPointExcludedWritePacket(layout, 13, 0, true)
        }

        // Snapshot detection of pre-existing Custom+unlinked override on Monitor bus
        val overriddenLayout = RodeTreeLayout(
            RodeValueTreeCodec.parse(
                RodeValueTreeCodec.encodeNode(buildRealisticPro2Tree(monitorOverriddenMic1 = true))
            )
        )
        val overriddenSnap = RodeHidProtocol.parsePro2Snapshot(overriddenLayout)
        val mic1Fader = overriddenSnap.faders.first { it.faderIndex == 0 }
        assertTrue(mic1Fader.isSoftwareOverridden)
        assertEquals(38, mic1Fader.level) // 0.3 * 127 ≈ 38
        assertEquals(90, mic1Fader.physicalLevel)

        assertTrue(RodeHidProtocol.sourceMatchesStripCode(0, 0))
        assertTrue(RodeHidProtocol.sourceMatchesStripCode(4, 0))
        assertTrue(RodeHidProtocol.sourceMatchesStripCode(4, 1))
        assertTrue(RodeHidProtocol.sourceMatchesStripCode(5, 2))
        assertTrue(RodeHidProtocol.sourceMatchesStripCode(6, 3))
        assertFalse(RodeHidProtocol.sourceMatchesStripCode(0, 1))

        // Safe outbound packet guard: Report 0x01 only allows 'N' (0x4E) with trailing zeros
        val safeMode = RodeHidProtocol.buildNormalModePacket()
        assertEquals(RodeHidProtocol.MODE_PACKET_SIZE, safeMode.size)
        assertTrue(RodeHidProtocol.isSafeOutboundPacket(safeMode))
        assertFalse(RodeHidProtocol.isSafeOutboundPacket(ByteArray(0)))
        val forbiddenM = safeMode.copyOf().also { it[1] = 'M'.code.toByte() }
        assertFalse(RodeHidProtocol.isSafeOutboundPacket(forbiddenM))
        val forbiddenU = safeMode.copyOf().also { it[1] = 'U'.code.toByte() }
        assertFalse(RodeHidProtocol.isSafeOutboundPacket(forbiddenU))
        val dirtyMode = safeMode.copyOf().also { it[2] = 0x01 }
        assertFalse(RodeHidProtocol.isSafeOutboundPacket(dirtyMode))
        assertFalse(RodeHidProtocol.isSafeOutboundPacket(byteArrayOf(0x09, 0x00)))
    }

    @Test
    fun mixerPresetCodecAndStateModels_roundTripAndEdgeCases() {
        val preset = RodeMixerPreset(unmuteFaderIndices = setOf(0, 2))
        assertFalse(preset.isEmpty)
        assertEquals("Apri: F1, F3", preset.badgeLabel())
        val json = RodeMixerPresetCodec.encode(preset)
        val decoded = RodeMixerPresetCodec.decode(json)
        assertEquals(preset, decoded)

        assertTrue(RodeMixerPreset().isEmpty)
        assertNull(RodeMixerPreset().badgeLabel())
        assertNull(RodeMixerPresetCodec.encodeOrNull(RodeMixerPreset()))
        assertEquals("{}", RodeMixerPresetCodec.encode(RodeMixerPreset()))
        assertNull(RodeMixerPresetCodec.decode(null))
        assertNull(RodeMixerPresetCodec.decode("   "))
        assertNull(RodeMixerPresetCodec.decode("{not-json"))
        // Legacy key fallback + out-of-range/non-numeric entries ignored safely
        val legacyDecoded = RodeMixerPresetCodec.decode(
            """{"customUnmutedFaders":[1,"bad",99,-1]}"""
        )
        assertEquals(setOf(1), legacyDecoded?.unmuteFaderIndices)
        val unknownKey = RodeMixerPresetCodec.decode("""{"other":123}""")
        assertEquals(emptySet<Int>(), unknownKey?.unmuteFaderIndices)

        val state = RodeMixerState(status = RodeConnectionStatus.CONNECTED)
        assertTrue(state.isConnected)
        assertTrue(state.assignedFaders.isEmpty())

        // 0xFF (255) strip source is normalized as unassigned
        val layoutWithUnassigned = RodeTreeLayout(
            RodeValueTreeCodec.parse(
                RodeValueTreeCodec.encodeNode(buildRealisticPro2Tree(unassigned255 = true))
            )
        )
        val snap255 = RodeHidProtocol.parsePro2Snapshot(layoutWithUnassigned)
        assertEquals(9, snap255.faders.size)
        assertTrue(snap255.faders[0].isPhysical)
        assertFalse(snap255.faders[6].isPhysical)
        assertNull(snap255.inputs.first { it.sourceCode == 2 }.assignedFaderIndex)
        assertNull(snap255.inputs.first { it.sourceCode == 3 }.assignedFaderIndex)
        assertNull(snap255.virtualFaders.first { it.faderIndex == 8 }.assignedSourceCode)
        assertEquals("Vuoto", snap255.virtualFaders.first { it.faderIndex == 8 }.assignedLabel)

        val state255 = RodeMixerState(
            status = RodeConnectionStatus.CONNECTED,
            inputs = snap255.inputs,
            virtualFaders = snap255.virtualFaders,
            faders = snap255.faders
        )
        assertEquals(5, state255.assignedFaders.size)
        assertTrue(state255.assignedFaders.any { it.assignedSourceCode == 11 }) // SMART Pads
    }

    @Test
    fun protocol_parsesRealStreamHeaderAndSkipsInterleavedAsyncPushFrames() {
        val tree = buildRealisticPro2Tree().copy(name = "Rodecaster")
        val treeBytes = RodeValueTreeCodec.encodeNode(tree)
        val declaredLen = 1 + treeBytes.size
        val streamWithHeader = ByteBuffer.allocate(5 + treeBytes.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(declaredLen)
            .put(0x02)
            .put(treeBytes)
            .array()

        // Verifica readDeclaredDumpLength
        assertNull(RodeHidProtocol.readDeclaredDumpLength(byteArrayOf(0x01, 0x02)))
        val tinyHeader = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(1024).array()
        assertNull(RodeHidProtocol.readDeclaredDumpLength(tinyHeader))
        assertEquals(declaredLen, RodeHidProtocol.readDeclaredDumpLength(streamWithHeader))
        val bigHeader = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(143_187).array()
        assertEquals(143_187, RodeHidProtocol.readDeclaredDumpLength(bigHeader))

        // Costruisce un frame push asincrono valido: 04 <len32> <01 01 01 01 1c "channelOutputMute"\0 01 01 02> <zeros>
        val pushRecord = RodeValueTreeCodec.encodeAddressPath(listOf(0x1C)) +
            "channelOutputMute".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00) +
            RodeValueTreeCodec.encodeValue(RodeValue.Bool(true))
        val pushFrame = ByteArray(RodeHidProtocol.PACKET_SIZE)
        pushFrame[0] = RodeHidProtocol.REPORT_IN_ID
        ByteBuffer.wrap(pushFrame, 1, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(pushRecord.size)
        pushRecord.copyInto(pushFrame, destinationOffset = 5)
        assertTrue(RodeHidProtocol.isAsyncPushFrame(pushFrame))

        // Frame non push (trailing byte diverso da zero o report diverso da 0x04)
        val nonPush = pushFrame.copyOf().also { it[255] = 0x01 }
        assertFalse(RodeHidProtocol.isAsyncPushFrame(nonPush))
        val wrongReport = pushFrame.copyOf().also { it[0] = 0x02 }
        assertFalse(RodeHidProtocol.isAsyncPushFrame(wrongReport))

        // Impacchetta streamWithHeader in frame Report 0x04 intercalati dal pushFrame
        val frames = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < streamWithHeader.size) {
            val end = minOf(offset + 255, streamWithHeader.size)
            val f = ByteArray(1 + (end - offset))
            f[0] = RodeHidProtocol.REPORT_IN_ID
            streamWithHeader.copyInto(f, destinationOffset = 1, startIndex = offset, endIndex = end)
            frames.add(f)
            if (offset == 0) frames.add(pushFrame)
            offset = end
        }

        val reassembled = RodeHidProtocol.assembleDumpFrames(frames)
        assertArrayEquals(streamWithHeader, reassembled)

        val parsed = RodeValueTreeCodec.parse(reassembled)
        assertEquals("Rodecaster", parsed.name)
        val snap = RodeHidProtocol.parsePro2Snapshot(RodeTreeLayout(parsed))
        assertEquals(10, snap.inputs.size)
        assertEquals(42, snap.inputs.first { it.sourceCode == 0 }.gainDb)
    }

    @Test
    fun automationConfigAndScenePresetCodec_roundTripAndEdgeCases() {
        // Default enabled = true
        val defaultCfg = it.parrocchia.coro.domain.mixer.RodeAutomationConfig()
        assertTrue(defaultCfg.enabled)
        assertTrue(defaultCfg.allConfiguredFaderIndices.isEmpty())

        // RodeAutomationConfig + Codec
        val cfg = it.parrocchia.coro.domain.mixer.RodeAutomationConfig(
            enabled = true,
            musicFaderIndices = setOf(6, 99, -1),
            alwaysMuteOnStopFaderIndices = setOf(0, 1),
            keepMonitorSpeakersUnmuted = false
        )
        assertEquals(setOf(0, 1, 6), cfg.allConfiguredFaderIndices)
        assertEquals(listOf(6), cfg.fadersToUnmuteOnStart(RodeMixerPreset()))
        assertEquals(
            listOf(2, 6),
            cfg.fadersToUnmuteOnStart(RodeMixerPreset(unmuteFaderIndices = setOf(2, 42)))
        )
        assertEquals(
            listOf(0, 1, 2, 3, 6),
            cfg.fadersToMuteOnStop(
                preset = RodeMixerPreset(unmuteFaderIndices = setOf(2)),
                previouslyOpenedFaders = setOf(3, 6)
            )
        )

        val encodedCfg = it.parrocchia.coro.domain.mixer.RodeAutomationConfigCodec.encode(cfg)
        val decodedCfg = it.parrocchia.coro.domain.mixer.RodeAutomationConfigCodec.decode(encodedCfg)
        assertTrue(decodedCfg.enabled)
        assertFalse(decodedCfg.keepMonitorSpeakersUnmuted)
        assertEquals(setOf(6), decodedCfg.musicFaderIndices)
        assertEquals(setOf(0, 1), decodedCfg.alwaysMuteOnStopFaderIndices)

        assertEquals(
            it.parrocchia.coro.domain.mixer.RodeAutomationConfig(),
            it.parrocchia.coro.domain.mixer.RodeAutomationConfigCodec.decode(null)
        )
        assertEquals(
            it.parrocchia.coro.domain.mixer.RodeAutomationConfig(),
            it.parrocchia.coro.domain.mixer.RodeAutomationConfigCodec.decode("{invalid")
        )
        val mixedJson = """{"enabled":false,"musicFaderIndices":[6,"bad",99],"alwaysMuteOnStopFaderIndices":[0]}"""
        val decodedMixed = it.parrocchia.coro.domain.mixer.RodeAutomationConfigCodec.decode(mixedJson)
        assertFalse(decodedMixed.enabled)
        assertTrue(decodedMixed.keepMonitorSpeakersUnmuted) // default true per JSON legacy
        assertEquals(setOf(6), decodedMixed.musicFaderIndices)
        assertEquals(setOf(0), decodedMixed.alwaysMuteOnStopFaderIndices)

        val micPreset = RodeMixerPreset(unmuteFaderIndices = setOf(0, 6))
        assertEquals("Apri: F1, F7", micPreset.badgeLabel())
        val micJson = RodeMixerPresetCodec.encodeOrNull(micPreset)
        assertNotNull(micJson)
        assertEquals(micPreset, RodeMixerPresetCodec.decode(micJson))
    }

    @Test
    fun monitorOutputPropertiesAndWritePackets_roundTripAndStrayGuard() {
        val tree = buildRealisticPro2Tree(
            monitorMuted = true,
            monitorAutoMute = true,
            monitorAutoMuteActive = true
        )
        val layout = RodeTreeLayout(tree)
        val snap = RodeHidProtocol.parsePro2Snapshot(layout)
        assertEquals(true, snap.monitorMuted)
        assertEquals(true, snap.monitorAutoMuteEnabled)
        assertEquals(true, snap.monitorAutoMuteActive)

        val unmuteMonPkt = RodeHidProtocol.buildMonitorMuteWritePacket(layout, muted = false)
        val parsedUnmuteMon = RodeHidProtocol.parsePushFrame(
            unmuteMonPkt.copyOf().also { it[0] = RodeHidProtocol.REPORT_IN_ID }
        )
        assertNotNull(parsedUnmuteMon)
        assertEquals(RodeHidProtocol.PROP_OUTPUT_MON_MUTE, parsedUnmuteMon!!.propertyName)
        assertEquals(RodeValue.Bool(false), parsedUnmuteMon.value)

        val disableAutoMutePkt = RodeHidProtocol.buildMonitorAutoMuteWritePacket(layout, enabled = false)
        val parsedDisableAuto = RodeHidProtocol.parsePushFrame(
            disableAutoMutePkt.copyOf().also { it[0] = RodeHidProtocol.REPORT_IN_ID }
        )
        assertNotNull(parsedDisableAuto)
        assertEquals(RodeHidProtocol.PROP_OUTPUT_MON_AUTO_MUTE, parsedDisableAuto!!.propertyName)
        assertEquals(RodeValue.Bool(false), parsedDisableAuto.value)

        val stateSilenced = RodeMixerState(
            monitorMuted = true,
            monitorAutoMuteEnabled = false,
            monitorAutoMuteActive = false
        )
        assertTrue(stateSilenced.hasMonitorControl)
        assertTrue(stateSilenced.isMonitorOutputSilenced)

        val stateAutoMuted = RodeMixerState(
            monitorMuted = false,
            monitorAutoMuteEnabled = true,
            monitorAutoMuteActive = true
        )
        assertTrue(stateAutoMuted.hasMonitorControl)
        assertTrue(stateAutoMuted.isMonitorOutputSilenced)

        val stateActive = RodeMixerState(
            monitorMuted = false,
            monitorAutoMuteEnabled = false,
            monitorAutoMuteActive = false
        )
        assertTrue(stateActive.hasMonitorControl)
        assertFalse(stateActive.isMonitorOutputSilenced)
        assertFalse(RodeMixerState().hasMonitorControl)

        // Stray guard: un albero senza proprieta' monitor rifiuta la scrittura
        val minimalLayout = RodeTreeLayout(RodeNode(name = "Rodecaster", children = listOf(RodeNode(name = "OUTPUT"))))
        assertThrows(IllegalStateException::class.java) {
            RodeHidProtocol.buildMonitorMuteWritePacket(minimalLayout, muted = false)
        }
        assertThrows(IllegalStateException::class.java) {
            RodeHidProtocol.buildMonitorAutoMuteWritePacket(minimalLayout, enabled = false)
        }
    }

    companion object {
        fun buildRealisticPro2Tree(
            usbExpanded: Boolean = false,
            stereoLink12: Boolean = false,
            unassigned255: Boolean = false,
            monitorOverriddenMic1: Boolean = false,
            monitorMuted: Boolean = false,
            monitorAutoMute: Boolean = false,
            monitorAutoMuteActive: Boolean = false
        ): RodeNode {
            val physicalChildren = mutableListOf<RodeNode>()
            repeat(4) { i ->
                physicalChildren.add(RodeNode(name = "POT", indexInParent = i))
            }
            val initialFaderLevels = intArrayOf(90, 85, 0, 0, 70, 60, 100, 80, 127)
            for (f in 0 until 9) {
                physicalChildren.add(
                    RodeNode(
                        name = "FADER",
                        properties = listOf(
                            RodeProperty(RodeHidProtocol.PROP_FADER_LEVEL, RodeValue.Int32(initialFaderLevels[f]))
                        ),
                        indexInParent = 4 + f
                    )
                )
            }

            val rootChildren = mutableListOf<RodeNode>()
            rootChildren.add(RodeNode(name = "PHYSICALINTERFACE", children = physicalChildren))
            rootChildren.add(
                RodeNode(
                    name = "SYSTEM",
                    properties = listOf(
                        RodeProperty("systemName", RodeValue.Str("RØDECaster Pro II")),
                        RodeProperty("systemFirmwareVersion", RodeValue.Str("1.7.7"))
                    )
                )
            )
            rootChildren.add(
                RodeNode(
                    name = "OUTPUT",
                    properties = listOf(
                        RodeProperty(
                            RodeHidProtocol.PROP_OUTPUT_MULTI_MODE,
                            RodeValue.Int32(if (usbExpanded) 3 else 0)
                        ),
                        RodeProperty(
                            RodeHidProtocol.PROP_OUTPUT_MON_MUTE,
                            RodeValue.Bool(monitorMuted)
                        ),
                        RodeProperty(
                            RodeHidProtocol.PROP_OUTPUT_MON_AUTO_MUTE,
                            RodeValue.Bool(monitorAutoMute)
                        ),
                        RodeProperty(
                            RodeHidProtocol.PROP_OUTPUT_MON_AUTO_MUTE_ACTIVE,
                            RodeValue.Bool(monitorAutoMuteActive)
                        )
                    )
                )
            )

            // 13 MIXMINUSES output routing nodes (0=HP1..4=Monitor..12)
            for (outIdx in 0 until RodeHidProtocol.CROSS_POINT_OUTPUT_COUNT) {
                val mode = if (monitorOverriddenMic1 && outIdx == RodeHidProtocol.OUTPUT_MONITOR) {
                    RodeHidProtocol.OUTPUT_MODE_CUSTOM
                } else {
                    RodeHidProtocol.OUTPUT_MODE_MAIN_MIX
                }
                rootChildren.add(
                    RodeNode(
                        name = "MIXMINUSES",
                        properties = listOf(
                            RodeProperty(RodeHidProtocol.PROP_OUTPUT_MIX_MINUS, RodeValue.Int32(mode))
                        )
                    )
                )
            }

            val stripSources = when {
                unassigned255 -> intArrayOf(0, 1, 255, 255, 10, 11, 7, 255, 255)
                stereoLink12 -> intArrayOf(4, -1, 2, 3, 10, 11, 7, 8, -1)
                else -> intArrayOf(0, 1, 2, 3, 10, 11, 7, 8, 9)
            }
            for (s in 0 until 9) {
                rootChildren.add(
                    RodeNode(
                        name = "CHANNEL",
                        properties = listOf(
                            RodeProperty(RodeHidProtocol.PROP_CHANNEL_INPUT_SOURCE, RodeValue.Int32(stripSources[s])),
                            RodeProperty(RodeHidProtocol.PROP_CHANNEL_OUTPUT_MUTE, RodeValue.Bool(s == 3))
                        )
                    )
                )
            }

            // 17 INPUTSOURCE slots (0..16)
            for (code in 0..16) {
                val props = when (code) {
                    0 -> listOf(
                        RodeProperty(RodeHidProtocol.PROP_INPUT_TYPE, RodeValue.Int32(0)),
                        RodeProperty(RodeHidProtocol.PROP_MIC_GAIN, RodeValue.Int32(42)),
                        RodeProperty(RodeHidProtocol.PROP_INSTRUMENT_GAIN, RodeValue.Int32(0)),
                        RodeProperty(RodeHidProtocol.PROP_DIGITAL_GAIN, RodeValue.Int32(0))
                    )
                    1 -> listOf(
                        RodeProperty(RodeHidProtocol.PROP_INPUT_TYPE, RodeValue.Int32(1)),
                        RodeProperty(RodeHidProtocol.PROP_MIC_GAIN, RodeValue.Int32(35)),
                        RodeProperty(RodeHidProtocol.PROP_INSTRUMENT_GAIN, RodeValue.Int32(20)),
                        RodeProperty(RodeHidProtocol.PROP_DIGITAL_GAIN, RodeValue.Int32(0))
                    )
                    2 -> listOf(
                        RodeProperty(RodeHidProtocol.PROP_INPUT_TYPE, RodeValue.Int32(2)),
                        RodeProperty(RodeHidProtocol.PROP_MIC_GAIN, RodeValue.Int32(15)),
                        RodeProperty(RodeHidProtocol.PROP_INSTRUMENT_GAIN, RodeValue.Int32(0)),
                        RodeProperty(RodeHidProtocol.PROP_DIGITAL_GAIN, RodeValue.Int32(0))
                    )
                    3 -> listOf(
                        RodeProperty(RodeHidProtocol.PROP_INPUT_TYPE, RodeValue.Int32(0)),
                        RodeProperty(RodeHidProtocol.PROP_MIC_GAIN, RodeValue.Int32(50)),
                        RodeProperty(RodeHidProtocol.PROP_INSTRUMENT_GAIN, RodeValue.Int32(0)),
                        RodeProperty(RodeHidProtocol.PROP_DIGITAL_GAIN, RodeValue.Int32(0))
                    )
                    else -> listOf(
                        RodeProperty(RodeHidProtocol.PROP_INPUT_TYPE, RodeValue.Int32(-1)),
                        RodeProperty(RodeHidProtocol.PROP_DIGITAL_GAIN, RodeValue.Int32(-12))
                    )
                }
                rootChildren.add(RodeNode(name = "INPUTSOURCE", properties = props))
            }

            // 13 MIX cross-points per source (prime 12 sorgenti = 156 celle, include Mic1..4, USB, BT, SMART)
            for (cell in 0 until 156) {
                val isOverriddenCell = monitorOverriddenMic1 && cell == RodeHidProtocol.OUTPUT_MONITOR
                rootChildren.add(
                    RodeNode(
                        name = "MIX",
                        properties = listOf(
                            RodeProperty(
                                RodeHidProtocol.PROP_MIX_LEVEL_WITH_ANCHOR,
                                RodeValue.Str(if (isOverriddenCell) "0.3|0" else "0.5|0.5")
                            ),
                            RodeProperty(RodeHidProtocol.PROP_MIX_LINK, RodeValue.Bool(!isOverriddenCell)),
                            RodeProperty(RodeHidProtocol.PROP_MIX_LINK_REQUEST, RodeValue.Bool(false)),
                            RodeProperty(RodeHidProtocol.PROP_MIX_UNLINK_REQUEST, RodeValue.Bool(false)),
                            RodeProperty(RodeHidProtocol.PROP_MIX_DISABLED, RodeValue.Bool(false))
                        )
                    )
                )
            }

            return RodeNode(name = "RODECASTER_MODEL", children = rootChildren)
        }
    }
}
