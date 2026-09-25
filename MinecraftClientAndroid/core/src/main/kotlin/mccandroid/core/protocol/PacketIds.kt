// 本文件由 tools/gen_packet_ids.py 从 MCC 上游 PacketPalette*.cs 自动生成，请勿手工修改。
// 重新生成：python3 tools/gen_packet_ids.py

package mccandroid.core.protocol

import mccandroid.core.protocol.PacketIdTables.InConfig
import mccandroid.core.protocol.PacketIdTables.InPlay
import mccandroid.core.protocol.PacketIdTables.OutConfig
import mccandroid.core.protocol.PacketIdTables.OutPlay

/** 包 ID 表，来源：MCC PacketPalettes/PacketPalette1206.cs */
internal object PacketPalette1206 : PacketIdTable {
    override val inPlay: Map<InPlay, Int> = mapOf(
        InPlay.KeepAlive to 0x26,
        InPlay.Ping to 0x35,
        InPlay.Disconnect to 0x1D,
        InPlay.JoinGame to 0x2B,
        InPlay.SystemChat to 0x6C,
        InPlay.ChatMessage to 0x39,
        InPlay.ProfilelessChatMessage to 0x1E,
        InPlay.PluginMessage to 0x19,
        InPlay.StartConfiguration to 0x69,
        InPlay.TabComplete to 0x10,
        InPlay.PlayerInfo to 0x3E,
    )

    override val outPlay: Map<OutPlay, Int> = mapOf(
        OutPlay.KeepAlive to 0x18,
        OutPlay.Pong to 0x27,
        OutPlay.ChatMessage to 0x06,
        OutPlay.ChatCommand to 0x04,
        OutPlay.SignedChatCommand to 0x05,
        OutPlay.ClientSettings to 0x0A,
        OutPlay.PluginMessage to 0x12,
        OutPlay.ClientStatus to 0x09,
        OutPlay.PlayerSession to 0x07,
        OutPlay.AcknowledgeConfiguration to 0x0C,
    )

    override val inConfig: Map<InConfig, Int> = mapOf(
        InConfig.Disconnect to 0x02,
        InConfig.FinishConfiguration to 0x03,
        InConfig.KeepAlive to 0x04,
        InConfig.Ping to 0x05,
        InConfig.KnownDataPacks to 0x0E,
        InConfig.CookieRequest to 0x00,
        InConfig.PluginMessage to 0x01,
    )

    override val outConfig: Map<OutConfig, Int> = mapOf(
        OutConfig.ClientInformation to 0x00,
        OutConfig.CookieResponse to 0x01,
        OutConfig.PluginMessage to 0x02,
        OutConfig.FinishConfiguration to 0x03,
        OutConfig.KeepAlive to 0x04,
        OutConfig.Pong to 0x05,
        OutConfig.KnownDataPacks to 0x07,
    )
}

/** 包 ID 表，来源：MCC PacketPalettes/PacketPalette121.cs */
internal object PacketPalette121 : PacketIdTable {
    override val inPlay: Map<InPlay, Int> = mapOf(
        InPlay.KeepAlive to 0x26,
        InPlay.Ping to 0x35,
        InPlay.Disconnect to 0x1D,
        InPlay.JoinGame to 0x2B,
        InPlay.SystemChat to 0x6C,
        InPlay.ChatMessage to 0x39,
        InPlay.ProfilelessChatMessage to 0x1E,
        InPlay.PluginMessage to 0x19,
        InPlay.StartConfiguration to 0x69,
        InPlay.TabComplete to 0x10,
        InPlay.PlayerInfo to 0x3E,
    )

    override val outPlay: Map<OutPlay, Int> = mapOf(
        OutPlay.KeepAlive to 0x18,
        OutPlay.Pong to 0x27,
        OutPlay.ChatMessage to 0x06,
        OutPlay.ChatCommand to 0x04,
        OutPlay.SignedChatCommand to 0x05,
        OutPlay.ClientSettings to 0x0A,
        OutPlay.PluginMessage to 0x12,
        OutPlay.ClientStatus to 0x09,
        OutPlay.PlayerSession to 0x07,
        OutPlay.AcknowledgeConfiguration to 0x0C,
    )

