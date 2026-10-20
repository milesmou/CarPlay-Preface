# 星瑞 E02 固件资料索引

本页是当前资料入口。详细接口、证据路径、行号、SHA-256 与历史运行观察统一保存在 [事实索引](firmware/ihu519g-327.json)；不重复抄录每次修改和构建流水账。

## 固件身份与边界

| 项目 | 参考值 |
| --- | --- |
| 原始文件 | 用户提供的 23星瑞1.5T固件.zip，最初名 update.zip，2,519,405,530 字节 |
| OTA SHA-256 | f61a7399d61a7f4502b9289ea6c49c661c39c459aae81e676722bcf9be913c01 |
| 产品／型号 | IHU519G / FS11A1，ecarx |
| 平台／系统 | ECARX_E02_V1.3，Android 9 / API 28 / 构建 327 |
| 系统指纹 | ecarx/IHU519G/IHU519G:9/PQ2A.190405.003/327:user/dev-keys |
| 安全补丁／ABI | 2021-02-05；arm64-v8a,armeabi-v7a,armeabi |
| OTA 格式 | 完整 BLOCK OTA，transfer list v4，Brotli system/vendor |

文件名不能证明所有星瑞 E02 固件共享接口。此前运行报告来自 **IHU353G**，不能视为此 OTA 的实车验证。系统声明、exported 服务、SDK 方法或权限常量存在，也不等于本应用有准入或功能已经可用。

用户确认屏幕为 2023 款星瑞 12.3 英寸、1920×720 横屏；应用逻辑密度、窗口与截图规则见 [屏幕适配](PREFACE_SCREEN_LAYOUT.md)。

## 按问题查资料

| 主题 | 当前说明与证据 |
| --- | --- |
| 方控、输入观察者、SDK 准入 | [构建 98 Siri 方控](PREFACE_SIRI_WHEEL_2026-10-20.md)、[QQ 授权接入、保留原签名](QQ_MEDIA_AUTHORIZATION.md)、[构建 327 分析](PREFACE_FIRMWARE_327.md)、[Linkco 媒体按键](LINKCO_APK_MEDIA_KEYS_REFERENCE.md)、[完整媒体链路移植](PREFACE_LINKCO_MEDIA_REPLICA.md) |
| 音频流、焦点、导航混音 | [构建 98 通话音量残留](CONNECTION_REPORT_2026-10-20_BUILD98_VOLUME.md)、[Linkco 音频](LINKCO_CARPLAY_AUDIO.md)、[音频方案](BLUETOOTH_MUSIC_WHEEL.md)、[元数据](PREFACE_MEDIA_METADATA.md) |
| 蓝牙交接、自动暂停、方控跳页 | [构建 100：组件交接生效、助手被拒](CONNECTION_REPORT_2026-10-20_BUILD100_AUDIO.md)、[构建 99 自动暂停与切歌转蓝牙](CONNECTION_REPORT_2026-10-20_BUILD99_AUDIO.md)、[构建 97 原车日志与修正](CONNECTION_REPORT_2026-10-20_BUILD97.md)、[v0.2.20.1 移植](GEELYPLAY_BLUETOOTH_0.2.20.1.md)、[后续修正](BLUETOOTH_WHEEL_RECOVERY_2026-10-19.md)、[构建 91 报告](CONNECTION_REPORT_2026-10-19_BUILD91.md) |
| 连接入口、取消与自动连接 | [主页优先级](HOME_CONNECTION_PRIORITY.md)、[自动连接](AUTOMATIC_CONNECTION.md)、[蓝牙重连复核](BLUETOOTH_AUTO_RECONNECT_2026-10-16.md) |
| 连接耗时与提速评估 | [构建 98 实车时序](CONNECTION_STARTUP_TIMING_2026-10-20.md)：无线约 18 秒、三次 USB 约 17–22 秒；主要等待在网络建链，尚未修改连接流程或验证提速。 |
| 无线热点与生命周期 | [手动／临时热点](MANUAL_HOTSPOT.md)、[HiCar 证据](HICAR_HOTSPOT_327.md)、[热点恢复](HOTSPOT_BLUETOOTH_RECOVERY_2026-10-16.md)、[清理屏障](WIRELESS_TEARDOWN_RECOVERY.md) |
| USB 当前方案 | [官方 v0.2.20 NCM＋本地 VPN/TUN](USB_VPN_RESTORE_0.2.20.md)、[VPN 固件证据](PREFACE_VPN_FIRMWARE_ANALYSIS_2026-10-16.md) |
| 授权组件、系统 UID、平台签名 | [组件说明与当前构建核验](ANDROID9_AUTHORIZATION_COMPONENTS.md)、[系统授权](SYSTEM_AUTHORIZATION.md)、[原生设置对照](NATIVE_SETTINGS_AUTHORIZATION_REFERENCE_2026-10-19.md) |
| 显示、快捷操作、桌面 | [固定协商像素](PREFACE_FIXED_RESOLUTION.md)、[快捷操作](QUICK_ACTIONS_UI.md)、[主页导航](CARPLAY_HOME_NAVIGATION.md)、[桌面集成](LAUNCHER_INTEGRATION.md) |
| 其他 APK 参考边界 | [QQ／网易云辅助工具](MUSIC_HELPER_REFERENCE_2026-10-20.md)、[Linkco 无线](LINKCO_APK_WIRELESS_REFERENCE.md)、[E02 市场包](E02_APPSTORE_STEERING_REFERENCE.md)、[网易服务](NETEASE_TRACK_SERVICE_REFERENCE.md)、[下游音频设计](HUANGWITS_AUDIO_DESIGN_2026-10-19.md) |

