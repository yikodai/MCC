package mccandroid.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mccandroid.core.auth.MccAccount
import mccandroid.core.auth.MicrosoftLoginFlow
import mccandroid.core.auth.ProfileKeys
import mccandroid.core.protocol.ChatKind
import mccandroid.core.protocol.ChatLine
import mccandroid.core.protocol.ClientSettings
import mccandroid.core.protocol.ConnectionEvents
import mccandroid.core.protocol.ConnectionState
import mccandroid.core.protocol.MinecraftConnection
import mccandroid.core.protocol.ProtocolException
import mccandroid.core.protocol.ServerAddress
import mccandroid.core.protocol.ServerPing
import mccandroid.core.protocol.ServerStatus
import mccandroid.core.protocol.SrvResolver
import mccandroid.core.session.ServerEntry
import java.io.IOException

/** 连接参数 */
data class SessionOptions(
    val clientSettings: ClientSettings = ClientSettings.DEFAULT,
    /** 是否对聊天消息签名（需要在线模式 + 成功申请到聊天密钥，实际失败会自动退回未签名） */
    val signChat: Boolean = true,
    /** 是否尝试通过 SRV 记录解析服务器地址 */
    val resolveSrv: Boolean = true,
    /** 强制指定协议号；0 表示通过服务器列表 Ping 自动探测 */
    val protocolOverride: Int = 0,
)

/** 连接准备结果（地址解析、版本探测、令牌刷新完成后返回） */
data class ConnectionSetup(
    /** 可能已刷新过访问令牌的账号，调用方应重新持久化 */
    val account: MccAccount,
    val address: ServerAddress,
    /** 实际使用的协议号 */
    val protocol: Int,
    /** 服务器列表 Ping 得到的版本信息，探测失败时为 null */
    val status: ServerStatus?,
)

/**
 * 一条 Minecraft 会话。
 *
 * 对外暴露 Flow 形式的连接状态与聊天消息，供安卓界面订阅；
 * 内部负责地址解析、版本探测、令牌刷新、聊天密钥申请与协议收发。
 */
class McSession(private val loginFlow: MicrosoftLoginFlow? = null) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<ChatLine>(replay = 200, extraBufferCapacity = 256)
    val messages: SharedFlow<ChatLine> = _messages.asSharedFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var connection: MinecraftConnection? = null
    private var connectionJob: Job? = null

    /** 是否已连接并进入游戏阶段 */
    val isPlaying: Boolean get() = _state.value == ConnectionState.PLAYING

    /**
     * 建立连接。
     *
     * 该方法只负责「准备工作 + 启动收发线程」，返回后可立即通过 [messages] 与 [state] 观察连接；
     * 准备阶段的失败（地址错误、版本不支持、令牌失效等）会直接抛出异常。
     */
    suspend fun connect(
        server: ServerEntry,
        account: MccAccount,
        options: SessionOptions = SessionOptions(),
    ): ConnectionSetup {
        disconnect()
        _lastError.value = null

        var currentAccount = account

        // 1. 令牌刷新（在线账号且令牌过期时）
        if (loginFlow != null && currentAccount.isMicrosoft) {
            currentAccount = loginFlow.ensureFreshAccount(currentAccount)
        }

        // 2. 地址解析（SRV）
        val baseAddress = ServerAddress(server.host, server.port, server.host)
        val address = if (options.resolveSrv) {
            SrvResolver.resolve(server.host) ?: baseAddress
        } else {
            baseAddress
        }

        // 3. 版本探测
        val status = if (options.protocolOverride == 0) {
            try {
                ServerPing.ping(address)
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }

        val protocol = when {
            options.protocolOverride != 0 -> options.protocolOverride
            status != null && McVersion.isSupported(status.protocol) -> status.protocol
            else -> McVersion.DEFAULT_PROTOCOL
        }

        if (!McVersion.isSupported(protocol)) {
            throw ProtocolException(
                "服务器版本不支持：${status?.versionName ?: "未知"}（协议号 $protocol），" +
                    "安卓端目前支持 ${McVersion.protocolToVersion(McVersion.MIN_PROTOCOL)} - " +
                    McVersion.protocolToVersion(McVersion.MAX_PROTOCOL)
            )
        }

        // 4. 聊天密钥（安全聊天签名用，失败不影响连接）
        val profileKeys: ProfileKeys? = if (options.signChat && currentAccount.isMicrosoft && loginFlow != null) {
            loginFlow.fetchProfileKeysSafely(currentAccount)
        } else {
            null
        }

        // 5. 启动协议收发
        val events = FlowConnectionEvents()
        val session = MinecraftConnection(
            address = address,
            account = currentAccount,
            protocol = protocol,
            profileKeys = profileKeys,
            settings = options.clientSettings,
            signChat = options.signChat,
            events = events,
        )
        connection = session

        // 同步置为「连接中」，避免调用方在跳转界面时看到过期的 DISCONNECTED 状态
        _state.value = ConnectionState.CONNECTING

        connectionJob = scope.launch {
            withContext(Dispatchers.IO) {
                session.run()
            }
        }

        return ConnectionSetup(
            account = currentAccount,
            address = address,
            protocol = protocol,
            status = status,
        )
    }

    /** 发送聊天内容或指令（以 `/` 开头视为指令） */
    fun sendChat(text: String) {
        val session = connection ?: throw ProtocolException("尚未连接到服务器")
        if (_state.value != ConnectionState.PLAYING) {
            throw ProtocolException("尚未进入游戏阶段，无法发送消息")
        }
        session.sendChatMessage(text)
    }

    /** 断开连接（幂等） */
    fun disconnect() {
        connection?.close()
        connectionJob?.cancel()
        connection = null
        connectionJob = null
        _state.value = ConnectionState.DISCONNECTED
    }

    /** 释放会话资源（App 退出时调用） */
    fun shutdown() {
        disconnect()
        scope.cancel()
    }

    /** 把连接事件转成 Flow 事件 */
    private inner class FlowConnectionEvents : ConnectionEvents {

        override fun onStateChanged(state: ConnectionState) {
            _state.value = state
        }

        override fun onChat(line: ChatLine) {
            _messages.tryEmit(line)
        }

        override fun onJoined(protocol: Int, onlineMode: Boolean) {
            _messages.tryEmit(
                ChatLine(
                    legacyText = "§a已加入服务器§r（${McVersion.describe(protocol)}，${if (onlineMode) "正版验证" else "离线模式"}）",
                    plainText = "已加入服务器（${McVersion.describe(protocol)}，${if (onlineMode) "正版验证" else "离线模式"}）",
                    kind = ChatKind.CLIENT,
                ),
            )
        }

        override fun onDisconnected(reason: String?, error: Throwable?) {
            val text = when {
                reason != null -> "与服务器断开连接：$reason"
                error is IOException -> "连接中断：${error.message ?: error::class.simpleName}"
                error != null -> "连接失败：${error.message ?: error::class.simpleName}"
                else -> "已断开连接"
            }
            _lastError.value = text
            _messages.tryEmit(ChatLine("§c$text", text, ChatKind.CLIENT))
            _state.value = ConnectionState.DISCONNECTED
        }
    }
}