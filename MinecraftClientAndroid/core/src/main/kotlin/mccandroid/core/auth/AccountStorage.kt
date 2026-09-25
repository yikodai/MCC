package mccandroid.core.auth

import kotlinx.serialization.encodeToString
import mccandroid.core.util.Json

/**
 * 账号持久化接口。
 *
 * 核心库只负责序列化格式，真正的存储（如 Android 的加密 SharedPreferences）由 App 实现。
 */
interface AccountStorage {
    fun load(): String?
    fun save(json: String)
    fun clear()
}

/** 账号 JSON 编解码，字段与 [AccountRecord] 一一对应 */
object AccountCodec {

    fun encode(account: MccAccount): String = Json.INSTANCE.encodeToString(account.toRecord())

    fun decode(json: String): MccAccount = Json.INSTANCE.decodeFromString<AccountRecord>(json).toAccount()
}