## 当前结论

- 当前源码按用户最新要求[移除独立媒体助手](MEDIA_ASSISTANT_REMOVAL_2026-10-20.md)，包括模块、探测、IPC、打包校验及导出，保留授权组件 10 的蓝牙交接和主应用自身原厂 SDK 检查。本次尚未重新打包，最近测试包仍为构建 100。该包的 IHU353G / 系统 169 [实车日志](CONNECTION_REPORT_2026-10-20_BUILD100_AUDIO.md)确认无线／USB A2DP 实际断开及退出恢复，一次无线 NEXT 成功；助手已安装且拥有 OEM 权限，仍被服务端拒绝准入。退出代理连接泄漏告警另行保留，未在本次移除中修改。
- 最新构建 99 的 IHU353G / 系统 169 实测中，无线一次、USB 两次均出现 CarPlay NEXT 与原车 AVRCP NEXT 并行、随后蓝牙重获焦点；A2DP 交接因隐藏 API 访问失败而保留原连接，蓝牙在失焦／无焦点时向 iPhone 发送 PAUSE。QQ license 和配套参数已核对在包内，OpenAPI 仍明确拒绝本应用 mediacenter；本轮只记录分析，未修改运行代码或打包。详见 [构建 99 音频报告](CONNECTION_REPORT_2026-10-20_BUILD99_AUDIO.md)。
- E02 默认媒体／导航流 3／12，方控媒体广播直接接入。Linkco 参考流程在实际导航发声时只将本应用音乐压至 30%，通话／Siri 发声时静音音乐，结束后按有效焦点恢复；用户保存的路由和焦点关闭选择优先。
- 构建 98 用户确认两种音频方案切歌正常；同次 IHU353G / 系统 169 日志确认通话结束后本应用尝试恢复 MODE_IN_CALL 被权限检查拒绝，残留 MODE_IN_COMMUNICATION，音量键持续控制通话流。源码改为 MODE_NORMAL 释放本应用请求，43 项定向测试通过，未打包或实车验证此修正；媒体中心 ready=false 仍不能描述为厂商准入成功。
- 同份日志确认物理语音键 298、事件 200231 和语音广播正常上报；残留通话模式会阻断 Siri。另修正源码中蓝牙模式漏注册语音接收器、CarPlay 前台 Siri 依赖音乐焦点的问题，82 项定向测试通过；默认广播路径无需无障碍服务，未打包或实车验证。
- 输入观察者是多路分发接口，不能宣称独占按键或阻止原车跳页。构建 97 的 IHU353G 原车日志已确认 OpenAPI 拒绝本应用的 mediacenter 授权，原厂媒体中心同时处理切歌广播并启动原车播放器；应用内去重不能阻止另一进程处理。现有普通 Android 授权组件不能补发厂商 license，仍需有效的厂商准入方案。
- 同次日志确认蓝牙进程在 audioFocus=0 时主动发 AVRCP 暂停；构建 98 源码增加原车蓝牙播放器的一次 prepare 请求，效果仍待实车验证。组件 9 修复非导出 VPN 服务被误判缺失，不能据此认定 USB/TUN 已恢复。
- 当前 USB 直接接管 iPhone NCM，桥接到仅本应用可用、仅路由 fe80::/64 的本地 TUN，不需 root。旧系统网卡／无 VPN 主流程、失败后的独立原始帧测试均已退出；[旧帧诊断](USB_NCM_FRAME_DIAGNOSTICS.md) 只作历史证据，不与实时桥接并用。
- 无线只有临时热点和手动车机热点。临时预约在手动断开后可按接口／地址复核复用，完全退出或改用 USB／手动热点时只释放本应用预约；不得关闭其他持有者的热点。
- 组件 7 使用匹配平台证书、系统共享 UID 和原生状态查询。用户反馈授权恢复正常；不能扩展为 USB、蓝牙或方控已经修复。

## 运行证据

