# Linkco 方控媒体链路完整对照与移植

日期：2026-10-15。按用户“完整复刻”的要求，对照第三方盒子 APK 的 SDK 加载、初始化、注册、音源、播放记录、回调分发及断开生命周期。实现目标为星瑞 E02 的方控媒体接入，应用版本 **0.1.1 / versionCode 67**。

## 证据与适用范围

- 用户提供 `CarPlay-linkco-v1.9.1.63.2330-20240705 (1).apk`，包名 `com.aufn.carplay`，SHA-256 `bdb4f4075a4b0a3ffad93faa1fd1c07f284b2fe4ade1f114d12bd12c2409d4c9`。用户确认同车切歌正常、不跳原车多媒体；该次运行的具体 SDK 路由没有实时日志。
- 反编译缓存位于 `agent_docs/apk-reference/linkco-1.9.1.63.2330/decompiled/`；JADX 1.5.6 有四处恢复错误，不把整个 APK 视为已完整恢复。下表使用可读的方法与调用点。
- 参考固件 **IHU519G / FS11A1 / Android 9 / 构建 327**，来源 OTA SHA-256 `f61a7399d61a7f4502b9289ea6c49c661c39c459aae81e676722bcf9be913c01`。新增 PlaybackInfo 声明逐项核对 `catalog/media-center-classes.json` 中 `Lcom/ecarx/sdk/mediacenter/MusicPlaybackInfo;` 的方法名、返回类型和无参数签名，来自 `boot-ecarx.gkui.openapi.impl.vdex`，SHA-256 `f0b8f013ccd0b5fcdb52c59bb77ef28c4c9abd142b9540515de3066de7db797f`。
- 当前车机旧报告为 IHU353G，不能将以上静态证据替代该车机的初始化成功、音源准入或切歌验收。
- 本轮证据文件清单及校验保存在本地忽略目录 `agent_docs/linkco-replica/evidence.json`；修改前的已有工作保存在同目录 `pre-existing/`。

## 全链路对照

下列第三方路径相对于缓存 `decompiled/sources/`。

| 环节 | 第三方证据 | 本地处理 |
| --- | --- | --- |
| SDK 来源及 Manifest | Manifest 没有 ECARX `uses-library`、system shared UID 或专用媒体权限声明；缓存未发现打包的 `com/ecarx/sdk/` 实现 | 使用车机提供的 SDK；本仓库编译声明继续 compileOnly，未打包厂商实现。原应用可成功运行不等于本应用已获相同准入。 |
| 初始化入口 | `com/juyou/app/Controller.java:829–836` 在控制器构造期间直接调用 init，传入 BaseApplication | 保留用户先前要求的 Application 启动初始化；改为应用主线程执行 get/init，移除独立 OEM 初始化线程。源码只证明参考 APK 直接在调用者线程初始化，没有将它的所有调用线程推断为已实测主线程。 |
| 创建顺序 | `com/juyou/ecarx/ECarxManager.java:51–62` 先 get API，再创建播放记录和 MusicClient，最后 init | 调整本地顺序：先取得 API 实例，再构建具体客户端。新增顺序测试；不声称这一差异已被证明是 ready=false 的根因。 |
| 就绪与注册 | `ECarxManager.java:62–73` 在 ready=true 时 registerMusic(本应用包名, MyMusicClient) | 使用相同具体类重载与本应用真实包名；同步主线程 ready 回调立即注册，其他线程的 ready 回调进入主线程队列；false 不注册，不循环切换重载。 |
| 注册生命周期 | ECarxManager 是进程单例，所读实现没有 unregister 调用 | 注册跨 CarPlay 控制器复用。断开撤销播放／按键归属并清空会话快照，不每次重新初始化 SDK；API 丢失和显式关闭仍清理 token。 |
| 请求音源及发布顺序 | `ECarxManager.java:96–111` requestPlay 成功后发布列表、source=6、播放状态 | 同顺序；断开后下一次请求前先恢复记录的 source=6。只在真实媒体开始／恢复且运行检查允许时申请。 |
| 列表 | `MyMediaListInfo.java:12–24` 空列表、listType=0、sourceType=6 | 已有实现保持一致，不生成假手机队列。 |
| 播放记录对象 | `MyMusicPlaybackInfo.java:13–29,96–109` 单例可变对象，状态 1/0、source=6 | 改为每个进程注册使用同一个稳定对象，客户端查询和 SDK 发布复用该对象；播放／暂停、歌曲更新及重连反映到保留的引用。 |
| 音频停止后的暂停 | `Controller.java:1354–1358` 调用 `ECarxManager.pause()` 清除共享记录的 playing | 媒体流停止即更新原厂记录为暂停，不让 iPhone 尚未刷新的旧 playing=true 留住播放状态，不额外申请音源。 |
| 播放记录完整能力 | `MyMusicPlaybackInfo.java:44–145` 空图标、carplay artwork URI、listType=0、collect/download/loop/VR capability=false | 补齐声明与具体覆写；继续发布真实曲目信息、进度及本应用 PendingIntent。新增声明已与参考固件签名逐项核对。 |
| 下一曲／上一曲 | `MyMusicClient.java:30–55` 入 EventBus，返回接受；`CoreService.java:389–399` 主线程分发 | 主线程直接分发；厂商 Binder 回调入主线程队列后返回接受，不再等待主线程最多 250 ms。队列记录控制器和回调身份，断开／替换后丢弃旧按键。 |
| 断开与重新播放 | `ECarxManager.onDisconnect():82–87` 将 sourceType 设为 -1；具体调用点是 `SWCActivity.onDestroy():359–365` | 将这一状态撤销用于本地 CarPlay 断开，稳定记录不再报告播放，source=-1；下一次播放复用注册并重新请求音源。不能将 SWCActivity 的调用点描述为已经证明覆盖第三方所有普通断线。 |
| SDK 不可用与诊断 | 第三方 work=false 后停止接入 | 保留 ready、注册、音源结果与初始化线程日志；SDK 不可用时应用仍可用已有媒体路径。没有把 false 强改成 true，也不在失败时假称注册成功。 |

