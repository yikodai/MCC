package mccandroid.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PacketReaderTest {

    @Test
    fun `VarInt 往返编码`() {
        val values = listOf(0, 1, 2, 127, 128, 255, 300, 25565, 2097151, Int.MAX_VALUE, -1)
        for (value in values) {
            val encoded = PacketWriter.encodeVarInt(value)
            assertEquals(value, PacketReader(encoded).readVarInt(), "VarInt 往返失败：$value")
        }
    }

    @Test
    fun `VarInt 负数使用固定 5 字节`() {
        assertEquals(5, PacketWriter.encodeVarInt(-1).size)
    }

    @Test
    fun `VarLong 往返编码`() {
        val values = listOf(0L, 1L, 127L, 128L, 2147483647L, 4294967295L)
        for (value in values) {
            val encoded = PacketWriter().writeVarLong(value).toByteArray()
            assertEquals(value, PacketReader(encoded).readVarLong(), "VarLong 往返失败：$value")
        }
    }

    @Test
    fun `定长整数与浮点数往返`() {
        val writer = PacketWriter()
            .writeShort(0x1234)
            .writeInt(-123456)
            .writeLong(Long.MIN_VALUE)
            .writeFloat(1.5f)
            .writeDouble(-2.25)
        val reader = PacketReader(writer.toByteArray())
        assertEquals(0x1234.toShort(), reader.readShort())
        assertEquals(-123456, reader.readInt())
        assertEquals(Long.MIN_VALUE, reader.readLong())
        assertEquals(1.5f, reader.readFloat())
        assertEquals(-2.25, reader.readDouble())
        assertEquals(0, reader.remaining)
    }

    @Test
    fun `无符号短整数按无符号解释`() {
        assertEquals(65535, PacketReader(byteArrayOf(0xFF.toByte(), 0xFF.toByte())).readUnsignedShort())
        assertEquals(25565, PacketReader(byteArrayOf(0x63, 0xDD.toByte())).readUnsignedShort())
    }

    @Test
    fun `字符串按 UTF-8 往返`() {
        val text = "你好，Minecraft §a颜色代码"
        val encoded = PacketWriter().writeString(text).toByteArray()
        assertEquals(text, PacketReader(encoded).readString())
    }

    @Test
    fun `字符串长度按字节计算`() {
        val encoded = PacketWriter().writeString("中").toByteArray()
        // 1 字节长度前缀 + 3 字节 UTF-8
        assertEquals(4, encoded.size)
    }

    @Test
    fun `UUID 往返`() {
        val uuid = java.util.UUID.randomUUID()
        val encoded = PacketWriter().writeUUID(uuid).toByteArray()
        assertEquals(uuid, PacketReader(encoded).readUUID())
    }

    @Test
    fun `UUID 使用大端字节序`() {
        val uuid = java.util.UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")
        val bytes = PacketWriter().writeUUID(uuid).toByteArray()
        assertEquals(listOf<Byte>(0x00, 0x11, 0x22, 0x33), bytes.take(4))
        assertEquals(listOf<Byte>(0x88.toByte(), 0x99.toByte(), 0xAA.toByte(), 0xBB.toByte()), bytes.drop(8).take(4))
    }

    @Test
    fun `布尔值按 1 字节表示`() {
        assertEquals(1, PacketWriter().writeBoolean(true).toByteArray().size)
        assertTrue(PacketReader(PacketWriter().writeBoolean(true).toByteArray()).readBoolean())
        assertEquals(false, PacketReader(byteArrayOf(0)).readBoolean())
    }

    @Test
    fun `读取越界抛出协议异常`() {
        val reader = PacketReader(byteArrayOf(1, 2))
        assertFailsWith<ProtocolException> { reader.readInt() }
    }

    @Test
    fun `VarInt 超过 5 字节时抛出异常`() {
        val invalid = byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x01)
        assertFailsWith<ProtocolException> { PacketReader(invalid).readVarInt() }
    }

    @Test
    fun `seek 可回退到之前的位置`() {
        val reader = PacketReader(PacketWriter().writeString("abc").writeInt(7).toByteArray())
        val mark = reader.position()
        reader.readString()
        assertEquals(7, reader.readInt())
        reader.seek(mark)
        assertEquals("abc", reader.readString())
    }

    @Test
    fun `读取 VarInt 长度前缀的字节数组`() {
        val data = byteArrayOf(9, 8, 7)
        val encoded = PacketWriter().writeVarIntPrefixedByteArray(data).toByteArray()
        assertEquals(data.toList(), PacketReader(encoded).readVarIntPrefixedByteArray().toList())
    }
}