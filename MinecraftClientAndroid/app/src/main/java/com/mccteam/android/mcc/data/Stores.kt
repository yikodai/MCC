package com.mccteam.android.mcc.data

import android.content.Context
import mccandroid.core.auth.AccountStorage
import mccandroid.core.session.ServerEntryStore

/** 账号存储：内容经 [SecureStore]（Android Keystore + AES-GCM）加密后落盘 */
internal class AccountStoreImpl(context: Context) : AccountStorage {

    private val store = SecureStore(context, PREF_FILE)

    override fun load(): String? = store.get(KEY_ACCOUNT)

    override fun save(json: String) = store.put(KEY_ACCOUNT, json)

    override fun clear() = store.remove(KEY_ACCOUNT)

    private companion object {
        const val PREF_FILE = "mcc_account"
        const val KEY_ACCOUNT = "account"
    }
}

/** 服务器列表存储：非敏感数据，明文 JSON 存于 SharedPreferences */
internal class ServerStoreImpl(context: Context) : ServerEntryStore {

    private val prefs = context.applicationContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    override fun load(): String? = prefs.getString(KEY_SERVERS, null)

    override fun save(json: String) {
        prefs.edit().putString(KEY_SERVERS, json).apply()
    }

    private companion object {
        const val PREF_FILE = "mcc_servers"
        const val KEY_SERVERS = "servers"
    }
}