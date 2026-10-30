# 9.5 音频通道与原厂高德对照

用户说明所提供悬浮版名称中的“锁12”指默认音频通道为 **12**，并提供“声音调试 → 音频通道 12”截图，要求与原厂一致。

**按缓存 327 固件，导航音频流为 `STREAM_NAVI = 12`，当前音频通道保留 12。没有依据把它改成通用配置中的 11。** 这里的结论针对原厂导航音频用途；未读取实车 169 原厂高德的同页设置，也没有独立捕获其策略调用返回数值，不能宣称已完成实车页面逐项对照。

## 原厂取值链

1. 327 原厂高德的 `AudioConfigData.getStreamType()` 优先查询渠道接口 `GET_AUDIO_STREAM_TYPE`（30007）；只有返回 -1 时，才使用配置字段 `audioChannel`。
2. 327 的 `C04010181178`／IHU519G 对应 `BeidouFS11A1InteractionImpl`，继承到 `JiliGkuiDefaultInteractionImpl`。它的 30007 分支调用车机 `PolicyAPI.getAudioPolicy().getAudioStreamTypeForFunction(FUNCTION_NAVIGATION_GUIDE)`，并直接使用真实结果。
3. 327 框架已确认导航流 `STREAM_NAVI=12`；`STREAM_TTS_NOTICE=11` 是另一条流。原厂优先使用车机导航策略，不能只看未适配时的通用回退值。
4. 原厂声音调试信息通过 `AudioConfigData.getStreamType()`／`AndroidAudioControl.getStreamType()` 输出有效值，不能由原始 JSON 的 `audioChannel` 单独推导界面必然显示什么。

原厂已缓存通用配置中，`audioChannel="11"`、`audioAttrUsage="12"`、`isUserHighVersionAudioApi="1"`。这几个参数作用不同：音频流编号、AudioAttributes 用途以及是否采用对应 API 不能混为一项。原厂适配还会查询／设置厂商 AudioAttributes；本次只核对用户指定的音频通道，不据此更改其他声音选项。

## 当前包的处理

- 保留 `26000006` 当前音频通道 12，不新增钳制为 11 的代码或配置。
- 9.5 的 `AudioConfigData.getStreamType()` 同样保留“渠道真实返回值优先、配置回退”的逻辑；此前三向 DEX 审计没有发现该音频方法被悬浮版或本仓库仪表改造修改。
- 用户截图确认当前页面显示 12；对照 327 的导航流定义，未触发“原厂不同则修改”的条件。保留策略接口检查，不能把原厂行为描述为对所有环境无条件写死 12。
- 已纠正公众版重建记录中对“锁12”的含义描述。具体二进制配置的最小修改位置仍未还原；不能因名称含“锁”而认定 Java getter被改成固定返回。
- 本次未构建、签名、安装新 APK，也未改动已交付包或设备保存设置。

## 证据与边界

详细证据路径、SHA-256、原厂方法行号及允许公开的音频数字字段见事实项 `amap95AudioChannelReview20261030`。原始 APK、恢复 DEX、完整配置和用户截图只留本机。

本轮额外尝试恢复 `ecarx.policy.vdex` 作只读检查，恢复工具执行成功，但 JADX 有 11 处错误，且未定位到导航策略的最终数值映射；因此该尝试不作为“策略调用实测返回 12”的证据。现有实车日志中找到了策略调用名，没有检出独立返回值，仍与前述 327 静态定义分开记录。

当前仪表提示的实际导航数据验证见 [26000006 测试记录](PREFACE_AMAP95_NATIVE_HINTS_TEST_2026-10-30.md)；本次音频参数核对不增加仪表显示验收结论。
