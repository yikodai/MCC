package mccandroid.core.session

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import mccandroid.core.protocol.ServerAddress

/**
 * 服务器列表中的一条记录。
 *
 * 由 App 负责持久化（[ServerEntryStore]），核心库只定义数据结构与 JSON 编解码。
 */
@Serializable
data class ServerEntry(
    val name: String,
    val host: String,
    val port: Int = ServerAddress.DEFAULT_PORT,
) {
    val displayAddress: String
        get() = if (port == ServerAddress.DEFAULT_PORT) host else "$host:$port"

    /** 转换为连接用的地址对象 */
    fun toAddress(): ServerAddress = ServerAddress(host, port, handshakeHost = host)
}

/** 服务器列表存储接口 */
interface ServerEntryStore {
    fun load(): String?
    fun save(json: String)
}

/** 服务器列表 JSON 编解码 */
object ServerEntries {

    fun encode(entries: List<ServerEntry>): String =
        mccandroid.core.util.Json.INSTANCE.encodeToString(entries)

    fun decode(json: String): List<ServerEntry> = try {
        mccandroid.core.util.Json.INSTANCE.decodeFromString<List<ServerEntry>>(json)
    } catch (_: Exception) {
        emptyList()
    }
}