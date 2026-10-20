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

本次发布版本为 0.2.1／1204，上一发布版本为 0.2.0／1202。mobile／automotive 统一版本和包名，避免同包名变体版本倒退。升级构建号必须递增。其他按需任务：

| 目的 | Gradle 任务 |
| --- | --- |
| 检查认证资料并构建独立连接测试包 | :mobile:assembleStandaloneDebug |
| Release | :mobile:assembleRelease |
| 完整测试 | 仅用户明确要求时按 [验证说明](TESTING.md) 执行 CI 命令 |

正式版执行 `:mobile:assembleRelease`，保留原始输出及元数据，交付文件命名为 `CarPlay-Preface-<versionName>-<versionCode>.apk`。旧包名不会自动迁移数据，不自动卸载。

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
