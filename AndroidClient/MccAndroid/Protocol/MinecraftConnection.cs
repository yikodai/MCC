using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using MccAndroid.Auth;

namespace MccAndroid.Protocol;

/// <summary>One line shown in the in-game chat view.</summary>
public sealed record ChatLine(string Text, bool IsSystem);

/// <summary>
/// A minimal Minecraft Java Edition client: handshake, login (including
/// online-mode encryption), configuration and the play-phase chat loop.
/// </summary>
public sealed class MinecraftConnection : IDisposable
{
    private const int MaxPacketSize = 16 * 1024 * 1024;

    private readonly GameVersion version;
    private readonly MinecraftAccount account;
    private readonly MicrosoftAuthService auth;

    private readonly SemaphoreSlim writeLock = new(1, 1);

    private TcpClient? tcp;
    private Stream stream = Stream.Null;
    private BufferedStream? buffered;
    private CancellationTokenSource? cts;
    private Task? loop;
    private int compressionThreshold = -1;
    private ProtocolState state = ProtocolState.Login;
    private bool onlineMode;
    private bool closedRaised;
    private string serverAddress = string.Empty;

    public MinecraftConnection(GameVersion version, MinecraftAccount account, MicrosoftAuthService auth)
    {
        this.version = version;
        this.account = account;
        this.auth = auth;
    }

    /// <summary>Raised for progress and diagnostic lines.</summary>
    public event Action<string>? Log;

    /// <summary>Raised for every chat/system line received from the server.</summary>
    public event Action<ChatLine>? ChatReceived;

    /// <summary>Raised once the server has put us into the play phase.</summary>
    public event Action? Joined;

    /// <summary>Raised exactly once when the connection ends.</summary>
    public event Action<string>? Closed;

    public bool IsConnected => tcp?.Connected == true && loop is { IsCompleted: false };

    /// <summary>
    /// Resolves the server (including the minecraft SRV record when available)
    /// and performs the full login until the play phase is reached.
    /// </summary>
    public async Task ConnectAsync(
        string host,
        int port,
        IReadOnlyList<IPAddress> dnsServers,
        CancellationToken ct)
    {
        cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        var token = cts.Token;

        serverAddress = host.Trim();
        string connectHost = serverAddress;
        int connectPort = port;

        var srv = await SrvResolver.ResolveMinecraftAsync(host, dnsServers, TimeSpan.FromSeconds(3), token)
            .ConfigureAwait(false);
        if (srv is not null)
        {
            connectHost = srv.Value.Host;
            connectPort = srv.Value.Port;
            Log?.Invoke($"[SRV] {host} -> {connectHost}:{connectPort}");
        }

        tcp = new TcpClient { NoDelay = true };
        await tcp.ConnectAsync(connectHost, connectPort, token).ConfigureAwait(false);
        buffered = new BufferedStream(tcp.GetStream(), 16 * 1024);
        stream = buffered;

        loop = Task.Run(() => RunAsync(token), CancellationToken.None);
    }

    private async Task RunAsync(CancellationToken ct)
    {
        string reason = "连接已关闭";

        try
        {
            await SendHandshakeAsync(ct).ConfigureAwait(false);
            await SendLoginStartAsync(ct).ConfigureAwait(false);
            Log?.Invoke($"已发送登录请求（{version}）");

            while (!ct.IsCancellationRequested)
            {
                var (packetId, reader) = await ReadPacketAsync(ct).ConfigureAwait(false);

                switch (state)
                {
                    case ProtocolState.Login:
                        await HandleLoginPacketAsync(packetId, reader, ct).ConfigureAwait(false);
                        break;
                    case ProtocolState.Configuration:
                        await HandleConfigurationPacketAsync(packetId, reader, ct).ConfigureAwait(false);
                        break;
                    case ProtocolState.Play:
                        await HandlePlayPacketAsync(packetId, reader, ct).ConfigureAwait(false);
                        break;
                }
            }
        }
        catch (OperationCanceledException)
        {
            reason = "已断开连接";
        }
        catch (Exception ex)
        {
            reason = ex.Message;
        }
        finally
        {
            RaiseClosed(reason);
        }
    }

    // ---------------------------------------------------------------- login

