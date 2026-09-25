package com.mccteam.android.mcc.ui

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan

/**
 * Minecraft 传统格式代码（§）渲染器。
 *
 * 核心库把聊天组件统一转成带 § 代码的文本，这里把颜色与样式还原成 Android 富文本。
 */
object LegacyTextRenderer {

    /** 原版 16 色（0-9, a-f） */
    private val COLORS = intArrayOf(
        0x000000, // 0 黑
        0x0000AA, // 1 深蓝
        0x00AA00, // 2 深绿
        0x00AAAA, // 3 深青
        0xAA0000, // 4 深红
        0xAA00AA, // 5 深紫
        0xFFAA00, // 6 金
        0xAAAAAA, // 7 灰
        0x555555, // 8 深灰
        0x5555FF, // 9 蓝
        0x55FF55, // a 绿
        0x55FFFF, // b 青
        0xFF5555, // c 红
        0xFF55FF, // d 品红
        0xFFFF55, // e 黄
        0xFFFFFF, // f 白
    )

    fun render(text: String): CharSequence {
        val builder = SpannableStringBuilder()
        var color: Int? = null
        var bold = false
        var italic = false
        var underline = false
        var strike = false
        var spanStart = 0

        fun applySpans(end: Int) {
            if (end <= spanStart) return
            color?.let { builder.setSpan(ForegroundColorSpan(it), spanStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            if (bold) builder.setSpan(StyleSpan(Typeface.BOLD), spanStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (italic) builder.setSpan(StyleSpan(Typeface.ITALIC), spanStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (underline) builder.setSpan(UnderlineSpan(), spanStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (strike) builder.setSpan(StrikethroughSpan(), spanStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        var index = 0
        while (index < text.length) {
            val current = text[index]
            if (current == '§' && index + 1 < text.length) {
                applySpans(builder.length)
                when (val code = text[index + 1].lowercaseChar()) {
                    in '0'..'9' -> {
                        color = COLORS[code - '0']
                        bold = false; italic = false; underline = false; strike = false
                    }
                    in 'a'..'f' -> {
                        color = COLORS[code - 'a' + 10]
                        bold = false; italic = false; underline = false; strike = false
                    }
                    'k' -> Unit // 混淆文字在聊天里没有实际意义，保留原文
                    'l' -> bold = true
                    'm' -> strike = true
                    'n' -> underline = true
                    'o' -> italic = true
                    'r' -> {
                        color = null
                        bold = false; italic = false; underline = false; strike = false
                    }
                    else -> Unit
                }
                spanStart = builder.length
                index += 2
                continue
            }

            builder.append(current)
            index++
        }

        applySpans(builder.length)
        return builder
    }
}