| 报告 | 主要观察 |
| --- | --- |
| [2026-10-14](CONNECTION_REPORT_2026-10-14.md) | 无线曾渲染，USB 认证与网络／VPN 阶段分别记录。 |
| [2026-10-15](CONNECTION_REPORT_2026-10-15.md)、[第五份](CONNECTION_REPORT_2026-10-15_5.md) | 原厂 API 未就绪；USB 网卡／地址就绪不等于 AirPlay 建链。 |
| [构建 65](CONNECTION_REPORT_2026-10-15_BUILD65.md)、[67](CONNECTION_REPORT_2026-10-15_BUILD67.md) | 方控 NEXT 发出仍跳页，USB OUT 失败，无线清理等待。 |
| [构建 74](CONNECTION_REPORT_2026-10-16_BUILD74.md) | 一次有效临时热点复用，蓝牙自动暂停，原始 USB 证据不能代替实时双向通信。 |
| [构建 85](CONNECTION_REPORT_2026-10-16_BUILD85.md) | 热点接口失效与真实断线；应用修正后仍待新报告。 |
| [构建 91](CONNECTION_REPORT_2026-10-19_BUILD91.md) | API ready=false、NEXT 后离开前台；蓝牙播放后短时间暂停，缺少原车播放状态及命令来源。 |
| [构建 97](CONNECTION_REPORT_2026-10-20_BUILD97.md) | 已有原车进程证据：OpenAPI 拒绝 mediacenter；原厂 NEXT 启动播放器；蓝牙焦点为 0 时发暂停。VPN 校验误拒非导出服务，源码已修正。 |
| [构建 98](CONNECTION_REPORT_2026-10-20_BUILD98_VOLUME.md) | 用户确认两种切歌正常；日志定位通话后通信模式未释放，系统音量事件均为通话流。源码已修正，尚未打包或复测。 |
| [构建 99](CONNECTION_REPORT_2026-10-20_BUILD99_AUDIO.md) | 无线和 USB 均复现蓝牙发 PAUSE、原车方控让蓝牙夺回焦点；QQ 资料已打包，媒体中心授权仍被拒绝。本轮仅分析。 |
| [构建 100](CONNECTION_REPORT_2026-10-20_BUILD100_AUDIO.md) | 组件 10 的无线／USB A2DP 交接及退出恢复已有实际状态证据，一次方向盘 NEXT 成功；助手自己的媒体中心准入仍明确被拒，另记录退出代理连接泄漏告警。 |

历史报告保持各自版本和时间，不能用后来的源码说明覆盖当时运行事实。当前构建结果见 [组件与构建核验](ANDROID9_AUTHORIZATION_COMPONENTS.md)，不逐次在本页复制。

后续已按用户要求构建 **0.1.1 / 99** 并核验两项修复、主包原签名及内置组件 9，已覆盖安装到在线模拟器。上文音量、Siri 的“未打包”为源码阶段记录；随后收到的构建 99 音频日志及本轮源码 100 的未打包边界，见本页最新结论和对应报告。

## 本地证据与复用

根目录：`agent_docs/firmware/ihu519g-327/`，保留分区镜像、提取文件、反编译、原始检查结果与 catalog。完整文件只留本机 Git 忽略目录。

| 路径 | 用途 |
| --- | --- |
| catalog/index.json | 本地索引总入口，以实际内容为准 |
| catalog/extracted-files.json | 提取文件、大小与校验值 |
| catalog/apk-manifests.json、catalog/manifests/ | APK 权限、组件和 intent-filter 声明 |
| catalog/adapter-api-index.json、adapter-api-constants.json | SDK 签名与常量，仍须核对车型分支和反编译警告 |
| catalog/configuration-trees.json、declared-codecs.json | 实际 XML 节点及 codec 声明，不能代替运行时能力 |
| catalog/declared-features.json、init-services.json | feature 和服务声明，完整启动条件查原始文件 |
| catalog/*authorization*、*appops*、*bluetooth* | 授权、系统状态查询、蓝牙代理的专项静态证据 |

复用缓存生成索引：

```powershell
python scripts/firmware_catalog.py --cache agent_docs/firmware/ihu519g-327 --aapt2 D:/Tools/AndroidSdk/build-tools/36.0.0/aapt2.exe
```

原始重建与 CompactDex 脚本在 agent_docs/firmware/，JADX、Brotli 工具在其 tools/ 下。缓存缺失时重新取得已核对的原固件；不下载替代固件、不执行更新二进制或刷写。迁移工作机时一并保留证据缓存。

## 待实车验证

1. 核对系统身份、平台证书、最终屏幕／窗口、音频设备及 codec。
2. 分别验证厂商 SDK 准入、方控去重、音源切回、媒体／导航并发、电话和 Siri。
3. 检查 USB 授权、实时双向 NCM/TUN、拔插重连；热点复用、重建和后台自动连接。
4. 检查冷启动、休眠、唤醒和后台限制；诊断公开前去除 VIN、唯一 ID、配对地址和网络凭据。