    private async Task HandleLoginPacketAsync(int packetId, PacketReader reader, CancellationToken ct)
    {
        switch (packetId)
        {
            case 0x00: // Disconnect
                throw new InvalidOperationException(ReadChatComponent(reader));

            case 0x01: // Encryption request
                await HandleEncryptionRequestAsync(reader, ct).ConfigureAwait(false);
                break;

            case 0x02: // Login success
                reader.ReadUuid();
                string name = reader.ReadString();
                Log?.Invoke($"登录成功：{name}");
                state = ProtocolState.Configuration;
                await SendPacketAsync(0x03, new PacketWriter(), ct).ConfigureAwait(false); // Login acknowledged
                await SendClientInformationAsync(ct).ConfigureAwait(false);
                break;

            case 0x03: // Set compression
                compressionThreshold = reader.ReadVarInt();
                Log?.Invoke($"已启用数据包压缩（阈值 {compressionThreshold}）");
                break;

            case 0x04: // Login plugin request
                {
                    int messageId = reader.ReadVarInt();
                    string channel = reader.ReadString();
                    Log?.Invoke($"[登录插件] {channel}");
                    var writer = new PacketWriter().WriteVarInt(messageId).WriteBool(false);
                    await SendPacketAsync(0x02, writer, ct).ConfigureAwait(false);
                    break;
                }
        }
    }

    private async Task HandleEncryptionRequestAsync(PacketReader reader, CancellationToken ct)
    {
        string serverId = reader.ReadString();
        byte[] publicKey = reader.ReadByteArray();
        byte[] verifyToken = reader.ReadByteArray();

        if (version.Protocol >= 766)
            reader.ReadBool(); // shouldAuthenticate

        onlineMode = true;

        byte[] secretKey = CryptoUtil.GenerateAesKey();
        string serverHash = CryptoUtil.GetServerHash(serverId, publicKey, secretKey);

        if (serverId != "-")
        {
            Log?.Invoke("正在向 Mojang 会话服务器验证...");
            await auth.JoinServerAsync(account.MinecraftAccessToken, account.MinecraftUuid, serverHash, ct)
                .ConfigureAwait(false);
        }

        using var rsa = CryptoUtil.DecodeRsaPublicKey(publicKey);
        byte[] encryptedSecret = rsa.Encrypt(secretKey, RSAEncryptionPadding.Pkcs1);
        byte[] encryptedToken = rsa.Encrypt(verifyToken, RSAEncryptionPadding.Pkcs1);

        var writer = new PacketWriter().WriteByteArray(encryptedSecret).WriteByteArray(encryptedToken);
        await SendPacketAsync(0x01, writer, ct).ConfigureAwait(false);

        // Everything after this point is AES/CFB8 encrypted in both directions.
        stream = new AesCfb8Stream(buffered!, secretKey);
        Log?.Invoke("已启用传输加密");
    }

    private async Task SendHandshakeAsync(CancellationToken ct)
    {
        int remotePort = ((IPEndPoint)tcp!.Client.RemoteEndPoint!).Port;

        var writer = new PacketWriter()
            .WriteVarInt(version.Protocol)
            .WriteString(serverAddress)
            .WriteUShort((ushort)remotePort)
            .WriteVarInt(2); // login

        await SendPacketAsync(0x00, writer, ct).ConfigureAwait(false);
    }

    private async Task SendLoginStartAsync(CancellationToken ct)
    {
        var writer = new PacketWriter()
            .WriteString(account.MinecraftName)
            .WriteUuid(account.Uuid);

        await SendPacketAsync(0x00, writer, ct).ConfigureAwait(false);
    }

    // -------------------------------------------------------- configuration

    private async Task SendClientInformationAsync(CancellationToken ct)
    {
        var writer = new PacketWriter()
            .WriteString("zh_CN")
            .WriteByte(8)                 // view distance
            .WriteVarInt(0)               // chat mode: enabled
            .WriteBool(true)              // chat colors
            .WriteByte(0x7F)              // displayed skin parts
            .WriteVarInt(1)               // main hand: right
            .WriteBool(false)             // text filtering
            .WriteBool(true)              // allow server listings
            .WriteVarInt(0);              // particle status: all (1.21.2+)

        await SendPacketAsync(GameVersion.ConfigOutClientInformation, writer, ct).ConfigureAwait(false);
    }

