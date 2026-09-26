using Android.App;
using Android.Content;
using Android.Net;
using Android.OS;
using Android.Views;
using Android.Widget;
using MccAndroid.Auth;
using MccAndroid.Protocol;
using OperationCanceledException = System.OperationCanceledException;

namespace MccAndroid;

[Activity(
    Label = "@string/app_name",
    MainLauncher = true,
    Exported = true,
    Theme = "@android:style/Theme.Material.Light.DarkActionBar")]
public sealed class MainActivity : Activity
{
    private const int MaxLogLines = 400;

    private readonly MicrosoftAuthService auth = new();
    private readonly List<string> logLines = [];

    private MinecraftAccount? account;
    private MinecraftConnection? connection;
    private CancellationTokenSource? loginCts;
    private AlertDialog? deviceCodeDialog;

    private TextView accountView = null!;
    private TextView statusView = null!;
    private TextView logView = null!;
    private EditText hostInput = null!;
    private EditText portInput = null!;
    private EditText chatInput = null!;
    private Button loginButton = null!;
    private Button connectButton = null!;
    private Button disconnectButton = null!;
    private Button sendButton = null!;
    private Spinner versionSpinner = null!;
    private ScrollView logScroll = null!;

    protected override void OnCreate(Bundle? savedInstanceState)
    {
        base.OnCreate(savedInstanceState);
        SetContentView(BuildLayout());
        UpdateAccountUi();
        SetStatus(GetString(Resource.String.status_disconnected));
        AppendLog("MCC 安卓版已就绪，请先使用微软账号登录。");
    }

    private View BuildLayout()
    {
        var root = new LinearLayout(this)
        {
            Orientation = Orientation.Vertical,
        };
        root.SetPadding(Dp(16), Dp(16), Dp(16), Dp(16));

        root.AddView(SectionTitle(Resource.String.section_account));

        accountView = new TextView(this) { TextSize = 14 };
        root.AddView(accountView);

        var accountRow = new LinearLayout(this) { Orientation = Orientation.Horizontal };
        accountRow.SetPadding(0, Dp(6), 0, Dp(12));

        loginButton = new Button(this) { Text = GetString(Resource.String.btn_login) };
        loginButton.Click += OnLoginClicked;
        accountRow.AddView(loginButton, WeightedParams(1));
        root.AddView(accountRow);

        root.AddView(SectionTitle(Resource.String.section_server));

        hostInput = new EditText(this)
        {
            Hint = GetString(Resource.String.hint_server_host),
        };
        hostInput.SetSingleLine(true);
        root.AddView(hostInput);

        var portRow = new LinearLayout(this) { Orientation = Orientation.Horizontal };
        portInput = new EditText(this)
        {
            Hint = GetString(Resource.String.hint_server_port),
            InputType = Android.Text.InputTypes.ClassNumber,
        };
        portInput.SetSingleLine(true);
        portInput.Text = "25565";
        portRow.AddView(portInput, WeightedParams(1));

        versionSpinner = new Spinner(this);
        var names = GameVersions.All.Select(v => v.Name).ToArray();
        versionSpinner.Adapter = new ArrayAdapter<string>(this, Android.Resource.Layout.SimpleSpinnerItem, names);
        portRow.AddView(versionSpinner, WeightedParams(1));
        root.AddView(portRow);

        var connectRow = new LinearLayout(this) { Orientation = Orientation.Horizontal };
        connectRow.SetPadding(0, Dp(6), 0, Dp(12));

        connectButton = new Button(this) { Text = GetString(Resource.String.btn_connect) };
        connectButton.Click += OnConnectClicked;
        connectRow.AddView(connectButton, WeightedParams(1));

        disconnectButton = new Button(this) { Text = GetString(Resource.String.btn_disconnect) };
        disconnectButton.Click += (_, _) => Disconnect();
        connectRow.AddView(disconnectButton, WeightedParams(1));
        root.AddView(connectRow);

        statusView = new TextView(this) { TextSize = 14 };
        root.AddView(statusView);

        root.AddView(SectionTitle(Resource.String.section_chat));

        logScroll = new ScrollView(this);
        logView = new TextView(this)
        {
            TextSize = 13,
            VerticalScrollBarEnabled = true,
        };
        logScroll.AddView(logView);
        root.AddView(logScroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MatchParent, 0, 1f));

        var chatRow = new LinearLayout(this) { Orientation = Orientation.Horizontal };
        chatInput = new EditText(this)
        {
            Hint = GetString(Resource.String.hint_chat_input),
        };
        chatInput.SetSingleLine(true);
        chatRow.AddView(chatInput, WeightedParams(1));

