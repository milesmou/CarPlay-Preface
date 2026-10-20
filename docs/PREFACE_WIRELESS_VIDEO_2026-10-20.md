# 无线画质与流畅度：固件证据和试验顺序

2026-10-20，初始分析仅补充记录；后续实车反馈及界面分类调整分别记于下文。

参考固件为用户提供的 IHU519G / FS11A1 / Android 9 API 28 / 构建 327，OTA SHA-256 为 `f61a7399d61a7f4502b9289ea6c49c661c39c459aae81e676722bcf9be913c01`。已有构建 100 实车日志来自 IHU353G / 系统 169，不能将两者当作同一固件，也不能将以下静态证据描述为实车优化已生效。

## 优先试验

以下顺序是本日最初的对照试验建议。按用户后续要求，当前未保存配置时的默认值已改为高效视频、直接视频输出、MTK 解码器低延迟均开启，具体规则见“默认设置调整”。

保持“隐藏导航栏”决定的 1920×720／1760×720 协商像素，以及请求 60 fps；在相同地图、缩放、操作和热点条件下每次只变更一项。

| 顺序 | 试验 | 根据与限制 |
| --- | --- | --- |
| 1 | H.264 硬解下开启“直接视频输出”，先关闭“流畅视频” | 实车使用 OMX.MTK.VIDEO.DECODER.AVC，但显示经 TextureView。SurfaceView 直接交给系统合成，适合先比较显示开销与响应；这是 Android 显示链路推断，不是该固件已验证的硬件叠加保证。当前开关会重连，并停用画面调节。 |
| 2 | 保留直出，单独开启“高效视频”，确认实际使用 MTK HEVC 硬解 | 固件声明 HEVC 硬解及相应性能表，值得比较地图细节、文字边缘、动态压缩和输出节奏；不能由声明推导出恒定 60 fps 或画质必然提高，不启用软件 HEVC。 |
| 3 | 在较好的编码组合上单独对比“MTK 解码器低延迟” | 固件 ACodec 有 vdec-lowlatency 到 MTK OMX 扩展的实际配置分支。用户随后确认实车测试未见异常，见下文；实际延迟变化尚无对照数据。无重排部分证据不足。 |
| 条件优先 | 若实际为 2.4GHz，且系统允许选择，手动设置系统热点为 5GHz 后使用“手动车机热点”对比 | 固件接口仅在运行时双频支持且 country code 非空时提供 5GHz。不能从枚举常量推断当前正在使用 5GHz。应用不写系统热点频段，不强制修改临时热点的系统分配配置。 |
| 后续 | 仍有短时抖动时，再单独尝试“流畅视频” | 通过延后呈现吸收抖动；当前 60 fps 起始额外延迟为 90ms，并动态调整。已有实车解码延迟较低，不能照搬其他车型的缓冲值作为 E02 推荐值。 |

清晰度和流畅度分别比较。当前应用只协商像素、最高帧率和编码能力，没有视频码率设置；不能把 Android 本地编码器的码率参数当作 iPhone CarPlay 编码控制。

## 解码器配置证据

`vendor/etc/media_codecs.xml:84` 包含 MTK 视频配置。`vendor/etc/media_codecs_mediatek_video.xml:87-93,108-116` 分别声明 HEVC／AVC 解码器，尺寸上限均为 3840×2176。该上限不证明实际吞吐、延迟或 CarPlay 稳定性。

`vendor/etc/media_codecs_performance.xml:124-135` 的厂商性能配置包含：

| 解码器 | 1920×1080 的 measured-frame-rate 范围 |
| --- | --- |
| OMX.MTK.VIDEO.DECODER.AVC | 113–248 |
| OMX.MTK.VIDEO.DECODER.HEVC | 125–276 |

