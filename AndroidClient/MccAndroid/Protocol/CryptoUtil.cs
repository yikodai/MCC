using System.Security.Cryptography;
using System.Text;

namespace MccAndroid.Protocol;

/// <summary>
/// RSA / AES / SHA-1 helpers for the protocol login handshake.
/// Ported from MCC's Crypto/CryptoHandler.cs.
/// </summary>
internal static class CryptoUtil
{
    public static byte[] GenerateAesKey()
    {
        using var aes = Aes.Create();
        aes.KeySize = 128;
        aes.GenerateKey();
        return aes.Key;
    }

    /// <summary>
    /// Parses the server's X.509 SubjectPublicKeyInfo blob into an RSA key.
    /// </summary>
    public static RSA DecodeRsaPublicKey(byte[] x509Key)
    {
        // Only the rsaEncryption PKCS#1 structure is accepted, matching MCC.
        byte[] rsaOid = [0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x01, 0x01];

        using var ms = new MemoryStream(x509Key);
        using var reader = new BinaryReader(ms);

        if (reader.ReadByte() != 0x30)
            throw new InvalidDataException("Invalid RSA public key: missing outer sequence.");

        ReadAsnLength(reader);

        if (reader.ReadByte() != 0x30)
            throw new InvalidDataException("Invalid RSA public key: missing algorithm identifier.");

        int identifierSize = ReadAsnLength(reader);

        if (reader.ReadByte() != 0x06)
            throw new InvalidDataException("Invalid RSA public key: missing object identifier.");

        int oidLength = ReadAsnLength(reader);
        byte[] oid = reader.ReadBytes(oidLength);
        if (!oid.AsSpan().SequenceEqual(rsaOid))
            throw new InvalidDataException("Invalid RSA public key: unexpected object identifier.");

        reader.ReadBytes(identifierSize - 2 - oidLength);

        if (reader.ReadByte() != 0x03)
            throw new InvalidDataException("Invalid RSA public key: missing bit string.");

        ReadAsnLength(reader);
        reader.ReadByte(); // unused bits indicator

        if (reader.ReadByte() != 0x30)
            throw new InvalidDataException("Invalid RSA public key: missing inner sequence.");

        ReadAsnLength(reader);

        if (reader.ReadByte() != 0x02)
            throw new InvalidDataException("Invalid RSA public key: missing modulus.");

        byte[] modulus = reader.ReadBytes(ReadAsnLength(reader));
        if (modulus.Length > 0 && modulus[0] == 0x00)
            modulus = modulus[1..];

        if (reader.ReadByte() != 0x02)
            throw new InvalidDataException("Invalid RSA public key: missing exponent.");

        byte[] exponent = reader.ReadBytes(ReadAsnLength(reader));

        var rsa = RSA.Create();
        rsa.ImportParameters(new RSAParameters { Modulus = modulus, Exponent = exponent });
        return rsa;
    }

    private static int ReadAsnLength(BinaryReader reader)
    {
        int length = reader.ReadByte();
        if ((length & 0x80) == 0x80)
        {
            int count = length & 0x0F;
            byte[] lengthBytes = new byte[4];
            reader.Read(lengthBytes, 4 - count, count);
            Array.Reverse(lengthBytes);
            length = BitConverter.ToInt32(lengthBytes, 0);
        }

        return length;
    }

    /// <summary>
    /// Builds the Minecraft-style server hash: a SHA-1 digest rendered as a
    /// signed two's complement hex string.
    /// </summary>
    public static string GetServerHash(string serverId, byte[] publicKey, byte[] secretKey)
    {
        byte[] hash;
        using (var sha1 = SHA1.Create())
        {
            byte[] serverIdBytes = Encoding.Latin1.GetBytes(serverId);
            sha1.TransformBlock(serverIdBytes, 0, serverIdBytes.Length, null, 0);
            sha1.TransformBlock(secretKey, 0, secretKey.Length, null, 0);
            sha1.TransformFinalBlock(publicKey, 0, publicKey.Length);
            hash = sha1.Hash!;
        }

        bool negative = (hash[0] & 0x80) == 0x80;
        if (negative)
            hash = TwosComplementLittleEndian(hash);

        var builder = new StringBuilder(hash.Length * 2);
        foreach (byte value in hash)
            builder.Append(value.ToString("x2"));

        string result = builder.ToString().TrimStart('0');
        return negative ? "-" + result : result;
    }

    private static byte[] TwosComplementLittleEndian(byte[] value)
    {
        bool carry = true;
        for (int i = value.Length - 1; i >= 0; i--)
        {
            value[i] = (byte)~value[i];
            if (carry)
            {
                carry = value[i] == 0xFF;
                value[i]++;
            }
        }

        return value;
    }
}
