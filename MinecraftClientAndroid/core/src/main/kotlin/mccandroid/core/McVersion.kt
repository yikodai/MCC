package mccandroid.core

/**
 * Minecraft Java 版本与网络协议号映射。
 *
 * 与 MCC `Protocol/ProtocolHandler.cs` 的 `MCVer2ProtocolVersion()` 保持一致，
 * 安卓端首版支持 1.20.6 (766) 至 26.2 (776)。
 */
object McVersion {

    /** 首版支持的最低协议号：1.20.6 */
    const val MIN_PROTOCOL = 766

    /** 首版支持的最高协议号：26.2 */
    const val MAX_PROTOCOL = 776

    /** 默认使用的协议号（连接时由服务器 ping 结果决定，失败时回退到该值）。 */
    const val DEFAULT_PROTOCOL = 776

    private val versionToProtocol: Map<String, Int> = mapOf(
        "1.20.5" to 766, "1.20.6" to 766,
        "1.21" to 767, "1.21.1" to 767,
        "1.21.2" to 768, "1.21.3" to 768,
        "1.21.4" to 769,
        "1.21.5" to 770,
        "1.21.6" to 771,
        "1.21.7" to 772, "1.21.8" to 772,
        "1.21.9" to 773, "1.21.10" to 773,
        "1.21.11" to 774,
        "26.1" to 775,
        "26.2" to 776,
    )

    private val protocolToVersion: Map<Int, String> = mapOf(
        766 to "1.20.6",
        767 to "1.21.1",
        768 to "1.21.3",
        769 to "1.21.4",
        770 to "1.21.5",
        771 to "1.21.6",
        772 to "1.21.8",
        773 to "1.21.10",
        774 to "1.21.11",
        775 to "26.1",
        776 to "26.2",
    )

    /** 是否支持该协议号。 */
    fun isSupported(protocol: Int): Boolean = protocol in MIN_PROTOCOL..MAX_PROTOCOL

    /**
     * 人类可读版本号转协议号，未知版本返回 0（与 MCC 行为一致）。
     */
    fun versionToProtocol(version: String): Int {
        val normalized = version.trim().split(' ').first()
        versionToProtocol[normalized]?.let { return it }
        return normalized.toIntOrNull() ?: 0
    }

    /**
     * 协议号转人类可读版本号，未知协议返回 "未知"。
     */
    fun protocolToVersion(protocol: Int): String = protocolToVersion[protocol] ?: "未知"

    /**
     * 悬停提示 / 界面展示用的协议号描述。
     */
    fun describe(protocol: Int): String = "$protocol (${protocolToVersion(protocol)})"
}