    override val inConfig: Map<InConfig, Int> = mapOf(
        InConfig.Disconnect to 0x02,
        InConfig.FinishConfiguration to 0x03,
        InConfig.KeepAlive to 0x04,
        InConfig.Ping to 0x05,
        InConfig.KnownDataPacks to 0x0E,
        InConfig.CookieRequest to 0x00,
        InConfig.PluginMessage to 0x01,
    )

    override val outConfig: Map<OutConfig, Int> = mapOf(
        OutConfig.ClientInformation to 0x00,
        OutConfig.CookieResponse to 0x01,
        OutConfig.PluginMessage to 0x02,
        OutConfig.FinishConfiguration to 0x03,
        OutConfig.KeepAlive to 0x04,
        OutConfig.Pong to 0x05,
        OutConfig.KnownDataPacks to 0x07,
    )
}

/** 包 ID 表，来源：MCC PacketPalettes/PacketPalette1212.cs */
internal object PacketPalette1212 : PacketIdTable {
    override val inPlay: Map<InPlay, Int> = mapOf(
        InPlay.KeepAlive to 0x27,
        InPlay.Ping to 0x37,
        InPlay.Disconnect to 0x1D,
        InPlay.JoinGame to 0x2C,
        InPlay.SystemChat to 0x73,
        InPlay.ChatMessage to 0x3B,
        InPlay.ProfilelessChatMessage to 0x1E,
        InPlay.PluginMessage to 0x19,
        InPlay.StartConfiguration to 0x70,
        InPlay.TabComplete to 0x10,
        InPlay.PlayerInfo to 0x40,
    )

    override val outPlay: Map<OutPlay, Int> = mapOf(
        OutPlay.KeepAlive to 0x1A,
        OutPlay.Pong to 0x29,
        OutPlay.ChatMessage to 0x07,
        OutPlay.ChatCommand to 0x05,
        OutPlay.SignedChatCommand to 0x06,
        OutPlay.ClientSettings to 0x0C,
        OutPlay.PluginMessage to 0x14,
        OutPlay.ClientStatus to 0x0A,
        OutPlay.PlayerSession to 0x08,
        OutPlay.AcknowledgeConfiguration to 0x0E,
    )

    override val inConfig: Map<InConfig, Int> = mapOf(
        InConfig.Disconnect to 0x02,
        InConfig.FinishConfiguration to 0x03,
        InConfig.KeepAlive to 0x04,
        InConfig.Ping to 0x05,
        InConfig.KnownDataPacks to 0x0E,
        InConfig.CookieRequest to 0x00,
        InConfig.PluginMessage to 0x01,
    )

    override val outConfig: Map<OutConfig, Int> = mapOf(
        OutConfig.ClientInformation to 0x00,
        OutConfig.CookieResponse to 0x01,
        OutConfig.PluginMessage to 0x02,
        OutConfig.FinishConfiguration to 0x03,
        OutConfig.KeepAlive to 0x04,
        OutConfig.Pong to 0x05,
        OutConfig.KnownDataPacks to 0x07,
    )
}

/** 包 ID 表，来源：MCC PacketPalettes/PacketPalette1214.cs */
internal object PacketPalette1214 : PacketIdTable {
    override val inPlay: Map<InPlay, Int> = mapOf(
        InPlay.KeepAlive to 0x27,
        InPlay.Ping to 0x37,
        InPlay.Disconnect to 0x1D,
        InPlay.JoinGame to 0x2C,
        InPlay.SystemChat to 0x73,
        InPlay.ChatMessage to 0x3B,
        InPlay.ProfilelessChatMessage to 0x1E,
        InPlay.PluginMessage to 0x19,
        InPlay.StartConfiguration to 0x70,
        InPlay.TabComplete to 0x10,
        InPlay.PlayerInfo to 0x40,
    )

