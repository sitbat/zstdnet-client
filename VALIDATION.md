# 验证范围

## 客户端 2.1.2 / Minecraft 1.21.11

公共库 28、客户端 27 项测试全部通过，共 55 项，0 失败、0 错误、0 跳过。使用 Java 21，验证了 Fabric Loader 0.19.2 / API 0.140.2+1.21.11，以及 Loader 0.20.1 / API 0.141.6+1.21.11；后者的客户端测试、构建及真实加载也通过。Fabric API 元数据继续沿用原有 `*` 声明，此处列出的是实际验证版本。

高速 tick 修复的六项回归包括：单批 20,000 包；40,000 包分四批延迟后合并交付；可信接收真实 136 MiB 原始帧；默认代理 16,384/16,385 包边界；默认代理精确 128 MiB 字节边界；可信方向仍拒绝超大包/帧和异常历史窗口。逐包 accounting 在可信方向仍被调用。

旧限流行为通过仅恢复旧预算判定的负控制稳定复现 `Zstd packet budget exceeded`。新策略通过；不依赖测试循环能在一秒内完成。字节预算负控制仅使用测试夹具固定窗口时间，可信 136 MiB 测试不修改时钟。

两种 Fabric 组合的真实 1.21.11 Fabric/Knot 无窗口加载均成功。烟测使用生产 ClientTransport Layout，让实际 Minecraft 拆包、原生压缩处理器、AES 和流控管线接收 40,000 个合成小包，输出 `ZSTDNET_TRUSTED_SERVER_BURST_OK`。合成包只用于验证传输，不冒充游戏包内容语义或实际 `/tick` 世界验收。Mixin/Accessor、HUD 注册、CONFIG HELLO、原生编码和 F3 计数检查继续通过。两种依赖组合构建的生产包（含嵌套依赖）业务类与资源内容一致，仅清单记录的 Loader/Mixin 构建版本不同。

1.21.11 同步包含此前的 Via 出站边界修复，通用回归覆盖普通写包、原生编码器上下文 raw 写包及转换器自行发包；本分支没有把完整 ViaFabricPlus 模组包或全协议引擎作为实机验收。其余测试保留持续 AES、zlib→Zstd、碎包/粘包、损坏帧、窗口限制、超时、流控、报告和 HUD/F3 范围。

修复与边界见 [TICK-RATE-FIX.md](TICK-RATE-FIX.md)。未据此宣称完成完整模组组合、实际 HUD 画面、真实正版账号或多后端实机验收；极高持续吞吐仍受游戏处理能力限制。

本仓库不发布原始用户日志、机器路径、主机名或服务器地址。历史版本的验证记录保留于 Git 历史。


## 配置发现事件回归

补充真实 Fabric `ClientConfigurationNetworkAddon` 的注册包 → START → 生产 HELLO 事件链。旧检查直接调用 `ClientTransport.onJoin`，只能验证编码，不能证明 START 会在没有后端时触发。本回归确认没有注册/品牌包时不发 HELLO，收到代理 `minecraft:register` 后才发，关闭客户端功能不发，重复注册不重复发。只跳过图形客户端/监听器构造，网络 addon、事件回调、Minecraft 编码器和 AES 均使用真实实现。

可设置 `ZSTDNET_DISCOVERY_FIXTURES` 指向测试目录：先由代理 `PluginMessageProtocolIntegrationTest` 输出 `discovery-<协议号>.bin`，客户端 `runSmoke` 读取该加密探测包并写出 `hello-<协议号>.bin`，再重跑代理同一测试，验证实际客户端加密回包能完成后端准入。该环境变量仅用于测试，不读取玩家日志或账号凭据。

这是验证代码改进，生产客户端源码、版本及已发布 JAR 无需改变。
