# 原车高德的仪表准备流程

后续源码补齐与分析修正见 [原厂分项发送](PREFACE_INSTRUMENT_NATIVE_FIELDS_2026-10-29.md)：原厂合并信息后还逐项发送箭头、距离、ETA、道路名，并有显式开始／停止状态。本页追踪的是合并路径的准备条件，不能据此认定分项路径也必须等待扩展渲染首帧；此前遗漏这组调用，本次已补入源码，尚未打包及实车验证。

2026-10-29，继续复核用户提供固件中的原车高德。已从缓存系统镜像提取 `AutoMap.apk` 及预优化代码，确认 **327 参考固件的仪表准备状态来自高德扩展渲染事件，经 NaviService 更新后才交给仪表 SDK**。普通导航开始和 Widget 首帧不是同一个事件。1210 仅接入 SDK 的连接及导航数据发送，尚未补齐这段准备过程；仪表显示仍未修复。

## 来源与分析方法

| 输入 | 身份与 SHA-256 |
| --- | --- |
| 用户 OTA | IHU519G／FS11A1／Android 9／构建 327；`f61a7399d61a7f4502b9289ea6c49c661c39c459aae81e676722bcf9be913c01` |
| `system/app/AutoMap/AutoMap.apk` | `com.autonavi.amapauto`；版本 `5.0.7.605871`／6030053；167,758,944 字节；`82272e4dc531c3ff1b1c6fff02d5fc0362f963319e435e0f5021d4b6dc5c67dc` |
| `system/app/AutoMap/oat/arm64/AutoMap.vdex` | 7,828,416 字节；`33c78415570eb7b6aacc59c43bcb807a8099c5d03976ceaa2b7c5cd80f71608e` |

APK 内没有 `classes.dex`，代码位于配套 VDEX，不能仅反编译 APK 后就认定没有实现。本轮使用缓存 JADX 1.5.6 中的 dexlib2 读取 VDEX 偏移 40 的 CDEX，核对指令边界、常量、分支、接口及静态调用。部分虚调用仍是 quickened 指令，保留虚表序号和字段偏移，不将其冒充完整 Java 反编译结果；JNI 原生渲染内部也未完整追踪。

完整 APK、VDEX、指令及分析脚本只保存在 Git 忽略目录。相对 `agent_docs/firmware/ihu519g-327/` 的本地证据为：

- `catalog/automap-instrument-20261027/manifest-badging.txt`、`manifest-xmltree.txt`：包身份与组件声明。
- 同目录 `amap-readiness-focused.txt`：本轮聚焦指令，SHA-256 `5b2b4c5a9579c79f7e31ccd9c4c10c4f86cfc2eed24df79dfcd4a22f2c542988`。
- 同目录 `readiness-findings-20261029.json`：输入和源码逐文件字节数、SHA-256；`inventory.json`、`status-instructions.txt` 保留搜索索引和扩大范围的指令。

## 三类首帧必须分别处理

| 来源 | 高德内部状态 | 对外协议／NaviService 状态 | 本次确认的含义 |
| --- | --- | --- | --- |
| 普通 `FIRST_DRAW` | 116 | JSON `autoStatus=200`；参考 `aq` 未将 200 映射为仪表准备事件 | 不能把这个内部 116 直接当成 NaviService 的 116 |
| `EXSCREEN_FIRST_DRAW` | 200 | JSON `autoStatus=116` → NaviService 116 | 触发 `V2DIM` 准备缓存的事件 |
| AIDL Widget 首帧 | 3026，另有 `onWidgetFirstFrameDrawn()` Binder 回调 | JSON 3026 → NaviService 124；Binder 路径也发布 124 | 参考缓存不以 124 设置仪表准备完成 |

关键证据为聚焦指令中的以下方法及代码单元偏移（PC）：

- `Lb70.onNaviStatusChange(int)`：PC `000f/0011` 将内部 116 导向 `0157` 的 `FIRST_DRAW`，`0013/0015` 将 200 导向 `0150` 的 `EXSCREEN_FIRST_DRAW`。
- `Lbu.a():ProtocolBaseModel`（日志名 `AutoStatusDisAction`）：PC `0041` 的 switch 中，输入 200 的相对偏移 140 落到 `00cd`，输出 116；输入 116 的偏移 144 落到 `00d1`，输出 200。构造参数写入与读取的字段偏移均为 20，状态详情使用偏移 24，未将二者混用。
- `AndroidProtocolExe.sendExScreenStatus(int,int,int)`：PC `0064–009b` 区分 `SYSTEM_PRESENTATION`、`VIDEO_STREAM`、`AUXILIARY_NAVIGATION_ACTIVITY`；状态参数为 0 时构造 `Lbu(200)`，为 1 时构造 `Lbu(3028)`。PC `009f–00b7` 的 AIDL 内外 Widget 分支只在参数为 0 时构造 `Lbu(3026)`。
- `AutoSimilarWidgetService$c.u()`：PC `0022` 调用 `IAutoWidgetStateProvider` 对应的首帧回调；NaviService 的 `map/amap/j.java:19–29` 接收后发布 124。

