# 星瑞 E02 固件资料索引

分析日期：2026-10-13。用于后续 CarPlay 适配、排查和核对接口；本次只提取资料，不增加应用行为。

## 适用固件与资料入口

用户提供的 `23星瑞1.5T固件.zip`（最初文件名 `update.zip`），大小 **2,519,405,530 字节**；SHA-256：

```text
f61a7399d61a7f4502b9289ea6c49c661c39c459aae81e676722bcf9be913c01
```

| 项目 | 固件中的值 |
| --- | --- |
| 产品／设备 | `IHU519G` |
| 型号／制造商 | `FS11A1` / `ecarx` |
| ECARX 平台版本 | `ECARX_E02_V1.3`（`ro.ecarx.version.platform`） |
| Android / API / 构建 | `9` / `28` / `327` |
| 系统指纹 | `ecarx/IHU519G/IHU519G:9/PQ2A.190405.003/327:user/dev-keys` |
| 安全补丁日期 | `2021-02-05` |
| 应用 ABI | `arm64-v8a,armeabi-v7a,armeabi` |
| OTA 格式 | 完整 BLOCK OTA；transfer list v4；Brotli 压缩 system/vendor |

文件名中的年款与排量是提供者命名，不作为所有星瑞 E02 固件共用接口的依据。新固件先核对产品、型号、API、构建及来源校验值，再比较差异。

- [机器可读的适配事实](firmware/ihu519g-327.json)：身份、音频常量、按键映射、服务、权限和证据校验值，可随仓库保留。
- [方控与音频接入分析](PREFACE_FIRMWARE_327.md)：已有代码接入范围、去重及真车验证项。
- [方控学习与处理状态](WHEEL_MEDIA_LEARNING.md)：应用新增上一曲／下一曲／Siri 学习、停用、恢复默认，分别展示申请、收到回调与音源归属；独占处理仍未确认，属于工程实现，不是新增固件可用性证据。
- [2026-10-15 方控整体复核](WHEEL_DESIGN_REVIEW_2026-10-15.md)：6 项本地探针确认跨入口准入、临时语音归属恢复、语义回调改键、摇杆优先级、释放事件及观察者代际状态缺陷；后续[六项修正](WHEEL_DESIGN_FIXES_2026-10-15.md)通过 151 项定向测试，未构建 APK 或进行真车验证。
- [E02 专用 APK 方控参考边界](E02_APPSTORE_STEERING_REFERENCE.md)：实际为智全云联市场 2.3.1，业务代码受 SecShell 加固；可见无障碍和媒体通知服务声明，未能复核其方控链路或阻止原车跳页的机制。
- [2026-10-14 用户连接报告排查](CONNECTION_REPORT_2026-10-14.md)：运行时报告的设备为 IHU353G，身份与参考 OTA 不一致，证据单独记录；无线曾渲染画面，USB 认证通过但网络与 VPN 授权阻塞，不能外推到构建 327。
- [2026-10-15 用户连接报告排查](CONNECTION_REPORT_2026-10-15.md)：IHU353G 上原厂媒体 API 未就绪；USB 网卡、IPv6 与监听已就绪但无 AirPlay TCP，原始诊断未确认双向；临时热点成功记录均为用户手动加入。不能再用旧报告的 VPN 阻塞解释本轮 USB。
- [2026-10-15 第五份报告](CONNECTION_REPORT_2026-10-15_5.md)：用户确认播放／方控均实际走原车蓝牙；日志显示 SDK 351 的具体类路径仍在注册前 `ready=false`，同时 CarPlay 音频持续接收／写入。USB 原始 IN 被识别为 IPv4 DHCP，3 次 OUT 返回 -1，未确认双向。运行证据与用户观察分别记录，不外推到参考 OTA。
- [2026-10-15 构建 65 新报告](CONNECTION_REPORT_2026-10-15_BUILD65.md)：原厂 API 仍未就绪；USB 原始帧明确为两次 DHCP Discover、OUT 三次失败，CDC 先 down 后 up；无线断线后清理等待至少 16 秒未完成。属于 IHU353G 运行证据，具体拒绝原因与清理卡点未确定。
  用户补充首次临时热点连接成功后自动中断、随后改用手动热点；首次日志对应窗口增长触发应用重建并释放临时热点，随后热点重建失败，区别于末尾手动热点会话的清理阻塞。
  后续按用户要求[固定 CarPlay 分辨率](PREFACE_FIXED_RESOLUTION.md)：隐藏导航栏为 1920×720、显示导航栏为 1760×720；停用百分比与画布缩放，临时窗口增长不再触发重建。属于工程修改，待新包实车验证。
  [无线清理与重连修正](WIRELESS_TEARDOWN_RECOVERY.md)核对 JmDNS 3.6.3 的无上限等待，并在确认旧实例 socket 已释放后允许库后台收尾；未知释放状态仍保留屏障。46 项定向测试通过，实际报告卡点待新包验证。
