# 从公众 9.5 重建当前仪表测试版的改动审计

## 结论与范围

**利用目前已有的公众 APK、用户悬浮版素材和本仓库仪表源码，可以从公众版按固定差异重做；本轮尚未实施这条 APK 重建路线，也未验证其运行。** 不能把这个静态结论写成“公众版只加仪表代码就能正常启动”。

本次审计对象 `26000006` 实际以用户确认能运行的悬浮版为底包；它在模拟器正常显示地图，已接收高德引擎的实际箭头、距离和道路名。此前直接修改公众底包的 `26000003`／`26000004` 仍黑屏，未确定唯一原因。当前物理仪表副屏、回读、外屏准备和 CarPlay 接续仍待实车验证。

如果“直接从公众版”要求**完全不取用悬浮版的代码和素材**，则不能原样复现当前包：新增悬浮实现、签名代理及五份替换资源不在公众原包中，需要自行实现或提供相应素材。仅做仪表导航提示可以重放本仓库自有修改，但公众底包的重签／启动问题仍须单独解决。

本轮只读比较文件和 DEX，编译了主机侧审计工具；没有构建、签名、安装或执行新的 APK，没有更改已交付测试包、CarPlay 或车辆服务。

## 固定输入

| 输入 | 身份 | SHA-256 |
| --- | --- | --- |
| 公众版 | 用户提供 `Auto_9.5.0.600013_release_signed (1).apk`，构建号 26000001 | `f2855369c11b346756ff828fa453447c812563b0cda678fcc6a36326d385fa00` |
| 可运行悬浮版 | 用户提供 `高德地图_9.5悬浮锁12默认小团团 (1).apk`，构建号 26000001；与前次无 `(1)` 文件逐字节相同 | `094e296ef0783a76d3c2a82a2b91a5eb3198cef2338db78549295617f523ed92` |
| 当前测试版 | `AMap-9.5.0.600013-E02-native-hints-26000006-test.apk` | `3190527f8fbbc1b0369b0c09e4ba667546a7eec615e864812f46efdf112d26f8` |

三份包均为 `com.autonavi.amapauto`／`9.5.0.600013`。公众实际安装签名为 `b36c0b80cb2417e7fe4c3df01a2d163a7d8443604dd276a2bc1ab0d325102e3e`，另两份为 `c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8`。后者匹配缓存 327 参考证书，不是公众证书；进程内签名代理不能改变系统安装签名或直接解决不同证书的覆盖安装。

## 方法级比较结果

比较解析后的 DEX 指令、操作数／引用、寄存器、分支和异常区间，同时比较类继承、字段和方法声明。忽略调试行表，以及 DEX 静态字段中省略默认值与显式 false／0／null 的等价表示。没有把 JADX 的导入变化、资源符号替换或反编译排版当成补丁。工具没有归一化任意编译器等价指令，计数属于这三份固定输入。

| 对比 | 类数量 | 新增类（含内部类） | 原有同名方法体变化 | 移除方法 |
| --- | --- | --- | --- | --- |
| 公众 → 悬浮版 | 7,239 → 7,257 | 18 | 2 | 3 |
| 悬浮版 → 当前测试版 | 7,257 → 7,277 | 20 | 6 | 0 |
| 公众 → 当前测试版 | 7,239 → 7,277 | 38 | 8 | 3 |

当前的 6 处同名方法变化中，3 处是包装；它们的原方法体另以新名字保留，所以新增 3 个保存原体的方法。其余 3 处插入自有回调。现有打包工具另外验证了 45,215 个悬浮底包原方法体及异常范围的保留，详见 [测试记录](PREFACE_AMAP95_NATIVE_HINTS_TEST_2026-10-30.md)。

### A. 公众 → 悬浮版：4 个原有类的有效变化

| 改动点 | 已确认的实际变化 | 重做时的处理及限制 |
| --- | --- | --- |
| `com.autonavi.amapauto.app.MapApplicationProxy` 类继承 | 父类由 `android.app.Application` 改为 `bin.mt.signature.KillerApplication` | 属于底包已有签名代理；精确复现须处理该启动依赖，不能把它当作原厂签名密钥 |
| 同类 `onCreate()` | 增加 `floatapp.AutoamapPluginManage.load(this)` | 启动悬浮插件；不是本次仪表模块入口 |
| `kc.onPause()` | 整个覆写方法移除，回到继承的 Activity 行为 | 原体有暂停渲染、状态和原有生命周期通知；不能把移除说成仅改了 UI。对切后台、恢复和退出需重新测试 |
| `kc.onStop()` | 整个覆写方法移除 | 原体含停止通知、回调／触摸清理及状态处理。悬浮持续绘制与退出资源管理的取舍需单独验证 |
| `com.autonavi.auto.remote.fill.UsbFillActivity.onStop()` | 整个覆写方法移除 | 原体会关闭权限说明框，并在相应条件下结束入口 Activity；不是正常授权结果被自动放行 |
| `com.alibaba.wireless.security.framework.e.c(): HashMap` | 插件名称数组构造少了一个 `CONST_STRING`，对应公众原体的 `libsgmain` 字符串；另两项仍为 `libsgsecuritybody`、`libsgmiddletier` | 属于底包已有安全 SDK 装载改动。JADX 对改包报告未初始化寄存器，需按 DEX／ART 单独核对；未证明它是启动所必需，不应作为普通功能开关盲目照搬，也不能把它描述为在线签名／授权已修复 |

