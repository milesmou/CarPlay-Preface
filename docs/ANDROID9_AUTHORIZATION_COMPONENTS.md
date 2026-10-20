# Android 9 授权组件

当前组件为 **preface-android9-11 / 11**，包名 `com.android.vpndialogs`，平台签名不变，仅适用于 API 28、匹配平台证书和系统身份的设备。主 APK 仅内置授权组件，独立媒体助手已移除。

组件 11 将蓝牙代理的调用方统一为采用仓库原证书的 `com.preface.carplay`，六项授权和 A2DP 会话租约边界保持不变。新包名需保存并手动安装／升级组件 11；仅升级主应用不会更新组件。旧组件 10 的交接实车证据见 [构建 100](CONNECTION_REPORT_2026-10-20_BUILD100_AUDIO.md)，不代表新包名已通过实车验收。

## 使用与升级

主 APK 已包含组件，无需另传第二个 APK。在 CarPlay“设置 → 授权”点击“保存授权 APK”，保存到公共 `Download` 根目录，再由用户通过文件管理器手动安装。授权组件导出流程不调用 APK 安装器、不申请安装来源权限、不静默安装或授权；主应用更新功能已有的安装权限与此流程独立。

“保存授权 APK”和“打开保存目录”始终同行、等宽等高。授权组件保存为固定文件名 `Preface-Android9-Authorization.apk`，文件名不带版本号。保存时先复制并校验临时文件，再覆盖同名旧文件，不追加编号；相同内容提示已保存，不重复写入或自动打开目录。目录按钮独立可用，优先系统下载入口，再尝试文件管理器；系统受理不等于目录实际显示。Android 9 缺少存储权限时仅请求导出所需权限。

组件 7 使用 `android.uid.system`。旧独立 UID 补丁不能直接覆盖升级：先保存新版，再**仅手动卸载本仓库旧授权补丁后重装**，保留 CarPlay 和数据。原厂／未知同名组件不覆盖、不卸载；平台不匹配时跳过导出。组件没有桌面图标，由 CarPlay 或标准 Settings Intent 提供入口。

## 功能与边界

| 功能 | 限制 |
| --- | --- |
| VPN 确认 | 任何符合条件的当前用户应用可发起系统 ConfirmDialog；目标只取 getCallingPackage()，须有已启用且受 BIND_VPN_SERVICE 保护的服务，允许非导出服务供系统显式绑定，保留用户限制及其他 always-on VPN 检查。没有代选应用／Intent extra 授权入口，仅授权，不创建 TUN；其他应用的实际 VPN 范围由其自身实现决定。 |
| 修改系统设置、悬浮窗 | 支持当前用户下已安装、启用、未暂停且声明对应权限的应用，不要求目标采用 CarPlay 签名；记录并复核 UID、版本和签名。标准 Settings Intent 无 package: 时选择应用，有 package: 时进入该应用的确认页；逐项允许／撤销、读回状态，保留系统策略检查。悬浮窗不再额外要求 WRITE_SETTINGS。CarPlay 仍优先原生页面，缺失时使用就绪补丁。 |
| 安装未知应用、使用情况访问、无障碍管理 | 仅补丁标准 Settings 入口提供；选择同用户下已安装、声明对应权限／服务且符合策略的应用，CarPlay 主授权页不增加这些项目。 |
| 蓝牙音乐连接及会话交接 | 仅供采用仓库原证书的 `com.preface.carplay` 使用；原连接操作只连接所选已配对 iPhone。组件 10 源码另提供仅限当前 CarPlay 会话的 A2DP 临时断开／恢复租约，以真实状态确认，只恢复自己请求断开的连接；保留当前用户、权限、通话、其他手机及取消检查。 |

