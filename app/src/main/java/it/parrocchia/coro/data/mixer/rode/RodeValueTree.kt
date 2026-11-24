package it.parrocchia.coro.data.mixer.rode

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Eccezione sollevata quando un nodo o una proprieta non esiste nell'albero live del mixer.
 * Previene scritture su indirizzi errati che potrebbero aggiungere proprieta spurie persistenti.
 */
class RodeAddressGuardException(message: String) : IllegalStateException(message)

/**
 * Eccezione sollevata quando il dump binario del modello del mixer e tronco o malformato.
 */
class RodeTreeParseException(message: String) : IllegalArgumentException(message)

sealed interface RodeValue {
    data class Int32(val value: Int) : RodeValue
    data class Int64(val value: Long) : RodeValue
    data class Float64(val value: Double) : RodeValue
    data class Bool(val value: Boolean) : RodeValue
    data class Str(val value: String) : RodeValue
    data class Composite(val parts: List<RodeValue>) : RodeValue
    data class Raw(val typeTag: Byte, val data: ByteArray) : RodeValue {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Raw) return false
            return typeTag == other.typeTag && data.contentEquals(other.data)
        }

        override fun hashCode(): Int = 31 * typeTag.hashCode() + data.contentHashCode()
    }
    data object Empty : RodeValue
}

data class RodeProperty(
    val name: String,
    val value: RodeValue
)

data class RodeAddressedRecord(
    val path: List<Int>,
    val propertyName: String,
    val value: RodeValue
)

data class RodeNode(
    val name: String,
    val properties: List<RodeProperty> = emptyList(),
    val children: List<RodeNode> = emptyList(),
    val indexInParent: Int = 0
) {
    fun property(name: String): RodeValue? = properties.firstOrNull { it.name == name }?.value

    fun intProp(name: String): Int? = (property(name) as? RodeValue.Int32)?.value

    fun boolProp(name: String): Boolean? = (property(name) as? RodeValue.Bool)?.value

    fun doubleProp(name: String): Double? = (property(name) as? RodeValue.Float64)?.value

    fun stringProp(name: String): String? = (property(name) as? RodeValue.Str)?.value
}

/**
 * Chiave simbolica di un nodo nell'albero del mixer (es. `INPUTSOURCE[0]` o
 * `PHYSICALINTERFACE[0].FADER[6]`). Non usa mai indici fissi di firmware.
 */
data class RodeNodeKey(val segments: List<Pair<String, Int>>) {
    init {
        require(segments.isNotEmpty()) { "RodeNodeKey requires at least one segment" }
    }

    fun format(): String = segments.joinToString(".") { (name, idx) -> "$name[$idx]" }

    companion object {
        fun rootChild(name: String, copyIndex: Int): RodeNodeKey =
            RodeNodeKey(listOf(name to copyIndex))

        fun nested(
            parentName: String,
            parentIndex: Int,
            childName: String,
            childIndex: Int
        ): RodeNodeKey = RodeNodeKey(listOf(parentName to parentIndex, childName to childIndex))
    }
}

/**
 * Indice dinamico costruito dal dump live del mixer collegato.
 * Mappa ogni chiave simbolica al percorso numerico corrente nell'albero e all'insieme
 * delle proprieta effettivamente presenti sul nodo (Stray Guard).
 */
class RodeTreeLayout(val root: RodeNode) {

    private data class IndexedEntry(
        val path: List<Int>,
        val node: RodeNode,
        val propertyNames: Set<String>
    )

    private val entriesByKey = LinkedHashMap<RodeNodeKey, IndexedEntry>()
    private val keysByPath = LinkedHashMap<List<Int>, RodeNodeKey>()
    private val blockCounts = LinkedHashMap<List<Pair<String, Int>>, MutableMap<String, Int>>()

    init {
        val rootSeen = mutableMapOf<String, Int>()
        root.children.forEach { child ->
            val copyIdx = rootSeen.getOrDefault(child.name, 0)
            rootSeen[child.name] = copyIdx + 1
            val childKey = RodeNodeKey.rootChild(child.name, copyIdx)
            val childPath = listOf(child.indexInParent)
            register(childKey, childPath, child, emptyList(), child.name)

            if (child.name in INDEXED_PARENT_BLOCKS) {
                val innerSeen = mutableMapOf<String, Int>()
                child.children.forEach { grandChild ->
                    val innerIdx = innerSeen.getOrDefault(grandChild.name, 0)
                    innerSeen[grandChild.name] = innerIdx + 1
                    val nestedKey = RodeNodeKey.nested(child.name, copyIdx, grandChild.name, innerIdx)
                    val nestedPath = listOf(child.indexInParent, grandChild.indexInParent)
                    register(nestedKey, nestedPath, grandChild, childKey.segments, grandChild.name)
                }
            }
        }
    }

