using System.Text;

namespace MccAndroid.Protocol;

/// <summary>Sequential reader over a single decoded packet payload.</summary>
internal sealed class PacketReader(byte[] data)
{
    private readonly byte[] data = data;
    private int pos;

    public int Remaining => data.Length - pos;

    /// <summary>Current read offset. Used to retry a parse with a different encoding.</summary>
    public int Position
    {
        get => pos;
        set => pos = value;
    }

    public byte ReadByte()
    {
        if (pos >= data.Length)
            throw new InvalidDataException("Packet payload ended unexpectedly.");
        return data[pos++];
    }

    public byte[] ReadBytes(int count)
    {
        if (count < 0 || pos + count > data.Length)
            throw new InvalidDataException("Packet payload ended unexpectedly.");
        var result = data[pos..(pos + count)];
        pos += count;
        return result;
    }

    public byte[] ReadRest() => ReadBytes(Remaining);

    public bool ReadBool() => ReadByte() != 0;

    public ushort ReadUShort() => (ushort)((ReadByte() << 8) | ReadByte());

    public short ReadShort() => (short)ReadUShort();

    public int ReadInt() => (ReadByte() << 24) | (ReadByte() << 16) | (ReadByte() << 8) | ReadByte();

    public long ReadLong()
    {
        long value = 0;
        for (int i = 0; i < 8; i++)
            value = (value << 8) | ReadByte();
        return value;
    }

    public float ReadFloat() => BitConverter.Int32BitsToSingle(ReadInt());

    public double ReadDouble() => BitConverter.Int64BitsToDouble(ReadLong());

    public int ReadVarInt()
    {
        int value = 0;
        int shift = 0;

        while (true)
        {
            byte current = ReadByte();
            value |= (current & 0x7F) << shift;

            if ((current & 0x80) == 0)
                return value;

            shift += 7;
            if (shift >= 35)
                throw new InvalidDataException("VarInt is too big.");
        }
    }

    public string ReadString()
    {
        int length = ReadVarInt();
        return Encoding.UTF8.GetString(ReadBytes(length));
    }

    public Guid ReadUuid() => new(ReadBytes(16), bigEndian: true);

    /// <summary>Reads a VarInt-prefixed byte array.</summary>
    public byte[] ReadByteArray()
    {
        int length = ReadVarInt();
        return ReadBytes(length);
    }
}

/// <summary>Append-only writer used to build outgoing packet payloads.</summary>
internal sealed class PacketWriter
{
    private readonly List<byte> buffer = [];

    public int Length => buffer.Count;

    public PacketWriter WriteByte(byte value)
    {
        buffer.Add(value);
        return this;
    }

    public PacketWriter WriteBool(bool value) => WriteByte(value ? (byte)1 : (byte)0);

    public PacketWriter WriteUShort(ushort value)
    {
        buffer.Add((byte)(value >> 8));
        buffer.Add((byte)value);
        return this;
    }

    public PacketWriter WriteInt(int value)
    {
        buffer.Add((byte)(value >> 24));
        buffer.Add((byte)(value >> 16));
        buffer.Add((byte)(value >> 8));
        buffer.Add((byte)value);
        return this;
    }

    public PacketWriter WriteLong(long value)
    {
        for (int i = 7; i >= 0; i--)
            buffer.Add((byte)(value >> (i * 8)));
        return this;
    }

    public PacketWriter WriteFloat(float value) => WriteInt(BitConverter.SingleToInt32Bits(value));

    public PacketWriter WriteDouble(double value) => WriteLong(BitConverter.DoubleToInt64Bits(value));

    public PacketWriter WriteVarInt(int value)
    {
        uint current = (uint)value;
        do
        {
            byte temp = (byte)(current & 0x7F);
            current >>= 7;
            if (current != 0)
                temp |= 0x80;
            buffer.Add(temp);
        }
        while (current != 0);

        return this;
    }

    public PacketWriter WriteString(string value)
    {
        byte[] bytes = Encoding.UTF8.GetBytes(value);
        WriteVarInt(bytes.Length);
        buffer.AddRange(bytes);
        return this;
    }

    public PacketWriter WriteUuid(Guid value)
    {
        Span<byte> bytes = stackalloc byte[16];
        value.TryWriteBytes(bytes, bigEndian: true, out _);
        buffer.AddRange(bytes.ToArray());
        return this;
    }

    /// <summary>Writes a VarInt-prefixed byte array.</summary>
    public PacketWriter WriteByteArray(ReadOnlySpan<byte> value)
    {
        WriteVarInt(value.Length);
        buffer.AddRange(value.ToArray());
        return this;
    }

    public PacketWriter WriteRaw(ReadOnlySpan<byte> value)
    {
        buffer.AddRange(value.ToArray());
        return this;
    }

    public byte[] ToArray() => [.. buffer];
}
