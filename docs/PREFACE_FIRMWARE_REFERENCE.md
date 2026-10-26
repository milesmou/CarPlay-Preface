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

用户确认适配车机为 2023 款星瑞 1.5T，无飞屏功能；屏幕为 12.3 英寸、1920×720 横屏；应用逻辑密度、窗口与截图规则见 [屏幕适配](PREFACE_SCREEN_LAYOUT.md)。

## 按问题查资料

| 主题 | 当前说明与证据 |
| --- | --- |
| 方控、输入观察者、SDK 准入 | [Siri 方控](PREFACE_SIRI_WHEEL_2026-10-20.md)、[构建 327 分析](PREFACE_FIRMWARE_327.md)、[音频方案](BLUETOOTH_MUSIC_WHEEL.md) |
| 电话接听／挂断键 | [电话键接入](PREFACE_PHONE_WHEEL_2026-10-20.md)：299→200005 短按接听／挂断，独立于音乐焦点；HFP 存在或未知时保留原车，避免双发。94 项定向测试通过，实车验证尚未完成。 |
| 音频流、焦点、导航混音 | [通话音量残留](CONNECTION_REPORT_2026-10-20_BUILD98_VOLUME.md)、[音频方案](BLUETOOTH_MUSIC_WHEEL.md)、[元数据](PREFACE_MEDIA_METADATA.md) |
| 车机电话音频 | [Telecom 权限与准入分析](PREFACE_PHONE_AUDIO_TELECOM_2026-10-21.md)：1205 曾被拒绝、1206 辅助通话成功，音质改善仍待验证。后续源码移除实验性标志，未保存时默认开启并保留保存值；仅在当前会话采用 CarPlay 音频时请求辅助通话，蓝牙音频保留原车处理，通话状态照常更新；尚未打包。 |
| CarPlay 通话麦克风补充日志 | [诊断字段与复测](CALL_MICROPHONE_DIAGNOSTICS_2026-10-21.md)、[1206 实车分析](CONNECTION_REPORT_2026-10-21_BUILD1206_MICROPHONE.md)：新字段已有 IHU353G／169 运行证据；手动 ADC 增益在采集启动时被覆盖，采集／上传电平统计一致，辅助通话本轮成功。根因及人工对端听感仍待确认。 |
| 高德首次顶部按钮失效 | [1206 输入分析](PREFACE_MAP_FIRST_CONTROLS_2026-10-21.md)：用户确认仅连接后首次显示的四个按钮失效，隐藏重显恢复，拖动／缩放正常；左上连续点击已有完整 HID 报告，无直接本地吞键证据。苹果地图未对照，根因和修复均未确认。 |
| 蓝牙交接、自动暂停、方控跳页 | [构建 100：组件交接生效、助手被拒](CONNECTION_REPORT_2026-10-20_BUILD100_AUDIO.md)、[构建 99 自动暂停与切歌转蓝牙](CONNECTION_REPORT_2026-10-20_BUILD99_AUDIO.md)、[构建 97 原车日志与修正](CONNECTION_REPORT_2026-10-20_BUILD97.md)、[v0.2.20.1 移植](GEELYPLAY_BLUETOOTH_0.2.20.1.md)、[后续修正](BLUETOOTH_WHEEL_RECOVERY_2026-10-19.md)、[构建 91 报告](CONNECTION_REPORT_2026-10-19_BUILD91.md) |
| 连接入口、取消与自动连接 | [主页优先级](HOME_CONNECTION_PRIORITY.md)、[自动连接](AUTOMATIC_CONNECTION.md)、[蓝牙重连复核](BLUETOOTH_AUTO_RECONNECT_2026-10-16.md) |
| 连接耗时与提速评估 | [历史时序与源码改动](CONNECTION_STARTUP_TIMING_2026-10-20.md)、[构建 1205 实车报告](CONNECTION_REPORT_2026-10-21_BUILD1205.md)：新建热点准备由旧版 3.390 秒降至约 1.04 秒；完整无线连接 15.720／21.218 秒、复用 6.423 秒，仍有波动。 |
| 无线热点与生命周期 | [手动／临时热点](MANUAL_HOTSPOT.md)、[HiCar 证据](HICAR_HOTSPOT_327.md)、[热点恢复](HOTSPOT_BLUETOOTH_RECOVERY_2026-10-16.md)、[清理屏障](WIRELESS_TEARDOWN_RECOVERY.md) |
| USB 当前方案 | [官方 v0.2.20 NCM＋本地 VPN/TUN](USB_VPN_RESTORE_0.2.20.md)、[VPN 固件证据](PREFACE_VPN_FIRMWARE_ANALYSIS_2026-10-16.md) |
| 授权组件、系统 UID、平台签名 | [组件说明与当前构建核验](ANDROID9_AUTHORIZATION_COMPONENTS.md)、[系统授权](SYSTEM_AUTHORIZATION.md)、[原生设置对照](NATIVE_SETTINGS_AUTHORIZATION_REFERENCE_2026-10-19.md) |
| 显示、快捷操作、桌面 | [固定协商像素](PREFACE_FIXED_RESOLUTION.md)、[快捷操作](QUICK_ACTIONS_UI.md)、[主页导航](CARPLAY_HOME_NAVIGATION.md)、[桌面集成](LAUNCHER_INTEGRATION.md) |
| 无线画质与流畅度优化 | [固件证据与默认设置](PREFACE_WIRELESS_VIDEO_2026-10-20.md)：HEVC、直出及 MTK 低延迟未保存时默认开启，保留已保存选择、能力检查与回退，已纳入 0.2.1／1204 正式包；用户明确实测未见异常的是 MTK 专用选项。延迟改善尚无对照统计，无重排桥接未证实。 |

