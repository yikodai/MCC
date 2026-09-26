using System.IO.Compression;

namespace MccAndroid.Protocol;

/// <summary>
/// Zlib helpers for Minecraft's packet compression. Minecraft uses the zlib
/// wrapper, which <see cref="ZLibStream"/> produces directly.
/// </summary>
internal static class Zlib
{
    public static byte[] Compress(byte[] data)
    {
        using var output = new MemoryStream();
        using (var stream = new ZLibStream(output, CompressionMode.Compress, leaveOpen: true))
            stream.Write(data, 0, data.Length);

        return output.ToArray();
    }

    public static byte[] Decompress(byte[] data, int uncompressedSize)
    {
        using var input = new MemoryStream(data, writable: false);
        using var stream = new ZLibStream(input, CompressionMode.Decompress);

        byte[] result = new byte[uncompressedSize];
        int total = 0;
        while (total < uncompressedSize)
        {
            int read = stream.Read(result, total, uncompressedSize - total);
            if (read <= 0)
                break;
            total += read;
        }

        return result;
    }
}