- [Linkco 完整媒体链路移植](PREFACE_LINKCO_MEDIA_REPLICA.md)：补齐 API 实例创建顺序、主线程初始化、进程注册复用、稳定播放记录与厂商回调入队；声明与构建 327 签名核对，IHU353G 实车准入仍待验证。
- [Linkco 注册路径后续修订](LINKCO_APK_MEDIA_KEYS_REFERENCE.md#后续修订默认采用盒子的具体类注册路径)：默认采用 `registerMusic(本应用包名, MusicClient)`，父类实现由车机 SDK 提供。当前 IHU353G 的 API ready 失败发生在注册前，同一路径不等于已经实车修复。
- [网易云切歌服务参考](NETEASE_TRACK_SERVICE_REFERENCE.md)：用户提供包实际为 `com.ecarx.sdk.openapi` 服务端，用户确认同车已安装且网易云切歌正常。静态代码区分调用者未获授权与目标服务未就绪，可用于追查 CarPlay 的 ready=false；不是已经确认的网易云专用按键客户端实现，也不作为参考 OTA 实车验证。
- 本地完整索引：`agent_docs/firmware/ihu519g-327/catalog/index.json`。固件、镜像、APK/JAR、反编译代码及完整索引保存在 Git 忽略目录，可直接继续分析。

最新 16:13 报告及构建 56 后的媒体控制代码审查见 [第四份报告与后续排查](CONNECTION_REPORT_2026-10-14.md)：明确播放／暂停不再转成切换，旧媒体回调和音频通知隔离，后台页面复用更新方控归属。以上为工程实现与定向测试，不是实车根因或固件接口可用性的新证据。

这里的“已确认”均指静态文件或代码已确认。系统配置声明、SDK 方法存在、服务 exported，并不证明当前 APK 有权限调用、硬件已启用或实车已经验证。

第三份用户连接报告补充：本轮无线认证后没有 AirPlay TCP；USB 在 IPv6 地址尚为 tentative 时发出启动请求，稍后地址 ready 且邻居可达仍未接入 TCP。工程补上当前临时热点密码显示及系统 USB 已知未就绪地址过滤；仍待实车验证，详见连接报告第三份补充，不作为构建 327 的静态事实。

按用户要求，连接设置新增手动车机热点与临时热点二选一；实现范围及验证限制见 [手动热点说明](MANUAL_HOTSPOT.md)。手动模式现按用户要求在选择时尝试开启系统保存的热点，不修改配置或关闭热点；仍保留权限、状态与会话检查，尚未获得实车验证结果。

临时热点重试保留同一有效 LocalOnlyHotspot 预约，失效才重建。2026-10-16 按用户补充要求扩展到手动断开／重连：旧连接清理完成后交接本应用有效预约，新连接复核接口和地址再复用，完全退出或改用其他连接方式时释放；定向验证及重试预算见 [热点生命周期](MANUAL_HOTSPOT.md)。这是应用实现，不是参考固件或报告设备的自动接入可用性证据。

第五份 17:50 报告确认私有 305 和标准媒体广播双路发送；用户补充首次自动连接成功、第二次手动点击连接。参考 OTA 底层 `services.vdex` 的五个 CompactDex 方法显示观察者多路分发及原厂互联应用条件，详见 [输入观察者证据](PREFACE_FIRMWARE_327.md#输入观察者不是第三方独占接口2026-10-14)。工程撤回私有音量回调并行写入，补上跨路径去重、无线启动窗口变化保留及待准备预约即时释放；不能据此宣称原车跳页或间歇性自动接入已修复。

## 后续工程可复用的信息

2026-10-16 [主动断开后的重连与蓝牙音乐复核](DISCONNECT_RECONNECT_AUDIO_2026-10-16.md)：用户确认首次为应用内主动断开；修正退出清理提前放行、重建中退出、会话丢失时临时热点重建及蓝牙模式主音频声明不一致。报告中的运行中 socket 中断根因和修正后蓝牙播放仍待实车验证，未增加原厂接口证据。

2026-10-16 [方控重复触发修正](WHEEL_ECHO_FIXES_2026-10-16.md)：播放／暂停语义回调加入跨入口去重，Siri 长按广播与物理释放共享已处理按压状态。复核语音广播 0/1 为短按／长按语义，参考 E02 长按亦可发送动作 0；应用定向测试通过，未构建 APK 或实车验证。

2026-10-16 [蓝牙触发无线重连复核](BLUETOOTH_AUTO_RECONNECT_2026-10-16.md)：修正失败控制器阻挡后续蓝牙事件、跨手机／开机防抖、页面异步请求竞争及旧重试定时器问题；保留有效临时热点与活动会话保护。属于应用状态机修正，未新增固件接口证据，实车自动接入待验证。

2026-10-15 [构建 67 方控与蓝牙报告](CONNECTION_REPORT_2026-10-15_BUILD67.md)：用户确认临时热点稳定、方控切歌仍跳原车多媒体；日志确认 NEXT 成功且广播去重，原厂 API 仍在注册前未就绪。蓝牙唤醒原报告未记录匹配事件，本轮补接收端 profile 连接事件及跳过原因，API 28/29 定向测试 8 项通过，实车自动启动与方控接管仍未确认。

2026-10-15 后续 [音源、蓝牙与 USB 定位日志](MEDIA_USB_DIAGNOSTICS_2026-10-15.md) 补充服务查询／SDK 分类、接收端蓝牙状态、USB 阶段／DHCP／CDC 通知。属于应用诊断与定向测试，没有取得新的原厂准入或实车通信成功证据；现有 64 包不含这些日志。

2026-10-15 按用户要求增加 [USB 自动 NCM 帧测试](USB_NCM_FRAME_DIAGNOSTICS.md)：系统 USB 接入失败时保留认证，自动尝试原始帧及 ICMPv6 探测；测试可能解除 NCM 内核驱动绑定，结束后提示重新插拔，不抢占已建立会话，不提供 AirPlay 网络回退。这是工程诊断与定向验证，未新增构建 327 或报告 IHU353G 的实车接口可用性证据。

同日分析用户提供的 [Linkco 盒子车机端 APK](LINKCO_APK_WIRELESS_REFERENCE.md)：主要通过 USB 私有命令与外接盒子通信，无线接入主流程推断在盒子固件。记录可借鉴的分阶段恢复原则及与临时热点的差异；固定热点、无线握手细节和快连根因仍未确认。此为独立 APK 参考，不属于本页 OTA 的静态接口证据，也未引入其他车型适配。

后续 [切歌与原厂媒体中心参考](LINKCO_APK_MEDIA_KEYS_REFERENCE.md) 确认盒子 APK 使用 MediaCenterAPI 注册音源并接收 MusicClient 切歌回调；用户确认同车切歌不跳页。参考 OTA 的 `boot-ecarx.gkui.openapi.impl.vdex` 也有相应 SDK 类型／方法定义，实际准入与内部路由未确认。当前项目已按用户要求默认尝试该音源注册，独立于音量选流开关；接口／准入失败保留原接入。新实现待实车验证，不能把同车盒子结果描述成本仓库已修复。

同日 [原厂音源元数据补充](PREFACE_MEDIA_METADATA.md) 核对 `updateCurrentProgress`、`updateCurrentLyric` 和歌词 getter 的定义／偏移；工程同步歌曲信息和位置，当前没有独立歌词输入。用户确认网易云 CarPlay 画面可见歌词，不能据此认定 iAP2 已传歌词文本；实际原厂进度单位和显示仍待验证。

2026-10-15 按用户要求移除独立仪表／桌面地图功能：设置入口、Stream 111 配置、桌面悬浮卡片及地图共享服务均退出主应用；保留正常 CarPlay 主屏地图与文字／箭头导航小部件。原保存开关不再读取，备份不再导出或恢复该开关；这不是对参考固件仪表能力的新增判断。桌面功能范围见 [桌面导航集成](LAUNCHER_INTEGRATION.md)。

随后按用户要求 [移除整个“显示（实验性）”卡片](REMOVED_EXPERIMENTAL_DISPLAY.md)：分屏区域、免重连旋转方形画面及侧边面板均退出主应用，旧开关不再读取；固定程序栏位置与普通窗口布局保留。此为应用功能范围精简，不增加固件能力或实车验证结论。

### 方控与原车媒体

2026-10-15 按用户要求完善[音乐与方控方式](BLUETOOTH_MUSIC_WHEEL.md)：实验性页面将蓝牙音频与方控音量开关合并为一个互斥选择，保留原保存值，默认 CarPlay＋音量联动，下次连接生效。蓝牙方案不提供 CarPlay 音频且将媒体方控交回原车，避免重复转发与音乐焦点竞争。用户确认原车蓝牙切歌不跳页，混合运行 CarPlay 的实际效果及导航／Siri／通话路由待验证，未新增参考 OTA 的接口事实。

用户另提供 [GeelyPlay 0.2.19.8 方控对照](GEELYPLAY_STEERING_REFERENCE.md)：主 APK 默认接收标准 Android 按键／MediaSession 并转 CarPlay HID，保留独立 VehiclePropertiesService 桥接客户端，未发现同本仓库的 MediaCenterAPI 注册路径。配套加密兼容组件未分析、未实车验证切歌不跳页；此为独立 APK 证据，不属于参考 OTA 的已确认能力。

- 输入服务：`ecarx.xsf.inputservice/ecarx.xsf.inputservice.InputService`；不是 OneOS 的 `com.geely.inputservice`。
- 输入 SDK：`com.ecarx.xui.adaptapi.input.Input.create(Context)`，`requestKeysInterception(int[], IKeyCallback)`、`abandonKeysInterception(IKeyCallback)`；底层依赖定制 `IInputManager` / `IKeyEventObserver`。第三方调用权限及对原车按键的影响尚未验证。
- 私有码 304/305 经 AdapterAPI 200088/200087，原厂短按输出标准上一曲 88／下一曲 87；播放暂停动作 200085 输出 85，其原始物理码未确认。
- 原厂对媒体键发 `android.intent.action.MEDIA_BUTTON` 广播，带 DEFAULT category 和 `android.intent.extra.KEY_EVENT`。同一短按产生 DOWN/UP，对广播和 MediaSession 双路送达需要去重。
- 自定义按键 `soft_key_function=7` 对应媒体暂停动作。语音、电话和音量有原车专用处理，不应把全部私有按键直接转成 CarPlay 按键。
- 原车音乐包 `com.ecarx.multimedia`（4.0.0 / versionCode 40001095），媒体中心包 `ecarx.xsf.mediacenter`。媒体中心服务 action 为 `ecarx.xsf.MEDIA_CENTER_SERVICE`；已补充 SDK 注册／回调定义和盒子客户端用法，Binder 实现及当前应用准入仍未确认，不能仅凭 action 接入桌面媒体卡片。
- HiCar 配套 SDK 静态实现通过定制 `IInputManager.requestKeysInterception` 登记媒体／语音按键观察者；工程请求标准 85/87/88/298 及物理 304/305，并为语音按钮接入有序广播回退。HiCar 音量沿用原车；本项目曾申请私有音量键并写导航音量，该候选在第五份报告与底层分发复核后撤回，不能把收到回调当独占。电话、方向键、快进快退和唤醒词的处理边界见 [HiCar 对照与按钮审查](PREFACE_FIRMWARE_327.md) 及事实索引 `hicarTrackInterception`、`hicarButtonAudit`。权限与原车页面抑制效果分别待验证。

证据：`decompiled/input/sources/ecarx/xsf/inputservice/{key,util,factory}/`、`decompiled/adapter/sources/com/ecarx/xui/adaptapi/input/`、`catalog/apk-manifests.json`。

### 音频输出、独立音量与麦克风

| 用途 | 固件 stream |
| --- | --- |
| 通话 | 0 |
| 媒体 | 3 |
| 蓝牙 SCO | 6 |
| TTS / TTS 提示 | 9 / 11 |
| 导航 | 12 |
| 收音机 / 提示音 | 13 / 14 |
| 开机 / 雷达 / VOIP | 15 / 16 / 17 |
| 虚拟 / KTV / ECALL | 18 / 19 / 20 |

上述值直接读取 `boot-framework.vdex` 中 AudioManager/AudioSystem 的 CompactDex 静态字段；完整 STREAM、USAGE、FLAG 常量保存在 `audio-framework-constants.json`。**stream 与 AudioAttributes usage 是不同参数。14 是提示音，不能套作导航。**

- 原厂音量动作查询 `AudioManager.getStreamTypeInActive()`，只接受 0、3、9、12，其余选媒体 3。使用 `getStreamVolume` / `setStreamVolume(..., flags=5)`，长按每 250 ms 重复。本项目不再提供应用内音量比例，媒体与导航音量均由系统音频流控制。
- 2026-10-16 用户要求在快捷菜单加入媒体／导航音量滑动条，应用通过公共系统接口调节实际音频通道，保留通话、蓝牙模式、通道支持及写入结果检查，详见 [即时音量调节](CARPLAY_HOME_NAVIGATION.md#即时音量调节构建-73)。这是应用 UI 与系统音量控制实现，沿用已有 3／12 静态常量，不是新增固件接口证据或实车验证。
- `vendor/etc/audio_policy_configuration.xml` 的 primary output 声明 PCM 16-bit、44.1/48 kHz、立体声；primary input 声明 PCM 16-bit、8/16 kHz、单声道／双声道／语音上下行 mask。
- 内置前、后麦克风端口声明 8/16 kHz、单／双声道。可用作 Siri 录音排查起点；真实通路、声道布局、并发录音及 HAL 重采样仍需运行时确认。
- `vendor/etc/audio_effects.xml` 有实际 `aec` effect，并在 `voice_communication` preprocess 中应用。不能据此声称所有 AudioRecord source 自动获得回声消除；需真车确认录音 source、AEC 可用性及通话抢占。
- `USB Primary In/Out` 为 BUS 类型，地址 `usb_phone_call`，是音频总线地址，不能当 USB 设备路径或 iPhone 接口号。
- 主音频策略实际 include `a2dp_audio_policy_configuration.xml`、`r_submix_audio_policy_configuration.xml` 以及两份 ECARX 音量表。已额外保存 USB audio policy 文件，但主策略未 include 它，文件存在不代表生效。
- 音频 XML 中有大量注释模板；索引使用 XML 解析排除注释，仅记录实际节点。BT SCO input 的 mask 原文为 `AUDIO_CHANNEL_OUT_STEREO`，保留原值，不能当作硬件正确性的证明。

证据：`vendor/etc/audio*.xml`、`vendor/etc/ecarx*volume*.xml`、`catalog/configuration-trees.json`、`audio-framework-constants.json`。

### 导航、定位与仪表

- 固件声明 GPS、网络定位，启动项包含 MTK GNSS 服务。优先通过 Android LocationManager / NMEA 获取位置；标准权限及实际 provider 可用性需真车确认。
- `Navigation.create(Context)` 进入 `INavigationImpl`；SDK 还包含 DR、车速、陀螺仪／加速度接口，部分实现依赖 VehicleSignalManager / CarPropertyManager。
- 原厂导航服务：`ecarx.naviservice/ecarx.naviservice.service.NaviService`，action `ecarx.intent.action.NAVI_SERVICE_STARTED`，需要 `ecarx.oem.permission.OPENAPI_NAVI_PERMISSION`（manifest 值 `0x12`，signature | privileged）。不能作为普通第三方 APK 可直接绑定的接口。
- 仪表 SDK `INaviInteraction` / `NaviInteractionImpl` 有 ETA、剩余距离、下一个路名、整数箭头和导航状态方法。但 Bitmap／SVG 箭头等多个实现为空；同名方法存在不代表功能实现。
- `IGCJLocationImpl.isGCJLocationSupported()` 限定 `NL_3B`，不要将其 GCJ 写入 MCU 的路径直接套给 FS11A1；位置坐标系和 CarPlay 位置分享应单独核实。

证据：`decompiled/adapter/sources/com/ecarx/xui/adaptapi/navigation/`、`dim/impl/nav/NaviInteractionImpl.java`、`catalog/apk-manifests.json`。

### USB、Wi-Fi 与蓝牙连接

#### HiCar 热点创建方式（2026-10-14）

已从原始 system 分区补提取 `HwNearbyCar.apk` 并核对下层实现：Nearby 自行生成 `HiCar-` 加 4 位数字的 SSID 和 8 位数字密码，保存原热点配置，写入 WifiConfiguration，再在 Android 9 上调用系统 `startTethering()`；存在复用及恢复原配置逻辑。这是系统身份使用的 SoftAP 路径，与公共 `LocalOnlyHotspot` 不同。权限、调用链、证据行号与 SHA-256 见 [HiCar 热点分析](HICAR_HOTSPOT_327.md)，机器索引见 `hicarWirelessHotspot`。本次只补充参考资料，没有引入系统热点或 P2P 模式；该静态结论不等于 IHU353G 报告设备已验证。

#### HiCar 有线连接与 CarPlay VPN 的区别（2026-10-13）

构建 327 的 `HiCarHMI.apk` SHA-256 为 `401d87f13ab1c075d8a3fdb9ad5d1b18810fee0e48993d025202414a3326b294`，来源为本页所列 OTA。`catalog/manifests/HiCarHMI.txt:3` 声明 `android.uid.system`；HMI 的 Java 源码与 manifest 搜索未发现 `VpnService` / `BIND_VPN_SERVICE`。这只说明所检查 HMI 层未发现该实现，不证明底层服务的全部行为。

`decompiled/hicar/sources/com/huawei/authagent/service/connect/NearbyConnectControl.java` 注册 USB socket listener（business id 19），`com/huawei/nearbysdk/NearbyConfig.java` 声明 USB channel 为 6；`com/huawei/nearbysdk/NearbyAdapter.java:139–143` 绑定 `com.huawei.nearby/com.huawei.nearby.NearbyService`。2026-10-14 继续追到 `HwNearbyCar.apk` 的 UsbNcmChannel 分支：监听系统 `ncm0_reserved` / `wwan0`，通过 NetworkImpl / INetworkManagementService 配置 IPv4 地址和 DHCP，再绑定普通 socket；这是系统网卡路径，未在该分支发现 VPN 创建调用。它依赖原厂权限且使用 Huawei 网络协议，不能视为可复用的 iPhone NCM / CarPlay 接口。证据、限制和应用内协议栈等不依赖 VPN 的方案见 [USB 无 VPN 分析](USB_WITHOUT_VPN.md)；内核驱动的建立过程和实际权限仍待核对。

2026-10-14 工程已接入系统 USB 网卡优先、VPN 备用的流程。选择系统网卡时，要求 sysfs 的 USB busnum/devnum 与当前 iPhone 的设备路径一致，接口号属于该 iPhone CarPlay 配置的 NCM control/data，并具有已启用的网卡、IPv6 链路本地地址和有效 MAC；不能仅凭 `usb0` 等名称选择，避免误认 TBOX／仪表网卡。已处于目标 USB 配置时不重新执行 SET_CONFIGURATION，以保留系统驱动。先保留 NCM 接口供系统使用，发现网卡后绑定其带 scope 的 IPv6 地址，并向 iPhone 公告真实地址和监听端口。

发现等待上限为 2 秒；无可确认网卡、无读取权限或监听失败时，才按需请求 VPN 授权并由应用接管 NCM。系统路径发出 CarPlayStartSession 后 30 秒仍未建立 AirPlay 会话，也切换到 NCM/VPN，并重新公告地址与端口。已经建立过的会话不由此启动超时切换；用户拒绝或无法授权 VPN 时停止自动重试，保留手动重试。VPN 仍只路由本应用的 IPv6 本地链路通信，不修改系统网卡、sysfs 或其他应用的网络。

这属于工程实现，**不是固件驱动支持或星瑞实车可用性的确认**。当前 targeted 验证覆盖 USB 归属判断、启动超时与会话保护、授权回调和拒绝后停止重试；系统网卡路径、VPN 回退后的 iPhone 会话重建、拔插、音视频及长时间连接仍需实车验证。若固件不生成 iPhone NCM 网卡或应用无法读取归属信息，会使用 VPN 备用路径。未实现应用内 IPv6/TCP/UDP 协议栈，也未复用华为 Nearby 服务。

系统 USB 直连已补充只读启动诊断：接口归属／驱动、IPv6 地址标记、路由和邻居状态、监听端口、RX/TX 变化，以及 StartSession、TCP 和会话阶段。不可读取时明确标为 unavailable；详见 [报告字段与判断方法](USB_SYSTEM_NETWORK_DIAGNOSTICS.md)。这些是工程诊断能力，尚未确认报告设备的实际网络阻塞原因。

- 固件声明 USB host/accessory、Wi-Fi/Wi-Fi Direct、Bluetooth/BLE；这些是 feature 声明，不等同于每个物理 USB 口都支持 iPhone 连接。
- USB HAL 为 `android.hardware.usb@1.1-service-mediatek`；init 配置列出 `dual-role-type_c_port0` 与 `dual-role-usb20` 的 role 节点，owner root:system、mode 664。记录作排查线索，应用不要直接写 sysfs 强切角色。
- `android.car.usb.handler` 的 `BootUsbScanner` 接收 BOOT_COMPLETED；使用 `MANAGE_USB`，该权限属于原厂系统组件，不能从其 manifest 推断本应用可获同等授权。
- 系统属性声明 Wi-Fi `wlan0`、热点 `ap0`、Wi-Fi Direct `p2p0`。车机还有 TBOX／仪表网络接口；连接测试应读取实际路由，不能把全部 USB 网卡认作 iPhone。
- `WifiAp.create(Context)` / `IWifiAPHost`：SDK 模式 1=2.4 GHz、2=5 GHz；内部 `WifiConfiguration.apBand` 的值另为 0/1，不可混用。5 GHz 支持判定依赖 `isDualBandSupported()` 和非空 countryCode，固件文件不足以确认现场支持。
- `Device.getBtExtension()` 下有 A2DP/PBAP 扩展。部分方法标注 KX11；蓝牙 APK 同时声明 A2dpSink、AvrcpController、HeadsetClient，其 enabled 值是资源引用，未解析前不能声称全部已启用。

证据：`system/build.prop`、`vendor/etc/init/android.hardware.usb@1.1-service-mediatek.rc`、`catalog/declared-features.json`、`catalog/apk-manifests.json`、`decompiled/adapter/sources/com/ecarx/xui/adaptapi/{wifiap,device/ext}/`。

### 视频、屏幕与多窗口

- 实际 codec XML include MTK 与 Google 音视频配置。声明 `OMX.MTK.VIDEO.DECODER.AVC`（H.264）及 `.HEVC`，最大尺寸都为 3840×2176；AVC 有 `wants-NAL-fragments` 等 quirk。
- Google AVC/HEVC 软件解码器也有声明。`media_codecs_performance.xml` 的 measured-frame-rate 是配置值，不能作为 CarPlay 实测帧率或稳定性保证。HEVC 仍应保持实验性并提供 AVC 回退。
- system 与 vendor 的 `ro.sf.lcd_density` 分别为 160 和 480，存在冲突；vendor rotation 为 0、GLES 属性为 196610。实际屏幕分辨率、最终 density、刷新率、可用窗口需真车 `wm` / `dumpsys display` 获取，不能用解码最大尺寸推算屏幕。
- SDK `UiInteraction` / `IMultiWindow` 包含分屏支持判定、窗口包名与退出分屏；`isSplitScreenModeSupported()` 返回 FunctionStatus，调用可用性及隐藏 API 权限待验证。

证据：`vendor/etc/media_codecs*.xml`、`catalog/declared-codecs.json`、两份 build.prop、`decompiled/adapter/sources/com/ecarx/xui/adaptapi/uiinteraction/`。

### 电源、启动、权限与 SDK

- `ecarx.power.service.ECarXPowerManagerService` 是系统 UID、persistent、directBootAware 的原厂服务。SDK `Device.getSystemMode()` / `ISystemMode` 提供模式状态和 listener，供后续研究熄火／休眠／唤醒；ACC 时序、第三方进程保活及唤醒事件尚未确认。
- vendor 声明 DuraSpeed 与后台节能开启，应在实车测试中观察 CarPlay 服务是否被终止；本次没有改动系统保活配置。
- OpenAPI pool：`com.ecarx.sdk.openapi/com.ecarx.sdk.openapi.ServicePoolService`，action `ecarx.intent.action.OpenAPIService`，exported=true。服务内部准入、Binder 事务与 SDK 注册要求待分析。
- CarService 包 `com.android.car`、action `android.car.ICar`。`CAR_CONTROL_AUDIO_VOLUME` / `CAR_CONTROL_AUDIO_SETTINGS` / `CAR_NAVIGATION_MANAGER` 都声明为 `0x12`；`BIND_CAR_INPUT_SERVICE` 为 `0x02`（signature）；默认 car input 服务 exported=false。
- `CAR_SPEED` 声明 `0x01`（dangerous），但不据此保证 ECU 提供该属性；普通应用的授权、属性支持与地区／车型差异仍需验证。
- `AdapterAPI.jar` / `AdapterAPIImpl.jar` 含可读取的 DEX；其他 framework JAR 可能仅为优化后的空壳，类位于 boot VDEX/ODEX。不要将空壳 JAR 当作完整编译 SDK，也不要仅因搜索不到 Java 源码就判定接口不存在。
- SDK 同时含多个车型实现，出现 KX11、NL_3B 等分支时必须检查支持判断和 FunctionStatus，禁止对星瑞直接启用所有接口。

证据：`catalog/apk-manifests.json`、`catalog/init-services.json`、`decompiled/adapter/sources/com/ecarx/xui/adaptapi/{device,car,policy}/`、`vendor/build.prop`。

## 本地证据目录与重新生成索引

本机缓存根目录：`D:/Workspace/CarPlay-Preface/agent_docs/firmware/ihu519g-327/`。

| 文件 | 内容 |
| --- | --- |
| `source.json` / `metadata.txt` | 固件来源、大小、SHA-256、OTA 身份 |
| `system.img` / `vendor.img` | 已重建分区，8,589,934,592 / 897,581,056 字节 |
| `system-files.txt` / `vendor-files.txt` | 完整 7-Zip 分区列表，可找到尚未提取的文件 |
| `system/` / `vendor/` | 已选择提取的 APK/JAR、配置、权限、keylayout、init 脚本 |
| `decompiled/adapter/` / `decompiled/input/` | AdapterAPI 和原厂输入服务反编译结果 |
| `catalog/partition-files.json` | 8,936 个分区条目，含路径、大小与权限等原始字段 |
| `catalog/extracted-files.json` | 2,552 个已提取文件的路径、大小、SHA-256 |
| `catalog/apk-manifests.json` / `catalog/manifests/` | 18 个 APK 的树形 manifest、aapt2 原始输出；保留声明的权限、组件及 intent-filter |
| `catalog/adapter-api-index.json` | 332 个类的可识别方法签名、源码行号、SHA-256、反编译警告标记；不是完整 ABI 解析器 |
| `catalog/adapter-api-constants.json` | 4,305 条公开数值／布尔常量，含车辆属性、按键和状态编号的原文字面量、类名及行号；未求值表达式或推断车型支持 |
| `catalog/configuration-trees.json` | 18 份音频／codec 配置的实际 XML 节点，排除注释模板 |
| `catalog/declared-codecs.json` | 68 条 codec 声明／性能更新条目，尚未合并为运行时能力 |
| `catalog/declared-features.json` | 98 条 feature 声明，含多份文件中的重复声明 |
| `catalog/init-services.json` | 135 条 service 起始声明及源文件、行号；完整启动条件查看原始 rc |

数量为本次提取快照，继续补充文件后以 `catalog/index.json` 为准。JADX 在 Adapter/Input 两次分析各报 2 处错误；关键方控方法已完整输出，其他方法须检查警告并回看原始指令。aapt2 和 XML 索引生成本次无错误。

重新生成本地索引：

```powershell
python scripts/firmware_catalog.py --cache agent_docs/firmware/ihu519g-327 --aapt2 D:/Tools/AndroidSdk/build-tools/36.0.0/aapt2.exe
```

按需从已经重建的分区提取文件，然后再次生成索引。例如查看主策略的 A2DP include：

```powershell
& 'C:/Program Files/7-Zip/7z.exe' x agent_docs/firmware/ihu519g-327/vendor.img '-oagent_docs/firmware/ihu519g-327/vendor' 'etc/a2dp_audio_policy_configuration.xml' -y
```

工具已缓存：7-Zip 26.01、`agent_docs/firmware/tools/jadx-1.5.6/`、`agent_docs/firmware/tools/python/` 中的 Brotli 1.2.0。本地重建脚本为 `agent_docs/firmware/unpack_firmware.py`，CompactDex 常量提取脚本为 `agent_docs/firmware/cdex_constants.py`；只适用于已核对的完整 OTA／该 CompactDex 布局，不用于任意增量包。升级脚本、MCU、bootloader 及更新二进制均只视作数据，没有执行或刷写。

仓库保存此摘要、事实 JSON 和索引生成脚本。完整固件、厂商 APK、反编译实现留在本机忽略目录；迁移工作机时需一并保留缓存或重新提供原固件。

## 真车补证清单

1. 核对 build 身份和最终屏幕参数；枚举实际 codec、输入／输出音频设备及 location provider。
2. 验证 MEDIA_BUTTON 在普通应用进程的送达、每次切歌只触发一次、切回收音机后方控归原车。
3. 验证导航 stream 12 及原厂音量选流，媒体／导航并发、通话与蓝牙优先；确认 AudioRecord 的 8/16 kHz、source 与 AEC。
4. 逐个确认 SDK 是否加载、服务是否允许本应用绑定、权限是否获授、FunctionStatus 及具体车型分支。拒绝访问时记录错误并回退标准 Android 行为。
5. 验证 USB 物理口、授权／重连、Wi-Fi 频段／路由，以及熄火休眠、唤醒、冷启动、后台保活。诊断记录应去除 VIN、设备唯一 ID、配对地址和网络凭据。

按用户不能依赖 VPN 的运行约束，当前 USB 改用普通服务承载系统 NCM 网卡 socket，删除 USB VPN 授权与回退；这是应用实现变更，不是新增固件可用性证据，实车端到端连接仍待验证，见 [USB 无 VPN 方案](USB_WITHOUT_VPN.md)。
