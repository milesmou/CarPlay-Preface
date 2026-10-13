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
- 本地完整索引：`agent_docs/firmware/ihu519g-327/catalog/index.json`。固件、镜像、APK/JAR、反编译代码及完整索引保存在 Git 忽略目录，可直接继续分析。

这里的“已确认”均指静态文件或代码已确认。系统配置声明、SDK 方法存在、服务 exported，并不证明当前 APK 有权限调用、硬件已启用或实车已经验证。

## 后续工程可复用的信息

### 方控与原车媒体

- 输入服务：`ecarx.xsf.inputservice/ecarx.xsf.inputservice.InputService`；不是 OneOS 的 `com.geely.inputservice`。
- 输入 SDK：`com.ecarx.xui.adaptapi.input.Input.create(Context)`，`requestKeysInterception(int[], IKeyCallback)`、`abandonKeysInterception(IKeyCallback)`；底层依赖定制 `IInputManager` / `IKeyEventObserver`。第三方调用权限及对原车按键的影响尚未验证。
- 私有码 304/305 经 AdapterAPI 200088/200087，原厂短按输出标准上一曲 88／下一曲 87；播放暂停动作 200085 输出 85，其原始物理码未确认。
- 原厂对媒体键发 `android.intent.action.MEDIA_BUTTON` 广播，带 DEFAULT category 和 `android.intent.extra.KEY_EVENT`。同一短按产生 DOWN/UP，对广播和 MediaSession 双路送达需要去重。
- 自定义按键 `soft_key_function=7` 对应媒体暂停动作。语音、电话和音量有原车专用处理，不应把全部私有按键直接转成 CarPlay 按键。
- 原车音乐包 `com.ecarx.multimedia`（4.0.0 / versionCode 40001095），媒体中心包 `ecarx.xsf.mediacenter`。媒体中心服务 action 为 `ecarx.xsf.MEDIA_CENTER_SERVICE`；其 Binder 协议及应用准入尚未分析，不能仅凭 action 接入桌面媒体卡片。

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

- 原厂音量动作查询 `AudioManager.getStreamTypeInActive()`，只接受 0、3、9、12，其余选媒体 3。使用 `getStreamVolume` / `setStreamVolume(..., flags=5)`，长按每 250 ms 重复。应用内音量比例与系统音量是两个层次。
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