除这些变化外，45,137 个两包共有方法体完全一致。反编译文件原先显示的多处字段差异，经默认初值等价处理后没有额外运行值变化。

### B. 悬浮版已有的 18 个新增类

| 组别 | 数量 | 类／功能 |
| --- | --- | --- |
| 悬浮管理 | 7 | `floatapp` 的 `AMapViewManage`、`ApplicationInitialization`、`AutoamapPluginManage`、`ControlReceiver`、`FloatWindowsManage`、`LifecycleCallbacks`、`Utils` |
| 签名代理 | 2 | `bin.mt.signature.KillerApplication` 及其内部 Creator 类；进程内改变包信息中返回的证书 |
| 隐藏接口辅助 | 9 | `org.lsposed.hiddenapibypass.HiddenApiBypass` 及 8 个 `Helper` 内部类，供已有代理调用 |

悬浮管理注册 Activity 生命周期，保存主地图和 UI 的两个 SurfaceView，在移出主界面时可重挂到悬浮窗口，回前台时恢复。已有控制广播为 `com.autonavi.plus.showmap`、`com.autonavi.plus.closemap`；初始化还注册了 `com.autonavi.plus.offset`，但当前控制方法未发现对应处理分支。显示路径仍检查标准悬浮窗权限，没有把接收广播当作授权。

签名代理类还包含尝试加载 `SignatureKiller`、读取配套原 APK 资源和重定向路径的代码，但三份输入均无对应 `libSignatureKiller.so` 或 `assets/SignatureKiller/origin.apk`。因此这里只能记录“代码存在、配套条目缺失”，不能说该原生重定向已经运行。本次自有仪表代码没有添加这些机制；当前测试包继承了工作底包已有实现。

### C. 本次自有仪表及诊断修改：6 个入口

| 原有入口 | 当前修改 | 原有行为的保留方式 |
| --- | --- | --- |
| `oo(GuideInfoProtocolData)` 构造方法 | 原构造结束前复制真实导航字段 | 原构造逻辑保留；数据不回写给原接收者 |
| `AndroidProtocolExe.sendAutoStatus(int,int)` | 插入主屏首绘／已确认结束事件观察 | 原通知继续执行；主屏 116 不直接变成外屏准备 |
| `AndroidProtocolExe.dispatchAutoStatus(int,int)` | 同上，观察另一条实际分发入口 | 同上 |
| `a20.i(PresentationConfig)` | 对本次 `InstrumentPannelLarge`／1001 尝试自有副屏提示窗口 | 原体保存为 `preface95OriginalCreate`，其他调用转回原体 |
| `a20.v(int)` | 识别并停止本次窗口 | 原体保存为 `preface95OriginalStop`，非本次窗口转回原体 |
| `cz.l(String,String,Object[])` | 增加限量、固定模板及原始类型的启动诊断 | 原体保存为 `preface95OriginalLog`，继续执行原日志逻辑 |

新增 20 个 DEX 类来自五份自有 Java 源码及内部类：`Preface95FrameGate`、`Preface95Guidance`、`Preface95HintScreen`、`Preface95NativeHints`、`Preface95StartupTrace`。旧公众尝试中的 `Preface95PackageIdentity`／`Preface95CertificatePin` 未进入当前程序 DEX；当前未覆写 `Sign.checkSign`。

自有实现包括真实字段快照、原包箭头资源、实际物理 Display 1／窗口身份检查、绘制提交及 PixelCopy 回读、代次／有效期判断、准备与停止配对和退出释放。没有增加旧原厂 C++ 仪表库，没有直写车辆／系统属性、投屏、电源或 NAV F3。协议只有符合实际副屏和画面条件才发布；模拟器只有主屏时明确等待。

## 五份已有替换资源

以下是已交付 `26000006` 的历史对比。按用户后续要求，打包流程已取消其中语音文件的替换，改为恢复固定公众默认文件；已生成并安装 `26000007`，33 个语音初始化条目全部与公众原包一致，DEX 与审计对象相同；详见[最新交付记录](PREFACE_AMAP95_NATIVE_HINTS_TEST_2026-10-30.md)。其他四份资源保留原流程。

