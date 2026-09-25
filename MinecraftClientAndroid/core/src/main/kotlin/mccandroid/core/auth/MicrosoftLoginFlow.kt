package mccandroid.core.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * 微软账号登录流程编排。
 *
 * 完整链路（与 MCC 一致）：
 * 1. 设备码流程获取微软令牌（MSA）：[MicrosoftAuth]
 * 2. XBL 认证 -> XSTS 授权：[XboxLiveAuth]
 * 3. 登录 Minecraft 服务 -> 校验游戏所有权 -> 拉取玩家档案：[MinecraftAuth]
 *
 * 所有网络请求都在 IO 线程执行，供安卓端直接在协程里调用。
 */
class MicrosoftLoginFlow(private val storage: AccountStorage? = null) {

    /** 读取上次登录的账号（未登录返回 null） */
    fun cachedAccount(): MccAccount? {
        val json = storage?.load() ?: return null
        return try {
            AccountCodec.decode(json)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 启动设备码登录。
     *
     * @param onDeviceCode 拿到设备码后回调，界面应展示用户码并提示用户去验证网址输入
     * @param onEvent 轮询进度回调
     */
    suspend fun startDeviceCodeLogin(
        onDeviceCode: (DeviceCodeInfo) -> Unit,
        onEvent: (MicrosoftAuth.PollEvent) -> Unit = {},
    ): MccAccount = withContext(Dispatchers.IO) {
        val info = MicrosoftAuth.requestDeviceCode()
        onDeviceCode(info)
        val token = MicrosoftAuth.pollForToken(info, onEvent)
        val account = completeLogin(token)
        persist(account)
        account
    }

    /** 使用刷新令牌静默登录（App 重启后免去再次输码） */
    suspend fun loginWithRefreshToken(refreshToken: String): MccAccount = withContext(Dispatchers.IO) {
        val token = MicrosoftAuth.refreshToken(refreshToken)
        val account = completeLogin(token)
        persist(account)
        account
    }

    /**
     * 确保账号的 Minecraft 访问令牌可用：
     * 令牌未过期直接返回；已过期则用刷新令牌重新走一遍认证链。
     */
    suspend fun ensureFreshAccount(account: MccAccount): MccAccount = withContext(Dispatchers.IO) {
        if (!account.isMicrosoft) return@withContext account
        if (account.accessToken != null && !account.isAccessTokenExpired()) return@withContext account

        val refreshToken = account.refreshToken
            ?: throw AuthException("缺少刷新令牌，需要重新使用微软账号登录")

        val token = MicrosoftAuth.refreshToken(refreshToken)
        val refreshed = completeLogin(token)
        persist(refreshed)
        refreshed
    }

    /** 退出登录，清除本地缓存的账号 */
    fun logout() {
        storage?.clear()
    }

    /**
     * 申请安全聊天密钥，失败时返回 null（不影响正常聊天，只是消息不带签名）。
     */
    suspend fun fetchProfileKeysSafely(account: MccAccount): ProfileKeys? = withContext(Dispatchers.IO) {
        val accessToken = account.accessToken ?: return@withContext null
        try {
            MinecraftAuth.requestProfileKeys(accessToken)
        } catch (_: AuthException) {
            null
        }
    }

    /** 完成微软令牌到 Minecraft 账号的完整认证链 */
    private fun completeLogin(msa: MsaToken): MccAccount {
        val xbl = XboxLiveAuth.authenticate(msa)
        val xsts = XboxLiveAuth.authorize(xbl)
        val (accessToken, expiresIn) = MinecraftAuth.loginWithXbox(xsts)

        if (!MinecraftAuth.hasGameOwnership(accessToken)) {
            throw AuthException("该微软账号未拥有 Minecraft Java 版，无法登录服务器")
        }

        val profile = MinecraftAuth.fetchProfile(accessToken)
        val login = msa.email.ifBlank { profile.name }

        return MccAccount(
            type = AccountType.MICROSOFT,
            login = login,
            username = profile.name,
            uuid = profile.uuid,
            refreshToken = msa.refreshToken,
            accessToken = accessToken,
            accessTokenExpiresAt = Instant.now().plusSeconds(expiresIn.toLong()),
        )
    }

    private fun persist(account: MccAccount) {
        storage?.save(AccountCodec.encode(account))
    }
}