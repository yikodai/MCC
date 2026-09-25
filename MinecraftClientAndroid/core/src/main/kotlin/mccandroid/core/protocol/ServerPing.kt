package mccandroid.core.protocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mccandroid.core.chat.ChatParser
import mccandroid.core.util.Json
import mccandroid.core.util.get
import mccandroid.core.util.int
import mccandroid.core.util.str
import java.net.InetSocketAddress
import java.net.Socket

/** 服务器状态（由 Server List Ping 获得） */
data class ServerStatus(
    /** 服务器声明的协议号 */
    val protocol: Int,
    /** 服务器版本名（如 "1.21.11"） */
    val versionName: String,
    val onlinePlayers: Int,
    val maxPlayers: Int,
    /** 去除了颜色代码的 MOTD */
    val motd: String,
    /** 往返延迟（毫秒） */
    val latencyMs: Long,
)

/**
 * 服务器列表 Ping。
 *
 * 端口自 MCC `Protocol18Handler.DoPing()`：
 * 握手（nextState=1）-> 状态请求 -> 读取 JSON 状态响应。
 * 安卓端用它来自动识别服务器版本，进而选择正确的包 ID 表。
 */
object ServerPing {

    private const val STATUS_STATE = 1

    suspend fun ping(address: ServerAddress, timeoutMs: Int = 7000): ServerStatus = withContext(Dispatchers.IO) {
        Socket().use { socket ->
            val start = System.currentTimeMillis()
            socket.tcpNoDelay = true
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(address.host, address.port), timeoutMs)

            val output = socket.getOutputStream()
            val input = socket.getInputStream()

            val handshake = PacketWriter()
                .writeVarInt(0x00)
                .writeVarInt(-1) // 使用 -1 表示“未知版本”，服务端会返回其真实版本信息
                .writeString(address.handshakeHost)
                .writeShort(address.port)
                .writeVarInt(STATUS_STATE)
                .toByteArray()
            output.write(PacketWriter().writeVarInt(handshake.size).writeByteArray(handshake).toByteArray())

            val request = PacketWriter().writeVarInt(0x00).toByteArray()
            output.write(PacketWriter().writeVarInt(request.size).writeByteArray(request).toByteArray())
            output.flush()

            val length = readVarInt(input)
            if (length <= 0) throw ProtocolException("服务器返回了空的响应")
            val payload = readFully(input, length)
            val reader = PacketReader(payload)
            if (reader.readVarInt() != 0x00) {
                throw ProtocolException("未知的响应包类型")
            }
            val json = Json.parseOrNull(reader.readString())
                ?: throw ProtocolException("服务器状态响应不是合法 JSON")

            val version = json["version"]
            val players = json["players"]
            val description = json["description"]

            return@withContext ServerStatus(
                protocol = version.int("protocol") ?: 0,
                versionName = version.str("name") ?: "",
                onlinePlayers = players.int("online") ?: 0,
                maxPlayers = players.int("max") ?: 0,
                motd = if (description != null) ChatParser.toPlainText(description) else "",
                latencyMs = System.currentTimeMillis() - start,
            )
        }
    }

    private fun readVarInt(input: java.io.InputStream): Int {
        var result = 0
        var shift = 0
        while (true) {
            val current = input.read()
            if (current < 0) throw ProtocolException("连接已被服务器关闭")
            result = result or ((current and 0x7F) shl shift)
            if (current and 0x80 == 0) return result
            shift += 7
            if (shift >= 35) throw ProtocolException("VarInt 超长")
        }
    }

    private fun readFully(input: java.io.InputStream, length: Int): ByteArray {
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            if (read < 0) throw ProtocolException("连接已被服务器关闭")
            offset += read
        }
        return buffer
    }
}