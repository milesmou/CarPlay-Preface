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
| 高德首次顶部按钮失效 | [1208 再次分析](PREFACE_MAP_FIRST_CONTROLS_2026-10-27.md)：用户确认第二次连接、仅隐藏重显顶部按钮恢复；候选顶部 7 次点击的系统坐标与完整 HID 对应，期间无窗口重建，未见本地吞键或发送失败。手机端控件结果与故障／恢复逐次时刻仍缺失，根因和修复未确认；此前见 [1206 分析](PREFACE_MAP_FIRST_CONTROLS_2026-10-21.md)。 |
| 蓝牙交接、自动暂停、方控跳页 | [构建 100：组件交接生效、助手被拒](CONNECTION_REPORT_2026-10-20_BUILD100_AUDIO.md)、[构建 99 自动暂停与切歌转蓝牙](CONNECTION_REPORT_2026-10-20_BUILD99_AUDIO.md)、[构建 97 原车日志与修正](CONNECTION_REPORT_2026-10-20_BUILD97.md)、[v0.2.20.1 移植](GEELYPLAY_BLUETOOTH_0.2.20.1.md)、[后续修正](BLUETOOTH_WHEEL_RECOVERY_2026-10-19.md)、[构建 91 报告](CONNECTION_REPORT_2026-10-19_BUILD91.md) |
| 连接入口、取消与自动连接 | [主页优先级](HOME_CONNECTION_PRIORITY.md)、[自动连接](AUTOMATIC_CONNECTION.md)、[蓝牙重连复核](BLUETOOTH_AUTO_RECONNECT_2026-10-16.md) |
| 连接耗时与提速评估 | [历史时序与源码改动](CONNECTION_STARTUP_TIMING_2026-10-20.md)、[构建 1205 实车报告](CONNECTION_REPORT_2026-10-21_BUILD1205.md)：新建热点准备由旧版 3.390 秒降至约 1.04 秒；完整无线连接 15.720／21.218 秒、复用 6.423 秒，仍有波动。 |
| 无线热点与生命周期 | [手动／临时热点](MANUAL_HOTSPOT.md)、[HiCar 证据](HICAR_HOTSPOT_327.md)、[热点恢复](HOTSPOT_BLUETOOTH_RECOVERY_2026-10-16.md)、[清理屏障](WIRELESS_TEARDOWN_RECOVERY.md) |
| USB 当前方案 | [官方 v0.2.20 NCM＋本地 VPN/TUN](USB_VPN_RESTORE_0.2.20.md)、[VPN 固件证据](PREFACE_VPN_FIRMWARE_ANALYSIS_2026-10-16.md) |
| 授权组件、系统 UID、平台签名 | [组件说明与当前构建核验](ANDROID9_AUTHORIZATION_COMPONENTS.md)、[系统授权](SYSTEM_AUTHORIZATION.md)、[原生设置对照](NATIVE_SETTINGS_AUTHORIZATION_REFERENCE_2026-10-19.md) |
| 显示、快捷操作、桌面 | [固定协商像素](PREFACE_FIXED_RESOLUTION.md)、[快捷操作](QUICK_ACTIONS_UI.md)、[主页导航](CARPLAY_HOME_NAVIGATION.md)、[桌面集成](LAUNCHER_INTEGRATION.md) |
| 无线画质与流畅度优化 | [固件证据与默认设置](PREFACE_WIRELESS_VIDEO_2026-10-20.md)：HEVC、直出及 MTK 低延迟未保存时默认开启，保留已保存选择、能力检查与回退，已纳入 0.2.1／1204 正式包；用户明确实测未见异常的是 MTK 专用选项。延迟改善尚无对照统计，无重排桥接未证实。 |

## 当前结论

- 用户后续要求的[改版高德后台准备源码](PREFACE_AMAP95_BACKGROUND_PREPARATION_2026-10-30.md)已补：CarPlay 会话绑定已有 JSON 服务的身份校验准备租约，真实 Eaglet／后台工作完成后，对真实 Display 1 空闲画面提交并回读，再经既有协议准备；无手动地图 Activity、无假导航字段、无直写属性。取消／退出／死亡／失联／超时及本地导航优先释放本次资源，保留旧清理屏障。19 组宿主场景、43 项主应用定向测试及 Android／DEX 接口核对通过；未打包、安装或实车验证冷启动准备／系统应答，历史 26000007／1213 不含新链路。仍依赖配套改版高德，原厂服务继续原有路径，不是无高德独立引擎。事实项 `amap95BackgroundPreparation20261030`。

