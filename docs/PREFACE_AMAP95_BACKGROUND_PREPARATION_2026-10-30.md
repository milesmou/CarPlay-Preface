# 改版高德后台初始化、画面准备与退出释放

> **口径（2026-10-30 更新）：** 悬浮底包已从工程移除，其改动冻结为 `floating-delta`；当前构建改为纯公众版。文中 `prepare_amap95_hint_dex.py` 已删除，改用 `prepare_amap95_public_rebuild.py`，见 [公众版重建](PREFACE_AMAP95_PUBLIC_REBUILD_2026-10-30.md)。

2026-10-30，用户在[实车日志及退出恢复修正](PREFACE_AMAP95_VEHICLE_LOG_2026-10-30.md)后，明确要求补通“后台初始化、真实仪表准备及退出释放”。当前源码在 CarPlay 和配套改版高德之间增加受身份校验的准备租约，通过高德已有 JSON 服务启动进程内流程；不要求用户先打开地图或开始一次高德导航。

**源码链路和定向替身验证已完成，实车冷启动、真实 PixelCopy、系统接收准备状态及仪表显示尚未复测。** 不能将新的 Binder 握手、初始化回调或缓冲区回读描述成车机仪表应答成功。没有构建、签名或安装新 APK；现有高德 26000007 和 CarPlay 1211／1213 均不包含这条新链路。下一次获准打包高德时脚本使用 26000008／`test-apk-07`，保留原历史产物；本轮未执行该打包脚本。

## 实现范围

- CarPlay 主应用仍使用安装的原厂仪表 SDK，并发送当前会话真实 iPhone 导航数据。未恢复主 APK 独立引擎、独立进程或助手 APK，也未修改授权组件。
- 配套改版高德仍基于固定工作底包 `094e296ef0783a76d3c2a82a2b91a5eb3198cef2338db78549295617f523ed92`。原生库、主屏启动逻辑、已有签名处理、原 JSON Binder 方法体均保留。固定输入的 Manifest 已核对 JSON 服务 enabled／exported；没有增加新的 Android 组件或更改其导出权限。
- 新增控制入口复用 `com.autonavi.amapauto.protocol.service.JsonProtocolService`，仅在专用 action 下返回准备 Binder；普通 OEM JSON action 继续执行原始 `onBind()`。新增 IPC 是本次用户要求的后台准备控制通道，仅有打开、心跳查询、释放三项，不接受地图启动、导航字段、任意 Intent、系统属性或车辆控制命令。
- 不启动地图 Activity，不自动安装、恢复或启用地图。未知包／证书、缺失组件或原厂服务未实现协议时，保留原有 OEM SDK 路径，并明确仍需原厂高德自身准备，不能把回退当作已准备成功。

## 后台初始化与真实回读

1. 仪表设置启用且当前 CarPlay 会话建立后，`PrefaceInstrumentNavigation` 请求准备；此时没有导航字段也不会构造虚假导航或调用 SDK 的导航开始／更新。主应用校验固定高德包、证书、UID 和启用状态后，只使用 `BIND_AUTO_CREATE` 绑定固定服务组件。
2. 高德启动仍由原有 Application／Eaglet 链执行。包装固定 `g7.b()`，先完整执行原方法及其 `AutoLoader.n()`，随后只记录真实初始化完成事件。服务已绑定并不设置此状态。
3. 取得真实初始化回调后，通过固定 `a7.c().h(Context)` 请求原有后台工作流程，并等待 `a7.c().d()` 的实际完成状态。仅请求启动、服务存在或日志模板都不能代替完成。用户权限、原有环境或后台启动失败不会被跳过。
4. 在主线程取得真实 Display 1，保留有效性、开屏状态及内置／HDMI 类型检查，拒绝主屏、虚拟屏、无线或未知显示类型。窗口 1001 若已由其他窗口或高德真实导航持有，拒绝抢占。
5. 新背景租约的真实 Surface 绘制中文空闲画面“CarPlay 导航待命”，提交后以 PixelCopy 回读同一 Surface 并逐像素比对。当前窗口、Surface 代次、当前租约、Binder 存活及心跳均有效才走既有 `sendExScreenStatus(0,1001,1)`。这里报告的是**实际空闲画面的准备**，没有旧转向、距离、道路名、伪造地图、虚拟 Surface 或手工重放历史 116。
6. 空闲画面准备与导航内容分开：CarPlay 的箭头、距离及道路名仍由原厂 SDK 发送；高德自身导航仍要求新的真实字段和回读。准备通道不传输或保存用户导航内容，也不代表完整原厂地图引擎已经移植。

