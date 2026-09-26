using System.Text;
using System.Text.Json;

namespace MccAndroid.Auth;

/// <summary>
/// Data returned by the Microsoft OAuth 2.0 device code endpoint.
/// </summary>
public sealed class DeviceCodeInfo
{
    public required string DeviceCode { get; init; }
    public required string UserCode { get; init; }
    public required string VerificationUri { get; init; }
    public int ExpiresIn { get; init; }
    public int Interval { get; init; }
}

/// <summary>Microsoft OAuth tokens returned once the user finishes signing in.</summary>
public sealed class MicrosoftTokenSet
{
    public required string AccessToken { get; init; }
    public required string RefreshToken { get; init; }
    public required string Email { get; init; }
}

/// <summary>
/// A fully authenticated Minecraft account: Microsoft tokens plus the Minecraft
/// profile obtained through Xbox Live / XSTS.
/// </summary>
public sealed class MinecraftAccount
{
    public required string Email { get; init; }
    public required string MsaAccessToken { get; init; }
    public required string MsaRefreshToken { get; init; }
    public required string MinecraftAccessToken { get; init; }
    public required string MinecraftUuid { get; init; }
    public required string MinecraftName { get; init; }

    /// <summary>Minecraft profile UUID in the dashed form used by the login packet.</summary>
    public Guid Uuid =>
        Guid.TryParseExact(MinecraftUuid, "N", out var parsed) ? parsed : Guid.Empty;
}

/// <summary>
/// Microsoft / Xbox Live authentication chain used to sign in and then join
/// online-mode Minecraft servers. Ported from MCC's Protocol/MicrosoftAuthentication.cs.
/// </summary>
public sealed class MicrosoftAuthService
{
    private const string ClientId = "54473e32-df8f-42e9-a649-9419b0dab9d3";
    private const string Scope = "XboxLive.signin offline_access openid email";

    private const string DeviceCodeUrl = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
    private const string TokenUrl = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";

    private const string XblUrl = "https://user.auth.xboxlive.com/user/authenticate";
    private const string XstsUrl = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private const string McLoginUrl = "https://api.minecraftservices.com/authentication/login_with_xbox";
    private const string McProfileUrl = "https://api.minecraftservices.com/minecraft/profile";
    private const string McJoinUrl = "https://sessionserver.mojang.com/session/minecraft/join";

    private const int SlowDownIncrementSeconds = 5;

    private readonly HttpClient http = new() { Timeout = TimeSpan.FromSeconds(30) };

    public HttpClient Http => http;

    /// <summary>Start the OAuth device code flow.</summary>
    public async Task<DeviceCodeInfo> RequestDeviceCodeAsync(CancellationToken ct)
    {
        using var content = new FormUrlEncodedContent(new Dictionary<string, string>
        {
            ["client_id"] = ClientId,
            ["scope"] = Scope,
        });

        string body;
        using (var response = await http.PostAsync(DeviceCodeUrl, content, ct).ConfigureAwait(false))
        {
            body = await response.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
        }

        using var doc = JsonDocument.Parse(body);
        var root = doc.RootElement;
        ThrowIfOAuthError(root);

        return new DeviceCodeInfo
        {
            DeviceCode = root.GetProperty("device_code").GetString()!,
            UserCode = root.GetProperty("user_code").GetString()!,
            VerificationUri = root.GetProperty("verification_uri").GetString()!,
            ExpiresIn = root.GetProperty("expires_in").GetInt32(),
            Interval = root.GetProperty("interval").GetInt32(),
        };
    }

    /// <summary>
    /// Poll the token endpoint until the user finishes signing in.
    /// Reports the current status through <paramref name="onProgress"/>.
    /// </summary>
    public async Task<MicrosoftTokenSet> WaitForDeviceCodeTokenAsync(
        DeviceCodeInfo info,
        Action<string>? onProgress,
        CancellationToken ct)
    {
        var deadline = DateTime.UtcNow.AddSeconds(info.ExpiresIn);
        int interval = Math.Max(1, info.Interval);
        bool slowDownNotified = false;

        while (DateTime.UtcNow < deadline)
        {
            await Task.Delay(TimeSpan.FromSeconds(interval), ct).ConfigureAwait(false);

            using var content = new FormUrlEncodedContent(new Dictionary<string, string>
            {
                ["client_id"] = ClientId,
                ["grant_type"] = "urn:ietf:params:oauth:grant-type:device_code",
                ["device_code"] = info.DeviceCode,
            });

            string body;
            using (var response = await http.PostAsync(TokenUrl, content, ct).ConfigureAwait(false))
            {
                body = await response.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
            }

            using var doc = JsonDocument.Parse(body);
            var root = doc.RootElement;

            if (root.TryGetProperty("error", out var errorElement))
            {
                string error = errorElement.GetString() ?? string.Empty;
                switch (error)
                {
                    case "authorization_pending":
                        onProgress?.Invoke(info.UserCode);
                        continue;
                    case "slow_down":
                        interval += SlowDownIncrementSeconds;
                        if (!slowDownNotified)
                        {
                            slowDownNotified = true;
                            onProgress?.Invoke(info.UserCode);
                        }
                        continue;
                    case "expired_token":
                        throw new InvalidOperationException("设备代码已过期，请重新登录。");
                    case "authorization_declined":
                        throw new InvalidOperationException("用户在微软登录页面拒绝了授权。");
                    default:
                        throw new InvalidOperationException(
                            root.TryGetProperty("error_description", out var desc)
                                ? desc.GetString() ?? error
                                : error);
                }
            }

            string accessToken = root.GetProperty("access_token").GetString()!;
            string refreshToken = root.TryGetProperty("refresh_token", out var refreshElement)
                ? refreshElement.GetString() ?? string.Empty
                : string.Empty;
            string email = root.TryGetProperty("id_token", out var idTokenElement)
                ? ReadEmailFromIdToken(idTokenElement.GetString() ?? string.Empty)
                : string.Empty;

            return new MicrosoftTokenSet
            {
                AccessToken = accessToken,
                RefreshToken = refreshToken,
                Email = email,
            };
        }

        throw new TimeoutException("等待微软登录超时，请重试。");
    }

