# 构建与交付

车机主应用使用 `:mobile`；common、shared 为库，home、maphost 是示例，automotive 是独立应用。不要用未限定模块的 assembleDebug 代替主应用任务。

## 环境

复用 agent_docs/build-environment.local.json、local.properties 与当前环境变量，检查工具路径有效。项目要求 JDK 25、Android SDK Platform android-37.0、Build-Tools 36.0.0、Platform-Tools 和 NDK 28.2.13676358，使用仓库 Gradle Wrapper。缺少工具或版本不符时先取得用户提供的路径／获取方式，不自动下载或替换版本。

JAVA_HOME 指向 JDK，ANDROID_HOME 指向 SDK；或在 Git 忽略的 local.properties 设置 sdk.dir。工具放稳定本地目录，缓存只记路径、版本和必要参数，不保存密码或密钥，不修改全局环境。

## 按需构建

日常修改不自动构建、签名或安装；用户明确要求打包时，默认仅执行：

```powershell
./gradlew.bat :mobile:assembleDebug
```

不附加单元测试、Lint 或所有模块检查。Debug 与 Release 的包名均为 `com.preface.carplay`，同签名变体可相互覆盖。原产物 mobile/build/outputs/apk/debug/mobile-debug.apk 和 output-metadata.json 保留；按实际版本复制为：

```text
CarPlay-Preface-<versionName>-<versionCode>-debug.apk
```

