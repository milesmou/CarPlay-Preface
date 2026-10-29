# AGENTS.md

Instructions for coding agents that work on CarPlay-Preface.

## 适配范围与默认配置

1. 本仓库专门服务 2023 款吉利星瑞 1.5T（E02）车机，无飞屏功能，所有配置默认针对星瑞，不适配其他车型。新增功能、默认值、界面说明及验证场景均以星瑞 E02 为目标，不新增或维护其他车型的专属适配分支、车型选择或兼容开关。
2. 星瑞配置直接默认启用，不以产品、型号、Android 版本或固件构建号识别作为启用门槛；媒体／导航默认流为 3／12，方控媒体广播默认接入。CarPlay 音频参考已验证 Linkco APK 的 AudioTrack／媒体中心流程：默认启用播放焦点，并由播放器与媒体方控共用；导航播报时仅将本应用音乐压至 30%，通话／Siri 实际发声时静音音乐，结束后恢复。用户已保存的自动路由、自定义配置、焦点关闭及媒体焦点禁用选项优先。实现与证据见 `docs/BLUETOOTH_MUSIC_WHEEL.md`。
3. 服务存在、权限、会话、焦点、通话及蓝牙音频检查仍需保留，它们是接口可用性和运行状态判断，不是车型适配门槛。通用 Android 能力用于实现星瑞功能，不据此扩展支持车型。
4. 同步上游时，只引入星瑞所需或可用于星瑞的通用改动，不引入其他车型专属适配。固件静态证据和实车验证结果分别记录，不将默认启用描述为所有星瑞固件均已验证。
5. 无线连接提供“临时热点”和“手动车机热点”二选一，默认使用 Android `LocalOnlyHotspot` 临时热点。手动模式选择后，应用尝试使用系统保存配置开启车机热点；用户保存匹配的 SSID 和密码。保留权限和接口检查，无法开启时提示原因及系统设置入口；不修改热点配置或自动关闭热点，存在 CarPlay 会话时不操作热点开关。临时热点使用公共 API 与系统分配的名称、密码、频段；手动断开 CarPlay 后保留本应用仍有效的预约，手动重连或重建连接前复核接口和地址，有效则复用原名称与密码，失效才重建。完全退出、重置临时热点或改用 USB／手动热点时仅释放本应用的预约，不关闭其他应用或系统持有的热点。预约仅在本进程内保留，进程结束后重新检查并申请。两种模式均保留权限、接口归属和运行状态检查，不猜测信道；不引入 Wi-Fi Direct 或现有 Wi-Fi／同一局域网模式，正式发布前不保留旧配置迁移代码。设置变更在下一次连接生效，不擅自中断正在运行的 CarPlay。
6. 用户已确认实际车机为 **2023 款星瑞、12.3 英寸中控屏、1920×720 像素、横屏**。应用“界面大小 → 星瑞 12.3 英寸”使用该屏幕的 160 dpi 应用逻辑密度基准（不是硬件物理 PPI，也不修改系统密度），并作为默认选项；界面大小仅保留星瑞 12.3 英寸、75%、100%、125%、150%，移除自动及 200% 模式，缺失或无效保存值使用星瑞选项，保留有效百分比选择与系统字体大小，详见 `docs/PREFACE_SCREEN_LAYOUT.md`。后续车机界面测试和截图固定使用 **1920×720 横屏**，不使用竖屏截图验证车机界面；高密度、大字体和窄可用窗口场景也必须保持横屏，在同一分辨率下通过密度、字体比例或可用窗口约束验证。1920×720 是像素分辨率，不是 dp 尺寸；控件仍使用 dp，并根据运行时密度、系统栏及分屏后的实际可用窗口适配。用户预览优先交付已安装 APK 的原始截图，记录设备、版本、密度、字体及界面大小；单元测试渲染图必须标明来源，不作为实际运行截图交付。可使用 `scripts/capture_ui_preview.py` 检查页面和参数后截图。

