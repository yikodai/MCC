package mccandroid.core.auth

import kotlinx.coroutines.runBlocking
import mccandroid.core.McVersion
import mccandroid.core.session.ServerEntries
import mccandroid.core.session.ServerEntry
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountTest {

    @Test
    fun `离线账号 UUID 与 Minecraft 服务端算法一致`() {
        // 原版离线模式的 UUID 由 "OfflinePlayer:<名字>" 的 MD5（UUID v3）得到
        assertEquals(
            UUID.fromString("b50ad385-829d-3141-a216-7e7d7539ba7f"),
            OfflineAccounts.offlineUuid("Notch"),
        )
    }

    @Test
    fun `离线账号同一用户名 UUID 稳定`() {
        assertEquals(OfflineAccounts.offlineUuid("Steve"), OfflineAccounts.offlineUuid("Steve"))
        assertTrue(OfflineAccounts.offlineUuid("Steve") != OfflineAccounts.offlineUuid("Alex"))
    }

    @Test
    fun `构造离线账号`() {
        val account = OfflineAccounts.create("Steve")
        assertEquals(AccountType.OFFLINE, account.type)
        assertFalse(account.isMicrosoft)
        assertEquals("Steve", account.username)
        assertNull(account.refreshToken)
    }

    @Test
    fun `账号记录编解码往返`() {
        val account = MccAccount(
            type = AccountType.MICROSOFT,
            login = "player@example.com",
            username = "Player",
            uuid = UUID.randomUUID(),
            refreshToken = "refresh-token",
            accessToken = "access-token",
            accessTokenExpiresAt = Instant.parse("2030-01-01T00:00:00Z"),
        )

        val decoded = AccountCodec.decode(AccountCodec.encode(account))
        assertEquals(account, decoded)
        assertTrue(decoded.isMicrosoft)
    }

    @Test
    fun `访问令牌过期判断包含安全余量`() {
        val account = MccAccount(
            type = AccountType.MICROSOFT,
            login = "player@example.com",
            username = "Player",
            uuid = UUID.randomUUID(),
            accessToken = "token",
            accessTokenExpiresAt = Instant.parse("2030-01-01T00:00:00Z"),
        )
        assertFalse(account.isAccessTokenExpired(Instant.parse("2029-12-30T00:00:00Z")))
        assertTrue(account.isAccessTokenExpired(Instant.parse("2029-12-31T23:59:30Z")))
    }

    @Test
    fun `服务器列表编解码往返`() {
        val servers = listOf(
            ServerEntry("生存服", "mc.example.com", 25565),
            ServerEntry("小游戏", "play.example.com", 25566),
        )
        assertEquals(servers, ServerEntries.decode(ServerEntries.encode(servers)))
    }

    @Test
    fun `服务器地址展示省略默认端口`() {
        assertEquals("mc.example.com", ServerEntry("服", "mc.example.com").displayAddress)
        assertEquals("mc.example.com:25566", ServerEntry("服", "mc.example.com", 25566).displayAddress)
    }

    @Test
    fun `损坏的 JSON 返回空列表而不是抛异常`() {
        assertTrue(ServerEntries.decode("{not-json").isEmpty())
    }

    @Test
    fun `微软刷新令牌可用性判断`() = runBlocking {
        // 离线账号不需要刷新令牌，ensureFreshAccount 会原样返回
        val offline = OfflineAccounts.create("Steve")
        val flow = MicrosoftLoginFlow(storage = null)
        assertEquals(offline, flow.ensureFreshAccount(offline))
        assertNull(flow.cachedAccount())
    }

    @Test
    fun `协议版本描述用于界面展示`() {
        assertEquals("776 (26.2)", McVersion.describe(776))
        assertTrue(McVersion.isSupported(McVersion.DEFAULT_PROTOCOL))
    }
}