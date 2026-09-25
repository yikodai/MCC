package mccandroid.core.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import mccandroid.core.protocol.PacketReader
import mccandroid.core.protocol.ProtocolException
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class NbtReaderTest {

    @Test
    fun `无名字 root compound 含 String Int Byte List 嵌套 Compound`() {
        val bytes = nbt {
            u8(0x0A) // 根为 TAG_Compound，无名字
            field(0x08, "name") { str("Steve") }
            field(0x03, "level") { i32(42) }
            field(0x01, "flag") { u8(1) }
            field(0x09, "nums") {
                u8(0x03) // 元素类型 TAG_Int
                i32(3)
                i32(1); i32(2); i32(3)
            }
            field(0x0A, "meta") {
                field(0x03, "id") { i32(7) }
                u8(0x00) // 嵌套复合体结束
            }
            u8(0x00) // 根复合体结束
        }

        val root = readNetworkNbt(PacketReader(bytes)) as JsonObject
        assertEquals("Steve", (root["name"] as JsonPrimitive).content)
        assertEquals(42, (root["level"] as JsonPrimitive).intOrNull)
        assertEquals(1, (root["flag"] as JsonPrimitive).intOrNull)

        val nums = root["nums"] as JsonArray
        assertEquals(3, nums.size)
        assertEquals(1, (nums[0] as JsonPrimitive).intOrNull)
        assertEquals(2, (nums[1] as JsonPrimitive).intOrNull)
        assertEquals(3, (nums[2] as JsonPrimitive).intOrNull)

        val meta = root["meta"] as JsonObject
        assertEquals(7, (meta["id"] as JsonPrimitive).intOrNull)
    }

    @Test
    fun `根为 TAG_End 时返回空对象`() {
        assertEquals(JsonObject(emptyMap()), readNetworkNbt(PacketReader(byteArrayOf(0x00))))
    }

    @Test
    fun `readNbtTagOrNull 遇到 0x00 返回 null`() {
        assertNull(readNbtTagOrNull(PacketReader(byteArrayOf(0x00))))
    }

    @Test
    fun `readNbtTagOrNull 读取带类型 ID 的单个标签`() {
        val bytes = nbt { u8(0x03); i32(5) }
        assertEquals(5, (readNbtTagOrNull(PacketReader(bytes)) as JsonPrimitive).intOrNull)
    }

    @Test
    fun `根为 TAG_String 时返回字符串`() {
        val bytes = nbt {
            u8(0x08) // TAG_String
            str("hello")
        }
        assertEquals("hello", (readNetworkNbt(PacketReader(bytes)) as JsonPrimitive).content)
    }

    @Test
    fun `readNamedNbt 读取根名字与复合体内容`() {
        val bytes = nbt {
            str("root") // 根名字：unsigned short 长度 + ASCII
            field(0x03, "x") { i32(9) }
            u8(0x00)
        }
        val obj = readNamedNbt(PacketReader(bytes))
        assertEquals(9, (obj["x"] as JsonPrimitive).intOrNull)
    }

    @Test
    fun `数据不足时抛出 ProtocolException`() {
        // 连根类型 ID 都没有
        assertFailsWith<ProtocolException> { readNetworkNbt(PacketReader(byteArrayOf())) }
        // 声明了 5 字节字符串，但后面没有数据
        assertFailsWith<ProtocolException> {
            readNetworkNbt(PacketReader(byteArrayOf(0x08, 0x00, 0x05)))
        }
    }

    @Test
    fun `未知标签类型抛出 ProtocolException`() {
        assertFailsWith<ProtocolException> { readNbtTagOrNull(PacketReader(byteArrayOf(0x0D))) }
    }
}

/** 手工构造 NBT 字节数组的简易写入器。 */
private class NbtWriter {
    private val out = ByteArrayOutputStream()

    fun u8(value: Int) {
        out.write(value and 0xFF)
    }

    fun i32(value: Int) {
        for (shift in 24 downTo 0 step 8) out.write((value ushr shift) and 0xFF)
    }

    fun str(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        u8((bytes.size ushr 8) and 0xFF)
        u8(bytes.size and 0xFF)
        out.write(bytes)
    }

    /** 写入一个复合体内的命名字段：类型 ID + 名字 + 值。 */
    fun field(type: Int, name: String, payload: NbtWriter.() -> Unit) {
        u8(type)
        str(name)
        payload()
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

private fun nbt(block: NbtWriter.() -> Unit): ByteArray = NbtWriter().apply(block).toByteArray()