所有 SDK 生命周期调用在主线程串行执行；持有 CarPlayMediaKeys 全局锁时先入队，避免在锁内执行 SDK IPC。同步主线程 ready 回调仍能在 init 返回前完成注册。原厂切歌回调处理使用当前真实会话、焦点、通话、蓝牙及语音优先检查；断开立即使旧会话不可接受，下一会话必须重新取得播放归属。

## 与盒子协议及本地功能的衔接

第三方最终向其 USB 盒子发送媒体命令，本项目向 iPhone 发送 CarPlay HID，保留当前按下／释放与跨来源去重。第三方的 PLAY/PAUSE 均映射 toggle、500 ms 动作合并、50 ms 去重及音频包中每 101 秒再请求音源，不用于替换本地明确 PLAY／PAUSE、连续按键和音源归属策略。按用户既有要求保留歌曲信息与进度，未把标题／歌手固定成占位文字，也未冒用第三方包名或签名。Android targetSdk 保持本项目配置。

这些是衔接现有 iPhone 会话与已确认需求的差异，本次是方控媒体链路的源码对照移植，不是整个盒子 APK 的二进制克隆。USB 盒子协议不等于 iPhone NCM；本轮未更改本项目 USB 正常连接及原始诊断策略。

## 验证与实车验收

本轮 **65 项定向测试通过**：具体类后端 11、interface／元数据后端 7、注册生命周期 13、应用启动及注册复用 8、Application 入口 1、媒体归属 7、媒体回调 10、按键去重 8。

新增测试覆盖 get API 先于具体客户端构造、主线程 get/init 且不持全局按键锁、同步 ready 在 init 返回前注册、进程 token 复用及重新播放申请、稳定播放对象在暂停／断开／恢复后被 SDK 保留引用仍正确、完整 capability 字段、Binder 回调不等待主线程、下一曲／上一曲队列顺序及控制器替换后丢弃旧按键、音频停止时清除原厂播放状态。参考固件中的十个新增 PlaybackInfo 方法签名核对通过。

`:mobile:dependencies --configuration debugRuntimeClasspath` 成功，运行依赖中没有 `ecarx-mediacenter-api-stubs`；本次编译出的 common Debug runtime classes.jar 中 `com/ecarx/sdk/` 定义数量为零。依赖日志及测试 XML 保存在本地忽略目录 `agent_docs/linkco-replica/`。

空白检查按 CRLF 行尾配置的 `git diff -w --check` 通过。未运行全量 CI、未构建 APK，未提交或推送。

实车重点：报告应先看到 `mainThread=true processRegistration=true`，再看 `ready=true`、`registered`、`request-play-accepted` 以及真实音源／按键行为。若仍 ready=false，说明更接近参考调用条件后 SDK 仍未准入，需继续区分服务绑定与调用者准入；本次不将线程、稳定对象或注册复用任一项冒充已确认根因。

验收包括前后台上一曲／下一曲、连续快速切歌、播放和暂停、断开后原车接管、再次连接不重复注册、Siri／通话、主动切到收音机后的归属，以及无跳页／无自动暂停。静态方法签名和模拟测试不替代这些实车结果。

## 后续测试包构建

按用户后续要求，`:mobile:assembleDebug` 已成功，产物为 `CarPlay-Preface-0.1.1-67-debug.apk`，包名 `com.shihab.diplay.hudtest`，大小 24678073 字节，SHA-256 `4ecbdca0f009bb18c2486ad6356f9a5d6e07eed5d5517fa35e965dc49708a754`。签名 SHA-256 `75f4eda0d13be105f578253381eadb5619c04548cc78f58faf2cc8f73a2660a8`，与现有发布证书一致。ADB 无在线设备，未安装，未启动模拟器。此次只执行主应用 Debug 构建，没有运行全量 CI，也没有取得实车验证。构建元数据保存在本地 `agent_docs/linkco-replica/build-67.json`。
