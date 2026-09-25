package mccandroid.core.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatParserTest {

    private fun legacy(json: String): String = ChatParser.toLegacyString(Json.parseToJsonElement(json))

    private fun plain(json: String): String = ChatParser.toPlainText(Json.parseToJsonElement(json))

    @Test
    fun `纯字符串形式直接返回文本`() {
        assertEquals("hello", ChatParser.toLegacyString(JsonPrimitive("hello")))
        assertEquals("hello", ChatParser.toLegacyString("\"hello\""))
    }

    @Test
    fun `text 对象渲染为文本`() {
        assertEquals("hi", legacy("""{"text":"hi"}"""))
    }

    @Test
    fun `extra 子组件按顺序拼接`() {
        assertEquals("ab", legacy("""{"text":"a","extra":[{"text":"b"}]}"""))
    }

    @Test
    fun `translate 使用 with 参数替换`() {
        assertEquals("<Alice> Hello", legacy("""{"translate":"chat.type.text","with":["Alice","Hello"]}"""))
    }

    @Test
    fun `translate 未命中内置表时保留键名与参数`() {
        assertEquals("foo.bar(a, b)", legacy("""{"translate":"foo.bar","with":["a","b"]}"""))
    }

    @Test
    fun `十六进制颜色生成对应颜色代码`() {
        val component = Json.parseToJsonElement("""{"text":"x","color":"#ff0000"}""")
        val result = ChatParser.toLegacyString(component)
        assertTrue(result.contains("§#ff0000"), "实际输出: $result")
        assertEquals("x", ChatParser.toPlainText(component))
    }

    @Test
    fun `bold 样式生成加粗代码`() {
        val component = Json.parseToJsonElement("""{"text":"x","bold":true}""")
        val result = ChatParser.toLegacyString(component)
        assertTrue(result.contains("§l"), "实际输出: $result")
        assertEquals("x", ChatParser.toPlainText(component))
    }

    @Test
    fun `toPlainText 去除颜色代码`() {
        assertEquals("x", plain("""{"text":"x","color":"red"}"""))
        assertEquals("", ChatParser.toPlainText(null))
    }

    @Test
    fun `非法 JSON 字符串回退为原始字符串`() {
        assertEquals("not json", ChatParser.toLegacyString("not json"))
        assertEquals("{invalid", ChatParser.toLegacyString("{invalid"))
    }
}