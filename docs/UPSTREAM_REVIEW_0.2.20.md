# DiPlay v0.2.20：星瑞 E02 选择性同步评估

评估日期：2026-10-15。此文保留同步前的评估判断；用户随后要求合并所列适用项，实际移植及验证结果见 [0.2.20 同步记录](UPSTREAM_SYNC_0.2.20.md)。

对比：

- v0.2.19：`e06a250219b2c2288948d8b4ad7203b417db0de1`。
- v0.2.20：`3c95c936859e8a9153f0040caf2337adddb9e3e4`。
- 本地 HEAD：`3b4f24548ec6ad011b4d50ffc988a1c2ac75bf07`，并纳入当前未提交的固定分辨率、Bonjour 清理等修改判断；不覆盖这些工作。
- 发布区间涉及 162 个文件。不是每个提交都适合 cherry-pick：不少提交混合通用修复、比亚迪功能、设置入口和文档。

依据为已抓取的两个发布标签、逐项源码对比及[上游正式发布说明](https://github.com/shihabal3amri/DiPlay/releases/tag/v0.2.20)。上游明确多项行为仍待发布包实车验收；其自动化结果不能替代本仓库的验证。

## 第一批：建议优先引入

| 项目 | 上游提交／范围 | 本地证据与必要性 | 移植边界 |
| --- | --- | --- | --- |
| Android 9 设置搜索崩溃 | [55877dba](https://github.com/shihabal3amri/DiPlay/commit/55877dba) | 本地 `DiPlayActivity.showSettingsSearchResults()` 仍无条件调用 Android 10 才公开的 `PopupWindow.setIsLaidOutInScreen()`；报告设备 API 28，直接命中适用系统。优先级最高。 | 仅移植 API 判断及旧接口受保护调用，保留现有搜索布局。 |
| USBMUX VERSION 回包尾部填充解析 | [19649861](https://github.com/shihabal3amri/DiPlay/commit/19649861) 的传输层部分 | 本地 `UsbMuxFrameBuffer` 只恢复 TCP／诊断包前的填充，缺少 VERSION 精确头匹配；可避免另一类 `Invalid USBMUX frame length 0`。最新报告没出现这个错误，不能冒充本次 USB 根因。 | 保持长度、协议和 VERSION word 的精确校验及不完整包等待，不能泛化为丢弃任意四字节。首页标题变更不与之捆绑。 |
| USB 配置切换时内核驱动重新绑定 | [bcc5013a](https://github.com/shihabal3amri/DiPlay/commit/bcc5013a) 的 `IphoneUsbHost`／`UsbConfigurationSelection` 部分 | 本地仍在强制 claim 后立即 `releaseInterface()`；上游改为维持 claim，关闭并重新打开连接后再选配置，避免 release 重新挂载驱动。是控制层真实缺陷修正。 | 仅限原本配置切换失败的恢复分支；正确替换和关闭连接句柄，保留授权、重枚举、系统 NCM 归属与 raw 诊断边界，不抢占正常 NCM 会话。 |
| 同一 USB 枚举上的再次连接 | [5e8a6f9a](https://github.com/shihabal3amri/DiPlay/commit/5e8a6f9a) | 新增 `WiredSessionMemory`：本进程曾在同一设备节点发送 StartSession 时，再次请求 CarPlay 模式，避免复用旧模式后仅认证成功、没有 AirPlay TCP。本地尚无该记忆。 | 不是首次插线无 TCP 的通用修复，也不覆盖进程重启后的历史。保留重试上限、拒绝后的受限恢复，核对 USB 拔出／重枚举和原始诊断后的归属。不能带回上游 VPN 路径。 |
| 语音消息无下行时启动麦克风，以及空读恢复 | [9647d689](https://github.com/shihabal3amri/DiPlay/commit/9647d689) | 本地麦克风启动仍依赖首个下行音频包；`AudioRecord.read()==0` 时直接继续，缺少空读退避和超时重启。上游对非通话流增加 500 ms 延迟启动，对持续 1 秒无音频最多重启三次，并避免紧循环。 | 保留流／会话归属与取消保护；通话继续等下行，沿用本仓库星瑞的录音 source、AEC 和现有读错误恢复，不能混入别车型麦克风 source 调整。 |
| 音频线程优先级 | [656577fe](https://github.com/shihabal3amri/DiPlay/commit/656577fe) 的三处线程设置 | 本地接收、播放、录音工作线程尚未设置 `THREAD_PRIORITY_AUDIO`。可降低倒车／全景界面抢占时的音频调度延迟，是低侵入的通用修正。 | 只修改实际音频线程；保留失败保护。不合入同提交的 Wi-Fi Direct 或比亚迪蓝牙暂停行为；不能解决原厂音源接管失败。 |
| HEVC 无硬件解码时退回 H.264 | [f5366138](https://github.com/shihabal3amri/DiPlay/commit/f5366138) | 本地没有 `HevcSupport` 协商保护，仍可能将 HEVC 偏好直接用于配置。对开启 HEVC 的用户有保护价值；最新报告使用 H.264，非其当前故障原因。 | 根据实际解码器能力选择，默认仍保留本地 H.264；不要整体替换已固定为 1920×720／1760×720 的配置方法，也不重新启用画布缩放。 |
| 进程崩溃记录 | [5b6dead5](https://github.com/shihabal3amri/DiPlay/commit/5b6dead5) | 本地缺少 `CrashRecord`；车机 Android 9 不提供 Android 11 的进程退出查询。新增未捕获异常记录可补关键证据。 | Provider 不导出，保留原异常处理器，报告继续脱敏、限制记录大小；不能捕获死锁或所有 native／系统杀进程，不把它称为重连修复。 |

这批应按功能抽取代码及相应测试，而不是整块替换 Controller、Activity 或 AndroidMediaSink。

## 第二批：有价值，但独立验收或按需要补充

| 项目 | 上游提交 | 判断 |
| --- | --- | --- |
| 音乐时钟漂移补偿 | [dfd7c3d6](https://github.com/shihabal3amri/DiPlay/commit/dfd7c3d6) | 建议作为单独的音频稳定性批次。根据 AudioTrack 队列调整实际播放速率，幅度最多 ±1.5%，拒绝时关闭补偿。本地缺少该逻辑。要检查长期播放、暂停／切歌、重缓冲、队列读取与路由切换；保持仅真实媒体流生效，不能因自定义通道映射而给通话／Siri／导航调速。最新报告的 iPhone playing=false、原车蓝牙抢音源不等于时钟漂移，不能以此替代原厂媒体调查。 |
| 无线／USB 独立音乐缓冲 | [7d991396](https://github.com/shihabal3amri/DiPlay/commit/7d991396) | 可选体验改进。USB 增加 50–200 ms 等短档位，当前共用 300／500／1000 ms。优先等 USB 能实际建立会话后验收；无线继续保守默认，短档实际延迟仍受设备最小 AudioTrack 容量限制。 |
| 手动热点启动兼容 | [d8e688d3](https://github.com/shihabal3amri/DiPlay/commit/d8e688d3)、[c55241e3](https://github.com/shihabal3amri/DiPlay/commit/c55241e3)，以及 bcc5013a 的 shell helper | 对本地 Android 9 手动车机热点有相关性：通过接口优先查找、受保护访问、有限 hidden API exemption 增加可达调用，已授权 ADB 时可用 shell helper。建议仅取星瑞所需通用启动部分；本地已有选择手动模式时尝试开启和权限提示。不能带回品牌启用门槛、自动关闭热点或修改配置，也不照搬 Android 11+ 专属支持。加入 exemption 时必须包含 c55241e3 的收窄修订，不能使用早期全量 `L` 豁免。 |
| “系统报告热点关”仍可连接 | [ab087735](https://github.com/shihabal3amri/DiPlay/commit/ab087735) | 暂不直接引入。其问题是上游对话框循环，本地已有独立 `ManualHotspotManager` 归属／接口验证。可借鉴避免单一状态误报阻塞，但必须保留实际接口、权限、地址稳定和会话检查，不能靠一个持久化忽略标记认定热点可用。 |
| 手机报告／备份 HTTP 服务退出 | [67612809](https://github.com/shihabal3amri/DiPlay/commit/67612809) 的 `SettingsBackupServer` 部分 | 小修可顺带引入：accept 250 ms 轮询，独立累计十分钟空闲超时。本地仍将 accept 超时设为完整空闲时间。上游针对 macOS JVM 阻塞测试，不能当成 Android 上 Bonjour 清理问题的修复。 |
| 音频重缓冲事件、vocoderInfo | [e3b6c58c](https://github.com/shihabal3amri/DiPlay/commit/e3b6c58c)、[0abc0712](https://github.com/shihabal3amri/DiPlay/commit/0abc0712)，656577fe 部分 | 可顺带补充短暂断音证据；每流最多五条事件，再保留累计计数。vocoderInfo 应明确格式、长度与脱敏，不能直接导出未经审查的任意对象文本。 |
| 原始按键历史／蓝牙失败分类 | [d713087d](https://github.com/shihabal3amri/DiPlay/commit/d713087d)、[7544723c](https://github.com/shihabal3amri/DiPlay/commit/7544723c)、[26d2154c](https://github.com/shihabal3amri/DiPlay/commit/26d2154c)、[799693af](https://github.com/shihabal3amri/DiPlay/commit/799693af) | 只补本地缺口。当前已有物理 304/305 接收、命令转发、广播去重、焦点、原厂 SDK 和蓝牙接收端诊断，上游 MediaSession 历史有部分重复。可补有界原始键历史与“socket manager 缺失”停止重试分类，但不替换本地媒体转发／去重实现。这些检查是能力与运行状态判断，不是新车型分支。 |
| 连接等待页跟随应用主题 | [536415ad](https://github.com/shihabal3amri/DiPlay/commit/536415ad) | 本地等待页仍使用 `darkMode`，上游用 `appNight`，可小范围合入统一主题。保留本地已有外观刷新，不搬整个等待页。 |
| 通话被路由至听筒时改走扬声器 | [333e20fc](https://github.com/shihabal3amri/DiPlay/commit/333e20fc) | 当前报告没有通话 routeType=1 的证据，暂非必需。如实车出现通话无声，可仅引入实际听筒路由检测及结束恢复；不新增手机／平板专属设置，不强制改变所有通话路线。 |

## 明确不引入的部分

- 比亚迪仪表、DiLink 控制器 13／17、豹 5 仪表图层、HUD 网关重试、氛围灯、比亚迪 Siri 双键及仅长按设置。本仓库已移除仪表地图，不恢复这些功能。
- Wi-Fi Direct 频段、搜索暂停和同局域网相关能力。本地无线仅临时热点／手动车机热点二选一。
- 导航 −20 至 0 dB 的应用内衰减滑块（db3d3bd2、6648f6dc 等）。与本仓库导航流 12、音量交给车机且不提供应用内音量比例的既定方案冲突。
- Han 的 VOICE_RECOGNITION 通话 source（598a82f0）。上游本身对 Geely／Lynk 保留原 source；本地星瑞默认 VOICE_COMMUNICATION，不引入车型识别门槛或其它车型 source 分支。
- 手机／平板“通话用扬声器”独立设置；Raspberry Pi／FFmpeg Codec2 专属输出路径；仅服务 Android 17 的 USB 本地网络权限流程。报告目标 API 28，不据这些变更扩大适配范围。
- 整体替换下滑菜单／设置架构、重新加入设置向导、Overview、多语言、上游网站或上游版本号。分组设计可以参考，但本地已按目标重做设置，不是修复当前连接问题的必要条件。
- 默认本地认证下，CH341 等待时 USB 列表和 CH341 选择入口不是当前优先事项。也不替换本仓库认证资料与签名。

## 对当前实车问题的实际帮助

1. **原厂媒体 API ready=false、方控跳页：没有直接修复。**发布区间没有新增针对当前 `PrefaceMediaCenter` 注册准入的解决方案。音乐优先级和时钟补偿不等于取得原厂音源。
2. **USB NCM 无 AirPlay：有值得引入的前置／重连修复，不能保证解决本次故障。**最新报告已完成认证、系统 usb0 与 IPv6 ready，并有邻居控制通信；USBMUX 解析和初次配置切换不是日志中的失败阶段。WiredSessionMemory 只在同进程同枚举已发 StartSession 的条件下相关。发布区间没有补出应用 TCP/IP 栈或解决本仓库原始 NCM OUT=-1 的具体修复。
3. **无线断线后清理不结束：上游没有 Bonjour 修复。**两个标签间 `CarPlayBonjour.kt` 无差异；本地新做的 socket 确认释放／后台收尾及等待屏障必须保留。上游“USB 保存后重连”不能替代无线清理修正。
4. **首次临时热点自动中断：保留本地固定画布方案。**任何合入不得覆盖“隐藏导航栏=1920×720／显示=1760×720”和临时窗口变化不重建连接的修改。

## 合并时应如何执行

先按第一批逐功能移植并运行对应定向测试，覆盖 Android 9 搜索、USB 连接句柄与重枚举、同枚举再次连接／拒绝恢复、麦克风启动取消／空读重启、音频线程和 HEVC 实际选择。音乐时钟补偿、缓冲及手动热点兼容独立验证，避免同时修改所有音频与连接路径后无法归因。

真正同步时再按仓库规则更新 README、AGENTS 上游基线和同步清单，递增当前 `versionCode`，保留开发 `versionName = "0.1.1"`。只分析本版本不应将“基线已更新至 0.2.20”写入正式记录。评估阶段没有运行测试、构建、合并、提交或推送；后续执行状态以同步记录为准。