7. 按用户 2026-10-16 的最新要求，USB 恢复官方 DiPlay v0.2.20 的 **NCM＋本地 VPN/TUN** 方案（来源提交 `3c95c936859e8a9153f0040caf2337adddb9e3e4`），不依赖 root 或系统预先创建的 NCM 网卡。USB 启动时按需请求系统 VPN 授权，应用仅接管当前 iPhone 的 NCM 接口，TUN 只允许本应用且仅路由 `fe80::/64`，不连接远程 VPN 服务器、不回退为全设备 VPN。保留 USB 授权、活动配置、配对、认证和会话检查；授权拒绝／组件缺失、TUN 建立失败或桥接错误明确失败并停止自动重试，不伪造授权、不自动通过 ADB 修改 VPN app-op。无线继续使用普通 socket 服务，不申请 VPN 或创建 TUN。退出、取消、拔线及授权撤销释放本应用持有的 NCM/TUN/监听资源。旧系统网卡路径与失败后的独立原始帧测试不再接入 USB 主流程，避免与实时桥接争抢接口。设置中的“授权”保留手动系统设置／VPN 入口，顶部说明主 APK 已内置授权组件，兼容时由用户点击保存到下载目录（本仓库组件已安装就绪时也允许再次保存），再经文件管理器手动安装；页面渲染、搜索及恢复时不调用 `VpnService.prepare()`，手动 VPN 按钮只授权，不启动连接。USB 启动及该次授权返回可以调用准备接口。详见 `docs/USB_VPN_RESTORE_0.2.20.md`、`docs/SYSTEM_AUTHORIZATION.md`。应用实现与实车验证分别记录，不将恢复上游方案描述为星瑞实车连接已修复。

8. CarPlay 协商像素严格由“隐藏导航栏”选项决定：勾选为 **1920×720**，不勾选为 **1760×720**。停用分辨率百分比及旧画布缩放，已有保存值不得改变这两档。图标文字物理尺寸调节保留；窗口测量只用于本地显示与触摸映射，不因临时窗口增长／缩小重建连接。导航栏选择引起的分辨率变更在下次连接生效，不擅自中断会话。

9. 按用户 2026-10-20 后续要求，接入星瑞电话键 299／事件 200005 的短按接听与挂断，使用当前控制器自己的 iPhone 通话状态，不依赖音乐焦点、媒体中心准入或无障碍服务。只有明确没有原车 HFP 链路时发送 CarPlay Telephony HID；HFP 存在、转换中或查询未知时保留原车，不能普通广播和 HFP 双发，不修改 HFP／SCO、配对或蓝牙开关。普通 RCALL 广播入口要求发送方持有 INJECT_EVENTS；不为主应用申请该权限。保留单路通话、当前会话、窗口／广播去重、执行前重查及退出失效检查；无通话、未知或多路通话不猜测目标，长按不擅自改为拒接。实现与94项定向验证见 `docs/PREFACE_PHONE_WHEEL_2026-10-20.md`；实车验证尚未完成，不能宣称阻止原车电话跳页。

10. 按用户 2026-10-20 最新要求，高效视频（HEVC）、直接视频输出和 MTK 解码器低延迟在保存值缺失时默认开启，用户已保存的选择优先。保留 HEVC 硬件能力检查及 H.264 回退、MTK 当前编码解码器路径检查及失败回退，不以 CPU 名称或车型识别启用；不强制写入或覆盖旧设置，不因默认值变更中断当前会话。直接输出和 MTK 专用项作为普通高级选项；普通“低延迟解码”和“流畅视频”仍保留实验性标识且默认关闭，HEVC 软件解码仍默认关闭。用户实测未见异常的反馈明确属于 MTK 专用选项，不能归到普通低延迟或扩展为量化性能结论，详见 `docs/PREFACE_WIRELESS_VIDEO_2026-10-20.md`。

11. 按用户 2026-10-21 最新要求，“CarPlay 通话使用车机电话音频”移除实验性标志，作为普通高级选项，在保存值缺失时默认开启；用户已保存的关闭选择优先，不强制改写旧设置。仅对当前会话的 CarPlay 音频方案请求辅助通话，蓝牙音频方案保留原车 HFP，不因 iPhone 通话状态额外创建辅助通话；使用控制器本次连接固定的音频方案，不读取下次连接才生效的保存值来改变当前通话。通话状态显示继续更新，其他应用的 VoIP 选项独立。通话回声消除、更清晰的通话人声及其他应用 VoIP 电话音频继续默认关闭并保留实验性标志。保留 Telecom 权限、准入、失败退避与释放检查，不因更改默认值主动创建辅助通话或打断会话，也不据此宣称麦克风问题已修复。详见 `docs/PREFACE_PHONE_AUDIO_TELECOM_2026-10-21.md`。

