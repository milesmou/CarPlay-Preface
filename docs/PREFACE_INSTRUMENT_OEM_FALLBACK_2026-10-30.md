# 仪表独立移植的 327 静态验证与撤回

2026-10-30，用户要求先尝试完全不依赖原厂高德的版本，验证不可行后移除独立移植模块，明确依赖原厂高德；后续再次指定直接使用缓存 327 固件。本轮沿完整引擎和车机接入两侧继续核对，发现**在当前包名、私有 IPC、不改系统服务和不伪造准备事件的边界内，已确认的 327 准备链路不能接入主 APK 内的独立引擎**。按预先授权撤回独立移植代码，保留原厂 SDK 导航发送。

没有产出完全独立 APK，也没有执行 169 实车独立版测试。这里是候选方案在静态接入验证阶段失败，不将其描述为所有可能实现或所有星瑞固件均不可行。

## 新核对的启动与接入证据

来源沿用用户允许的 IHU519G／FS11A1／Android 9／构建 327。OTA SHA-256 `f61a7399d61a7f4502b9289ea6c49c661c39c459aae81e676722bcf9be913c01`；AutoMap 5.0.7.605871 APK `82272e4dc531c3ff1b1c6fff02d5fc0362f963319e435e0f5021d4b6dc5c67dc`；配套 VDEX `33c78415570eb7b6aacc59c43bcb807a8099c5d03976ceaa2b7c5cd80f71608e`。实际车机 169 与该缓存不是相同实现。

本轮使用 VDEX 自带 quickening 表恢复字段／方法引用，再由现有 dexlib2 重写为可分析的标准 DEX：8,019 个类、41,748 个含代码的方法，恢复 200,685 个 quick 指令、352 处移除的类型转换、20,172 处 return 指令；逐方法校验 quickening 索引消费完整，输出不含 odex 专属指令。JADX 返回 6 个错误，不能声称全部 Java 源码无误。原始、转换后的二进制、反编译和原始指令仅留在忽略目录 `agent_docs/instrument-independent-20261030/`。

转换格式参考 [AOSP VDEX 处理](https://android.googlesource.com/platform/art/+/android-9.0.0_r20/runtime/vdex_file.cc)、[反 quickening 实现](https://android.googlesource.com/platform/art/+/android-9.0.0_r20/runtime/dex_to_dex_decompiler.cc)；没有下载或替换本地构建工具。

| 证据（相对本机缓存） | 确认的行为 |
| --- | --- |
| 新恢复的 `framework/AutoLoader.java`、`framework/AutoMainThread.java`、`framework/LibraryLoaderHelper.java` | 原厂经环境、配置、库注册与 Java/JNI 初始化，再创建原生句柄及 Surface 生命周期；`libEaglet` 等启动库在此前 28 个 `DT_NEEDED` 库之外。28 个库的导入不等于完整启动。 |
| `decompiled/naviservice/sources/defpackage/g.java` 的 `f()` | JSON 协议客户端显式绑定 `com.autonavi.amapauto/com.autonavi.amapauto.protocol.service.JsonProtocolService`，不是按 action 任意发现一个 CarPlay 服务。 |
| 同目录 `defpackage/ab.java`、`ai.java`、`ag.java`、`aq.java` | 从该 JSON 连接接收协议 30200，将 `autoStatus=116` 交给地图状态监听；随后才进入原有准备缓存与 SDK 流程。 |
| 同目录 `ecarx/naviservice/map/amap/c.java` 的 `a(int)` | 普通广播的状态分支仅处理 107／108／109 的路线选择；不会把内部状态 200 或外部 116 转成仪表准备。单独启动库或发普通广播不能替代 JSON 连接。 |
| 同目录 `ecarx/naviservice/map/ce.java`、`bh.java`、`d/i.java` | 创建高德缓存前检查固定高德包名是否已安装；完全没有该包时不创建这个地图入口。该检查不区分原厂与同包名的 9.5，不能单独解释升级失败。 |

因此，单纯把旧引擎放到 `com.preface.carplay:instrument`，即使未来启动成功，其 JSON 回调也不会自动成为系统已绑定的高德回调。存在 9.5 时，系统绑定的仍是 9.5 的服务。要改变该结果需另有已确认的接入方式；本轮没有用改原厂服务、占用高德包名、更换主签名、开放新外部 IPC、重放 116、直写属性或直接调用投屏开关来替代。

## 撤回范围与保留行为

- 移除独立进程 Service、私有 Binder、客户端／Host／进程识别及其专属测试和 manifest 声明，移除主 Application 的次进程分支。仪表入口恢复直接使用原有 `PrefaceInstrumentSdkBackend`。
- 移除 28 库导入脚本、对应 Python 测试、构建校验任务和发布树内的库导入清单。不删除本机原始固件、已导入库、历史 1212 APK 或分析证据；撤回前源码快照留在上述忽略目录。
- 保留真实 iPhone 导航输入、确认过的箭头映射、原厂合并加五个分项、开始／停止状态、SDK 连接和权限检查、取消后逐字段失效、失败停止、会话替换以及 550 毫秒旧清理屏障。
- 导航设置明确必须保留支持仪表功能的原厂高德，并先由其完成仪表准备。连接和诊断不再暗示独立引擎；SDK 连接仍不代表显示已准备。应用不自动打开、安装、恢复或停用高德，不按包存在或版本号伪造准备。

原厂高德准备后 1211 的 42 次有效输出与用户确认的可见成功仍是实车证据；升级版 9.5 的准备失败也仍保留。源码撤回不是新的实车成功验证，1212 历史包仍包含已经撤回的私有服务。

## 定向验证与测试包

shared Debug／Release 各 34 项通过，合计 68 项，无失败、错误或跳过。覆盖原厂字段与开始／停止顺序、失败停止、旧清理屏障、会话替换及取消后不再报告可用／发送，取消仍能清除已拥有输出。这些 SDK 调用使用测试替身，不是实车验证；未运行完整 CI 或 Lint。

仅执行 `:mobile:assembleDebug`，生成 0.2.2／1213，保留主包原签名与内置独立签名的授权组件 11。检查最终 manifest／DEX 不含已撤回的服务、Binder 和进程类，APK 不含原厂仪表库或对应资产，仍含 SDK 合并加分项及明确的原厂高德依赖说明。ADB 无在线设备，未安装；构建核验见 [1213 记录](ANDROID9_AUTHORIZATION_COMPONENTS.md#0221213-测试包构建核验)。没有因静态方案失败而生成一个只会绑定 SDK／加载库、却宣称已经独立的 APK。
