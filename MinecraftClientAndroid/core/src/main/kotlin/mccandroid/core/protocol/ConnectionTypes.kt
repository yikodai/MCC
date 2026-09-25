package mccandroid.core.protocol

/** 连接所处的协议阶段 */
enum class ConnectionState {
    DISCONNECTED,
    /** 正在解析地址 / 建立 TCP 连接 */
    CONNECTING,
    /** 登录阶段（握手、加密、Login Success） */
    LOGGING_IN,
    /** 配置阶段（1.20.2+，服务端下发注册表、数据包列表） */
    CONFIGURING,
    /** 游戏阶段（可以收发聊天） */
    PLAYING,
}

/** 聊天行的类型 */
enum class ChatKind {
    /** 玩家聊天 */
    PLAYER_CHAT,

    /** 系统消息（服务器广播、加入/退出提示等） */
    SYSTEM,

    /** 物品栏上方的动作栏文本 */
    ACTION_BAR,

    /** 客户端本地提示（连接中、已加入等），不由服务器下发 */
    CLIENT,
}

/** 一行聊天内容 */
data class ChatLine(
    /** 带 § 颜色/样式代码的文本 */
    val legacyText: String,
    /** 去掉所有格式代码的纯文本 */
    val plainText: String,
    val kind: ChatKind,
    /** 玩家聊天的发送者显示名（可能为空） */
    val sender: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

/** 客户端信息（对应 MCC 的 MCSettings 配置节） */
data class ClientSettings(
    val locale: String = "zh_CN",
    val viewDistance: Int = 8,
    /** 0=全部, 1=仅指令, 2=隐藏 */
    val chatMode: Int = 0,
    val chatColors: Boolean = true,
    /** 1=左手, 0=右手 */
    val mainHand: Int = 1,
    /** 显示的皮肤层，0x7F 为全部显示 */
    val skinParts: Int = 0x7F,
    /** 客户端品牌，会显示在服务端的 F3 调试信息里 */
    val brand: String = "MCC-Android",
) {
    companion object {
        val DEFAULT = ClientSettings()
    }
}

/**
 * 连接事件回调。
 *
 * 由 [McSession] 实现并转换为 Flow，供安卓界面订阅。
 */
interface ConnectionEvents {
    fun onStateChanged(state: ConnectionState)

    fun onChat(line: ChatLine)

    /** 成功进入游戏阶段（收到 Join Game 包） */
    fun onJoined(protocol: Int, onlineMode: Boolean)

    /** 连接结束；[reason] 为服务器给出的踢出原因，[error] 为本地异常（二者最多一个非空） */
    fun onDisconnected(reason: String?, error: Throwable?)
}

/** 服务器主动断开连接（踢出） */
class ServerDisconnectException(val reason: String) : Exception(reason)