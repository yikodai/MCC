package mccandroid.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ServerAddressTest {

    @Test
    fun `仅主机名时使用默认端口`() {
        val address = ServerAddress.parse("mc.example.com")
        assertEquals("mc.example.com", address.host)
        assertEquals(25565, address.port)
        assertEquals("mc.example.com", address.handshakeHost)
    }

    @Test
    fun `解析自定义端口`() {
        val address = ServerAddress.parse("mc.example.com:25566")
        assertEquals("mc.example.com", address.host)
        assertEquals(25566, address.port)
        assertEquals("mc.example.com:25566", address.display)
    }

    @Test
    fun `解析 IPv6 地址`() {
        val address = ServerAddress.parse("[::1]:25565")
        assertEquals("::1", address.host)
        assertEquals(25565, address.port)
    }

    @Test
    fun `去掉输入两端空白`() {
        assertEquals("host", ServerAddress.parse("  host  ").host)
    }

    @Test
    fun `端口非法时抛出异常`() {
        assertFailsWith<ProtocolException> { ServerAddress.parse("host:abc") }
    }

    @Test
    fun `地址为空时抛出异常`() {
        assertFailsWith<ProtocolException> { ServerAddress.parse("   ") }
    }

    @Test
    fun `SRV 解析结果保留原始握手域名`() {
        val address = ServerAddress("node1.example.net", 25570, handshakeHost = "mc.example.com")
        assertEquals("mc.example.com", address.handshakeHost)
        assertEquals("node1.example.net:25570", address.display)
    }
}