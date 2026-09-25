package mccandroid.core.protocol

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * 包压缩（zlib）。
 *
 * 服务端发送 SetCompression 后，所有包体的长度字段语义变为：
 * - 长度 0：包体未压缩
 * - 长度 > 0：包体为该长度的 zlib 数据
 * 见 MCC `Protocol18Handler.SendPacket` / `ReadNextPacket`。
 */
internal object Compression {

    /** zlib 压缩，等价于 MCC 使用的 ZlibUtils.Compress（默认压缩级别） */
    fun compress(data: ByteArray): ByteArray {
        val deflater = Deflater()
        try {
            deflater.setInput(data)
            deflater.finish()
            val output = ByteArrayOutputStream(data.size / 2 + 16)
            val buffer = ByteArray(8192)
            while (!deflater.finished()) {
                val written = deflater.deflate(buffer)
                output.write(buffer, 0, written)
            }
            return output.toByteArray()
        } finally {
            deflater.end()
        }
    }

    /** zlib 解压，[expectSize] 为服务端声明的解压后长度，用于预分配与校验 */
    fun decompress(data: ByteArray, expectSize: Int): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(data)
            val output = ByteArrayOutputStream(if (expectSize > 0) expectSize else data.size * 4)
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val written = inflater.inflate(buffer)
                if (written == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        throw ProtocolException("压缩包数据不完整")
                    }
                }
                output.write(buffer, 0, written)
            }
            val result = output.toByteArray()
            if (expectSize > 0 && result.size != expectSize) {
                throw ProtocolException("解压长度不符：期望 $expectSize，实际 ${result.size}")
            }
            return result
        } catch (exception: java.util.zip.DataFormatException) {
            throw ProtocolException("解压失败：${exception.message}", exception)
        } finally {
            inflater.end()
        }
    }
}