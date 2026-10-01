# Maven 依赖解析进展

后续更新：本文为 P2 阶段记录；`.module` 原生解析与 Okio 真机结果见
[P3 报告](P3-module-metadata.md)，下文的元数据未实现状态已被 P3 替代。

日期：2026-09-27。状态：Maven/POM JAR 路线已接入并验证，AAR 与 Gradle 变体仍未完成。

## 本轮实现

- 新增固定坐标校验、HTTPS 仓库访问、流式大小限制、取消检查、原子缓存和缓存摘要校验。
- 新增 POM、父 POM、属性继承、dependencyManagement 与 BOM 导入。
- 处理传递依赖、compile/runtime/compileOnly 作用域、optional/provided/test、省略与路径相关排除。
- 显式顶层依赖可以固定冲突版本；未固定的冲突会报错，不模拟或暗中猜测 Gradle 的版本选择。
- 记录完整解析结果与依赖摘要，将依赖实际内容加入 APK 缓存键，结束构建时再次校验依赖未变。
- NativeBuildClient / Service 提供显式 offline 请求；缓存缺失/损坏时返回错误，不访问网络。

缓存 SHA-256 用于识别内容和损坏，不等同于发布者签名。下载使用 HTTPS；POM 声明的
其他仓库不会自动获得访问权，仓库范围由工程配置决定。动态版本、范围、SNAPSHOT、
具有依赖行为的 POM profiles 等尚不支持的内容均明确拒绝。

## 验证结果

- **47 个单元测试通过，0 失败、0 跳过**：工程/快照 29 个，Maven 解析 18 个。
- 主应用和测试 APK 构建成功，最新 **4 个真机仪器测试通过**。
- 实际解析 `com.google.code.gson:gson:2.10.1` 及其父 POM，在 Android 16 / ARM64
  上通过 ECJ/D8 编译含 Gson 调用的应用。
- 修改源码增加一个类后，使用 `offline=true` 重新编译，结果 `cacheHit=false`；
  最新记录耗时 3563 ms。它证明的是显式离线模式，不是声称关闭了设备 Wi-Fi。
- 导出该 APK，与手机记录的 SHA-256 核对，电脑 apksigner 独立验签通过。
- 通过 ADB 安装并启动，Activity 实际调用 Gson，文件回读得到 `"maven-on-android"`。
- 验证未使用 Linux JDK、Gradle 或 Termux 编译用户项目。

证据：

- [仪器测试输出](p2-evidence/device-test-output.txt)
- [实际运行校验](p2-evidence/maven-runtime-verified.json)
- [解析后的依赖与摘要](p2-evidence/resolved-dependencies.json)
- p2-evidence 中的 JUnit XML

复现脚本：先运行 `NativeBuildIntegrationTest`，再运行
`mobile/scripts/test-maven-artifact.ps1`。生成的 APK 与验证结果位于
`mobile/app/build/maven-device-result/`。这些是测试产物，不是完整产品发布声明。

## 发现的必须继续处理项

部分 Google/Android 库的 POM 标有 `published-with-gradle-metadata`，其中依赖可能
只在 `.module` 的 runtime/API 变体中存在。实际检查
`com.android.tools.build:manifest-merger:30.0.3` 发现 POM 无依赖，而 `.module`
包含 common、sdklib、sdk-common、Gson、Kotlin 与 kxml 等依赖。

因此当前解析器对该标记返回 `GRADLE_METADATA_REQUIRED`，避免漏掉依赖却错误地宣称
构建成功。这是临时的未实现项，**不是把最终目标缩减为只支持 POM**。下一步需要：

1. 实现 Android/JVM API/runtime 变体选择、作用域合并、可重定向元数据和版本约束。
2. 安全解包 AAR，接入资源、R 类、assets、Manifest 合并及依赖优先级。
3. 将构建 API 接入 Agent 工具调用，继续完成编辑器、模板和完整验收。

Manifest 合并优先评估官方 merger 在 Android 上的适配，避免自行简化规则而损失
tools:node、tools:replace、SDK 冲突等语义；尚未证明这条适配路线可运行。

标准参考：[Maven 依赖机制](https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html)、
[Android Manifest 合并规则](https://developer.android.com/build/manage-manifests)、
[AAR 内容](https://developer.android.com/studio/projects/android-library#aar-contents)。

完整项目目标仍 active；Agent 端到端、AAR、阶段增量、UI 和设备验收未完成，未执行关机。
