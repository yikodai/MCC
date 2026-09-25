package mccandroid.core.protocol

/**
 * Minecraft 包分帧（framing）。
 *
 * 与 MCC `Protocol18Handler.SendPacket` / `ReadNextPacket` 的字节布局保持一致：
 * ```
 * [长度 VarInt][包体]
 * 包体（未启用压缩）      = [包 ID VarInt][负载]
 * 包体（启用压缩）        = [解压后长度 VarInt][zlib 数据或原始负载]
 * ```
 * 独立成对象是为了让分包逻辑可以脱离网络连接做单元测试。
 */
internal object PacketFraming {

    /** 组包，返回带长度前缀的完整包（不含加密）。[compressionThreshold] 为负表示未启用压缩。 */
    fun encode(packetId: Int, payload: ByteArray, compressionThreshold: Int): ByteArray {
        val body = PacketWriter().writeVarInt(packetId).writeByteArray(payload).toByteArray()

        val framed = if (compressionThreshold >= 0) {
            if (body.size >= compressionThreshold) {
                PacketWriter()
                    .writeVarInt(body.size)
                    .writeByteArray(Compression.compress(body))
                    .toByteArray()
            } else {
                PacketWriter().writeVarInt(0).writeByteArray(body).toByteArray()
            }
        } else {
            body
        }

        return PacketWriter().writeVarInt(framed.size).writeByteArray(framed).toByteArray()
    }

    /**
     * 拆包：传入长度前缀之后读取到的包体，返回包 ID 与负载读取器。
     */
    fun decodePayload(payload: ByteArray, compressionThreshold: Int): Pair<Int, PacketReader> {
        val body = if (compressionThreshold >= 0) {
            val reader = PacketReader(payload)
            val uncompressedLength = reader.readVarInt()
            if (uncompressedLength == 0) {
                reader.readRemaining()
            } else {
                Compression.decompress(reader.readRemaining(), uncompressedLength)
            }
        } else {
            payload
        }

        val reader = PacketReader(body)
        return reader.readVarInt() to reader
    }
}