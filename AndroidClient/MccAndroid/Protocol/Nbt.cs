using System.Text;

namespace MccAndroid.Protocol;

/// <summary>
/// Minimal NBT reader for the "network NBT" format used since 1.20.2:
/// the root tag has no name, and may be either TAG_Compound or TAG_String.
/// </summary>
internal static class Nbt
{
    private const int TagEnd = 0;
    private const int TagByte = 1;
    private const int TagShort = 2;
    private const int TagInt = 3;
    private const int TagLong = 4;
    private const int TagFloat = 5;
    private const int TagDouble = 6;
    private const int TagByteArray = 7;
    private const int TagString = 8;
    private const int TagList = 9;
    private const int TagCompound = 10;
    private const int TagIntArray = 11;
    private const int TagLongArray = 12;

    /// <summary>
    /// Reads a root tag. Returns a string for TAG_String, a dictionary for
    /// TAG_Compound, or null for TAG_End.
    /// </summary>
    public static object? ReadNetworkRoot(PacketReader reader)
    {
        int type = reader.ReadByte();

        if (type == TagEnd)
            return null;

        if (type == TagString)
            return reader.ReadString();

        if (type != TagCompound)
            throw new InvalidDataException($"Unsupported NBT root tag type {type}.");

        var root = new Dictionary<string, object?>();
        while (true)
        {
            int fieldType = reader.ReadByte();
            if (fieldType == TagEnd)
                return root;

            string name = reader.ReadString();
            root[name] = ReadField(reader, fieldType);
        }
    }

    private static object? ReadField(PacketReader reader, int type)
    {
        switch (type)
        {
            case TagByte:
                return (sbyte)reader.ReadByte();
            case TagShort:
                return reader.ReadShort();
            case TagInt:
                return reader.ReadInt();
            case TagLong:
                return reader.ReadLong();
            case TagFloat:
                return reader.ReadFloat();
            case TagDouble:
                return reader.ReadDouble();
            case TagByteArray:
                return reader.ReadBytes(reader.ReadInt());
            case TagString:
                return reader.ReadString();
            case TagList:
                {
                    int itemType = reader.ReadByte();
                    int length = reader.ReadInt();
                    var items = new List<object?>(Math.Max(0, length));
                    for (int i = 0; i < length; i++)
                        items.Add(ReadField(reader, itemType));
                    return items;
                }
            case TagCompound:
                {
                    var compound = new Dictionary<string, object?>();
                    while (true)
                    {
                        int fieldType = reader.ReadByte();
                        if (fieldType == TagEnd)
                            return compound;

                        string name = reader.ReadString();
                        compound[name] = ReadField(reader, fieldType);
                    }
                }
            case TagIntArray:
                {
                    int length = reader.ReadInt();
                    var values = new int[length];
                    for (int i = 0; i < length; i++)
                        values[i] = reader.ReadInt();
                    return values;
                }
            case TagLongArray:
                {
                    int length = reader.ReadInt();
                    var values = new long[length];
                    for (int i = 0; i < length; i++)
                        values[i] = reader.ReadLong();
                    return values;
                }
            default:
                throw new InvalidDataException($"Unknown NBT tag type {type}.");
        }
    }

    internal static string Utf8(byte[] bytes) => Encoding.UTF8.GetString(bytes);
}
