package mccandroid.core.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mccandroid.core.protocol.PacketReader
import mccandroid.core.protocol.ProtocolException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

// NBT 标签类型 ID（与 Java 版一致，0..12）
private const val TAG_END = 0
private const val TAG_BYTE = 1
private const val TAG_SHORT = 2
private const val TAG_INT = 3
private const val TAG_LONG = 4
private const val TAG_FLOAT = 5
private const val TAG_DOUBLE = 6
private const val TAG_BYTE_ARRAY = 7
private const val TAG_STRING = 8
private const val TAG_LIST = 9
private const val TAG_COMPOUND = 10
private const val TAG_INT_ARRAY = 11
private const val TAG_LONG_ARRAY = 12

/**
 * Minecraft「网络 NBT」读取（Java 版 1.20.2+ 的编码方式）。
 *
 * 与磁盘上的经典 NBT 相比，网络 NBT 有两点不同：
 * - 根标签没有名字（不再有 unsigned short 名字长度前缀）。
 * - 字符串长度使用 unsigned short，而不是 VarInt；根标签允许 TAG_Compound 或 TAG_String。
 *
 * 解析结果统一映射为 kotlinx.serialization 的 [JsonElement]：
 * - Compound 映射为 [JsonObject]
 * - List / IntArray / LongArray / ByteArray 映射为 [JsonArray]
 * - Byte / Short / Int / Long / Float / Double 映射为数值 [JsonPrimitive]
 * - String 映射为字符串 [JsonPrimitive]
 *
 * 所有字节读取都委托给 [PacketReader]，数据不足时由其抛出 [ProtocolException]。
 */

/**
 * 读取一个网络 NBT 根标签（1.20.2+ 规则）。
 *
 * 根标签无名字，允许的根类型为：
 * - TAG_End：返回空的 [JsonObject]
 * - TAG_Compound：返回解析出的 [JsonObject]
 * - TAG_String（1.20.3+ 根可以是字符串）：返回该字符串对应的 [JsonPrimitive]
 *
 * @param reader 已定位到根标签类型 ID 的读取器。
 * @throws ProtocolException 根类型非法、内部标签类型未知或数据不足时抛出。
 */
fun readNetworkNbt(reader: PacketReader): JsonElement {
    val rootType = reader.readUnsignedByte()
    return when (rootType) {
        TAG_END -> JsonObject(emptyMap())
        TAG_COMPOUND -> JsonObject(readCompoundBody(reader))
        TAG_STRING -> JsonPrimitive(readShortString(reader))
        else -> throw ProtocolException(
            "网络 NBT 根标签类型非法：$rootType，只允许 TAG_Compound、TAG_String 或 TAG_End"
        )
    }
}

/**
 * 读取一个可选的「带类型 ID 的单个标签」，用于包中的可选 NBT 字段。
 *
 * 遇到类型 ID 0（TAG_End）时返回 null，用于表示该字段不存在；其余情况返回解析结果。
 *
 * @throws ProtocolException 标签类型未知或数据不足时抛出。
 */
fun readNbtTagOrNull(reader: PacketReader): JsonElement? {
    val tagType = reader.readUnsignedByte()
    if (tagType == TAG_END) return null
    return readTagPayload(reader, tagType)
}

/**
 * 读取带根名字的旧式 NBT（1.20.2 之前的编码方式）。
 *
 * 本函数假定根标签为 TAG_Compound，因此不读取根类型 ID，直接读取根名字
 * （unsigned short 长度 + ASCII 字节），随后解析复合体内容。根名字会被读取并丢弃，
 * 返回值是复合体本身的字段。
 *
 * @throws ProtocolException 数据不足、内部标签类型未知时抛出。
 */
fun readNamedNbt(reader: PacketReader): JsonObject {
    readShortString(reader, StandardCharsets.US_ASCII)
    return JsonObject(readCompoundBody(reader))
}

/**
 * 解析复合体内容，直到遇到 TAG_End。
 *
 * 复合体内的每个子字段都是「类型 ID + 名字 + 值」结构；名字为 unsigned short 长度前缀的字符串。
 */
private fun readCompoundBody(reader: PacketReader): Map<String, JsonElement> {
    val fields = LinkedHashMap<String, JsonElement>()
    while (true) {
        val fieldType = reader.readUnsignedByte()
        if (fieldType == TAG_END) return fields
        val name = readShortString(reader)
        fields[name] = readTagPayload(reader, fieldType)
    }
}

/**
 * 按类型 ID 读取标签的值部分（不含类型 ID 与名字）。
 *
 * 未知类型会抛出 [ProtocolException]，避免静默吞掉真实解析错误。
 */
private fun readTagPayload(reader: PacketReader, type: Int): JsonElement = when (type) {
    TAG_BYTE -> JsonPrimitive(reader.readByte())
    TAG_SHORT -> JsonPrimitive(reader.readShort())
    TAG_INT -> JsonPrimitive(reader.readInt())
    TAG_LONG -> JsonPrimitive(reader.readLong())
    TAG_FLOAT -> JsonPrimitive(reader.readFloat())
    TAG_DOUBLE -> JsonPrimitive(reader.readDouble())
    TAG_BYTE_ARRAY -> {
        val length = reader.readInt()
        checkLength(reader, length, "字节数组")
        JsonArray(List(length) { JsonPrimitive(reader.readByte()) })
    }
    TAG_STRING -> JsonPrimitive(readShortString(reader))
    TAG_LIST -> readList(reader)
    TAG_COMPOUND -> JsonObject(readCompoundBody(reader))
    TAG_INT_ARRAY -> {
        val length = reader.readInt()
        checkLength(reader, length, "整型数组")
        JsonArray(List(length) { JsonPrimitive(reader.readInt()) })
    }
    TAG_LONG_ARRAY -> {
        val length = reader.readInt()
        checkLength(reader, length, "长整型数组")
        JsonArray(List(length) { JsonPrimitive(reader.readLong()) })
    }
    else -> throw ProtocolException("未知的 NBT 标签类型：$type")
}

/**
 * 读取 TAG_List：先读元素类型 ID 与元素数量，再逐个读取元素。
 *
 * 列表元素不自带名字与类型；空列表按规范使用元素类型 TAG_End 与长度 0 表示。
 */
private fun readList(reader: PacketReader): JsonArray {
    val elementType = reader.readUnsignedByte()
    val length = reader.readInt()
    if (elementType == TAG_END) {
        if (length != 0) throw ProtocolException("NBT 列表元素类型为 TAG_End 但长度非零：$length")
        return JsonArray(emptyList())
    }
    checkLength(reader, length, "列表")
    val items = ArrayList<JsonElement>(length)
    repeat(length) { items.add(readTagPayload(reader, elementType)) }
    return JsonArray(items)
}

/** 读取 unsigned short 长度前缀的字符串。字段名使用 UTF-8（ASCII 为其子集）。 */
private fun readShortString(reader: PacketReader, charset: Charset = StandardCharsets.UTF_8): String {
    val length = reader.readUnsignedShort()
    val bytes = reader.readByteArray(length)
    return String(bytes, charset)
}

/** 校验集合长度，避免畸形长度导致超大内存分配。 */
private fun checkLength(reader: PacketReader, length: Int, label: String) {
    if (length < 0) throw ProtocolException("NBT $label 长度非法：$length")
    if (length > reader.remaining) {
        throw ProtocolException("NBT $label 长度超出剩余数据：$length > ${reader.remaining}")
    }
}