这里的“扩展渲染”是原厂代码分类，不能仅凭名称推导实车有地图飞屏，或为本仓库增加飞屏功能。星瑞实际是否以其他内部渲染完成相同准备，仍需核对实车 169 的实现。

## NaviService 到仪表 SDK

以下路径相对缓存的 `decompiled/naviservice/sources/`：

| 实现与行号 | 处理 |
| --- | --- |
| `defpackage/ag.java:32–36`、`defpackage/aq.java:44,54` | 读取协议 `autoStatus`；116 映射为 116，3026 映射为 124，再调用地图状态监听者 |
| `ecarx/naviservice/map/a.java:898–900`、`map/ck.java:164–169`、`map/cg.java:73–76` | 将状态加入地图状态机，更新对应地图缓存，并发布给服务使用 |
| `ecarx/naviservice/map/co.java:105–113,253–301` | `V2DIM` 缓存仅值 1 表示 ready；状态 116 写入 1；地图状态 9 写入 2，使 ready 失效；导航开始 16 不执行写入 1 |
| `ecarx/naviservice/a/a.java:236–259,460–484` | 收到 116／9 等事件后重算当前地图的支持和真实准备状态，再调用 `setPresentationToDimSwitch` |

SDK 对应代码位于 `decompiled/adapter/sources/com/ecarx/xui/adaptapi/`：

1. `dim/impl/DimInteractionImpl.java:103–117`：连接存在时按上述结果维护 `sys.ecarx.navsyncscreen.status`，随后调用 `updatePresentationStatus()`。
2. `dim/impl/nav/NaviInteractionImpl.java:228–232`：更新准备状态时重新处理上次导航数据；`notifyTurnByTurnStarted()` 在该参考实现的第 77 行为空，不能代替准备事件。
3. `dim/impl/ECarXDimServiceImpl.java:572–600`：生成有效导航内容的条件包含非零导航状态和 `firstFrame==1`，SDK 连接成功本身不足以满足。

原车还注册了导航交互回调：`a/a.java:320–330` → `a/p.java:95–96`。SDK 在回调已注册且收到非零导航状态时会首次请求动作 35（`ACTION_DIM_DISPLAY_FULL`），原车回调将其转换成 `DimDisplayMode(2)` 交给地图流程。**注册一个空回调并不会产生渲染就绪事件**；把动作交给原车高德也不能自动变成 CarPlay 导航。不能将此回调当成可直接复制的独立转向提示授权接口。

上述可搜索源码的 SHA-256 与完整路径已写入事实索引的 `instrumentNavigationOriginalAmapReadiness20261029`，原始高德内容不入库。

## 与实车 1210 的关系及下一步

[1210 实车日志](PREFACE_INSTRUMENT_NAVIGATION_2026-10-27_BUILD1210.md) 来自 **IHU353G／构建 169**，其中已观察到 36 次有效导航输入、38 次 `firstFrame 0`，有效更新与清除均输出相同短帧。327 的静态链路解释了为什么只补 SDK 连接和 `start/update/stop` 仍不完整，但没有证明实际 169 采用完全相同的准备来源。

当前可确认的是：CarPlay 导航数据已进入安装的 SDK；有效输出所需准备条件没有满足。当前不能确认的是：169 上原车高德在无飞屏条件下如何完成准备，以及 CarPlay 是否有可用的独立转向提示入口。169 实际输出私有属性 `0x2d700050` 已与参考 327 的 `0x2d70004e` 不同，不直接套用参考字节或属性。

下一步应取得实车 169 对应的高德、NaviService 与仪表 SDK（包含配套 VDEX／ODEX），并用同一车机的一次原车高德成功导航日志核对：状态 116／124 的实际来源、`notifyV2DIMState isReady:true`、`firstFrame 1`、有效导航输出及停止清理的顺序。已复查当前归档缓存的三份主日志，未检出上述导航 ready=true／firstFrame=1 或高德扩展渲染事件；其他服务同名 `isReady:true` 不计为导航证据，日志未检出也不否定用户确认的原车高德成功显示。只复制一个没有 DEX 的 APK 不足以复核；若原厂成功流程完全不同，应以 169 实际实现和运行日志为准。2026-10-29 检查 ADB 时没有在线设备，本轮无法从车机读取这些材料。

本轮完成静态分析、输入哈希复核和文档记录。应用运行代码、设置及权限没有变更，没有伪造高德事件或准备状态，没有构建、签名、安装 APK 或提交 Git。
