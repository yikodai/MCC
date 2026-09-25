package mccandroid.core.auth

import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

/** 账号类型 */
enum class AccountType {
    /** 微软正版账号 */
    MICROSOFT,

    /** 离线（盗版）账号，仅能连接离线模式服务器 */
    OFFLINE,
}

/** 认证过程中的可预期失败，消息为面向用户的中文提示 */
class AuthException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 微软 OAuth 设备码信息，用于在界面上展示“请输入该代码” */
data class DeviceCodeInfo(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSeconds: Int,
    val intervalSeconds: Int,
    val message: String,
) {
    /** 设备码有效期 */
    val expiresAt: Instant = Instant.now().plusSeconds(expiresInSeconds.toLong())
}

/** 微软账号令牌（MSA） */
data class MsaToken(
    val email: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Int,
)

/** Xbox Live / XSTS 令牌 */
data class XboxToken(
    val token: String,
    val userHash: String,
)

/** Minecraft 档案（正版用户名与 UUID） */
data class MinecraftProfile(
    val uuid: UUID,
    val name: String,
)

/**
 * 登录得到的账号。
 *
 * 与 MCC 的 `SessionToken` 对应：保存了微软刷新令牌、Minecraft 访问令牌
 * 以及用于安全聊天的档案密钥。
 */
data class MccAccount(
    val type: AccountType,
    /** 登录名：微软账号为邮箱，离线账号为用户名 */
    val login: String,
    /** 游戏内用户名 */
    val username: String,
    val uuid: UUID,
    /** 微软刷新令牌（离线账号为 null） */
    val refreshToken: String? = null,
    /** Minecraft 服务访问令牌（离线账号为 null） */
    val accessToken: String? = null,
    /** Minecraft 访问令牌过期时间 */
    val accessTokenExpiresAt: Instant? = null,
) {
    val isMicrosoft: Boolean get() = type == AccountType.MICROSOFT

    val uuidString: String get() = uuid.toString()

    /** Minecraft 访问令牌是否已过期（含 60 秒安全余量） */
    fun isAccessTokenExpired(now: Instant = Instant.now()): Boolean {
        val expiresAt = accessTokenExpiresAt ?: return false
        return now.isAfter(expiresAt.minusSeconds(60))
    }
}

/**
 * 可用于持久化的账号记录（字段全部为可序列化的基础类型）。
 *
 * 由 [MccAccount.toRecord] / [AccountRecord.toAccount] 与运行时模型互转，
 * 具体存储方式（加密存储、数据库）由安卓端实现。
 */
@Serializable
data class AccountRecord(
    val type: String,
    val login: String,
    val username: String,
    val uuid: String,
    val refreshToken: String? = null,
    val accessToken: String? = null,
    val accessTokenExpiresAtEpochMilli: Long? = null,
) {
    fun toAccount(): MccAccount = MccAccount(
        type = if (type == AccountType.MICROSOFT.name) AccountType.MICROSOFT else AccountType.OFFLINE,
        login = login,
        username = username,
        uuid = UUID.fromString(uuid),
        refreshToken = refreshToken,
        accessToken = accessToken,
        accessTokenExpiresAt = accessTokenExpiresAtEpochMilli?.let(Instant::ofEpochMilli),
    )
}

fun MccAccount.toRecord(): AccountRecord = AccountRecord(
    type = type.name,
    login = login,
    username = username,
    uuid = uuid.toString(),
    refreshToken = refreshToken,
    accessToken = accessToken,
    accessTokenExpiresAtEpochMilli = accessTokenExpiresAt?.toEpochMilli(),
)

/** 离线账号工具 */
object OfflineAccounts {

    /**
     * 生成离线模式玩家的 UUID。
     *
     * 与 Minecraft 服务端离线模式使用的算法一致（UUID v3，基于
     * `OfflinePlayer:<用户名>`），这样同一用户名在服务器上的玩家数据保持稳定。
     */
    fun offlineUuid(username: String): UUID = UUID.nameUUIDFromBytes("OfflinePlayer:$username".toByteArray(Charsets.UTF_8))

    /** 构造离线账号 */
    fun create(username: String): MccAccount = MccAccount(
        type = AccountType.OFFLINE,
        login = username,
        username = username,
        uuid = offlineUuid(username),
    )
}