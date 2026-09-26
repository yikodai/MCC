using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;
using System.Text;

namespace MccAndroid.Protocol;

/// <summary>
/// Minimal DNS SRV resolver used to follow "_minecraft._tcp" records, which many
/// public servers rely on. Implemented directly against the DNS servers reported
/// by the Android platform because /etc/resolv.conf is not readable there.
/// </summary>
internal static class SrvResolver
{
    private const int RecordTypeSrv = 33;
    private const int RecordClassIn = 1;

    /// <summary>
    /// Looks up "_minecraft._tcp.&lt;host&gt;". Returns null when no usable record
    /// exists or the lookup fails for any reason.
    /// </summary>
    public static async Task<(string Host, int Port)?> ResolveMinecraftAsync(
        string host,
        IReadOnlyList<IPAddress> dnsServers,
        TimeSpan timeout,
        CancellationToken ct)
    {
        if (dnsServers.Count == 0 || string.IsNullOrWhiteSpace(host))
            return null;

        string queryName = "_minecraft._tcp." + host.Trim('.');
        byte[] query = BuildQuery(queryName);

        foreach (var server in dnsServers)
        {
            try
            {
                byte[]? response = await QueryAsync(server, query, timeout, ct).ConfigureAwait(false);
                if (response is null)
                    continue;

                var record = ParseFirstSrv(response);
                if (record is not null)
                    return record;
            }
            catch (Exception)
            {
                // Try the next configured DNS server.
            }
        }

        return null;
    }

    private static byte[] BuildQuery(string name)
    {
        using var stream = new MemoryStream();
        using var writer = new BinaryWriter(stream);

        writer.Write((ushort)0);      // transaction id
        writer.Write((ushort)0x0100); // standard query, recursion desired
        writer.Write((ushort)1);      // questions
        writer.Write((ushort)0);      // answers
        writer.Write((ushort)0);      // authority
        writer.Write((ushort)0);      // additional

        foreach (string label in name.Split('.', StringSplitOptions.RemoveEmptyEntries))
        {
            byte[] bytes = Encoding.ASCII.GetBytes(label);
            writer.Write((byte)bytes.Length);
            writer.Write(bytes);
        }

        writer.Write((byte)0);              // root label
        writer.Write((ushort)RecordTypeSrv);
        writer.Write((ushort)RecordClassIn);
        writer.Flush();

        return stream.ToArray();
    }

    private static async Task<byte[]?> QueryAsync(
        IPAddress server,
        byte[] query,
        TimeSpan timeout,
        CancellationToken ct)
    {
        using var udp = new UdpClient(server.AddressFamily);
        udp.Connect(server, 53);

        await udp.SendAsync(query, ct).ConfigureAwait(false);

        using var timeoutCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeoutCts.CancelAfter(timeout);

        var result = await udp.ReceiveAsync(timeoutCts.Token).ConfigureAwait(false);
        return result.Buffer;
    }

    private static (string Host, int Port)? ParseFirstSrv(byte[] response)
    {
        if (response.Length < 12)
            return null;

        int questionCount = BinaryPrimitives.ReadUInt16BigEndian(response.AsSpan(4));
        int answerCount = BinaryPrimitives.ReadUInt16BigEndian(response.AsSpan(6));

        int offset = 12;
        for (int i = 0; i < questionCount; i++)
        {
            offset = SkipName(response, offset);
            offset += 4; // type + class
            if (offset > response.Length)
                return null;
        }

        for (int i = 0; i < answerCount; i++)
        {
            offset = SkipName(response, offset);
            if (offset + 10 > response.Length)
                return null;

            int type = BinaryPrimitives.ReadUInt16BigEndian(response.AsSpan(offset));
            int recordClass = BinaryPrimitives.ReadUInt16BigEndian(response.AsSpan(offset + 2));
            int dataLength = BinaryPrimitives.ReadUInt16BigEndian(response.AsSpan(offset + 8));
            int dataOffset = offset + 10;

            if (type == RecordTypeSrv && recordClass == RecordClassIn && dataLength >= 7)
            {
                int port = BinaryPrimitives.ReadUInt16BigEndian(response.AsSpan(dataOffset + 4));
                string target = ReadName(response, dataOffset + 6).TrimEnd('.');

                if (!string.IsNullOrEmpty(target) && port > 0)
                    return (target, port);
            }

            offset = dataOffset + dataLength;
        }

        return null;
    }

    private static int SkipName(byte[] buffer, int offset)
    {
        while (offset < buffer.Length)
        {
            int length = buffer[offset];

            if (length == 0)
                return offset + 1;

            // Compression pointer terminates the name.
            if ((length & 0xC0) == 0xC0)
                return offset + 2;

            offset += length + 1;
        }

        return offset;
    }

    private static string ReadName(byte[] buffer, int offset)
    {
        var builder = new StringBuilder();
        int guard = 0;

        while (offset < buffer.Length && guard++ < 128)
        {
            int length = buffer[offset];

            if (length == 0)
                break;

            if ((length & 0xC0) == 0xC0)
            {
                if (offset + 1 >= buffer.Length)
                    break;

                int pointer = ((length & 0x3F) << 8) | buffer[offset + 1];
                if (pointer >= offset)
                    break; // Only backward pointers are valid.

                offset = pointer;
                continue;
            }

            offset++;
            if (offset + length > buffer.Length)
                break;

            if (builder.Length > 0)
                builder.Append('.');

            builder.Append(Encoding.ASCII.GetString(buffer, offset, length));
            offset += length;
        }

        return builder.ToString();
    }
}
