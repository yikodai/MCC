package mccandroid.core.chat

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Minecraft 聊天组件渲染器。
 *
 * 把聊天组件（JSON 字符串或已解析的 [JsonElement]）渲染成带 § 传统颜色代码的字符串，
 * 供安卓端终端式界面按颜色显示；也可以用 [toPlainText] 去掉所有 § 代码，用于日志与通知。
 *
 * 支持的组件形式：字符串、数组，以及包含 text / translate / extra / keybind / score /
 * selector / nbt 等键的对象。样式键 color / bold / italic / underlined / strikethrough /
 * obfuscated / reset 会向下继承，子组件可以覆盖父组件的样式。
 */
object ChatParser {

    /** 原版 16 色（含旧版别名）到传统 § 颜色代码的映射。 */
    private val LEGACY_COLORS: Map<String, String> = mapOf(
        "black" to "§0",
        "dark_blue" to "§1",
        "dark_green" to "§2",
        "dark_aqua" to "§3",
        "dark_cyan" to "§3",
        "dark_red" to "§4",
        "dark_purple" to "§5",
        "dark_magenta" to "§5",
        "gold" to "§6",
        "dark_yellow" to "§6",
        "gray" to "§7",
        "grey" to "§7",
        "dark_gray" to "§8",
        "dark_grey" to "§8",
        "blue" to "§9",
        "green" to "§a",
        "aqua" to "§b",
        "cyan" to "§b",
        "red" to "§c",
        "light_purple" to "§d",
        "magenta" to "§d",
        "yellow" to "§e",
        "white" to "§f",
    )

    /**
     * 内置的少量常用翻译键。
     *
     * 只覆盖聊天里最常出现的一小部分；缺失的键会在 [translate] 中以「键名(参数...)」形式保留，
     * 保证信息不丢失。
     */
    private val TRANSLATIONS: Map<String, String> = mapOf(
        "chat.type.text" to "<%s> %s",
        "chat.type.announcement" to "[%s] %s",
        "chat.type.emote" to "* %s %s",
        "chat.type.team.text" to "<%s> <%s> %s",
        "chat.type.team.sent" to "-> %s <%s> %s",
        "chat.type.advancement.task" to "%s has made the advancement [%s]",
        "chat.type.advancement.challenge" to "%s has completed the challenge [%s]",
        "chat.type.advancement.goal" to "%s has reached the goal [%s]",
        "chat.square_brackets" to "[%s]",
        "multiplayer.player.joined" to "%s joined the game",
        "multiplayer.player.joined.renamed" to "%s (formerly known as %s) joined the game",
        "multiplayer.player.left" to "%s left the game",
        "death.fell.accident.generic" to "%s fell from a high place",
        "commands.message.display.incoming" to "%s whispers to you: %s",
        "commands.message.display.outgoing" to "You whisper to %s: %s",
    )

    private const val HEX_DIGITS = "0123456789abcdef"

    /** 匹配一个 § 代码：十六进制颜色 §#rrggbb 或单字符代码 §x。 */
    private val LEGACY_CODE_REGEX = Regex("§(?:#[0-9a-fA-F]{6}|.)")

    /**
     * 把聊天组件渲染为带 § 颜色/样式代码的文本。
     *
     * @param component 已解析的聊天组件。
     * @return 含 § 代码的文本。
     */
    fun toLegacyString(component: JsonElement): String = render(component, Style())

    /**
     * 把聊天组件的 JSON 字符串渲染为带 § 颜色/样式代码的文本。
     *
     * JSON 解析失败时不抛异常，直接返回原始字符串（Minecraft 有时会把纯文本当作组件内容）。
     *
     * @param json 聊天组件的 JSON 序列化文本。
     */
    fun toLegacyString(json: String): String {
        val element = try {
            Json.parseToJsonElement(json)
        } catch (e: SerializationException) {
            return json
        } catch (e: IllegalArgumentException) {
            return json
        }
        return toLegacyString(element)
    }

