# DiPlay v0.2.22 选择性同步

2026-10-29，按用户要求合入适用于 2023 款星瑞 E02 的必要改动。固定来源为 [上游 v0.2.22 预发布](https://github.com/shihabal3amri/DiPlay/releases/tag/v0.2.22)，提交 **`28d6002ba00320f0ba745212160b08759e4f60b9`**，发布日期 2026-10-28。比较范围为 v0.2.21（`49bf3bc2b011cb5ee7c7f4da9b99b18cbd3a23a2`）至该标签；此前仅选择性移植的范围不会因此变成整版本同步。

应用 `versionName` 保持 **0.2.2**，mobile／automotive 的源码 `versionCode` 从 **1210** 增至 **1211**。源码同步阶段未构建、签名或安装 APK。随后按用户明确打包要求，已交付 0.2.2／1211 Debug，包含本次同步及此前仪表分项补齐；主签名和内置授权组件核验通过，无在线设备可安装，见 [1211 构建记录](ANDROID9_AUTHORIZATION_COMPONENTS.md#0221211-测试包构建核验)。旧 1210 不包含这些改动。

## 合入范围

以下以固定标签的最终源码为准，按本地结构移植，不整分支合并或覆盖星瑞实现。

| 改动 | 上游来源 | 本地处理 |
| --- | --- | --- |
| 音乐时钟的时间平均与目标学习 | [#993](https://github.com/shihabal3amri/DiPlay/pull/993)、[#1070](https://github.com/shihabal3amri/DiPlay/pull/1070)、[#1080](https://github.com/shihabal3amri/DiPlay/pull/1080) | `MediaClockTrim` 按时间平均队列水位；本地拆出的 `AudioRenderer` 记录包到达时刻，计入阻塞写入期间的到达量，播放／丢包后重新学习目标。仍只对真实音乐流且映射到 MEDIA 时调速，保留 ±1.5% 上限、压缩积压保护和平台拒绝退出。 |
| USB PCM 麦克风分包与时钟名称 | [#1148](https://github.com/shihabal3amri/DiPlay/pull/1148)、[#963](https://github.com/shihabal3amri/DiPlay/pull/963) | iPhone 未指定分包长度时，按包含加密、IPv6、UDP 开销的 1500 字节预算选择 20／10／5 ms；48 kHz 单声道选 10 ms，Opus 与显式协商不变；诊断使用实际 RTP 时钟。 |
| USB 网络晚就绪后的单次启动恢复 | [#962](https://github.com/shihabal3amri/DiPlay/pull/962)、[#1057](https://github.com/shihabal3amri/DiPlay/pull/1057) | 移植 `WiredStartAgain` 和只读头部的 NCM 流量计数，接入本地 NCM＋VPN 与 `WiredStartupDeadline`，保留现有授权、身份和清理逻辑，规则见下文。 |
| Android 8／9 TCP 断线检测 | [#1042](https://github.com/shihabal3amri/DiPlay/pull/1042) | API 26–28 直接调节 socket 原描述符，避免关闭会接管原描述符的包装器；失败保留连接及原因，不将空闲视频当断线。 |
| mDNS 启动避免反向 DNS | [#972](https://github.com/shihabal3amri/DiPlay/pull/972) | 为本地地址预置主机名并保留 IPv6 scope；保留已有 Bonjour 取消、关闭与清理屏障。 |
| 多指稳定槽位、移动队列合并、延迟构造 TRACE | [#912](https://github.com/shihabal3amri/DiPlay/pull/912)、[#1134](https://github.com/shihabal3amri/DiPlay/pull/1134)、[#1131](https://github.com/shihabal3amri/DiPlay/pull/1131) | 使用 Android pointer ID 跟踪同一手指，抬起保留坐标；只合并同会话／同屏幕身份下尚未发送的连续移动，按下和抬起保持顺序。报告拒绝 TRACE 时不构造十六进制内容；Release 仍关闭详细日志。 |
| 协议输入边界 | [#1122](https://github.com/shihabal3amri/DiPlay/pull/1122)、[#1125](https://github.com/shihabal3amri/DiPlay/pull/1125)、[#1126](https://github.com/shihabal3amri/DiPlay/pull/1126)、[#1128](https://github.com/shihabal3amri/DiPlay/pull/1128) | 限制 RTSP 头／体、iAP 包、Bonjour 状态行、bplist 嵌套／工作量／展开量和 NCM 数据报条目，畸形输入结束对应连接或丢弃对应块。 |
| 绑定控制会话的辅助通道 | [#1123](https://github.com/shihabal3amri/DiPlay/pull/1123) | 屏幕、iAP、视频设置、事件及 NTP 响应仅接受当前控制会话的 iPhone 地址；保留本地会话身份、监听释放和首帧回调检查。 |
| USB 权限结果保护 | [#1129](https://github.com/shihabal3amri/DiPlay/pull/1129) | 声明 `${applicationId}.USB_PERMISSION_RESULT` 签名权限；旧系统动态结果接收器要求本应用签名身份，Android 13+ 保留非导出接收。保护已有 iPhone／CH341 接收代码，不新增 CH341 连接入口或授权类别。 |
| 远程 MFI 的明文边界 | [#1127](https://github.com/shihabal3amri/DiPlay/pull/1127) | 公网服务器要求 HTTPS，私网允许 HTTP；本仓库默认认证资料、身份及签名不变。 |
| 更新下载保护 | [#1143](https://github.com/shihabal3amri/DiPlay/pull/1143) | APK 与校验文件须为 GitHub HTTPS 地址，文件名不得含路径，下载上限 64 MiB；更新源仍为 `milesmou/CarPlay-Preface`。 |
| 配置导入与导出 Activity 入口 | [#1136](https://github.com/shihabal3amri/DiPlay/pull/1136)、[#1138](https://github.com/shihabal3amri/DiPlay/pull/1138) | 备份仅恢复已知画面键及本地电话音频／仪表／方控键的正确类型；蓝牙唤醒增加进程内随机令牌并保留取消代际、目标手机、事件时效、USB 优先级；外部 Intent 不得打开会启动 HTTP 服务的备份页。 |
| 诊断脱敏、USB 日志限量、备份端口释放、开机 Intent | [#1141](https://github.com/shihabal3amri/DiPlay/pull/1141)、[#1144](https://github.com/shihabal3amri/DiPlay/pull/1144)、[#932](https://github.com/shihabal3amri/DiPlay/pull/932)、[#1085](https://github.com/shihabal3amri/DiPlay/pull/1085) | 补齐带连字符 USB 标识脱敏、限制 USBMUX ACK／大包日志；关闭备份等待监听线程退出；开机启动 Intent 补 MAIN／LAUNCHER 形态，复用现有返回 CarPlay 逻辑。 |

## USB 的有限恢复

仅使用当前 iPhone、当前 NCM／VPN 和当前控制通道。首次完整 bulk OUT 被 USB 接受的时刻用于判断链路可写，不把它描述为手机确认收包。如果链路在首个 StartSession 之前就已打开，不补发。链路在之后打开且静默满 2 秒、未观察到向本次 AirPlay 端口发起的 TCP SYN、会话未建立、控制器和 VPN 仍有效时，最多主动补发一次 StartSession。TCP SYN 观察只读取 Ethernet／IP／TCP 头部并有端口数上限，不记录导航或用户数据。

正常准入窗口仍为 30 秒；仅当这一次补发仍可能发生时允许初始窗口延至 45 秒。成功补发后获得一次新的 30 秒窗口，普通重复通知不能延长窗口，过期后不复活，已连接会话没有启动超时。准入计时上限为初始 45 秒加补发后 30 秒；检查复用既有控制循环，正在进行的 USB I/O 仍受各自超时约束。授权拒绝、VPN／TUN 组件缺失、桥接失败仍明确停止，不据此创建新 VPN、重新授权或循环重建连接。

## 保留与排除

- 保留星瑞固定 1920×720／1760×720 协商像素、现有中文界面及密度选项、临时热点／手动车机热点二选一、原厂媒体中心／方控／电话音频，以及仪表 SDK 的合并加分项发送。没有修改车型判断、证书、授权组件、媒体授权输入或运行认证资源。
- 本地通话结束释放音频模式、异步有界诊断写入、旧系统热点归属未知状态保护已覆盖相应问题，继续保留本地实现；不引入上游将有线网卡作为热点的回退，本地不据此选择蜂窝网卡。其他重复修正按现有代码处理。
- 不引入其他车型的仪表地图／飞屏／氛围灯／音乐卡、其他车型的 ECARX 车速与档位适配、Wi-Fi Direct／同 LAN、导航抢焦点、通话原采样率、同任务启动新选项、其他语言、设置搜索、整套首页和新失败分类界面。车道消息声明及尚无星瑞消费端的输出不新增。
- 上游 80 ms 手势预判等与本地 `CarPlayTouchController`、窗口重建和菜单结构交织的 UI 改动未整段移入；本次触控修正针对 HID 槽位与发送队列。地图嵌入服务、其他车型工具和相关权限入口未恢复。

## 验证与交付边界

使用缓存 JDK／SDK，按所改功能筛选 shared／common 的单元测试，包含上游恶意输入、辅助连接来源、多指及队列、PCM 包长、音乐到达／阻塞写模型，以及本地 USB、音频混音／焦点、仪表导航、配置与蓝牙唤醒回归。测试模型和上游车机时序不代表星瑞实车结果。

Debug **431** 项、Release **302** 项定向测试全部通过，均无失败、错误或跳过；Release 同时验证详细日志过滤、关键状态保留以及音频／仪表业务继续执行。公开文件检查 `python scripts/check_public_tree.py` 和忽略行末差异的 `git diff -w --check` 通过。定向测试的最终分组、结果和选用源码位于本地忽略目录 `agent_docs/upstream-0.2.22/`；完整测试、Lint、APK 构建、签名和安装均未在源码同步阶段执行；随后的 1211 打包仅运行主应用 assembleDebug 及其组件依赖，没有追加单元测试、主应用全量 Lint 或完整 CI，也未安装。后续实车应分别验证 USB 重连及语音消息、无线长时间音乐与拔线／断网、多指地图、后台返回及仪表提示；不把本次移植描述为 IHU353G／169 的上述问题已实车修复。
