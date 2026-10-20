# 独立切歌辅助工具：QQ 助手与网易云服务对照

后续结论：构建 100 的独立 CarPlay 媒体助手已实际尝试，但被原车明确拒绝准入；用户随后要求[从源码移除](MEDIA_ASSISTANT_REMOVAL_2026-10-20.md)。以下候选架构与第三方分析保留为历史依据，不代表当前保留此模块。

2026-10-20。用户询问能否参考这两个额外 APK，为 CarPlay 提供独立切歌工具。本轮只读分析 APK 和补充方案，没有新增辅助应用、修改运行代码、构建或安装。

后续用户明确选择“使用 QQ 助手媒体授权文件，签名还是用之前的”。已另外接入本机授权输入及主应用 manifest／assets，保留原签名，详见 [QQ 授权接入](QQ_MEDIA_AUTHORIZATION.md)。以下记录此前的辅助工具分析；独立助手仍未实现。

## 结论

**可以评估独立的 CarPlay 媒体助手，QQ 助手提供了明确的架构参考。** 它以自己的身份注册原车媒体中心，再将回调转给目标播放器；目标播放器无需自己完成同一套原车接入。但助手自身仍须真正获得原车媒体接入和音源归属，分成两个 APK 本身不会解除准入限制。

当前两个文件没有可直接切换为 CarPlay 的通用目标配置。QQ 助手固定控制 QQ 音乐；网易云文件位于 OpenAPI 服务端，不是向任意播放器发送上一曲／下一曲的通用转发器。独立 CarPlay 助手属于可实施的候选结构，目前未完成其原车准入或不跳页实车验证，不能承诺安装即解决。

## 输入与身份

| 文件 | 角色、身份与 SHA-256 |
| --- | --- |
| QQ音乐助手带小卡片 (1).apk | 包名 com.ecarx.qq.tool，版本 1.0.20272130 (bdc) / 3036，1,171,300 字节；SHA-256 e67a0e548fc9ca0e54f0a75cd5b1bcbaaa207f4f935e25b9898805e69e38ce9c |
| 网易云切歌服务(不可禁用多媒体) (1).apk | 包名 com.ecarx.sdk.openapi，版本 4.1.6 (26A7051) / 40530001，5,817,627 字节；SHA-256 8e0e8b76f53f10a9c0d49d5efbd99b24f946223ebecfc9d9ecf42b3460f6aa23，与此前已分析缓存相同 |

两者在 API 28 目标下核验的签名证书 SHA-256 均为参考平台证书 `c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8`。QQ 包默认全 minSdk 范围校验因其签名属性不兼容 API 18 而失败，显式 `--min-sdk-version 28` 校验通过；不将该结论推广至其声明的所有 Android 版本。

平台签名与 ApplicationInfo 的系统应用标志是不同条件，不能以签名匹配推断助手已通过 OpenAPI 许可检查。没有实际安装路径、运行身份、ready／token／requestPlay 日志，本轮不判断其当前实车准入结果。

QQ APK、反编译、API 28 签名结果、逐文件校验存于本机忽略目录 `agent_docs/apk-reference/qq-music-helper/`。JADX 1.5.6 完成，无报告错误；反编译输出仍不替代运行测试。网易云证据继续复用 `agent_docs/apk-reference/netease-track-service/`。不公开原 APK、反编译实现、接入参数或授权明文。

## QQ 助手的实际切歌链

路径相对于 QQ 缓存 `decompiled/sources/`：

| 证据 | 实际行为 |
| --- | --- |
| com/ecarx/qq/tool/t.java:20–26、r.java:17–23 | 取得 MediaCenterAPI，等待 ready，使用助手真实包名 registerMusic。 |
| com/ecarx/qq/tool/t.java:36–42、j.java:31–36 | 向原车请求播放音源，再更新列表、source=6 与播放状态。 |
| com/ecarx/qq/tool/p.java:35–48 | MusicClient 的 onNext/onPrevious/onPlay/onPause 进入 QQ 接口适配器。 |
| com/ecarx/extension/qqmusic/e.java:79–81,137–139 | 明确绑定 com.tencent.qqmusiccar 的 QQMusicApiService。 |
| 同文件 :171–188 | 发送 pauseMusic、skipToNext、skipToPrevious、resumeMusic 四种独立命令。 |
| com/ecarx/qq/tool/q.java:76–90 | 播放记录和打开播放器的 PendingIntent 明确指向 QQ 音乐，缺少通用目标选择。 |
| QQMusicToolService.java:19–32；res/xml/accessible_service_config_ali.xml | 无障碍部分观察 QQ 音乐／桌面窗口变化，启动相应管理流程；未发现以 onKeyEvent 独占方向盘媒体键的实现。 |

