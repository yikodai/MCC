package com.mccteam.android.mcc

import android.app.Application
import android.content.Context
import mccandroid.core.McSession
import mccandroid.core.auth.AccountStorage
import mccandroid.core.auth.MicrosoftLoginFlow
import mccandroid.core.session.ServerEntry
import mccandroid.core.session.ServerEntryStore
import mccandroid.core.session.ServerEntries
import com.mccteam.android.mcc.data.AccountStoreImpl
import com.mccteam.android.mcc.data.ServerStoreImpl

/**
 * 应用级单例。
 *
 * [session] 持有当前连接，前台服务只负责保活与通知，界面订阅它的 Flow，
 * 因此界面被销毁/重建不会影响连接。
 */
class MccApplication : Application() {

    lateinit var accountStore: AccountStorage
        private set

    lateinit var serverStore: ServerEntryStore
        private set

    lateinit var loginFlow: MicrosoftLoginFlow
        private set

    lateinit var session: McSession
        private set

    override fun onCreate() {
        super.onCreate()
        accountStore = AccountStoreImpl(this)
        serverStore = ServerStoreImpl(this)
        loginFlow = MicrosoftLoginFlow(accountStore)
        session = McSession(loginFlow)
    }

    /** 读取服务器列表 */
    fun loadServers(): MutableList<ServerEntry> =
        ServerEntries.decode(serverStore.load() ?: "").toMutableList()

    /** 保存服务器列表 */
    fun saveServers(servers: List<ServerEntry>) {
        serverStore.save(ServerEntries.encode(servers))
    }

    override fun onTerminate() {
        session.shutdown()
        super.onTerminate()
    }

    companion object {
        fun from(context: Context): MccApplication = context.applicationContext as MccApplication
    }
}