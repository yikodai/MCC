package com.mccteam.android.mcc.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.mccteam.android.mcc.R
import com.mccteam.android.mcc.databinding.ItemServerBinding
import mccandroid.core.session.ServerEntry

/**
 * 服务器列表适配器。
 *
 * 单选语义：由外部（Activity）持有选中下标，通过 [submit] / [select] 同步；
 * 点击回调下标，长按回调下标，具体行为交给界面处理。
 */
class ServerListAdapter(
    /** 点击某一行 */
    private val onSelect: (Int) -> Unit,
    /** 长按某一行 */
    private val onLongPress: (Int) -> Unit,
) : RecyclerView.Adapter<ServerListAdapter.ServerHolder>() {

    private val items = mutableListOf<ServerEntry>()
    private var selectedIndex = RecyclerView.NO_POSITION

    class ServerHolder(val binding: ItemServerBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ServerHolder {
        val binding = ItemServerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ServerHolder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ServerHolder, position: Int) {
        val entry = items[position]
        val context = holder.itemView.context
        val selected = position == selectedIndex

        holder.binding.serverName.text = entry.name
        holder.binding.serverAddress.text = entry.displayAddress

        // 选中态：勾选图标 + 描边加粗 + 淡绿背景
        holder.binding.serverSelectedIcon.isVisible = selected
        val surface = ContextCompat.getColor(context, R.color.mcc_surface)
        val highlight = ColorUtils.setAlphaComponent(ContextCompat.getColor(context, R.color.mcc_green), 0x40)
        holder.binding.serverCard.setCardBackgroundColor(if (selected) highlight else surface)
        holder.binding.serverCard.strokeColor =
            ContextCompat.getColor(context, if (selected) R.color.mcc_green else R.color.mcc_green_dark)
        val density = context.resources.displayMetrics.density
        holder.binding.serverCard.strokeWidth = (density * if (selected) 2f else 1f).toInt()

        holder.binding.root.setOnClickListener {
            val adapterPosition = holder.bindingAdapterPosition
            if (adapterPosition != RecyclerView.NO_POSITION) onSelect(adapterPosition)
        }
        holder.binding.root.setOnLongClickListener {
            val adapterPosition = holder.bindingAdapterPosition
            if (adapterPosition != RecyclerView.NO_POSITION) onLongPress(adapterPosition)
            true
        }
    }

    /** 用新列表与选中下标整体刷新 */
    fun submit(servers: List<ServerEntry>, selected: Int) {
        items.clear()
        items.addAll(servers)
        selectedIndex = selected
        notifyDataSetChanged()
    }

    /** 仅更新选中项（避免整表刷新） */
    fun select(index: Int) {
        val previous = selectedIndex
        selectedIndex = index
        if (previous != selectedIndex) {
            if (previous in items.indices) notifyItemChanged(previous)
            if (index in items.indices) notifyItemChanged(index)
        }
    }
}