    /// <summary>Exchange a Microsoft refresh token for a fresh access token.</summary>
    public async Task<string> RefreshMicrosoftTokenAsync(string refreshToken, CancellationToken ct)
    {
        using var content = new FormUrlEncodedContent(new Dictionary<string, string>
        {
            ["client_id"] = ClientId,
            ["grant_type"] = "refresh_token",
            ["refresh_token"] = refreshToken,
            ["scope"] = Scope,
        });

        string body;
        using (var response = await http.PostAsync(TokenUrl, content, ct).ConfigureAwait(false))
        {
            body = await response.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
        }

        using var doc = JsonDocument.Parse(body);
        ThrowIfOAuthError(doc.RootElement);
        return doc.RootElement.GetProperty("access_token").GetString()!;
    }

    /// <summary>
    /// Run the full Xbox Live -> XSTS -> Minecraft chain and fetch the profile.
    /// </summary>
    public async Task<MinecraftAccount> CompleteLoginAsync(
        string msaAccessToken,
        string refreshToken,
        string email,
        CancellationToken ct)
    {
        // 1. Xbox Live user authenticate. Tokens issued for our own client id need the "d=" prefix.
        using var xblDoc = await PostJsonAsync(XblUrl, new
        {
            Properties = new
            {
                AuthMethod = "RPS",
                SiteName = "user.auth.xboxlive.com",
                RpsTicket = "d=" + msaAccessToken,
            },
            RelyingParty = "http://auth.xboxlive.com",
            TokenType = "JWT",
        }, new Dictionary<string, string> { ["x-xbl-contract-version"] = "0" }, ct).ConfigureAwait(false);

        string xblToken = xblDoc.RootElement.GetProperty("Token").GetString()!;
        string userHash = xblDoc.RootElement.GetProperty("DisplayClaims")
            .GetProperty("xui")[0].GetProperty("uhs").GetString()!;

        // 2. XSTS authorize against the Minecraft relying party.
        using var xstsResponse = await SendJsonAsync(XstsUrl, new
        {
            Properties = new
            {
                SandboxId = "RETAIL",
                UserTokens = new[] { xblToken },
            },
            RelyingParty = "rp://api.minecraftservices.com/",
            TokenType = "JWT",
        }, new Dictionary<string, string> { ["x-xbl-contract-version"] = "1" }, ct).ConfigureAwait(false);

        string xstsBody = await xstsResponse.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
        if (!xstsResponse.IsSuccessStatusCode)
            throw new InvalidOperationException(DescribeXstsFailure(xstsResponse.StatusCode, xstsBody));

        using var xstsDoc = JsonDocument.Parse(xstsBody);
        string xstsToken = xstsDoc.RootElement.GetProperty("Token").GetString()!;
        userHash = xstsDoc.RootElement.GetProperty("DisplayClaims")
            .GetProperty("xui")[0].GetProperty("uhs").GetString()!;

        // 3. Exchange the XSTS token for a Minecraft access token.
        using var mcLoginDoc = await PostJsonAsync(McLoginUrl, new
        {
            identityToken = $"XBL3.0 x={userHash};{xstsToken}",
        }, null, ct).ConfigureAwait(false);
        string minecraftAccessToken = mcLoginDoc.RootElement.GetProperty("access_token").GetString()!;

        // 4. Fetch the Minecraft profile (name + uuid).
        using var profileRequest = new HttpRequestMessage(HttpMethod.Get, McProfileUrl);
        profileRequest.Headers.Authorization =
            new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", minecraftAccessToken);
        using var profileResponse = await http.SendAsync(profileRequest, ct).ConfigureAwait(false);
        string profileBody = await profileResponse.Content.ReadAsStringAsync(ct).ConfigureAwait(false);

        if (!profileResponse.IsSuccessStatusCode)
            throw new InvalidOperationException(
                "该微软账号没有可用的 Minecraft Java 版档案（可能未购买游戏）。");

        using var profileDoc = JsonDocument.Parse(profileBody);
        string uuid = profileDoc.RootElement.GetProperty("id").GetString()!;
        string name = profileDoc.RootElement.GetProperty("name").GetString()!;

        return new MinecraftAccount
        {
            Email = email,
            MsaAccessToken = msaAccessToken,
            MsaRefreshToken = refreshToken,
            MinecraftAccessToken = minecraftAccessToken,
            MinecraftUuid = uuid,
            MinecraftName = name,
        };
    }