这些是固件携带的性能元数据，不是本次跑分或实车帧率。它们提供尝试 HEVC 硬解的依据，不支持“车机只能解 30 帧”或“HEVC 一定比 AVC 更快”的结论。HEVC 编码器另有 640×480 尺寸上限，那是车机本地编码能力，与接收 iPhone 视频所需的 HEVC 解码能力不同。

## MTK 低延迟路径

本次从缓存分区镜像提取了 32／64 位 `libstagefright.so` 和 `vendor/lib/libMtkOmxVdecEx.so`，未执行固件代码。64 位 ACodec::configureCodec 的 `0xcd138–0xcd1e0` 配置分支读取 `vdec-lowlatency`，查询 `OMX.MTK.index.param.video.LowLatencyDecode` 扩展并提交配置；扩展查询失败则跳过。该调用之后没有据其结果让整个 configure 明确失败，故应用配置成功不能证明已启用。厂商解码库也包含同名扩展字符串。

现有应用的 MTK 开关最高模式同时写入 `vdec-lowlatency` 和 `vdec-no-record`。本次检查的两份 ACodec 库未找到 `vdec-no-record`／其他 `vdec-no*` 字符串；厂商解码库虽有 `OMX.MTK.index.param.video.DecNoReorderMode`，但未证实应用键到该扩展的桥接。不能因该扩展存在就声称现有开关实现了无重排，也不能把 configure 成功的 `mtkTuning=low-latency+no-reorder` 应用诊断标签当作系统读回结果。本轮不修改开关实现。