12. 按用户 2026-10-27 最新要求，仪表导航移除实验性标志，以“仪表导航提示”作为导航页第一项，保存值缺失时默认开启，已保存的关闭选择优先，不强制改写旧设置；变更仍在下次连接生效。使用原厂 `DimInteraction` 连接观察及 `INaviInteraction` 的导航开始／更新／停止流程；原厂状态处理交给车机安装的 SDK，不再自行将 `sys.bicv` 输出状态属性作为准入门槛，不自行编码或直写 NAV F3。仅使用当前控制器真实导航数据及已确认转向映射，保留 SDK 接口、真实连接、会话身份、异常停止和退出释放检查；旧会话停止的延迟清理完成前不发布新提示。应用不直接调用投屏开关、电源或系统属性写入，不伪造 SDK 准备状态，不扩权授权组件。界面归类和默认值调整不等于实车显示已验证，1208 旧包不含后续修正，详见 `docs/PREFACE_INSTRUMENT_NAVIGATION_2026-10-27.md`。

仪表导航后续实现按用户 2026-10-29 要求补齐原厂 `refreshGuideInfo` 的合并加分项发送：当前真实支持箭头数据通过安装的 SDK 依次发送合并信息、整数箭头、下一转向距离、ETA、剩余距离和道路名；开始／停止显式通知导航状态 1／0。仅有真实字段才新增对应分项，不伪造航向或扩展渲染首帧。保留接口检查、逐次会话重查、失败停止、原厂状态处理及旧清理屏障，不直接写车辆属性、投屏开关、电源或系统属性。此次源码补齐尚未实车验证，不将 HUD 命名的分项接口存在描述为星瑞仪表必然接收；1210 APK 不含此次补齐，详见 `docs/PREFACE_INSTRUMENT_NATIVE_FIELDS_2026-10-29.md`。

## 效果图与界面预览基准

1. 后续所有车机界面设计、效果图和预览统一以 **2023 款星瑞的 12.3 英寸中控屏、1920×720 像素、横屏**为基准，按该屏幕校准文字、按钮和间距。交付原始 1920×720 图片，不将其他尺寸的图片拉伸后作为适配预览。
2. 标准效果预览使用应用默认的 **“星瑞 12.3 英寸”**界面大小，并核对实际运行的密度、字体比例、系统栏和可用窗口。1920×720 是像素尺寸，不能直接当作 dp，也不能仅凭输出图片的分辨率就声称与安装后的效果一致。
3. 安装后效果优先使用当前 APK 在匹配条件下的原始运行截图，并记录版本和显示参数；设计稿或单元测试渲染图须明确标明来源，不作为实际安装效果交付。
4. 应用仅支持中文。大字体、高密度、紧凑窗口作为补充验证，图片仍保持 1920×720 横屏，并注明与标准预览的参数差异。

## Checks

按用户 2026-10-27 要求，主应用日志按 Debug／Release 区分：普通 V／D／I、协议收发、逐次仪表更新及健康周期统计仅用于 Debug；Release 保留关键状态、连接失败具体原因、W／E、异常、回退、音频／麦克风错误窗口和结束汇总。统一使用 `shared` 的 `logging/CarPlayLog.kt` 与 `DiagnosticLevel`，文件和异步诊断继续脱敏、限长、轮转，并保留会话身份检查；不以日志开关跳过业务回调、导航发送或音频路由／计数复位。详细策略和两种实际变体的定向验证见 `docs/BUILD.md`。源码修改不自动更新已交付 APK，不因日志调整自动打包或宣称实车性能收益。

用户 2026-10-19 要求：日常修改后暂不自动构建、签名或安装测试 APK；仅在用户明确要求打包时执行后续构建与安装流程。

只有用户明确要求“完整测试”时，才运行以下 CI 全量检查。普通修改或“构建测试 APK”默认不运行全量单元测试、Lint 或所有模块检查；构建测试包时仅执行用户指定模块的构建任务（默认 `:mobile:assembleDebug`），并继续核对签名及按下文规则自动安装。不要把仅构建成功描述为完整检查通过。

When the user explicitly requests full testing, run the CI command from `.github/workflows/android.yml`:

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :authorization-components:testDebugUnitTest :home:testDebugUnitTest \
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