六项授权均校验当前用户、目标 UID、签名、版本、启用状态及权限／服务声明；签名校验用于确认用户选中的身份未发生变化，不将其他应用限定为 CarPlay 签名。确认时重新检查，失败显示原因，拒绝遮挡触摸。组件 7 及更早版本的前三项仅接受两个固定签名 CarPlay 包，此限制从组件 8 源码起解除。组件本身仍要求匹配平台签名与系统 UID；其持有的系统权限不会转授给目标应用。不提供任意 app-op、批量授权、通知使用权或电池优化白名单。组件 11 的蓝牙代理仅接受 `com.preface.carplay` 和仓库原证书。组件 10 仅按用户新授权提供会话内 A2DP 临时断开与本租约恢复，不提供通用断开、配对、HFP/SCO、切音源或全局蓝牙设置。

其他应用使用入口：修改系统设置 `android.settings.action.MANAGE_WRITE_SETTINGS`，悬浮窗 `android.settings.action.MANAGE_OVERLAY_PERMISSION`，安装来源 `android.settings.MANAGE_UNKNOWN_APP_SOURCES`，使用情况 `android.settings.USAGE_ACCESS_SETTINGS`，无障碍 `android.settings.ACCESSIBILITY_SETTINGS`；可由应用或系统设置入口打开。组件没有桌面图标；VPN 应由目标应用调用 `VpnService.prepare()` 并通过 `startActivityForResult` 发起，不能从其他应用传包名代办。CarPlay 主授权页继续只管理自身权限。

组件 8 定向验证：`:authorization-components:testDebugUnitTest` 仅选择 9 个相关测试类，**70 项通过，0 失败／错误／跳过**。覆盖其他应用的权限声明、普通签名、UID／签名／版本变化、VPN 调用者身份、悬浮窗单独声明、撤销、取消和系统策略；包括 API28、1920×720 横屏、字体 150% 的完整 160dpi、紧凑 480dpi 和 RTL 160dpi 测试渲染，已查看应用选择及确认／滚动操作图。渲染位于 `agent_docs/screenshots/authorization-other-apps-test-renders/`，仅为 Robolectric 测试图，不是安装 APK 或实车截图。结果副本见 `agent_docs/authorization-other-apps-97/`。没有运行完整 CI，没有构建、签名或安装 APK；组件 8 授权效果仍待设备验证。

组件以 `getOpsForPackage()` 读状态、`setMode()` 写对应 app-op；保留 `GET_APP_OPS_STATS`、`WRITE_SECURE_SETTINGS`、`MANAGE_USERS` 及各功能已有权限，不再声明旧查询路径使用的 `UPDATE_APP_OPS_STATS`。不使用 root、shell、ADB 授权或隐藏 API 解锁。

`RECEIVE_BOOT_COMPLETED` 是主应用已声明的普通权限；悬浮窗辅助和厂商后台启动策略不等于标准开机广播权限。

## 签名与构建

参考固件为 IHU519G / FS11A1 / Android 9 / 构建 327，原始证据见 [固件索引](PREFACE_FIRMWARE_REFERENCE.md)。平台证书 SHA-256：

```text
c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8
```

