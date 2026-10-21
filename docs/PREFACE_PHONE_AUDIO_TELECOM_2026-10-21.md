# 车机电话音频实验选项：权限与通话准入分析

2026-10-21，用户针对构建 1205 在 11:22:53 的 `CREATE_CONNECTION_FAILED` 询问是权限问题还是固件不支持。结合原始车机日志、应用实现和用户提供的参考固件继续分析。本次只补充证据和文档，没有修改运行代码、构建、签名、安装或更改车机授权。

结论：**这次失败高度符合系统已有 HFP 拨号状态记录时，Telecom 拒绝再创建辅助通话的准入规则；没有发现缺少录音权限或 MANAGE_OWN_CALLS 的证据。参考固件保留自管理通话实现，不能将单次拒绝归为系统完全不支持。** HFP 记录可以是同一部 iPhone 通话的状态同步，不代表用户通过原车另行拨打了第二通电话，也不证明原车 SCO 音频已接管。但该实验选项在本次通话中未成功生效，触发成功后是否能改善星瑞录音也尚未验证。

## 固件来源与复核范围

参考资料为 IHU519G／FS11A1／Android 9／构建 327，OTA SHA-256 为 `f61a7399d61a7f4502b9289ea6c49c661c39c459aae81e676722bcf9be913c01`。复用已重建的本地 `system.img`，只提取 Telecom APK 与对应 ODEX／VDEX；原厂二进制、完整反编译和原始日志均留在忽略目录。

| 原厂文件 | 字节 | SHA-256 |
| --- | --- | --- |
| system/priv-app/Telecom/Telecom.apk | 5,040,192 | 21c06b7a60f71e5eb60e55c39ed91fa21b870633f5f4f4d39e7dfa22f8fed5c5 |
| APK 内 classes.dex | 2,155,888 | 699c7bb80998b359275aabaa7a91ac61cee45563bd916de1c2c83de93d7e5bab |
| system/priv-app/Telecom/oat/arm64/Telecom.vdex | 26,254 | 8c78559ddd5729e39b2343d4ac1f45f7138b8c9eb9c81d7a0c8629ba93d675a8 |
| system/priv-app/Telecom/oat/arm64/Telecom.odex | 4,606,040 | ab394ed7fe418ba5a283b5a5c12a44e0a04f9987282bebaf7d9b8a8bc746082f |
| system/framework/framework-res.apk | 31,146,655 | 166741d4e79dd8084f5850941c09f28f93d2ba5c9567f28ebb938d338d5787db |

缓存根目录为 `agent_docs/firmware/ihu519g-327/`；专项输出为 `catalog/telecom-analysis-20261021/`，反编译位于 `decompiled/telecom/sources/com/android/server/telecom/`。

JADX 对整个 APK 报告 2 个错误，其中存在 PhoneAccountRegistrar XML 反序列化方法的代码生成失败；本次不使用该失败方法作证据。另直接解析原 APK 的标准 DEX，复核 12 个相关方法的指令边界、条件跳转目标和调用；关键准入分支与反编译吻合。DEX Adler32 匹配，原始 DEX 的内置 SHA-1 字段与重算值不一致，保留原字节并单独记录，未“修复”或使用该字段作为来源认证；来源以从参考镜像提取的 APK／DEX SHA-256 记录为准。

复核脚本为本地 `agent_docs/firmware/inspect_telecom_admission_20261021.py`，输出 `source-hashes.json`、`dex-header-validation.json`、`dex-admission-instructions.json`／`.txt`。没有运行固件内程序。

本次实际日志来自 **IHU353G／系统 169**，并非上述构建 327。尚未取得实际车机的 Telecom.apk 作字节对照，因此以下“命中拒绝规则”属于参考固件与实际时序高度吻合的推断，不能宣称两个系统实现逐字节一致或全部星瑞固件已验证。

## 权限检查与功能实现