1. 当前上游参考基线为 **DiPlay v0.2.22（选择性同步，通用音频、USB、TCP、触控及协议／入口保护）**，固定来源与范围见 `docs/UPSTREAM_SYNC_0.2.22.md`；保留此前 v0.2.21 三项移植及 v0.2.20 选择性移植，见 `docs/UPSTREAM_SYNC_0.2.21.md`、`docs/UPSTREAM_SYNC_0.2.20.md`；另选择性移植 **huangwits/DiPlay-Preface v0.2.20.1** 的蓝牙音频流程，来源与差异见 `docs/GEELYPLAY_BLUETOOTH_0.2.20.1.md`，USB/VPN 按用户后续要求从官方 v0.2.20 恢复，见 `docs/USB_VPN_RESTORE_0.2.20.md`；不引入 E01 专属功能。本仓库应用版本独立维护，以 `mobile/build.gradle.kts` 中的 `versionName` 为准；中文 `README.md` 记录应用版本和上游基线。
2. 后续同步上游改动时，必须更新版本记录：按实际同步到的上游发布版本更新中文 `README.md` 及本节的上游基线，并递增 `mobile/build.gradle.kts` 中的 `versionCode`。应用 `versionName` 遵循下一条的发布规则，不得直接覆盖为上游的应用版本号。
3. 用户指定应用版本时采用指定版本。用户指定本次正式发布版本为 **0.2.1**，在 GitHub 正式发布此版本前，所有开发修改与上游同步均保持 `versionName = "0.2.1"`，不自动递增补丁号。正式发布后，未指定下一版本时默认递增本仓库应用版本的补丁号。`versionCode` 不得回退，新升级构建必须大于上一升级构建，以支持覆盖安装；修改应用版本时同时更新中文 `README.md`。
4. 如果同步的是尚未发布或零散的上游提交，记录实际同步的提交 SHA 和同步范围，不虚构上游发布版本，也不将部分提交描述为完整同步某个新版本。

## 主应用身份

按用户 2026-10-20 最新要求，`mobile`／`automotive` 的正式版和测试版统一使用 `com.preface.carplay`，不添加 Debug 包名后缀，使用仓库原有签名。源码 namespace 和 CarPlay 配件协议标识不随安装包名改动。两种变体可相互覆盖，须使用递增构建号；0.2.0 统一构建号为 1202，大于原 mobile 100 和 automotive 1201；0.2.1 正式构建号为 1204，大于已交付测试包 1203。旧包数据不自动迁移，不自动卸载旧包。授权组件 11 的蓝牙代理只接受统一包名及原证书，旧组件需用户保存新版后手动升级。

## 构建环境与设备安装

