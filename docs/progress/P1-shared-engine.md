# P1 当前进展：共享原生编译模块

日期：2026-09-27。状态：进行中，最终 IDE 和 Agent 整合尚未完成。

## 实现

`mobile/native-build-core` 提供 ProjectModel、只读工程识别、静态配置解析、
Manifest 检查、路径边界与输入快照。标准 Gradle 目录的识别不意味着执行 Gradle：
只支持显式实现的声明式子集，动态配置、插件行为、未支持源码目录会阻塞。

`mobile/native-compiler` 接收实际工程目录、工具链、签名身份、取消信号和事件监听器，
执行 aapt2/ECJ/D8/打包/对齐/签名。编译发生在只读输入快照上，最后再次检查工作区
是否变化；错误中的快照路径映射回真实工程文件。

本地 JAR 按 compile/runtime 作用域处理。Maven 与 AAR 当前明确返回兼容性错误，
尚未实现完整依赖解析，不能把本次成功用例推广到 AndroidX 或任意 Android 工程。

无改动构建可以复用通过 SHA-256、签名及对齐验证的 APK。缓存键覆盖源码输入、
工具、SDK、签名证书与编译器身份；验证器由源码与编译器 JAR 摘要自动生成身份，
升级代码后不会继续使用旧实现生成的产物。当前是整包缓存，阶段增量仍未实现。

## 验证

- 29 个 Gradle/JUnit 测试通过，0 失败、0 错误、0 跳过，覆盖导入、动态 DSL 拒绝、
  路径越界、符号链接、XML 实体、源码增删改与新配置切换。
- 共享模块已替代 P0 验证器中的专用编译代码，直接在 Android 16 / ARM64 上验证。
- 真机矩阵覆盖 5 次基线、Java 修改、资源修改、Gradle 布局、Forge JSON、本地 JAR、
  Java 错误、XML 错误与资源阶段取消。最新结果保存到本目录的 p1-evidence/。
- 成功 APK 经签名校验后通过 ADB 安装并启动，Activity 回读实际 View 的文本/代码标记。
- 手机锁屏时跳过 UIAutomator 可见界面检查，报告中 uiVerified=false，不算视觉通过。

桌面命令（Windows 环境示例）：

```powershell
$env:JAVA_HOME = '<Temurin JDK 17 路径>'
cd mobile
./gradlew.bat :native-build-core:test :native-compiler:compileJava '-PmhNdkVersion=27.2.12479018'
./gradlew.bat :app:assembleOnlineDebug '-PmhNdkVersion=27.2.12479018'
```

桌面 Gradle 用于编译 Forge 自身，不参与手机上用户工程的编译。项目仍保留上游
CLI Agent 后端的 PRoot 源码依赖；它不是 Termux 应用，也不是 Android 编译器。

## 主工程状态

Mobile-Harness 固定源码已导入 mobile/app，保留上游许可与来源记录。修复了一处
RuntimeBridge 接口声明缺少换行的问题，并固定原生依赖子模块路径。主工程 APK
已经构建成功，且已接入共享编译器，但不能将该 APK 当作最终 Forge 产品交付。

新增 `NativeBuildService` / `NativeBuildClient`，使用独立 `:native_builder` 进程、
前台通知、私有 Binder、持久化构建记录与失败恢复标记。工具从应用包中验证并准备，
Debug 签名改用 Android Keystore，而不是 P0 内置测试私钥。应用包名为
`dev.forge.mobile`，关闭上游应用更新源，避免覆盖其他 Mobile-Harness 安装。

主应用构建入口已替换为原生服务，增加构建日志和取消入口。Android setup 不再安装
Linux Gradle 工具链，Agent 提示也不再宣称 Linux SDK/Gradle 可用。

主应用与仪器测试 APK 均构建并安装成功，真机 **3 个仪器测试通过**：
独立进程与 Keystore 签名/缓存、结构化错误无产物、拒绝工作区外路径。
证据见 [测试输出尾部](p1-evidence/native-service-test-tail.txt) 与
[服务构建记录](p1-evidence/native-service-results.json)。尚未验证可见 UI 操作和安装器交互。

本轮未执行真实模型请求，没有宣称 Agent 构建修复闭环已完成。下一步是依赖解析、
Agent 构建工具桥接、模板与编辑器工作流，详见实现清单。