| 检查点 | 参考固件的实际实现 | 本次运行观察 |
| --- | --- | --- |
| MANAGE_OWN_CALLS | framework manifest 解码第 1214–1216 行定义 protectionLevel=0，即普通权限 | 主应用 shared manifest 已声明；实际成功注册 self-managed 账户并进入创建流程，没有相应 SecurityException |
| 注册自管理账户 | TelecomServiceImpl.java 第 420–442 行，对 self-managed 账户强制检查 MANAGE_OWN_CALLS；enforceRegisterSelfManaged 在第 1537–1538 行 | 11:22:53.461 已记录 New phone account registered |
| 拨出自管理辅助通话 | TelecomServiceImpl.java 第 1126–1134 行再次检查 MANAGE_OWN_CALLS 和账户归属 | 实际进入 CallsManager 并执行 makeRoomForOutgoingCall，未记录权限拒绝 |
| 服务绑定权限 | PhoneAccountRegistrar.java 第 633–638 行要求 ConnectionService 声明 BIND_TELECOM_CONNECTION_SERVICE 保护 | 应用服务已声明对应保护，系统在 11:22:53.513 成功绑定；此权限用于限制谁能绑定服务，不是要求普通应用取得系统签名权限 |
| 账户启用 | PhoneAccountRegistrar.java 第 657–666 行自动启用 self-managed 账户 | 注册日志在启用写入前输出，日志中的初始 `[ ]` 不能单独解释为需要用户另行打开账户授权 |

因此没有依据通过新增主应用系统电话权限、换签名或 ADB 改 app-op 解决这次拒绝。Android 普通录音路径也已启动并持续读到数据，见 [构建 1205 报告](CONNECTION_REPORT_2026-10-21_BUILD1205.md)。

## 与这次失败吻合的准入分支

参考固件 CallsManager.java：

1. 第 110–111 行的 OUTGOING_CALL_STATES／LIVE_CALL_STATES 均包含拨号状态 3；CallState.java 第 15–16 行将 3 映射为 DIALING。
2. makeRoomForOutgoingCall 第 1860–1883 行检查现有活动／拨出通话。已有另一通拨号、且新请求不是紧急通话时，若原通话不处于选账户状态 2，返回不准入。原车已经处于 DIALING 状态 3，符合这一条件。
3. startOutgoingCall 第 855–866 行在不准入且前台是普通受管通话时调用 notifyCreateConnectionFailed。
4. notifyCreateConnectionFailed 第 2270–2279 行找到应用服务，ConnectionServiceWrapper.java 第 1064–1077 行在绑定成功后写入 CREATE_CONNECTION_FAILED，并向应用发出创建失败回调。**这次绑定是为了通知失败，不代表辅助通话曾成功创建。**

标准 DEX 复核：makeRoomForOutgoingCall 的 code_item 偏移为 1024484；unit 47 调用 hasMaximumOutgoingCalls，unit 85 调用原通话 getState，unit 89 设置比较值 2，unit 90 在不相等时跳到 unit 110 返回此前设置的 false。startOutgoingCall 的 code_item 偏移为 1027280，unit 271 调用该准入方法，失败路径 unit 311 调用 notifyCreateConnectionFailed。上述偏移均相对于 APK 原始 classes.dex，不是 ODEX 偏移。

另有 isOutgoingCallPermitted 第 2075–2095 行的自管理通话准入检查：紧急通话、达到数量上限或当前焦点通话不能保持时返回 false。不能将 CAPABILITY_SUPPORT_HOLD（支持保持）直接等同于 CAPABILITY_HOLD（当前可保持）。本次更直接的证据是 makeRoomForOutgoingCall 的拨号状态限制，不需要假定已发生 SCO 音频占用。

实际日志 `sys_log_10__2026_1021_112506` 的对应时序：

| 时间／行号 | 事件 |
| --- | --- |
| 11:22:53.388，第 63423 行 | 原车 HFP 通话进入 DIALING |
| 11:22:53.461，第 63455 行 | 本应用注册 SelfManaged 电话账户 |
| 11:22:53.501，第 63472 行 | makeRoomForOutgoingCall 明确列出新辅助通话和已在 DIALING 的原车 HFP 通话 |
| 11:22:53.513，第 63478–63480 行 | 应用服务绑定成功，随后 CREATE_CONNECTION_FAILED |
| 11:22:53.522，第 63421 行 | 应用 ConnectionService 收到 createConnectionFailed 回调 |

