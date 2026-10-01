# P0 执行记录：Android 原生编译链

日期：2026-09-27  
状态：**原生编译 → 签名 → 安装 → Activity 执行验证已通过**。P0 中与 AIDE 的性能对比、其他设备覆盖尚未完成；整个产品未完成。

## 结论

在连接的 HONOR BVL-AN00、ARM64、Android 16 / API 36 上，独立 Android 服务进程成功完成：

`aapt2 compile/link → ECJ → D8 → APK 组装 → zipalign → apksig → 签名验证`

没有使用 Termux、PRoot、Linux rootfs、手机 JDK 或 Gradle。电脑用于构建验证器本身、ADB 部署与采集；**样例源码到样例 APK 的全部构建步骤在手机完成**。

验证工具包名为 `dev.forge.nativeprobe`，targetSdk 36；样例包名为 `dev.forge.sample`，minSdk 28、targetSdk 29，使用 SDK Platform 29 的编译桩。不能将本结果推导为全部 API/设备、现代 AndroidX/Kotlin 工程已经支持。

## 已交付代码

- `probes/native-build/src/dev/forge/nativeprobe/NativeBuildEngine.java`：原生编译流水线。
- `BuilderService.java`：非导出 `:builder` 进程、Binder/Messenger、状态与日志持久化。
- `ProbeActivity.java`：触发构建和查看输出。
- `fixture/`：Java/XML 样例，Activity 回读实际 View 内容用于启动校验。
- `build.ps1`：固定来源/摘要的工具准备、桌面构建和开发签名。
- `test-device.ps1`：真机完整构建、安装、启动、代码/资源变更和负向回归。
- `verify-results.ps1`：结果一致性、产物摘要、失败阶段终止等独立检查。

研究副本仍放在 `research/`，没有将不完整 AIDE 镜像或 AIDE-Termux 混入产品代码。根目录已建立 Git 仓库，未创建提交或推送远端。

## 工具基线

| 工具 | 此次验证版本 |
|---|---|
| ECJ | 3.18.0（Java 8 源码目标） |
| D8 | Android SDK Build Tools 30.0.3 中的 2.1.7-r1 |
| apksig | Android SDK Build Tools 30.0.3 自带版本 |
| aapt2 / zipalign / libc++ | AndroidIDEOfficial 工具归档 v34.0.4 / aarch64 |
| Android API 编译桩 | android-29 |
| 桌面引导编译器 | OpenJDK 8u442 |

工具 APK 约 24.8 MiB，包含编译 API 桩、编译器及原生工具；签名密钥仅为自动生成的测试密钥。实际文件摘要见 [toolchain-manifest.json](evidence/toolchain-manifest.json)。

使用了 AndroidIDEOfficial 的独立原生工具归档，不依赖该 IDE 的终端或包管理环境。AIDE-CN 与 AndroidIDEOfficial 是不同来源，本报告没有把二者混称。工具来源和分发前需要核对的事项见 [THIRD_PARTY.md](../../probes/native-build/THIRD_PARTY.md)。

## 最终回归结果

执行命令：`./probes/native-build/test-device.ps1 -BuildOnly`。

该选项跳过 UIAutomator 的屏幕可见性断言，因为手机锁屏；**仍执行真实 APK 安装、Activity 启动，以及 Activity 从实际膨胀的 View 读取文本/代码标记后的回读断言**。未将后台启动结果当成视觉检查通过。

| 场景 | 构建耗时 | 结果 |
|---|---:|---|
| 基线 1 | 2720 ms | APK 签名、安装、Activity 校验通过 |
| 基线 2 | 2037 ms | 同上 |
| 基线 3 | 2671 ms | 同上 |
| 基线 4 | 2714 ms | 同上 |
| 基线 5 | 2366 ms | 同上 |
| Java 标记修改 | 2811 ms | 运行后读取到新代码标记 |
| XML 字符串修改 | 2661 ms | 实际 View 文本为修改后的字符串 |
| Java 调用不存在方法 | 1493 ms | 预期失败，报告文件与行号，未进入 D8/签名 |
| XML 缺少结束标签 | 519 ms | 预期失败，报告资源解析错误，未进入 ECJ/D8/签名 |

基线 5 次中位数为 **2671 ms**，范围 **2037–2720 ms**。每次都创建新工作目录并执行完整流水线，重启验证应用进程；系统页缓存/ART 缓存仍可能影响耗时。不是增量编译、首次冷机性能或生产工程性能。尚未与 AIDE 在同工程同设备上对比，因此没有速度倍数结论。

独立结果核对：

- 5 次相同输入的 APK SHA-256 一致。
- Java 修改与资源修改的 APK SHA-256 均与基线不同。
- 7 个成功 APK 的手机与电脑摘要一致，手机签名验证和电脑 apksigner 复核均通过。
- 7 个 Activity 内容校验通过；0 个可见 UI 校验通过（未执行）。
- 2 个失败用例没有报告产物，没有继续进入 Dex 或签名阶段。
- [完整结果摘要](evidence/device-summary.json)、[验证断言结果](evidence/verification.json)。

## 已定位并解决的引导问题

1. 桌面 Java 21 生成的部分匿名类被此基线 D8 处理时触发内部错误；验证器引导编译固定 JDK 8。该结论不代表所有新版 D8 都有此问题。
2. 本机 Windows aapt2 将部分 assets ZIP 路径写成反斜杠，Android AssetManager 无法找到。引导脚本改为显式以 `/` 路径写入 assets。
3. ECJ 转换为 Dex 后缺少消息资源，运行时抛 MissingResourceException。脚本保留 JAR 中非 class 资源，ART 中编译恢复正常。
4. 原生 aapt2/zipalign 从 APK 解压的 nativeLibraryDir 运行，并按单个子进程设置 libc++ 路径；targetSdk 36 下在本设备验证通过。

## AIDE 源码可用性

当前 AIDE-Plus 仓库的公开 tags 均指向仅含发布说明的当前提交。找到第三方 `Familyye/AIDE-Plus-AS1` 镜像，固定提交 `88cb968c7068747ba3c540763a8d9ccb314a9862`，但其关键子模块仍指向：

`AndroidIDE-CN/AIDE-Plus @ 48a31b3e8bbcab55c096aeceb7399d2b5fd41a35`

该 git tree API 查询返回 422，子模块未能取得。镜像自身有构建外壳，但不能据此宣称拥有完整、独立可构建的 AIDE 内核。此次实现为按公开工具组合编写的验证链路，没有复制该镜像中的代码或闭源 APK 内容。

## 后续执行项

1. 将 P0 固定夹具编译器抽为接受 ProjectModel 的构建模块，补齐原生工程配置与静态 Gradle DSL 子集。
2. 实现 Maven/JAR/AAR、Manifest 合并和依赖资源优先级；对暂不支持配置明确阻塞。
3. 将受管理的 Build API 接入 Mobile-Harness，替换原 Gradle 编译入口和 Agent 构建提示。
4. 增加用户取消、进程中断恢复、输入快照、结构化诊断、缓存失效及增量构建。
5. 接入应用内系统安装流程，补做可见 UI、另一设备及 AIDE 性能对照。

上述工作尚未实现；本次没有接入远程模型、运行真实 Agent 会话或完成整合 IDE。
