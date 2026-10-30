# 仪表导航独立进程服务：已实现范围与原厂引擎阻塞

**2026-10-30 更新：此独立方案已按用户预先授权撤回。** 327 静态接入验证确认固定高德 JSON 服务依赖，主 APK 内的独立引擎不能自动接入该准备链路。源码已移除下述 Service／Binder、进程分支、库导入脚本、清单及构建任务，恢复直接 SDK 发送，并明确要求原厂高德准备。以下保留 1212 历史范围和当时的验证记录，导入命令不再是当前可用工具，详见 [撤回说明](PREFACE_INSTRUMENT_OEM_FALLBACK_2026-10-30.md)。

2026-10-29，按用户要求将仪表导航改为主 APK 内的独立进程服务。**私有服务、导航发送迁移及会话释放已实现；原厂高德 5.x 引擎的独立启动尚未实现，因此本次不是“原厂仪表库已脱离高德运行”。** 当前服务仍调用车机安装的 `DimInteraction/INaviInteraction`，保留已验证的合并加分项发送，不会将 Binder 连接、库文件导入或 SDK 连接当作真实地图准备。

## 有／无原厂高德使用同一服务

用户后续明确目标是方便兼容保留原厂高德与没有原厂高德的情况。本轮保持**同一主 APK、`:instrument` 独立进程、未导出且仅接受同 UID 的私有 Binder**，没有新增独立 APK、模块、安装包名、公开 IPC 或证书准入规则。服务不加载已安装高德的代码，不查询其版本作为准入门槛，也不主动打开、安装、恢复或停用地图。

| 地图环境 | 应用侧服务行为与验证边界 |
| --- | --- |
| 保留原厂高德 | 仍需真实 SDK 连接和会话检查；地图存在不等于 SDK 已连接或仪表已准备。 |
| 升级为第三方高德／停用高德 | 保持同一 SDK 数据接口，不按地图版本切换发送逻辑；不尝试重启原厂地图。 |
| 未安装高德 | 不因缺少地图 APK 阻止 SDK 调用；SDK 存在时仍执行同一连接、发送与释放检查。**这不等于已经实现无地图时的独立准备或显示。** |
| SDK 缺失／接口不兼容／权限拒绝 | 分别报出固定原因并停止本会话；原厂地图存在不能替代缺失的 SDK，也不能作为伪造成功的理由。 |

私有协议中的连接状态现命名为 `SDK_CONNECTED`（原 `READY`，枚举位置未变），只表示已有 SDK 的连接／可用性检查通过，不表示原厂引擎、渲染或显示准备。未增加未实现的独立引擎接口或可选模式。SDK 方法缺失、抽象方法错误及接口形状不匹配统一报告 `instrument-service-sdk-contract-mismatch`；另区分 SDK 不存在、创建失败、权限拒绝和主线程超时。日志只使用固定原因码，未知厂商异常消息不对外透传。

新增回归在模拟包管理器中覆盖原厂包、9.5 包、停用包和未安装地图，并使用测试 SDK 核对一致的连接、字段发送及停止流程；这些是应用侧依赖边界验证，不是实车 169 的四种环境均已验收。完整原厂引擎初始化、无高德环境的真实准备及显示仍是未完成项，详见下文。

## 当前代码行为

- 主 APK 的 `PrefaceInstrumentService` 使用 `:instrument` 进程，`exported=false`，不另装助手、不改主包签名或授权组件。当前控制器通过显式绑定和私有 Binder 传入真实导航数据。
- 服务协议校验调用 UID、版本、Binder 会话身份、递增序号、已确认箭头 102–109、非负距离／时间及最多 31 个 UTF-16 单元的有效道路名；不提供广播输入、任意包名或第三方导航接入。
- Binder 线程先使被替换／关闭的会话失效，SDK 操作串行执行。客户端死亡及 5 秒心跳失联触发清理；客户端每秒发送心跳。只保留一份待发送的最新导航数据，取消时丢弃积压数据。
- 现有 SDK 的逐字段会话复查、真实连接观察、权限失败处理、停止清理和 550 毫秒旧清理屏障继续保留。SDK 断开、服务失联或调用失败后停止本会话，不回退到主进程写车辆属性，也不自动重试到新会话。
- `DiPlayApplication` 仅在主进程注册蓝牙、USB 自动连接及媒体中心；服务进程保留语言环境，不重复启动这些功能。
- 客户端日志明确记录“服务已接收排队”，不再将它误写为 SDK 调用已完成。服务的逐次完成日志仅 Debug 输出，结束汇总及失败原因保留；不记录道路内容、Binder 对象或原始异常消息。

服务内部的 `SDK_CONNECTED` 仅代表现有 SDK 的连接和可用性检查通过，仍由安装的 SDK 处理自身状态，不代表高德引擎或仪表显示已经准备。新进程的实车准入、冷启动、断开／重连及显示效果尚未验证。

## 原厂库已完成的准备