## 当前结论

- 高德导航仪表方向链路及 [实验性接入](PREFACE_INSTRUMENT_NAVIGATION_2026-10-26.md)：原厂 `NaviService` 经仪表 SDK 发送 NAV F3；CarPlay 新增默认关闭的“仪表导航提示（实验性）”，复用运行时编码器和固定车辆属性，不操作投屏／电源。已纳入 0.2.2／1208 测试包，权限、原车状态仲裁和实际仪表显示均待实车验证。

- 主应用 0.2.0 统一包名为 `com.preface.carplay`，保持原证书，授权组件为 11；改包名不等于取得厂商媒体准入，需重新授权和实车复核。
- 独立媒体助手已移除。构建 100 的旧包实车日志确认组件 10 完成无线／USB A2DP 交接及退出恢复、一次 NEXT 成功；助手自身仍被拒绝准入。代理退出泄漏告警未在本次修改。
- 媒体／导航流默认 3／12；导航发声时本应用音乐压至 30%，通话／Siri 发声时静音，结束后按有效焦点恢复。用户保存的路由和关闭焦点选择优先。
- 通话模式释放、Siri 接收器及电话键已有源码定向验证；电话键遵循 HFP 原车优先，不能宣称阻止原车电话跳页。当前版本的实车结果与此前报告分别记录。
- USB 使用 NCM＋仅本应用的本地 TUN，只路由 `fe80::/64`。旧系统网卡直连和失败后的独立帧测试不再接入主流程；历史事实保留在 JSON 索引及 Git 历史。
- 无线仅有临时热点和手动车机热点。有效临时预约在手动断开后可复用；退出或改用其他模式只释放本应用预约。
- 组件校验平台证书及系统共享 UID，提供逐项手动授权；蓝牙代理仅接受统一主包及原签名。厂商 SDK ready、token、requestPlay 仍分别真实检查。

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
| [构建 1205](CONNECTION_REPORT_2026-10-21_BUILD1205.md) | 热点后台回读提速已有时序支持，完整无线连接仍波动；USB／无线通话均打开内置输入并持续上传但电平偏低，原车电话模式切换会重建采集处理器，实验电话音频请求被 Telecom 拒绝，麦克风根因及修复仍待验证。 |
| [构建 1206](CONNECTION_REPORT_2026-10-21_BUILD1206_MICROPHONE.md) | 手动 ADC 增益 −8→0→−2 后，CarPlay 录音仍自动写为此前相同的 −3；采集与上传电平统计一致、1508 包本地发送无错误，HAL 自带 AEC／NS，辅助通话本轮创建成功。用户确认调节前后各有 CarPlay 音频通话大致能听清，不支持调节后才改善；人工对端音量仍未确认。 |

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
