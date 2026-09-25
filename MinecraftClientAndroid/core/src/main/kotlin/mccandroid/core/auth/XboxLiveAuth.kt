package mccandroid.core.auth

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mccandroid.core.http.Http
import mccandroid.core.util.Json
import mccandroid.core.util.get
import mccandroid.core.util.intValue
import mccandroid.core.util.str

/**
 * Xbox Live 认证。
 *
 * 端口自 MCC `Protocol/MicrosoftAuthentication.cs` 中的 `XboxLive` 类。
 * 认证链：微软令牌 -> XBL 令牌 -> XSTS 令牌 -> Minecraft 服务令牌。
 */
object XboxLiveAuth {

    private const val XBL_URL = "https://user.auth.xboxlive.com/user/authenticate"
    private const val XSTS_URL = "https://xsts.auth.xboxlive.com/xsts/authorize"

    /** 与 MCC 相同的 User-Agent，Xbox Live 服务对该客户端类型有白名单校验 */
    private const val USER_AGENT =
        "Mozilla/5.0 (XboxReplay; XboxLiveAuth/3.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/71.0.3578.98 Safari/537.36"

    /** XSTS 常见错误码，见 https://wiki.vg/Microsoft_Authentication_Scheme */
    private val xstsErrors = mapOf(
        "2148916227" to "该账号已被 Xbox Live 封禁",
        "2148916229" to "该账号为儿童账号，需要家长同意",
        "2148916233" to "该微软账号没有 Xbox 账号，请先到 xbox.com 创建",
        "2148916235" to "Xbox Live 在你所在的国家或地区不可用",
        "2148916236" to "该账号需要完成成人验证（韩国地区）",
        "2148916237" to "该账号需要完成成人验证（韩国地区）",
        "2148916238" to "该账号是未成年账号，需要由成年家庭成员加入 Family 家庭组后才能使用",
    )

    /**
     * Xbox Live 认证。
     *
     * 注意：使用自有客户端 ID 取得的微软令牌必须加 `d=` 前缀（详见 MCC 注释）。
     */
    fun authenticate(msa: MsaToken): XboxToken {
        val payload = buildJsonObject {
            put("Properties", buildJsonObject {
                put("AuthMethod", JsonPrimitive("RPS"))
                put("SiteName", JsonPrimitive("user.auth.xboxlive.com"))
                put("RpsTicket", JsonPrimitive("d=${msa.accessToken}"))
            })
            put("RelyingParty", JsonPrimitive("http://auth.xboxlive.com"))
            put("TokenType", JsonPrimitive("JWT"))
        }.toString()

        val response = Http.postJson(
            XBL_URL,
            payload,
            headers = mapOf("x-xbl-contract-version" to "0", "Accept" to "application/json", "User-Agent" to USER_AGENT),
        )
        if (!response.isSuccess) {
            throw AuthException("Xbox Live 认证失败（HTTP ${response.statusCode}）")
        }
        return parseXboxToken(response.body, "Xbox Live 认证")
    }

    /**
     * XSTS 授权，换取面向 Minecraft 服务的令牌。
     */
    fun authorize(xbl: XboxToken): XboxToken {
        val payload = buildJsonObject {
            put("Properties", buildJsonObject {
                put("SandboxId", JsonPrimitive("RETAIL"))
                put("UserTokens", buildJsonArray { add(JsonPrimitive(xbl.token)) })
            })
            put("RelyingParty", JsonPrimitive("rp://api.minecraftservices.com/"))
            put("TokenType", JsonPrimitive("JWT"))
        }.toString()

        val response = Http.postJson(
            XSTS_URL,
            payload,
            headers = mapOf("x-xbl-contract-version" to "1", "Accept" to "application/json", "User-Agent" to USER_AGENT),
        )

        if (response.isSuccess) {
            return parseXboxToken(response.body, "XSTS 授权")
        }

        if (response.statusCode == 401) {
            val json = Json.parseOrNull(response.body)
            val xerr = json.str("XErr") ?: json["XErr"].intValue()?.toString()
            if (xerr != null) {
                throw AuthException(xstsErrors[xerr] ?: "XSTS 授权失败，错误码：$xerr")
            }
        }
        throw AuthException("XSTS 授权失败（HTTP ${response.statusCode}）")
    }

    private fun parseXboxToken(body: String, action: String): XboxToken {
        val json = Json.parseOrNull(body) ?: throw AuthException("$action 返回内容无法解析")
        val token = json.str("Token") ?: throw AuthException("$action 响应缺少 Token")
        val claims = json["DisplayClaims"] as? JsonObject
        val xui = claims?.get("xui") as? JsonArray
        val userHash = xui?.firstOrNull()?.let { Json.parseOrNull(it.toString())?.str("uhs") }
            ?: throw AuthException("$action 响应缺少用户哈希（uhs）")
        return XboxToken(token = token, userHash = userHash)
    }
}