    /**
     * 去掉所有 § 代码，返回纯文本，用于日志和通知。
     *
     * @param component 聊天组件，允许为 null。
     * @return 不含 § 代码的纯文本；[component] 为 null 时返回空串。
     */
    fun toPlainText(component: JsonElement?): String {
        if (component == null) return ""
        return LEGACY_CODE_REGEX.replace(toLegacyString(component), "")
    }

    /** 继承后的有效样式。null 颜色表示沿用父级颜色。 */
    private data class Style(
        val color: String? = null,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underlined: Boolean = false,
        val strikethrough: Boolean = false,
        val obfuscated: Boolean = false,
    ) {
        /**
         * 生成把终端切换到该样式的 § 代码序列。
         *
         * 以 §r 归零，避免父级残留的颜色/格式混入；默认样式只输出 §r。
         */
        fun toLegacyCodes(): String {
            val sb = StringBuilder("§r")
            color?.let { sb.append(it) }
            if (obfuscated) sb.append("§k")
            if (bold) sb.append("§l")
            if (strikethrough) sb.append("§m")
            if (underlined) sb.append("§n")
            if (italic) sb.append("§o")
            return sb.toString()
        }
    }

    /** 渲染任意 [JsonElement]，[inherited] 为其继承的父级样式。 */
    private fun render(element: JsonElement, inherited: Style): String {
        return when (element) {
            is JsonObject -> renderObject(element, inherited)
            is JsonArray -> buildString { for (child in element) append(render(child, inherited)) }
            is JsonPrimitive -> if (element is JsonNull) "" else element.content
            else -> ""
        }
    }

    /**
     * 渲染对象组件。
     *
     * 先按对象自身声明求出有效样式：若与父级不同，在内容前插入样式代码，内容后再重新输出父级样式，
     * 使后续兄弟节点仍以父级样式为基准。样式相同的普通节点不产生任何代码。
     */
    private fun renderObject(obj: JsonObject, inherited: Style): String {
        val own = resolveStyle(obj, inherited)
        val changed = own != inherited
        val sb = StringBuilder()
        if (changed) sb.append(own.toLegacyCodes())
        sb.append(renderContent(obj, own))
        if (changed) sb.append(inherited.toLegacyCodes())
        return sb.toString()
    }

    /** 渲染对象组件的内容（text / translate 等），并追加 extra 子组件。 */
    private fun renderContent(obj: JsonObject, style: Style): String {
        val main = when {
            obj.containsKey("text") -> render(obj.getValue("text"), style)
            obj.containsKey("translate") -> renderTranslate(obj, style)
            obj.containsKey("keybind") -> renderKeybind(obj)
            obj.containsKey("score") -> renderScore(obj, style)
            obj.containsKey("selector") -> render(obj.getValue("selector"), style)
            obj.containsKey("nbt") -> renderNbt(obj.getValue("nbt"), style)
            else -> ""
        }
        val extra = obj["extra"]
        if (extra !is JsonArray) return main
        val sb = StringBuilder(main)
        // extra 子组件继承当前节点的样式
        for (child in extra) sb.append(render(child, style))
        return sb.toString()
    }

    /** 渲染 translate 组件：用 with（或旧版 using）参数替换翻译模板。 */
    private fun renderTranslate(obj: JsonObject, style: Style): String {
        val key = textOf(obj["translate"]) ?: return ""
        val params = mutableListOf<String>()
        val with = obj["with"] ?: obj["using"]
        if (with is JsonArray) for (item in with) params.add(render(item, style))
        return translate(key, params)
    }

    /**
     * 应用翻译规则。
     *
     * 命中内置表时按 %s / %d / %1$s 顺序替换参数；未命中时输出「键名(参数1, 参数2)」形式，
     * 无参数则只输出键名，保证信息不丢失。
     */
    private fun translate(key: String, params: List<String>): String {
        val rule = TRANSLATIONS[key]
        if (rule != null) return applyParams(rule, params)
        return if (params.isEmpty()) key else "$key(${params.joinToString(", ")})"
    }

