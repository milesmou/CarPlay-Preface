# CarPlay 返回键与手势菜单主页入口

## 从投影进入设置后直接返回投影（2026-10-21）

按用户要求，CarPlay 快捷菜单“全部设置”进入 `DiPlayActivity` 时携带返回来源。设置页的返回按钮与 AndroidX 系统返回回调共用 `navigateBack()`，有原会话且未取消时通过 `FLAG_ACTIVITY_REORDER_TO_FRONT` 恢复原 `CarPlayHostActivity`，不先展示应用主页，不附带 USB 接入／手动连接请求、不主动重连。待重连设置仍保留，须由用户明确操作生效。

来源同时支持 `onNewIntent` 和实例状态恢复；设置内备份等子页仍先返回设置，之后再回投影。重新从主页或其他入口打开设置会清除旧来源。从主页进入设置的返回行为不变；会话已结束或连接已取消时回主页，不为返回动作启动连接。CarPlay 页面自身的返回键拦截规则不变。

**17 项定向测试通过**：API 28 设置返回 7 项、既有启动入口 API 28／33 共 8 项、投影“全部设置”入口 2 项。覆盖系统返回和页面按钮、保存／重建来源、新 Intent、主页入口、无会话／取消、子页返回及保留会话／待重连设置。首次测试夹具未进入 STARTED 状态，生命周期感知的返回回调尚未启用，6 项失败；修正夹具后同组检查通过，初始输出保留在本机。记录在 `agent_docs/settings-projection-return-20261021/`。未运行完整 CI／Lint，也未构建、签名或安装 APK；尚未实车验证。

## 历史改动

2026-10-16，应用 0.1.1 / 71。用户要求连接成功后留在 CarPlay 页面，不通过返回键进入应用主界面；在三指下滑打开的菜单中提供明确的主页入口。

CarPlayHostActivity 在按键分发入口消费 KEYCODE_BACK 的按下／释放事件，并通过 AndroidX 返回回调消费系统返回事件。返回键不关闭 CarPlay 页面或手势菜单，不进入媒体／遥控转发路径。

菜单提供“返回主界面”按钮，点击后关闭菜单并打开 DiPlay 主界面，沿用后台会话保留方式，不停止控制器或主动重连。构建 71 沿用原菜单的保存／放弃确认流程；构建 72 将菜单精简为即时操作入口，不再提供需要保存后重连的编辑项，详见下文。

按钮使用现有 DiPlayButton 样式、共享 dp 高度及命名间距；菜单宽度继续约束到可用窗口。标题起始留白和 X 按钮边距使用相对方向。新增文案已提供当前维护的英文与简体中文资源。

9 项定向验证通过：API 28／33 的实际 Activity 返回回调与硬件返回键、保持会话返回主页、未保存修改确认、窄菜单宽度及翻译检查。截图全部为 **1920×720 横屏、字体 150%**，包括 mdpi 全窗口、xhdpi 紧凑窗口和 Arabic RTL（文案按现有资源回退英文），已视觉核对按钮可见且无截断。

- XML：`agent_docs/reviews/carplay-home-navigation-2026-10-16/`
- 截图：`agent_docs/screenshots/carplay-home-navigation/{full,compact,rtl}-font150.png`

`:mobile:assembleDebug` 成功，交付 APK：
`mobile/build/outputs/apk/debug/CarPlay-Preface-0.1.1-71-debug.apk`。
SHA-256：`5be64d0dd04ae7750717268e211e2ac2c23abfb2b4f677f980f7f5335b275708`。
APK 签名验证与原发布证书一致（SHA-256：`75f4eda0d13be105f578253381eadb5619c04548cc78f58faf2cc8f73a2660a8`），已使用 `install -r` 覆盖安装到唯一在线模拟器 `emulator-5554` 并确认版本 0.1.1 / 71。未运行全量 CI 或 Lint，实车返回键行为待复测。

## 快捷菜单精简（构建 72）

三指下滑菜单只保留“返回 CarPlay”“画面调整”“全部设置”“返回主界面”“退出应用”。认证、连接、热点、开机启动、位置、车辆标识、图标、驾驶侧、Dock、帧率、物理尺寸、安全区、系统栏、编解码和手指数等配置统一从完整设置页修改；菜单不再显示“保存并重新连接”或重复的实验性设置。设置页原有选项及生效时机保持不变。

画面调整入口打开现有的亮度、对比度、饱和度和色温面板，支持的纹理输出会立即保存并应用。使用兼容视频输出时仍保留原有不可调节说明。重新打开快捷菜单会关闭画面面板并结束临时原图对比，已保存的调节不回滚。普通菜单开关、画面调整和主页／设置导航不主动断开或重连会话；“退出应用”仍执行退出。

菜单按可用窗口宽度采用两列或单列操作区，按钮等高，标题与间距支持 RTL；高度不足时允许滚动。所有间距与尺寸使用命名 dp 常量和现有 DiPlayButton 样式。截图目录：`agent_docs/screenshots/carplay-quick-menu/`，均为 1920×720 横屏、字体 150%，包含全窗口、高密度紧凑窗口、窄可用窗口及 Arabic RTL。

48 项定向检查通过（CarPlayHostSettingsTest 47 项、SettingsTranslationsTest 1 项），包含入口精简、返回键拦截、连接保留、画面参数立即保存且不回滚、窗口适配及截图检查；XML 留在 `agent_docs/reviews/carplay-quick-menu-2026-10-16/`。四种截图已视觉核对按钮、文字、列间距与 RTL 方向。