    /// <summary>
    /// Tell Mojang's session server that we are about to join a server with the
    /// given server hash. Required for online-mode servers.
    /// </summary>
    public async Task JoinServerAsync(string minecraftAccessToken, string uuid, string serverHash, CancellationToken ct)
    {
        using var content = new StringContent(JsonSerializer.Serialize(new
        {
            accessToken = minecraftAccessToken,
            selectedProfile = uuid,
            serverId = serverHash,
        }), Encoding.UTF8, "application/json");

        using var response = await http.PostAsync(McJoinUrl, content, ct).ConfigureAwait(false);
        if (!response.IsSuccessStatusCode)
        {
            string body = await response.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
            throw new InvalidOperationException($"会话验证失败（session join 返回 {response.StatusCode}）：{body}");
        }
    }

    private static string DescribeXstsFailure(System.Net.HttpStatusCode status, string body)
    {
        if (status == System.Net.HttpStatusCode.Unauthorized)
        {
            try
            {
                using var doc = JsonDocument.Parse(body);
                string xerr = doc.RootElement.GetProperty("XErr").GetInt64().ToString();
                return xerr switch
                {
                    "2148916233" => "该微软账号还没有 Xbox 档案，请先在 Xbox 官网创建（可能需要在主机或 Xbox 应用中登录一次）。",
                    "2148916238" => "该账号未满 18 岁，需要由成人加入家庭组后才能用于登录。",
                    _ => $"XSTS 授权失败，错误码 {xerr}。",
                };
            }
            catch (JsonException)
            {
                // fall through to the generic message
            }
        }

        return $"XSTS 授权失败（HTTP {(int)status}）。";
    }

    private async Task<JsonDocument> PostJsonAsync(
        string url,
        object payload,
        Dictionary<string, string>? extraHeaders,
        CancellationToken ct)
    {
        using var response = await SendJsonAsync(url, payload, extraHeaders, ct).ConfigureAwait(false);
        string body = await response.Content.ReadAsStringAsync(ct).ConfigureAwait(false);

        if (!response.IsSuccessStatusCode)
            throw new InvalidOperationException($"请求 {url} 失败（HTTP {(int)response.StatusCode}）：{body}");

        return JsonDocument.Parse(body);
    }

    private async Task<HttpResponseMessage> SendJsonAsync(
        string url,
        object payload,
        Dictionary<string, string>? extraHeaders,
        CancellationToken ct)
    {
        var request = new HttpRequestMessage(HttpMethod.Post, url)
        {
            Content = new StringContent(JsonSerializer.Serialize(payload), Encoding.UTF8, "application/json"),
        };

        if (extraHeaders is not null)
        {
            foreach (var (key, value) in extraHeaders)
                request.Headers.TryAddWithoutValidation(key, value);
        }

        request.Headers.TryAddWithoutValidation("Accept", "application/json");
        return await http.SendAsync(request, ct).ConfigureAwait(false);
    }

    private static void ThrowIfOAuthError(JsonElement root)
    {
        if (root.TryGetProperty("error", out var errorElement))
        {
            string message = root.TryGetProperty("error_description", out var description)
                ? description.GetString() ?? errorElement.GetString()!
                : errorElement.GetString()!;
            throw new InvalidOperationException(message);
        }
    }

    /// <summary>
    /// Read the "email" claim out of a JWT without validating its signature
    /// (the token came straight from Microsoft over TLS).
    /// </summary>
    public static string ReadEmailFromIdToken(string idToken)
    {
        try
        {
            string[] parts = idToken.Split('.');
            if (parts.Length < 2)
                return string.Empty;

            string payload = parts[1].Replace('-', '+').Replace('_', '/');
            payload = payload.PadRight(payload.Length + ((4 - payload.Length % 4) % 4), '=');

            using var doc = JsonDocument.Parse(Convert.FromBase64String(payload));
            if (doc.RootElement.TryGetProperty("email", out var email))
                return email.GetString() ?? string.Empty;
            if (doc.RootElement.TryGetProperty("preferred_username", out var preferred))
                return preferred.GetString() ?? string.Empty;
        }
        catch (Exception)
        {
            // The e-mail claim is only used for display, never fail the login over it.
        }

        return string.Empty;
    }
}
