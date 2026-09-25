package mccandroid.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PacketFramingTest {

    /** 按流式读取的方式剥掉长度前缀，再用 decodePayload 解包 */
    private fun roundTrip(packetId: Int, payload: ByteArray, threshold: Int): Pair<Int, ByteArray> {
        val framed = PacketFraming.encode(packetId, payload, threshold)
        val reader = PacketReader(framed)
        val length = reader.readVarInt()
        assertEquals(framed.size - reader.position(), length, "长度前缀必须等于包体长度")
        val (id, body) = PacketFraming.decodePayload(reader.readByteArray(length), threshold)
        return id to body.readRemaining()
    }

    @Test
    fun `未启用压缩时原样收发`() {
        val payload = PacketWriter().writeString("hello").writeInt(42).toByteArray()
        val (id, body) = roundTrip(0x05, payload, -1)
        assertEquals(0x05, id)
        assertEquals(payload.toList(), body.toList())
    }

    @Test
    fun `小于阈值的数据包保持未压缩`() {
        val payload = PacketWriter().writeString("tiny").toByteArray()
        val (id, body) = roundTrip(0x10, payload, 256)
        assertEquals(0x10, id)
        assertEquals(payload.toList(), body.toList())
    }

    @Test
    fun `超过阈值的数据包会被压缩并可还原`() {
        // 重复内容便于压缩，且长度必须超过阈值
        val payload = PacketWriter().writeString("z".repeat(2000)).toByteArray()
        val framed = PacketFraming.encode(0x20, payload, 256)
        assertTrue(framed.size < payload.size, "压缩后应当明显变小")

        val reader = PacketReader(framed)
        val length = reader.readVarInt()
        val (id, body) = PacketFraming.decodePayload(reader.readByteArray(length), 256)
        assertEquals(0x20, id)
        assertEquals(payload.toList(), body.readRemaining().toList())
    }

    @Test
    fun `压缩声明长度与实际不符时抛出异常`() {
        val payload = PacketWriter().writeString("z".repeat(2000)).toByteArray()
        val framed = PacketFraming.encode(0x20, payload, 256)
        val reader = PacketReader(framed)
        val length = reader.readVarInt()
        val corrupted = reader.readByteArray(length).copyOf()
        corrupted[0] = 0x7F // 篡改声明的解压长度

        assertFailsWith<ProtocolException> {
            PacketFraming.decodePayload(corrupted, 256)
        }
    }

    @Test
    fun `大包 ID 使用 VarInt 编码`() {
        val (id, body) = roundTrip(0x80, PacketWriter().writeByte(1).toByteArray(), -1)
        assertEquals(0x80, id)
        assertEquals(1, body.size)
    }
}