    /** 按出现顺序替换模板里的 %s / %d / %N$s 占位符。 */
    private fun applyParams(rule: String, params: List<String>): String {
        val sb = StringBuilder()
        var index = 0
        var usingIndex = 0
        while (index < rule.length) {
            val c = rule[index]
            if (c == '%' && index + 1 < rule.length) {
                val next = rule[index + 1]
                // %s 或 %d，按顺序取参数
                if (next == 's' || next == 'd') {
                    if (usingIndex < params.size) {
                        sb.append(params[usingIndex])
                        usingIndex++
                        index += 2
                        continue
                    }
                }
                // %1$s / %2$s 形式的显式下标
                else if (next.isDigit() && index + 3 < rule.length &&
                    rule[index + 2] == '$' && (rule[index + 3] == 's' || rule[index + 3] == 'd')
                ) {
                    val target = next - '1'
                    if (target in params.indices) {
                        sb.append(params[target])
                        usingIndex++
                        index += 4
                        continue
                    }
                }
            }
            sb.append(c)
            index++
        }
        return sb.toString()
    }

    /** 渲染 keybind 组件：优先查内置翻译，找不到则原样输出按键名。 */
    private fun renderKeybind(obj: JsonObject): String {
        val key = textOf(obj["keybind"]) ?: return ""
        return TRANSLATIONS[key] ?: key
    }

    /** 渲染 score 组件：取 value，缺失时取 name。 */
    private fun renderScore(obj: JsonObject, style: Style): String {
        val score = obj["score"] ?: return ""
        if (score is JsonObject) {
            val value = score["value"] ?: score["name"] ?: return ""
            return render(value, style)
        }
        return textOf(score) ?: ""
    }

    /** 渲染 nbt 组件：取其中的文本字段，找不到则忽略。 */
    private fun renderNbt(element: JsonElement, style: Style): String {
        if (element is JsonObject) {
            val text = element["text"] ?: element["value"] ?: element["name"] ?: return ""
            return render(text, style)
        }
        return textOf(element) ?: ""
    }

    /** 求出对象自身声明的有效样式，从 [inherited] 继承未被覆盖的项。 */
    private fun resolveStyle(obj: JsonObject, inherited: Style): Style {
        // reset 为真时清空父级样式，从默认样式重新开始
        val reset = readBoolean(obj["reset"]) == true
        val base = if (reset) Style() else inherited
        return Style(
            color = colorToCode(obj["color"]) ?: base.color,
            bold = readFlag(obj["bold"], base.bold),
            italic = readFlag(obj["italic"], base.italic),
            underlined = readFlag(obj["underlined"], readFlag(obj["underline"], base.underlined)),
            strikethrough = readFlag(obj["strikethrough"], base.strikethrough),
            obfuscated = readFlag(obj["obfuscated"], base.obfuscated),
        )
    }

    /** 把颜色名或 #RRGGBB 转成 § 代码；无法识别时返回 null（忽略该颜色）。 */
    private fun colorToCode(element: JsonElement?): String? {
        val name = textOf(element)?.lowercase() ?: return null
        if (name.startsWith("#")) {
            val hex = name.substring(1)
            return if (hex.length == 6 && hex.all { it in HEX_DIGITS }) "§$name" else null
        }
        val stripped = if (name.startsWith("minecraft:")) name.substring("minecraft:".length) else name
        return LEGACY_COLORS[stripped]
    }

    /** 读取样式开关：缺失或非法时沿用 [current]。 */
    private fun readFlag(element: JsonElement?, current: Boolean): Boolean = readBoolean(element) ?: current

    /** 把 JsonElement 解析为布尔值，非布尔或缺失时返回 null。 */
    private fun readBoolean(element: JsonElement?): Boolean? {
        if (element !is JsonPrimitive || element is JsonNull) return null
        element.booleanOrNull?.let { return it }
        return when (element.content.lowercase()) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }
    }

    /** 取出原始字符串值，非字符串基本类型返回 null。 */
    private fun textOf(element: JsonElement?): String? {
        if (element !is JsonPrimitive || element is JsonNull) return null
        return element.content
    }
}