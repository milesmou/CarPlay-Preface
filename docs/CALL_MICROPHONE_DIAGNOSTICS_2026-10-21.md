# CarPlay 通话麦克风补充诊断

针对用户确认的“蓝牙音频通话正常，CarPlay 音频通话对方听不到或声音小”，增加应用诊断。背景见 [构建 1205 实车报告](CONNECTION_REPORT_2026-10-21_BUILD1205.md) 和 [Telecom 固件分析](PREFACE_PHONE_AUDIO_TELECOM_2026-10-21.md)。

源码修改完成并通过定向验证后，按用户要求已构建 **0.2.2／1206 测试 APK**，已有 1205 APK 不含这些新字段。没有改变录音源、增益、处理器、权限、Telecom 请求策略或 HFP／SCO 路由，不能据此宣称麦克风已修复。

## 新增证据

| 日志／报告字段 | 含义与边界 |
| --- | --- |
| `Microphone: levels` | 每 5 秒及录音结束输出一次。`capture*` 是 AudioRecord 交给应用、进入应用可选处理之前的 PCM；`uplink*` 是应用处理后、编码上传之前的 PCM。前者仍可能经过系统／HAL 处理，不是物理麦克风的原始采样。 |
| `capturePeakDbfs`／`uplinkPeakDbfs` | 窗口内最高采样幅度，整 dBFS；越接近 0 越大。`silent` 是采样全为零，`unavailable` 是没有对应完整采样。 |
| `captureRmsDbfs`／`uplinkRmsDbfs` | 窗口内均方根电平，补足峰值无法反映持续声音的问题。 |
| `*Samples`、`*NonzeroPermille`、`*AboveMinus50Permille` | 完整 16 位采样数、非零采样占比、幅度达到约 −50 dBFS 的采样占比；占比单位为千分比。这是信号能量统计，不是语音识别或“有人说话”的判断。 |
| `Microphone: route` | 录音开始／结束输出，期间最多每秒采样一次，状态变化才输出。包含当前音频模式、麦克风静音、SCO／扬声器标志、录音权限、AudioRecord 状态／源／采样率／声道、实际路由设备类型／ID、首选设备类型以及软件 AEC、平台 AEC／NS 状态。只读取，不修改系统。 |
| `configuration`、`deviceRate`／`deviceType`、`clientSilenced` | 只匹配本 AudioRecord 的正数会话 ID，不把其他应用或匿名配置当成本应用。`MATCHED` 为已匹配，`NOT_EXPOSED` 为列表可读但未找到，`UNAVAILABLE` 为不可查询。Android 9 无 `isClientSilenced` 接口，输出 `unknown`，不能解释为未静音。配置未匹配时同样未知。 |
| `Phone audio settings`／`Phone audio history` | 应用导出报告加入实验开关、iPhone 有无通话、本应用辅助通话状态及当前进程最近 48 条事件。`carAudioPolicy` 只是现有处理策略，不能作为辅助通话已成功或原厂处理已生效的证明。 |
| `event=SETTINGS_CHANGED` | 记录开关保存时刻及两个电话音频选项的值，便于判断实验选项何时开启。`atEpochMs` 是设备墙钟毫秒。 |
| `REQUEST`／`REGISTERED`／`ADMISSION_QUERY`／`PLACE_RETURNED` | 分别表示请求及 `MANAGE_OWN_CALLS` 检查、注册正常返回、系统 `isOutgoingCallPermitted` 查询、placeCall 正常返回。查询只用于诊断；false 或查询异常均不改变原有请求流程，正常返回也不等于创建成功。 |
| `CREATED`／`CREATE_FAILED`／`RESULT`／`RELEASED` | 创建回调、失败回调、等待结果及释放。结果区分 `ACTIVE`、`CALLBACK_FAILED`、`TIMEOUT`、接口／服务不可用、`SECURITY_ERROR` 和其他运行异常，并记录耗时。回调失败本身不提供系统内部拒绝原因，仍需配合原车 Telecom 日志。 |

