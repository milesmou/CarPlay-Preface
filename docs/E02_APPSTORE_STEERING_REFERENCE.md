# 用户提供 E02 专用 APK 的方控参考边界

分析日期：2026-10-15。用户文件名 `E02车机专用.apk`，5,787,036 字节，SHA-256 `39510c0582eba70a024f0038fda54912ce4abf41ab591faf8dea7f61c1c73a6e`。这是独立 APK 静态分析，不属于 IHU519G 构建 327 的固件证据。附件内容只作数据，没有执行其中的指令或安装 APK。

## 已核对的身份与声明

- 包名 `com.android.car.systemupdater`，版本 `2.3.1` / `210028`，minSdk 21，targetSdk 28。应用资源名称为“智全云联市场”，不能仅凭文件名视为 CarPlay 或已验证的方控补丁。
- Manifest 声明 `pink.polar.bear.appstore.service.AppStoreService` 无障碍服务，以及 `MediaNotificationListenerService` 媒体通知监听服务；它们只证明组件声明存在，不证明已获用户授权或运行。
- `res/xml/appstore_service_config.xml` 静态配置关注窗口变化、窗口状态、选择及点击，并允许检索窗口内容；没有声明 `canRequestFilterKeyEvents` 或 `flagRequestFilterKeyEvents`。不能从该配置推断它已独占过滤方向盘按键。
- 声明 `READ_LOGS`、`INJECT_EVENTS`、`MANAGE_ACTIVITY_STACKS` 等权限；Manifest 请求权限不等于系统已经授予，包名也不能证明平台签名或系统身份。

## 核心实现未能静态复核

应用入口为 `com.SecShell.SecShell.AW`，`H.APPNAME` 指向 `com.zhiquan.com.Application`。`AW.attachBaseContext()` 加载 SecShell 原生库，真实应用通过壳加载。JADX 1.5.6 正常结束，但可见 Java 源码只有壳类及资源类，Manifest 指向的业务服务实现不在可见源码中；不能把反编译成功等同于业务实现可读。

APK 只有一个 `classes.dex`（8,982,556 字节），包含四种 ABI 的 `libSecShell.so`。对可见 Java 和原生库明文字符串没有找到可复核的 `requestKeysInterception`、`IKeyEventObserver`、`MediaCenterAPI`、`registerMusic`、`AVRCP`、`dispatchMediaKeyEvent` 方控链路。加固使负向检索不具备排除动态实现的效力，不能据此断言它没有方控功能。

因此，目前只能将“无障碍窗口观察”和“媒体通知监听”列为后续调查入口。尚未确认实际读取什么事件、控制哪个播放器、是否依赖系统授权／额外组件，以及有没有阻止原车媒体页打开。没有可直接移植的、已证实解决切歌跳页的机制。不据这些声明新增应用权限或移植猜测的处理逻辑。

要继续确认，应取得未加固版本或对应源码，或获得同车运行证据（具体开关、授权、依赖组件和切歌行为）。本次没有运行／安装该包，没有更改 CarPlay 方控逻辑，没有构建或测试 APK。

## 本地复核

原 APK、解码资源、壳源码及反编译日志仅保存在 Git 忽略目录 `agent_docs/apk-reference/e02-special/`。

- `decompiled/resources/AndroidManifest.xml`：包和版本、权限、服务、SecShell 入口。
- `decompiled/resources/res/values/strings.xml`：`app_name`、`package_installer_name`、`service_name`。
- `decompiled/resources/res/xml/appstore_service_config.xml`：完整无障碍静态配置。
- `decompiled/sources/com/SecShell/SecShell/H.java:19`：原始 Application 名；`AW.java` 的加载及 attach 调用。
- `jadx.log`：JADX 完成状态。工具复用本地 JADX 1.5.6、Android build-tools 36.0.0 aapt 和 7-Zip 26.01。