应用另在 main_log_7__2026_1021_112506 第 79934、79950 行记录未创建辅助通话和车机电话音频路径被拒绝。原始来源、哈希与缓存见构建 1205 报告。

### 用户补充：原车未进行通话，直接出现拨号界面

用户进一步明确，原车没有另行进行通话，而是直接打开了拨号界面。此前将 HFP 状态口语化为“原车另有一通电话”容易混淆，现修正为**系统识别到一个 HFP 电话状态记录**。该记录可以来自同一部 iPhone 的 CarPlay 通话同步；原车电话页出现、系统状态、真实拨号行为及 HFP／SCO 实际音频路由必须分别判断，不能相互代替。

补查 main_log_7__2026_1021_112506 第 79520 行：11:22:53.227 底层先收到 HFP callsetup=2；第 80479 行在 11:22:53.356 上报 DIALING。辅助账户注册／失败稍后才发生在 11:22:53.461／53.513，第 80845 行于 11:22:53.541 已有原车 CallingActivity 更新。这一时序支持“本段 HFP 状态早于这次辅助请求”，不支持把更早的 HFP 状态直接归因于后来已失败的辅助请求。11:22:54.018 系统又将同一 HFP 记录置为 ACTIVE，但这仍只是日志中的状态，不单独断言用户发起了另一通电话。

用户随后明确操作顺序：此前按了一下方向盘电话键，原车弹出拨号界面，之后未继续操作。已找到相符记录：main_log_5__2026_1021_111655 第 63517、63803 行在 11:13:48.675／48.809 记录 299 DOWN／UP；第 64046 行 11:13:48.811 发出 RCALL，第 64268 行原车电话接收；sys_log_10__2026_1021_112506 第 58736 行 11:13:48.824 启动 com.ecarx.btphone/.ui.MainActivity，随后 main 第 65100、66026 行创建 DialerFragment／DialpadFragment。此页面来源是电话键，不应归因于后来开启实验选项。

应用报告第 1706 行在 11:13:53.789、1853 行在 11:14:44.640 均采样到 mode=0、scoOn=false；不能把仅打开拨号页描述成原车持续持有通话模式，也不能据此解释九分钟后的辅助请求失败。后面的 11:22:53 HFP 状态记录须独立判断，可以对应同一部 iPhone 的通话同步。现有日志仍未记录点击实验开关的精确时间，用户也未确认“完全无任何手机呼叫、只开实验开关即弹页”的场景。补查摘录仅留本机 `agent_docs/vehicle-20261021-build1205/phone-wheel-dialer-evidence.txt`、`dialer-clarification-evidence.txt` 与 `dialer-ui-events.txt`。

## 与麦克风问题的关系及下一步

“CarPlay 通话使用车机电话音频（实验性）”通过 Telecom 创建一条不承载声音的本地自管理辅助通话，尝试触发车机电话音频状态。应用实现见 `TelecomPhoneAudioBackend.kt` 的 place 与 PhoneAudioConnection；它没有音频采集、编码或网络通话实现。该选项不负责授予 RECORD_AUDIO，也不是物理麦克风选择开关。

普通 CarPlay 通话仍通过 AudioRecord 采集车机内置输入并上传。实验请求失败不等于车机麦克风不能使用；反过来，自管理机制存在或未来请求成功，也不证明能取得正确原厂语音处理。此次低电平在开启选项之前的 USB 通话已出现，须独立检查音频模式交接、实际输入与处理前后电平。

用户后续明确，已对照测试两种音频方案，结果是**蓝牙音频通话正常，CarPlay 音频通话对方听不到或声音小**。补查确认蓝牙方案的 11:20:15–35 时段启用了原厂 HFP AudioOn，本应用未启动 CarPlay telephony 录音；此前三段低电平统计均来自 CarPlay 方案。该结果将问题重点收敛到 CarPlay 采集／处理／路由／上传链路，不能把 USB／无线两种连接方式误写成两种音频方案均异常。完整分组及车机 HFP 实际音频证据见构建 1205 报告。