    override val outPlay: Map<OutPlay, Int> = mapOf(
        OutPlay.KeepAlive to 0x1A,
        OutPlay.Pong to 0x2B,
        OutPlay.ChatMessage to 0x07,
        OutPlay.ChatCommand to 0x05,
        OutPlay.SignedChatCommand to 0x06,
        OutPlay.ClientSettings to 0x0C,
        OutPlay.PluginMessage to 0x14,
        OutPlay.ClientStatus to 0x0A,
        OutPlay.PlayerSession to 0x08,
        OutPlay.AcknowledgeConfiguration to 0x0E,
    )

    override val inConfig: Map<InConfig, Int> = mapOf(
        InConfig.Disconnect to 0x02,
        InConfig.FinishConfiguration to 0x03,
        InConfig.KeepAlive to 0x04,
        InConfig.Ping to 0x05,
        InConfig.KnownDataPacks to 0x0E,
        InConfig.CookieRequest to 0x00,
        InConfig.PluginMessage to 0x01,
    )

    override val outConfig: Map<OutConfig, Int> = mapOf(
        OutConfig.ClientInformation to 0x00,
        OutConfig.CookieResponse to 0x01,
        OutConfig.PluginMessage to 0x02,
        OutConfig.FinishConfiguration to 0x03,
        OutConfig.KeepAlive to 0x04,
        OutConfig.Pong to 0x05,
        OutConfig.KnownDataPacks to 0x07,
    )
}

/** 包 ID 表，来源：MCC PacketPalettes/PacketPalette1215.cs */
internal object PacketPalette1215 : PacketIdTable {
    override val inPlay: Map<InPlay, Int> = mapOf(
        InPlay.KeepAlive to 0x26,
        InPlay.Ping to 0x36,
        InPlay.Disconnect to 0x1C,
        InPlay.JoinGame to 0x2B,
        InPlay.SystemChat to 0x72,
        InPlay.ChatMessage to 0x3A,
        InPlay.ProfilelessChatMessage to 0x1D,
        InPlay.PluginMessage to 0x18,
        InPlay.StartConfiguration to 0x6F,
        InPlay.TabComplete to 0x0F,
        InPlay.PlayerInfo to 0x3F,
    )

    override val outPlay: Map<OutPlay, Int> = mapOf(
        OutPlay.KeepAlive to 0x1A,
        OutPlay.Pong to 0x2B,
        OutPlay.ChatMessage to 0x07,
        OutPlay.ChatCommand to 0x05,
        OutPlay.SignedChatCommand to 0x06,
        OutPlay.ClientSettings to 0x0C,
        OutPlay.PluginMessage to 0x14,
        OutPlay.ClientStatus to 0x0A,
        OutPlay.PlayerSession to 0x08,
        OutPlay.AcknowledgeConfiguration to 0x0E,
    )

    override val inConfig: Map<InConfig, Int> = mapOf(
        InConfig.Disconnect to 0x02,
        InConfig.FinishConfiguration to 0x03,
        InConfig.KeepAlive to 0x04,
        InConfig.Ping to 0x05,
        InConfig.KnownDataPacks to 0x0E,
        InConfig.CookieRequest to 0x00,
        InConfig.PluginMessage to 0x01,
    )

    override val outConfig: Map<OutConfig, Int> = mapOf(
        OutConfig.ClientInformation to 0x00,
        OutConfig.CookieResponse to 0x01,
        OutConfig.PluginMessage to 0x02,
        OutConfig.FinishConfiguration to 0x03,
        OutConfig.KeepAlive to 0x04,
        OutConfig.Pong to 0x05,
        OutConfig.KnownDataPacks to 0x07,
    )
}

