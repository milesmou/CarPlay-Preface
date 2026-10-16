# 星瑞 Android 9 授权补充组件

## 当前交付：一个主 APK 引导补装（构建 86）

按用户后续确认，主应用 **0.1.1 / 86** 的 APK 已内置独立的平台签名组件。现在只需传输并安装 `CarPlay-Preface-0.1.1-86-debug.apk`，打开“设置 → 授权”；当系统为 API 28、平台证书匹配且没有原厂／未知同名组件时，显示“安装授权组件 · 打开安装器”。用户点击后校验内置文件并调起系统安装提示，确认安装后返回页面，分别操作“修改系统设置”和“创建 VPN”。系统可能要求允许本应用作为安装来源，由系统安装器处理。

两个应用在系统中仍独立安装和签名：主应用继续使用仓库现有证书，补充组件使用参考固件匹配的平台测试证书。内置的是签名 APK 和最小校验元数据，不包含平台签名密钥、绝对本机路径或安装器诊断。安装、取消、返回或页面渲染不会自动授予 CarPlay 权限，也不会创建 VPN/TUN 或中断会话。

运行时先检查包信息及已登记但对当前用户不可用的组件；原厂／未知组件保留，已安装本补充包时复核启用状态、四项实际权限和两个 Activity。未就绪时明确提示，不重复覆盖安装。可安装时在工作线程解包到应用私有缓存，校验 SHA-256、APK 包名／版本／API／非调试标志及平台证书，再次核对系统和现有包状态后，以只读 FileProvider URI 调起系统安装。安装后页面按实际包与权限状态刷新，不将 `startActivity()` 成功或安装返回当作授权成功。

已就绪时，修改系统设置入口明确打开补充包的对应 Activity，避免与原生 Settings 发生处理器选择冲突；其他系统保留标准 Settings Intent。VPN 仍使用系统返回的确认 Intent，不在页面渲染或返回时调用 `prepare()`。

主应用构建自动依赖本模块 Release 构建和本地签名步骤，生成 `mobile/build/generated/authorizationAssets/authorization/`。新环境先显式准备固定来源、固定校验值的公开签名输入（无需重新生成密钥）：

```powershell
python scripts/android9_authorization.py --prepare-signing-inputs --fetch-public-aosp-key
./gradlew.bat :mobile:assembleDebug
```

正常构建复用本地输入，不自动下载 JDK／SDK 或获取其他签名材料。CI 也在构建前显式准备这两份已核对的公开输入；签名脚本适配 Windows 和 Linux。所有签名输入继续留在忽略目录。

本次验证：**39 项主应用定向测试通过**（组件安装 9、授权页面 11、系统授权 18、翻译 1），Python 安装器原有 9 项定向测试通过；`:mobile:assembleDebug` 成功，主包与内置组件两层证书均核对，APK 内组件内容与独立签名 APK 完全一致。没有运行全量 CI。

最终主包 SHA-256：`0239a88196ca157d5b2e969e7584902d11de16fb8a0005f05ae8141a80906274`；主证书仍为 `75f4eda0d13be105f578253381eadb5619c04548cc78f58faf2cc8f73a2660a8`。已用 `adb install -r` 覆盖安装到在线 Android 14 / API 34 模拟器 `emulator-5558`，保留数据；原系统 VpnDialogs 未改动。没有在线匹配 Android 9 车机，本轮 **未验证实车补装、实际权限授予、VPN/TUN 或 USB 通信**。

原始运行截图及参数：`agent_docs/authorization-bundled-86/runtime.png` / `.json`，1920×720 横屏，系统 240 dpi、应用“星瑞 12.3 英寸”160 dpi、字体 100%，显示已有组件而跳过补装。安装按钮分支另以假状态定向渲染验证：`agent_docs/screenshots/authorization-test-renders/`，全幅与紧凑布局、150% 字体及 RTL，均为原始 1920×720 横屏测试图，不能作为已安装 APK／实车截图交付。构建与安装记录为 `agent_docs/authorization-bundled-86/build.json`。

以下为独立组件首次交付时的记录，关于“未构建主应用”的描述已被本节替代；独立 APK 和桌面安装方法仍可用于排障。

2026-10-16，用户要求按现有参考固件补装 VPN 授权组件，同时增加“修改系统设置”授权页。本组件独立于 CarPlay 主应用，包名 `com.android.vpndialogs`，版本 `preface-android9-1` / 1；主应用仍为 0.1.1 / 85。

