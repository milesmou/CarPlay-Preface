# 原厂注册音源的歌曲信息与歌词

2026-10-15，用户要求将歌曲信息和歌词发给默认注册的星瑞 E02 音源，并确认主要使用网易云、CarPlay 画面能显示歌词。原厂音源接入边界见 [切歌与媒体中心接入](LINKCO_APK_MEDIA_KEYS_REFERENCE.md)。这次没有构建新 APK，也没有获得原厂音源显示的实车结果。

## 当前可同步的数据

| 手机接收数据 | 原厂接口 | 当前行为 |
| --- | --- | --- |
| iAP2 NowPlayingUpdate 标题、歌手、专辑、时长 | IMusicPlaybackInfo 对应 getter | 注册、申请音源成功后同步；手机传来的动态标题保持原样更新 |
| iAP2 播放／暂停状态 | getPlaybackStatus | 明确同步播放和暂停，不发送切换命令 |
| iAP2 播放位置 | IMusicClient.getCurrentProgress / updateCurrentProgress(token, long) | 查询提供当前位置；存在推送方法时单独推送，位置刷新不重复发布整份歌曲信息 |
| 独立歌词文本／当前句 | getLyricContent / getCurrentLyricSentence / updateCurrentLyric(token, String) | 当前接收链路没有独立歌词数据，返回空文本，音源激活时清除残留歌词；不把歌曲名当歌词 |

现有 iAP2 订阅 `Iap2ControlMessages.startNowPlayingUpdates()` 请求 MediaItem 1/4/6/12/26 和 Playback 0/1/7；`CarPlayPlaybackStatus` 解析相同已知字段。没有为歌词猜测新属性编号，没有联网搜索歌词、抓取网易云账号或识别 CarPlay 画面。**CarPlay 画面可见歌词不证明当前元数据链路提供歌词文本。** 若手机以动态标题传出歌词，原厂音源的标题也会同步该文字；仅凭标题变化不将其分类为歌词，也不能由此生成完整歌词或逐句时间轴。

独立进度／歌词接口为可选能力；缺失、权限拒绝或调用失败不影响基础歌曲信息与原有切歌回退，失败后停止反复调用该可选接口。重新申请音源后会重新发布状态和当前位置。诊断只报告 `progressPush`、`lyricInfo`、`lyricPush`、`lyricInput=not-provided` 等能力标志，不记录歌曲名或歌词正文。

本轮 25 项定向测试通过：`PrefaceMediaCenterBackendTest` 7、`PrefaceMediaCenterTest` 11、`CarPlayMediaOwnershipTest` 7。新增验证覆盖动态标题、独立进度、可选方法缺失／权限失败、音源恢复重发及歌词残留清理；未运行全量 CI，未将模拟接口验证描述为车机显示已通过。

## 固件静态证据与验证限制

仍为 IHU519G/FS11A1 Android 9 构建 327，OTA SHA-256 `f61a7399d61a7f4502b9289ea6c49c661c39c459aae81e676722bcf9be913c01`。`system/framework/boot-ecarx.gkui.openapi.impl.vdex` SHA-256 `f0b8f013ccd0b5fcdb52c59bb77ef28c4c9abd142b9540515de3066de7db797f` 中定义：

- `IMusicPlaybackInfo` class definition 偏移 194132；包含 `getLyric(): Uri`、`getLyricContent(): String`、`getCurrentLyricSentence(): String`。
- `MediaCenterAPIImpl.updateCurrentLyric(Object, String): void` 方法体偏移 286786。
- `MediaCenterAPIImpl.updateCurrentProgress(Object, long): void` 方法体偏移 286916。

以上为已核对的类型、签名与偏移，未将优化方法体当作歌词格式、时间单位或显示行为的完整证明。位置沿用当前 iAP2 毫秒值，实际进度显示与单位仍需同车核对。可用本地 `catalog/media-center-classes.json`、`catalog/boot-ecarx.gkui.openapi.impl-method-ids.json` 复核；生成命令仍为 `python agent_docs/firmware/inspect_media_center.py`。实际报告设备 IHU353G 的接口准入与显示能力需单独验证。