独立模块 `authorization-components` 是用户要求补齐的 Android 9 系统授权组件，包名 `com.android.vpndialogs`，不属于 CarPlay 主应用。该模块仅使用匹配参考固件的公开 AOSP 平台测试签名，由 `scripts/android9_authorization.py` 在 Release 构建后本地签名并核对实际设备；原始签名输入只留忽略目录，不扩大凭据白名单、不更换主应用签名。主应用从构建 86 起内置该独立签名 APK 和最小校验元数据，在“设置 → 授权”点击保存按钮后导出到公共 Download 根目录并打开目录，由用户通过文件管理器手动安装，CarPlay 不直接发起组件安装；不能静默安装、自动授权或创建 VPN/TUN，已有原厂／未知同名组件时保留。组件 4 起移除忽略电池优化入口、白名单调用及 DEVICE_POWER 权限。组件 5 起按用户要求提供“允许安装未知应用”“使用情况访问”“无障碍服务管理”三项；按用户后续要求，组件 7 移除补丁桌面图标，保留标准 Settings Intent 授权入口，CarPlay 主应用不增加这三项，可由用户选择当前用户下已安装且声明对应权限／服务的其他应用；逐项确认、校验 UID／签名／版本和系统策略，支持撤销，不提供任意 app-op 或批量授权入口。用户已取消通知使用权，不引入通知授权或独立系统管理包。按用户后续明确选择，组件 7 改用与原生设置一致的 android.uid.system 共享 UID 和 getOpsForPackage 状态查询；继续校验平台证书、实际安装身份、目标和系统策略。按用户 2026-10-19 最新要求，组件 8 起现有六项授权均支持当前用户下符合条件的其他已安装应用；修改系统设置与悬浮窗的标准 Settings Intent 无 package: 时提供应用选择，有 package: 时仅确认该应用；VPN 只接受系统 getCallingPackage() 身份，由目标应用自己发起，不提供代选包名或 Intent extra 授权。保留 UID、签名、版本、声明、启用状态和系统策略检查，签名用于确认目标身份未变化，不要求其他应用采用 CarPlay 签名。组件自身平台权限不转授，蓝牙音乐代理继续仅接受已核对签名的 `com.preface.carplay`；不新增授权类别或桌面图标，CarPlay 主授权页继续只管理自身权限。补丁保留 WRITE_SECURE_SETTINGS、读取无障碍管理员允许列表所需的 MANAGE_USERS 及既有授权权限，使用 GET_APP_OPS_STATS 读取状态，不再声明仅用于旧 checkOpNoThrow 查询的 UPDATE_APP_OPS_STATS；签名保持原值。旧独立 UID 补丁不能直接覆盖升级为系统共享 UID，必须明确提示用户仅手动卸载本仓库旧授权补丁后重装，不卸载 CarPlay、不自动卸载或清数据，已有原厂／未知同名组件仍保留。RECEIVE_BOOT_COMPLETED 是已声明的普通权限；悬浮窗不是接收开机广播的授权，不把后台拉起辅助或未确认的厂商自启动白名单描述为标准开机权限。“保存授权 APK”和“打开保存目录”按钮在所有组件状态下常驻，放在同一行、均分可用宽度并保持等高，文字可换行，使用统一 dp 间距。点击保存时保留兼容性与签名检查；已安装就绪的本仓库组件也可保存，按用户 2026-10-20 最新要求，导出文件固定命名为 Preface-Android9-Authorization.apk，名字后不加版本号；保存时校验临时副本后覆盖同名不同内容文件，不追加编号。下载目录已有相同内容 APK 时提示已保存，不重复写入、不再次自动打开目录。“打开保存目录”按钮行为独立于导出按钮，任何组件状态下均可手动打开下载目录，无需重新导出；优先系统下载入口，再尝试文件管理器目录入口，不优先发送通用目录 ACTION_VIEW。系统接受打开请求不代表目录已实际显示，不能据此声明实车闪回已修复。新环境先显式使用脚本的 `--prepare-signing-inputs --fetch-public-aosp-key` 准备固定来源及校验值的公开输入，正常主应用构建自动依赖组件构建、签名及生成 assets，签名输入不得打包。只在 API 28、平台签名匹配且无原厂／未知同名组件时安装，不覆盖现有系统 VpnDialogs；实际安装及权限状态分别记录，详见 `docs/ANDROID9_AUTHORIZATION_COMPONENTS.md`。

主应用默认打包 `runtime-assets/offline-mfi/identity.pk8` 和 `certificate.p7b`。这是用户明确要求导入的上游 v0.2.18 公共预览 APK 的实验性配件认证资料；来源和 SHA-256 见 `docs/runtime-authentication-provenance.json`。设置 `DIPLAY_AUTH_ASSETS_DIR` 可为本地构建替换认证目录，替换资料不得入库。

按用户 2026-10-20 最新明确要求，主应用接入所提供 QQ 助手的媒体授权文件及配套 ECARX manifest 参数，**主应用保持原签名，包名按用户最新要求统一为 `com.preface.carplay`**，不采用 QQ 助手平台签名。本次明确使用授权资料的要求替代此前对此文件只分析、不导入的边界；不改变其他凭据规则。原始 license 字节及参数仅存本机忽略目录 `agent_docs/runtime-ecarx-media/`，由 `scripts/ecarx_media_authorization.py` 从固定 SHA-256 的用户 APK 导入；可用 `DIPLAY_ECARX_MEDIA_AUTH_DIR` 指定同内容输入目录。`mobile`／`automotive` 构建校验输入哈希后打包为 `assets/license.txt` 并注入参数，缺失或不一致时明确失败，不静默省略。不提交授权原文／参数、不扩大公开凭据白名单、除用户已要求的统一包名外，不修改签名配置、系统服务或授权文件，不伪造 ready／token／requestPlay。保留原签名的静态身份仍不匹配该授权，实际车机结果待验证，不能描述为切歌跳页已修复。详见 `docs/BUILD.md`。只有用户明确要求打包时才构建、签名或安装 APK。

