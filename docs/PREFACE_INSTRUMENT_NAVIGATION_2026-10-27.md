# 仪表导航未显示：1208 应用与车机日志分析

最新实车结果见 [1210 分析](PREFACE_INSTRUMENT_NAVIGATION_2026-10-27_BUILD1210.md)：SDK 连接和更新调用已经走通，但原厂准备状态仍为 0，输出与清除时相同的短帧；有效仪表输出准备流程尚未完成，不能把下面的源码修正当作实车显示已修复。

后续交付：2026-10-27 已构建并核验 0.2.2／1209 测试 APK，包含本文原厂 SDK 修正及导航页首项、去除实验性、缺失保存值默认开启的调整；保留已保存的关闭选择，下次连接生效。主包原签名及内置授权组件 11 核验通过，已覆盖安装至在线模拟器 `emulator-5558` 并查询确认版本。此前 39 项定向验证通过，本次仅打包，未运行完整 CI。实车 169 固件的 SDK 准备状态、实际输出和仪表显示仍待复测。详细哈希、签名和安装记录见 [1209 构建核验](ANDROID9_AUTHORIZATION_COMPONENTS.md#0221209-测试包构建核验)；以下“未打包”表述记录各源码修改阶段当时的状态。

2026-10-27，用户提供 E 盘的新应用报告和车机日志，要求分析仪表导航指示未成功的原因。

**已确认本次直接阻塞点：CarPlay 导航数据已被应用接收并识别，车辆服务连接和目标属性配置检查也已通过；但原车导航／电话状态被读成 unknown，1208 因此返回 available=false，在调用 NAV F3 写入之前暂停了输出。本次并非已经发送后仪表不显示。**

## 来源与适用范围

| 输入 | 身份与 SHA-256 |
| --- | --- |
| 应用报告 | `E:/DiPlay-20261027-140229-499.txt`，0.2.2／1208；`2358f68884128e5df927c337a1311892f235f524d331e8218ca022e1745486fb` |
| 车机日志 | `E:/IHU353G_log_261027140246/mobilelog.zip`；`463f1886235616263d0af04aaa68a25b99c63ecad74e018b17c957e4be9eed64` |
| 当前会话系统日志 | `APLog_2026_1027_132651__21/main_log_2__2026_1027_140304`；`b4ef9e66ae50e0f187ecb672df5dc8d5f83d5903a5c46362e2556235b68625b4` |
| 当前属性快照 | 同目录 `properties`；`6e6118225b30a913cf41b4c1ce23eda0be2ba8c2783824f3d6d4ba40785d0112` |
| E 盘 APK | `CarPlay-Preface-0.2.2-1208-debug.apk`；`33c301de651acf0959e950a25d687fd54371b5791c51715e85024333e8833ed3`，与此前交付包一致 |

实际车机为 IHU353G、Android 9／API 28、构建 169，系统指纹为 `ecarx/IHU353G/IHU353G:9/PQ2A.190405.003/169:user/dev-keys`，来自 properties 第 8405、8407、8409、8438、8441、8651 行。参考固件仍为 IHU519G／FS11A1／构建 327，OTA SHA-256 `f61a7399d61a7f4502b9289ea6c49c661c39c459aae81e676722bcf9be913c01`；本次运行证据不能与其静态证据混同，也不能仅凭型号或版本差异认定所有接口不兼容。

完整报告保留此前 13:22 等历史记录；下面仅以本次 **14:01** 的启用会话定位仪表问题。原始报告、压缩日志、选取文件及可复核索引只保存在忽略目录 `agent_docs/instrument-navigation-20261027/`，不提交完整车机诊断、地图位置或设备隐私。

## 应用与车机日志对应

| 应用报告时间 | 事件 | 应用报告行号 | 系统日志行号 |
| --- | --- | --- | --- |
| 14:01:35.317 | 当前会话启用仪表实验功能 | 1670 | 96896 |
| 14:01:44.404–44.479 | 收到 5 条 iAP2 `0x5201` 路线消息 | 1786–1788、1792–1793 | 110221、110244、110267、110396、110476 |
| 14:01:45.650 | 收到 `0x5202` 转向消息 | 1794 | 110915 |
| 14:01:45.795 | `route-data received; supported-arrow=true` | 1795 | 111018 |
| 14:01:46.319 | `car-service connected; fixed byte-array property writable` | 1808 | 111633 |
| 14:01:46.320 | `nativeNavigation=unknown nativePhone=unknown` | 1809 | 111640 |
| 14:01:46.321 | `available=false; native-navigation/phone checks applied` | 1810 | 111647 |

两类日志时间来自不同记录位置，毫秒存在小差异，但事件和顺序一致。系统侧对应本次主应用进程 21685。

完整应用报告没有 `instrument navigation NAV-F3 submitted`；当前系统日志也未发现该进程的 `CarPropertyManager.setProperty`，或任意进程对目标 `762314830 / 0x2d70004e` 的 setProperty 记录。结合源码中明确的提前返回，可以确认本次观察到的导航信息没有从该输出路径提交。

## 为什么返回 unknown

`shared/src/main/java/com/shilapi/xcertplay/vehicle/PrefaceInstrumentBackend.kt` 的 `available()`：

- 读取 `sys.bicv.navi.status` 和 `sys.bicv.phone.status`；仅把 `false/0` 识别为空闲。
- 缺失值的默认字符串为 `unknown`；空串及其他值也归入 unknown。
- 两项均为空闲才允许输出。

当前 properties 快照中，**这两个 sys.bicv 状态均没有条目**。快照仍包含 `sys.ecarx.navsyncscreen.status=0`（第 9128 行）与 `sys.local.incall=false`（第 8659 行）。这佐证当前依赖的属性并未在这次快照中提供；不能由此断言它们在该固件的所有运行阶段永远不存在，也不能仅凭应用的 unknown 区分属性缺失、未初始化、不可读或非预期格式。

`PrefaceInstrumentGuidanceSession.tick()` 在 `backend.available()` 为 false 时直接返回，位于 `backend.publish(guidance)` 之前。状态未知时暂停输出是原车优先保护，但 **将来自 327 参考适配实现、尚未在当前车机确认可用的两个属性设成必需的运行条件，使 1208 在本次实车上无法输出**。问题在现有状态来源与判断的适用性，不是用户没有启用功能，也不是本次 iPhone 完全没有下发导航信息。

## 已证实与未证实

### 与原厂实现的关键差异

进一步逐项核对 327 缓存源码：`ECarXDimServiceImpl.java:572,582,598` 的原厂导航方法读取当前导航对象和投屏状态，电话属性使用 `getBoolean(..., false)`，随后由该方法**写出** `sys.bicv.navi.status`。该属性是这套 SDK 的导航输出状态记录，不是已经确认在所有运行阶段都存在的全局原厂导航占用接口；电话属性也由同类的电话信息更新流程在 `:665,668,677` 读写。

当前实验后端只复用原厂编码器和固定属性，新增了“两项属性都必须存在并明确为空闲”的发送门槛，未完整复现原厂 `NaviService → INaviInteraction → ECarXDimServiceImpl` 流程。它与上述原厂默认值和状态管理语义存在实际差异。因此不能称为“已经按照原厂完整规范实现”；更准确的定位是基于 327 静态证据实现的底层消息实验，当前运行结果已暴露出状态仲裁部分的设计缺陷。原厂缺失值默认 false 的代码事实也不意味着应立即删除未知保护或直接采用其默认值，仍须先确认当前车机和当前接入路径的状态来源。

### 验证结论

- 已证实：实验开关对当前会话生效；iPhone 下发了路线及转向消息；应用识别到可映射箭头；Car 服务连接及目标属性列表／类型／写入能力配置检查成功；原车状态检查阻止本次发送。
- 尚未证实：实际 `setProperty()` 写入权限是否足够、固件编码器调用结果、MCU 是否接受该消息，以及仪表实际能否显示。日志中的“property writable”只是属性配置检查通过，不能等同真实写入成功。
- 不能将其他进程的媒体中心拒绝、SELinux 日志或无关车辆属性读写当作本次仪表发送失败的证据。当前路径没有走到写入调用。
- `sys.local.incall=false` 和 `sys.ecarx.navsyncscreen.status=0` 不是已经核对完成的替代仲裁接口；后者描述投屏状态，不能直接当成原厂导航会话是否活跃。

## 后续修正方向

需确认当前车机真实可用的原厂导航会话和通话状态来源，再替换不适用的强依赖，保留原车优先、未知状态处理、会话身份和退出失效检查。不能把 unknown 直接当 false，也不通过写系统属性、自动扩权或伪造接口成功来放行。

之后仍应分阶段核对实际写入返回、MCU／仪表响应，不能只消除 available=false 就宣布显示已修复。本轮仅做日志分析和证据记录，没有修改应用行为，没有运行构建、签名、安装或新增测试。分析索引为 `agent_docs/instrument-navigation-20261027/sources.json`、`application-navigation.json`、`instrument-analysis.json`。

## 按用户后续要求修正源码

用户随后明确要求“按照原厂固件流程进行修复”。这一阶段修改源码，不覆盖上面的 1208 运行事实；本次没有构建或安装新的 APK。

### 原厂证据与接入范围

复用同一 OTA 的缓存证据：

| 本地证据（相对 `agent_docs/firmware/ihu519g-327/`） | 用途 |
| --- | --- |
| `decompiled/naviservice/sources/ecarx/naviservice/a/a.java:132,136,317,500,541,610` | 原厂管理器创建 DimInteraction、观察连接、取得 INaviInteraction，通知开始／停止并提交 INavigationInfo |
| `decompiled/adapter/sources/com/ecarx/xui/adaptapi/dim/impl/DimInteractionImpl.java:35,45,53,75` | 连接／断开使用同一 VehicleSignalManager，取得进程内导航实现 |
| `decompiled/adapter/sources/com/ecarx/xui/adaptapi/dim/impl/nav/NaviInteractionImpl.java:65,270,325` | 导航实现为进程单例，更新及状态由本机 SDK 完成 |
| `decompiled/adapter/sources/com/ecarx/xui/adaptapi/dim/impl/ECarXDimServiceImpl.java:223,275` | 原厂停止在主队列执行即时、200 ms、500 ms 的清理 |

前三份文件的 SHA-256 分别为 `3308c3092469c73b94907cac6ce5d52281f4d8a1bc8049c8f3089e5aa66f731d`、`b8bdbaa5287e4394465dade738462dff50a1764fec1b0b9fbfe2102ba6e8d075`、`f6f75d353ced5847fc033c9cb1cf1d89f1f8de64f00b70ebf02fd13af53e927f`。原始实现和日志仅留忽略目录。

修正的是**原厂导航管理器所用的仪表 SDK 接入流程**，不是将 iPhone 导航伪装成原车高德，也没有把外部数据注入 NaviService 自身的地图／路线管理。本机安装的 SDK 负责其内部仪表、电话和输出状态处理；不再由应用复写原厂编码及车辆属性发送流程。

### 当前流程

1. 仅在当前 CarPlay 会话有可识别导航时，调用 `DimInteraction.create(context)`，注册 `IConnectable.IConnectWatcher` 并连接。必须收到真实 `onConnected()` 才取得 `getNaviInteraction()`；没有车型、固件号或 CPU 门槛。
2. 检查本机 `INavigationInfo` 接口的方法、参数和返回类型。通过该接口对象传递实际转向编号、距离、道路名和路线剩余量，先通知 `notifyTurnByTurnStarted()`，再调用 `updateNavigationInfo(info)`；不再查询两项 sys.bicv 属性，也不调用编码器或 `CarPropertyManager.setProperty()`。
3. 取消、到达、过期或退出时，提交非 null 的 UNNAVI（状态 0）信息，并调用 `notifyTurnByTurnStopped()`。没有被本应用成功开始的导航，不发送停止清除。保留会话执行前重查、SDK 异常后停止本次尝试及无底层写入回退。
4. 复用进程内原始 DimInteraction 实例，避免 SDK 的导航单例仍引用已被替换的旧 VehicleSignalManager。使用完释放连接观察和绑定；新会话交接时，在同一主队列为已知的原厂延迟清理留出 550 ms，再允许发布新提示，旧释放不能断开新会话正在复用的实例。
5. SDK 调用和连接回调在主队列排序；接收线程只提交工作，不直接执行原厂调用。主队列等待有界，退出清理保持排队，不随发布超时取消；连接未成功或方法／权限调用失败时明确记录，不伪造 ready 或成功结果。
6. 诊断改为 `native SDK update submitted; display-confirmed=false`。这一记录只证明 SDK 调用返回；SDK 内部仍可能因自身条件不输出，不能再称为 NAV F3 已经发到 MCU。道路名只作为真实 SDK 数据，不新增公开原始导航日志；SDK 自身可能记录字段，完整设备诊断仍须保留本地并在分享前脱敏。

### 原厂准备条件仍须区分

327 参考实现的 `updateNAVInfo()` 还会读取 `sys.ecarx.navsyncscreen.status`；是否输出活跃提示由 SDK 自身流程决定。此次不调用 `setPresentationToDimSwitch()`、不建立地图飞屏、不伪造首帧／准备状态，也不直接调用电源或系统属性写入。SDK 内部原厂状态维护和清理副作用由安装的 SDK 执行，不能将其描述成“内部绝无属性／电源操作”。若当前车机 SDK 同样要求准备条件，还需结合新包的原厂日志检查；本次源码修正不能证明这个后续条件已满足。

SDK 修复阶段保留了实验性标志、默认关闭、保存值优先及下次连接生效；用户随后要求调整界面和默认值，见下节。当前 1208 包仍是旧实现，真实 169 固件的 SDK 调用、权限、准备状态和仪表显示需要后续新包复测。

### 源码定向验证结果

39 项通过，无失败／错误／跳过：共享模块 26 项（原厂后端 13、导航解析与会话状态 11、控制器交接 2），设置模块定向 13 项（归类、实验性标题、1920×720 横屏下的默认关闭／下次连接、不打断会话、翻译及备份等）。后端测试替换的是原厂 SDK 接口，验证连接回调、start/update/stop、数据字段、SDK 复用、主队列回调、550 ms 停止交接、拒绝开始后的不误清、接口不匹配及失败释放；它们不是真实车机 SDK／仪表验证。

相关源码编译通过，公开树与差异检查通过；未运行全量单元测试、Lint 或完整 CI，未执行 assemble、签名或安装。命令、结果及本地汇总位于 `agent_docs/instrument-sdk-repair-20261027/`，其中 `validation-summary.json` 为最终分组结果。没有修改签名、组件权限、发布版本或 1208 的历史交付记录。

## 后续界面与默认值要求

用户在同日提供界面截图，明确要求移除“实验性”、把该选项放在导航页第一项，并随后要求默认开启。当前源码使用独立 `INSTRUMENT_NAVIGATION` 设置卡片，映射到导航分类，位于“位置”之前；高级页移除原开关，标题改为“仪表导航提示”，导航页说明覆盖仪表提示与位置共享。

偏好键继续使用 `diplay_instrument_navigation/enabled`；缺失值默认 true，已保存的 false 和 true 都保留，不执行迁移或强制写入。设置变更继续标记需要重新连接，不打断当前会话；原厂 SDK 入口、权限和准备状态处理不因界面调整而变化。默认值和标题调整不代表已完成实车显示验证，既有 1208 APK 不受源码修改影响。本轮未构建 APK。

更新后的 39 项定向验证全部通过（shared 26、common 13），覆盖缺失值默认开启且不主动写偏好、已保存关闭值保留、导航页首项及高级页无重复、无实验性标题、保存后仅标记下次连接且不中断会话，并保留 SDK 回归验证。UI 用例固定 1920×720 横屏、160 dpi；本地命令与结果位于 `agent_docs/instrument-navigation-ui-20261027/`。未运行完整 CI，也未构建、签名或安装 APK。
