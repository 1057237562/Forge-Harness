# Android 原生编译设备验证器

一个独立 Android 验证 APK，在 `:builder` 服务进程里编译内置 Java/XML 工程。
所有样例编译步骤在手机执行；电脑只负责构建验证 APK、ADB 部署、读取结果和测试。
不调用 Shell 构建命令、不安装 Termux/PRoot/JDK/Gradle，也不复制 AIDE 闭源实现。

## 已实现

- ARM64 aapt2 资源 compile/link 与 R.java 生成。
- ART 内执行 ECJ 编译 Java，再执行 D8 生成 Dex。
- APK 组装、原生 zipalign、apksig 签名和签名验证。
- 独立 Android 构建进程、Binder/Messenger 日志、阶段计时。
- 每次构建独立目录、唯一 buildId、日志、状态 JSON 和 APK SHA-256。
- 正常、源码修改、资源修改、Java 错误、XML 错误五类测试场景。
- 标准 Gradle 目录、显式 Forge JSON、本地 JAR、取消与相同输入 APK 缓存验证。
- 自动安装生成产物，启动 Activity，并检查实际加载的文本和代码标记。

## 运行

需要 PowerShell 7、ADB、JDK 17、已安装的 Android SDK Platform 29/36、Build Tools 30.0.3
及主工程 AGP 所需的桌面 Build Tools。SDK 路径通过 mobile/local.properties 或 ANDROID_HOME 配置。
探针宿主 APK 由桌面 Gradle 的 :native-probe 模块构建，与主应用共享完整依赖和 XML 适配；
手机里的样例 APK 仍由原生编译器构建，不运行 Gradle。
设备需要 ARM64、API 28+；当前验证目标是 ARM64 Android 16。工具下载包含固定 SHA-256
校验，详细来源见 [THIRD_PARTY.md](THIRD_PARTY.md)。宿主 APK targetSdk 为 36，
样例 targetSdk 为 29；这不表示验证了全部 Android 16 API 或 16KB 页面设备。

```powershell
./probes/native-build/build.ps1 -Sdk E:\Android_SDK -Jdk E:\OpenJDK
./probes/native-build/test-device.ps1 -Serial <ADB设备序列号>
```

第一次运行需要解锁手机并授权 USB 调试。默认执行 5 次基线和 8 次变更/导入/错误/取消场景。
基线中相同输入会命中整包缓存，报告的 cacheHit 字段区分真实编译与产物复用。
脚本将安装/更新独立包 `dev.forge.nativeprobe` 与 `dev.forge.sample`，不操作用户其他工程。

设备锁屏时可以使用：

```powershell
./probes/native-build/test-device.ps1 -BuildOnly
```

该模式仍然编译、校验 SHA-256、安装和启动样例，并检查 Activity 从实际视图读取的结果；
只跳过 UIAutomator 的可见界面校验，报告中 `uiVerified` 明确为 false。

## 输出

- `build/forge-native-probe.apk`：原生编译验证工具。
- `build/toolchain-manifest.json`：本次使用的工具与产物摘要。
- `build/device-results/summary.json`：回归结果。
- `build/device-results/<buildId>.apk`：手机编译生成的样例。
- 同目录的 `.log`、`.json`、`.activity.txt`、`.ui.xml`：阶段输出和校验依据。
- 手机端 `files/runs/<buildId>/`：工程、编译中间文件和产物。

电脑端重新执行 build.ps1 会更新验证 APK 和工具清单，保留已有 device-results 报告。
手机端验证产物暂不自动清理；卸载验证工具会删除其私有数据。

## 明确限制

本验证器使用固定夹具测试共享 native-build-core/native-compiler 模块；共享模块
已支持实际工程识别、静态 DSL 子集、快照、本地 JAR、取消和整包缓存。
共享引擎新增的 Maven/AAR、阶段缓存和 Agent 桥接证据见 docs/progress；本探针仍主要覆盖
基础夹具，安装由 ADB 测试脚本执行，不能视为已验证最终产品的系统安装 UI。重复运行可能复用已有 APK，
也受到系统页缓存/ART 缓存影响，不能将缓存命中耗时称为源码级增量编译性能。

ECJ 3.18.0、D8 2.1.7-r1 是初期可运行基线，不代表现代 AGP/Kotlin/Compose 支持。
打包器仅覆盖无第三方依赖的 Java/XML 夹具。该开发 APK 内置测试私钥，不能当成
生产签名器。正式构建引擎需替换为项目级密钥管理。

目前只能证明 AIDE 同类工具路线可行，不能声称复用了 AIDE 的完整构建引擎。
