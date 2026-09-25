package mccandroid.core.protocol

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Minecraft 协议字节读取器。
 *
 * 端口自 MCC `Protocol/Handlers/DataTypes.cs`，只保留安卓端需要的部分：
 * VarInt / VarLong / 字符串 / UUID / 字节数组。
 *
 * 所有多字节整数均为大端（网络字节序），VarInt 为 Minecraft 的 LEB128 变体。
 */
class PacketReader(private val data: ByteArray, private var pos: Int = 0) {

    val remaining: Int get() = data.size - pos

    private fun require(count: Int) {
        if (pos + count > data.size) {
            throw ProtocolException("数据不足：需要 $count 字节，剩余 ${data.size - pos} 字节")
        }
    }

    fun readUnsignedByte(): Int {
        require(1)
        return data[pos++].toInt() and 0xFF
    }

    fun readByte(): Byte = readUnsignedByte().toByte()

    fun readBoolean(): Boolean = readUnsignedByte() != 0

    fun readShort(): Short {
        require(2)
        val value = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
        pos += 2
        return value.toShort()
    }

    fun readUnsignedShort(): Int = readShort().toInt() and 0xFFFF

    fun readInt(): Int {
        require(4)
        val value = ((data[pos].toInt() and 0xFF) shl 24) or
            ((data[pos + 1].toInt() and 0xFF) shl 16) or
            ((data[pos + 2].toInt() and 0xFF) shl 8) or
            (data[pos + 3].toInt() and 0xFF)
        pos += 4
        return value
    }

    fun readLong(): Long {
        require(8)
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (data[pos + i].toLong() and 0xFF)
        }
        pos += 8
        return value
    }

    fun readFloat(): Float = Float.fromBits(readInt())

    fun readDouble(): Double = Double.fromBits(readLong())

    fun readVarInt(): Int {
        var result = 0
        var shift = 0
        while (true) {
            if (shift >= 35) {
                throw ProtocolException("VarInt 超过 5 字节上限")
            }
            val current = readUnsignedByte()
            result = result or ((current and 0x7F) shl shift)
            if (current and 0x80 == 0) {
                return result
            }
            shift += 7
        }
    }

    fun readVarLong(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (shift >= 70) {
                throw ProtocolException("VarLong 超过 10 字节上限")
            }
            val current = readUnsignedByte()
            result = result or ((current.toLong() and 0x7F) shl shift)
            if (current and 0x80 == 0) {
                return result
            }
            shift += 7
        }
    }

    /**
     * 读取 VarInt 长度前缀的 UTF-8 字符串。
     *
     * @param maxLength 允许的最大字符数，用于防御畸形包。
     */
    fun readString(maxLength: Int = MAX_STRING_LENGTH): String {
        val length = readVarInt()
        if (length < 0 || length > maxLength * 4) {
            throw ProtocolException("字符串长度非法：$length")
        }
        require(length)
        val value = String(data, pos, length, StandardCharsets.UTF_8)
        pos += length
        if (value.length > maxLength) {
            throw ProtocolException("字符串超过 $maxLength 字符")
        }
        return value
    }

    fun readByteArray(length: Int): ByteArray {
        if (length < 0) {
            throw ProtocolException("字节数组长度非法：$length")
        }
        require(length)
        val value = data.copyOfRange(pos, pos + length)
        pos += length
        return value
    }

    /** 读取 VarInt 长度前缀的字节数组（协议中的 "Byte Array" / "Rest Buffer"）。 */
    fun readVarIntPrefixedByteArray(): ByteArray = readByteArray(readVarInt())

    fun readUUID(): UUID = UUID(readLong(), readLong())

    /** 读取长度为 [count] 的 VarInt 数组（部分旧包使用）。 */
    fun readVarIntArray(count: Int): IntArray = IntArray(count) { readVarInt() }

    fun readRemaining(): ByteArray = readByteArray(remaining)

    /** 跳过指定字节数。 */
    fun skip(count: Int) {
        require(count)
        pos += count
    }

    /** 当前读取位置，配合 [seek] 用于在解析失败后回退重试（例如聊天组件的 JSON 回退）。 */
    fun position(): Int = pos

    /** 回到指定位置 */
    fun seek(position: Int) {
        if (position < 0 || position > data.size) {
            throw ProtocolException("非法定位：$position")
        }
        pos = position
    }

    companion object {
        const val MAX_STRING_LENGTH = 262144
    }
}

/**
 * Minecraft 协议字节写入器，与 [PacketReader] 对称。
 */
class PacketWriter {

    private val buffer = ByteArrayOutputStream()

    fun writeByte(value: Int): PacketWriter {
        buffer.write(value and 0xFF)
        return this
    }

    fun writeBoolean(value: Boolean): PacketWriter = writeByte(if (value) 1 else 0)

    fun writeShort(value: Int): PacketWriter {
        buffer.write((value ushr 8) and 0xFF)
        buffer.write(value and 0xFF)
        return this
    }

    fun writeInt(value: Int): PacketWriter {
        buffer.write((value ushr 24) and 0xFF)
        buffer.write((value ushr 16) and 0xFF)
        buffer.write((value ushr 8) and 0xFF)
        buffer.write(value and 0xFF)
        return this
    }

    fun writeLong(value: Long): PacketWriter {
        for (i in 7 downTo 0) {
            buffer.write(((value ushr (i * 8)) and 0xFF).toInt())
        }
        return this
    }

    fun writeFloat(value: Float): PacketWriter = writeInt(value.toBits())

    fun writeDouble(value: Double): PacketWriter = writeLong(value.toBits())

    fun writeVarInt(value: Int): PacketWriter {
        var current = value
        while (true) {
            if (current and 0x7F.inv() == 0) {
                buffer.write(current)
                return this
            }
            buffer.write((current and 0x7F) or 0x80)
            current = current ushr 7
        }
    }

    fun writeVarLong(value: Long): PacketWriter {
        var current = value
        while (true) {
            if (current and 0x7FL.inv() == 0L) {
                buffer.write(current.toInt())
                return this
            }
            buffer.write(((current and 0x7F) or 0x80).toInt())
            current = current ushr 7
        }
    }

    fun writeString(value: String): PacketWriter {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeVarInt(bytes.size)
        buffer.write(bytes)
        return this
    }

    fun writeByteArray(value: ByteArray): PacketWriter {
        buffer.write(value)
        return this
    }

    /** 写入 VarInt 长度前缀的字节数组。 */
    fun writeVarIntPrefixedByteArray(value: ByteArray): PacketWriter {
        writeVarInt(value.size)
        buffer.write(value)
        return this
    }

    fun writeUUID(value: UUID): PacketWriter = writeLong(value.mostSignificantBits).writeLong(value.leastSignificantBits)

    fun toByteArray(): ByteArray = buffer.toByteArray()

    companion object {
        /** 单独编码 VarInt，用于包长前缀等场景。 */
        fun encodeVarInt(value: Int): ByteArray = PacketWriter().writeVarInt(value).toByteArray()
    }
}

/** 协议解析异常 */
class ProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)