- 改版高德 9.5／26000007 的[最新实车日志及后续源码修复](PREFACE_AMAP95_VEHICLE_LOG_2026-10-30.md)确认 IHU353G／169 在升级后、车机重启后各完成一次真实提示窗口回读与原车准备 116，随后 `firstFrame=1`；用户反馈起初仪表正常，不能只归因于原厂进程残留。17:15:30 高德主 Activity 退出后准备归零，早于 CarPlay 连接及首条仪表提交；同 PID 重开后仍有真实导航但未恢复准备。交付源码的 `closed` 实例恢复缺口已在当前源码补齐：通过真实 Activity 重建恢复监听／空窗口，检查 Activity 身份及旧回调代次，保留新导航内容和真实回读门槛。七组宿主替身回归、Android／DEX 编译与固定底包接口核对通过，旧源码能复现回归失败；未重新打包或实车复测，26000007 不含修复，CarPlay 完整交接与长期稳定仍未验证。事实项 `amap95VehicleLifecycle20261030`、`amap95HintLifecycleFix20261030`。以下旧报告的“尚未成功／验证”保持各自历史范围。

- 用户要求从公众 9.5 重做当前版的[差异审计](PREFACE_AMAP95_PUBLIC_REBUILD_AUDIT_2026-10-30.md)已记录：三份固定输入的 63 个原生条目和资源表一致；公众到悬浮底包实际新增 18 类、改变 2 个同名方法体并移除 3 个生命周期方法，含已有签名代理和安全 SDK 装载变化。再叠加本仓库 20 个自有类／内部类及 6 个入口，公众到当前另有 5 份资源替换和 versionCode／签名变化。现有材料足以形成重做差异，但尚未执行纯公众重建或验证最小启动修正；物理仪表显示仍未确认。用户澄清“锁12”指默认音频通道 12，[原厂对照](PREFACE_AMAP95_AUDIO_CHANNEL_2026-10-30.md)确认 327 导航流定义为 12，原厂优先查询车机导航策略，当前保留 12。事实项 `amap95PublicRebuildAudit20261030`、`amap95AudioChannelReview20261030`。

- 原厂读秒 [运行验证](PREFACE_AMAP_TRAFFIC_LIGHT_RUNTIME_2026-10-30.md)：97 份去重历史日志只检出 9.5 的 32 条读秒消息（84→53 秒），没有原厂接收成功证据；不能将日志缺失当作原厂不支持。用户授权的 327 原代码验证包已签名并安装模拟器，原生库加载后因缺少 ECARX 系统类在初始化退出，未到达导航／灯态请求阶段，原厂接收结论仍未确定；没有实车测试或读秒补丁。

- 新提供公众版 9.5 的 [星瑞仪表移植研究](PREFACE_AMAP_PUBLIC95_INSTRUMENT_PORT_2026-10-30.md)：用户已选这条路线，并要求仅用现有资料继续。63 个原生条目与前次改版相同，旧插件依赖仍不匹配；后续只读工具在 59 个可解析库的导出并集外检出 Large 的 54 个既有 C++ 导入名及 1 个旧任务 RTTI 类型信息，其余 4 个条目单独记录，不当作成功解析。327 通道配置明确 Large 窗口为 1001、1920×720、分离渲染且关闭附加 UI；原插件仍负责渲染结束回调。9.5 自带 TBT 的加载入口和同版资源已找到，但固定返回 1 不能代替真实渲染或准备 116。只读分析脚本和六项定向检查已完成，渲染移植及实际 Surface／JSON 生命周期仍未完成；未制作或安装修改包。新公众文件签名不同于 327 原厂及前次改版。

  后续新增 [原生回调桥接源码](../amap95-instrument/README.md)：完成七项真实事件的参数转交、会话／设备过滤、销毁失效和退出等待；arm64 编译与模拟器上五组回调替身场景通过，未运行厂商 SDK。新增核对确认 9.5 的 `GetDisplay` 返回空，`IsDeviceRendered` 对非主屏固定返回假，空 `InitMapView` 参数路径读取主屏尺寸；Python 检查增至九项。专用地图参数、渲染器和准备协议接入仍未完成，没有修改 APK，也没有用常量、主屏状态或手工 116 替代真实仪表准备。

  本次继续补齐观察者绑定／注册／回滚／注销源码及 [复现命令](../scripts/verify_amap95_observer.py)：只绑定已经加载的匹配核心库，核对已存在地图的设备与窗口，注册中失效不会重新激活，SDK 回调内关闭延迟至回调外注销。三份 arm64 对象编译、模拟器上 14 组原生替身及 11 项 Python 检查通过；未运行匹配厂商 SDK。专用地图创建、真实窗口所有者、成功绘制后的 JSON 接入仍未完成，没有修改 APK；详见事实项 `public95ObserverLifecycle20261030`。

  后续 [真实库探针](../scripts/verify_amap95_vendor_runtime.py) 已在 API 34／x86_64 模拟器通过 Houdini64 执行固定公众版 ARM64 库：HSL 及 TBT 入口所在 `libautonavi.so` 加载、生产绑定和真实地图服务 getter 调用通过；getter 在未初始化探针中返回空，未调用 TBT 启动、创建地图或发送准备。初次主动卸载未初始化厂商库发生 SIGSEGV；后续探针结束隔离进程，未验证厂商完整退出。此次与前述替身测试分开记录，仍未达到仪表功能测试 APK 状态，详见 `public95VendorRuntime20261030`。

  用户随后选择先打通导航提示，并明确要求生成、安装。[原生提示候选](PREFACE_AMAP95_NATIVE_HINTS_TEST_2026-10-30.md) 旧公众候选 `26000003`／诊断包 `26000004` 启动黑屏。用户再次提供可运行悬浮版后，当前改用该固定哈希底包，保留启动／签名代理和全部原生资源，生成并同证书覆盖安装 `26000006`。初次及冷启动实际显示主地图／完整界面，收到真实主屏首帧；高德自身规划并启动导航后，实际 type=0／icon=8、距离和道路名均已进入候选回调，未注入字段；副屏等待日志已去重。物理副屏、回读及外屏准备尚未验收，不能把主屏首帧或字段接入当作外屏 116 或仪表成功。事实项 `public95NativeHintsCandidate20261030`。