/** 包 ID 表，来源：MCC PacketPalettes/PacketPalette1216.cs */
internal object PacketPalette1216 : PacketIdTable {
    override val inPlay: Map<InPlay, Int> = mapOf(
        InPlay.KeepAlive to 0x26,
        InPlay.Ping to 0x36,
        InPlay.Disconnect to 0x1C,
        InPlay.JoinGame to 0x2B,
        InPlay.SystemChat to 0x72,
        InPlay.ChatMessage to 0x3A,
        InPlay.ProfilelessChatMessage to 0x1D,
        InPlay.PluginMessage to 0x18,
        InPlay.StartConfiguration to 0x6F,
        InPlay.TabComplete to 0x0F,
        InPlay.PlayerInfo to 0x3F,
    )

    override val outPlay: Map<OutPlay, Int> = mapOf(
        OutPlay.KeepAlive to 0x1B,
        OutPlay.Pong to 0x2C,
        OutPlay.ChatMessage to 0x08,
        OutPlay.ChatCommand to 0x06,
        OutPlay.SignedChatCommand to 0x07,
        OutPlay.ClientSettings to 0x0D,
        OutPlay.PluginMessage to 0x15,
        OutPlay.ClientStatus to 0x0B,
        OutPlay.PlayerSession to 0x09,
        OutPlay.AcknowledgeConfiguration to 0x0F,
    )

    override val inConfig: Map<InConfig, Int> = mapOf(
        InConfig.Disconnect to 0x02,
        InConfig.FinishConfiguration to 0x03,
        InConfig.KeepAlive to 0x04,
        InConfig.Ping to 0x05,
        InConfig.KnownDataPacks to 0x0E,
        InConfig.CookieRequest to 0x00,
        InConfig.PluginMessage to 0x01,
    )

    override val outConfig: Map<OutConfig, Int> = mapOf(
        OutConfig.ClientInformation to 0x00,
        OutConfig.CookieResponse to 0x01,
        OutConfig.PluginMessage to 0x02,
        OutConfig.FinishConfiguration to 0x03,
        OutConfig.KeepAlive to 0x04,
        OutConfig.Pong to 0x05,
        OutConfig.KnownDataPacks to 0x07,
    )
}

/** 包 ID 表，来源：MCC PacketPalettes/PacketPalette1219.cs */
internal object PacketPalette1219 : PacketIdTable {
    override val inPlay: Map<InPlay, Int> = mapOf(
        InPlay.KeepAlive to 0x2B,
        InPlay.Ping to 0x3B,
        InPlay.Disconnect to 0x20,
        InPlay.JoinGame to 0x30,
        InPlay.SystemChat to 0x77,
        InPlay.ChatMessage to 0x3F,
        InPlay.ProfilelessChatMessage to 0x21,
        InPlay.PluginMessage to 0x18,
        InPlay.StartConfiguration to 0x74,
        InPlay.TabComplete to 0x0F,
        InPlay.PlayerInfo to 0x44,
    )

    override val outPlay: Map<OutPlay, Int> = mapOf(
        OutPlay.KeepAlive to 0x1B,
        OutPlay.Pong to 0x2C,
        OutPlay.ChatMessage to 0x08,
        OutPlay.ChatCommand to 0x06,
        OutPlay.SignedChatCommand to 0x07,
        OutPlay.ClientSettings to 0x0D,
        OutPlay.PluginMessage to 0x15,
        OutPlay.ClientStatus to 0x0B,
        OutPlay.PlayerSession to 0x09,
        OutPlay.AcknowledgeConfiguration to 0x0F,
    )

    override val inConfig: Map<InConfig, Int> = mapOf(
        InConfig.Disconnect to 0x02,
        InConfig.FinishConfiguration to 0x03,
        InConfig.KeepAlive to 0x04,
        InConfig.Ping to 0x05,
        InConfig.KnownDataPacks to 0x0E,
        InConfig.CodeOfConduct to 0x13,
        InConfig.CookieRequest to 0x00,
        InConfig.PluginMessage to 0x01,
    )

