# 来源与许可

本项目以 MIT 许可发布，并保留原始 LICENSE 中的版权通知。

- 连续 Zstd 帧、报告生成器与报告资源参考/复用自 [wish131400/zstdnet](https://github.com/wish131400/zstdnet)，基准提交 `0b38e7c3e65a445ce9b8ed94c19d3badc9b92b88`。
- 审查了 [wish131400/zstdnet-Velocity](https://github.com/wish131400/zstdnet-Velocity) 的 `8b546f8b7482cf147e1aa5869a2318e2ea41db97`。本实现使用 Minecraft 连接内的压缩，不使用该参考项目的外置流代理或身份替换设计。
- [CallmeSHaobe/zstdnet](https://github.com/CallmeSHaobe/zstdnet) 的 `20a26ff789a8953ef00d252f396a2eff468c25eb` 用作 1.21.11 API 迁移参考。

本仓库从原本地配套工程提取客户端与公共库，保留 1.21.11 → 26.3 → ViaFabricPlus 兼容修复的实现顺序。原配套工程没有作为公开仓库发布；这里的提交哈希与原本地工程不同。服务端插件独立维护于 [sitbat/zstdnet-velocity](https://github.com/sitbat/zstdnet-velocity)。

Zstd JNI 以其上游许可随生产包分发；测试所需 ViaFabricPlus/ViaVersion 来自其官方发行包，仅用于本地回归测试，不随本模组分发。
