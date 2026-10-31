# 验证范围

本文件为脱敏的验证摘要，不包含原始用户日志、机器路径、主机名或实际服务器地址。

拆分前通过：公共库 22、客户端 27 项测试；另有 5 项实际 ViaFabricPlus 5.1.1 内附 Via handler 回归。旧顺序在 2 项一般转包测试与 5 项 Via 测试中均失败，修复后通过。真实 Via handler 使用受控 UserConnection 夹具，验证处理器顺序和指定包号转换，不冒充完整 Via 协议引擎。Loader 0.20.1 / API 0.165.0+26.3 与 Loader 0.19.5 / API 0.161.0+26.3 的编译及无窗口加载均通过。拆分后的结果由发布验证补充。

通用范围：连续 AES 下的 zlib→Zstd 双向切换、碎包/粘包、半帧迁移、限流/流控/读超时、Zstd 历史窗口限制、报告分片/HTML 安全、HUD 速率和重连行为。真实 Fabric/Knot 无窗口检查验证 Mixin/Accessor、HUD 注册、CONFIG HELLO、原生游戏 packet encoder、AES/Zstd 和 F3 计数。

未据此宣称完成 Mac 实机执行、完整模组包、实际 HUD 像素布局、真实正版账号、多后端切服或长时间收益验收。