用户明确要求公开现有 APK 签名密钥及签名密码，允许 `signing/carplay-release.p12` 和 `signing/signing.json` 入库。默认发布构建复用这组签名材料，证书指纹不得改变。`scripts/check_public_tree.py` 仅允许以上四个固定路径及已确认内容；其他私钥、令牌、签名密钥和本地配置继续排除，不扩大白名单。公开签名材料不得打包成应用资产。

后续构建 CarPlay 测试 APK（`mobile`／`automotive` 的 Debug 变体，包括 `assembleStandaloneDebug`）也必须使用 `signing/` 中的现有密钥和密码，与 Release 使用同一证书；不要使用 Android 默认 Debug 密钥或另行生成测试密钥。除非用户明确指定其他签名，不设置 `ANDROID_KEYSTORE_*`／`ANDROID_KEY_*` 覆盖参数。构建后核对 APK 证书指纹；旧测试包若使用其他签名导致覆盖安装失败，报告错误，不卸载或清除数据。

### 后续 APK 必须合并交付授权组件

组件 6 起提供仅供已核对签名的 `com.preface.carplay`调用的蓝牙音乐连接代理；原连接操作只连接同用户、仍已配对的所选 iPhone A2DP sink。用户在 2026-10-20 分析构建 99 后明确允许扩展，**组件 10 起增加当前 CarPlay 会话所选 iPhone 的 A2DP 临时断开／恢复租约**，替代此前“不提供断开”的限制；不提供任意手机的通用断开接口。保留 API 28、双方证书、实际权限、目标配对、版本、会话取消、其他手机和通话检查；不操作配对、HFP/SCO、音源或蓝牙总开关。只恢复本租约确实请求断开的连接；取消、退出、Binder 断开时有界恢复，其他手机、身份或策略变化时不得抢占。系统受理不等于实际断开／连接；CarPlay 音乐焦点须等待真实 A2DP 断开确认。不得将源码实现或平台接口存在描述为实车自动暂停已修复，历史见 `docs/BLUETOOTH_WHEEL_RECOVERY_2026-10-19.md`。

按用户 2026-10-20 在分析构建 100 实车日志后的最新要求，移除独立媒体助手模块及其 SDK 探测、IPC、包可见性、内置 APK、签名／校验脚本和导出入口。主应用直接使用自身原厂媒体中心 SDK，仍真实检查 ready、注册 token 和 requestPlay，不以助手失败为由伪造成功或扩大厂商准入。保留授权组件 10 的所选 iPhone A2DP 会话断开／恢复、确认断开后申请焦点及现有方控去重。构建 100 的历史 APK 和实车记录继续保留；它们仍含助手，不能把源码移除描述为已交付新包。后续打包只内置授权组件，不再构建、签名、探测或导出媒体助手。详见 `docs/MEDIA_ASSISTANT_REMOVAL_2026-10-20.md`。

1. 用户已确认：后续交付的 CarPlay 主应用 APK 必须内置独立签名的 Android 9 授权组件，以一个主 APK 分发，由“设置 → 授权 → 保存授权组件”保存到公共 Download 根目录并打开目录，让用户通过文件管理器手动安装；CarPlay 不直接调起组件 APK 安装器，不申请安装来源权限，Android 9 缺少存储权限时仅请求导出所需的存储权限。不得退回要求用户另外下载、传输两个 APK 的默认交付方式。
2. `mobile` 的 Debug、Release 和 `assembleStandaloneDebug` 均必须通过现有生成 assets 任务构建、签名并内置组件；用户指定其他 CarPlay 打包模块或新增变体时，也应先接入同等打包流程，再交付，不能遗漏组件。
3. 构建后必须检查主 APK 内含 `assets/authorization/authorization.apk` 和 `manifest.json`，核对内置 APK 的 SHA-256、包名、版本及平台证书，并核对主 APK 仍使用仓库原有签名。组件构建、签名、校验或打包失败时，修复或明确报告阻塞，不得交付缺少组件的 APK 或用旧组件替代本次应构建的产物。
4. 主应用和组件保持独立包名及签名；系统安装仍需用户确认，安装后分别授权。已有原厂／未知组件、API 或平台证书不匹配时跳过导出；本仓库旧版组件有更新时允许导出新版供用户手动覆盖安装，不静默安装、不自动授权、不覆盖原厂组件。平台签名输入只留本机忽略目录，不入库、不放入任何 APK 资产。
5. 按用户最新要求，主 APK 仅内置独立签名授权组件 `assets/authorization/authorization.apk` 和最小 `manifest.json`，经固定平台证书、包名、版本及哈希校验。“保存授权 APK”只保存 `Preface-Android9-Authorization.apk` 到 Download 根目录，再由用户手动安装；保留固定文件名、同名覆盖、相同内容去重、未知同包名应用保护及主包原签名。不得恢复媒体助手，也不自动卸载车机已有应用。用户明确要求打包前，不运行 assemble、签名或安装。

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
`SettingsCategory`, `SettingsSection` and `SettingsInformationArchitecture` in
`common/src/main/java/com/shilapi/xcertplay/SettingsInformationArchitecture.kt` define the groups.
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
   That form renders as a setting row with the value and a chevron. Settings search has been removed.