    private async Task HandleConfigurationPacketAsync(int packetId, PacketReader reader, CancellationToken ct)
    {
        switch (packetId)
        {
            case GameVersion.ConfigInDisconnect:
                throw new InvalidOperationException(ReadChatComponent(reader));

            case GameVersion.ConfigInFinishConfiguration:
                await SendPacketAsync(GameVersion.ConfigOutFinishConfiguration, new PacketWriter(), ct)
                    .ConfigureAwait(false);
                state = ProtocolState.Play;
                Log?.Invoke("已进入游戏世界");
                Joined?.Invoke();
                break;

            case GameVersion.ConfigInKeepAlive:
                await SendPacketAsync(GameVersion.ConfigOutKeepAlive, reader.ReadRest(), ct).ConfigureAwait(false);
                break;

            case GameVersion.ConfigInPing:
                await SendPacketAsync(GameVersion.ConfigOutPong, reader.ReadRest(), ct).ConfigureAwait(false);
                break;

            case GameVersion.ConfigInResourcePack:
                await AcceptResourcePackAsync(reader, isPlayState: false, ct).ConfigureAwait(false);
                break;
        }
    }

    // ----------------------------------------------------------------- play

    private async Task HandlePlayPacketAsync(int packetId, PacketReader reader, CancellationToken ct)
    {
        if (packetId == version.PlayInKeepAlive)
        {
            long id = reader.ReadLong();
            await SendPacketAsync(version.PlayOutKeepAlive, new PacketWriter().WriteLong(id), ct).ConfigureAwait(false);
            return;
        }

        if (packetId == version.PlayInDisconnect)
            throw new InvalidOperationException(ReadChatComponent(reader));

        if (packetId == version.PlayInSystemChat)
        {
            string text = ReadChatComponent(reader);
            bool overlay = reader.ReadBool();
            if (!overlay)
                ChatReceived?.Invoke(new ChatLine(text, IsSystem: true));
            return;
        }

        if (packetId == version.PlayInPlayerChat)
        {
            var line = ReadPlayerChat(reader);
            if (line is not null)
                ChatReceived?.Invoke(line);
            return;
        }

        if (packetId == version.PlayInDisguisedChat)
        {
            var line = ReadDisguisedChat(reader);
            if (line is not null)
                ChatReceived?.Invoke(line);
            return;
        }

        if (packetId == version.PlayInPlayerPositionAndLook)
        {
            int teleportId = reader.ReadVarInt();
            await SendPacketAsync(version.PlayOutTeleportConfirm, new PacketWriter().WriteVarInt(teleportId), ct)
                .ConfigureAwait(false);
            return;
        }

        if (packetId == version.PlayInStartConfiguration)
        {
            state = ProtocolState.Configuration;
            await SendPacketAsync(version.PlayOutAcknowledgeConfiguration, new PacketWriter(), ct).ConfigureAwait(false);
            return;
        }

        if (packetId == version.PlayInResourcePack)
            await AcceptResourcePackAsync(reader, isPlayState: true, ct).ConfigureAwait(false);
    }

    private ChatLine? ReadPlayerChat(PacketReader reader)
    {
        if (version.PlayerChatHasGlobalIndex)
            reader.ReadVarInt(); // global index

        reader.ReadUuid();                       // sender uuid
        reader.ReadVarInt();                     // index
        if (reader.ReadBool())                   // has signature
            reader.ReadBytes(256);

        string message = reader.ReadString();
        reader.ReadLong();                       // timestamp
        reader.ReadLong();                       // salt

        int previousCount = reader.ReadVarInt();
        for (int i = 0; i < previousCount; i++)
        {
            int messageId = reader.ReadVarInt() - 1;
            if (messageId == -1)
                reader.ReadBytes(256);
        }

        string? unsignedContent = reader.ReadBool() ? ReadChatComponent(reader) : null;

        int filterType = reader.ReadVarInt();
        if (filterType == 2) // partially filtered
            ReadUnsignedLongArray(reader);

        ReadChatTypeHolder(reader);

        string sender = ReadChatComponent(reader);
        string? target = reader.ReadBool() ? ReadChatComponent(reader) : null;

        string body = unsignedContent ?? message;
        string prefix = string.IsNullOrWhiteSpace(sender) ? string.Empty : sender;

        if (!string.IsNullOrWhiteSpace(target))
            prefix = prefix.Length > 0 ? $"{prefix}->{target}" : target;

        string text = prefix.Length > 0 ? $"<{prefix}> {body}" : body;
        return new ChatLine(text, IsSystem: false);
    }