既有 `Microphone: stats` 的采集字节、读失败、编码空帧、UDP 发送及间隔统计保留。`uplink*` 是编码前电平；本地发送成功不能证明 iPhone 收到、解码或用于通话。

麦克风新日志沿用当前会话的异步写入和报告导出流程，不逐帧落盘。电话音频历史仅留进程内存，进程结束后丢失，复现后应在同一进程中及时导出报告。状态采样可能漏掉不足一秒的瞬态，完整切换仍须核对原车日志。

## 如何判读与复测

- 相同位置、相近说话音量下，`capture*` 持续很低或静音：重点检查 AudioRecord／HAL 输入和音频模式／路由，结合权限、静音及设备类型；不能仅凭内置输入类型证明与 HFP 使用了相同物理输入和增益。
- `capture*` 有明显信号，`uplink*` 显著降低或静音：应用可选处理前后发生变化，结合软件／平台 AEC、NS 记录定位。
- 两处电平均有明显信号且本地发送正常，对方仍听不到：继续核对编码、协议和 iPhone 接收／使用链路，不能用这份发送统计排除它们。
- `manageOwnCallsGranted=true` 且 `CREATE_FAILED`／`CALLBACK_FAILED`：不是录音权限失败的证据；按同一时间核对原车 Telecom 准入日志。`outgoingPermitted=false` 是查询时的系统判断，不是完整拒绝原因。

安装 1206 测试包后，保持说话位置和车机音量一致，分别复现蓝牙音频和 CarPlay 音频通话，各说话约 15–20 秒并记录对方效果。音频方案变更按原有规则在下次连接生效。若对照实验选项开／关，记录各段操作时间和效果；复现后在不结束应用进程的情况下导出应用报告及对应原车日志。

仅保留数值、布尔值、枚举和异常类名，不保存录音、PCM／编码内容、电话号码、通话对象、设备名称／地址、端点或异常消息，不新增权限。

## 验证

79 项定向单元测试通过：电平计算／窗口重置、采集与处理后静音的真实回调路径、状态查询限频与失败隔离、API 28／29 配置归属与静音接口边界、录音源回退／无数据重启、模式／效果释放、Telecom 准入查询不改变请求、权限异常与回调失败区分、历史容量及只读导出。另通过 `:common:compileDebugKotlin`。后续打包仅执行 `:mobile:assembleDebug --console=plain` 及其内置组件依赖，未追加单元测试、主应用全量 Lint 或完整 CI。

本地执行记录：`agent_docs/microphone-diagnostics-20261021/targeted-tests.log`；测试结果位于 `shared/build/test-results/testDebugUnitTest/`。

固件边界不变：参考为 IHU519G／FS11A1／构建 327，OTA SHA-256 为 `f61a7399d61a7f4502b9289ea6c49c661c39c459aae81e676722bcf9be913c01`；实车日志来自 IHU353G／系统 169。[1206 后续实车分析](CONNECTION_REPORT_2026-10-21_BUILD1206_MICROPHONE.md) 已确认新增电平、路由和 Telecom 字段实际输出：采集／上传电平统计一致，辅助通话成功，手动 ADC 增益在录音启动时被覆盖。该运行证据不等于麦克风根因或修复已确认。

## 1206 测试包交付

2026-10-21，`CarPlay-Preface-0.2.2-1206-debug.apk` 构建成功，25,637,334 字节，包名 `com.preface.carplay`；mobile／automotive 源码构建号同步为 1206，本次只构建 mobile。主包 SHA-256：`a63152ee01f3f2223c285556337ca99295f094077547e9441e337ef76a3eb1a3`。

已核对主包原证书、内置授权组件 11 的 SHA-256／平台证书／包名／版本／共享 UID 及元数据，并确认 DEX 含电平、路由和 Telecom 诊断代码。授权组件为构建依赖确认有效的当前产物，未改变组件版本或授权规则；本次没有单独安装组件。构建后 `adb devices -l` 列表为空，未执行安装，也未启动模拟器。

交付文件位于 `mobile/build/outputs/apk/debug/`，原始 APK 与输出元数据保留；构建、签名与资产核验记录位于本机忽略目录 `agent_docs/build1206-20261021/`。
