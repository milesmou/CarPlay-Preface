# 仪表导航仍未显示：1210 实车日志分析

后续修正：2026-10-29 [补齐原厂分项导航发送](PREFACE_INSTRUMENT_NATIVE_FIELDS_2026-10-29.md)，新增原厂整数箭头、距离、ETA、道路名及开始／停止状态调用。本文的 firstFrame 阻塞结论针对 1210 已调用的合并路径，不涵盖原厂分项输出。源码补齐尚未打包，1210 不包含此次变更。

2026-10-27，用户反馈 0.2.2／1210 的仪表仍无导航提示。本次已确认：**真实导航数据进入原厂 SDK，SDK 连接和调用均已走通，车辆写出路径也有对应记录；但原厂导航仪表准备状态一直为 `0`，SDK 输出始终是同一短帧，包含 36 次有效导航更新和 2 次清除。当前阻塞已进入原厂输出准备阶段，不能再归因于 1208 的 `available=false`。**

此前修正只完成 `DimInteraction → INaviInteraction` 的连接及 start/update/stop 接入，尚未跑通原厂的有效仪表输出准备流程。`native SDK update submitted; display-confirmed=false` 只表示调用返回，不能证明生成了有效箭头或仪表已显示。本次不是实车修复验收。

## 来源与适用范围

| 输入 | 身份与 SHA-256 |
| --- | --- |
| 应用报告 | `E:/DiPlay-20261027-161321-942.txt`；507,953 字节；`25c1b641b6b25e14454180fe077b22ddbab1bfa0163c9f563374ee47bb01d052` |
| 车机压缩日志 | `E:/IHU353G_log_261027161345/mobilelog.zip`；24,274,221 字节；`ce371d4da54a21bf59ed4c058b6c152747279657b8c0f1692cadd444d1abfdbf` |
| 当前主日志 | `APLog_2026_1027_140343__22/main_log_2__2026_1027_161213`；`86fd27bf80b2296a72b1b616cd65a6d408f4089043eec4440d8060b77b5c84fa` |
| 当前属性快照 | 同目录 `properties`；`3dbb7acdde6ad555a4225c97b7640591bc3a028f1a63ba221316a9eefb24c371` |
| E 盘 APK | `CarPlay-Preface-0.2.2-1210-debug.apk`；`8e8e1ad32a12715cf23a31fc7195d9aa68eb845a1290290d5119262bc978b720`，与交付包一致 |

应用报告第 2 行确认构建 1210；当前会话日志从第 2349 行的 **16:08:47.240、PID 28654、`logging=debug-detailed`** 开始。报告还包含 14:01 的 1208 历史日志，压缩包也包含此前 `APLog_2026_1027_132651__21/`，不能混成当前复测结果。当前归档单独提取到本地 `current/`，属性快照不与历史同名文件混用。

实际车机仍为 IHU353G、Android 9／API 28、系统构建 169；属性快照第 8494、8526、8737 行分别对应构建、型号及指纹。参考 OTA 仍为 IHU519G／FS11A1／构建 327，SHA-256 `f61a7399d61a7f4502b9289ea6c49c661c39c459aae81e676722bcf9be913c01`。以下分别说明当前 169 运行事实与 327 静态实现，不将两者当作同一固件。

原始报告、完整日志、导航字段和提取索引仅保存在 Git 忽略目录 `agent_docs/instrument-navigation-build1210-20261027/`；公开文档不记录道路名、地点或设备私有身份。读取期间 E 盘曾断开，用户重新接入后已完成缓存和哈希核对。

## 当前会话的直接证据

以下系统日志行号均以 `main_log_2__2026_1027_161213` 为准。文件跨进程交错，不按相邻行直接推导时间先后。

| 时间 | 观察 | 应用报告／系统日志行号 |
| --- | --- | --- |
| 16:09:03 | 当前控制器启用仪表导航，使用原厂 SDK | 应用 2513；系统 79431 |
| 16:09:40–41 | iAP2 路线与转向消息到达，解析出支持的箭头 | 应用 2672–2678；系统 119974 |
| 16:09:41.730–.735 | 原厂连接成功，应用 `available=true` | 应用 2679–2680；系统 120251、120286 |
| 16:09:41.739313 | `NaviInteractionImpl` 收到 `navStatus=1`、有效转向 109，以及非零距离／剩余量 | 系统 120293；道路名仅保留本地 |
| 16:09:41.739653 | 原厂 `ECarXDimServiceImpl` 记录 `updateNAVInfo: firstFrame 0` | 系统 120321 |
| 16:09:41.739771 | 原厂输出 `updateNAVInfo : \| [3,10,0] {0,2,0,0,0,0,0,2}` | 系统 120328 |
| 16:09:41.739855–.740375 | 当前进程调用 `setProperty(0x2d700050)`，服务转为 `HalClient setValue 762314832` | 系统 120335、120377、120383 |
| 16:09:41.740691 | 车辆层记录匹配写出 `0e 13 03 00 03 0a 00 00 02 00 00 00 00 00 02` | 系统 121716 |
| 16:09:41.742–16:11:09.932 | 应用共记录 36 次原厂更新调用返回 | 应用首末 2681、2820 |
| 16:10:24、16:11:11 | 两次清除，原厂状态变为 0，SDK 仍输出相同短帧 | 应用 2786、2821；系统 158859、207143 |

