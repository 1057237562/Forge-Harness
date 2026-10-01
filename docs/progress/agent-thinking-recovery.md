# DeepSeek Harness Thinking 卡住修复

日期：2026-10-01。范围为 `dsh --profile sdk` 对话桥接层；不代表其他 Agent 驱动已完成同等验证。

## 调查证据

连接的 BVL-AN00 设备（序列号 ANYX024809000543）上，应用 `dev.forge.mobile`
保留的 `cache/runtime-output-248823988195392.log` 和
`cache/runtime-output-248912334351556.log` 显示 DeepSeek 官方路由请求发生
`TRANSPORT` 错误，后者记录了连续三轮重试，最后停在 `llm/retry-started`。
这些历史日志不能证明会话永久挂起，也不能区分 DNS、TLS 或连接层的具体故障。

原解析器忽略重试事件，发送时预置的 Thinking 因而继续显示。原运行循环仅记录
终止失败，等待后续 idle 和进程退出；3 秒退出超时仅在 shutdown 已发送后生效。

## 修复行为

- 解析 `llm/retry`，通过现有对话活动区和前台通知展示次数、上限及失败原因。
- 单次 `assistant/chunk.finish` 请求错误不直接终止会话，保留 SDK 的正常重试。
- JSON-RPC 错误、`turn/end` 错误和 blocked 结果立即进入失败路径，不依赖 idle。
- 成功 `turn/end` 直接启动退出流程，不依赖后续 `session.status:idle`。
- shutdown 等待 3 秒后发送 TERM，再等 500 毫秒发送 KILL；若仍无法退出，
  在 TERM 后 1.5 秒结束等待并报告错误。成功 SDK 结果不因清理产生的非零退出码
  或已关闭 stdin 而被误判为失败。
- 运行循环使用单调时钟，只由识别到的协议进展延长期限。初始化等待 30 秒、
  提示接收等待 60 秒、模型无进展等待 6 分钟、工具无进展等待 30 分钟。
  工具期限考虑了无流式输出的原生构建；这不是整个任务的总时长限制。
- 异常路径在 finally 中结束子进程并关闭输入，随后由桥接层发布 SessionFailed，
  让现有 ViewModel 完成工作片段并退出 Thinking。

## 回归验证

`DshSdkSessionTest` 使用模拟进程、输出文件和虚拟时钟执行真实运行循环，覆盖
初始化失败、终止失败缺少 idle、成功缺少 idle、TERM 无效后 KILL、KILL 无效、
重试后恢复、成功后 stdin 关闭，以及三个等待阶段的静默超时。
`DshSessionWatchdogTest` 覆盖流式进展、重复 running 状态、重叠工具、迟到握手响应。
解析器测试覆盖传输重试与终止失败的区别，并保留 SDK failure 消息。

在仓库缓存 JDK 17、NDK 27.2.12479018 下执行：

```powershell
./gradlew.bat :app:testOnlineDebugUnitTest :app:assembleOnlineDebug :app:lintOnlineDebug '-PmhNdkVersion=27.2.12479018' --console=plain
```

应用 JVM 测试 70 项通过，OnlineDebug APK 构建及 `lintOnlineDebug` 通过。
本机被忽略的 `mobile/local.properties` SDK 路径已按 Java properties 规则转义冒号。
未安装新 APK 或调用真实模型进行故障复现，设备可见 UI 与真实网络恢复仍待验收。
