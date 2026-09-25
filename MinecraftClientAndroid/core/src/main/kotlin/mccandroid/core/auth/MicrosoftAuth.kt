package mccandroid.core.auth

import mccandroid.core.http.Http
import mccandroid.core.util.Json
import mccandroid.core.util.get
import mccandroid.core.util.str
import java.util.Base64

/**
 * 微软账号登录（OAuth 2.0）。
 *
 * 端口自 MCC `Protocol/MicrosoftAuthentication.cs` 的 `Microsoft` 类。
 * 安卓端只保留设备码流程（Device Code Flow）：无需内嵌浏览器即可完成授权，
 * 也避免了在 App 里保存回调地址与自定义 URL Scheme。
 *
 * 客户端 ID 沿用 MCC 的公开客户端 ID（PKCE / 公开客户端，允许设备码流程）。
 */
object MicrosoftAuth {

    /** MCC 使用的公开客户端 ID */
    const val CLIENT_ID = "54473e32-df8f-42e9-a649-9419b0dab9d3"

    /** 与 MCC 保持一致的授权范围 */
    private const val SCOPE = "XboxLive.signin offline_access openid email"

    private const val TOKEN_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token"
    private const val DEVICE_CODE_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode"

    /** 用户完成授权后跳转的页面，与 MCC 保持一致；设备码流程下仅用于刷新令牌请求 */
    private const val REDIRECT_URI = "https://mccteam.github.io/redirect.html"

    /** slow_down 时每次增加的轮询间隔（秒），见 OAuth 2.0 设备码规范 */
    private const val SLOW_DOWN_INCREMENT_SECONDS = 5

    /** 设备码轮询过程中的事件 */
    sealed interface PollEvent {
        /** 用户尚未完成授权，继续等待 */
        data object Waiting : PollEvent

        /** 服务端要求降低轮询频率 */
        data class SlowDown(val newIntervalSeconds: Int) : PollEvent
    }

    /**
     * 申请设备码。返回的信息需要展示给用户（用户码 + 验证网址）。
     */
    fun requestDeviceCode(): DeviceCodeInfo {
        val body = "client_id=$CLIENT_ID&scope=${SCOPE.replace(" ", "%20")}"
        val response = Http.postForm(DEVICE_CODE_URL, body)
        val json = Json.parseOrNull(response.body)
            ?: throw AuthException("微软设备码接口返回异常（HTTP ${response.statusCode}）")

        if (json["error"] != null) {
            throw AuthException(json.str("error_description") ?: json.str("error") ?: "申请设备码失败")
        }

        return DeviceCodeInfo(
            deviceCode = json.str("device_code") ?: throw AuthException("设备码响应缺少 device_code"),
            userCode = json.str("user_code") ?: throw AuthException("设备码响应缺少 user_code"),
            verificationUri = json.str("verification_uri") ?: "https://microsoft.com/link",
            expiresInSeconds = json.str("expires_in")?.toIntOrNull() ?: 900,
            intervalSeconds = json.str("interval")?.toIntOrNull() ?: 5,
            message = json.str("message") ?: "",
        )
    }

    /**
     * 轮询令牌接口直到用户完成授权。
     *
     * @param info 由 [requestDeviceCode] 获得
     * @param onEvent 轮询进度回调，可用于刷新界面倒计时
     * @return 微软令牌（含刷新令牌）
     */
    suspend fun pollForToken(
        info: DeviceCodeInfo,
        onEvent: (PollEvent) -> Unit = {},
    ): MsaToken {
        val body = "client_id=$CLIENT_ID&grant_type=urn:ietf:params:oauth:grant-type:device_code&device_code=${info.deviceCode}"
        var interval = maxOf(info.intervalSeconds, 1)
        val deadline = System.currentTimeMillis() + info.expiresInSeconds * 1000L

        while (System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(interval * 1000L)
            onEvent(PollEvent.Waiting)

            val response = Http.postForm(TOKEN_URL, body)
            val json = Json.parseOrNull(response.body)
                ?: throw AuthException("微软令牌接口返回异常（HTTP ${response.statusCode}）")

            when (val error = json.str("error")) {
                null -> return parseTokenResponse(json)
                "authorization_pending" -> continue
                "slow_down" -> {
                    interval += SLOW_DOWN_INCREMENT_SECONDS
                    onEvent(PollEvent.SlowDown(interval))
                    continue
                }
                "expired_token" -> throw AuthException("设备码已过期，请重新获取并尽快完成登录")
                "authorization_declined" -> throw AuthException("授权已被取消")
                "bad_verification_code" -> throw AuthException("设备码无效，请重新获取")
                else -> throw AuthException(json.str("error_description") ?: "登录失败：$error")
            }
        }

        throw AuthException("设备码登录超时，请重新获取设备码")
    }

    /**
     * 使用刷新令牌换取新的微软令牌（离线账号不适用）。
     */
    fun refreshToken(refreshToken: String): MsaToken {
        val body = "client_id=$CLIENT_ID&grant_type=refresh_token" +
            "&redirect_uri=${REDIRECT_URI.replace(":", "%3A").replace("/", "%2F")}" +
            "&refresh_token=$refreshToken"
        val response = Http.postForm(TOKEN_URL, body)
        val json = Json.parseOrNull(response.body)
            ?: throw AuthException("刷新令牌失败（HTTP ${response.statusCode}）")

        if (json["error"] != null) {
            val error = json.str("error")
            val description = json.str("error_description") ?: error
            if (error == "invalid_grant") {
                throw AuthException("登录状态已失效，需要重新使用微软账号登录")
            }
            throw AuthException(description ?: "刷新令牌失败")
        }

        return parseTokenResponse(json)
    }

    private fun parseTokenResponse(json: kotlinx.serialization.json.JsonElement): MsaToken {
        val accessToken = json.str("access_token") ?: throw AuthException("令牌响应缺少 access_token")
        val refresh = json.str("refresh_token") ?: throw AuthException("令牌响应缺少 refresh_token")
        val expiresIn = json.str("expires_in")?.toIntOrNull() ?: 3600
        val idToken = json.str("id_token")
        return MsaToken(
            email = idToken?.let(::extractEmailFromIdToken) ?: "",
            accessToken = accessToken,
            refreshToken = refresh,
            expiresInSeconds = expiresIn,
        )
    }

    /**
     * 从 id_token（JWT）中读取邮箱，字段缺失时返回空串。
     */
    fun extractEmailFromIdToken(idToken: String): String {
        val parts = idToken.split('.')
        if (parts.size < 2) return ""
        val payload = try {
            val decoded = Base64.getUrlDecoder().decode(padBase64(parts[1]))
            String(decoded, Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return ""
        }
        val json = Json.parseOrNull(payload) ?: return ""
        return json.str("email") ?: json.str("preferred_username") ?: ""
    }

    private fun padBase64(value: String): String = when (value.length % 4) {
        2 -> "$value=="
        3 -> "$value="
        else -> value
    }
}