当前源码及最近交付测试包为 0.2.2／1213，已按用户预先授权撤回仪表独立服务及原厂库导入，恢复直接 SDK 发送并明确依赖原厂高德准备；原因及边界见 [327 静态验证与撤回](PREFACE_INSTRUMENT_OEM_FALLBACK_2026-10-30.md)。最近正式发布版本为 0.2.1／1204。mobile／automotive 源码统一构建号，避免同包名变体版本倒退；本次仅构建 mobile Debug，原签名和内置组件 11 已核验，当前无在线 ADB 设备、未安装，详见 [1213 构建记录](ANDROID9_AUTHORIZATION_COMPONENTS.md#0221213-测试包构建核验)。历史 1212 包仍包含私有服务。升级构建号必须递增。其他按需任务：

| 目的 | Gradle 任务 |
| --- | --- |
| 检查认证资料并构建独立连接测试包 | :mobile:assembleStandaloneDebug |
| Release | :mobile:assembleRelease |
| 完整测试 | 仅用户明确要求时按 [验证说明](TESTING.md) 执行 CI 命令 |

正式版执行 `:mobile:assembleRelease`，保留原始输出及元数据，交付文件命名为 `CarPlay-Preface-<versionName>-<versionCode>.apk`。旧包名不会自动迁移数据，不自动卸载。

## Debug 与 Release 日志

2026-10-27 源码按构建类型区分日志，由 `shared` 对应变体的 `BuildConfig.DEBUG` 决定，不读取机型或已有的屏幕调试显示偏好。日志分级已打入 0.2.2／1210 Debug 测试包，该包使用 `debug-detailed` 并保留详细日志；本次没有构建 Release。历史 1209 APK 仍采用原有日志行为。

| 日志 | Debug | Release |
| --- | --- | --- |
| 普通 V／D／I、协议收发和逐次仪表更新 | 保留 | 关闭 |
| 连接状态、会话开始／结束、仪表可用性及停止原因 | 保留 | 保留 |
| W／E 与异常堆栈、回退原因 | 保留 | 保留 |
| 周期视频、健康音频及麦克风统计 | 保留 | 关闭；音频／麦克风结束汇总及错误窗口保留 |
| 临时 iPhone 认证日志捕获 | Debug 可执行 | 不启动 |

Android 输出统一使用 `logging/CarPlayLog.kt`；新的普通详细日志通过 `i`／`d`／`v`，必须在正式版保留的状态用 `event`，异常用 `w`／`e`。控制器到应用的日志回调带 `DiagnosticLevel`，诊断文件的直接和异步写入使用同一策略并继续脱敏、限长和轮转。报告头部标记 `logging=debug-detailed` 或 `logging=release-events`。原厂服务和系统自身的日志不受该策略控制；ADB 辅助进程的标准输出协议继续保留。

过滤只影响记录，不跳过导航发送、首帧通知、连接状态回调、音频路由检查或统计窗口复位。不自动启用混淆／R8，不因关闭日志改变会话行为。连接失败在正式版保留具体原因及是否重试，会话关闭保留关闭原因；设置页打开时仍记录当前会话的关键事件和异常，旧会话不能向新会话写入普通状态／异常。

`shared`／`common` 提供 Release 单元测试入口，可在不打 APK 的情况下核对实际 Release 变体。2026-10-27 的 84 项定向验证通过：shared Debug 34、Release 26，common Debug 20、Release 4，失败／错误／跳过均为 0。覆盖 Android 日志优先级与异常、文件和异步队列过滤、脱敏、会话身份、设置页期间的错误保留、失败原因、仪表发送，以及音频／麦克风在过滤周期日志后仍查询路由、复位窗口并保留错误与结束汇总。本机命令输出和分组汇总位于 `agent_docs/logging-policy-20261027/final-targeted-tests.log`、`validation-summary.json`。该源码修改阶段未运行全量单元测试、Lint 或完整 CI，未构建、签名或安装 APK，未测量实车日志减量或性能收益。随后按用户要求仅构建 1210 Debug 测试包并核对签名、内置组件、日志策略和覆盖安装，未追加单元测试或完整 CI，详见 [1210 构建核验](ANDROID9_AUTHORIZATION_COMPONENTS.md#0221210-测试包构建核验)。

## 认证与签名

主 APK 默认内置 runtime-assets/offline-mfi/identity.pk8、certificate.p7b；来源和 SHA-256 见 [认证资源](CARPLAY_AUTH_RESOURCES.md)。DIPLAY_AUTH_ASSETS_DIR 可指定外部替换目录，资料不入库。包内应为 assets/offline-mfi/ 下的两项，构建会拒绝意外凭据资产。

Debug、Release 及 assembleStandaloneDebug 使用仓库 signing/ 的同一证书，不使用 Android 默认 Debug 密钥，不另行生成测试密钥；无用户明确指定时不设置 ANDROID_KEYSTORE_* 或 ANDROID_KEY_* 覆盖。详见 [签名说明](../signing/README.md)。

主证书 SHA-256：`75f4eda0d13be105f578253381eadb5619c04548cc78f58faf2cc8f73a2660a8`。公开签名材料不得进入 APK 资产，其他密钥和本地配置继续忽略。

## 必须内置授权组件

`mobile` 与 `automotive` 的 Debug、Release 和 StandaloneDebug 通过 `gradle/carplay-distribution.gradle` 统一主应用签名、认证资产限制与授权组件内置。组件构建后的签名及校验由 `:authorization-components:prepareDistributionAssets` 统一执行，各应用仅同步结果，避免并行构建重复写同一产物。主应用自动依赖该流程。新环境须显式准备固定来源的公开 AOSP 输入：

```powershell
python scripts/android9_authorization.py --prepare-signing-inputs --fetch-public-aosp-key
```

后续使用有效缓存。每次交付核对主 APK 的 assets/authorization/authorization.apk、manifest.json，确认内置 APK 字节 SHA-256、包名、版本、平台证书和元数据一致；同时核对主应用证书。签名输入不得打包，失败不交付缺组件的 APK，不以旧组件替代。细节及当前构建记录见 [授权组件](ANDROID9_AUTHORIZATION_COMPONENTS.md)。

## 覆盖安装

完成构建、命名和核验后运行 adb devices -l。只有状态 device 可安装；一台安装到该台，多台且未指定目标则全部安装：

```powershell
adb -s <serial> install -r <本次命名后的APK>
```

保留数据，无在线设备就交付路径，不自行启动模拟器。签名冲突、降级或安装失败时报告设备和错误，不卸载、清数据或强制降级。安装主应用不等于安装内置组件；组件仍由用户在设置中保存后手动安装。

## 常见问题

仪表独立移植已撤回，原厂库导入脚本和 `verifyOriginalInstrumentAssets` 任务已移除；正常构建不要求或打包该本地缓存。保留本机历史输入与分析证据，详见 [撤回说明](PREFACE_INSTRUMENT_OEM_FALLBACK_2026-10-30.md)。

| 问题 | 检查 |
| --- | --- |
| 装成 map host | 是否构建 mobile、选取 mobile 输出 |
| 找不到 SDK／Java 不匹配 | 缓存路径、ANDROID_HOME／sdk.dir、JAVA_HOME 与 java -version |
| 认证资料缺失 | 所选目录及两项文件 SHA-256，StandaloneDebug 校验结果 |
| 组件签名或资产生成失败 | 固定 AOSP 输入、平台证书和本次生成元数据，不跳过依赖 |
| 覆盖安装失败 | 已装包名、证书和 versionCode，保留数据并报告错误 |

## 本机媒体构建输入

构建还需要用户已指定的媒体授权输入，保存在 Git 忽略的 `agent_docs/runtime-ecarx-media/`：`assets/license.txt`、`manifest-values.json`、`provenance.json`。仅授权文件及配套 Manifest 参数进入主 APK，来源记录不打包；缺失或哈希不一致时构建明确失败。

新环境取得用户提供的固定来源 APK 后，使用已配置 SDK 的 aapt2 导入：

```powershell
python scripts/ecarx_media_authorization.py --import-source --apk '<用户提供的源 APK>' --aapt2 '<已有 SDK 的 aapt2.exe>'
python scripts/ecarx_media_authorization.py --verify
```

可用 `--directory` 和 `DIPLAY_ECARX_MEDIA_AUTH_DIR` 指定同内容本机目录。授权原文和参数不入库，不扩大公开凭据白名单，不更换 APK 签名或修改授权。文件正确、换包名均不代表厂商媒体准入成功，运行时仍检查真实 ready、注册 token 和 requestPlay。