8. When you move a control between categories, update `AdaptiveSettingsUiTest.settingsLiveWhereDriversLookForThem`.

### Settings entry

从 CarPlay 投影的“全部设置”入口打开设置后，设置页返回按钮和系统返回键直接恢复原 CarPlay 投影，不先回应用主页、不主动重连；从主页进入设置仍返回主页。返回来源随页面状态恢复，设置内的备份等子页先返回设置。会话已结束或连接已取消时回主页，不为返回动作启动新连接。

Picture adjustments are available only from the connected CarPlay projection quick menu. Do not add a picture-adjustment button to Settings or start a connection for that action. Keep saved picture values and live adjustment behavior.

The Audio page starts with the non-experimental “音频方案” (Audio mode) card, with exactly two choices: CarPlay audio and Bluetooth audio. Do not duplicate it in Experimental. The removed playback-dependent media/navigation volume selection must not be restored by old preferences or service recovery. Leave volume keys with the head unit; preserve media keys, explicitly configured Siri/joystick support, audio stream settings and speech mixing. Mode changes apply at the next connection through markReconnectNeeded(), without stopping the current session. See `docs/BLUETOOTH_MUSIC_WHEEL.md`.

The Automatic connection card is first on the Connection page, before wireless hotspot and readiness. It has exactly two switches: Auto-connect (default on when unset) and Start on boot (independent, default off). Auto-connect uses a phone with live Bluetooth connection evidence or the attached USB device, preserving USB priority and existing sessions. Do not restore a default transport picker, separate Bluetooth switch or preselected-phone control. Automatic USB and queued Bluetooth/permission launches obey the unified switch. When disabled or cancelled, the USB device row may start a manual connection; do not add another transport card or USB Connect button. Boot start opens the app through BOOT_COMPLETED without granting permissions or running ADB. See `docs/AUTOMATIC_CONNECTION.md`.

This branch has no Overview menu or standalone connection setup page. Home shows a brief Bluetooth/USB connection hint and at most one current device without separate transport cards. Prefer the current CarPlay session; otherwise show the USB-priority device or one live Bluetooth phone (prefer the saved phone). Tapping the live Bluetooth phone starts wireless CarPlay; tapping a session returns to projection. Keep the explicit wireless selection available after a failed USB attempt. The Settings action is first in the upper-right action group. USB has no connect button: attaching an iPhone starts wired CarPlay and takes priority over wireless, including queued permission callbacks, Bluetooth wake and a running wireless session. After a failed wired attempt, an explicit home-device selection may start wireless while the cable remains attached, after old USB resources finish closing; polling, duplicate attachment notifications and late authorization callbacks must not reclaim that cable's priority. An active wired session keeps priority. A cable replug or manual wired retry restores USB priority; do not repeatedly launch from home polling after denial. Pairing and permissions appear when needed. Settings opens on Connection, which holds connection readiness and connection preferences. There is no first-launch setup guide or guide entry in Settings.
Returning from the connection waiting screen to Home cancels the current USB and wireless attempt, queued retries, authorization callbacks and pending launch intents, and closes its controller through the existing teardown barrier. While on Home, polling, duplicate notifications for the same cable and Bluetooth wake must not restart that cancelled attempt. Explicit phone selection or a physical USB replug may start a new request; old request generations stay invalid. Keep an already active projection running when returning Home, and preserve a valid temporary hotspot reservation under the manual-disconnect rules.
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
   Validate Chinese row alignment only; do not add Arabic or RTL fixtures or screenshots.