原厂来源仍是用户允许使用的 IHU519G／FS11A1／构建 327，OTA SHA-256 为 `f61a7399d61a7f4502b9289ea6c49c661c39c459aae81e676722bcf9be913c01`。AutoMap 5.0.7.605871 APK SHA-256 为 `82272e4dc531c3ff1b1c6fff02d5fc0362f963319e435e0f5021d4b6dc5c67dc`，不是实车 169 的相同固件。

新增固定输入清单 [automap-327-instrument-libraries.json](firmware/automap-327-instrument-libraries.json) 和 `scripts/original_instrument_assets.py`，核对 APK 来源、28 个库各自大小／SHA-256、arm64 ELF、重复 ZIP 条目、目录链接、额外文件和元数据。只导入列出的库和描述清单，不复制整个高德 APK、VDEX、Java、license 或其他资产。文件仅留本机忽略目录 `agent_docs/runtime-original-instrument/`。

```powershell
python scripts/original_instrument_assets.py --prepare
python scripts/original_instrument_assets.py --verify
```

可用 `--apk` 指定同一哈希的原厂输入、`--directory` 指定本地输出。Gradle 的 `:mobile:verifyOriginalInstrumentAssets`／`:automotive:verifyOriginalInstrumentAssets` 可按需验证，`DIPLAY_ORIGINAL_INSTRUMENT_DIR` 指向已导入目录。**这不是构建前强制依赖，也未将这组资产加入默认 APK**；避免在引擎尚不能使用时扩大安装包和构建输入要求。

已导入 28 个库，共 91,694,752 字节；元数据始终标记 `bootstrapImplemented=false`。导入不调用 `System.load`、未知 C++ 入口或准备接口，不加载到主进程，也不伪造回调。

## 尚未完成的核心部分

原厂 `StartupInstrumentPannelLarge/Small` 最终依赖 `GNS_FRAME::CGPackage::startupInUI(..., CGFrameSimulation*)`，并访问 `hsl::MultiScreenService`。现有资料没有可直接安全调用的完整引擎初始化协议及对象创建／资源装配链路；不能猜测对象布局或传入假对象。仅把 `.so` 放进 Android Service 并不能创建该对象，更不能得到真实首帧。

因此本次未提供原厂原生引擎的加载／启动／导航数据注入／渲染就绪／退出适配，也未实现脱离原厂高德的准备。当前实际通路是：

```text
CarPlay 当前真实路线 → 私有 Binder → 独立进程服务 → 车机已安装仪表 SDK
```

后续完整托管仍需还原同版本引擎的有效初始化、资源和 Java/JNI 依赖、当前路线的数据接口及真实生命周期回调，并在不运行原厂高德的条件下验证冷启动和停止。不能用直接写准备属性、调用投屏开关、重放高德 116、替换主签名或扩权授权组件补齐这一步。成功与 9.5 差异证据见 [前序分析](PREFACE_INSTRUMENT_AMAP95_COMPARISON_2026-10-29.md)。

## 验证范围

后续地图依赖边界与错误分类改动重新执行 shared Debug／Release 各 59 项，合计 **118 项通过**。common 启动与 Python 导入检查沿用前一阶段的已通过记录，本轮未重复运行；没有重跑已确认同样失败的旧方控回归。

| 最终定向检查 | 结果 |
| --- | --- |
| shared Debug：服务、协议、SDK 后端、地图包环境和失败分类 | 59 项通过 |
| shared Release：同一组服务与导航逻辑 | 59 项通过 |
| common：主／仪表进程 Application 启动 | 前一阶段 2 项通过 |
| Python：固定来源、内容篡改、重复条目、ABI 和额外资产保护 | 前一阶段 6 项通过 |
| 原厂实际 28 个库及 Gradle 按需校验入口 | 通过 |
| 公共文件检查、JSON、manifest 与差异格式 | 通过 |

定向验证涵盖服务身份和参数、乱序／重复消息、更新合并、取消与替换、发送中失效、清理屏障、失联释放、权限失败、断开后不重试、主／服务进程启动区别，以及原厂输入保护。SDK 验证使用测试替身；Binder 使用 Robolectric 下的 Parcel，不是实车跨进程验收。

另运行既有 `PrefaceFirmwareIntegrationTest` 时，26 项中 12 项方控／媒体断言失败；恢复原来的 Application、仪表发送入口、SDK ready 与 manifest 后复测，仍是相同的 12 项失败，不将其计为通过，也未在本次改写方控行为。对照及完整输出仅保存在 `agent_docs/instrument-service-implementation-20261029/`。一次多任务测试命令可能扩大过滤范围，发现后已中止，最终按单任务重新执行定向范围，不将该次中止结果计为通过。

以上源码阶段没有运行完整 CI、Lint、assemble、签名或安装，版本与构建号当时不变。后续按用户要求仅构建 0.2.2／1212 mobile Debug 并核对主签名、内置组件 11、DEX 及私有服务声明；无在线 ADB 设备、未安装，详见 [1212 构建记录](ANDROID9_AUTHORIZATION_COMPONENTS.md#0221212-测试包构建核验)。1211 旧 APK 不含服务改造。1212 未包含原厂引擎库，独立准备与实车显示仍没有新增成功结论。
