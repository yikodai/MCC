using System.Security.Cryptography;

namespace MccAndroid.Protocol;

/// <summary>
/// AES/CFB8 stream cipher as used by the Minecraft protocol (one byte of
/// feedback per processed byte). Ported from MCC's Crypto/AesCfb8Stream.cs so
/// the same behaviour is available on Mono/Android.
/// </summary>
internal sealed class AesCfb8Stream : Stream
{
    private const int BlockSize = 16;

    private readonly Aes aes;
    private readonly byte[] readIv = new byte[BlockSize];
    private readonly byte[] writeIv = new byte[BlockSize];
    private readonly Stream baseStream;
    private bool readEnded;

    public AesCfb8Stream(Stream stream, byte[] key)
    {
        baseStream = stream;

        aes = Aes.Create();
        aes.BlockSize = 128;
        aes.KeySize = 128;
        aes.Key = key;
        aes.Mode = CipherMode.ECB;
        aes.Padding = PaddingMode.None;

        Array.Copy(key, readIv, BlockSize);
        Array.Copy(key, writeIv, BlockSize);
    }

    public override bool CanRead => true;
    public override bool CanSeek => false;
    public override bool CanWrite => true;
    public override long Length => throw new NotSupportedException();
    public override long Position
    {
        get => throw new NotSupportedException();
        set => throw new NotSupportedException();
    }

    public override void Flush() => baseStream.Flush();

    public override int ReadByte()
    {
        if (readEnded)
            return -1;

        int input = baseStream.ReadByte();
        if (input == -1)
        {
            readEnded = true;
            return -1;
        }

        Span<byte> blockOutput = stackalloc byte[BlockSize];
        aes.EncryptEcb(readIv, blockOutput, PaddingMode.None);

        Array.Copy(readIv, 1, readIv, 0, BlockSize - 1);
        readIv[BlockSize - 1] = (byte)input;

        return blockOutput[0] ^ input;
    }

    public override int Read(byte[] buffer, int offset, int count)
    {
        if (readEnded)
            return 0;

        Span<byte> blockOutput = stackalloc byte[BlockSize];
        byte[] inputBuffer = new byte[BlockSize + count];
        Array.Copy(readIv, inputBuffer, BlockSize);

        int read = 0;
        while (read < count)
        {
            int current = baseStream.Read(inputBuffer, BlockSize + read, count - read);
            if (current == 0)
            {
                readEnded = true;
                break;
            }

            int processEnd = read + current;
            for (int index = read; index < processEnd; index++)
            {
                ReadOnlySpan<byte> blockInput = new(inputBuffer, index, BlockSize);
                aes.EncryptEcb(blockInput, blockOutput, PaddingMode.None);
                buffer[offset + index] = (byte)(blockOutput[0] ^ inputBuffer[index + BlockSize]);
            }

            read = processEnd;
        }

        Array.Copy(inputBuffer, read, readIv, 0, BlockSize);
        return read;
    }

    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();

    public override void SetLength(long value) => throw new NotSupportedException();

    public override void WriteByte(byte value)
    {
        Span<byte> blockOutput = stackalloc byte[BlockSize];
        aes.EncryptEcb(writeIv, blockOutput, PaddingMode.None);

        byte output = (byte)(blockOutput[0] ^ value);
        baseStream.WriteByte(output);

        Array.Copy(writeIv, 1, writeIv, 0, BlockSize - 1);
        writeIv[BlockSize - 1] = output;
    }

    public override void Write(byte[] buffer, int offset, int count)
    {
        byte[] outputBuffer = new byte[BlockSize + count];
        Array.Copy(writeIv, outputBuffer, BlockSize);

        Span<byte> blockOutput = stackalloc byte[BlockSize];
        for (int index = 0; index < count; index++)
        {
            ReadOnlySpan<byte> blockInput = new(outputBuffer, index, BlockSize);
            aes.EncryptEcb(blockInput, blockOutput, PaddingMode.None);
            outputBuffer[BlockSize + index] = (byte)(blockOutput[0] ^ buffer[offset + index]);
        }

        baseStream.Write(outputBuffer, BlockSize, count);

        Array.Copy(outputBuffer, count, writeIv, 0, BlockSize);
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
            aes.Dispose();
        base.Dispose(disposing);
    }
}