    private fun register(
        key: RodeNodeKey,
        path: List<Int>,
        node: RodeNode,
        parentPrefix: List<Pair<String, Int>>,
        blockName: String
    ) {
        entriesByKey[key] = IndexedEntry(
            path = path,
            node = node,
            propertyNames = node.properties.map { it.name }.toSet()
        )
        keysByPath[path] = key
        val map = blockCounts.getOrPut(parentPrefix) { mutableMapOf() }
        map[blockName] = map.getOrDefault(blockName, 0) + 1
    }

    fun findNode(key: RodeNodeKey): RodeNode? = entriesByKey[key]?.node

    fun keyAtPath(path: List<Int>): RodeNodeKey? = keysByPath[path]

    fun countRootBlock(blockName: String): Int =
        blockCounts[emptyList()]?.get(blockName) ?: 0

    fun countNestedBlock(parentName: String, parentIndex: Int, childName: String): Int =
        blockCounts[listOf(parentName to parentIndex)]?.get(childName) ?: 0

    fun hasProperty(key: RodeNodeKey, propertyName: String): Boolean {
        val entry = entriesByKey[key] ?: return false
        return propertyName in entry.propertyNames
    }

    /**
     * Restituisce il percorso numerico nell'albero per [key], verificando che esista nel dump live
     * e, se [requiredProperty] e valorizzata, che tale proprieta sia gia pubblicata su quel nodo.
     */
    fun resolvePath(key: RodeNodeKey, requiredProperty: String? = null): List<Int> {
        val entry = entriesByKey[key]
            ?: throw RodeAddressGuardException("Nodo assente nel modello del mixer: ${key.format()}")
        if (requiredProperty != null && requiredProperty !in entry.propertyNames) {
            throw RodeAddressGuardException(
                "Proprieta '$requiredProperty' non presente su ${key.format()}: scrittura bloccata per sicurezza"
            )
        }
        return entry.path
    }

    companion object {
        private val INDEXED_PARENT_BLOCKS = setOf("PHYSICALINTERFACE")
    }
}

/**
 * Parser ed encoder binario del formato ValueTree utilizzato dal RØDECaster Pro II.
 *
 * Grammatica:
 * - `node     := NAME '\0' count(P) (property * P) count(C) (node * C)`
 * - `property := name '\0' count(vlen) payload`
 * - `count(n) := <lenByte L> <n in L byte little-endian>` (`L = 0` indica `n = 0`)
 */
object RodeValueTreeCodec {
    private const val TYPE_INT32: Byte = 0x01
    private const val TYPE_BOOL_TRUE: Byte = 0x02
    private const val TYPE_BOOL_FALSE: Byte = 0x03
    private const val TYPE_FLOAT64: Byte = 0x04
    private const val TYPE_STRING: Byte = 0x05
    private const val TYPE_INT64: Byte = 0x06
    private const val TYPE_COMPOSITE: Byte = 0x08
    private val ROOT_MARKER = "Rodecaster\u0000".toByteArray(Charsets.ISO_8859_1)

    fun parse(blob: ByteArray): RodeNode {
        if (blob.isEmpty()) {
            throw RodeTreeParseException("Dump modello vuoto")
        }
        // Il dump reale del RØDECaster Pro II e preceduto da un header di 5 byte
        // (<uint32 LE lunghezza totale> + 0x02) prima del nodo radice "Rodecaster\0".
        val startOffset = findRootMarkerOffset(blob).takeIf { it >= 0 } ?: 0
        val cursor = Cursor(blob, startOffset)
        return parseNode(cursor, indexInParent = 0)
    }

    internal fun findRootMarkerOffset(blob: ByteArray): Int {
        val maxStart = minOf(blob.size - ROOT_MARKER.size, 64)
        if (maxStart < 0) return -1
        for (i in 0..maxStart) {
            var matched = true
            for (j in ROOT_MARKER.indices) {
                if (blob[i + j] != ROOT_MARKER[j]) {
                    matched = false
                    break
                }
            }
            if (matched) return i
        }
        return -1
    }

    /**
     * Parsa un singolo record indirizzato completo
     * (`01 01 <depth> [<len><idx LE>]... <propName>\0 <valueBlock>`), restituendo null se
     * il buffer non e un record valido o contiene byte residui.
     */
    fun parseAddressedRecord(record: ByteArray): RodeAddressedRecord? {
        if (record.size < 6 || record[0] != 0x01.toByte() || record[1] != 0x01.toByte()) {
            return null
        }
        val levels = record[2].toInt() and 0xFF
        if (levels !in 1..8) return null
        return runCatching {
            val cursor = Cursor(record, startOffset = 3)
            val path = ArrayList<Int>(levels)
            repeat(levels) { path.add(cursor.readCount()) }
            val name = cursor.readNullTerminatedString()
            if (name.isEmpty() || name.any { it.code !in 0x20..0x7E }) return null
            val value = parseValue(cursor)
            if (cursor.hasRemaining()) return null
            RodeAddressedRecord(path = path, propertyName = name, value = value)
        }.getOrNull()
    }

