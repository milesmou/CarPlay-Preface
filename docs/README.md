# 文档索引

本仓库专用于星瑞 E02。当前源码为 **0.1.1 / 97（未打包）**，最近测试包为 **0.1.1 / 95**，上游参考 **DiPlay v0.2.21（三项选择性同步）**。当前行为优先查下表；历史运行报告保留在固件资料入口，不作为新版本验证结论。

## 使用与维护

| 目标 | 文档 |
| --- | --- |
| 安装、连接和反馈 | [安装与连接](INSTALL.md)、[兼容性](COMPATIBILITY.md)、[隐私](PRIVACY.md) |
| 模块、状态与存储边界 | [工程结构](CODE_STRUCTURE.md) |
| 构建、签名和交付 | [构建说明](BUILD.md)、[验证范围](TESTING.md)、[认证资料](CARPLAY_AUTH_RESOURCES.md) |
| 主页、自动连接和取消 | [主页优先级](HOME_CONNECTION_PRIORITY.md)、[两个自动连接选项](AUTOMATIC_CONNECTION.md) |
| 无线热点、USB | [热点配置与生命周期](MANUAL_HOTSPOT.md)、[NCM＋本地 VPN](USB_VPN_RESTORE_0.2.20.md) |
| 授权 | [使用入口](SYSTEM_AUTHORIZATION.md)、[组件、升级与构建核验](ANDROID9_AUTHORIZATION_COMPONENTS.md) |
| 屏幕与设置 | [屏幕基准](PREFACE_SCREEN_LAYOUT.md)、[固定分辨率](PREFACE_FIXED_RESOLUTION.md)、[说明和间距](SETTINGS_COPY_SIMPLIFICATION.md)、[快捷操作](QUICK_ACTIONS_UI.md) |
| 音频与方控 | [Linkco 音频](LINKCO_CARPLAY_AUDIO.md)、[音频方案](BLUETOOTH_MUSIC_WHEEL.md)、[蓝牙与方控跟踪](BLUETOOTH_WHEEL_RECOVERY_2026-10-19.md) |

## 工程依据与来源

- [固件资料总入口](PREFACE_FIRMWARE_REFERENCE.md)：接口主题、历史连接报告、本地原始证据及再生成方法。
- [构建 327 接入分析](PREFACE_FIRMWARE_327.md)与[机器可读事实](firmware/ihu519g-327.json)：静态证据、来源和待验证项。
- 选择性同步：[v0.2.19](UPSTREAM_SYNC_0.2.19.md)、[v0.2.20](UPSTREAM_SYNC_0.2.20.md)、[v0.2.21](UPSTREAM_SYNC_0.2.21.md)；保留实际 SHA、移植范围与排除项。
- 下游参考：[蓝牙流程移植](GEELYPLAY_BLUETOOTH_0.2.20.1.md)、[当前音频设计分析](HUANGWITS_AUDIO_DESIGN_2026-10-19.md)。
- [来源与许可证](THIRD_PARTY_NOTICES.md)、[原始上游 README](UPSTREAM-README.md)、[本仓库 0.1.0 发布说明](RELEASE-NOTES-0.1.0.md)。

已删除其他车型、已废弃连接／显示功能指南，以及重复的上游旧版发布记录和评估草稿；必要来源由同步记录、许可证和上游链接保留。带日期的运行报告、事实索引及认证来源继续保留。USB 旧系统网卡／无 VPN 文档仅是历史诊断，当前以 NCM＋本地 VPN 说明为准。

## 本地资料

agent_docs/ 被 Git 忽略，保留构建环境、授权组件签名输入、固件／APK 参考、真实诊断、当前构建核验和必要截图。目录用途与清理范围见本机 agent_docs/README.md。

静态分析、单元测试渲染、模拟器安装和实车结果仍分别记录。