    private ChatLine? ReadDisguisedChat(PacketReader reader)
    {
        string message = ReadChatComponent(reader);
        ReadChatTypeHolder(reader);
        string sender = ReadChatComponent(reader);
        string? target = reader.ReadBool() ? ReadChatComponent(reader) : null;

        if (string.IsNullOrWhiteSpace(message))
            return null;

        string prefix = string.IsNullOrWhiteSpace(sender) ? string.Empty : sender;
        if (!string.IsNullOrWhiteSpace(target))
            prefix = prefix.Length > 0 ? $"{prefix}->{target}" : target;

        string text = prefix.Length > 0 ? $"<{prefix}> {message}" : message;
        return new ChatLine(text, IsSystem: false);
    }

    private void ReadChatTypeHolder(PacketReader reader)
    {
        int encodedId = reader.ReadVarInt();

        if (version.Protocol < 767)
            return;

        if (encodedId > 0)
            return;

        // Direct (non-registry) chat type: chat decoration + narration decoration.
        ReadChatTypeDecoration(reader);
        ReadChatTypeDecoration(reader);
    }

    private static void ReadChatTypeDecoration(PacketReader reader)
    {
        reader.ReadString(); // translation key
        int parameterCount = reader.ReadVarInt();
        for (int i = 0; i < parameterCount; i++)
            reader.ReadVarInt();

        Nbt.ReadNetworkRoot(reader); // style
    }

    private static void ReadUnsignedLongArray(PacketReader reader)
    {
        int length = reader.ReadVarInt();
        for (int i = 0; i < length; i++)
            reader.ReadLong();
    }

    private async Task AcceptResourcePackAsync(PacketReader reader, bool isPlayState, CancellationToken ct)
    {
        Guid packId = Guid.Empty;
        if (version.Protocol >= 764)
            packId = reader.ReadUuid();

        reader.ReadString(); // url
        reader.ReadString(); // hash
        bool forced = reader.ReadBool();
        if (reader.ReadBool())
            ReadChatComponent(reader); // prompt

        Log?.Invoke(forced ? "服务器强制要求资源包，已按文本客户端方式应答" : "已忽略服务器资源包");

        int outId = isPlayState ? version.PlayOutResourcePackStatus : GameVersion.ConfigOutResourcePackResponse;

        // 3 = accepted, 0 = successfully loaded
        await SendPacketAsync(outId, new PacketWriter().WriteUuid(packId).WriteVarInt(3), ct).ConfigureAwait(false);
        await SendPacketAsync(outId, new PacketWriter().WriteUuid(packId).WriteVarInt(0), ct).ConfigureAwait(false);
    }

    // --------------------------------------------------------------- output

    /// <summary>
    /// Sends a chat message, or a command when the text starts with '/'.
    /// Chat signing is intentionally not implemented: messages are sent in the
    /// unsigned form, which servers accept unless secure chat is enforced.
    /// </summary>
    public async Task SendChatAsync(string text, CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(text))
            return;

        text = text.Trim();
        if (text.Length > 256)
            text = text[..256];

        if (text.StartsWith('/'))
        {
            await SendCommandAsync(text[1..], ct).ConfigureAwait(false);
            return;
        }

        var writer = new PacketWriter().WriteString(text);
        AppendUnsignedChatTail(writer, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
        await SendPacketAsync(version.PlayOutChatMessage, writer, ct).ConfigureAwait(false);
    }

