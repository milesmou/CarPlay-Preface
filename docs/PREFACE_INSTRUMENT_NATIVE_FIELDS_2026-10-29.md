# 补齐原厂高德的分项仪表导航发送

2026-10-29，用户要求参考原厂高德补齐流程。继续追踪后发现，原厂 `NaviDashboardManager.refreshGuideInfo` **先发送合并导航信息，再分别发送整数箭头、下一转向距离、ETA、剩余距离和道路名**。此前 1210 只实现了合并调用，遗漏分项发送，以及开始／停止时的 `notifyNavigationStatus(1/0)`。本次源码补齐这些已确认调用；没有伪造首帧或准备事件。

此前 [高德准备流程分析](PREFACE_AMAP_INSTRUMENT_FLOW_2026-10-29.md) 只追踪了合并输出的准备条件，不能据此认定整个原厂转向提示都必须先有扩展渲染首帧。本轮补充了原厂并行分项路径的证据。实车 169 是否使用这些分项输出、MCU 是否接收及仪表是否显示，仍待新包验证。

## 原厂证据

来源仍为用户提供的 IHU519G／FS11A1／Android 9／构建 327 OTA，SHA-256 `f61a7399d61a7f4502b9289ea6c49c661c39c459aae81e676722bcf9be913c01`。高德 APK 为 `com.autonavi.amapauto`／`5.0.7.605871`／6030053，SHA-256 `82272e4dc531c3ff1b1c6fff02d5fc0362f963319e435e0f5021d4b6dc5c67dc`；配套 VDEX SHA-256 `33c78415570eb7b6aacc59c43bcb807a8099c5d03976ceaa2b7c5cd80f71608e`。

以下路径相对本地忽略缓存 `agent_docs/firmware/ihu519g-327/`：

| 实现 | 本轮核对的调用 |
| --- | --- |
| `decompiled/naviservice/sources/ecarx/naviservice/a/a.java:494–502,536–553,630–672` | 开始／停止调用 start／stop，并将地图状态 16／17 转为 `notifyNavigationStatus(1/0)` |
| 同文件 `734–762` | `refreshGuideInfo` 先通过 `a(new o(...))` 发送合并信息，再调用整数箭头、方向、下一转向距离、ETA、剩余距离和道路名；之后处理服务区、摄像头 |
| 同文件 `800–906` | 分项调用明确为 `updateTurnByTurnArrow(int)`、`updateDistanceToNextGuidancePoint(int)`、`updateETA(int)`、`updateDistanceToDestination(int)`、`updateNextGuidancePointName(String)` |
| `decompiled/adapter/sources/com/ecarx/xui/adaptapi/dim/impl/nav/NaviInteractionImpl.java:90–144` | 分项入口保留 SDK 自身 reset 检查，再交给安装的 `ECarXDimServiceImpl`；Bitmap／SVG 箭头重载为空，不能用它们替代整数接口 |
| `decompiled/adapter/sources/com/ecarx/xui/adaptapi/dim/impl/ECarXDimServiceImpl.java:282–347,378–429` | 分项实现使用原厂导航状态、单位转换和编码；这几段不读取合并路径的 `firstFrame`，整数箭头使用 `convertNAVTurn2HUD` |
| 同文件 `88–109,274–279,572–600` | 停止排队清除字段缓存；合并路径仍按自己的 `firstFrame` 条件生成内容 |

三份主要源码 SHA-256 分别为 `3308c3092469c73b94907cac6ce5d52281f4d8a1bc8049c8f3089e5aa66f731d`、`f6f75d353ced5847fc033c9cb1cf1d89f1f8de64f00b70ebf02fd13af53e927f`、`d3abdad1781665ee7650de84614431ce643386d88b79f37b2b03679db516adf6`。完整源码和固件留在本地；事实索引记录本轮证据与适用范围。

## 应用发送顺序

