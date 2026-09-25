package mccandroid.core.protocol

/**
 * 安卓端使用的包种类枚举。
 *
 * 成员名与 MCC 的 `PacketTypesIn` / `PacketTypesOut` /
 * `ConfigurationPacketTypesIn` / `ConfigurationPacketTypesOut` 枚举保持一致，
 * 这样 [PacketIds.kt] 可以直接从上游 C# 调色板生成。
 */
object PacketIdTables {

    /** 游戏阶段接收包 */
    enum class InPlay {
        KeepAlive,
        Ping,
        Disconnect,
        JoinGame,
        SystemChat,
        ChatMessage,
        ProfilelessChatMessage,
        PluginMessage,
        StartConfiguration,
        TabComplete,
        PlayerInfo,
    }

    /** 游戏阶段发送包 */
    enum class OutPlay {
        KeepAlive,
        Pong,
        ChatMessage,
        ChatCommand,
        SignedChatCommand,
        ClientSettings,
        PluginMessage,
        ClientStatus,
        PlayerSession,
        AcknowledgeConfiguration,
    }

    /** 配置阶段接收包 */
    enum class InConfig {
        Disconnect,
        FinishConfiguration,
        KeepAlive,
        Ping,
        KnownDataPacks,
        CodeOfConduct,
        CookieRequest,
        PluginMessage,
    }

    /** 配置阶段发送包 */
    enum class OutConfig {
        ClientInformation,
        CookieResponse,
        PluginMessage,
        FinishConfiguration,
        KeepAlive,
        Pong,
        KnownDataPacks,
        AcceptCodeOfConduct,
    }
}

/** 单个 Minecraft 版本的包 ID 表 */
interface PacketIdTable {
    val inPlay: Map<PacketIdTables.InPlay, Int>
    val outPlay: Map<PacketIdTables.OutPlay, Int>
    val inConfig: Map<PacketIdTables.InConfig, Int>
    val outConfig: Map<PacketIdTables.OutConfig, Int>

    fun inPlayId(packet: PacketIdTables.InPlay): Int? = inPlay[packet]
    fun outPlayId(packet: PacketIdTables.OutPlay): Int? = outPlay[packet]
    fun inConfigId(packet: PacketIdTables.InConfig): Int? = inConfig[packet]
    fun outConfigId(packet: PacketIdTables.OutConfig): Int? = outConfig[packet]
}

/**
 * 登录阶段（Login）包 ID。
 *
 * 自 1.20.2 引入配置阶段后这些 ID 在 1.20.6 - 26.2 期间保持不变，
 * 因此无需按版本生成，见 MCC `Protocol18Handler` 的登录流程。
 */
object LoginPacketIds {
    // 客户端 -> 服务端
    const val HANDSHAKE = 0x00
    const val LOGIN_START = 0x00
    const val ENCRYPTION_RESPONSE = 0x01
    const val LOGIN_ACKNOWLEDGED = 0x03

    // 服务端 -> 客户端
    const val DISCONNECT = 0x00
    const val ENCRYPTION_REQUEST = 0x01
    const val LOGIN_SUCCESS = 0x02
    const val SET_COMPRESSION = 0x03
    const val LOGIN_PLUGIN_REQUEST = 0x04
    const val LOGIN_COOKIE_REQUEST = 0x05
}