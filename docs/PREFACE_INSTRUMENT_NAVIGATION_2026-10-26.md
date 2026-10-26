# 高德导航仪表方向信息：固件静态调用链

分析日期：2026-10-26。范围仅为用户提供的 IHU519G / FS11A1、ECARX_E02_V1.3、Android 9、构建 327 固件，不代替实际车机运行验证。

## 结论

导航管理服务为 `ecarx.naviservice.service.NaviService`，包名 `ecarx.naviservice`，位于 `system/priv-app/XSFNaviService/XSFNaviService.apk`。其内部 `NaviDashboardManager`（混淆类 `ecarx.naviservice.a.a`）负责把导航信息交给仪表 SDK。

静态调用链：高德车机版 `com.autonavi.amapauto` → AutoMapSDKManager / Amap500Proxy → NaviService → NaviDashboardManager → `INaviInteraction.updateNavigationInfo()` → `NaviInteractionImpl` → `ECarXDimServiceImpl.updateNAVInfo()` → VehicleSignalManager / CarPropertyManager → MCU 仪表消息通道。

该路径传递转向编号、下一路口距离、道路名、剩余距离和时间。它支持的是结构化导航提示；不能将此证据描述成星瑞具有整幅地图飞屏能力。`ECarXDimServiceImpl` 是 SDK 内部实现类，不是另一个已确认可独立绑定的 Android Service。

## 来源和证据

- 原始 OTA SHA-256：`f61a7399d61a7f4502b9289ea6c49c661c39c459aae81e676722bcf9be913c01`。
- XSFNaviService.apk SHA-256（本次重新核对）：`95ad61714a158e1ee3e1eb829cbd2e11c7d1f0bd1f31e31756efe5ad2359d7d1`。
- 本地证据根目录：`agent_docs/firmware/ihu519g-327/`，不提交 APK、反编译实现。
- `catalog/manifests/XSFNaviService.txt`：声明上述服务，action 为 `ecarx.intent.action.NAVI_SERVICE_STARTED`，受 `ecarx.oem.permission.OPENAPI_NAVI_PERMISSION` 保护。
- `decompiled/naviservice/sources/ecarx/naviservice/map/amap/a.java:12,55`：高德包名和 `AutoMapSDKManager.get()`。
- `decompiled/naviservice/sources/ecarx/naviservice/service/NaviService.java:119,423`：创建 dashboard manager 并向其转发地图事件。
- `decompiled/naviservice/sources/ecarx/naviservice/a/a.java:148,610,734`：处理导航事件，生成导航信息并调用 `updateNavigationInfo()`。
- `decompiled/naviservice/sources/ecarx/naviservice/a/o.java:16,69`：实现 `INavigationInfo`，读取转向编号、路口距离、路名、剩余时间和距离。
- `decompiled/adapter/sources/com/ecarx/xui/adaptapi/dim/impl/nav/NaviInteractionImpl.java:270`：整合导航状态与信息更新，调用 `dimService.updateNAVInfo()`。
- `decompiled/adapter/sources/com/ecarx/xui/adaptapi/dim/impl/ECarXDimServiceImpl.java:572,600,816`：转换转向编号为 IPK 编码，打包 NAV F3，最终通过属性 `762314830` 发送字节数组。
- `decompiled/adapter/sources/com/ecarx/xui/adaptapi/car/impl/vehicle/VehicleSignalManager.java:274`：实际调用 `CarPropertyManager.setProperty(byte[].class, propId, zone, data)`。

## 必须保留的边界

1. `updateTurnByTurnArrow(Bitmap)` 和 `updateTurnByTurnArrow(String)` 在当前适配实现中为空；整数重载有实现，但经另一组 HUD 属性输出。不能把任意箭头图片接口当成可用的仪表接口，也不能把 HUD 路径当成本车已验证能力。
2. 原厂 `updateNAVInfo()` 的状态标记涉及 `sys.ecarx.navsyncscreen.status`，另有电话状态和导航状态判断。仅找到方法不能证明第三方应用直接调用即可显示；静态分析阶段未修改系统属性或向车辆写数据。
3. Manifest exported 不代表第三方准入。服务权限、SDK 连接、车辆属性权限及当前固件的实际仪表响应仍需实车确认。
4. 新反编译使用本地 JADX 1.5.6，整体报告 16 个反编译错误；上述关键方法已逐项读取，不能将整包反编译描述为全部成功。
5. 上述静态分析阶段只补充文档；用户随后要求添加实验性功能，源码实现及验证范围如下。仍未构建、签名、安装或进行实车验证。

## 实验性功能实现

用户随后明确要求增加仪表导航提示并暂时标为实验性。入口为“设置 → 高级 → 仪表导航提示（实验性）”，默认关闭，保存后标记需要重新连接，下次创建 CarPlay 控制器时读取选择，不中断当前会话。开关进入设置备份，备份不收集道路或导航历史。

