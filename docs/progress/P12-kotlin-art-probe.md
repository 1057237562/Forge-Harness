# Kotlin ART 编译器适配探针

## 2026-10-01：保留编译器生成的资源

修复 classes 目录中的非字节码产物未进入 APK 的问题，包括 Kotlin 模块元数据。
打包现在保留这些资源，同时排除 class 文件与旧 JAR 签名；与依赖资源重名时明确失败，
不静默覆盖。完整 APK 缓存版本已更新，防止返回缺少元数据的旧产物。
native-compiler 的 17 项 JVM 测试通过，新增两项验证元数据字节保留与重名拒绝，
证据为 p12-evidence/ApkAssemblerTest.xml。本次修改尚待重新构建主 APK 和设备验证。

## 2026-10-01：正式混编 APK 链路通过

补充验证：显式 Maven 声明 org.jetbrains.kotlin:kotlin-stdlib:1.9.24 的工程也通过在线
解析与离线重编译（42.967 秒测试），证明与内置标准库一致时不会重复打包或误拒绝。
另外把 Java Activity 改为调用 Kotlin 的 String 返回方法，纯内置标准库离线构建、
源码变化/失败诊断测试通过（37.009 秒）；验签、安装和实际方法调用返回 kotlin-apk:43。
对应证据：kotlin-explicit-stdlib-test.txt、kotlin-string-api-test.txt、kotlin-string-api-runtime.json。

ProjectModel / Forge JSON 新增 kotlin 源码目录，快照与源码一致性检查包含 Kotlin 输入。
支持独立目录、与 Java 共享目录以及 java=[] 的 Kotlin-only 配置；.kts 脚本仍明确拒绝。
NativeCompiler 通过可选 KotlinBackend 接口调用 :native-kotlin；主应用已装配后端。
标准库 1.9.24 随 APK 提供，解包时校验摘要，并参与工具链与阶段缓存键。

构建顺序：资源生成 R/BuildConfig Java → Kotlin 解析 Kotlin 与 Java 源码 → ECJ 编译 Java
并引用 Kotlin classes → D8 → 打包/签名。修改 Kotlin 内容会使混编 classes 缓存失效。
与宿主 Kotlin 2.2 运行库同名的编译器 builtins 已重定位，并调整编译器资源加载路径，
保留各自版本，未使用不确定的 pick-first 规则。

NativeKotlinBuildTest 真机通过（36.541 秒）：初次离线混编、修改 Kotlin 后离线新编译、
APK 摘要变化/classes 缓存失效、错误 Kotlin 源码返回文件/行号且无 artifact。
导出的有效 APK 独立验签、安装与 Activity 运行通过，返回 `kotlin-apk:43`。
证据：kotlin-apk-device.txt、kotlin-apk-runtime-verified.json 和 KotlinProjectModelTest XML。
核心测试与主 APK 最终构建通过，Agent 能力说明已更新为显式 Forge JSON 的 Kotlin 1.9.24 支持。

仍有限制：Gradle Kotlin DSL/插件自动迁移、Kotlin 2.x、Compose、KAPT/KSP、广泛库/反射
兼容性等未完成。Kotlin runtime 依赖须与 1.9.24 profile 对齐；其他版本当前明确报错。
混编路径通过不代表完整项目目标已经验收。

## 可复用模块与双向混编

新增 :native-kotlin，包含 AndroidInjectionMethods 与 NativeKotlinCompiler。
该模块接收显式 Android SDK、stdlib、类路径和源码列表，准备官方扩展元数据，
通过 MessageCollector 返回结构化诊断，并接入 Kotlin 的取消检查回调。
输出目录必须为空，关闭环境设置读取；目前仍仅由 androidTest 引用。

真机 KotlinMixedCompilerTest 通过：KotlinBridge 引用 JavaBridge.base()，先由 Kotlin
编译器解析 Java 源码并输出 Kotlin 类，再由 ECJ 引用该输出编译 JavaBridge；
合并类与 stdlib 经 D8 转换，在独立类加载器中执行 JavaBridge.callKotlin()，
完成 Java → Kotlin → Java，返回 42。证据为 kotlin-mixed-device.txt 与 kotlin-mixed-result.txt。

这验证编译组件的双向符号解析和执行，尚未表示 ProjectModel/NativeBuildService 已开放
Kotlin 工程或生成完整混编 APK。下一步继续正式构建流水线和产物安装验证。

## 后续成功验证

新增固定摘要的 AndroidKotlinTransform，扫描编译器 class 文件中的运行时 Inject 注解，
生成候选方法索引（79 条注解方法记录），仅替换容器 getSetterInfos 的 getMethods 调用。
运行时按索引解析候选方法及继承层次，保留 Kotlin 自己的注解过滤；未知类保留反射行为。
没有引入伪造 Swing 类，也没有移除类型检查或跳过编译诊断。

真机 KotlinCompilerProbeTest 已通过：

- 在 ART 中运行编译器，生成调用 `listOf(20, 22).sum()` 的 Answer 类。
- 手机 D8 转换该类和 Kotlin stdlib 1.9.24。
- 使用仅继承系统引导类加载器的 InMemoryDexClassLoader 加载输出，断言标准库也来自
  本次 Dex，而非宿主 Kotlin runtime；执行 answer() 得到 42。
- 第二次调用同一编译器，未定义 MissingType 返回 COMPILATION_ERROR，不生成 Broken.class。

最新证据为 p12-evidence/kotlin-probe-device.txt 与 kotlin-compiler.log。
适配目前仍在独立测试依赖中；正式 ProjectModel/构建链接入、混编、APK 安装运行、
更广泛 Kotlin 语法与注入适配等价性验证仍需继续，尚未开放生产 Kotlin 支持。

## 初始定位记录（下列失败已被上述探针适配解决）

2026-09-27：在 androidTest 中加入 Kotlin compiler embeddable 1.9.24；它尚未进入主应用
正式编译依赖。独立标准库 JAR 作为测试输入提供给编译器，SDK 使用已验证的 Android 29 stubs。
测试直接在 ART 调用 K2JVMCompiler，不启动 Linux JVM、Gradle、Termux 或外部编译进程。

宿主测试 APK 构建成功。真机发现并处理了两处运行布局差异：

1. DEX 不保留可按资源查找的 PathUtil.class，显式传入 -kotlin-home 避免自动推断安装目录。
2. 同样无法通过 KotlinCoreEnvironment.class 定位扩展目录；将官方 compiler.xml 提取到私有
   测试目录，使用 -Xintellij-plugin-root 指定它。

最新测试仍失败：反射加载 IntelliJ 编译环境时，Android 缺少 javax.swing.Icon。
证据在 p12-evidence/kotlin-probe-device.txt 与 kotlin-compiler.log。
下一步处理无界面运行所需的桌面 API 适配，继续验证编译产物、D8、运行与 Java/Kotlin 混编。

不能将“测试 APK 构建成功”视为 Kotlin 原生编译通过。生产 ProjectInspector 仍拒绝 Kotlin
源码，避免半成品路径误报成功；完整 Kotlin 目标保持未完成。