## 已交付内容与安装状态

构建产物：`authorization-components/build/outputs/apk/release/Preface-Android9-Authorization-1.apk`。这是关闭调试的 Release 补充组件，不是 CarPlay 主应用 APK。

- 提供系统资源指定的 `com.android.vpndialogs.ConfirmDialog`，由 CarPlay 的 `VpnService.prepare()` 返回 Intent 后通过系统调用；确认后使用 Android 9 ConnectivityService 正式接口准备并授权该 CarPlay 包，再核对结果。
- 提供标准 `android.settings.action.MANAGE_WRITE_SETTINGS` / `package:` 授权页，显示当前状态，由用户点击“允许”或“关闭授权”后修改该 CarPlay 包的 WRITE_SETTINGS app-op，再读回核对；不修改热点配置或开关。
- 桌面入口“星瑞系统授权”可进入已安装 CarPlay 的修改系统设置页；VPN 确认必须由 CarPlay 发起。安装、打开桌面页及取消操作不授予 CarPlay 权限，不启动 VPN 服务或创建 TUN。
- 只接受 `com.shihab.diplay` / `com.shihab.diplay.hudtest`，要求已安装应用证书与仓库现有签名一致（SHA-256 `75f4eda0d13be105f578253381eadb5619c04548cc78f58faf2cc8f73a2660a8`），并检查当前用户、启用状态及相应权限／VPN 服务声明。VPN 目标取自 Android `getCallingPackage()`，不接受 Intent extra 指定的目标。
- 保留系统用户 VPN 限制与其他 always-on VPN 占用检查，确认时重新核对包 UID、签名、组件权限和策略；失败不会伪造授权成功。按钮拒绝被遮挡的触摸。

本次在线 ADB 设备只有 `emulator-5558`，是 Android 14 / API 34，已有系统 VpnDialogs。安装脚本按 API 不匹配跳过，**没有覆盖模拟器组件，也没有在 IHU353G 实车安装或验证**。安装包已签名并通过校验；实车 USB CarPlay 是否可用仍需后续验证。

## 固件与签名依据

参考 OTA 为 IHU519G / FS11A1 / Android 9 API 28 / 构建 327，来源 SHA-256 `f61a7399d61a7f4502b9289ea6c49c661c39c459aae81e676722bcf9be913c01`。完整背景和内置 TUN 驱动证据见 [VPN 固件分析](PREFACE_VPN_FIRMWARE_ANALYSIS_2026-10-16.md)。实车日志是 IHU353G，必须单独核对平台证书，不能由文件名或型号推定匹配。

参考 `framework-res.apk` 平台证书 DER SHA-256：

```text
c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8
```

