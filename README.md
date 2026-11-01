# ZstdNet Client

与 [ZstdNet Velocity 插件](https://github.com/sitbat/zstdnet-velocity) 配套的 Fabric 客户端。客户端与 Velocity 之间先进行 Zstd 压缩，再使用原版 AES 加密；继续使用正版认证和代理的 modern forwarding。

当前分支构建 Minecraft **1.21.11** 客户端 **2.1.2**。

| 分支 | Minecraft | 客户端版本 | Java |
| --- | --- | --- | --- |
| `main` | 1.21.11 | 2.1.2 | 21 |
| [`mc/26.3`](https://github.com/sitbat/zstdnet-client/tree/mc/26.3) | 26.3 | 2.2.2 | 25 |

## 安装

从 [Releases](https://github.com/sitbat/zstdnet-client/releases) 下载对应 Minecraft 版本的客户端 JAR，放入游戏实例 `mods/`；移除同模组旧版本。模组 ID 为 `zstdnet_proxy`，不能与旧版 `zstdnet` 同装。

Java 21；Fabric Loader 0.19.2、Fabric API 0.140.2+1.21.11。

Velocity 端安装配套仓库的插件，固定目标为 **Velocity 4.3.0 build 44**。后端不安装本客户端。原游戏地址和端口保持不变。代理正版验证及 FabricProxy-Lite 的认证/转发配置见插件仓库。

26.3 客户端若连接其他游戏版本，仍需有效的协议转换器。26.3 的 2.2.1 修复了启用 Zstd 后跳过 ViaFabricPlus 出站转换的问题；具体回归范围见该分支的 VALIDATION.md。

2.1.2 / 2.2.2 修复 `/tick` 高速运行或延迟后集中投递导致的 `Zstd packet budget exceeded` 断连。客户端接收服务器数据时不再套用代理防刷包的每秒包数/字节上限；单包、压缩帧及 Zstd 窗口限制保持有效。代理插件继续使用 2.1.0，无需更换。1.21.11 的 2.1.2 同时包含此前的 Via 出站顺序修复，详见 [TICK-RATE-FIX.md](TICK-RATE-FIX.md)。

## HUD、统计与报告

HUD 默认关闭，`/zstdhud on|off|toggle` 切换并保存；也可使用 `/zstdnet hud ...`。显式旧配置会保留。F1 跟随隐藏；打开 F3 图表时面板上移。

- `/zstdnet status`：压缩协商状态与累计原始/线路字节。
- 原版 F3 接收带宽图补入 Zstd 线路字节，避免重复计数。
- `/zstdreport today|session|24h|7d|30d`：从代理下载数据并生成自包含 HTML；代理必须授予报告权限。

报告位于当前实例的 `config/zstdnet/reports/`，聊天栏提供打开链接。报告的代理权限、历史范围和汇总口径见服务端仓库。

配置 `config/zstdnet-proxy-client.properties`：

```properties
enabled=true
level=3
debug=false
hud=false
```

压缩等级为 1–22。原始流量指 Zstd 内的 Minecraft 帧；线路流量包含 Zstd 头，不包含 TCP/IP 头或协商前流量。UDP 不处理。

## 构建

此仓库可独立构建，无需克隆服务端仓库或下载 Velocity。

设置 JDK 21 的 `JAVA_HOME` 和 `JAVA21_HOME`：

```powershell
$env:JAVA_HOME = $env:JAVA21_HOME
./gradlew.bat :common:test :client:test :client:build
```

Gradle 8.14 / Loom 1.13.1，交付生产 remapJar。

生产 JAR 位于 `client/build/libs/`，不要安装单独的 smoke 测试 JAR。`common/` 是本仓库内的公共协议/压缩/报告库，不依赖未发布的外部 Maven 包。

无窗口加载检查：

```powershell
./gradlew.bat :client:runSmoke -x :client:downloadAssets
```

验证范围见 [VALIDATION.md](VALIDATION.md)。自动化验证不等于完整模组包、实际 HUD 画面和正版账号联机验收。

## 许可

MIT；原项目与参考提交见 [NOTICE.md](NOTICE.md)。
