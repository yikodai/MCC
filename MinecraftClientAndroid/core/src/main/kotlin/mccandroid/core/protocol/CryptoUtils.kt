package mccandroid.core.protocol

import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Minecraft 协议加密相关工具。
 *
 * 端口自 MCC `Crypto/CryptoHandler.cs`：
 * 1. AES-128-CFB8 流加密（共享密钥，IV 与密钥相同）
 * 2. 用服务端 RSA 公钥加密共享密钥与验证令牌
 * 3. 在线模式会话校验用的 SHA-1 服务端哈希（Minecraft 特有的有符号十六进制）
 */
object CryptoUtils {

    private const val AES_KEY_BITS = 128
    private val random = SecureRandom()

    /** 生成客户端共享密钥（AES-128） */
    fun generateSharedSecret(): ByteArray {
        val key = ByteArray(AES_KEY_BITS / 8)
        random.nextBytes(key)
        return key
    }

    /**
     * Minecraft 的服务端哈希算法：SHA-1(serverId + sharedSecret + publicKey)，
     * 结果为补码小端十六进制字符串，负数带前缀 "-"。
     */
    fun serverHash(serverId: String, publicKey: ByteArray, sharedSecret: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(serverId.toByteArray(StandardCharsets.ISO_8859_1))
        digest.update(sharedSecret)
        digest.update(publicKey)
        var hash = digest.digest()

        val negative = hash[0].toInt() and 0x80 == 0x80
        if (negative) {
            hash = twosComplement(hash)
        }

        val hex = hash.joinToString("") { "%02x".format(it) }.trimStart('0')
        return if (negative) "-$hex" else hex
    }

    private fun twosComplement(value: ByteArray): ByteArray {
        val result = value.copyOf()
        var carry = true
        for (i in result.indices.reversed()) {
            result[i] = result[i].toInt().inv().toByte()
            if (carry) {
                carry = result[i] == 0xFF.toByte()
                result[i] = (result[i] + 1).toByte()
            }
        }
        return result
    }

    /** 使用服务端公钥（X.509 SubjectPublicKeyInfo DER）加密数据，PKCS#1 v1.5 填充。 */
    fun encryptWithServerKey(publicKeyDer: ByteArray, data: ByteArray): ByteArray {
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(publicKeyDer))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        return cipher.doFinal(data)
    }

    /** 校验服务端是否请求在线模式鉴权（1.20.6+ 的 shouldAuthenticate 字段）。 */
    fun parseShouldAuthenticate(reader: PacketReader): Boolean = reader.readBoolean()
}

/**
 * AES-128-CFB8 加密流。
 *
 * Minecraft 在启用加密后，之后所有收发的字节都经过该密码流处理。
 * CFB8 属于流加密，可以按块持续 update。
 */
class EncryptingStream(key: ByteArray) {

    private val encryptCipher = createCipher(Cipher.ENCRYPT_MODE, key)
    private val decryptCipher = createCipher(Cipher.DECRYPT_MODE, key)

    private fun createCipher(mode: Int, key: ByteArray): Cipher {
        val cipher = Cipher.getInstance("AES/CFB8/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        cipher.init(mode, keySpec, IvParameterSpec(key))
        return cipher
    }

    fun encrypt(data: ByteArray): ByteArray = encryptCipher.update(data) ?: ByteArray(0)

    fun decrypt(data: ByteArray): ByteArray = decryptCipher.update(data) ?: ByteArray(0)

    fun decrypt(data: ByteArray, offset: Int, length: Int): ByteArray =
        decryptCipher.update(data, offset, length) ?: ByteArray(0)
}