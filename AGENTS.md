# AGENTS.md

Instructions for coding agents that work on DiPlay.

## Checks

Run the CI command from `.github/workflows/android.yml` before you report a change as done:

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest \
  :mobile:lintDebug :home:lintDebug :maphost:lintDebug \
  :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug
```

Set `ANDROID_HOME` or `local.properties` if Gradle cannot find the SDK.

## 星瑞固件参考资料

1. 修改星瑞 E02 私有接口、方控、音频路由、连接或电源行为前，先读取 `docs/PREFACE_FIRMWARE_REFERENCE.md` 及 `docs/firmware/ihu519g-327.json`；方控接入细节见 `docs/PREFACE_FIRMWARE_327.md`。
2. 本地原始证据与可搜索索引位于被 Git 忽略的 `agent_docs/firmware/ihu519g-327/`，目录及再生成命令见参考文档。优先复用缓存，记录证据文件、行号或校验值；缓存缺失时说明并请求原固件，不把无法复核的接口写成已确认。
3. 静态声明与真车可用性分别记录。不要将构建 327 的常量推广到所有 E02，也不要把 SDK 中其他车型的分支、空实现、XML 注释模板或 exported 服务直接当作可用接口。
4. 新分析补充参考文档和事实索引，注明固件身份、来源 SHA-256、适用范围、证据和待验证项。完整固件、APK/JAR、反编译实现及包含设备隐私的诊断只留本地忽略目录。

## 版本与上游同步

1. 当前上游基线为 **DiPlay v0.2.18**。本仓库应用版本独立维护，以 `mobile/build.gradle.kts` 中的 `versionName` 为准；中英文 README 分别记录应用版本和上游基线。
2. 后续同步上游改动时，必须更新版本记录：按实际同步到的上游发布版本更新中英文 README 及本节的上游基线，并递增 `mobile/build.gradle.kts` 中的应用 `versionName` 和 `versionCode`。同步时不得直接覆盖为上游的应用版本号。
3. 用户指定应用版本时采用指定版本；未指定时默认递增本仓库应用版本的补丁号（例如 `0.1.0` → `0.1.1`）。`versionCode` 必须大于上一版本，以支持覆盖升级；修改应用版本时同时更新中英文 README。
4. 如果同步的是尚未发布或零散的上游提交，记录实际同步的提交 SHA 和同步范围，不虚构上游发布版本，也不将部分提交描述为完整同步某个新版本。

## 构建环境与设备安装

主应用默认打包 `runtime-assets/offline-mfi/identity.pk8` 和 `certificate.p7b`。这是用户明确要求导入的上游 v0.2.18 公共预览 APK 的实验性配件认证资料；来源和 SHA-256 见 `docs/runtime-authentication-provenance.json`。仅允许这两个固定路径及已记录内容，其他私钥、令牌和 APK 签名密钥仍不得入库。`scripts/check_public_tree.py` 校验此例外；不要扩大凭证白名单。设置 `DIPLAY_AUTH_ASSETS_DIR` 可为本地构建替换认证目录，替换资料不得入库。

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
| Connection | how the iPhone connects and how DiPlay starts | connection setup, connect on open, start with the car, USB permissions, car hotspot automation, iPhone choice, Android permissions |
| Display | how CarPlay looks on the head-unit screen | day/night mode, picture, size, resolution, frame rate, dock, system bars, multi-window resolution |
| Audio | what the driver hears | media and navigation streams, music buffer |
| Navigation | location and turn-by-turn guidance | location to iPhone, BYD HUD and cluster guidance |
| Vehicle | how CarPlay fits this car and its driver | driving side, wheel keys (Siri, BYD joystick and map zoom), car button, gestures that conflict with the head unit |
| Diagnostics | troubleshooting evidence | diagnostic reports |
| Advanced | experimental, firmware-specific or risky behavior | dashboard map, split screen, screen rotation, side panel, HEVC video, audio focus, audio channel mapping, buffered music, vehicle data |

A setting goes to Advanced when it is experimental, depends on specific firmware, or is an opt-in that can break sound, video or the connection on some head units.
A control that fixes a common problem and is safe at its default (for example the audio stream choice) stays in its category.
Exception: a gated control that only its own audience sees, and that completes an everyday goal, stays in that goal's category.
Example: auto car hotspot needs ADB but appears only for car-hotspot users, so it is in Connection.

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

This branch has no Overview menu. Settings opens on Connection, which holds connection readiness and the setup guide.
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