    /**
     * Verifica se [record] e un singolo record indirizzato completo
     * (`01 01 <depth> [<len><idx LE>]... <propName>\0 <valueBlock>`).
     * Usato per distinguere i frame push asincroni dalle fette del dump del modello.
     */
    fun isCompleteAddressedRecord(record: ByteArray): Boolean =
        parseAddressedRecord(record) != null

    fun encodeNode(node: RodeNode): ByteArray {
        val out = ByteArrayOutputStream()
        writeNode(out, node)
        return out.toByteArray()
    }

    fun encodeCount(value: Int): ByteArray {
        require(value >= 0) { "Count cannot be negative: $value" }
        if (value == 0) return byteArrayOf(0x00)
        val byteLen = when {
            value <= 0xFF -> 1
            value <= 0xFFFF -> 2
            else -> 4
        }
        val out = ByteArray(1 + byteLen)
        out[0] = byteLen.toByte()
        for (b in 0 until byteLen) {
            out[1 + b] = ((value ushr (8 * b)) and 0xFF).toByte()
        }
        return out
    }

    fun encodeValue(value: RodeValue): ByteArray {
        val payload: ByteArray = when (value) {
            is RodeValue.Int32 -> ByteBuffer.allocate(5)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(TYPE_INT32)
                .putInt(value.value)
                .array()

            is RodeValue.Int64 -> ByteBuffer.allocate(9)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(TYPE_INT64)
                .putLong(value.value)
                .array()

            is RodeValue.Float64 -> ByteBuffer.allocate(9)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(TYPE_FLOAT64)
                .putDouble(value.value)
                .array()

            is RodeValue.Bool -> byteArrayOf(if (value.value) TYPE_BOOL_TRUE else TYPE_BOOL_FALSE)

            is RodeValue.Str -> {
                val bytes = value.value.toByteArray(Charsets.UTF_8)
                ByteArray(bytes.size + 2).also { arr ->
                    arr[0] = TYPE_STRING
                    bytes.copyInto(arr, destinationOffset = 1)
                    arr[arr.lastIndex] = 0x00
                }
            }

            is RodeValue.Composite -> {
                val inner = ByteArrayOutputStream()
                inner.write(TYPE_COMPOSITE.toInt())
                value.parts.forEach { part -> inner.write(encodeValue(part)) }
                inner.toByteArray()
            }

            is RodeValue.Raw -> ByteArray(1 + value.data.size).also { arr ->
                arr[0] = value.typeTag
                value.data.copyInto(arr, destinationOffset = 1)
            }

            RodeValue.Empty -> ByteArray(0)
        }

        val countPrefix = encodeCount(payload.size)
        return ByteArray(countPrefix.size + payload.size).also { out ->
            countPrefix.copyInto(out, 0)
            payload.copyInto(out, countPrefix.size)
        }
    }

    /**
     * Codifica un percorso dell'albero come indirizzo Report 0x03:
     * `01 01 <depth> [<len><index LE>] x depth`.
     *
     * Con `compactZero = false` lo zero e codificato `01 00` (usato per INPUTSOURCE, CHANNEL, MIX).
     * Con `compactZero = true` lo zero e codificato `00` (richiesto dal firmware per `faderLevel`).
     */
    fun encodeAddressPath(path: List<Int>, compactZero: Boolean = false): ByteArray {
        require(path.isNotEmpty() && path.size <= 0xFF) { "Percorso indirizzo non valido: $path" }
        val out = ByteArrayOutputStream()
        out.write(0x01)
        out.write(0x01)
        out.write(path.size)
        path.forEach { index ->
            require(index >= 0) { "Indice percorso negativo: $index" }
            if (index == 0 && compactZero) {
                out.write(0x00)
            } else {
                val byteLen = when {
                    index <= 0xFF -> 1
                    index <= 0xFFFF -> 2
                    else -> 4
                }
                out.write(byteLen)
                for (b in 0 until byteLen) {
                    out.write((index ushr (8 * b)) and 0xFF)
                }
            }
        }
        return out.toByteArray()
    }