以下全部属于悬浮底包已有变化，本次仪表代码没有再修改它们。若要资源与当前包一致，需使用固定哈希素材；若只做仪表提示，应分别验证是否确有必要。

| APK 内路径 | 公众字节数 → 当前字节数 | 已确认范围 |
| --- | --- | --- |
| `assets/res/EngineRes/bl_data/DefaultTheme/libMapAsset.so` | 12,701,135 → 12,700,858 | 地图资源容器，文件虽以 `.so` 结尾但不是 ELF；内部语义差异未还原 |
| `assets/res/HmiRes/CarLogo_Style/2/carLogo.dat` | 169,398 → 3,428,096 | 车标资源数据已替换 |
| `assets/res/HmiRes/CarLogo_Style/2/carLogoLow.dat` | 157,728 → 3,350,291 | 低规格车标资源数据已替换 |
| `assets/res/HmiRes/FuncConfig.dat` | 26,432 → 26,464 | 二进制功能配置已替换；未将全部键值还原为可审核的小补丁 |
| `assets/res/HmiRes/voice_init/tts/voices/nvzhongyin` | 2,702,461 → 13,109,896 | 语音数据已替换；底包文件名标注“小团团”，本轮未做音色试听验收 |

每份资源的前／后 SHA-256 在本地机器可读报告 `public-rebuild-audit/verification.json` 的 `zipComparisons.public-to-current.nonSignatureChanged` 中。配置可能包含授权或渠道数据，不把原文写入公共记录，不因未知配置差异而声称改造了红绿灯服务。**用户随后澄清“锁12”指默认音频通道 12，并提供设置截图。** 已按[原厂音频对照](PREFACE_AMAP95_AUDIO_CHANNEL_2026-10-30.md)确认 327 导航流定义为 12，当前保留该值；原厂优先查询车机导航策略，通用回退配置的 11 不能直接当作星瑞有效值。具体二进制配置修改位置仍未还原。

## 打包层的确定变化

- 三份输入 `lib/` 下 63 个原生条目逐字节相同，无增删；资源表 `resources.arsc` 相同。
- 公众与悬浮版的 `AndroidManifest.xml` 逐字节相同。当前测试包只修改其中 versionCode 的 4 字节整数，`26000001 → 26000006`；包名、版本名、权限、服务及接收器声明没有额外变化。动态注册的悬浮广播属于新增 Java 行为，不能由 Manifest 相同推导为无新增 IPC。
- 相比公众版，非签名有效载荷只有 **7 个条目不同：5 个资源＋DEX＋Manifest**，没有非签名条目增删。ZIP 同路径条目共变化 8 项，额外一项为签名的 `META-INF/MANIFEST.MF`；另有签名文件增删，报告分别记录。
- 公众签名元数据的 `MINIMAP_.*` 与重签元数据名称不同；不靠 ZIP 内文件名判断证书，以 API 28 的实际签名验证结果为准。私钥不进入 APK 或本记录。

## 建议的重做顺序

1. 固定公众输入及证书，先形成可核对的最小启动／重签兼容方案；保留失败证据。已有“本地检查通过但黑屏”的尝试不能直接当作可用底座。
2. 将启动兼容、悬浮窗口、生命周期改变和安全 SDK 那一处异常分别验证，不能一次全部照搬后声称已找到黑屏根因。若只需星瑞仪表提示，悬浮 UI／车标／音色无需默认成为必选项。
3. 对确需保留的功能配置或资源，使用固定哈希导入；内部语义未还原的资源标明整体替换。现有资料足够做文件级复现，不等于所有二进制资源改动均已解释。
4. 在可正常启动的公众底座上复用本仓库六个自有入口和五份源码，重新核对 DEX 引用、原体保留、实际签名及增量构建号。
5. 取得重新构建授权后再打包／安装。先验证主地图、导航、后台恢复及退出，再在实车检查物理副屏、回读、原协议准备／停止及 CarPlay 接续；不以模拟器字段接入代替仪表显示。

## 复核命令及证据

```powershell
python scripts/analyze_amap95_public_rebuild.py
```

脚本固定三份输入 SHA-256，校验 ZIP CRC／重复条目，生成三向 ZIP 清单和方法级差异。只编译主机审计工具，不调用 Android 构建、签名、ADB 或 SDK 初始化。完整 APK／DEX、报告和反编译证据均仅留本机忽略目录。

工具源码为 `scripts/analyze_amap95_public_rebuild.py`、`amap95-instrument/tools/AuditApkDex.java`，指令指纹复用 `PrepareHintDex.body`。事实索引项为 `amap95PublicRebuildAudit20261030`；历史审计对象及最新语音恢复版的实际运行结果见 [测试记录](PREFACE_AMAP95_NATIVE_HINTS_TEST_2026-10-30.md) 为准。