        sendButton = new Button(this) { Text = GetString(Resource.String.btn_send) };
        sendButton.Click += OnSendClicked;
        chatRow.AddView(sendButton);
        root.AddView(chatRow);

        return root;
    }

    private TextView SectionTitle(int resourceId) => new(this)
    {
        Text = GetString(resourceId),
        TextSize = 16,
    };

    private static LinearLayout.LayoutParams WeightedParams(float weight) =>
        new(0, ViewGroup.LayoutParams.WrapContent, weight);

    private int Dp(int value) => (int)(value * Resources!.DisplayMetrics!.Density);

    // ------------------------------------------------------------ sign in

    private async void OnLoginClicked(object? sender, EventArgs e)
    {
        if (account is not null)
        {
            account = null;
            UpdateAccountUi();
            ShowToast(GetString(Resource.String.msg_signed_out));
            return;
        }

        loginButton.Enabled = false;
        loginCts = new CancellationTokenSource();

        try
        {
            var deviceCode = await auth.RequestDeviceCodeAsync(loginCts.Token);

            AppendLog(Format(Resource.String.status_device_code, deviceCode.UserCode));
            ShowDeviceCodeDialog(deviceCode);

            var tokens = await auth.WaitForDeviceCodeTokenAsync(
                deviceCode,
                code => Ui(() => SetStatus(Format(Resource.String.status_device_code, code))),
                loginCts.Token);

            SetStatus("正在获取 Minecraft 档案...");
            account = await auth.CompleteLoginAsync(tokens.AccessToken, tokens.RefreshToken, tokens.Email, loginCts.Token);

            DismissDeviceCodeDialog();
            AppendLog(Format(Resource.String.msg_signed_in, DescribeAccount()));
        }
        catch (OperationCanceledException)
        {
            AppendLog("已取消登录。");
        }
        catch (Exception ex)
        {
            DismissDeviceCodeDialog();
            AppendLog("登录失败：" + ex.Message);
            SetStatus("登录失败");
        }
        finally
        {
            loginCts?.Dispose();
            loginCts = null;
            loginButton.Enabled = true;
            UpdateAccountUi();
        }
    }

    private void ShowDeviceCodeDialog(DeviceCodeInfo deviceCode)
    {
        var dialog = new AlertDialog.Builder(this);
        dialog.SetTitle(GetString(Resource.String.btn_login));
        dialog.SetMessage(Format(Resource.String.status_device_code, deviceCode.UserCode));
        dialog.SetPositiveButton(GetString(Resource.String.btn_open_browser), (_, _) => OpenBrowser(deviceCode.VerificationUri));
        dialog.SetNeutralButton(GetString(Resource.String.btn_copy_code), (_, _) => CopyToClipboard(deviceCode.UserCode));
        dialog.SetNegativeButton("取消", (_, _) => loginCts?.Cancel());
        dialog.SetCancelable(false);

        deviceCodeDialog = dialog.Show();
    }

    private void DismissDeviceCodeDialog()
    {
        Ui(() =>
        {
            deviceCodeDialog?.Dismiss();
            deviceCodeDialog = null;
        });
    }

    private void OpenBrowser(string url)
    {
        try
        {
            StartActivity(new Intent(Intent.ActionView, Android.Net.Uri.Parse(url)));
        }
        catch (Exception ex)
        {
            CopyToClipboard(url);
            AppendLog("无法打开浏览器，链接已复制：" + ex.Message);
        }
    }

    private void CopyToClipboard(string text)
    {
        var clipboard = (ClipboardManager?)GetSystemService(ClipboardService);
        if (clipboard is not null)
            clipboard.PrimaryClip = ClipData.NewPlainText("MCC", text);
        ShowToast(GetString(Resource.String.msg_copied));
    }

    private string DescribeAccount() =>
        account is null ? string.Empty : $"{account.MinecraftName} ({account.Email})";

    private void UpdateAccountUi()
    {
        if (account is null)
        {
            accountView!.Text = GetString(Resource.String.status_logged_out);
            loginButton!.Text = GetString(Resource.String.btn_login);
        }
        else
        {
            accountView!.Text = Format(Resource.String.status_logged_in, DescribeAccount());
            loginButton!.Text = GetString(Resource.String.btn_logout);
        }
    }

    // ----------------------------------------------------------- connect

    private async void OnConnectClicked(object? sender, EventArgs e)
    {
        if (account is null)
        {
            ShowToast(GetString(Resource.String.msg_login_required));
            return;
        }

        if (connection is not null)
        {
            ShowToast(GetString(Resource.String.msg_already_connected));
            return;
        }

        string host = hostInput.Text?.Trim() ?? string.Empty;
        if (host.Length == 0)
        {
            ShowToast(GetString(Resource.String.msg_invalid_server));
            return;
        }

        int port = int.TryParse(portInput.Text, out int parsedPort) ? parsedPort : 25565;
        var version = GameVersions.All[Math.Max(0, versionSpinner.SelectedItemPosition)];

        SetStatus(Format(Resource.String.status_connecting, host, port, version.Name));
        AppendLog($"正在连接 {host}:{port}（{version}）");

        var target = new MinecraftConnection(version, account, auth);
        connection = target;
        target.Log += line => Ui(() => AppendLog(line));
        target.ChatReceived += line => Ui(() => AppendLog(line.Text));
        target.Joined += () => Ui(() => SetStatus(Format(Resource.String.status_connected, host, account.MinecraftName)));
        target.Closed += reason => Ui(() => OnConnectionClosed(reason));

        try
        {
            await target.ConnectAsync(host, port, GetDnsServers(), CancellationToken.None);
        }
        catch (Exception ex)
        {
            AppendLog("连接失败：" + ex.Message);
            SetStatus(GetString(Resource.String.status_disconnected));
            if (ReferenceEquals(connection, target))
                connection = null;
            target.Dispose();
        }
    }

    private void OnConnectionClosed(string reason)
    {
        SetStatus(GetString(Resource.String.status_disconnected));
        AppendLog("连接结束：" + reason);

        var target = connection;
        connection = null;
        target?.Dispose();
    }

    private void Disconnect()
    {
        if (connection is null)
        {
            ShowToast(GetString(Resource.String.msg_not_connected));
            return;
        }

        DisconnectCore();
    }

    private void DisconnectCore()
    {
        var target = connection;
        connection = null;
        target?.Dispose();
        SetStatus(GetString(Resource.String.status_disconnected));
        AppendLog(GetString(Resource.String.status_disconnected));
    }

    private async void OnSendClicked(object? sender, EventArgs e)
    {
        string text = chatInput.Text?.Trim() ?? string.Empty;
        if (text.Length == 0)
            return;

        var target = connection;
        if (target is null)
        {
            ShowToast(GetString(Resource.String.msg_not_connected));
            return;
        }

        chatInput.Text = string.Empty;

        try
        {
            await target.SendChatAsync(text, CancellationToken.None);
            AppendLog("> " + text);
        }
        catch (Exception ex)
        {
            AppendLog("发送失败：" + ex.Message);
        }
    }

    private List<System.Net.IPAddress> GetDnsServers()
    {
        var servers = new List<System.Net.IPAddress>();

        try
        {
            var manager = (ConnectivityManager?)GetSystemService(ConnectivityService);
            var network = manager?.ActiveNetwork;
            var properties = network is null ? null : manager!.GetLinkProperties(network);

            if (properties?.DnsServers is not null)
            {
                foreach (var address in properties.DnsServers)
                {
                    if (address?.HostAddress is { } value && System.Net.IPAddress.TryParse(value, out var parsed))
                        servers.Add(parsed);
                }
            }
        }
        catch (Exception)
        {
            // Without DNS servers we simply skip the SRV lookup.
        }

        return servers;
    }

    // ------------------------------------------------------------ helpers

    private void SetStatus(string text) => Ui(() => statusView!.Text = text);

    private void ShowToast(string text) => Ui(() => Toast.MakeText(this, text, ToastLength.Short)?.Show());

    /// <summary>Formats a localized string resource that contains {0}-style placeholders.</summary>
    private string Format(int resourceId, params object[] args) =>
        string.Format(System.Globalization.CultureInfo.CurrentCulture, GetString(resourceId) ?? string.Empty, args);

    private void AppendLog(string line)
    {
        logLines.Add(line);
        if (logLines.Count > MaxLogLines)
            logLines.RemoveRange(0, logLines.Count - MaxLogLines);

        logView!.Text = string.Join('\n', logLines);
        logScroll!.Post(() => logScroll.FullScroll(FocusSearchDirection.Down));
    }

    private void Ui(Action action)
    {
        if (Looper.MainLooper?.IsCurrentThread == true)
            action();
        else
            RunOnUiThread(action);
    }

    protected override void OnDestroy()
    {
        loginCts?.Cancel();
        loginCts?.Dispose();
        loginCts = null;

        connection?.Dispose();
        connection = null;

        base.OnDestroy();
    }
}