    private fun writeNode(out: ByteArrayOutputStream, node: RodeNode) {
        out.write(node.name.toByteArray(Charsets.ISO_8859_1))
        out.write(0x00)
        out.write(encodeCount(node.properties.size))
        node.properties.forEach { prop ->
            out.write(prop.name.toByteArray(Charsets.ISO_8859_1))
            out.write(0x00)
            out.write(encodeValue(prop.value))
        }
        out.write(encodeCount(node.children.size))
        node.children.forEach { child ->
            writeNode(out, child)
        }
    }

    private fun parseNode(cursor: Cursor, indexInParent: Int): RodeNode {
        val name = cursor.readNullTerminatedString()
        val propCount = cursor.readCount()
        val props = ArrayList<RodeProperty>(propCount.coerceAtMost(256))
        repeat(propCount) {
            val propName = cursor.readNullTerminatedString()
            val propValue = parseValue(cursor)
            props.add(RodeProperty(propName, propValue))
        }
        val childCount = cursor.readCount()
        val children = ArrayList<RodeNode>(childCount.coerceAtMost(512))
        for (i in 0 until childCount) {
            children.add(parseNode(cursor, indexInParent = i))
        }
        return RodeNode(
            name = name,
            properties = props,
            children = children,
            indexInParent = indexInParent
        )
    }

    private fun parseValue(cursor: Cursor): RodeValue {
        val valueLen = cursor.readCount()
        val payload = cursor.readBytes(valueLen)
        if (payload.isEmpty()) return RodeValue.Empty
        val typeTag = payload[0]
        return when (typeTag) {
            TYPE_INT32 -> {
                if (payload.size < 5) {
                    throw RodeTreeParseException("Valore Int32 troncato (${payload.size} byte)")
                }
                val v = ByteBuffer.wrap(payload, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
                RodeValue.Int32(v)
            }

            TYPE_BOOL_TRUE -> RodeValue.Bool(true)
            TYPE_BOOL_FALSE -> RodeValue.Bool(false)

            TYPE_FLOAT64 -> {
                if (payload.size < 9) {
                    throw RodeTreeParseException("Valore Float64 troncato (${payload.size} byte)")
                }
                val v = ByteBuffer.wrap(payload, 1, 8).order(ByteOrder.LITTLE_ENDIAN).double
                RodeValue.Float64(v)
            }

            TYPE_STRING -> {
                val endIdx = (1 until payload.size).firstOrNull { payload[it] == 0.toByte() } ?: payload.size
                val text = String(payload, 1, endIdx - 1, Charsets.UTF_8)
                RodeValue.Str(text)
            }

            TYPE_INT64 -> {
                if (payload.size < 9) {
                    throw RodeTreeParseException("Valore Int64 troncato (${payload.size} byte)")
                }
                val v = ByteBuffer.wrap(payload, 1, 8).order(ByteOrder.LITTLE_ENDIAN).long
                RodeValue.Int64(v)
            }

            TYPE_COMPOSITE -> {
                val inner = Cursor(payload.copyOfRange(1, payload.size))
                val parts = mutableListOf<RodeValue>()
                while (inner.hasRemaining()) {
                    parts.add(parseValue(inner))
                }
                RodeValue.Composite(parts)
            }

            else -> RodeValue.Raw(typeTag, payload.copyOfRange(1, payload.size))
        }
    }

    private class Cursor(
        private val data: ByteArray,
        startOffset: Int = 0
    ) {
        private var pos = startOffset

        fun hasRemaining(): Boolean = pos < data.size

        fun readNullTerminatedString(): String {
            val start = pos
            while (pos < data.size && data[pos] != 0.toByte()) {
                pos++
            }
            if (pos >= data.size) {
                throw RodeTreeParseException("Stringa senza terminatore null a offset $start")
            }
            val result = String(data, start, pos - start, Charsets.ISO_8859_1)
            pos++ // salta '\0'
            return result
        }

        fun readCount(): Int {
            if (pos >= data.size) {
                throw RodeTreeParseException("Fine inattesa del dump durante la lettura del conteggio")
            }
            val lenBytes = data[pos++].toInt() and 0xFF
            if (lenBytes == 0) return 0
            if (lenBytes > 4 || pos + lenBytes > data.size) {
                throw RodeTreeParseException("Conteggio non valido ($lenBytes byte a offset ${pos - 1})")
            }
            var result = 0
            for (b in 0 until lenBytes) {
                result = result or ((data[pos++].toInt() and 0xFF) shl (8 * b))
            }
            return result
        }

        fun readBytes(length: Int): ByteArray {
            if (length < 0 || pos + length > data.size) {
                throw RodeTreeParseException(
                    "Fine inattesa del dump: richiesti $length byte a offset $pos su ${data.size}"
                )
            }
            val slice = data.copyOfRange(pos, pos + length)
            pos += length
            return slice
        }
    }
}