因此可参考的链路是：**原车媒体中心 → 已注册助手的 MusicClient → QQ 接口 → QQ 音乐**。辅助工具确实能作为独立的媒体适配层；不能把无障碍窗口监听本身当作阻止原车处理 MEDIA_BUTTON 的证据。

QQ 包还带有 ECARX AppId/AppKey 和 assets/license.txt。license 为 1,152 字节，SHA-256 `4290f880caa28d5535897c9a365c13a7394b93ae06ca9bec919918b90b0dbd98`；按缓存 OpenAPI 实现本地核验，签名块有效、AppId 匹配且含 mediacenter，但授权身份列表不匹配本次所提供 APK 的当前包名＋签名组合。只记录布尔结果，没有保存解析明文或导出接入值。该静态结果不能排除已安装系统应用通过其他既有准入路径工作，也不能保证此文件作为普通用户应用安装后可用。

助手 Application 还写入无障碍启用设置；本项目不照搬自动开启服务的行为，不新增通知使用权，不把辅助工具等同于必须新增这些权限。

## 网易云文件的作用

本次文件与 [此前分析](NETEASE_TRACK_SERVICE_REFERENCE.md) 字节一致，继续复用原始证据。它提供 OpenAPI 服务池及调用者准入，媒体中心仍是独立系统组件。其服务端保留实际调用身份、系统应用标志和 license 检查；构建 97 的实车服务端仍明确拒绝当前 CarPlay 的 mediacenter 访问。

参考固件原 OpenAPI 与这个文件的 classes.dex／classes2.dex 均不同，仅字节差异不足以证明修改范围或全部授权限制被移除。本轮没有替换系统包，也不根据文件名认为它能为任意 CarPlay 应用补发授权或阻止原车播放器跳页。

## CarPlay 专用助手的候选边界

可以将 QQ 适配器替换为本仓库的受控 IPC：**原车媒体中心 → CarPlay 媒体助手 → 当前 CarPlay 会话 → 现有 iPhone 媒体 HID**。助手按自身真实身份接入，播放记录对应实际 CarPlay，主应用提供真实歌曲／播放状态并执行四种媒体命令。

实现顺序应先验证助手实际能取得 ready=true、非空 token 和 requestPlay=true，再接入主应用。若助手在同一阶段被拒绝，继续增加普通广播或换包部署不能证明问题已解决。可接受的准入条件需要以实际系统授予身份、权限或适用授权验证；平台签名、共享 UID 或接口可见性单独均不当作成功。

主应用与助手需双向核对包、证书、UID 及当前会话代际；接口只提供 PLAY、PAUSE、NEXT、PREVIOUS 与必要的真实播放状态。保留原有去重并明确唯一转发者，避免助手和现有观察／广播同时发送。断开、取消、通话及切换原车音源时撤销命令资格；没有会话不转发，蓝牙音频模式继续由原车处理，不引入循环 requestPlay、强拉 CarPlay 前台或禁用原车多媒体。

这是独立媒体适配层的设计，并非本轮已实现的新 APK。不扩展现有授权组件的六项授权或仅限蓝牙连接的代理职责，不复用第三方包名。用户后续明确要求复用的 QQ 授权文件及配套参数仅按上述专门接入文档处理；安装及打包继续遵守用户明确请求后才执行的规则。

本次只有文档与事实索引变更，未新增运行测试。静态证据仍以用户提供 APK 为限；固件参考为 IHU519G / FS11A1 / 327、OTA SHA-256 `f61a7399d61a7f4502b9289ea6c49c661c39c459aae81e676722bcf9be913c01`，用户日志 IHU353G 的事实分别记录。
