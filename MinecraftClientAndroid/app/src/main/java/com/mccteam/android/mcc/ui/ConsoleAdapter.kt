package com.mccteam.android.mcc.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mccteam.android.mcc.databinding.ItemConsoleLineBinding
import mccandroid.core.protocol.ChatLine

/** 命令行界面的一行日志 */
class ConsoleAdapter(private val maxLines: Int = 600) : RecyclerView.Adapter<ConsoleAdapter.LineHolder>() {

    private val lines = mutableListOf<ChatLine>()

    class LineHolder(val binding: ItemConsoleLineBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LineHolder {
        val binding = ItemConsoleLineBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return LineHolder(binding)
    }

    override fun getItemCount(): Int = lines.size

    override fun onBindViewHolder(holder: LineHolder, position: Int) {
        val line = lines[position]
        holder.binding.lineText.text = LegacyTextRenderer.render(line.legacyText)
    }

    /** 追加一行；超出上限时丢弃最早的行 */
    fun append(line: ChatLine) {
        lines.add(line)
        if (lines.size > maxLines) {
            val overflow = lines.size - maxLines
            repeat(overflow) { lines.removeAt(0) }
            notifyDataSetChanged()
            return
        }
        notifyItemInserted(lines.size - 1)
    }

    fun clear() {
        lines.clear()
        notifyDataSetChanged()
    }

    /** 导出当前日志（纯文本） */
    fun toPlainText(): String = lines.joinToString("\n") { it.plainText }
}