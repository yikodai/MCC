package mccandroid.core.protocol

import java.security.KeyPairGenerator
import javax.crypto.Cipher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CryptoUtilsTest {

    @Test
    fun `服务端哈希为小写十六进制且长度可控`() {
        val hash = CryptoUtils.serverHash("", ByteArray(16) { 1 }, ByteArray(16) { 2 })
        val digits = hash.removePrefix("-")
        assertTrue(digits.matches(Regex("[0-9a-f]+")), "哈希应为小写十六进制：$hash")
        assertTrue(digits.length in 1..40, "哈希位数不应超过 SHA-1 的 40 位：$hash")
        assertTrue(!digits.startsWith("0"), "不应保留前导零：$hash")
    }

    @Test
    fun `相同输入得到相同服务端哈希`() {
        val publicKey = ByteArray(32) { it.toByte() }
        val secret = ByteArray(16) { (it * 3).toByte() }
        assertEquals(
            CryptoUtils.serverHash("server-id", publicKey, secret),
            CryptoUtils.serverHash("server-id", publicKey, secret),
        )
    }

    @Test
    fun `不同 serverId 产生不同哈希`() {
        val publicKey = ByteArray(32) { it.toByte() }
        val secret = ByteArray(16) { (it * 3).toByte() }
        assertTrue(
            CryptoUtils.serverHash("a", publicKey, secret) != CryptoUtils.serverHash("b", publicKey, secret),
        )
    }

    @Test
    fun `共享密钥为 128 位且随机`() {
        val first = CryptoUtils.generateSharedSecret()
        val second = CryptoUtils.generateSharedSecret()
        assertEquals(16, first.size)
        assertTrue(first.contentEquals(second).not(), "两次生成的密钥不应相同")
    }

    @Test
    fun `RSA 加密可被服务端私钥解开`() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val data = CryptoUtils.generateSharedSecret()

        // 服务端在加密请求中发送的正是 X.509 SubjectPublicKeyInfo 编码的公钥
        val encrypted = CryptoUtils.encryptWithServerKey(keyPair.public.encoded, data)
        assertEquals(256, encrypted.size, "2048 位 RSA 的密文长度为 256 字节")

        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, keyPair.private)
        assertEquals(data.toList(), cipher.doFinal(encrypted).toList())
    }

    @Test
    fun `AES-CFB8 加解密往返`() {
        val key = CryptoUtils.generateSharedSecret()
        val plain = "Minecraft 加密通道测试".toByteArray()

        val encrypted = EncryptingStream(key).encrypt(plain)
        assertEquals(plain.size, encrypted.size, "流加密不改变长度")
        assertEquals(plain.toList(), EncryptingStream(key).decrypt(encrypted).toList())
    }

    @Test
    fun `逐字节解密与整块解密等价`() {
        val key = CryptoUtils.generateSharedSecret()
        val plain = ByteArray(64) { (it * 7).toByte() }
        val encrypted = EncryptingStream(key).encrypt(plain)

        // 接收路径会先逐字节读取长度前缀，再整块读取包体，两者必须一致
        val decryptor = EncryptingStream(key)
        val byteWise = ByteArray(8)
        for (i in 0 until 8) {
            byteWise[i] = decryptor.decrypt(byteArrayOf(encrypted[i]))[0]
        }
        val rest = decryptor.decrypt(encrypted, 8, encrypted.size - 8)

        assertEquals(plain.toList(), (byteWise.toList() + rest.toList()))
    }

    @Test
    fun `使用错误密钥解密得到错误数据`() {
        val plain = ByteArray(32) { 1 }
        val encrypted = EncryptingStream(CryptoUtils.generateSharedSecret()).encrypt(plain)
        val wrong = EncryptingStream(CryptoUtils.generateSharedSecret()).decrypt(encrypted)
        assertTrue(!wrong.contentEquals(plain))
    }

    @Test
    fun `非法公钥抛出异常`() {
        assertFailsWith<Exception> {
            CryptoUtils.encryptWithServerKey(byteArrayOf(1, 2, 3), ByteArray(16))
        }
    }
}