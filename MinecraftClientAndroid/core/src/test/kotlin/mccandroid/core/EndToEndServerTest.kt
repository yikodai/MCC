package mccandroid.core

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import mccandroid.core.auth.OfflineAccounts
import mccandroid.core.protocol.ChatKind
import mccandroid.core.protocol.ChatLine
import mccandroid.core.protocol.ConnectionState
import mccandroid.core.session.ServerEntry
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 针对真实服务端的端到端连通测试。
 *
 * 默认跳过（需要一台可用的 Minecraft 服务器）。启用方式：
 * ```
 * MCC_E2E_HOST=127.0.0.1 MCC_E2E_PORT=25565 ./gradlew :core:test
 * ```
 * 服务端需为离线模式（online-mode=false），测试使用离线账号登录，
 * 校验：进入游戏阶段 -> 聊天消息被服务器回显 -> 指令得到系统回复。
 */
class EndToEndServerTest {

    @Test
    fun `连接真实服务器并收发聊天与指令`() = runBlocking {
        val host = System.getenv("MCC_E2E_HOST")
        assumeTrue(!host.isNullOrBlank(), "未设置 MCC_E2E_HOST，跳过端到端测试")

        val port = System.getenv("MCC_E2E_PORT")?.toIntOrNull() ?: 25565
        val username = System.getenv("MCC_E2E_USER") ?: "MccAndroidE2E"

        val session = McSession()
        val received = mutableListOf<ChatLine>()
        val collector = launch { session.messages.collect { received.add(it) } }

        try {
            val setup = session.connect(
                server = ServerEntry("e2e", host!!, port),
                account = OfflineAccounts.create(username),
                options = SessionOptions(resolveSrv = false, signChat = false),
            )
            println("[E2E] 目标 ${setup.address.display}，协议号 ${setup.protocol}，版本 ${setup.status?.versionName}")
            assertTrue(McVersion.isSupported(setup.protocol), "探测到的协议号应受支持：${setup.protocol}")

            val playing = withTimeoutOrNull(60_000) {
                session.state.first { it == ConnectionState.PLAYING }
            }
            assertNotNull(playing, "60 秒内未进入游戏阶段，最后状态：${session.state.value}\n${received.joinToString("\n")}")

            // 1. 聊天：服务器会把玩家消息广播回所有玩家（包括发送者）
            val chatToken = "mcc-android-e2e-${System.currentTimeMillis()}"
            session.sendChat(chatToken)
            val echoed = withTimeoutOrNull(20_000) {
                session.messages.first { it.kind == ChatKind.PLAYER_CHAT && it.plainText.contains(chatToken) }
            }
            assertNotNull(echoed, "未收到自己发送的聊天消息回显\n${received.joinToString("\n")}")

            // 2. 指令：/list 为权限等级 0，任何玩家都可执行，服务端会返回系统消息
            val commandSentAt = System.currentTimeMillis()
            session.sendChat("/list")
            // 注意：SharedFlow 会向新订阅者重放历史消息，这里必须按「发送指令之后」的时间戳筛选
            val reply = withTimeoutOrNull(20_000) {
                session.messages.first {
                    it.timestamp >= commandSentAt && it.kind != ChatKind.ACTION_BAR && it.kind != ChatKind.CLIENT
                }
            }
            assertNotNull(reply, "执行 /list 后未收到服务端回复\n${received.joinToString("\n")}")
            assertTrue(
                reply.plainText.contains("players", ignoreCase = true),
                "指令回复内容异常：${reply.plainText}",
            )

            println("[E2E] 断言通过，收到 ${received.size} 条消息")
        } finally {
            session.disconnect()
            collector.cancel()
            session.shutdown()
        }
    }

    @Test
    fun `服务器列表 Ping 能获取版本信息`() = runBlocking {
        val host = System.getenv("MCC_E2E_HOST")
        assumeTrue(!host.isNullOrBlank(), "未设置 MCC_E2E_HOST，跳过端到端测试")

        val port = System.getenv("MCC_E2E_PORT")?.toIntOrNull() ?: 25565
        val status = mccandroid.core.protocol.ServerPing.ping(
            mccandroid.core.protocol.ServerAddress(host!!, port),
        )
        println("[E2E] Ping 结果：${status.versionName} (${status.protocol}) 在线 ${status.onlinePlayers}/${status.maxPlayers}")

        assertTrue(status.protocol > 0, "应拿到服务端协议号")
        assertTrue(status.versionName.isNotBlank(), "应拿到版本名")
        assertEquals(status.protocol, McVersion.versionToProtocol(status.versionName).takeIf { it != 0 } ?: status.protocol)
    }
}