- 原厂高德 [红绿灯倒计时升级评估](PREFACE_AMAP_TRAFFIC_LIGHT_UPGRADE_2026-10-30.md)：327 的 `libGbl` 已有红绿灯图层与计时器线索，9.5 有明确倒计时模型；原厂真实数据／配置／界面链路尚未确认，没有产出既支持倒计时又保留原厂仪表的升级包。本轮未修改高德或 CarPlay，不恢复已撤回的独立服务。

- 最新源码已按用户预先授权 [撤回独立移植方案](PREFACE_INSTRUMENT_OEM_FALLBACK_2026-10-30.md)：327 静态准备链固定绑定高德 JSON 服务，主 APK 私有引擎不能自动接入；已移除私有服务、Binder、库导入及构建任务，恢复直接 SDK 发送，明确要求支持仪表的原厂高德先完成准备。未交付独立 APK、未执行 169 实车独立测试。1212 历史包仍包含 [此前私有服务](PREFACE_INSTRUMENT_SERVICE_2026-10-29.md)，不因源码撤回而改变。

- 仪表导航最新 [原厂高德准备后成功与 9.5 对比](PREFACE_INSTRUMENT_AMAP95_COMPARISON_2026-10-29.md)：同一 169 车机原厂准备状态 116→V2DIM=1 后，1211 两个 CarPlay 进程共 42 次有效合并输出带出导航内容，用户确认仪表显示；升级第三方高德 9.5 后准备失效。9.5 保留扩展协议，但所给 APK 缺少旧版同名仪表库且二进制依赖不匹配，不能直接复制；CarPlay 独立准备仍未实现。[早一份 1211 报告](PREFACE_INSTRUMENT_NAVIGATION_2026-10-29_BUILD1211.md) 的 27 次 `firstFrame 0` 是此前失败会话，不能概括全部后续结果。

- 仪表导航 [补齐原厂分项发送](PREFACE_INSTRUMENT_NATIVE_FIELDS_2026-10-29.md)：原厂 `refreshGuideInfo` 在合并信息后还发送整数箭头、距离、ETA、道路名。源码现已补上这组调用和开始／停止状态，保留当前会话及清理屏障，不伪造首帧；已纳入 1211 Debug 测试包，169 实际接收与显示待验证。此前只追踪合并输出准备条件，不能据此判定所有分项也需要首帧。

- 原车高德 [仪表准备流程复核](PREFACE_AMAP_INSTRUMENT_FLOW_2026-10-29.md)：已读取固件内 AutoMap APK 及配套 VDEX。参考 327 的扩展渲染事件经协议转为状态 116，NaviService 才设置 V2DIM ready；普通首帧与 Widget 状态 124 不等价。实车 169 的无飞屏转向提示准备流程仍需单独核对，尚未据此修改应用行为。

- 仪表导航 [1210 实车分析](PREFACE_INSTRUMENT_NAVIGATION_2026-10-27_BUILD1210.md)：SDK 连接及 36 次有效数据调用已走通，车辆层也记录了写出；原厂 `firstFrame`／导航仪表准备状态始终为 0，SDK 输出与清除时相同的固定短帧，未带出有效导航内容。当前仍缺原厂有效输出准备流程，仪表显示未修复；169 实际使用 `0x2d700050` 私有属性，与 327 参考固定属性不同，不直接套用。此前 [1208 阻塞与 SDK 修正](PREFACE_INSTRUMENT_NAVIGATION_2026-10-27.md)、[初次接入](PREFACE_INSTRUMENT_NAVIGATION_2026-10-26.md) 分别保留历史事实。

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

用户后续明确要求构建高德 APK，已交付并覆盖安装 `26000007`：恢复公众默认语音，33 个语音初始化条目与公众原包一致；DEX 与 `26000006` 字节相同，63 个原生条目保持不变。模拟器冷启动显示地图并收到真实主屏首帧；未做语音试听或实车仪表验收。证据及限制见[最新测试记录](PREFACE_AMAP95_NATIVE_HINTS_TEST_2026-10-30.md)，事实项 `amap95PublicDefaultVoiceReset20261030`。
