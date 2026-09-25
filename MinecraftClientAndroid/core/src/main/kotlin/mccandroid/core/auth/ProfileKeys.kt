package mccandroid.core.auth

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.UUID

/**
 * 玩家档案密钥（安全聊天签名密钥）。
 *
 * 端口自 MCC `Protocol/ProfileKey/{KeyUtils,PlayerKeyPair,PrivateKey}.cs`。
 * 密钥由 Minecraft 服务签发，用于给聊天消息签名；1.19.3+ 的服务端会用
 * 「聊天会话更新」包收到的公钥验证签名。
 */
class ProfileKeys(
    /** DER 编码的公钥（X.509 SubjectPublicKeyInfo） */
    val publicKeyDer: ByteArray,
    /** PKCS#8 编码的私钥 */
    val privateKeyPkcs8: ByteArray,
    val publicKeySignature: ByteArray,
    val publicKeySignatureV2: ByteArray,
    val expiresAt: Instant,
    val refreshedAfter: Instant,
) {
    fun isExpired(now: Instant = Instant.now()): Boolean = now.isAfter(expiresAt)

    /** 是否到了建议刷新密钥的时间 */
    fun shouldRefresh(now: Instant = Instant.now()): Boolean = now.isAfter(refreshedAfter)

    fun expirationMillis(): Long = expiresAt.toEpochMilli()

    companion object {

        fun decodePem(pem: String): ByteArray {
            val startMarkers = listOf(
                "-----BEGIN RSA PRIVATE KEY-----",
                "-----BEGIN PRIVATE KEY-----",
                "-----BEGIN PUBLIC KEY-----",
            )
            var body = pem.replace("\r", "").replace("\n", "")
            for (marker in startMarkers) {
                val index = body.indexOf(marker)
                if (index >= 0) {
                    body = body.substring(index + marker.length)
                    val end = body.indexOf("-----END")
                    if (end >= 0) {
                        body = body.substring(0, end)
                    }
                    break
                }
            }
            return Base64.getDecoder().decode(body)
        }

        fun decodeBase64(value: String): ByteArray = Base64.getDecoder().decode(value.replace("\r", "").replace("\n", ""))

        fun parseInstant(value: String): Instant = try {
            Instant.parse(value)
        } catch (_: DateTimeParseException) {
            Instant.now().plusSeconds(86_400)
        }
    }
}

/**
 * 聊天消息签名器（1.19.3+ 协议格式）。
 *
 * 签名内容与 MCC `KeyUtils.GetSignatureData_1_19_3()` 完全一致，
 * 对应原版 `net.minecraft.network.message.SignedMessage#update`：
 * ```
 * int 1                      // 版本标记
 * UUID 玩家 UUID
 * UUID 聊天会话 UUID
 * int 消息序号
 * long 盐（8 字节）
 * long 时间戳（秒）
 * int 消息长度 + 消息字节
 * int 已确认消息数量 + 各消息签名
 * ```
 */
class MessageSigner(
    private val keys: ProfileKeys,
    private val playerUuid: UUID,
    val chatUuid: UUID,
) {
    private var messageIndex = 0
    private val privateKey by lazy {
        KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keys.privateKeyPkcs8))
    }

    fun nextMessageIndex(): Int = messageIndex++

    /**
     * 对聊天消息签名。
     *
     * @param message 消息正文
     * @param timestampMillis 消息时间戳（毫秒）
     * @param salt 8 字节盐
     * @param acknowledgedSignatures 已确认的上一条消息签名，安卓端默认不追踪（空列表）
     */
    fun sign(
        message: String,
        timestampMillis: Long,
        salt: ByteArray,
        acknowledgedSignatures: List<ByteArray> = emptyList(),
    ): ByteArray {
        val messageBytes = message.toByteArray(Charsets.UTF_8)
        val data = PacketSignBuffer.create { buffer ->
            buffer.writeInt(1)
            buffer.writeUuid(playerUuid)
            buffer.writeUuid(chatUuid)
            buffer.writeInt(nextMessageIndex())
            buffer.writeBytes(salt)
            buffer.writeLong(timestampMillis / 1000)
            buffer.writeInt(messageBytes.size)
            buffer.writeBytes(messageBytes)
            buffer.writeInt(acknowledgedSignatures.size)
            acknowledgedSignatures.forEach { buffer.writeBytes(it) }
        }

        val signature = Signature.getInstance("SHA256withRSA")
        signature.initSign(privateKey)
        signature.update(data)
        return signature.sign()
    }
}

/** 生成签名数据用的简易大端缓冲区 */
private class PacketSignBuffer private constructor() {

    private val output = java.io.ByteArrayOutputStream()

    fun writeInt(value: Int) {
        output.write((value ushr 24) and 0xFF)
        output.write((value ushr 16) and 0xFF)
        output.write((value ushr 8) and 0xFF)
        output.write(value and 0xFF)
    }

    fun writeLong(value: Long) {
        for (i in 7 downTo 0) {
            output.write(((value ushr (i * 8)) and 0xFF).toInt())
        }
    }

    fun writeUuid(value: UUID) {
        writeLong(value.mostSignificantBits)
        writeLong(value.leastSignificantBits)
    }

    fun writeBytes(value: ByteArray) = output.write(value)

    fun toByteArray(): ByteArray = output.toByteArray()

    companion object {
        fun create(block: (PacketSignBuffer) -> Unit): ByteArray =
            PacketSignBuffer().apply(block).toByteArray()
    }
}