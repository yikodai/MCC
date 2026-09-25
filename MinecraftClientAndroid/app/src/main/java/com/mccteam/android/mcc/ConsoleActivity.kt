package com.mccteam.android.mcc

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.mccteam.android.mcc.databinding.ActivityConsoleBinding
import com.mccteam.android.mcc.ui.ConsoleAdapter
import kotlinx.coroutines.launch
import mccandroid.core.protocol.ChatKind
import mccandroid.core.protocol.ChatLine
import mccandroid.core.protocol.ConnectionState

/**
 * 命令行界面。
 *
 * 与 MCC 的终端体验一致：上方滚动显示服务器文本（保留颜色），下方输入聊天内容或 /指令。
 * 连接由 [MccConnectionService] 保活，退出界面不会断开连接。
 */
class ConsoleActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConsoleBinding
    private val adapter = ConsoleAdapter()
    private val history = mutableListOf<String>()
    private var historyIndex = -1
    private var serverName: String = ""

    private val app: MccApplication get() = MccApplication.from(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConsoleBinding.inflate(layoutInflater)
        setContentView(binding.root)

        serverName = intent.getStringExtra(MccConnectionService.EXTRA_SERVER_NAME).orEmpty()
        if (serverName.isBlank()) {
            serverName = getString(R.string.app_name)
        }

        if (app.session.state.value == ConnectionState.DISCONNECTED) {
            Toast.makeText(this, R.string.msg_need_server, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setSupportActionBar(binding.toolbar)
        binding.toolbar.title = getString(R.string.console_title, serverName)

        binding.consoleList.layoutManager = LinearLayoutManager(this)
        binding.consoleList.adapter = adapter

        binding.sendButton.setOnClickListener { sendCurrentInput() }
        binding.historyButton.setOnClickListener { recallHistory() }
        binding.inputField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCurrentInput()
                true
            } else {
                false
            }
        }

        lifecycleScope.launch {
            app.session.messages.collect { line ->
                adapter.append(line)
                scrollToBottom()
            }
        }

        lifecycleScope.launch {
            app.session.state.collect { state -> updateSubtitle(state) }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_DISCONNECT, 0, R.string.action_disconnect)
        menu.add(0, MENU_CLEAR, 1, R.string.action_clear_console)
        menu.add(0, MENU_COPY, 2, R.string.action_copy_log)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_DISCONNECT -> {
            app.session.disconnect()
            MccConnectionService.stop(this)
            finish()
            true
        }

        MENU_CLEAR -> {
            adapter.clear()
            true
        }

        MENU_COPY -> {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("MCC log", adapter.toPlainText()))
            Toast.makeText(this, R.string.msg_log_copied, Toast.LENGTH_SHORT).show()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun sendCurrentInput() {
        val text = binding.inputField.text?.toString().orEmpty().trim()
        if (text.isEmpty()) return

        try {
            app.session.sendChat(text)
        } catch (exception: Exception) {
            adapter.append(
                ChatLine(
                    legacyText = "§c${exception.message}",
                    plainText = exception.message ?: "",
                    kind = ChatKind.CLIENT,
                ),
            )
            scrollToBottom()
            return
        }

        history.add(text)
        historyIndex = history.size
        binding.inputField.setText("")
    }

    /** 用 ↑ 按钮取回上一条输入 */
    private fun recallHistory() {
        if (history.isEmpty()) return
        historyIndex = (historyIndex - 1).coerceAtLeast(0)
        binding.inputField.setText(history[historyIndex])
        binding.inputField.setSelection(binding.inputField.text?.length ?: 0)
    }

    private fun updateSubtitle(state: ConnectionState) {
        val text = when (state) {
            ConnectionState.CONNECTING -> getString(R.string.console_state_connecting, serverName)
            ConnectionState.LOGGING_IN -> getString(R.string.console_state_connecting, serverName)
            ConnectionState.CONFIGURING -> getString(R.string.msg_loading)
            ConnectionState.PLAYING -> ""
            ConnectionState.DISCONNECTED -> getString(R.string.console_state_disconnected)
        }
        binding.toolbar.subtitle = text
        if (state == ConnectionState.DISCONNECTED) {
            // 断开后保留界面，便于查看断开原因；由用户自行返回
            MccConnectionService.stop(this)
        }
    }

    private fun scrollToBottom() {
        if (adapter.itemCount == 0) return
        binding.consoleList.scrollToPosition(adapter.itemCount - 1)
    }

    private companion object {
        const val MENU_DISCONNECT = 1
        const val MENU_CLEAR = 2
        const val MENU_COPY = 3
    }
}