普通“低延迟解码”开关与 MTK 专用开关分开：它启用独立解码输出线程，并在匹配的高通解码器上附加 vendor 参数；输出线程也适用于 MTK，不能因高通参数不匹配就把整个开关视为无效。标准 KEY_LOW_LATENCY 另有 API 30 门槛，不能当作这台 Android 9 车机的生效依据。标准功能自 Android 11 提供，见 [AOSP 说明](https://source.android.com/docs/core/media/low-latency-media)。SurfaceView 与 TextureView 的合成差异见 [AOSP 显示说明](https://source.android.com/docs/core/graphics/arch-tv)。

## 实车基线与后续验证

### 用户实车反馈（2026-10-20）

用户在讨论“MTK 解码器低延迟”为何标记实验性后，反馈“我刚刚车机测试了 没有异常”。按本轮上下文记录为该选项的一次用户实车测试未见异常，更新此前尚无实车反馈的状态。

本次反馈没有提供 APK 版本、当前固件版本、编码组合、测试时长或前后对照统计；不沿用构建 100 的参数替代本次条件，也不据此断言延迟已降低或无重排参数已生效。用户实测结果与上文固件静态分析分别保留。该次 MTK 反馈只更新验证记录，没有更改实验性标识、默认值或开关实现。

### 去标识要求与测试对象更正（2026-10-20 后续反馈）

用户随后以包含“直接视频输出”和普通“低延迟解码”的截图询问能否取消实验性标识，并补充“我今天测试了没问题”。代理起初把测试对象错误归到截图中的普通低延迟选项。用户明确更正：“那不对 我测的是MTK低延迟”。因此实测未见异常的对象记录为 MTK 专用选项，撤回普通低延迟已实测无异常的错误归属；不据此推断直接输出的独立测试结果。

按用户更正，将“MTK 解码器低延迟”作为普通高级选项，取消实验性标识并上移；普通“低延迟解码”恢复实验性标识并归回下方实验区域。“直接视频输出”保留此前按截图要求取消标识的界面调整。默认值、已保存选择、解码器能力检查、回退和重连／下次连接生效的行为保持原样。

本次是用户反馈后的界面分类调整，没有新增延迟测量或参数读回证据。此前测试包 0.2.1／1203 不包含此次去标识和排序更新；更新已纳入后续 0.2.1／1204 正式包。

### 默认设置调整（2026-10-20 最新要求）

用户明确要求“高效视频、直接视频输出、MTK 低延迟”三个选项默认开启。应用在各项保存键缺失时返回开启；用户已保存的关闭或开启选择仍优先，不写入迁移值、不强制覆盖旧设置，也不因此中断当前会话。

HEVC 继续检查硬件解码器，缺少可用硬解且用户未明确选择软件解码时仍回退 H.264；MTK 功能继续按当前 H.264／HEVC 解码路径提供与应用参数，非 MTK 解码器不写入 MTK 私有参数，配置失败时保留既有回退。直接输出保留不支持画面调节和切换会重连的说明。HEVC 软件解码、普通“低延迟解码”和“流畅视频”仍默认关闭。

此次默认值调整没有新增实车性能测量。此前测试包 0.2.1／1203 不包含更新；用户后续要求正式发布时，更新已构建入 0.2.1／1204，原签名、内置组件 11 和包体均已校验，并覆盖安装到在线模拟器。具体产物见 [正式包构建核验](ANDROID9_AUTHORIZATION_COMPONENTS.md)。

### 既有构建 100 日志

缓存应用日志 `agent_docs/vehicle-20261020-build100/DiPlay-20261020-145441-641.txt` SHA-256 为 `08742387ae94b4dd01d77f57862b70f355e88d7dc3dade32db13b8fb754262d6`：

- 5339、5343、5531、5533 行：TextureView、1920×720、请求 60 fps、MTK AVC 硬解、operatingRate=60、mtkTuning=off，实际解码尺寸也是 1920×720。
- 5678 行：接收／解码输出 49.7／49.9 fps，解码 p50／p90 为 11／16ms，recoveries=0。
- 5749、5755 行：接收和解码输出约 30 fps、解码 p90=13ms、recoveries=0。

这些样本未显示持续解码积压。接收帧率变化可能包含 iPhone 对静止画面的少发帧，maxGap 也可能包含画面停顿；两者均不能单独证明网络故障。应用 shown 计数发生在释放解码输出时，不等于面板最终呈现帧数，后续需结合系统合成记录。

每组记录实际 codec、尺寸、请求帧率、热点方式／已确认频段、rx／shown、解码 p50／p90、恢复次数及触摸响应；对比相同动态场景。无硬解、画面损坏、黑屏、异常恢复或延迟明显增加时回退该组，不同时叠加全部实验开关。

## 本地复核资料

原始二进制、反汇编、日志仅留在 Git 忽略的 agent_docs。字符串文件偏移与反汇编分别见 `catalog/video-latency-binary-strings.json`、`catalog/video-acodec-configure-arm64.txt`。

| 缓存相对路径 | SHA-256 |
| --- | --- |
| vendor/etc/media_codecs_mediatek_video.xml | eea7b29fa3c09ff299083083257d496daa17430e00638cc27da8bfd5c263b298 |
| vendor/etc/media_codecs_performance.xml | 701e603c710001b21a5f42ebb86ac7ae9da726a53811ae543208b0bbb1b17225 |
| vendor/etc/media_codecs.xml | 612b104c24adad7a46497697296adfca0428b35431d5e8bd945bede650611603 |
| system/lib/libstagefright.so | ba8868f216fa37aa035bd0dd6f2dd12fe0bce6c24601c72a966979a0216b9e1d |
| system/lib64/libstagefright.so | c21f68538747dd57ed4dfc12a15e4c0895ab7141abb774d2ace35271b684ba5d |
| vendor/lib/libMtkOmxVdecEx.so | f143b71e97b4ba32af25a17faae2da8d06de7383fac9dc0f5c097ef98401d3f3 |

5GHz 条件证据沿用 `decompiled/adapter/sources/com/ecarx/xui/adaptapi/wifiap/impl/WifiApHandleFileImpl.java:407-451`，SHA-256 为 `79b5a59ee9a32f5d5dc9911e0fc949d048514c5a6931aa03c32869ba57ba43cc`。本次没有新增实车频段或 HEVC 结果；MTK 低延迟的用户实车反馈见上文。
