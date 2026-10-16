# AGENTS.md

Instructions for coding agents that work on CarPlay-Preface.

## 适配范围与默认配置

1. 本仓库专门服务吉利星瑞 E02，所有配置默认针对星瑞，不适配其他车型。新增功能、默认值、界面说明及验证场景均以星瑞 E02 为目标，不新增或维护其他车型的专属适配分支、车型选择或兼容开关。
2. 星瑞配置直接默认启用，不以产品、型号、Android 版本或固件构建号识别作为启用门槛；媒体／导航默认流为 3／12，方控媒体广播默认接入。CarPlay 音频参考已验证 Linkco APK 的 AudioTrack／媒体中心流程：默认启用播放焦点，并由播放器与媒体方控共用；导航播报时仅将本应用音乐压至 30%，通话／Siri 实际发声时静音音乐，结束后恢复。用户已保存的自动路由、自定义配置、焦点关闭及媒体焦点禁用选项优先。实现与证据见 `docs/LINKCO_CARPLAY_AUDIO.md`。
3. 服务存在、权限、会话、焦点、通话及蓝牙音频检查仍需保留，它们是接口可用性和运行状态判断，不是车型适配门槛。通用 Android 能力用于实现星瑞功能，不据此扩展支持车型。
4. 同步上游时，只引入星瑞所需或可用于星瑞的通用改动，不引入其他车型专属适配。固件静态证据和实车验证结果分别记录，不将默认启用描述为所有星瑞固件均已验证。
5. 无线连接提供“临时热点”和“手动车机热点”二选一，默认使用 Android `LocalOnlyHotspot` 临时热点。手动模式选择后，应用尝试使用系统保存配置开启车机热点；用户保存匹配的 SSID 和密码。保留权限和接口检查，无法开启时提示原因及系统设置入口；不修改热点配置或自动关闭热点，存在 CarPlay 会话时不操作热点开关。临时热点使用公共 API 与系统分配的名称、密码、频段；手动断开 CarPlay 后保留本应用仍有效的预约，手动重连或重建连接前复核接口和地址，有效则复用原名称与密码，失效才重建。完全退出、重置临时热点或改用 USB／手动热点时仅释放本应用的预约，不关闭其他应用或系统持有的热点。预约仅在本进程内保留，进程结束后重新检查并申请。两种模式均保留权限、接口归属和运行状态检查，不猜测信道；不引入 Wi-Fi Direct 或现有 Wi-Fi／同一局域网模式，正式发布前不保留旧配置迁移代码。设置变更在下一次连接生效，不擅自中断正在运行的 CarPlay。
6. 用户已确认实际车机为 **2023 款星瑞、12.3 英寸中控屏、1920×720 像素、横屏**。应用“界面大小 → 星瑞 12.3 英寸”使用该屏幕的 160 dpi 应用逻辑密度基准（不是硬件物理 PPI，也不修改系统密度），并作为默认选项；界面大小仅保留星瑞 12.3 英寸、75%、100%、125%、150%，移除自动及 200% 模式，缺失或无效保存值使用星瑞选项，保留有效百分比选择与系统字体大小，详见 `docs/PREFACE_SCREEN_LAYOUT.md`。后续车机界面测试和截图固定使用 **1920×720 横屏**，不使用竖屏截图验证车机界面；高密度、大字体和窄可用窗口场景也必须保持横屏，在同一分辨率下通过密度、字体比例或可用窗口约束验证。1920×720 是像素分辨率，不是 dp 尺寸；控件仍使用 dp，并根据运行时密度、系统栏及分屏后的实际可用窗口适配。用户预览优先交付已安装 APK 的原始截图，记录设备、版本、密度、字体及界面大小；单元测试渲染图必须标明来源，不作为实际运行截图交付。可使用 `scripts/capture_ui_preview.py` 检查页面和参数后截图。