    override val outConfig: Map<OutConfig, Int> = mapOf(
        OutConfig.ClientInformation to 0x00,
        OutConfig.CookieResponse to 0x01,
        OutConfig.PluginMessage to 0x02,
        OutConfig.FinishConfiguration to 0x03,
        OutConfig.KeepAlive to 0x04,
        OutConfig.Pong to 0x05,
        OutConfig.KnownDataPacks to 0x07,
        OutConfig.AcceptCodeOfConduct to 0x09,
    )
}

/** 包 ID 表，来源：MCC PacketPalettes/PacketPalette261.cs */
internal object PacketPalette261 : PacketIdTable {
    override val inPlay: Map<InPlay, Int> = mapOf(
        InPlay.KeepAlive to 0x2C,
        InPlay.Ping to 0x3D,
        InPlay.Disconnect to 0x20,
        InPlay.JoinGame to 0x31,
        InPlay.SystemChat to 0x79,
        InPlay.ChatMessage to 0x41,
        InPlay.ProfilelessChatMessage to 0x21,
        InPlay.PluginMessage to 0x18,
        InPlay.StartConfiguration to 0x76,
        InPlay.TabComplete to 0x0F,
        InPlay.PlayerInfo to 0x46,
    )

    override val outPlay: Map<OutPlay, Int> = mapOf(
        OutPlay.KeepAlive to 0x1C,
        OutPlay.Pong to 0x2D,
        OutPlay.ChatMessage to 0x09,
        OutPlay.ChatCommand to 0x07,
        OutPlay.SignedChatCommand to 0x08,
        OutPlay.ClientSettings to 0x0E,
        OutPlay.PluginMessage to 0x16,
        OutPlay.ClientStatus to 0x0C,
        OutPlay.PlayerSession to 0x0A,
        OutPlay.AcknowledgeConfiguration to 0x10,
    )

    override val inConfig: Map<InConfig, Int> = mapOf(
        InConfig.Disconnect to 0x02,
        InConfig.FinishConfiguration to 0x03,
        InConfig.KeepAlive to 0x04,
        InConfig.Ping to 0x05,
        InConfig.KnownDataPacks to 0x0E,
        InConfig.CodeOfConduct to 0x13,
        InConfig.CookieRequest to 0x00,
        InConfig.PluginMessage to 0x01,
    )

    override val outConfig: Map<OutConfig, Int> = mapOf(
        OutConfig.ClientInformation to 0x00,
        OutConfig.CookieResponse to 0x01,
        OutConfig.PluginMessage to 0x02,
        OutConfig.FinishConfiguration to 0x03,
        OutConfig.KeepAlive to 0x04,
        OutConfig.Pong to 0x05,
        OutConfig.KnownDataPacks to 0x07,
        OutConfig.AcceptCodeOfConduct to 0x09,
    )
}

/** 协议号 -> 包 ID 表，来源：MCC ProtocolType18Handler.GetTypeHandler() */
internal fun packetIdTableForProtocol(protocol: Int): PacketIdTable = when (protocol) {
    766 -> PacketPalette1206 // 1.20.5 - 1.20.6
    767 -> PacketPalette121 // 1.21 - 1.21.1
    768 -> PacketPalette1212 // 1.21.2 - 1.21.3
    769 -> PacketPalette1214 // 1.21.4
    770 -> PacketPalette1215 // 1.21.5
    771 -> PacketPalette1216 // 1.21.6
    772 -> PacketPalette1216 // 1.21.7 - 1.21.8
    773 -> PacketPalette1219 // 1.21.9 - 1.21.10
    774 -> PacketPalette1219 // 1.21.11
    775 -> PacketPalette261 // 26.1
    776 -> PacketPalette261 // 26.2
    else -> throw IllegalArgumentException("不支持的协议号：$protocol")
}