上述 SDK 和初始化入口依据固定 9.5 DEX 逐项核对，327 静态证据只用于理解原厂协议，不能冒充 169 运行结果。327 OTA SHA-256 为 `f61a7399d61a7f4502b9289ea6c49c661c39c459aae81e676722bcf9be913c01`；此前 169 的改版真实提示回读及系统准备 116 是已存在的条件成功样本，不是本轮空闲后台路径的验证。

## 租约、失效与释放

共用协议源码位于 `amap95-instrument/protocol/Preface95PreparationProtocol.java`，同时编译进主应用与高德候选，避免手工维护两套 wire 常量。主应用只接受已核对的高德证书，服务的每次事务均核对 `Binder.getCallingUid()` 对应的唯一包名及仓库原主证书；版本、正数会话、Binder token、递增序号及 Parcel 尾部均检查。签名材料公开的既有身份边界没有改变。

每个服务实例只接受一个活动租约；关闭后的会话号不能重新打开。客户端事务在独立串行线程执行，不在主线程等待远程 Binder。心跳每 500 毫秒一次，服务拒绝超过 3 秒的失联租约，不能用迟到心跳重新激活。后台初始化至多 20 秒，失败仅在下一次 CarPlay 会话重试。原厂 SDK 的原 5 秒连接窗口保留；只有新后台准备待完成时允许较长窗口。

取消、会话结束、客户端 Binder 死亡、解绑、服务销毁、心跳超时、初始化或回读失败均先同步使租约失效，阻止在途回读发布准备，再在主线程成对停止、关闭并移除本次窗口、释放 Display 监听及死亡监听。旧清理完成前保留 550 毫秒屏障；不停止其他应用、系统持有的高德服务或共享引擎。保留单个进程级 Activity 观察器，用于先前已修复的同进程重新打开地图恢复。

高德真实导航到来时，其导航优先，后台租约停止，不混入 CarPlay 字段；以后客户端的旧释放不能关闭新本地窗口。仅高德主 Activity 退出时，若 CarPlay 租约仍有效，后台 Surface 继续由租约持有。原地图退出协议可能清除原厂准备，此时必须重新提交并回读**新空闲帧**，才再次走原协议，不能忽略系统退出或沿用旧 ready 缓存。

## 定向验证

- 19 组宿主测试通过：原有 7 组退出重开场景，加 12 组后台初始化、工作完成等待、真实回读门槛、取消、死亡、心跳、销毁、本地导航优先、包／UID／证书、版本、序号、退休会话、替换清理屏障和失败场景。执行实际 Host Binder、身份校验及 NativeHints owner；Android、后台工作及 Surface 是替身。不是 Android 界面或实车验证。
- 主应用 Debug 定向单元测试共 43 项通过，0 失败／错误／跳过：后台客户端 7 项、仪表 backend 20 项、guidance 14 项、navigation 2 项。覆盖延迟回读、仅绑定不能 ready、原厂 Binder 不接收自定义事务、未知身份不绑定、取消后晚回调、失联释放、版本失败，以及没有导航时不发送 SDK 导航内容。
- Android Java 和 DEX 编译、厂商方法／字段引用及注入点核对通过。45,215 个原有方法体和异常范围保持一致，原 3 个有意注入方法另计；新增初始化、JSON 绑定和销毁使用原体保留包装，新增 `onUnbind` 前确认原类未实现同名方法。原 JSON Binder、启动和签名方法没有被替换成恒真结果。
- 未执行完整 CI、APK assemble、签名或安装，未打开车机地图或修改车辆／系统属性。

命令：

```powershell
python scripts/prepare_amap95_hint_dex.py
./gradlew.bat :shared:testDebugUnitTest --tests '*PrefaceInstrument*' --tests '*PrefaceAmapPreparationTest' --offline --no-daemon
```

环境复用本机缓存 JDK 25、Android SDK，并使用缓存 `javaOptions` 指定 `D:/Temp`；初次遗漏这些参数触发 Gradle 回环连接错误，补齐后通过。随后一项 backend 回归暴露失败连接被 `ready()` 再次触发的问题，已改为只恢复真正等待后台准备的连接；一项异步回退断言暴露通知与解绑入队的次序问题，也已修正并重跑上述定向范围。

本机报告：`agent_docs/amap-public95-instrument-20261030/hint-lifecycle-regression/verification.json`、`working-hints-candidate/verification.json`、`background-preparation/verification.json`；原厂／高德反编译、完整日志和输入只留忽略目录。实车验收应分别观察 Application 初始化、后台工作完成、`BACKGROUND_IDLE_BUFFER_READBACK_CONFIRMED_WINDOW_1001`、NaviService 116／`isReady=true`、CarPlay `firstFrame=1`、实际显示，以及断开后的停止；缺少后半段证据不能宣称“后台仪表已修好”。