该轮分析时仍保持实验选项默认关闭。若后续修改请求策略，实现应先核对当前原车电话状态与系统是否准入，避免在原车拨号时再创建辅助通话；不能为取得准入去挂断／保持原车电话或修改 HFP／SCO。仅延后请求也不能视为修复，因为通话接通后仍有保持／焦点限制，且当时处理效果未经实车验证。麦克风问题的下一步仍是对照原始与处理后电平并复核路由，而非增加授权类别或直接放大增益。

## 后续配置调整：移除实验性标志，默认开启

2026-10-21，用户在核对 [1206 实车记录](CONNECTION_REPORT_2026-10-21_BUILD1206_MICROPHONE.md) 后明确要求：“CarPlay 通话使用车机电话音频”去除实验性标志并默认开启。源码已调整，现有 1206 APK 不包含本节默认值及标题变化；本次未构建、签名或安装 APK。

- `PhoneAudioSettings.phoneAudioRoute()` 仅将未保存 `carplay` 值的读取默认值改为 true，显式保存的 false／true 均继续优先，不写入迁移值、不按设备型号或固件识别启用。
- 设置保留在“高级”，移到普通选项组；中文与默认资源同步移除实验性后缀，并将说明更新为默认开启、下次通话生效。
- “通话回声消除”“更清晰的通话人声”“其他应用的 VoIP 通话使用车机电话音频”继续默认关闭并保留实验性标志。使用车机音频处理策略时，仍避免叠加应用自身的回声消除。
- Telecom 权限／服务、准入观察、创建结果、失败退避和释放流程均不变；本次只改变默认读取、展示和说明，不主动创建辅助通话或重连。实际通话事件仍按现有流程请求，系统允许创建不等于音质改善已验证。

本轮执行资源 XML／事实索引 JSON 解析和改动差异检查，不新增或运行单元测试、不运行 Gradle 构建或完整 CI。此前 1205 的拒绝和 1206 的创建成功继续作为各自版本的历史事实。

## 后续补充：按当前会话音频方案判断

同日用户进一步要求区分 CarPlay 音频和蓝牙音频。此前代码由 iPhone 通话状态直接触发辅助通话，没有判断当前音频方案；1206 日志中蓝牙音频时也存在辅助通话创建成功，不能将该历史版本描述为已有本节判断。

- `CarPlayController` 将本次连接固定的 `usesCarBluetoothAudio` 随通话状态传给 `NavigationOutputs`／`PhoneAudioRoute`，不读取设置页待下次连接生效的音频方案值。保存新方案不会改变当前通话。
- CarPlay 音频方案继续受本选项、Telecom 权限／准入和失败退避约束；蓝牙音频方案不因 CarPlay 的 iPhone 通话状态创建辅助通话，应用的车机处理策略也返回 false，原车 HFP／SCO 不作改动。
- 蓝牙音频通话仍更新显示和原始 iPhone 通话状态，不把“无需辅助通话”误报成“没有通话”。会话结束继续清除请求并按原有延迟释放；新蓝牙会话不会延长前一 CarPlay 会话的辅助通话。
- 队列中的旧请求执行前重查当前音频方案；其他应用 VoIP 的独立开关和请求理由不受此判断改变，其他应用的 VoIP 选项继续默认关闭。

新增定向验证覆盖蓝牙模式拨号／接通／来电状态、设置变更不得绕过蓝牙判断、切回 CarPlay、新蓝牙会话释放旧请求、排队请求重查、其他应用独立使用，以及真实通话状态入口和退出清理。共 **86 项定向单元测试通过**：shared 69 项、common 17 项；同时完成相关源码编译，未执行全量单元测试、Lint 或完整 CI。本地命令输出、分组结果及汇总在 `agent_docs/phone-audio-mode-20261021/`。本节修改尚未构建、签名、安装 APK 或实车验证，不改变 1206 已交付包的行为。