    private async Task SendCommandAsync(string command, CancellationToken ct)
    {
        if (!onlineMode)
        {
            // Offline servers accept the short, unsigned command packet.
            await SendPacketAsync(version.PlayOutSignedChatCommand, new PacketWriter().WriteString(command), ct)
                .ConfigureAwait(false);
            return;
        }

        var writer = new PacketWriter().WriteString(command);
        AppendUnsignedChatTail(writer, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
        await SendPacketAsync(version.PlayOutSignedChatCommand, writer, ct).ConfigureAwait(false);
    }

    private void AppendUnsignedChatTail(PacketWriter writer, long timestamp)
    {
        writer.WriteLong(timestamp);
        writer.WriteLong(0);     // salt: unused without a signature
        writer.WriteBool(false); // no signature

        writer.WriteVarInt(0);   // acknowledged message count
        writer.WriteRaw(new byte[3]); // acknowledged bitset (20 bits)

        if (version.ChatHasChecksumByte)
            writer.WriteByte(0); // skip signature verification
    }

    private async Task SendPacketAsync(int packetId, PacketWriter payload, CancellationToken ct)
    {
        await SendPacketAsync(packetId, payload.ToArray(), ct).ConfigureAwait(false);
    }

    private async Task SendPacketAsync(int packetId, byte[] payload, CancellationToken ct)
    {
        var packet = new PacketWriter().WriteVarInt(packetId).WriteRaw(payload).ToArray();

        if (compressionThreshold >= 0)
        {
            if (packet.Length >= compressionThreshold)
            {
                packet = new PacketWriter()
                    .WriteVarInt(packet.Length)
                    .WriteRaw(Zlib.Compress(packet))
                    .ToArray();
            }
            else
            {
                packet = new PacketWriter().WriteVarInt(0).WriteRaw(packet).ToArray();
            }
        }

        byte[] frame = new PacketWriter().WriteVarInt(packet.Length).WriteRaw(packet).ToArray();

        await writeLock.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            await stream.WriteAsync(frame, ct).ConfigureAwait(false);
            await stream.FlushAsync(ct).ConfigureAwait(false);
        }
        finally
        {
            writeLock.Release();
        }
    }

    // ---------------------------------------------------------------- input

    private async Task<(int PacketId, PacketReader Reader)> ReadPacketAsync(CancellationToken ct)
    {
        int length = ReadVarInt();
        if (length <= 0 || length > MaxPacketSize)
            throw new InvalidDataException($"收到非法数据包长度：{length}");

        byte[] frame = await ReadExactlyAsync(length, ct).ConfigureAwait(false);
        var reader = new PacketReader(frame);

        if (compressionThreshold >= 0)
        {
            int inflatedSize = reader.ReadVarInt();
            if (inflatedSize != 0)
            {
                byte[] inflated = Zlib.Decompress(reader.ReadRest(), inflatedSize);
                reader = new PacketReader(inflated);
            }
        }

        int packetId = reader.ReadVarInt();
        return (packetId, reader);
    }

    private int ReadVarInt()
    {
        int value = 0;
        int shift = 0;

        while (true)
        {
            int current = stream.ReadByte();
            if (current < 0)
                throw new IOException("连接已被服务器关闭");

            value |= (current & 0x7F) << shift;
            if ((current & 0x80) == 0)
                return value;

            shift += 7;
            if (shift >= 35)
                throw new InvalidDataException("VarInt 长度非法");
        }
    }

    private async Task<byte[]> ReadExactlyAsync(int count, CancellationToken ct)
    {
        byte[] buffer = new byte[count];
        int offset = 0;

        while (offset < count)
        {
            int read = await stream.ReadAsync(buffer.AsMemory(offset, count - offset), ct).ConfigureAwait(false);
            if (read <= 0)
                throw new IOException("连接已被服务器关闭");

            offset += read;
        }

        return buffer;
    }

    /// <summary>
    /// Reads a text component. Vanilla 1.20.4+ sends network NBT; a JSON string
    /// fallback is kept for servers that deviate from the vanilla format.
    /// </summary>
    private static string ReadChatComponent(PacketReader reader)
    {
        int start = reader.Position;

        try
        {
            return TextComponent.ToPlainText(Nbt.ReadNetworkRoot(reader));
        }
        catch (Exception)
        {
            reader.Position = start;
            return TextComponent.ToPlainText(reader.ReadString());
        }
    }

    private void RaiseClosed(string reason)
    {
        if (closedRaised)
            return;

        closedRaised = true;
        Closed?.Invoke(reason);
    }

    public void Dispose()
    {
        RaiseClosed("已断开连接");

        try
        {
            cts?.Cancel();
        }
        catch (ObjectDisposedException)
        {
            // already gone
        }

        try
        {
            tcp?.Close();
        }
        catch (Exception)
        {
            // best effort
        }

        stream = Stream.Null;
        cts?.Dispose();
        cts = null;
        tcp = null;
        writeLock.Dispose();
    }

    private enum ProtocolState
    {
        Login,
        Configuration,
        Play,
    }
}