本模块使用同一公开 AOSP 开发平台签名，**不更换主应用签名**。签名输入固定来自 AOSP platform_build 提交 `57adea3c0d8722ec23a487122d85c853f7a93d44`（android-9.0.0_r1）。签名文件仅缓存于本机忽略目录 `agent_docs/android9-authorization/platform-test-key/`，不入库、不打包为资产，不扩大公共树凭据白名单。[AOSP 测试签名来源说明](https://github.com/aosp-mirror/platform_build/blob/57adea3c0d8722ec23a487122d85c853f7a93d44/target/product/security/README)

原始输入 SHA-256：

```text
platform.pk8       1ad8ef556870edb70f69a9d3c112544c07de5162ba440d84d33f8bb0c5962875
platform.x509.pem  9837de028f460c35cc8d3fa45f14eecce30f6fbfe4b93d399aef1acb80c20d14
```

参考固件 manifest 中 `CONTROL_VPN` / `CONNECTIVITY_INTERNAL` 是 `0x12`，`MANAGE_APP_OPS_MODES` 是 `0x302`，`GET_APP_OPS_STATS` 是 `0x32`；均包含 signature 基础保护。运行时要求平台证书、组件自身证书匹配上述值，且该页所需权限确实授予；不匹配时只显示不可用。应用不反射解除隐藏 API 限制，不使用 root、shell 或 ADB app-op 授权命令。

在原始 `system/framework/boot-framework.vdex` 中核对了以下类定义及方法签名，缓存为 `catalog/authorization-component-classes.json`：

| 接口 | 构建 327 静态签名 |
| --- | --- |
| `IConnectivityManager.prepareVpn` | `(String, String, int) -> boolean` |
| `IConnectivityManager.setVpnPackageAuthorization` | `(String, int, boolean) -> void` |
| `IConnectivityManager.getAlwaysOnVpnPackage` | `(int) -> String` |
| `AppOpsManager.setMode` | `(int, int, String, int) -> void` |
| `UserHandle.myUserId` | `() -> int` |

这些是固件声明，不代表普通应用可访问。补充组件只在 API 28 且匹配平台签名／实际权限时使用这些接口，反射失败明确返回不可用。授权流程参考 [AOSP Android 9 VPN ConfirmDialog](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-9.0.0_r1/packages/VpnDialogs/src/com/android/vpndialogs/ConfirmDialog.java) 和 [WriteSettingsDetails](https://github.com/aosp-mirror/platform_packages_apps_settings/blob/android-9.0.0_r1/src/com/android/settings/applications/appinfo/WriteSettingsDetails.java)，以公共 Activity / View 和已核对的系统接口独立实现，不需要安装整套 Settings。

## 构建、签名与安装

先复用已配置 JDK / SDK，按模块构建：

```powershell
./gradlew.bat :authorization-components:testDebugUnitTest --tests '*AuthorizationConsentTest' --tests '*AuthorizationLayoutTest' :authorization-components:assembleRelease
python scripts/android9_authorization.py --sign --fetch-public-aosp-key --install-connected
```

Gradle 保留未签名原产物及 `output-metadata.json`；脚本读取本次元数据生成命名的签名 APK，并生成 `authorization-provenance.json`。目标为 Android 9 固件修复，单独忽略本模块 Google Play 的 `ExpiredTargetSdkVersion` 发布期限检查，其他自动随 Release 构建执行的 vital 检查保留。本模块不发布到 Google Play，也不改变主应用 targetSdk 或签名配置。

实车连接 ADB 后可使用已生成的 APK：

```powershell
python scripts/android9_authorization.py --apk authorization-components/build/outputs/apk/release/Preface-Android9-Authorization-1.apk --serial <实际车机序列号>
```

脚本只处理 ADB 状态为 `device` 的目标，检查 API 28、实车 framework-res 平台签名和补充 APK 签名；原厂或未知 VpnDialogs 已存在时拒绝覆盖，已登记但当前不可用的原包需先核对状态。仅本补充包的标记、版本格式和签名均匹配时允许后续 `install -r` 升级。不会卸载、清数据、强制降级或自动启用被停用的原厂组件。安装后读取四项权限实际授予状态，不通过 ADB 更改权限／app-op。原始设备检查结果仅保留本机忽略目录。

安装成功后，返回 CarPlay → 设置 → 授权，分别打开“修改系统设置”和“创建 VPN”，由用户确认。也可从补充包的桌面入口进入修改系统设置页。旧 CarPlay 提示“请先安装原生设置”属于先前文案；本补充包提供的这两项入口不依赖整套 Settings。若存在多个设置处理应用，选择“星瑞系统授权”。

此包只补齐所需的 CarPlay 授权入口，不提供完整 VPN 管理设置。系统原包存在时优先检查原包状态，不能将此补充包强行覆盖为整套系统 VpnDialogs。

## 验证范围

- 组件定向测试 20 项通过：显式同意／取消／返回、已授权、系统限制、其他调用方、接口失败、修改系统设置允许／关闭、页面重建，以及不匹配系统／签名拒绝。
- 安装器 9 项定向测试通过：错版本、错平台证书、原厂／未知原包、用户卸载状态、缺失权限、签名数量、APK 身份及调试标志，均不自动绕过。
- Release 构建及签名验证成功；公共树检查通过。未运行主应用全量 CI，未构建或安装新的 CarPlay APK。
- 布局测试图全部为 1920×720 横屏，标准 160 dpi 与紧凑 480 dpi，字体 150%，另含 Arabic locale / RTL 参数（文案回退中文）；高密度页可滚动到允许／取消按钮。图片位于 `agent_docs/screenshots/authorization-components-test-renders/`，**使用假后端的 Robolectric 原生绘制图，不是已安装 APK 或实车截图**。

APK SHA-256、签名输入来源和安装尝试结果以本次 `authorization-provenance.json` 及 `agent_docs/android9-authorization/install-results.json` 为准。授权成功后还需分别验证真实 TUN 建立、USB 桥接、拔插和音视频，当前不标记实车问题已修复。
