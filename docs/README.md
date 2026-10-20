# 文档索引

适配 **2023 款吉利星瑞 1.5T（E02）车机，无飞屏功能**。应用版本 0.2.0，正式版和测试版统一包名 `com.preface.carplay`；上游基线为 DiPlay v0.2.21（三项选择性同步）。

| 目标 | 文档 |
| --- | --- |
| 安装、连接和反馈 | [安装与连接](INSTALL.md)、[兼容性](COMPATIBILITY.md)、[隐私](PRIVACY.md) |
| 构建、签名和发布 | [构建说明](BUILD.md)、[验证范围](TESTING.md)、[认证资料](CARPLAY_AUTH_RESOURCES.md)、[0.2.0 发布说明](RELEASE-NOTES-0.2.0.md) |
| 主页与自动连接 | [连接优先级](HOME_CONNECTION_PRIORITY.md)、[自动连接与开机自启](AUTOMATIC_CONNECTION.md) |
| 无线和 USB | [临时／手动车机热点](MANUAL_HOTSPOT.md)、[NCM＋本地 VPN](USB_VPN_RESTORE_0.2.20.md) |
| 授权 | [使用入口](SYSTEM_AUTHORIZATION.md)、[授权组件](ANDROID9_AUTHORIZATION_COMPONENTS.md) |
| 显示与操作 | [屏幕基准](PREFACE_SCREEN_LAYOUT.md)、[协商分辨率](PREFACE_FIXED_RESOLUTION.md)、[快捷菜单](QUICK_ACTIONS_UI.md) |
| 音频与方控 | [音频方案](BLUETOOTH_MUSIC_WHEEL.md)、[按键学习](WHEEL_MEDIA_LEARNING.md)、[Siri](PREFACE_SIRI_WHEEL_2026-10-20.md)、[电话接听／挂断](PREFACE_PHONE_WHEEL_2026-10-20.md) |
| 工程与来源 | [代码结构](CODE_STRUCTURE.md)、[固件证据](PREFACE_FIRMWARE_REFERENCE.md)、[上游同步](UPSTREAM_SYNC_0.2.21.md)、[许可证](THIRD_PARTY_NOTICES.md) |

已删除废弃连接方案、其他车型功能说明、旧测试汇总和重复修改记录。历史运行报告、固件事实索引及来源校验保留各自版本边界，不能当作当前版本的实车验收结果。原始日志、构建缓存和签名输入仅保存在 Git 忽略的 `agent_docs/`。