7. 按用户 2026-10-16 的最新要求，USB 恢复官方 DiPlay v0.2.20 的 **NCM＋本地 VPN/TUN** 方案（来源提交 `3c95c936859e8a9153f0040caf2337adddb9e3e4`），不依赖 root 或系统预先创建的 NCM 网卡。USB 启动时按需请求系统 VPN 授权，应用仅接管当前 iPhone 的 NCM 接口，TUN 只允许本应用且仅路由 `fe80::/64`，不连接远程 VPN 服务器、不回退为全设备 VPN。保留 USB 授权、活动配置、配对、认证和会话检查；授权拒绝／组件缺失、TUN 建立失败或桥接错误明确失败并停止自动重试，不伪造授权、不自动通过 ADB 修改 VPN app-op。无线继续使用普通 socket 服务，不申请 VPN 或创建 TUN。退出、取消、拔线及授权撤销释放本应用持有的 NCM/TUN/监听资源。旧系统网卡路径与失败后的独立原始帧测试不再接入 USB 主流程，避免与实时桥接争抢接口。设置中的“授权”保留手动系统设置／VPN 入口，顶部提示先安装原生设置；页面渲染、搜索及恢复时不调用 `VpnService.prepare()`，手动 VPN 按钮只授权，不启动连接。USB 启动及该次授权返回可以调用准备接口。详见 `docs/USB_VPN_RESTORE_0.2.20.md`、`docs/SYSTEM_AUTHORIZATION.md`。应用实现与实车验证分别记录，不将恢复上游方案描述为星瑞实车连接已修复。

8. CarPlay 协商像素严格由“隐藏导航栏”选项决定：勾选为 **1920×720**，不勾选为 **1760×720**。停用分辨率百分比及旧画布缩放，已有保存值不得改变这两档。图标文字物理尺寸调节保留；窗口测量只用于本地显示与触摸映射，不因临时窗口增长／缩小重建连接。导航栏选择引起的分辨率变更在下次连接生效，不擅自中断会话。

## 效果图与界面预览基准

1. 后续所有车机界面设计、效果图和预览统一以 **2023 款星瑞的 12.3 英寸中控屏、1920×720 像素、横屏**为基准，按该屏幕校准文字、按钮和间距。交付原始 1920×720 图片，不将其他尺寸的图片拉伸后作为适配预览。
2. 标准效果预览使用应用默认的 **“星瑞 12.3 英寸”**界面大小，并核对实际运行的密度、字体比例、系统栏和可用窗口。1920×720 是像素尺寸，不能直接当作 dp，也不能仅凭输出图片的分辨率就声称与安装后的效果一致。
3. 安装后效果优先使用当前 APK 在匹配条件下的原始运行截图，并记录版本和显示参数；设计稿或单元测试渲染图须明确标明来源，不作为实际安装效果交付。
4. 大字体、高密度、紧凑窗口和 RTL 作为补充验证，图片仍保持 1920×720 横屏，并注明与标准预览的参数差异。

## Checks

只有用户明确要求“完整测试”时，才运行以下 CI 全量检查。普通修改或“构建测试 APK”默认不运行全量单元测试、Lint 或所有模块检查；构建测试包时仅执行用户指定模块的构建任务（默认 `:mobile:assembleDebug`），并继续核对签名及按下文规则自动安装。不要把仅构建成功描述为完整检查通过。

When the user explicitly requests full testing, run the CI command from `.github/workflows/android.yml`:

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest \
  :mobile:lintDebug :home:lintDebug :maphost:lintDebug \
  :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug
