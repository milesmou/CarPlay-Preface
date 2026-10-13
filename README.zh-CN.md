# CarPlay-Preface

[English](README.md) · [上游 DiPlay](https://github.com/shihabal3amri/DiPlay)

本仓库基于 DiPlay，主要面向**吉利星瑞车型的 E02 芯片版本车机**进行 CarPlay 适配。

本仓库应用版本：**0.1.0**；上游基线：[DiPlay v0.2.18](https://github.com/shihabal3amri/DiPlay/releases/tag/v0.2.18)。后续同步上游时，更新上游基线版本，并递增本仓库应用版本。

[0.1.0 预览版](https://github.com/milesmou/CarPlay-Preface/releases/tag/v0.1.0) · [发布说明](docs/RELEASE-NOTES-0.1.0.md)。附件为签名源码预览 APK，独立连接 iPhone 需另行配置 CarPlay 配件认证资源；实车验证及完整 CI 尚未全部通过。

## 相对上游的改动

- **适配目标**：主要面向星瑞 E02 版本车机。
- **固件 327 方控接入**：根据星瑞 IHU519G／FS11A1 固件分析，接收原厂媒体按键广播，实现上／下一曲及播放／暂停；广播、MediaSession 和前台窗口共用去重处理，支持拥有媒体焦点时后台切歌。该固件默认使用媒体流 3、导航流 12，保留用户路由设置与原车音量键处理。详见 [固件分析](docs/PREFACE_FIRMWARE_327.md)，仍待实车验证。
- **设置入口精简**：移除“概览”，默认进入“连接”；宽屏左侧分类、窄屏横向分类直接切换设置。
- **方控与独立音量**：CarPlay 前台处理标准播放／暂停、上／下一曲，车载蓝牙音频模式避免重复转发；支持媒体和导航输出比例独立调节。导航播报期间标准音量键可仅调节导航输出，通用后台按键处理需要无障碍服务。其他 E02 固件接口仍需单独确认，尚未实车验证。
- **实验性菜单与名称**：实验性功能集中到“实验性”菜单；应用名改为 CarPlay，车辆按钮默认使用生成的星瑞图标和“星瑞”名称。
- **仅保留中文界面**：移除设置页和 CarPlay 内的语言入口，应用固定使用简体中文，不再跟随系统或旧语言偏好。
- **首次设置精简为四步**：连接方式 → iPhone → 自动连接与位置共享 → 完成，移除车型／DiLink 版本选择。
- **移除比亚迪专属适配**：HUD 与仪表导航、DiLink 仪表显示及任务路由、电池／车速／挡位／胎压读取、氛围灯、专用通话卡片与按键码、蓝牙暂停、Wi-Fi 扫描暂停及开机启动修复。
- **保留通用功能**：有线／无线 CarPlay、Android 位置共享、媒体与 Siri、方向盘按键学习、吉利输入服务、桌面／中控地图及可选 FCE 导航输出。这些代码改动不代表已经完成星瑞 E02 实车兼容性验证。

## 参考文档

- [星瑞 E02 固件资料索引](docs/PREFACE_FIRMWARE_REFERENCE.md)：方控、音频与麦克风、USB／无线连接、定位／仪表、视频、权限和电源接口的证据及待验证项。
- [上游功能与版本发布](https://github.com/shihabal3amri/DiPlay)
- [安装与连接](docs/INSTALL.md)
- [从源码构建](docs/BUILD.md)：主应用请选择 `mobile` 模块。
- [兼容性与问题排查](docs/COMPATIBILITY.md)
- [来源与许可证](docs/THIRD_PARTY_NOTICES.md)

反馈问题时，请注明星瑞年款、E02 车机固件及 Android 版本、iPhone/iOS 版本、连接方式和复现步骤。
