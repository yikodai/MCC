package com.mccteam.android.mcc

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mccteam.android.mcc.databinding.ActivityMainBinding
import com.mccteam.android.mcc.databinding.DialogMsLoginBinding
import com.mccteam.android.mcc.databinding.DialogOfflineLoginBinding
import com.mccteam.android.mcc.databinding.DialogServerEditBinding
import com.mccteam.android.mcc.ui.ServerListAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mccandroid.core.auth.AccountCodec
import mccandroid.core.auth.MccAccount
import mccandroid.core.auth.MicrosoftAuth
import mccandroid.core.auth.OfflineAccounts
import mccandroid.core.protocol.ServerAddress
import mccandroid.core.session.ServerEntry

/**
 * 启动页。
 *
 * 负责三件事：账号登录（微软 / 离线）、服务器列表管理（增删改 + 单选）、发起连接。
 * 连接建立后跳转 [ConsoleActivity]，由 [MccConnectionService] 在后台保活。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** 服务器列表（单次选中由 [selectedIndex] 记录） */
    private val servers = mutableListOf<ServerEntry>()
    private var selectedIndex = NO_SELECTION

    /** 当前账号，onCreate 时读取一次，登录成功后再刷新 */
    private var account: MccAccount? = null

    private var isConnecting = false

    /** 微软登录的设备码信息，供「复制」与「打开浏览器」使用 */
    private var deviceCode: String? = null
    private var verificationUri: String? = null
    private var msLoginJob: Job? = null

    private val adapter by lazy {
        ServerListAdapter(
            onSelect = ::selectServer,
            onLongPress = ::showServerActions,
        )
    }

    private val app: MccApplication get() = MccApplication.from(this)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // 用户拒绝也不影响使用，无需额外处理
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        account = app.loginFlow.cachedAccount()
        servers.addAll(app.loadServers())
        selectedIndex = savedInstanceState?.getInt(STATE_SELECTED_INDEX, NO_SELECTION) ?: NO_SELECTION

        binding.serverList.layoutManager = LinearLayoutManager(this)
        binding.serverList.adapter = adapter

        binding.addServerButton.setOnClickListener { showServerEditDialog(null) }
        binding.connectButton.setOnClickListener { connect() }
        binding.loginMicrosoftButton.setOnClickListener { showMicrosoftLoginDialog() }
        binding.loginOfflineButton.setOnClickListener { showOfflineLoginDialog() }
        binding.logoutButton.setOnClickListener { logout() }

        refreshAccountCard()
        refreshServers()
        requestNotificationPermission()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_SELECTED_INDEX, selectedIndex)
    }

    // region 账号

    /** 刷新账号卡片：未登录显示占位文案，离线账号同样视为已登录 */
    private fun refreshAccountCard() {
        val current = account
        binding.accountName.text = when {
            current == null -> getString(R.string.account_none)
            current.isMicrosoft -> getString(R.string.account_display_microsoft, current.username)
            else -> getString(R.string.account_display_offline, current.username)
        }
        binding.logoutButton.isVisible = current != null
    }

    private fun logout() {
        app.loginFlow.logout()
        account = null
        refreshAccountCard()
    }

    /** 离线登录：校验用户名后本地派生账号并保存 */
    private fun showOfflineLoginDialog() {
        val dialogBinding = DialogOfflineLoginBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_login_offline_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.action_save, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val username = dialogBinding.offlineUsernameInput.text.toString().trim()
                if (!OFFLINE_USERNAME_PATTERN.matches(username)) {
                    Toast.makeText(this, R.string.offline_username_invalid, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val offlineAccount = OfflineAccounts.create(username)
                app.accountStore.save(AccountCodec.encode(offlineAccount))
                account = offlineAccount
                refreshAccountCard()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /**
     * 微软设备码登录。
     *
     * 设备码回调发生在 IO 线程，必须切回主线程更新界面；
     * 取消按钮只关闭对话框，底层协程继续轮询，登录成功后仍会刷新卡片。
     */
    private fun showMicrosoftLoginDialog() {
        val dialogBinding = DialogMsLoginBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ms_login_title)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        dialog.setCanceledOnTouchOutside(false)

        dialogBinding.openBrowserButton.setOnClickListener { launchVerificationUri() }
        dialogBinding.copyCodeButton.setOnClickListener { copyDeviceCode(dialogBinding) }

        dialog.show()

        msLoginJob?.cancel()
        msLoginJob = lifecycleScope.launch {
            try {
                val result = app.loginFlow.startDeviceCodeLogin(
                    onDeviceCode = { info ->
                        runOnUiThread {
                            deviceCode = info.userCode
                            verificationUri = info.verificationUri
                            dialogBinding.msUserCode.text = info.userCode
                            dialogBinding.msVerificationUri.text = info.verificationUri
                            dialogBinding.msLoginStatus.text = getString(R.string.ms_login_waiting)
                        }
                    },
                    onEvent = { event ->
                        if (event is MicrosoftAuth.PollEvent.SlowDown) {
                            runOnUiThread {
                                dialogBinding.msLoginStatus.text =
                                    getString(R.string.ms_login_slow_down, event.newIntervalSeconds)
                            }
                        }
                    },
                )
                if (isFinishing || isDestroyed) return@launch
                account = result
                if (dialog.isShowing) dialog.dismiss()
                refreshAccountCard()
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.ms_login_success, result.username),
                    Toast.LENGTH_SHORT,
                ).show()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Exception) {
                if (isFinishing || isDestroyed) return@launch
                if (dialog.isShowing) dialog.dismiss()
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.ms_login_failed, exception.message ?: ""),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /** 用系统浏览器打开微软验证网址 */
    private fun launchVerificationUri() {
        val uri = verificationUri ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.ms_login_no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyDeviceCode(dialogBinding: DialogMsLoginBinding) {
        val code = deviceCode ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(dialogBinding.msUserCode.text, code))
        Toast.makeText(this, R.string.ms_login_copied, Toast.LENGTH_SHORT).show()
    }

    // endregion

    // region 服务器列表

    private fun selectServer(position: Int) {
        if (position !in servers.indices) return
        selectedIndex = position
        adapter.select(position)
    }

    /** 长按列表项：编辑 / 删除 */
    private fun showServerActions(position: Int) {
        val entry = servers.getOrNull(position) ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(entry.name)
            .setItems(
                arrayOf<CharSequence>(
                    getString(R.string.action_edit),
                    getString(R.string.action_delete),
                ),
            ) { _, which ->
                when (which) {
                    0 -> showServerEditDialog(position)
                    1 -> confirmDeleteServer(position)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** 添加与编辑共用同一个对话框：编辑时传入下标并预填内容 */
    private fun showServerEditDialog(position: Int?) {
        val dialogBinding = DialogServerEditBinding.inflate(layoutInflater)
        if (position != null) {
            servers.getOrNull(position)?.let { entry ->
                dialogBinding.serverNameInput.setText(entry.name)
                dialogBinding.serverAddressInput.setText(entry.displayAddress)
            }
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_server_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.action_save, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                // 地址校验交给 ServerAddress.parse，端口非法或为空时给出统一提示
                val address = try {
                    ServerAddress.parse(dialogBinding.serverAddressInput.text.toString())
                } catch (_: Exception) {
                    Toast.makeText(this, R.string.server_invalid_address, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val name = dialogBinding.serverNameInput.text.toString().trim()
                val entry = ServerEntry(
                    name = name.ifBlank { address.host },
                    host = address.host,
                    port = address.port,
                )
                if (position != null && position in servers.indices) {
                    servers[position] = entry
                } else {
                    servers.add(entry)
                    selectedIndex = servers.lastIndex
                }
                persistServers()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun confirmDeleteServer(position: Int) {
        val entry = servers.getOrNull(position) ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_delete)
            .setMessage(getString(R.string.server_delete_confirm, entry.name))
            .setPositiveButton(R.string.action_delete) { _, _ -> deleteServer(position) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun deleteServer(position: Int) {
        if (position !in servers.indices) return
        servers.removeAt(position)
        // 删除后修正选中下标
        selectedIndex = when {
            selectedIndex == position -> NO_SELECTION
            selectedIndex > position -> selectedIndex - 1
            else -> selectedIndex
        }
        persistServers()
    }

    private fun persistServers() {
        app.saveServers(servers)
        refreshServers()
    }

    private fun refreshServers() {
        if (selectedIndex !in servers.indices) selectedIndex = NO_SELECTION
        adapter.submit(servers, selectedIndex)
        binding.emptyServers.isVisible = servers.isEmpty()
        binding.serverList.isVisible = servers.isNotEmpty()
    }

    // endregion

    // region 连接

    private fun connect() {
        if (isConnecting) return

        val server = servers.getOrNull(selectedIndex)
        if (server == null) {
            Toast.makeText(this, R.string.msg_need_server, Toast.LENGTH_SHORT).show()
            return
        }
        val currentAccount = account
        if (currentAccount == null) {
            Toast.makeText(this, R.string.msg_need_account, Toast.LENGTH_SHORT).show()
            return
        }

        setConnecting(true, server.name)
        lifecycleScope.launch {
            try {
                // 地址解析、版本探测、令牌刷新都在 IO 线程完成，避免阻塞界面
                val setup = withContext(Dispatchers.IO) {
                    app.session.connect(server, currentAccount)
                }
                // 连接准备过程可能刷新了访问令牌，需要重新持久化
                if (setup.account != currentAccount) {
                    app.accountStore.save(AccountCodec.encode(setup.account))
                    account = setup.account
                    refreshAccountCard()
                }
                MccConnectionService.start(this@MainActivity, server.name)
                startActivity(
                    Intent(this@MainActivity, ConsoleActivity::class.java)
                        .putExtra(MccConnectionService.EXTRA_SERVER_NAME, server.name)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.msg_connect_failed, exception.message ?: ""),
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                setConnecting(false)
            }
        }
    }

    private fun setConnecting(connecting: Boolean, serverName: String = "") {
        isConnecting = connecting
        binding.connectButton.isEnabled = !connecting
        binding.connectButton.text = if (connecting) {
            getString(R.string.msg_connecting_server, serverName)
        } else {
            getString(R.string.action_connect)
        }
    }

    // endregion

    /** API 33+ 需要通知权限才能展示连接状态通知，被拒绝也不影响连接 */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private companion object {
        const val STATE_SELECTED_INDEX = "state_selected_index"
        const val NO_SELECTION = -1

        /** 离线用户名规则：3-16 位字母、数字或下划线 */
        val OFFLINE_USERNAME_PATTERN = Regex("[A-Za-z0-9_]{3,16}")
    }
}