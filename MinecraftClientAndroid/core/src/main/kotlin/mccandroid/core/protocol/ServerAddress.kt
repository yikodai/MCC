package mccandroid.core.protocol

import kotlinx.serialization.json.JsonObject
import mccandroid.core.http.Http
import mccandroid.core.util.Json
import mccandroid.core.util.get
import mccandroid.core.util.int
import mccandroid.core.util.str

/** 服务器地址 */
data class ServerAddress(
    /** 实际连接的主机名或 IP（SRV 记录解析后的目标） */
    val host: String,
    /** 实际连接的端口 */
    val port: Int,
    /** 握手包中填写的地址（保持用户输入的原域名，部分服务器会校验） */
    val handshakeHost: String = host,
) {
    val display: String get() = "$host:$port"

    companion object {
        const val DEFAULT_PORT = 25565
        const val SRV_PREFIX = "_minecraft._tcp."

        /**
         * 解析用户输入的地址。
         *
         * 支持 `example.com`、`example.com:25566`、`[::1]:25565`。
         */
        fun parse(input: String, defaultPort: Int = DEFAULT_PORT): ServerAddress {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) {
                throw ProtocolException("服务器地址不能为空")
            }

            if (trimmed.startsWith("[")) {
                val end = trimmed.indexOf(']')
                if (end < 0) throw ProtocolException("IPv6 地址格式错误：$trimmed")
                val host = trimmed.substring(1, end)
                val portPart = trimmed.substring(end + 1).removePrefix(":")
                val port = if (portPart.isEmpty()) defaultPort else portPart.toIntOrNull()
                    ?: throw ProtocolException("端口格式错误：$portPart")
                return ServerAddress(host, port, host)
            }

            val separatorIndex = trimmed.lastIndexOf(':')
            // 只有单个冒号才视为端口分隔符，避免把 IPv6 地址误判
            if (separatorIndex > 0 && trimmed.indexOf(':') == separatorIndex) {
                val host = trimmed.substring(0, separatorIndex)
                val port = trimmed.substring(separatorIndex + 1).toIntOrNull()
                    ?: throw ProtocolException("端口格式错误：${trimmed.substring(separatorIndex + 1)}")
                return ServerAddress(host, port, host)
            }

            return ServerAddress(trimmed, defaultPort, trimmed)
        }
    }
}

/**
 * 通过 DNS over HTTPS 查询 Minecraft 的 SRV 记录。
 *
 * 桌面版 MCC 使用 DnsClient 做系统 DNS 查询；安卓端没有可直接使用的 SRV 查询 API，
 * 因此改用 DoH（Cloudflare / Google 的 JSON 接口）。查询失败一律返回 null，
 * 调用方回退到「主机 + 默认端口」。
 */
object SrvResolver {

    private val endpoints = listOf(
        "https://cloudflare-dns.com/dns-query?name=%s&type=SRV",
        "https://dns.google/resolve?name=%s&type=SRV",
    )

    suspend fun resolve(host: String): ServerAddress? {
        if (host.isBlank()) return null
        val queryName = java.net.URLEncoder.encode(ServerAddress.SRV_PREFIX + host, "UTF-8")

        for (endpoint in endpoints) {
            val url = String.format(endpoint, queryName)
            val response = try {
                Http.get(url, headers = mapOf("Accept" to "application/dns-json"), timeoutMs = 5000)
            } catch (_: Exception) {
                continue
            }
            if (!response.isSuccess) continue

            val json = Json.parseOrNull(response.body) ?: continue
            val answers = (json["Answer"] as? kotlinx.serialization.json.JsonArray) ?: continue
            val record = answers
                .mapNotNull { it as? JsonObject }
                .firstOrNull { it.int("type") == 33 }
                ?: continue

            val recordData = record.str("data") ?: continue

            // SRV 数据格式："优先级 权重 端口 目标主机."
            val parts = recordData.trim().split(Regex("\\s+"))
            if (parts.size < 4) continue
            val port = parts[2].toIntOrNull() ?: continue
            val target = parts[3].trimEnd('.')
            if (target.isEmpty()) continue

            return ServerAddress(target, port, handshakeHost = host)
        }

        return null
    }
}