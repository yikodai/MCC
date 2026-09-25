package com.mccteam.android.mcc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import mccandroid.core.protocol.ConnectionState

/**
 * 连接保活的前台服务。
 *
 * 连接本身由 [MccApplication.session] 持有，这里的作用是：
 * 1. 让进程在后台存活，避免切后台后连接被系统回收；
 * 2. 通过常驻通知展示连接状态，并提供「断开」入口。
 */
class MccConnectionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                MccApplication.from(this).session.disconnect()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val serverName = intent?.getStringExtra(EXTRA_SERVER_NAME).orEmpty()
        startForegroundCompat(serverName)
        observeSession(serverName)
        return START_STICKY
    }

    private fun startForegroundCompat(serverName: String) {
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(serverName, getString(R.string.console_state_connecting, serverName)),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
    }

    private fun observeSession(serverName: String) {
        if (observeJob != null) return
        observeJob = scope.launch {
            MccApplication.from(this@MccConnectionService).session.state.collectLatest { state ->
                if (state == ConnectionState.DISCONNECTED) {
                    stopSelf()
                    return@collectLatest
                }
                val text = when (state) {
                    ConnectionState.CONNECTING -> getString(R.string.console_state_connecting, serverName)
                    ConnectionState.LOGGING_IN -> getString(R.string.console_state_connecting, serverName)
                    ConnectionState.CONFIGURING -> getString(R.string.console_state_connecting, serverName)
                    ConnectionState.PLAYING -> getString(R.string.console_notification_title, serverName)
                    ConnectionState.DISCONNECTED -> ""
                }
                notify(buildNotification(serverName, text))
            }
        }
    }

    private fun buildNotification(serverName: String, text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, ConsoleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val disconnectIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, MccConnectionService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(getString(R.string.console_notification_title, serverName))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.action_disconnect), disconnectIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun notify(notification: Notification) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // 用户关闭了通知权限，连接仍然保持
        }
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.console_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "mcc_connection"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_DISCONNECT = "com.mccteam.android.mcc.DISCONNECT"
        const val EXTRA_SERVER_NAME = "server_name"

        /** 启动保活服务 */
        fun start(context: Context, serverName: String) {
            val intent = Intent(context, MccConnectionService::class.java)
                .putExtra(EXTRA_SERVER_NAME, serverName)
            context.startForegroundService(intent)
        }

        /** 停止保活服务 */
        fun stop(context: Context) {
            context.stopService(Intent(context, MccConnectionService::class.java))
        }
    }
}