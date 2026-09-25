# MinecraftClientAndroid（MCC 安卓版）

把 [Minecraft Console Client（MCC）](../../MinecraftClient) 移植到 Android 的客户端实现：
**图形化启动页**（服务器列表、微软正版登录 / 离线登录）+ **命令行界面**（收发聊天与执行 `/指令`），
由前台服务保活，切到后台不会掉线。

代码语言为 Kotlin，认证链与协议实现逐段对照 MCC 的 C# 源码移植，便于与上游同步。

## 功能范围

首版聚焦「登录 + 连服 + 聊天/指令」这条最小可用闭环：

- 微软账号登录（OAuth 2.0 设备码流程，无需内嵌浏览器/回调地址），令牌加密缓存并自动刷新
- 离线（盗版）登录，UUID 按原版离线算法由用户名派生，服务器上的玩家数据保持稳定
- 服务器列表增删改查、SRV 记录解析（DNS over HTTPS）、Server List Ping 自动识别服务端版本
- 连接服务器：离线模式与在线模式（含正版会话校验 `sessionserver/minecraft/join`）、协议加密（AES-128-CFB8 + RSA）、zlib 压缩
- 命令行界面：带颜色的聊天显示、输入聊天或 `/指令`、↑ 取回上一条输入、复制日志、清屏
- 1.20.2+ 的配置阶段处理（客户端信息、已知数据包、行为准则、Cookie 请求、踢出提示）
- 安全聊天：自动申请档案密钥并在进服后发送聊天会话更新，签名聊天消息（申请失败时自动退回未签名）

不在首版范围（可后续补充）：世界/地形与实体渲染、背包与容器操作、移动与寻路、内置机器人（自动重连/自动攻击等）、C# 脚本系统、多账号管理。

## 支持的游戏版本

协议号 766 - 776，即 **Minecraft Java 版 1.20.5 - 26.2**。
包 ID 表由 `tools/gen_packet_ids.py` 从 MCC 的 `PacketPalette*.cs` 直接生成，保证与上游一致。
低于 1.20.5 的版本尚未支持（缺少配置阶段的旧协议需要另一套登录流程）。

## 目录结构

```
MinecraftClientAndroid/
├── core/                              # 纯 Kotlin/JVM 库，可脱离 Android 单测
│   └── src/main/kotlin/mccandroid/core/
│       ├── auth/                      # 微软 / Xbox / Minecraft 认证链、档案密钥、账号存储
│       ├── protocol/                  # 字节读写、分帧压缩、加密、NBT、包 ID 表、连接与聊天收发
│       ├── chat/                      # 网络 NBT 解析、聊天组件渲染（§ 颜色/翻译键）
│       ├── session/                   # 服务器列表数据模型
│       ├── http/                      # 基于 HttpURLConnection 的极简 HTTP 客户端
│       ├── util/                      # JSON 便捷访问
│       ├── McSession.kt               # 对外高层 API（Flow 形式的连接状态与消息流）
│       └── McVersion.kt               # 版本与协议号映射
├── app/                               # Android 应用
│   └── src/main/java/com/mccteam/android/mcc/
│       ├── MainActivity.kt            # 启动页：服务器列表 + 账号 + 连接
│       ├── ConsoleActivity.kt         # 命令行界面
│       ├── MccConnectionService.kt    # 前台服务，保活并展示连接通知
│       ├── MccApplication.kt          # 应用级单例（会话、存储、登录流程）
│       ├── data/                      # Keystore 加密存储、服务器列表存储
│       └── ui/                        # 列表适配器、§ 颜色渲染
└── tools/gen_packet_ids.py            # 从 MCC 上游生成 Kotlin 包 ID 表
```

## 构建

依赖：JDK 17、Android SDK（compileSdk 35、build-tools 35.0.0）。

```bash
cd MinecraftClientAndroid
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug          # 产物：app/build/outputs/apk/debug/app-debug.apk
./gradlew :core:test                  # 核心库单元测试（不需要 Android SDK）
```

安装到手机：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

也可以直接用 Android Studio 打开本目录（Gradle 工程已带 wrapper）。

### 端到端测试（可选）

`core` 里有一个针对真实服务端的连通测试，默认跳过；设置环境变量即可启用：

```bash
MCC_E2E_HOST=127.0.0.1 MCC_E2E_PORT=25565 ./gradlew :core:test
```

它会用离线账号登录指定服务器（需为离线模式），校验：版本探测 → 进入游戏阶段 → 聊天消息被服务器回显 → `/list` 指令收到系统回复。

## 验证情况

- `./gradlew :core:test`：68 项测试用例（2 项端到端用例默认跳过），覆盖版本映射、VarInt/字符串/UUID 编解码、包分帧与压缩、AES-CFB8 流加密（含逐字节解密等价性）、RSA 加密、服务端哈希、NBT 解析、聊天组件渲染、账号序列化与离线 UUID
- 端到端：已在原版 **1.21.11 服务端**（协议 774，`online-mode=false`、`enforce-secure-profile=true`）上实测通过，
  服务端日志确认 `logged in` / `joined the game` / 聊天广播 / `/list` 回复
- 已验证：`enforce-secure-profile=true` 的原版服务端会接受未签名聊天（日志标记 `[Not Secure]`），
  因此即使档案密钥申请失败，聊天也能正常收发
- 未运行环境：真机安装与图形界面交互（无模拟器），以及正版账号在在线模式服务器上的完整登录（需要真实账号）