`shared/.../vehicle/PrefaceInstrumentBackend.kt` 继续使用安装的原厂 SDK 和原实例，不新增车辆属性写入器：

1. 连接前核对合并、start／stop 和六个新增方法签名，分项方法必须存在并返回 `void`。缺失时记录具体方法和 `instrument-sdk-guidance-method-mismatch`，停止该会话，不退回已知不完整的单独合并调用。
2. 有当前会话的真实、支持箭头数据且收到 SDK 连接回调后，调用 `notifyTurnByTurnStarted()` 和 `notifyNavigationStatus(1)`；同一路线只启动一次。
3. 每次允许发送时先调用 `updateNavigationInfo()`，随后依原厂顺序调用上述五个分项接口。使用已有 102–109 原厂箭头编号，不自行转换成 HUD 字节；米和秒交给 SDK 换算。仅 int 接口需要的超大数值饱和至 `Int.MAX_VALUE`，不让 Long 转换溢出为负数。
4. 每个调用前重查当前会话、所有者和真实连接；中途失效或异常后停止剩余字段，执行清除和释放。没有真实航向、车道、服务区或摄像头数据时不新增这些分项调用。
5. 结束时依次发送非空 `UNNAVI=0` 合并信息、`notifyTurnByTurnStopped()`、`notifyNavigationStatus(0)`。即使前一清理步骤失败，后面的停止／状态清理仍会尝试；保留 550 毫秒排队清理屏障，之后才能发送替换路线。

原车显示、状态及权限处理仍由 SDK 执行。本次没有直接调用 `setPresentationToDimSwitch`、电源或系统属性写入，没有伪造高德 116／124 或首帧事件，没有扩权授权组件，也没有因为普通 SDK 连接就声称仪表已显示。

## 日志与验证

连接事件增加 `output=aggregate+fields`；逐次全部调用返回后，Debug 才记录 `native SDK update submitted; output=aggregate+fields; display-confirmed=false`。Release 保留接口缺失、连接和失败状态，分项发送不受日志开关影响。

Debug／Release **各 33 项，共 66 项定向验证通过**（每种变体：Backend 19、Guidance 12、Navigation 2；无失败、错误或跳过）。覆盖原厂调用顺序和值、整数边界、接口缺失时禁止启动、分项权限拒绝、中途会话失效、停止异常和旧清理完成前阻止替换路线，并继续运行已有导航数据、节流、取消和过期清理用例。测试使用替身 SDK，不代表真实车辆通信；结果记录在 `agent_docs/instrument-native-fields-20261029/`。

仅执行 `:shared:testDebugUnitTest` 和 `:shared:testReleaseUnitTest`，使用 `--tests '*PrefaceInstrumentBackendTest'`、`--tests '*PrefaceInstrumentGuidanceTest'`、`--tests '*PrefaceInstrumentNavigationTest'` 限定范围。公开树、证据哈希和文档链接另行检查。

源码验证阶段未运行全量 CI，也未构建、签名或安装 APK。已有 **0.2.2／1210 APK 不包含此次源码补齐**，应用版本和构建号没有因源码修改自动变化；提交源码不代表已交付新 APK。

## 实车复测判断

当前 169 实车证据仍是 [1210 日志](PREFACE_INSTRUMENT_NAVIGATION_2026-10-27_BUILD1210.md)，不能当作本次补齐后的结果。复测应核对当前应用进程中五个分项方法的原厂日志、SDK／HAL 的实际发送、停止清理和人工仪表显示。即使合并输出仍记录 `firstFrame=0`，也不能单凭它判断分项路径失败；反过来，方法调用返回也不能证明分项到达仪表。

参考实现的分项编码包含 HUD 命名和独立车辆属性，这证明原厂确实有分项发送逻辑，不证明无 HUD 的星瑞仪表必然消费这些信号。169 的实际实现、准入和目标接收仍需实车核对；应用始终由安装的 SDK 选择属性和编码，不套用 327 的固定属性，也不增加车型分支。
