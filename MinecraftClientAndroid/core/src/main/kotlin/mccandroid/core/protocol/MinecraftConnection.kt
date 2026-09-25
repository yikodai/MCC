package mccandroid.core.protocol

import mccandroid.core.auth.AuthException
import mccandroid.core.auth.MccAccount
import mccandroid.core.auth.MessageSigner
import mccandroid.core.auth.MinecraftAuth
import mccandroid.core.auth.ProfileKeys
import mccandroid.core.chat.ChatParser
import mccandroid.core.chat.readNbtTagOrNull
import mccandroid.core.chat.readNetworkNbt
import mccandroid.core.util.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.UUID

/**
 * Minecraft Java 版协议客户端（1.20.6 - 26.2）。
 *
 * 端口自 MCC `Protocol/Handlers/Protocol18.cs`，只保留「命令行客户端」所需的部分：
 * 登录（含离线/在线模式与加密）、配置阶段、游戏阶段的聊天收发与保活。
 * 世界、背包、实体等功能不在安卓端首版范围内。
 *
 * 该类是阻塞式的：[run] 会一直执行到连接结束，调用方需要放在工作线程/IO 协程中。
 */
internal class MinecraftConnection(
    private val address: ServerAddress,
    private val account: MccAccount,
    private val protocol: Int,
    private val profileKeys: ProfileKeys?,
    private val settings: ClientSettings,
    private val signChat: Boolean,
    private val events: ConnectionEvents,
) : AutoCloseable {

    private var socket: Socket? = null
    private lateinit var input: InputStream
    private lateinit var output: OutputStream

    private var encryption: EncryptingStream? = null
    private var compressionThreshold = -1
    private var onlineMode = false
    private var state = ConnectionState.DISCONNECTED

    private val packetTable: PacketIdTable = packetIdTableForProtocol(protocol)
    private val inPlayById = packetTable.inPlay.entries.associate { (packet, id) -> id to packet }
    private val inConfigById = packetTable.inConfig.entries.associate { (packet, id) -> id to packet }

    private val writeLock = Any()
    private val random = SecureRandom()
    private val scratch = ByteArray(1)

    /** 聊天会话 UUID（安全聊天用），与 MCC 的 chatUuid 对应 */
    private val chatUuid: UUID = UUID.randomUUID()
    private var messageSigner: MessageSigner? = null

    /** 当前状态（供外部查询） */
    val currentState: ConnectionState get() = state

    // ---------------------------------------------------------------- 主流程

    fun run() {
        var kickReason: String? = null
        var failure: Throwable? = null
        try {
            connect()
            sendHandshake()
            sendLoginStart()
            loginLoop()
            if (state == ConnectionState.CONFIGURING) {
                configurationLoop()
            }
            playLoop()
        } catch (disconnect: ServerDisconnectException) {
            kickReason = disconnect.reason
        } catch (authError: AuthException) {
            failure = authError
        } catch (timeout: SocketTimeoutException) {
            failure = ProtocolException("连接超时：长时间未收到服务器数据")
        } catch (eof: EOFException) {
            failure = ProtocolException("连接已被服务器关闭")
        } catch (t: Throwable) {
            failure = t
        } finally {
            close()
            events.onDisconnected(kickReason, failure)
        }
    }

    override fun close() {
        setState(ConnectionState.DISCONNECTED)
        try {
            socket?.close()
        } catch (_: Exception) {
            // 关闭失败无需处理
        }
        socket = null
    }

    // ---------------------------------------------------------------- 连接与登录

    private fun connect() {
        setState(ConnectionState.CONNECTING)
        val tcp = Socket()
        tcp.tcpNoDelay = true
        tcp.soTimeout = READ_TIMEOUT_MS
        tcp.connect(InetSocketAddress(address.host, address.port), CONNECT_TIMEOUT_MS)
        socket = tcp
        input = BufferedInputStream(tcp.getInputStream(), 16 * 1024)
        output = BufferedOutputStream(tcp.getOutputStream(), 16 * 1024)
        setState(ConnectionState.LOGGING_IN)
    }

    private fun sendHandshake() {
        val payload = PacketWriter()
            .writeVarInt(protocol)
            .writeString(address.handshakeHost)
            .writeShort(address.port)
            .writeVarInt(HANDSHAKE_NEXT_STATE_LOGIN)
            .toByteArray()
        writePacket(LoginPacketIds.HANDSHAKE, payload)
    }

    private fun sendLoginStart() {
        val payload = PacketWriter()
            .writeString(account.username)
            .writeUUID(account.uuid)
            .toByteArray()
        writePacket(LoginPacketIds.LOGIN_START, payload)
    }

    private fun loginLoop() {
        while (true) {
            val (packetId, reader) = readPacket()
            when (packetId) {
                LoginPacketIds.DISCONNECT -> throw ServerDisconnectException(readChatComponent(reader).plain)
                LoginPacketIds.ENCRYPTION_REQUEST -> handleEncryptionRequest(reader)
                LoginPacketIds.LOGIN_SUCCESS -> {
                    handleLoginSuccess(reader)
                    return
                }
                LoginPacketIds.SET_COMPRESSION -> {
                    val threshold = reader.readVarInt()
                    compressionThreshold = if (threshold >= 0) threshold else -1
                }
                LoginPacketIds.LOGIN_PLUGIN_REQUEST -> {
                    val messageId = reader.readVarInt()
                    reader.readString() // 频道名
                    // 不识别任何登录插件请求，按协议回一个 understood=false（Forge 等模组服会用到）
                    writePacket(
                        LOGIN_PLUGIN_RESPONSE,
                        PacketWriter().writeVarInt(messageId).writeBoolean(false).toByteArray(),
                    )
                }
                LoginPacketIds.LOGIN_COOKIE_REQUEST -> {
                    val name = reader.readString()
                    sendCookieResponse(LOGIN_COOKIE_RESPONSE, name, null)
                }
                else -> Unit // 登录阶段忽略未知包
            }
        }
    }

    /**
     * 处理服务端的加密请求：在线模式下先做会话校验，再握手加密。
     *
     * 与 MCC `Protocol18Handler.StartEncryption()` 一致：
     * 1. 计算 serverHash = SHA-1(serverId + 共享密钥 + 服务端公钥)
     * 2. 在线账号调用 sessionserver 的 join 接口
     * 3. 用服务端公钥加密共享密钥与验证令牌后回复（该包本身不加密）
     * 4. 之后所有收发数据都经过 AES-CFB8 加密
     */
    private fun handleEncryptionRequest(reader: PacketReader) {
        onlineMode = true
        val serverId = reader.readString()
        val publicKey = reader.readVarIntPrefixedByteArray()
        val verifyToken = reader.readVarIntPrefixedByteArray()

        // 1.20.6+ 新增字段：服务端是否要求客户端上报会话（我们始终为在线账号上报，等价且更稳妥）
        @Suppress("UNUSED_VARIABLE")
        val shouldAuthenticate = reader.readBoolean()

        val sharedSecret = CryptoUtils.generateSharedSecret()

        val needsSessionCheck = serverId.isNotEmpty() && serverId != "-" && account.isMicrosoft
        if (needsSessionCheck) {
            val accessToken = account.accessToken
                ?: throw AuthException("缺少 Minecraft 访问令牌，无法通过服务器的正版验证")
            val serverHash = CryptoUtils.serverHash(serverId, publicKey, sharedSecret)
            if (!MinecraftAuth.joinServer(accessToken, account.uuid, serverHash)) {
                throw AuthException("正版会话校验失败：服务器拒绝了本次登录（可能是网络问题或令牌失效）")
            }
        }

        val payload = PacketWriter()
            .writeVarIntPrefixedByteArray(CryptoUtils.encryptWithServerKey(publicKey, sharedSecret))
            .writeVarIntPrefixedByteArray(CryptoUtils.encryptWithServerKey(publicKey, verifyToken))
            .toByteArray()

        // 加密响应包必须在启用加密之前以明文发送
        writePacket(LoginPacketIds.ENCRYPTION_RESPONSE, payload)
        encryption = EncryptingStream(sharedSecret)
    }

    private fun handleLoginSuccess(reader: PacketReader) {
        reader.readUUID() // 玩家 UUID（离线模式下由服务端生成）
        reader.readString() // 用户名

        val propertyCount = reader.readVarInt()
        repeat(propertyCount) {
            reader.readString() // 属性名
            reader.readString() // 属性值
            if (reader.readBoolean()) {
                reader.readString() // 签名
            }
        }

        // 1.20.6 - 1.21.1 的 strictErrorHandling 字段（1.21.2 移除）
        if (protocol in 766..767) {
            reader.readBoolean()
        }

        setState(ConnectionState.CONFIGURING)
        writePacket(LoginPacketIds.LOGIN_ACKNOWLEDGED, ByteArray(0))
        sendClientInformation()
    }

    // ---------------------------------------------------------------- 配置阶段

    private fun configurationLoop() {
        while (state == ConnectionState.CONFIGURING) {
            val (packetId, reader) = readPacket()
            when (inConfigById[packetId]) {
                PacketIdTables.InConfig.Disconnect -> throw ServerDisconnectException(readChatComponent(reader).plain)

                PacketIdTables.InConfig.FinishConfiguration -> {
                    writePacket(packetTable.outConfigId(PacketIdTables.OutConfig.FinishConfiguration)!!, ByteArray(0))
                    setState(ConnectionState.PLAYING)
                }

                PacketIdTables.InConfig.KeepAlive ->
                    writePacket(
                        packetTable.outConfigId(PacketIdTables.OutConfig.KeepAlive)!!,
                        reader.readRemaining(),
                    )

                PacketIdTables.InConfig.Ping ->
                    writePacket(
                        packetTable.outConfigId(PacketIdTables.OutConfig.Pong)!!,
                        reader.readRemaining(),
                    )

                PacketIdTables.InConfig.KnownDataPacks -> {
                    // 只回我们自己“认识”的原版数据包，服务端便无需下发注册表数据
                    val count = reader.readVarInt()
                    val vanillaPacks = mutableListOf<Triple<String, String, String>>()
                    repeat(count) {
                        val namespace = reader.readString()
                        val id = reader.readString()
                        val version = reader.readString()
                        if (namespace == "minecraft") {
                            vanillaPacks.add(Triple(namespace, id, version))
                        }
                    }
                    sendKnownDataPacks(vanillaPacks)
                }

                PacketIdTables.InConfig.CodeOfConduct -> {
                    reader.readString() // 行为准则文本
                    packetTable.outConfigId(PacketIdTables.OutConfig.AcceptCodeOfConduct)?.let {
                        writePacket(it, ByteArray(0))
                    }
                }

                PacketIdTables.InConfig.CookieRequest -> {
                    val name = reader.readString()
                    sendCookieResponse(packetTable.outConfigId(PacketIdTables.OutConfig.CookieResponse)!!, name, null)
                }

                // 注册表、特性开关、资源包、服务器链接等与聊天无关，直接忽略
                else -> Unit
            }
        }
    }

    private fun sendKnownDataPacks(packs: List<Triple<String, String, String>>) {
        val writer = PacketWriter().writeVarInt(packs.size)
        for ((namespace, id, version) in packs) {
            writer.writeString(namespace).writeString(id).writeString(version)
        }
        writePacket(packetTable.outConfigId(PacketIdTables.OutConfig.KnownDataPacks)!!, writer.toByteArray())
    }

    /** 发送客户端信息（配置阶段）；1.20.2+ 的服务端依赖该包才会下发 Finish Configuration */
    private fun sendClientInformation() {
        val writer = PacketWriter()
            .writeString(settings.locale)
            .writeByte(settings.viewDistance)
            .writeVarInt(settings.chatMode)
            .writeBoolean(settings.chatColors)
            .writeByte(settings.skinParts)
            .writeVarInt(settings.mainHand)
            .writeBoolean(false) // 启用文本过滤（始终关闭）
            .writeBoolean(true) // 允许服务器列表展示玩家
        if (protocol >= 768) {
            writer.writeVarInt(0) // 1.21.2+ 粒子状态：0=全部
        }
        writePacket(packetTable.outConfigId(PacketIdTables.OutConfig.ClientInformation)!!, writer.toByteArray())
    }

    // ---------------------------------------------------------------- 游戏阶段

    private fun playLoop() {
        while (state == ConnectionState.PLAYING) {
            val (packetId, reader) = readPacket()
            when (inPlayById[packetId]) {
                PacketIdTables.InPlay.KeepAlive ->
                    writePacket(packetTable.outPlayId(PacketIdTables.OutPlay.KeepAlive)!!, reader.readRemaining())

                PacketIdTables.InPlay.Ping ->
                    writePacket(packetTable.outPlayId(PacketIdTables.OutPlay.Pong)!!, reader.readRemaining())

                PacketIdTables.InPlay.Disconnect -> throw ServerDisconnectException(readChatComponent(reader).plain)

                PacketIdTables.InPlay.JoinGame -> handleJoinGame(reader)

                PacketIdTables.InPlay.SystemChat -> handleSystemChat(reader)

                PacketIdTables.InPlay.ChatMessage -> handlePlayerChat(reader)

                PacketIdTables.InPlay.ProfilelessChatMessage -> handleProfilelessChat(reader)

                PacketIdTables.InPlay.StartConfiguration -> {
                    setState(ConnectionState.CONFIGURING)
                    // 服务端要求客户端确认后才重新进入配置阶段
                    packetTable.outPlayId(PacketIdTables.OutPlay.AcknowledgeConfiguration)?.let {
                        writePacket(it, ByteArray(0))
                    }
                }

                else -> Unit // 世界、实体、背包等包在首版中忽略
            }
        }
    }

    private fun handleJoinGame(reader: PacketReader) {
        reader.readInt() // 玩家实体 ID
        sendBrand()
        sendPlayerSession()
        events.onJoined(protocol, onlineMode)
    }

    /** 上报客户端品牌（显示在服务端调试信息里） */
    private fun sendBrand() {
        val packetId = packetTable.outPlayId(PacketIdTables.OutPlay.PluginMessage) ?: return
        val payload = PacketWriter()
            .writeString(BRAND_CHANNEL)
            .writeVarIntPrefixedByteArray(PacketWriter().writeString(settings.brand).toByteArray())
            .toByteArray()
        writePacket(packetId, payload)
    }

    /**
     * 发送聊天会话更新（1.19.3+），把档案公钥告知服务端，之后的签名消息才能被验证。
     */
    private fun sendPlayerSession() {
        if (!onlineMode || profileKeys == null || !signChat) return
        val packetId = packetTable.outPlayId(PacketIdTables.OutPlay.PlayerSession) ?: return

        val signer = MessageSigner(profileKeys, account.uuid, chatUuid)
        messageSigner = signer

        val payload = PacketWriter()
            .writeUUID(chatUuid)
            .writeLong(profileKeys.expirationMillis())
            .writeVarIntPrefixedByteArray(profileKeys.publicKeyDer)
            .writeVarIntPrefixedByteArray(profileKeys.publicKeySignatureV2)
            .toByteArray()
        writePacket(packetId, payload)
    }

    private fun handleSystemChat(reader: PacketReader) {
        val text = readChatComponent(reader)
        val overlay = reader.readBoolean() // 1.19.3+：true 表示动作栏文本
        emitChat(
            ChatLine(
                legacyText = text.legacy,
                plainText = text.plain,
                kind = if (overlay) ChatKind.ACTION_BAR else ChatKind.SYSTEM,
            ),
        )
    }

    private fun handleProfilelessChat(reader: PacketReader) {
        val content = readChatComponent(reader)
        readChatTypeHolder(reader)
        val senderName = readChatComponent(reader)
        if (reader.readBoolean()) {
            reader.readString() // 目标玩家名（团队消息用），首版忽略
        }
        emitChat(
            ChatLine(
                legacyText = content.legacy,
                plainText = content.plain,
                kind = ChatKind.SYSTEM,
                sender = senderName.plain.takeIf { it.isNotBlank() },
            ),
        )
    }

    /**
     * 处理玩家聊天（1.19.3+ 格式）。
     *
     * 字段布局见 MCC `Protocol18Handler.HandlePacket` 中的 `PacketTypesIn.ChatMessage` 分支
     * 与原版 `net.minecraft.network.packet.s2c.play.ChatMessageS2CPacket#write`。
     */
    private fun handlePlayerChat(reader: PacketReader) {
        if (protocol >= 770) {
            reader.readVarInt() // 1.21.5+ 全局消息序号
        }
        val senderUuid = reader.readUUID()
        reader.readVarInt() // 消息序号
        if (reader.readBoolean()) {
            reader.readByteArray(SIGNATURE_LENGTH) // 消息签名（固定 256 字节）
        }

        val message = reader.readString()
        reader.readLong() // 时间戳
        reader.readLong() // 盐

        val previousCount = reader.readVarInt()
        repeat(previousCount) {
            val messageId = reader.readVarInt() - 1
            if (messageId == -1) {
                reader.readByteArray(SIGNATURE_LENGTH)
            }
        }

        val unsignedContent = if (reader.readBoolean()) readChatComponent(reader) else null

        val filterType = reader.readVarInt()
        if (filterType == FILTER_PARTIALLY) {
            val longs = reader.readVarInt()
            repeat(longs) { reader.readLong() }
        }

        readChatTypeHolder(reader)
        val senderName = readChatComponent(reader)
        val targetName = if (reader.readBoolean()) readChatComponent(reader) else null

        val displayName = sequenceOf(senderName.plain, targetName?.plain)
            .filterNotNull()
            .firstOrNull { it.isNotBlank() }
            ?: senderUuid.toString()

        val body = unsignedContent ?: ChatText(message, message)
        emitChat(
            ChatLine(
                legacyText = body.legacy,
                plainText = body.plain,
                kind = ChatKind.PLAYER_CHAT,
                sender = displayName,
            ),
        )
    }

    /**
     * 读取聊天类型 holder（1.21+）。
     *
     * 1.21 起注册表 ID 使用 +1 偏移，0 表示后面跟随内联的类型装饰定义。
     */
    private fun readChatTypeHolder(reader: PacketReader) {
        val encodedId = reader.readVarInt()
        if (protocol < 767 || encodedId > 0) {
            return
        }
        // 内联装饰：翻译键 + 参数类型列表 + 样式，随后还有一个旁白装饰
        repeat(2) {
            reader.readString()
            val parameterCount = reader.readVarInt()
            repeat(parameterCount) { reader.readVarInt() }
            readNbtTagOrNull(reader) // 样式（可选，TAG_End 表示没有样式）
        }
    }

    /** 聊天组件的两种呈现形式 */
    private data class ChatText(val legacy: String, val plain: String)

    /**
     * 读取聊天组件。
     *
     * 1.20.4+ 使用网络 NBT 编码；部分服务端（例如 Hypixel）仍发送 JSON 字符串，
     * 因此与 MCC 一样保留字符串回退路径。
     */
    private fun readChatComponent(reader: PacketReader): ChatText {
        val mark = reader.position()
        val element = try {
            readNetworkNbt(reader)
        } catch (_: ProtocolException) {
            reader.seek(mark)
            null
        }

        if (element != null) {
            return ChatText(ChatParser.toLegacyString(element), ChatParser.toPlainText(element))
        }

        // 回退：按 JSON 字符串（1.20.3 及以前 / 部分服务端）解析，失败时按纯文本处理
        val raw = reader.readString()
        val parsed = Json.parseOrNull(raw)
        return if (parsed != null) {
            ChatText(ChatParser.toLegacyString(parsed), ChatParser.toPlainText(parsed))
        } else {
            ChatText(raw, raw)
        }
    }

    private fun emitChat(line: ChatLine) {
        events.onChat(line)
    }

    // ---------------------------------------------------------------- 发送聊天

    /** 发送聊天内容；以 `/` 开头的内容作为指令发送（与 MCC 行为一致） */
    fun sendChatMessage(text: String) {
        val message = text.trim()
        if (message.isEmpty()) return
        if (message.startsWith("/")) {
            sendChatCommand(message.substring(1))
        } else {
            sendPlayerChat(message)
        }
    }

    private fun sendPlayerChat(message: String) {
        if (message.isEmpty() || message.length > 256) {
            throw ProtocolException("聊天内容长度必须在 1-256 个字符之间")
        }

        val signer = messageSigner
        val writer = PacketWriter().writeString(message)
        val timestamp = System.currentTimeMillis()
        writer.writeLong(timestamp)

        if (signer != null) {
            val salt = generateSalt()
            val signature = signer.sign(message, timestamp, salt)
            writer.writeByteArray(salt) // 盐（long）
            writer.writeBoolean(true)
            writer.writeByteArray(signature) // 1.19.3+ 为定长 256 字节
        } else {
            writer.writeLong(0)
            writer.writeBoolean(false)
        }

        // 1.19.3+：客户端已确认的消息数量与位图（未追踪已读消息，固定为 0）
        writer.writeVarInt(0)
        writer.writeByteArray(EMPTY_ACKNOWLEDGMENT_BITSET)
        if (protocol >= 770) {
            writer.writeByte(0) // 1.21.5+ 校验和：0 表示跳过校验
        }

        writePacket(packetTable.outPlayId(PacketIdTables.OutPlay.ChatMessage)!!, writer.toByteArray())
    }

    private fun sendChatCommand(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return

        if (!onlineMode) {
            // 1.20.6+ 的离线模式使用独立的「未签名指令」包，只包含指令文本
            writePacket(
                packetTable.outPlayId(PacketIdTables.OutPlay.ChatCommand)!!,
                PacketWriter().writeString(trimmed).toByteArray(),
            )
            return
        }

        val writer = PacketWriter().writeString(trimmed)
        writer.writeLong(System.currentTimeMillis())
        writer.writeLong(0) // 盐：不签名时为 0
        writer.writeVarInt(0) // 已签名参数数量：首版不解析指令树，全部不签名
        writer.writeVarInt(0) // 已确认消息数量
        writer.writeByteArray(EMPTY_ACKNOWLEDGMENT_BITSET)
        if (protocol >= 770) {
            writer.writeByte(0)
        }
        writePacket(packetTable.outPlayId(PacketIdTables.OutPlay.SignedChatCommand)!!, writer.toByteArray())
    }

    private fun sendCookieResponse(packetId: Int, name: String, data: ByteArray?) {
        val writer = PacketWriter().writeString(name).writeBoolean(data != null)
        if (data != null) {
            writer.writeVarIntPrefixedByteArray(data)
        }
        writePacket(packetId, writer.toByteArray())
    }

    private fun generateSalt(): ByteArray {
        val salt = ByteArray(8)
        random.nextBytes(salt)
        if (salt.all { it == 0.toByte() }) {
            salt[7] = 1
        }
        return salt
    }

    // ---------------------------------------------------------------- 包收发

    private fun setState(newState: ConnectionState) {
        if (state == newState) return
        state = newState
        events.onStateChanged(newState)
    }

    private fun writePacket(packetId: Int, payload: ByteArray) {
        val full = PacketFraming.encode(packetId, payload, compressionThreshold)
        val toSend = encryption?.encrypt(full) ?: full

        synchronized(writeLock) {
            output.write(toSend)
            output.flush()
        }
    }

    /** 读取一个完整的数据包，返回包 ID 与包体读取器 */
    private fun readPacket(): Pair<Int, PacketReader> {
        val length = readVarIntFromStream()
        if (length < 0) throw ProtocolException("包长度非法：$length")
        if (length == 0) {
            return readPacket()
        }

        val payload = readFully(length)
        return PacketFraming.decodePayload(payload, compressionThreshold)
    }

    private fun readVarIntFromStream(): Int {
        var result = 0
        var shift = 0
        while (true) {
            val current = readByteFromStream()
            result = result or ((current and 0x7F) shl shift)
            if (current and 0x80 == 0) {
                return result
            }
            shift += 7
            if (shift >= 35) {
                throw ProtocolException("VarInt 超过 5 字节上限")
            }
        }
    }

    /** 从套接字读取一个字节，必要时解密 */
    private fun readByteFromStream(): Int {
        val raw = input.read()
        if (raw < 0) throw EOFException("连接已关闭")
        val stream = encryption ?: return raw
        scratch[0] = raw.toByte()
        return stream.decrypt(scratch)[0].toInt() and 0xFF
    }

    /** 读取指定长度的数据，必要时解密 */
    private fun readFully(length: Int): ByteArray {
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            if (read < 0) throw EOFException("连接已关闭")
            offset += read
        }
        return encryption?.decrypt(buffer) ?: buffer
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 180_000
        const val HANDSHAKE_NEXT_STATE_LOGIN = 2
        const val BRAND_CHANNEL = "minecraft:brand"

        /** 登录阶段的包 ID：LoginPluginResponse 与 CookieResponse */
        const val LOGIN_PLUGIN_RESPONSE = 0x02
        const val LOGIN_COOKIE_RESPONSE = 0x04

        const val SIGNATURE_LENGTH = 256
        const val FILTER_PARTIALLY = 2

        /** 未确认任何已读消息时的位图（BitSet(20) = 3 字节全零） */
        val EMPTY_ACKNOWLEDGMENT_BITSET: ByteArray = byteArrayOf(0, 0, 0)
    }
}