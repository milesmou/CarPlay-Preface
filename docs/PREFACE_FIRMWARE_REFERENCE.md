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
| 方控、输入观察者、SDK 准入 | [构建 327 分析](PREFACE_FIRMWARE_327.md)、[Linkco 媒体按键](LINKCO_APK_MEDIA_KEYS_REFERENCE.md)、[完整媒体链路移植](PREFACE_LINKCO_MEDIA_REPLICA.md) |
| 音频流、焦点、导航混音 | [Linkco 音频](LINKCO_CARPLAY_AUDIO.md)、[音频方案](BLUETOOTH_MUSIC_WHEEL.md)、[元数据](PREFACE_MEDIA_METADATA.md) |
| 蓝牙交接、自动暂停、方控跳页 | [v0.2.20.1 移植](GEELYPLAY_BLUETOOTH_0.2.20.1.md)、[后续修正](BLUETOOTH_WHEEL_RECOVERY_2026-10-19.md)、[构建 91 报告](CONNECTION_REPORT_2026-10-19_BUILD91.md) |
| 连接入口、取消与自动连接 | [主页优先级](HOME_CONNECTION_PRIORITY.md)、[自动连接](AUTOMATIC_CONNECTION.md)、[蓝牙重连复核](BLUETOOTH_AUTO_RECONNECT_2026-10-16.md) |
| 无线热点与生命周期 | [手动／临时热点](MANUAL_HOTSPOT.md)、[HiCar 证据](HICAR_HOTSPOT_327.md)、[热点恢复](HOTSPOT_BLUETOOTH_RECOVERY_2026-10-16.md)、[清理屏障](WIRELESS_TEARDOWN_RECOVERY.md) |
| USB 当前方案 | [官方 v0.2.20 NCM＋本地 VPN/TUN](USB_VPN_RESTORE_0.2.20.md)、[VPN 固件证据](PREFACE_VPN_FIRMWARE_ANALYSIS_2026-10-16.md) |
| 授权组件、系统 UID、平台签名 | [组件说明与当前构建核验](ANDROID9_AUTHORIZATION_COMPONENTS.md)、[系统授权](SYSTEM_AUTHORIZATION.md)、[原生设置对照](NATIVE_SETTINGS_AUTHORIZATION_REFERENCE_2026-10-19.md) |
| 显示、快捷操作、桌面 | [固定协商像素](PREFACE_FIXED_RESOLUTION.md)、[快捷操作](QUICK_ACTIONS_UI.md)、[主页导航](CARPLAY_HOME_NAVIGATION.md)、[桌面集成](LAUNCHER_INTEGRATION.md) |
| 其他 APK 参考边界 | [Linkco 无线](LINKCO_APK_WIRELESS_REFERENCE.md)、[E02 市场包](E02_APPSTORE_STEERING_REFERENCE.md)、[网易服务](NETEASE_TRACK_SERVICE_REFERENCE.md)、[下游音频设计](HUANGWITS_AUDIO_DESIGN_2026-10-19.md) |

## 当前结论

- E02 默认媒体／导航流 3／12，方控媒体广播直接接入。Linkco 参考流程在实际导航发声时只将本应用音乐压至 30%，通话／Siri 发声时静音音乐，结束后按有效焦点恢复；用户保存的路由和焦点关闭选择优先。
- 输入观察者是多路分发接口，不能宣称独占按键或阻止原车跳页。Linkco 的 MusicClient 注册链已经移植，但 IHU353G 报告多次在注册前 ready=false，厂商准入与跳页原因仍未确认。
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

历史报告保持各自版本和时间，不能用后来的源码说明覆盖当时运行事实。当前构建结果见 [组件与构建核验](ANDROID9_AUTHORIZATION_COMPONENTS.md)，不逐次在本页复制。

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