```

Set `ANDROID_HOME` or `local.properties` if Gradle cannot find the SDK.

## 星瑞固件参考资料

1. 修改星瑞 E02 私有接口、方控、音频路由、连接或电源行为前，先读取 `docs/PREFACE_FIRMWARE_REFERENCE.md` 及 `docs/firmware/ihu519g-327.json`；方控接入细节见 `docs/PREFACE_FIRMWARE_327.md`。
2. 本地原始证据与可搜索索引位于被 Git 忽略的 `agent_docs/firmware/ihu519g-327/`，目录及再生成命令见参考文档。优先复用缓存，记录证据文件、行号或校验值；缓存缺失时说明并请求原固件，不把无法复核的接口写成已确认。
3. 静态声明与真车可用性分别记录。不要将构建 327 的静态证据描述为所有 E02 固件已验证，也不要把 SDK 中其他车型的分支、空实现、XML 注释模板或 exported 服务直接当作可用接口。
4. 新分析补充参考文档和事实索引，注明固件身份、来源 SHA-256、适用范围、证据和待验证项。完整固件、APK/JAR、反编译实现及包含设备隐私的诊断只留本地忽略目录。

## 版本与上游同步

1. 当前上游参考基线为 **DiPlay v0.2.20（选择性同步）**，具体提交与排除范围见 `docs/UPSTREAM_SYNC_0.2.20.md`；另选择性移植 **huangwits/DiPlay-Preface v0.2.20.1** 的蓝牙音频流程，来源与差异见 `docs/GEELYPLAY_BLUETOOTH_0.2.20.1.md`，USB/VPN 按用户后续要求从官方 v0.2.20 恢复，见 `docs/USB_VPN_RESTORE_0.2.20.md`；不引入 E01 专属功能。本仓库应用版本独立维护，以 `mobile/build.gradle.kts` 中的 `versionName` 为准；中文 `README.md` 记录应用版本和上游基线。
2. 后续同步上游改动时，必须更新版本记录：按实际同步到的上游发布版本更新中文 `README.md` 及本节的上游基线，并递增 `mobile/build.gradle.kts` 中的 `versionCode`。应用 `versionName` 遵循下一条的发布规则，不得直接覆盖为上游的应用版本号。
3. 用户指定应用版本时采用指定版本。当前开发版本为 **0.1.1**，在 GitHub 正式发布此版本前，所有开发修改与上游同步均保持 `versionName = "0.1.1"`，不自动递增补丁号。正式发布后，未指定下一版本时默认递增本仓库应用版本的补丁号。`versionCode` 不得回退，新升级构建必须大于上一升级构建，以支持覆盖安装；修改应用版本时同时更新中文 `README.md`。
4. 如果同步的是尚未发布或零散的上游提交，记录实际同步的提交 SHA 和同步范围，不虚构上游发布版本，也不将部分提交描述为完整同步某个新版本。

## 构建环境与设备安装

主应用默认打包 `runtime-assets/offline-mfi/identity.pk8` 和 `certificate.p7b`。这是用户明确要求导入的上游 v0.2.18 公共预览 APK 的实验性配件认证资料；来源和 SHA-256 见 `docs/runtime-authentication-provenance.json`。设置 `DIPLAY_AUTH_ASSETS_DIR` 可为本地构建替换认证目录，替换资料不得入库。

用户明确要求公开现有 APK 签名密钥及签名密码，允许 `signing/carplay-release.p12` 和 `signing/signing.json` 入库。默认发布构建复用这组签名材料，证书指纹不得改变。`scripts/check_public_tree.py` 仅允许以上四个固定路径及已确认内容；其他私钥、令牌、签名密钥和本地配置继续排除，不扩大白名单。公开签名材料不得打包成应用资产。

后续构建 CarPlay 测试 APK（`mobile`／`automotive` 的 Debug 变体，包括 `assembleStandaloneDebug`）也必须使用 `signing/` 中的现有密钥和密码，与 Release 使用同一证书；不要使用 Android 默认 Debug 密钥或另行生成测试密钥。除非用户明确指定其他签名，不设置 `ANDROID_KEYSTORE_*`／`ANDROID_KEY_*` 覆盖参数。构建后核对 APK 证书指纹；旧测试包若使用其他签名导致覆盖安装失败，报告错误，不卸载或清除数据。

### 测试 APK 命名

构建测试 APK 时，交付及安装文件统一命名为 `CarPlay-Preface-<versionName>-<versionCode>-debug.apk`，例如 `CarPlay-Preface-0.1.1-52-debug.apk`。版本名称和构建号必须使用本次 APK 的实际值，示例中的 `52` 不是固定值。保留 Gradle 原始产物及输出元数据，构建成功后在同一输出目录复制为该名称；核对签名后安装并交付命名后的文件。

### 环境准备与本地缓存

1. 构建前先读取 `agent_docs/build-environment.local.json`（如果存在），并检查记录的工具路径是否仍然可用；结合 `local.properties` 和当前环境变量复用已配置的环境。
2. 如果缺少 JDK、Android SDK/NDK、Gradle、构建工具或必需配置，立即向用户询问所缺环境的路径、安装包或获取方式。不要先长时间搜索、自动下载安装或擅自替换项目要求的版本。
3. 用户提供后，验证工具可用性及版本，并将路径、版本和必要的构建参数保存到 `agent_docs/build-environment.local.json`。安装包或工具如果位于临时目录，应缓存或解压到 `agent_docs/build-environment/` 或其他稳定的本地目录；已有可用的本地安装直接复用。后续任务优先读取并复用有效缓存，不重复询问已提供的信息；路径失效或版本不匹配时再询问。
4. 缓存保留在本机；`agent_docs/` 已被 Git 忽略。仅记录配置及文件路径，不将密码、令牌或私钥写入缓存。优先为当前构建进程设置环境变量，不修改整台机器的全局配置。

### 构建成功后自动安装

1. APK 构建成功后，使用已配置 SDK 的 `adb devices -l` 检查在线安卓设备，包括真机和模拟器。以 ADB 状态为 `device` 的设备为安装目标；`offline` 或 `unauthorized` 不算在线可安装设备。
2. 有在线设备时，直接安装本次构建成功的应用 APK，无需再次向用户请求安装确认。默认安装 `mobile` 主应用；用户指定其他模块、变体或目标设备时按用户要求执行。
3. 使用 `adb -s <serial> install -r <apk>` 安装，保留已有应用数据。只有一台在线设备时安装到该设备；多台在线设备且用户没有指定目标时，安装到所有在线设备，并分别报告结果。
4. 安装前确认对应 APK 构建任务成功，且 APK 是本次构建的产物（包括经构建任务确认仍有效的缓存产物）。单元测试或 Lint 失败必须单独报告，不能将完整检查描述为通过。
5. 没有在线设备时，报告 APK 路径和设备状态即可，不为安装而自行启动模拟器。安装失败时报告设备、错误及 APK 路径，不通过卸载应用或强制降级绕过错误；安装成功后报告设备和 APK 路径。

## Adding or moving a setting

The Settings screen is grouped by driver goal, not by implementation.
`SettingsCategory`, `SettingsSection` and `SettingsInformationArchitecture` at the top of
`common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt` define the groups.
`SettingsLayoutPolicyTest` fails when a section has no category or has more than one.

### Choose the category

Ask which goal the driver has when they look for the setting. Use the first row that fits.

| Category | Put a setting here when it controls… | Examples |
| --- | --- | --- |
| Authorization | manually granting system capabilities to this app | modify system settings, create VPN consent; connection readiness stays in Connection |
| Connection | how the iPhone connects and how DiPlay starts | connection setup, connect on open, start with the car, USB permissions, temporary hotspot, iPhone choice, Android permissions |
| Display | how CarPlay looks on the head-unit screen | day/night mode, picture, size, resolution, frame rate, dock, system bars, multi-window resolution |
| Audio | what the driver hears | media and navigation streams, music buffer |
| Navigation | location and turn-by-turn guidance | location to iPhone, navigation output for Xingrui |
| Vehicle | how CarPlay fits this car and its driver | driving side, Xingrui wheel keys (Siri and map zoom), car button, gestures that conflict with the head unit |
| Diagnostics | troubleshooting evidence | diagnostic reports |
| Advanced | experimental, firmware-specific or risky behavior | dashboard map, split screen, screen rotation, side panel, HEVC video, audio focus, audio channel mapping, buffered music, vehicle data |

A setting goes to Advanced when it is experimental, depends on specific firmware, or is an opt-in that can break sound, video or the connection on some head units.
A control that fixes a common problem and is safe at its default (for example the audio stream choice) stays in its category.
Exception: a gated control that only its own audience sees, and that completes an everyday goal, stays in that goal's category.
Example: an explicit boot-start permission grant belongs in Connection.

Mark an experimental setting or card with the title suffix ` (experimental)`, never `· experimental`, a dash, or an `Experimental …` prefix.
Each locale MUST use its existing form: `(تجريبي)`, `(experimental)`, `(экспериментально)`, `(експериментально)`, `（实验性）`, `（實驗性）`, `（実験的機能）`.

### Add the code

1. To extend an existing card, add the control inside the `filteredSection(...)` block of a card in the correct category.
2. For a new card, add a `SettingsSection` entry and map it to exactly one category in `SettingsInformationArchitecture.sectionsByCategory`.
3. Build the new card with `filteredSection(content, SettingsSection.X, …)` inside `allSettingsSections()`.
   A plain `section(...)` call there MUST NOT be used: it renders on every category page.
4. A setting MUST NOT appear in two categories.
   A header shortcut MAY duplicate a Display setting when it uses the same persistence.
5. If the change reconnects CarPlay or applies at the next connection, the description MUST say so.
   A setting that only takes effect at the next connection MUST call `markReconnectNeeded()` after it saves.
   This shows the "Reconnect now" bar. It MUST NOT drop a running session without the driver's consent.
   Reconnect at once (`reconnectIfRunning()`) only after a dialog button that says "Apply and reconnect", or when the description says that the change reconnects CarPlay.
6. Add each new string to `common/src/main/res/values/` and to every `values-xx` locale folder.
   New Settings copy SHOULD use the `settings_` prefix. `SettingsTranslationsTest` fails when a `settings_*` string has no translation.
7. A control that opens a choice uses `button()` with the text `"Title · Value"`.
   That form renders as a setting row with the value and a chevron, and Search indexes the title.
8. When you move a control between categories, update `AdaptiveSettingsUiTest.settingsLiveWhereDriversLookForThem`.

### Settings entry

This branch has no Overview menu or standalone connection setup page. Home lists currently connected Bluetooth phones; tapping a device starts wireless CarPlay. The Settings action is first in the upper-right action group. USB has no connect button: attaching an iPhone starts wired CarPlay and takes priority over wireless, including queued permission callbacks, Bluetooth wake and a running wireless session. A cable replug may retry a failed wired attempt; do not repeatedly launch from home polling after denial. Pairing and permissions appear when needed. Settings opens on Connection, which holds connection readiness and connection preferences. There is no first-launch setup guide or guide entry in Settings.
Every category is directly accessible through the wide-screen rail or compact horizontal category strip.
About has its own category. Do not reintroduce Overview, quick-setting duplicates, or a language picker.

## Settings layout

Spacing, size and shape in the Settings screen MUST come from one place.

1. A gap between blocks MUST use a named dp constant, such as `SETTINGS_BLOCK_GAP_DP`.
   A new literal gap value MUST NOT be added.
2. A size MUST NOT be a pixel constant (`*_PX`). Use dp, and clamp to the available window width.
   Test a menu or panel on a narrow window and on a high-density screen.
3. Controls in one row MUST have the same height.
   Corner radius MUST follow the control height. A new button MUST NOT set its own radius.
4. Every `SettingsCategory` MUST have a destination in the rail and compact category strip.
   `AdaptiveSettingsUiTest.settingsLinksToEveryCategory` checks this.
5. A layout change MUST include compact and full screenshots with a large font scale.
   Add an Arabic (right-to-left) screenshot when the change touches row alignment.
