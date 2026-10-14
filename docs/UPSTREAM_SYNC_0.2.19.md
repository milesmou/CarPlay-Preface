# 上游 0.2.19 选择性同步记录

本仓库应用版本：**0.1.1 / versionCode 44**。上游发布版本：**DiPlay v0.2.19**，发布提交 `e06a250219b2c2288948d8b4ad7203b417db0de1`。本次按星瑞 E02 需要选择同步，不是完整合并发布树。

同步前 HEAD：`aa6900ab0fcaf733e2e68b3d983e37be76b1c546`；共同上游祖先：`d9881b4ae6b21ab12db1cc4f0f7d3a0857c3f09c`。音频时钟、麦克风读失败恢复、100 ms 音频解码等待、有线 20 ms 写入等待与 iOS 26 USB 模式切换已经继承，未重复引入。

| 上游提交 | 同步范围 |
| --- | --- |
| [54116013](https://github.com/shihabal3amri/DiPlay/commit/541160134f1e12aaae380890b41025909906ce78) | 蓝牙适配器获取回退 |
| [5fbd7171](https://github.com/shihabal3amri/DiPlay/commit/5fbd7171b47984105ecff1f1e9746424c4f0798a) | 启动热点前检查蓝牙，移除比亚迪暂停例外 |
| [b5f1bfe4](https://github.com/shihabal3amri/DiPlay/commit/b5f1bfe42f9961e96e526f13d7116654f58b3d62) | 倒车界面隐藏状态栏时保持会话尺寸 |
| [301c5601](https://github.com/shihabal3amri/DiPlay/commit/301c5601808f6d30ada2792bc2e505b2aace7d6d) | SurfaceView 返回时保留最后画面并再次请求关键帧 |
| [59c26525](https://github.com/shihabal3amri/DiPlay/commit/59c2652592ce92d2fb22e4554858deb0bbaa4656) | Android 7.1–9 缺失 USB 授权页面时尝试已授权 ADB |
| [74569fe6](https://github.com/shihabal3amri/DiPlay/commit/74569fe680a9e6363e0a8c2e3829c48cffd0091a) | Windows shell 与 FileProvider 测试修复 |
| [9b169ee3](https://github.com/shihabal3amri/DiPlay/commit/9b169ee38fc4dfe6a2c45ba8b6dd93056b361596) | 连接失败提示 |
| [65087c2a](https://github.com/shihabal3amri/DiPlay/commit/65087c2a575fc3424449085c707036f72bcd2e77) | 按错误和连接模式选择可执行提示，改为临时热点 |
| [4081472f](https://github.com/shihabal3amri/DiPlay/commit/4081472f5c67eb1e9929c36578633b9980723aab) | 本地页面空闲连接不阻塞请求 |
| [468d18cd](https://github.com/shihabal3amri/DiPlay/commit/468d18cd4bedbe06ae0fdb9bf17940d34f52ae49) | 手机下载诊断报告 |
| [f57c36b1](https://github.com/shihabal3amri/DiPlay/commit/f57c36b1d1a3de7e0ed3d48675e9eee19a9102db) | 手输地址与临时验证码 |

手机页面以 v0.2.19 发布树为参考，移除其他语言并固定简体中文；不引入上游新增的多语言支持。

## 星瑞分支调整

- 无线默认且仅使用 LocalOnlyHotspot。工程尚未正式发布，已删除旧热点模式及其他历史配置的迁移逻辑，直接读取当前设置或使用默认值。
- 删除原车热点自动开启、ADB 热点配置修改和热点加入修复的实现及对应测试。原先 3 项 AtomicFile 失败所属的原车热点修复模块随功能一起移除，不将删除测试描述为实车问题已解决。Windows 通用 shell 和 FileProvider 测试修复继续保留。
- 连接失败提示针对临时热点、定位、蓝牙、USB 和接口可用性；不导入 Wi-Fi Direct、同一局域网或其他车型模式。
- 保留星瑞媒体／导航流 3／12、由车机统一控制音量、中文界面、现有签名与认证资源；不导入 DiLink 14／15 导航默认值、仪表专属启动门控、MIUI 车型分支、多语言、网站或上游版本号。

## 验证范围

2026-10-13 本地验证结果：

- 执行 `.github/workflows/android.yml` 规定的完整命令成功：shared 864、common 940、home 4，共 1808 项单元测试，零失败；mobile、home、maphost 的 Lint 与 Debug 构建全部通过。复跑时遇到 Windows 构建 JAR 占用，停止 Gradle 守护进程后重新执行通过，未跳过检查。
- 主应用 APK 使用 `signing/` 中的既有证书，SHA-256 指纹 `75f4eda0d13be105f578253381eadb5619c04548cc78f58faf2cc8f73a2660a8`。已通过 `install -r` 安装到在线 Android 14 模拟器 `emulator-5554`，启动后保存模式为 `LOCAL_ONLY_HOTSPOT`，旧网络字段为零；APK 中不含已删除的旧热点管理类或 APK 签名材料。
- 检查宽屏、窄屏及 1.4 倍字体界面；手机页面在英文和阿拉伯文浏览器语言下仍显示简体中文。模拟器上的本地 HTTP 流程验证了验证码进入、诊断报告下载、返回诊断菜单和关闭页面后停止服务。
- 公开文件检查与 `git diff -w --check` 通过。日志、截图及诊断报告仅保存在本地忽略目录 `agent_docs/upstream/sync-0.2.19/validation/`。

星瑞 E02 真车的临时热点创建、iPhone 自动加入、倒车返回、USB 授权和长时间连接仍需验证；模拟器界面检查与固件静态证据不能代替这些结果。
