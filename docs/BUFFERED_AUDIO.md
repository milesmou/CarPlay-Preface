# 预缓冲音乐（实验性）

此功能对应 CarPlay main buffered audio，默认关闭。支持的 iPhone 应用会提前发送音乐，由接收端按时间播放，以抵抗短暂传输间隙；不支持的应用仍使用普通音乐流。它与日常音乐缓冲时长设置不同，不保证每个应用／iOS 版本采用该流。

背景参考：[Apple WWDC23](https://developer.apple.com/videos/play/wwdc2023/10150/)。上游其他车型的播放测试不是 E02 实车结论；本分支设置归入高级实验选项，按界面提示重连生效。

## 实现摘要

- /info 同时声明 mainBufferedInfo 和 type 103 AAC-LC 音频格式，协商 mainBuffered；不支持或音频输出关闭时不启动。
- 单一控制会话拥有共享 renderer。替换必须取消旧 TCP 预加载、完成清理；退役会话的迟到 SETUP／控制／关闭不能抢回，新连接需新控制会话。
- SETUP 返回 dataPort 和 audioBufferSize。只接受控制会话同地址的 TCP 发送端；帧按长度、RTP 和 ChaCha20-Poly1305 校验后交付。
- 本地限制 8 MiB 和两分钟帧数，包含已交给 renderer 但尚未实际播放的预加载；满时施加 TCP 背压，不无限堆积。
- 暂停、flush、替换与关闭和交付串行化；关闭取消正在等待或已接收的发送端，错误帧、速率或包格式明确失败。
- 音频沿用普通 renderer 的路由、焦点和导航／Siri 混音。锚点将 RTP 样本定位到会话时钟；暂停保留必要预加载，换曲／seek 清除指定旧段。

| 控制 | 用途 |
| --- | --- |
| SETRATE | 从指定 RTP 样本开始，并返回时间锚点 |
| GETANCHOR | 查询当前锚点 |
| SETRATEANCHORTIME | rate 0 暂停 |
| FLUSHBUFFERED | 丢弃指定序列／时间戳前的音频 |
| /feedback | 报告播放位置 |

## 限制与验证

当前只接收 AAC-LC，不宣称无损或空间音频。固定锚点延迟估计可能影响进度显示，链路长期低于音频码率时不能靠预缓冲解决卡顿。

源码测试涵盖协商、认证帧、TCP 拆分／合并、容量、锚点、RTP 回绕、暂停／flush／关闭和会话替换；不能据此宣称实车已通过。E02 应逐项验证 Apple Music 或实际采用该流的应用的暂停恢复、seek／切歌、导航压低、电话和 Siri，以及断线重连。普通音乐积压修正另见 [v0.2.21](UPSTREAM_SYNC_0.2.21.md)。