`:mobile:assembleDebug` 成功，APK：`mobile/build/outputs/apk/debug/CarPlay-Preface-0.1.1-72-debug.apk`。SHA-256：`cf791b4e9b3f845e6c7fdb21a7a46049fb0ec3d7c05142ab4966266380351f64`。签名证书指纹与上文原发布证书一致，已 `install -r` 安装至在线模拟器 `emulator-5554` 并确认版本 0.1.1 / 72。未运行全量 CI 或 Lint，实车连接保留与即时画面调节仍需复测。

## 即时音量调节（构建 73）

快捷菜单直接增加“导航音量”和“媒体音量”滑动条，显示当前系统档位／最大档位。宽窗口将操作区和音量区并排，窄窗口上下排列并保留滚动。调节使用 AudioManager 系统流音量，不保存应用内增益、不重连、不播放预览音，不依赖可选的方控音量方案；菜单关闭不回滚音量。菜单显示期间沿用前台配置轮询刷新原车按键等外部音量变更，拖动期间避免轮询覆盖当前操作。

按用户补充要求收紧按钮尺寸：常规最小高度 48 dp、文字 16 sp、菜单最大宽度 920 dp；“返回 CarPlay”和“返回主界面”并排，“退出应用”与其他按钮同宽。大字体允许按钮随文字换行增长，同排等高。沿用应用现有 DiPlayButton 的普通界面配色、描边、涟漪、焦点与随高度变化的圆角；菜单、标题和滑条采用应用明暗主题的背景、文字及蓝色强调色。

有音轨时优先使用 CarPlayAudioOutputs 记录的实际通道，无音轨时读取当前 AndroidMediaSink 冻结的连接配置，避免使用已保存但尚未重连的设置；自动／未知回退沿用现有方控音量的媒体流回退。默认媒体／导航流为 3／12，依据已有 [构建 327 静态参考](PREFACE_FIRMWARE_REFERENCE.md#音频输出独立音量与麦克风)，不引入私有接口或新固件能力判断。拖动时重新检查通道、范围及通话／语音状态，通道变化时放弃旧手势写入。

共用同一通道时同步两条读数并解释共用系统音量。车机蓝牙模式的媒体条调节系统媒体流，独立导航条禁用并提示使用原车系统；通话／语音交互期间禁用两条。服务缺失、固定音量、通道不支持或读取失败时禁用对应条。系统拒绝或忽略写入时读回真实数值并显示失败提示，不能把滑条位置当作已经生效。没有实车音量调节验证结果。

大字体截图：`agent_docs/screenshots/carplay-quick-volume/{full,compact,narrow,rtl}-font150.png`，全部 1920×720 横屏、字体 150%；截图用模拟的可用 3／12 通道演示菜单布局，不作为设备支持流 12 的证据。
另提供 `standard-font100.png` 作为常规字体效果预览，仍为 1920×720 横屏。

定向验证 62 项通过（CarPlayHostSettingsTest 49、CarPlayMenuVolumeTest 12、SettingsTranslationsTest 1）；修正双列测量产生的多余高度后，再次复核 8 项截图／窗口布局测试，均通过，并重新视觉核对五张预览。XML 分别在 `agent_docs/reviews/carplay-quick-volume-2026-10-16/compact-style/` 和 `layout-final/`，未运行全量 CI 或 Lint。

最终 `:mobile:assembleDebug` 成功，交付 APK：`mobile/build/outputs/apk/debug/CarPlay-Preface-0.1.1-73-debug.apk`。SHA-256：`a4689b1169a82b785ac81910c77b09829f10274c6e27dade43715c8373e3e7f0`。签名指纹与原发布证书一致，已 `install -r` 覆盖安装至唯一在线模拟器 `emulator-5554`，确认版本 0.1.1 / 73；尚未在实车验证。

## 点击空白返回 CarPlay（构建 74）

按用户要求移除“返回 CarPlay”按钮，四个入口改为两行：“返回主界面／全部设置”和“画面调整／退出应用”。点击菜单外围空白、内侧留白、按钮间距或操作区与音量区之间的空白，关闭菜单并回到当前 CarPlay 画面，保持现有会话及音量。无障碍仍可通过菜单的返回动作关闭。

手势只在起点和终点都不属于按钮／滑条、移动未超过系统点击阈值时作为空白点击；拖动、滚动、多指或取消的触摸序列不关闭菜单。禁用的滑条仍算控件区域。关闭前取消菜单内尚未结束的触摸，关闭操作不向 CarPlay 转发触点。硬件及系统返回键继续保持原有不响应行为。

截图位于 `agent_docs/screenshots/carplay-blank-dismiss/`，均为 1920×720 横屏，包含常规字体预览，以及全窗口、紧凑窗口、窄可用窗口和 Arabic RTL 的 150% 字体预览。

CarPlayHostSettingsTest 的 53 项定向检查通过，包含真实触摸分发下的外围空白、按钮间距、菜单内边距及双区间距关闭、禁用滑条不误关、按钮导航、拖动／取消保护、连接保留和返回键拦截。五张截图已视觉核对；XML 留在 `agent_docs/reviews/carplay-blank-dismiss-2026-10-16/`。未运行全量 CI 或 Lint。

`:mobile:assembleDebug` 成功，APK：`mobile/build/outputs/apk/debug/CarPlay-Preface-0.1.1-74-debug.apk`；SHA-256：`a7b163dff58ede9afa9e7359fd8950247c14e685ac44d93220642bd342cb6701`。签名与原发布证书一致，已 `install -r` 覆盖安装到唯一在线模拟器 `emulator-5554` 并确认版本 0.1.1 / 74。实车触摸操作仍待复测。
