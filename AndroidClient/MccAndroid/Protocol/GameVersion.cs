namespace MccAndroid.Protocol;

/// <summary>
/// Packet ids and layout flags for one supported Minecraft Java version.
/// The configuration-state ids are identical for every version in this range,
/// so they stay as constants on this type.
/// </summary>
public sealed record GameVersion(
    string Name,
    int Protocol,
    int PlayInKeepAlive,
    int PlayInDisconnect,
    int PlayInSystemChat,
    int PlayInPlayerChat,
    int PlayInDisguisedChat,
    int PlayInPlayerPositionAndLook,
    int PlayInStartConfiguration,
    int PlayInResourcePack,
    int PlayOutKeepAlive,
    int PlayOutTeleportConfirm,
    int PlayOutChatMessage,
    int PlayOutSignedChatCommand,
    int PlayOutAcknowledgeConfiguration,
    int PlayOutResourcePackStatus,
    bool PlayerChatHasGlobalIndex,
    bool ChatHasChecksumByte)
{
    public const int ConfigInDisconnect = 0x02;
    public const int ConfigInFinishConfiguration = 0x03;
    public const int ConfigInKeepAlive = 0x04;
    public const int ConfigInPing = 0x05;
    public const int ConfigInResourcePack = 0x09;

    public const int ConfigOutClientInformation = 0x00;
    public const int ConfigOutFinishConfiguration = 0x03;
    public const int ConfigOutKeepAlive = 0x04;
    public const int ConfigOutPong = 0x05;
    public const int ConfigOutResourcePackResponse = 0x06;

    public override string ToString() => $"{Name} (protocol {Protocol})";
}

/// <summary>Versions the Android client can talk to.</summary>
public static class GameVersions
{
    public static readonly GameVersion V1_21_8 = new(
        "1.21.8", 772,
        PlayInKeepAlive: 0x26, PlayInDisconnect: 0x1C, PlayInSystemChat: 0x72,
        PlayInPlayerChat: 0x3A, PlayInDisguisedChat: 0x1D, PlayInPlayerPositionAndLook: 0x41,
        PlayInStartConfiguration: 0x6F, PlayInResourcePack: 0x4A,
        PlayOutKeepAlive: 0x1B, PlayOutTeleportConfirm: 0x00, PlayOutChatMessage: 0x08,
        PlayOutSignedChatCommand: 0x07, PlayOutAcknowledgeConfiguration: 0x0F,
        PlayOutResourcePackStatus: 0x30,
        PlayerChatHasGlobalIndex: true, ChatHasChecksumByte: true);

    public static readonly GameVersion V1_21_6 = new(
        "1.21.6", 771,
        PlayInKeepAlive: 0x26, PlayInDisconnect: 0x1C, PlayInSystemChat: 0x72,
        PlayInPlayerChat: 0x3A, PlayInDisguisedChat: 0x1D, PlayInPlayerPositionAndLook: 0x41,
        PlayInStartConfiguration: 0x6F, PlayInResourcePack: 0x4A,
        PlayOutKeepAlive: 0x1B, PlayOutTeleportConfirm: 0x00, PlayOutChatMessage: 0x08,
        PlayOutSignedChatCommand: 0x07, PlayOutAcknowledgeConfiguration: 0x0F,
        PlayOutResourcePackStatus: 0x30,
        PlayerChatHasGlobalIndex: true, ChatHasChecksumByte: true);

    public static readonly GameVersion V1_21_5 = new(
        "1.21.5", 770,
        PlayInKeepAlive: 0x26, PlayInDisconnect: 0x1C, PlayInSystemChat: 0x72,
        PlayInPlayerChat: 0x3A, PlayInDisguisedChat: 0x1D, PlayInPlayerPositionAndLook: 0x41,
        PlayInStartConfiguration: 0x6F, PlayInResourcePack: 0x4A,
        PlayOutKeepAlive: 0x1A, PlayOutTeleportConfirm: 0x00, PlayOutChatMessage: 0x07,
        PlayOutSignedChatCommand: 0x06, PlayOutAcknowledgeConfiguration: 0x0E,
        PlayOutResourcePackStatus: 0x2F,
        PlayerChatHasGlobalIndex: true, ChatHasChecksumByte: true);

    public static readonly GameVersion V1_21_4 = new(
        "1.21.4", 769,
        PlayInKeepAlive: 0x27, PlayInDisconnect: 0x1D, PlayInSystemChat: 0x73,
        PlayInPlayerChat: 0x3B, PlayInDisguisedChat: 0x1E, PlayInPlayerPositionAndLook: 0x42,
        PlayInStartConfiguration: 0x70, PlayInResourcePack: 0x4B,
        PlayOutKeepAlive: 0x1A, PlayOutTeleportConfirm: 0x00, PlayOutChatMessage: 0x07,
        PlayOutSignedChatCommand: 0x06, PlayOutAcknowledgeConfiguration: 0x0E,
        PlayOutResourcePackStatus: 0x2F,
        PlayerChatHasGlobalIndex: false, ChatHasChecksumByte: false);

    public static IReadOnlyList<GameVersion> All { get; } =
        [V1_21_8, V1_21_6, V1_21_5, V1_21_4];

    public static GameVersion Default => V1_21_8;
}
