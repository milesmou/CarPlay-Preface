# CarPlay-Preface

基于 [DiPlay](https://github.com/shihabal3amri/DiPlay)，适配 **2023 款吉利星瑞 1.5T（E02）车机**，**无飞屏功能**。

应用版本：**0.2.0**。正式版和测试版统一包名：`com.preface.carplay`，使用相同签名，可相互覆盖。上游基线：**DiPlay v0.2.21（选择性同步）**。

## 相对上游的主要改动

- **星瑞屏幕**：默认适配 12.3 英寸、1920×720 横屏，保留界面大小调节。
- **连接**：USB 优先，支持自动连接、临时热点和手动车机热点；USB 使用 NCM＋本地 VPN。
- **音频**：提供 CarPlay／蓝牙两种方案，调整蓝牙交接、导航混音及通话后恢复。
- **方控**：接入切歌、Siri 和电话接听／挂断，保留原车音量与 HFP 电话处理。
- **授权**：主 APK 内置 Android 9 授权组件，保存到下载目录后手动安装。
- **界面与精简**：仅保留中文，简化首页、分类设置和投影快捷菜单，移除其他车型专属适配。

## 使用与文档

- [安装与连接](docs/INSTALL.md) · [兼容性与排查](docs/COMPATIBILITY.md)
- [源码构建](docs/BUILD.md) · [认证资源](docs/CARPLAY_AUTH_RESOURCES.md) · [签名说明](signing/README.md)
- [完整文档](docs/README.md) · [固件资料](docs/PREFACE_FIRMWARE_REFERENCE.md) · [来源与许可证](docs/THIRD_PARTY_NOTICES.md)

反馈问题时，请提供车机固件、iPhone／iOS 版本、连接方式和复现步骤。

## 支持与联系

| 赞助开发者一杯咖啡 ☕ | 联系开发者 |
| :---: | :---: |
| 如果这个项目对你有帮助，欢迎扫码赞助。 | 有问题可以扫码添加微信，联系开发者。 |
| <a href="docs/images/wechat-donate.jpg"><img src="docs/images/wechat-donate.jpg" alt="微信赞助收款码" width="280"></a> | <a href="docs/images/wechat-contact.jpg"><img src="docs/images/wechat-contact.jpg" alt="开发者微信联系二维码" width="280"></a> |

点击图片可查看原图。
