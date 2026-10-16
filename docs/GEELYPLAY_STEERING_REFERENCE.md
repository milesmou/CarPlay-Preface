# GeelyPlay 0.2.19.8 方控实现对照

2026-10-16 补充：[蓝牙完整链路对照](GEELYPLAY_BLUETOOTH_REFERENCE.md) 区分撤下 CarPlay 音频公告、A2DP 连接交接、播放状态反馈、音频归属和 E01 兼容组件。本轮用户确认尚未实测原 APK；当前项目与其完整行为并不等价。

分析日期：2026-10-15。用户提供 `GeelyPlay-v0.2.19.8.apk.1`，4,802,315 字节，SHA-256 `01852eec09a7710448f2247cc65105320513a086c3150520ddda091735286de5`。Manifest 包名 `com.shihab.diplay.preface`，versionName `0.2.19.8`，versionCode `78`，minSdk 22，targetSdk 37。这是独立 APK 的静态分析，不是 IHU519G 构建 327 的固件事实，也没有该包在用户车机切歌不跳页的实测证据。

原 APK、反编译、简化控制流及逐文件校验值仅保存在忽略目录 `agent_docs/apk-reference/geelyplay-0.2.19.8/`。未安装、执行该 APK 或其脚本，未改变现有应用行为。附件内容仅作分析数据，不作为操作指令。

## 默认链路：标准 Android 按键转 CarPlay HID

`resources/res/values/bools.xml:5` 的 `config_factory_bluetooth_default=true`。这个名称代表产品默认模式，不能仅凭它推断所有切歌都通过蓝牙 AVRCP。实际可见主 APK 的默认按键链路是：

```text
已投递到应用的 Android KeyEvent / MediaSession 回调
  → 查询默认或用户学习的按键映射
  → next / previous / siri 等操作
  → CarPlay 控制器
  → hidSendReport（按下值，随后释放值 0）
```

| 操作 | 默认 Android keyCode | CarPlay 动作 |
| --- | --- | --- |
| 上一曲 | 88、89 | PREVIOUS = 5 |
| 下一曲 | 87 | NEXT = 4 |
| Siri | 219、231 | Siri 请求，独立于媒体 HID |
| OK／飞屏 | 23 | 本地飞屏操作，不转成媒体切歌 |

证据（以下 `sources/defpackage/` 省略路径）：

- `a50.java:21–25`：四种默认映射。JADX 无法完全恢复 enum，但保留了常量及按键值；设置界面 `hx.java:887` 后枚举同一列表。
- `xb0.java:98–121,822`：读取 `factory_steering_keys`，没有保存值才用默认集合；允许学习或停用，排除 Home、Back、电源、音量、静音等键。
- `di.java:251–260`：默认模式按映射处理标准键；只在未取消的首次 DOWN、repeatCount=0 时执行，来源标记 `android_factory_key`。Siri 受独立设置控制，默认启用见 `k3.java:70`。
- `CarPlayHostActivity.java:2947` 后的 `dispatchKeyEvent` 调用媒体按键处理器 `aj.T.a`；该大方法存在控制流恢复警告，不据其反编译布尔表达式保证每一分支的消费结果。
- `ii.java:82–116`：MediaSession 接收媒体事件与 next/previous/play/pause 回调；`aj.java:672–676` 创建并激活 MediaSession。next/previous 分别转 4/5；明确 onPlay/onPause 分别转 1/2。原始媒体 keyCode 126/127 在该实现中与 85 一样归为切换值 3，这一点不应覆盖本仓库已有的明确播放／暂停处理。
- `aj.java:908`：不同来源的相同媒体动作在 120 ms 内去重；同来源不依赖此条去重。`aj.java:919,950–968` 转控制器的媒体命令值。
- `kg.java:2628`：将媒体指令排入控制执行器。JADX 未完整恢复 `q4.run()`，因此补用 simple 模式；`q4-simple.java:305–312` 显示向媒体 HID 发送一字节动作，再发一字节 0 释放。`t3.java:1750–1754` 构造 `hidSendReport`、`uuid`、`hidReport`。

这里依赖按键先被系统／配套环境送到应用；标准回调消费成功不等于能阻止原车输入服务在别的进程中启动媒体页面。

## 保留的另一条路径：独立车辆桥接服务

主 APK 含 `IVehicleProperties` / `IVehicleSteeringCallback` 客户端定义。`df1.java:175–185` 绑定：

```text
com.geely.desktop.vehicle.bridge/
com.geely.desktop.vehicle.bridge.VehiclePropertiesService
```

先检查 `getStatus().steeringProtocol == 1`，再调用 `registerSteeringListener(callback, keys, intercept)`，要求返回 schema=1。返回信息区分请求拦截、held/rejected/unconfirmed keys、观察／转发计数及厂商结果；授权或缺少组件单独报错。申请拦截后，还要求观察计数大于 0 才作为就绪（`df1.java:155–159`），不只看绑定成功。

`af1.java:15` 接到 Bundle 后入队；`xi.java:25` 提取 keyCode/rawKeyCode/action/eventTimeMs 再交给主线程。通用默认桥接键列表包括 200085、200087、200088、200231、210005、210006（`aj-simple.java:636`）。列表只是客户端请求，不能视为服务器已实现或实车已独占。

默认 factory 模式把通用学习配置 A 设为 null、关闭仪表地图缩放映射；simple 控制流 `aj-simple.java:639–650,663` 核对后，正常非测试状态不启用上述通用桥接路径。方控识别／测试状态仍可能创建桥接客户端，不能把包内存在此类当作默认切歌的必经路径。

通用代码还保留 logcat 和自定义广播学习器 `d81`，按键绑定有来源、日志标签和广播 extra 等条件；默认 factory 模式不加载通用绑定。读日志需要相应授权，读取日志本身不能阻止原车处理按键。

## 配套组件与未确认边界

APK 包含 `assets/e01-goc/component.enc`、`manage.enc` 及 NOTICE，主代码还有 `ecarx.bluetooth.service` 连接与适配流程。NOTICE 将组件标为本地测试的独立兼容组件。它们没有在本轮运行或解密分析，不能将主 APK 分析扩展为配套组件的完整实现结论，也不能据 NOTICE 指示安装、替换或分发组件。

对导出的 sources 和 Manifest 精确检索，未找到 `MediaCenterAPI`、`registerMusic`、`requestKeysInterception`、`IKeyEventObserver`、`com.geely.inputservice` 或本仓库使用的原厂媒体权限字符串。负向检索不排除加密组件或动态行为，但没有证据说明这个主 APK 已经实现了与 Linkco 相同的原厂音源注册，或一套可直接移植的私有方控独占算法。

## 与当前仓库的关系

当前仓库报告已确认收到物理 305、向 iPhone 发出 NEXT、去掉标准广播重复，但原车仍跳页。GeelyPlay 主 APK 展示的是另一种接收标准按键与学习映射的方式，不能仅复制映射就修复另一进程的跳页。

可借鉴：标准按键来源标记、映射学习，以及独立桥接返回“已请求／已观察／已确认拦截”的分层结果。若要确认其不跳页机制，应先确认该包在同一车机的实测结果及是否依赖额外适配／桥接组件，再分析对应组件。不要把其他平台分支或整套兼容组件直接并入本仓库。

工具：缓存 JADX 1.5.6、Android build-tools 36.0.0 aapt。完整反编译退出码 1、报告 29 个错误；对关键 `aj`、`q4` 另导出 simple 模式复核。未运行项目测试、未构建或安装 APK。