本次实现使用固定 NAV F3 输出入口：当前控制器的 iAP2 `0x5201/0x5202` → 独立 `CarPlayRouteState` → `PrefaceInstrumentGuidance` → **当前固件安装的** `ECarXDIMUtil.convertNAVTurn2IPK/convertNAVF3` → `CarPropertyManager.setProperty(byte[].class, 762314830, 0, data)`。不绑定或冒充高德，不调用会夹带投屏状态／电源操作的 `NaviInteractionImpl`，不调用 `setPresentationToDimSwitch()`，不写系统属性，不请求飞屏或创建地图画面流。

与静态分析发现的原厂完整调用链相比，实验实现复用底层编码器和固定车辆属性，不复用上层导航管理服务。活跃标志来自当前 iPhone 的有效导航数据；系统仍真实检查车辆服务访问权限。主应用仅声明 `android.car.permission.CAR_VENDOR_EXTENSION`，没有自动授权、平台签名更换、授权组件扩权或失败后的其他写入通道。该权限声明不能证明已获授权，缺少权限或固定属性不可访问时停止本次输出并记录原因。

### 映射与显示范围

| 提示 | 固件转向编号 |
| --- | --- |
| 左转／右转 | 102／103 |
| 向左前方／向右前方 | 104／105 |
| 向左后方／向右后方 | 106／107 |
| 左侧掉头／直行 | 108／109 |

这些编号在 `ECarXDIMUtil.java:978` 的 IPK 映射中有明确基础分支，对应 IPK 2–9。右侧掉头、环岛及其他未确认类型隐藏提示并清除本应用之前的箭头，不采用固件对未知编号的默认直行回退。道路名限制为 31 个 UTF-16 单元且不截断代理对，距离／剩余时间限制在消息字段范围内；未下发的路线总距离和总时间没有补算，编码输入为 0，因此相应字段不保证完整。

### 接口、状态和生命周期

- 仅收到有效且可映射的导航信息后尝试连接 `android.car.Car`；检查真实连接、属性列表、写入能力及字节数组类型，连接等待上限 5 秒。API、权限、编码或发送错误停止当前会话尝试，下一次会话才重新尝试。
- 发送和清除前均只读核对 `sys.bicv.navi.status` 与 `sys.bicv.phone.status`，两项均明确为空闲才写入；原车导航、原车通话或状态未知时暂停输出并放弃对旧提示的清除权。不会为了接入而将未知状态改为 false。
- NAV F3 写入在单独工作线程进行，变化最多每 500 ms 一次，相同有效提示每 2 秒刷新。原车状态检查只是固件静态接口上的保守仲裁，尚未证明能覆盖所有原厂写入者。
- 导航取消／到达清除；连续空列表 3 秒或导航数据超过 30 秒未更新隐藏；CarPlay 断开、退出和会话替换清除本应用持有的提示。仪表状态不沿用中控卡片在无线短断时保留的旧路线。
- 所有控制器在同一串行输出线程交接，旧会话消息按会话身份失效；旧控制器的延迟退出不会清掉新控制器的提示。清除仍受实时接口和原车状态检查约束，服务断开时无法保证仪表收到清除。
- 诊断区分消息提交、服务／属性可用性、原车状态和失败类型；不把 `setProperty()` 返回描述为仪表实际显示成功，不记录道路名或原始导航数据。

补充本地证据：`ECarXDIMUtil.java:543,978`、`VehicleSignalManager.java:67,274`、`ECarXDimServiceImpl.java:33,42,582,598,665`，相对目录同上。前两份源文件 SHA-256 分别为 `9aed09a1bb73f1af1d7b53be29e378ff6f70d673fbf2e3282c22c52b7aaa6d40`、`aafb92cc23dcb8fab5dad8072d1b3078b29c9e59247bfb733c6e531b0b976c29`。编码器／属性能力的实际设备可用性，以及原车导航、通话仲裁、仪表响应和 iPhone 高德是否下发数据，均仍待实车验证。

### 定向验证

源码阶段定向运行 `PrefaceInstrumentGuidanceTest`、`PrefaceInstrumentBackendTest`、`PrefaceInstrumentNavigationTest` 共 20 项，覆盖基础映射、未知箭头、边界、取消／到达、过期、原车优先、权限失败、连接超时、断开及控制器交接；后端测试使用假 Car API／编码器，不是实车发送测试。另运行设置归类、实验性标题、默认关闭／下次连接且不中断会话、翻译与备份等 13 项验证。合计 33 项通过，无失败／错误／跳过；新增 UI 用例使用 1920×720 横屏、160 dpi 条件。本地汇总为 `agent_docs/instrument-navigation-20261026-validation.json`。该阶段未运行完整 CI，也未构建 APK。

### 测试包交付

用户随后要求打包。2026-10-26，`:mobile:assembleDebug` 成功，交付 0.2.2／1208 测试包。APK 已核对仪表输出实现及实验性标题，并核对主包原签名和内置授权组件 11；命名后的 APK 已覆盖安装到在线设备 `emulator-5558`，版本查询为 0.2.2／1208。此次未追加单元测试或完整 CI，没有实车仪表发送／显示验证；构建及安装结果见 [组件与构建核验](ANDROID9_AUTHORIZATION_COMPONENTS.md)。