签名输入固定来自 AOSP `platform_build` 提交 `57adea3c0d8722ec23a487122d85c853f7a93d44`（Android 9）。[来源说明](https://github.com/aosp-mirror/platform_build/blob/57adea3c0d8722ec23a487122d85c853f7a93d44/target/product/security/README)

| 输入 | SHA-256 |
| --- | --- |
| platform.pk8 | 1ad8ef556870edb70f69a9d3c112544c07de5162ba440d84d33f8bb0c5962875 |
| platform.x509.pem | 9837de028f460c35cc8d3fa45f14eecce30f6fbfe4b93d399aef1acb80c20d14 |

新环境显式准备一次，后续复用本地缓存：

```powershell
python scripts/android9_authorization.py --prepare-signing-inputs --fetch-public-aosp-key
./gradlew.bat :mobile:assembleDebug
```

主应用 Debug、Release 和 assembleStandaloneDebug 自动依赖组件 Release 构建、签名和生成 assets。必须包含 `assets/authorization/authorization.apk`、`manifest.json`，核对内置字节 SHA-256、包名、版本、平台证书及元数据，主应用仍用仓库原签名。失败不得交付缺少组件的 APK 或替换旧产物。

签名输入仅在 `agent_docs/android9-authorization/platform-test-key/`，不得入库或放入 APK。Gradle 保留原产物、输出元数据和 authorization-provenance.json。独立组件安装工具还检查实际设备 API、framework-res 平台证书、原厂／未知组件和 UID 迁移；不自动卸载、清数据、降级或修改权限。正常交付由文件管理器手动安装。

## 0.2.0 构建核验

`:mobile:assembleRelease` 成功，正式包为 `CarPlay-Preface-0.2.0-1202.apk`，包名 `com.preface.carplay`。主证书保持原值，内置组件 11 的包名、版本、平台证书、系统 UID 和 SHA-256 均已核对；包内无媒体助手资产或运行实现。已安装到在线模拟器 `emulator-5558`，没有安装独立组件或清除旧应用数据。包名与授权相关 114 项定向测试通过，未运行完整 CI；新包名的车机准入及实车功能仍待复核。

主 APK SHA-256：`4c06bf13ff22678bd5869d68b306f161041a589a0c4cc8047cea08a595253d04`。

组件 11 SHA-256：`09258b868ea339da1854c59e23172306f8fd90d2973e67250e7c2bad8168bab3`。

## 历史验证记录

2026-10-19 仅执行 `:mobile:assembleDebug`，没有追加单元测试、Lint 或完整 CI。主包已 adb install -r 安装到 API 34 模拟器 emulator-5558，版本查询为 0.1.1 / 95，保留数据；没有替换模拟器系统 VpnDialogs。

| 项目 | 核验值 |
| --- | --- |
| 主包 | CarPlay-Preface-0.1.1-95-debug.apk，25,564,785 字节 |
| 主包 SHA-256 | 5914be5caa434609514fbf5bf6a2198363a718e78493fce5e0992f407b5175cd |
| 主证书 SHA-256 | 75f4eda0d13be105f578253381eadb5619c04548cc78f58faf2cc8f73a2660a8 |
| 内置组件 SHA-256 | 52b660b8a9e135c8345d0e14fdcb66dfec1dfd6dc14a0493936eeae769c267c8 |
| 组件身份 | com.android.vpndialogs / preface-android9-7 / 7，android.uid.system |

完整核验记录为 `agent_docs/build-95/verification.json`。源码阶段的组件定向测试和大字体／紧凑／RTL 渲染不等于车机运行验证。

用户在构建 92 交付后确认“授权功能已经正常了”，记为用户实车反馈；未逐项说明所有授权，不能外推为 USB/TUN、方控或蓝牙已修复。原生设置对照见 [授权参考](NATIVE_SETTINGS_AUTHORIZATION_REFERENCE_2026-10-19.md)，蓝牙和方控见 [后续修正](BLUETOOTH_WHEEL_RECOVERY_2026-10-19.md)。


## 2026-10-20 构建 97 交付核验

仅执行 `:mobile:assembleDebug --offline --console=plain`，自动依赖组件 Release 构建、独立签名和生成 assets；没有追加单元测试、Lint 或完整 CI。

| 项目 | 结果 |
| --- | --- |
| 主包 | `CarPlay-Preface-0.1.1-97-debug.apk，25,579,210 字节` |
| 包名 | `com.shihab.diplay.hudtest` |
| 主包 SHA-256 | `6539c43c328716550d9abc2a682856b40c0c0aff54cbfadabe878ca12f3475eb` |
| 主包证书 SHA-256 | `75f4eda0d13be105f578253381eadb5619c04548cc78f58faf2cc8f73a2660a8` |
| 内置组件 | `com.android.vpndialogs，preface-android9-8 / 8` |
| 内置组件 SHA-256 | `a31300f258cb1712053fb540d19e9d1cb59f8326465857f15b3e51fc0e5331ed` |
| 组件平台证书 SHA-256 | `c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8` |
| 共享 UID | `android.uid.system` |

主 APK 含 `assets/authorization/authorization.apk` 与 `manifest.json`；内置字节与本次生成组件完全一致，包名、版本、签名及 SHA-256 与元数据匹配。未将公开主签名材料或平台签名输入放入 APK assets。

构建后检测到 `emulator-5558`（API 34），使用命名后的 APK 执行 `adb install -r` 成功，保留数据；安装后包信息为 0.1.1 / 97。未安装内置 Android 9 组件，未授权或启动 VPN，无星瑞实车连接验证。产物及本地核验记录位于 `mobile/build/outputs/apk/debug/` 和 `agent_docs/build-97/`。

## 2026-10-20 构建 98 交付核验

仅执行 `:mobile:assembleDebug --console=plain`，自动完成组件 9 Release 构建、独立签名和生成 assets；组件自身 vital 检查随任务执行，未追加主应用全量 Lint、单元测试或完整 CI。

| 项目 | 结果 |
| --- | --- |
| 主包 | `CarPlay-Preface-0.1.1-98-debug.apk` |
| 字节数 | `25580730` |
| 主包 SHA-256 | `86d99867015b383bc3e886278724c9f1fcd742653140e5f96d38c4dc994dc689` |
| 主包证书 SHA-256 | `75f4eda0d13be105f578253381eadb5619c04548cc78f58faf2cc8f73a2660a8` |
| 内置组件 | `com.android.vpndialogs / preface-android9-9 / 9` |
| 内置组件 SHA-256 | `b031d848a049af8068190cef1e953e7190d2774192128dafced2445f0fa74434` |
| 组件证书 SHA-256 | `c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8` |
| 共享 UID | `android.uid.system` |
| QQ 媒体授权 SHA-256 | `4290f880caa28d5535897c9a365c13a7394b93ae06ca9bec919918b90b0dbd98` |

APK 内的授权组件与本次签名产物字节一致，manifest.json 的哈希、版本、包名、共享 UID 和平台证书一致；QQ 授权文件及最终 manifest 参数一致。主签名／平台签名输入未放入 assets，只有原先已批准的 MFi 密钥及证书容器。

对唯一在线设备 emulator-5558 执行命名 APK 的 `adb install -r` 成功，安装后版本确认 0.1.1 / 98，保留原数据。未单独安装组件 9、未操作 VPN 授权。实车测试 VPN 修正前，仍需在主应用保存组件 9，由用户手动覆盖本仓库旧组件；只更新主应用不会替换车机上的组件代码。

本机完整记录：`agent_docs/build98-20261020/verification.json`、`assemble-debug.log`、`install-results.json`。这次构建与模拟器安装不证明 QQ 授权已获车机准入、蓝牙持续播放或切歌不跳页。

## 2026-10-20 构建 99 交付核验

按用户要求仅执行 `:mobile:assembleDebug --console=plain`，成功构建 0.1.1 / 99，包含通话结束后的通信模式释放及 Siri 方控接入修正。此前源码阶段的 43 项音频测试和 82 项 Siri／媒体链路测试已通过；此次打包未追加单元测试、主应用全量 Lint 或完整 CI，组件依赖的 vital 检查随构建执行。

| 项目 | 结果 |
| --- | --- |
| 主包 | `CarPlay-Preface-0.1.1-99-debug.apk` |
| 字节数 | `25597114` |
| 主包 SHA-256 | `a622d32957e1f0b61925a491ceb99cb3fde60e0c29994752ebc1d359ec18883b` |
| 主包证书 SHA-256 | `75f4eda0d13be105f578253381eadb5619c04548cc78f58faf2cc8f73a2660a8` |
| 内置组件 | `com.android.vpndialogs / preface-android9-9 / 9` |
| 内置组件 SHA-256 | `b031d848a049af8068190cef1e953e7190d2774192128dafced2445f0fa74434` |
| 组件证书 SHA-256 | `c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8` |

`assets/authorization/authorization.apk` 和 `manifest.json` 完整，内置字节与当前构建任务确认有效的组件签名产物和生成 assets 相同；包名、版本、平台证书、共享 UID、权限及非调试属性均已核验。QQ 媒体授权哈希和最终 manifest 两项参数保持原输入；只有原已批准的 MFi 密钥／证书容器进入 assets，未包含主包或组件签名输入。DEX 中确认包含通信模式释放、Siri 语音接收器及阻断诊断标记。

构建后检查到唯一在线设备 `emulator-5558`（API 34），使用上述命名 APK 执行 `adb install -r` 返回 Success，安装后版本确认 0.1.1 / 99，保留数据。未单独安装授权组件，也未操作 VPN 授权或发起 CarPlay 连接。音量、Siri 方控及蓝牙实车效果待用户验证。

原 Gradle APK 与输出元数据保留。完整构建、核验和安装记录在本机忽略目录 `agent_docs/build99-20261020/`。

## 2026-10-20 构建 100 交付核验

按用户要求执行 `:mobile:assembleDebug --console=plain` 成功，依赖同时完成授权组件 10、媒体助手 1 的 Release 构建、平台签名和生成资产；组件 vital 检查随构建执行。此次打包未追加单元测试、主应用全量 Lint 或完整 CI。此前源码阶段分别完成音频链路、固定导出文件名及快捷菜单布局的定向验证，不能与本次打包核验合称完整检查。

| 项目 | 结果 |
| --- | --- |
| 主包 | `CarPlay-Preface-0.1.1-100-debug.apk`，26,357,148 字节 |
| 包名 | `com.shihab.diplay.hudtest` |
| 主包 SHA-256 | `74145a6e3d26640e41aa3f1788fd0a63773a5f9c3c28ceef5f2a7455ef8fa436` |
| 主包证书 SHA-256 | `75f4eda0d13be105f578253381eadb5619c04548cc78f58faf2cc8f73a2660a8` |
| 内置授权组件 | `com.android.vpndialogs / preface-android9-10 / 10`，共享 UID 为 `android.uid.system` |
| 授权组件 SHA-256 | `6d646944d2c61be6860e35d39f9f450f167c53f6ab298b119d0a26f9f9a8796d` |
| 内置媒体助手 | `com.shilapi.xcertplay.mediaassistant / preface-media-assistant-1 / 1`，独立 UID |
| 媒体助手 SHA-256 | `0ee81f5bf10d7f87c566d00138dd2234881388cde0cf90664559aa7b566e6407` |
| 两组件平台证书 SHA-256 | `c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8` |

主 APK 内 `assets/authorization/`、`assets/media-assistant/` 均包含 APK 和 manifest.json；嵌入字节、元数据、生成 assets 与本次独立签名输出一致。两个组件的包名、版本、API 28、平台证书、UID 声明及非调试属性均已核验，均无桌面入口。助手 APK 没有厂商 SDK 类或授权资产；主 APK 的 QQ 授权文件及最终 manifest 参数与原输入一致，签名材料未打包，只有已批准的 MFi 密钥／证书容器进入 assets。

本包包含蓝牙会话交接、媒体助手、固定文件名覆盖保存及上方按钮／下方音量滑杆布局；滑轨 4 dp、按钮与滑杆触控高度 56 dp。构建后检测到唯一在线设备 `emulator-5558`（API 34），对命名后的主 APK 执行 `adb install -r` 成功，安装后确认 0.1.1 / 100，保留数据。没有单独安装两组件或操作授权。车机仍需用户导出后手动更新授权组件 10、安装媒体助手 1，并验证实际 SDK 准入、音源归属及 A2DP 恢复。

Gradle 原始 APK 与元数据保留，交付文件位于 `mobile/build/outputs/apk/debug/`。构建、签名、嵌入校验和安装完整记录位于本机忽略目录 `agent_docs/build100-20261020/`。