## 使用说明

1. **登录账号**：点「微软登录」，界面会显示设备码与验证网址，用手机浏览器打开
   `microsoft.com/link`（或点「打开浏览器」）输入设备码完成授权；也可以点「离线登录」输入用户名。
   登录状态与刷新令牌使用 Android Keystore 加密保存，下次启动免登录。
2. **添加服务器**：点右下角按钮填写名称与地址，地址支持 `mc.example.com`、`mc.example.com:25566`、`[::1]:25565`；
   保存后自动选中该服务器。
3. **连接**：选中服务器后点底部「连接」，客户端会先做 SRV 解析与版本探测，随后进入命令行界面。
4. **命令行界面**：输入内容回车即发送；以 `/` 开头的内容会作为指令发送。
   右上角菜单可断开连接、清屏、复制日志，输入框左侧的 ↑ 按钮取回上一条输入。
   切到后台后连接由前台服务维持，通知栏可查看状态或直接断开。

## 微软登录实现说明

与 MCC 使用同一个公开客户端 ID（`54473e32-df8f-42e9-a649-9419b0dab9d3`），
采用设备码流程而不是授权码 + 重定向，原因是安卓端无需注册自定义 URL Scheme 也能完成登录。

认证链（`core/auth/`，与 MCC `Protocol/MicrosoftAuthentication.cs` 一一对应）：

1. `MicrosoftAuth.requestDeviceCode()` 申请设备码，界面展示 `user_code` 与验证网址
2. `MicrosoftAuth.pollForToken()` 轮询令牌接口，处理 `authorization_pending` / `slow_down` / `expired_token` / `authorization_declined`
3. `XboxLiveAuth.authenticate()` Xbox Live 认证（微软令牌需加 `d=` 前缀），失败时按 XSTS 错误码给出中文提示
4. `XboxLiveAuth.authorize()` XSTS 授权（RelyingParty 为 `rp://api.minecraftservices.com/`）
5. `MinecraftAuth.loginWithXbox()` 换取 Minecraft 访问令牌，并校验游戏所有权、拉取玩家档案
6. 连接服务器时若收到加密请求，用 `CryptoUtils.serverHash()` 计算服务端哈希后调用
   `sessionserver.mojang.com/session/minecraft/join` 完成正版会话校验
7. 申请 `api.minecraftservices.com/player/certificates` 档案密钥，进服后发送聊天会话更新（`SendPlayerSession`），
   之后的消息按 1.19.3+ 格式签名；申请失败则退回未签名消息

令牌管理：Minecraft 访问令牌过期前 60 秒视为失效，连接前会自动用刷新令牌重走 3-5 步；
刷新令牌失效时会提示需要重新登录。

## 与 MCC 的对应关系

| Kotlin | 对应 MCC 源码 |
| --- | --- |
| `protocol/PacketReader.kt`、`protocol/Compression.kt` | `Protocol/Handlers/DataTypes.cs`、`Protocol/Handlers/SocketWrapper.cs` |
| `protocol/CryptoUtils.kt` | `Crypto/CryptoHandler.cs` |
| `protocol/MinecraftConnection.kt` | `Protocol/Handlers/Protocol18.cs`（登录、配置阶段、聊天、保活） |
| `protocol/ServerPing.kt`、`protocol/ServerAddress.kt` | `Protocol/ProtocolHandler.cs`（DoPing、SRV 解析） |
| `chat/NbtReader.kt`、`chat/ChatParser.kt` | `Protocol/Handlers/DataTypes.cs`（NBT 读取）、`Protocol/Message/ChatParser.cs` |
| `auth/*` | `Protocol/MicrosoftAuthentication.cs`、`Protocol/ProfileKey/*`、`Protocol/ProtocolHandler.SessionCheck()` |

差异与取舍：

- 只保留命令行客户端需要的数据包，世界/背包/实体相关包在收发时忽略
- SRV 记录改用 DNS over HTTPS（Cloudflare / Google）查询，安卓端没有可直接使用的系统 SRV API
- 聊天翻译键只内置了聊天场景常用的一小部分（`chat.type.*`、加入/退出等），
  未命中的键会以 `键名(参数)` 形式原样显示，不会丢信息
- 未解析服务端指令树，因此签名指令只发送未签名参数（与 MCC 在无密钥时的行为一致）

## 新增 Minecraft 版本支持

1. 在 MCC 上游新增 `PacketPalette` 后，更新 `tools/gen_packet_ids.py` 里的 `PROTOCOL_TO_PALETTE` 映射
2. 执行 `python3 tools/gen_packet_ids.py` 重新生成 `core/.../protocol/PacketIds.kt`
3. 在 `core/.../McVersion.kt` 中补充版本号与协议号、调整 `MAX_PROTOCOL`
4. 若新版本改动了登录/配置阶段的字段布局，在 `MinecraftConnection.kt` 中按协议号分支处理（参考 MCC 的 `Protocol18Handler`）

## 安全与隐私

- 仅使用 MCC 的公开客户端 ID 完成微软登录，不接触也不上传用户密码
- 刷新令牌与访问令牌经 Android Keystore（不可导出密钥）+ AES-GCM 加密后存于应用私有目录
- 不使用任何统计、遥测或第三方 SDK；聊天内容只在本地展示

## 许可

本目录内的 Kotlin 代码是从 MCC（CDDL 1.0）移植的衍生作品，遵循仓库根目录的 [LICENSE.md](../../LICENSE.md)（CDDL 1.0）。