本进程在原厂日志中有 **36 次 `navStatus=1`、2 次清除、38 次 `firstFrame 0`，38 次输出均为上述同一短帧**；未观察到本进程 `firstFrame 1`。当前属性快照第 9214 行同样为 `sys.ecarx.navsyncscreen.status=0`。这项原厂准备状态与 CarPlay 中控视频的首帧、HEVC 解码器或实际中控画面不是同一状态。

有效调用时 SDK 已打印输入的箭头、距离及道路字段，但输出没有带出相应导航内容，且与清除时完全相同。结合准备状态，说明阻塞在原厂有效输出准备环节；短帧的每一位含义尚未用当前 169 的 SDK／协议逐项复核，不能把 327 的编码布局直接套用。

16:09:41–16:11:12 范围内记录到该进程 44 次 `0x2d700050` 调用、服务 44 次同属性 HAL 调用，以及 34 条匹配短帧的车辆层写出。该属性可能还承载清理等私有消息，不能把 44 次全部当成有效导航更新，也不能仅凭写出日志宣称 MCU／仪表确认接收。日志没有本进程的 `SecurityException`、`Permission Denial`、`sendBytesToMcuByPropID error` 或 `InReset` 候选；这里的 Property.service／CAR.HAL 使用 E 级别记录正常调用，级别 E 本身不是权限拒绝证据。

## 原厂准备流程为何仍缺失

当前归档中，原车导航进程 PID 1782 曾在 **14:10** 记录 `notifyV2DIMState ... sptV2D:true, isReady:false`，随后调用 `setPresentationToDim on:false`，`DimInteractionImpl` 记录“导航投屏状态未准备好，不可以投屏”。对应 `main_log_1__2026_1027_160806` 第 242605、243871、243893、243974、243995 行。这是当前归档中的较早事件，说明状态来源链路，不能称为 16:09 的一次原车抢占或清除。

327 缓存可复核的静态对应关系如下，相对目录为 `agent_docs/firmware/ihu519g-327/`：

| 参考实现 | 关键事实 |
| --- | --- |
| `decompiled/naviservice/sources/ecarx/naviservice/a/a.java:460,479,484` | NaviDashboardManager 从原车真实准备状态派生开关，再调用 `setPresentationToDimSwitch`；不是开始导航后直接一律设 true |
| `decompiled/adapter/sources/com/ecarx/xui/adaptapi/dim/impl/DimInteractionImpl.java:103,107,110` | 维护 `sys.ecarx.navsyncscreen.status`，并通知导航实现更新准备状态 |
| `decompiled/adapter/sources/com/ecarx/xui/adaptapi/dim/impl/nav/NaviInteractionImpl.java:77,270,311` | `notifyTurnByTurnStarted()` 在此参考实现为空；`updateNavigationInfo()` 才处理数据和回调；不能用 start 调用返回代替准备就绪 |
| `decompiled/adapter/sources/com/ecarx/xui/adaptapi/dim/impl/ECarXDimServiceImpl.java:572,581,590,600` | 有效输出条件包含非零导航状态及 `firstFrame==1`，不仅是车辆服务连接成功 |

上述四份源文件 SHA-256 依次为 `3308c3092469c73b94907cac6ce5d52281f4d8a1bc8049c8f3089e5aa66f731d`、`b8bdbaa5287e4394465dade738462dff50a1764fec1b0b9fbfe2102ba6e8d075`、`f6f75d353ced5847fc033c9cb1cf1d89f1f8de64f00b70ebf02fd13af53e927f`、`d3abdad1781665ee7650de84614431ce643386d88b79f37b2b03679db516adf6`。

1210 只判断 SDK 真实连接，`available()` 实际返回 `ready()`，没有完成上述准备流程。应用按既定边界没有调用投屏开关、伪造首帧或改写系统属性；因此，删除 1208 的多余门槛并接入 SDK 后，原厂自身的准备条件仍未满足。**之前的修正不能称为完整原厂流程已跑通。**

另有固件差异必须保留：本次 169 实际写入属性是 `0x2d700050`／762314832（`ID_MCU_CAN_PRIVATE`），327 参考输出使用 762314830／`0x2d70004e`，输出字节也不同。1210 交给安装的 SDK 选择路径已经有运行证据，不应回退成直接写 327 的固定属性或擅自改编码。

## 修复方向与本轮结果

2026-10-29 已继续读取固件内原车高德及配套 VDEX，补充 [原车准备流程](PREFACE_AMAP_INSTRUMENT_FLOW_2026-10-29.md)：参考 327 的 V2DIM ready 来自扩展渲染事件，而非普通导航开始或 Widget 首帧；该静态证据尚不能代替实车 169 的流程复核。

下一步需要复核实际 169 固件的仪表 SDK，确认星瑞无飞屏条件下转向提示如何取得真实的输出准备状态，以及相应回调／准入流程。SDK 连接、原厂准备、生成有效内容、车辆发送和仪表显示应分阶段诊断。继续重复 `updateNavigationInfo()` 不会补齐准备状态；直接把系统属性设为 1、假装已有投屏首帧或无条件调用投屏开关，也不能当作已按原厂流程修复。

MCU／仪表对有效提示的接收与显示、实际 169 的独立转向提示启用路径仍待验证。本轮完成日志与源码分析、更新说明及事实索引；没有修改应用运行行为，没有运行 Gradle、构建、签名、安装或提交 Git。原始证据、带行号索引和统计位于本地 `sources.json`、`application-summary.json`、`runtime-summary.json`、`runtime-navigation.json`、`output-path-summary.json`。
