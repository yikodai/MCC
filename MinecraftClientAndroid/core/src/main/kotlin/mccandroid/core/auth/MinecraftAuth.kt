package mccandroid.core.auth

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mccandroid.core.http.Http
import mccandroid.core.util.Json
import mccandroid.core.util.get
import mccandroid.core.util.str
import java.util.UUID

/**
 * Minecraft 服务认证。
 *
 * 端口自 MCC `Protocol/MicrosoftAuthentication.cs` 中的 `MinecraftWithXbox` 类，
 * 以及 `ProtocolHandler.SessionCheck()`（在线模式加入服务器）与
 * `Protocol/ProfileKey/KeyUtils.GetNewProfileKeys()`（安全聊天档案密钥）。
 */
object MinecraftAuth {

    private const val LOGIN_WITH_XBOX_URL = "https://api.minecraftservices.com/authentication/login_with_xbox"
    private const val ENTITLEMENTS_URL = "https://api.minecraftservices.com/entitlements/mcstore"
    private const val PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile"
    private const val CERTIFICATES_URL = "https://api.minecraftservices.com/player/certificates"
    private const val JOIN_SERVER_URL = "https://sessionserver.mojang.com/session/minecraft/join"

    /** 使用 XSTS 令牌登录 Minecraft 服务 */
    fun loginWithXbox(xsts: XboxToken): Pair<String, Int> {
        val payload = buildJsonObject {
            put("identityToken", "XBL3.0 x=${xsts.userHash};${xsts.token}")
        }.toString()

        val response = Http.postJson(LOGIN_WITH_XBOX_URL, payload, headers = mapOf("Accept" to "application/json"))
        val json = Json.parseOrNull(response.body) ?: throw AuthException("Minecraft 服务登录返回异常（HTTP ${response.statusCode}）")
        val accessToken = json.str("access_token")
            ?: throw AuthException("Minecraft 服务登录失败：${json.str("errorMessage") ?: "HTTP ${response.statusCode}"}")
        val expiresIn = json.str("expires_in")?.toIntOrNull() ?: 86400
        return accessToken to expiresIn
    }

    /** 检查账号是否拥有 Minecraft（Java 版） */
    fun hasGameOwnership(accessToken: String): Boolean {
        val response = Http.get(ENTITLEMENTS_URL, headers = authHeader(accessToken))
        if (!response.isSuccess) {
            throw AuthException("查询游戏所有权失败（HTTP ${response.statusCode}）")
        }
        val json = Json.parseOrNull(response.body) ?: return false
        return json["items"]?.let { element ->
            element is kotlinx.serialization.json.JsonArray && element.isNotEmpty()
        } ?: false
    }

    /** 获取玩家档案（正版用户名与 UUID） */
    fun fetchProfile(accessToken: String): MinecraftProfile {
        val response = Http.get(PROFILE_URL, headers = authHeader(accessToken))
        val json = Json.parseOrNull(response.body) ?: throw AuthException("获取 Minecraft 档案失败（HTTP ${response.statusCode}）")
        val id = json.str("id") ?: throw AuthException("获取 Minecraft 档案失败：账号可能未拥有游戏")
        val name = json.str("name") ?: throw AuthException("获取 Minecraft 档案失败：缺少用户名")
        return MinecraftProfile(uuid = parseUndashedUuid(id), name = name)
    }

    /**
     * 在线模式加入服务器前的会话校验（sessionserver join）。
     *
     * @param serverHash [mccandroid.core.protocol.CryptoUtils.serverHash] 计算出的服务端哈希
     */
    fun joinServer(accessToken: String, uuid: UUID, serverHash: String): Boolean {
        val payload = buildJsonObject {
            put("accessToken", accessToken)
            put("selectedProfile", uuid.toString().replace("-", ""))
            put("serverId", serverHash)
        }.toString()

        val response = Http.postJson(JOIN_SERVER_URL, payload)
        return response.isSuccess
    }

    /**
     * 申请安全聊天所需的档案密钥。
     *
     * 1.20.6+ 的在线模式服务器若开启了「强制安全聊天」，未签名消息会被拒绝，
     * 因此需要向该接口申请密钥对并在游戏内发送聊天会话更新。
     */
    fun requestProfileKeys(accessToken: String): ProfileKeys {
        val response = Http.post(CERTIFICATES_URL, "application/json", "", headers = authHeader(accessToken))
        if (!response.isSuccess) {
            throw AuthException("申请聊天密钥失败（HTTP ${response.statusCode}）")
        }
        val json = Json.parseOrNull(response.body) ?: throw AuthException("申请聊天密钥失败：响应无法解析")

        val keyPair = json["keyPair"] ?: throw AuthException("申请聊天密钥失败：响应缺少 keyPair")
        val publicKeyPem = keyPair.str("publicKey") ?: throw AuthException("申请聊天密钥失败：缺少公钥")
        val privateKeyPem = keyPair.str("privateKey") ?: throw AuthException("申请聊天密钥失败：缺少私钥")
        val signature = json.str("publicKeySignature") ?: throw AuthException("申请聊天密钥失败：缺少签名")
        val signatureV2 = json.str("publicKeySignatureV2") ?: throw AuthException("申请聊天密钥失败：缺少 V2 签名")
        val expiresAt = json.str("expiresAt") ?: throw AuthException("申请聊天密钥失败：缺少过期时间")
        val refreshedAfter = json.str("refreshedAfter") ?: expiresAt

        return ProfileKeys(
            publicKeyDer = ProfileKeys.decodePem(publicKeyPem),
            privateKeyPkcs8 = ProfileKeys.decodePem(privateKeyPem),
            publicKeySignature = ProfileKeys.decodeBase64(signature),
            publicKeySignatureV2 = ProfileKeys.decodeBase64(signatureV2),
            expiresAt = ProfileKeys.parseInstant(expiresAt),
            refreshedAfter = ProfileKeys.parseInstant(refreshedAfter),
        )
    }

    private fun authHeader(accessToken: String): Map<String, String> =
        mapOf("Authorization" to "Bearer $accessToken", "Accept" to "application/json")

    /** 把无连字符的 32 位 UUID 字符串转成 UUID */
    fun parseUndashedUuid(value: String): UUID = if (value.contains('-')) {
        UUID.fromString(value)
    } else {
        val standard = buildString(36) {
            append(value, 0, 8); append('-')
            append(value, 8, 12); append('-')
            append(value, 12, 16); append('-')
            append(value, 16, 20); append('-')
            append(value, 20, 32)
        }
        UUID.fromString(standard)
    }
}