# 项目实现与完成审计清单

目标：完成 Forge Mobile，将 Mobile-Harness 的 Agent 工作流与 AIDE 同类原生 Android
编译手段整合为一个可用、可验证的应用。以 [Spec](specs/001-mobile-agent-android-ide.md)
为设计基线；本清单记录真实缺口，不将验证器替代最终产品。

## 不变约束

- 编译用户工程不用 Termux、PRoot、Linux JDK 或 Gradle；失败也不回退到这条链路。
- Agent CLI 的可选 Linux 后端与 Android 原生构建隔离。
- 不假称已取得 AIDE 闭源内核；工具组合相似与源码复用严格区分。
- 不把缓存 APK、安装请求或后台 Activity 启动分别等同于新编译、安装成功或视觉通过。

## 已有证据

- [x] P0：ARM64 Android 16 上直接调用 ECJ、aapt2、D8、zipalign、apksig，生成并运行 APK。
- [x] Mobile-Harness 源码固定基线导入到 mobile/，保留许可；原生依赖以固定子模块记录。
- [x] 主工程 :app:assembleOnlineDebug 在本机 JDK 17、SDK 36、NDK 27.2 下构建通过。
- [x] 统一 ProjectModel；显式 Forge JSON、严格的静态 Groovy DSL 子集和旧式 Java/XML 导入。
- [x] 不支持的语法/插件/源码类型明确阻塞；工程路径、符号链接和 Manifest 实体保护。
- [x] 源码快照、输入摘要、构建前后变更检查；修改/新增/删除/重命名检测。
- [x] 共享原生编译模块在真机运行，支持本地 JAR 编译及打包。
- [x] 结构化 ECJ 错误与 aapt2 错误，编译失败终止后续步骤。
- [x] 相同输入的 APK 缓存复用，校验内容、签名、工具、编译器源码和签名身份。
- [x] 资源目录、Java classes、Dex 阶段独立缓存接入；真机修改 assets/Java/XML 的命中与失效规则验证通过，详见 P8。
- [x] 取消信号贯穿阶段与进程；真机验证在资源阶段取消，不继续 Dex/签名。
- [x] Maven/POM JAR 依赖、父 POM/BOM、传递作用域、排除、显式版本固定与校验缓存已接入。
- [x] Gson 实库在真机编译并执行，修改源码后显式离线重新编译通过；详见 P2 报告。
- [x] Gradle .module 基础变体解析、重定向与约束已接入；Okio/Kotlin 传递依赖在真机编译、离线重编译及实际执行通过，详见 P3。
- [x] 官方 Manifest 合并器已适配 Android XML 解析器；本地资源 AAR 的 R 类、Manifest、assets 经真机编译、安装及实际读取验证，详见 P4。
- [x] AndroidX CardView 1.0.0 实际 Maven AAR 经真机编译、离线重编译、安装和运行通过。
- [x] 应用扩展 AAR styleable 后，按库发布符号保留索引；CardView 索引位移与 17dp 属性读取经真机验证。
- [x] Agent loopback Build API、会话令牌、路径限制、预算和三个驱动接入完成；真机 API 与 Node 协议测试通过，真实 Agent 调用仍待验收。
- [x] 手机 guest Node → NativeBuildService → 诊断返回 → 脚本修复后成功构建的联合测试通过；真实模型决策仍待验收。
- [x] 构建取消绑定独立请求身份；真机验证外来请求取消无效、自身资源阶段取消生效且无 APK 产物。
- [x] 完整 UTF-8 文本编辑/保存入口、外部修改检查与退出确认已接入；核心与 Android 真机存储测试通过，可见 UI 待验收。
- [x] 私有草稿持久化与恢复接入编辑器；真机强停应用后的恢复、冲突保护与迟到写入防护测试通过。
- [x] Android Java/XML 新项目模板接入创建界面，含 Manifest/布局/示例 Activity，真机离线新编译通过。
- [x] DeepSeek Harness 对话重试展示、分阶段无进展超时及不依赖 idle 的终止处理接入；70 项应用 JVM 测试与 APK 构建通过，真实网络及可见 UI 待验收，详见 [Thinking 恢复记录](progress/agent-thinking-recovery.md)。

这些勾选仅证明列出的范围；详细证据见 progress/，不意味着下面项目已完成。

## 必须继续完成

1. **主应用接入**：已加入构建 Service、客户端、运行/取消、日志、诊断源码跳转、产物身份展示与再次安装前校验；继续完成 Agent 结果 UI 联动及可见 UI 验证。
2. **移除旧编译路径**：已删除 RuntimeInstaller 的 Android Gradle 安装/配置代码与旧离线 Android bundle；已替换驱动指导与主 UI 编译入口，继续全仓审计遗留脚本/说明。手机编译不得回退。
3. **工具链交付**：原生工具和 SDK 的完整安装/校验/恢复/更新，项目级签名与应用身份、更新源隔离。
4. **依赖**：Maven/POM JAR、基础 .module 变体、离线缓存与基础 AAR 已验证；继续验证真实 Maven AAR、库字节码、覆盖优先级、原生库、compileOnly AAR 及更多变体。
5. **Agent**：Build API、驱动接入、诊断结果与预算已实现；继续完成 guest Node/真实模型修复循环、停止竞争、UI 联动、差异审阅、检查点及回滚验收。
6. **项目工作流**：已接文本编辑/草稿恢复、冲突检查、Android 基础模板与兼容性检查/迁移 JSON 预览；继续完成更多模板、Git 克隆/SAF/ZIP 往返验收、实际 AIDE 工程覆盖、迁移报告/patch 导出与中断恢复。
7. **生命周期**：构建持久化、前台服务、进程死亡恢复、真正运行中的工具取消、超时/空间/内存错误与互斥。
8. **安装链路**：已按 sessionId/buildId/包名关联 PackageInstaller 回调，持久化会话与最终结果，并区分构建成功和安装取消/失败；3 项设备状态测试通过。继续系统弹窗授权/拒绝/完成的可见 UI 验收、进程重建后的产物界面恢复，以及 MIUI 外部安装器结果回收。
9. **增量性能**：资源目录/Java/Dex 整阶段缓存、基础失效规则与阶段缓存 512 MiB LRU 配额已实现；继续完成更细粒度增量、其他构建数据存储管理和同工程 AIDE 性能对照。
10. **产品完成度**：已补 BUILDING.md、独立工具准备脚本及与主模块同步的探针构建入口（13 场景回归通过）；继续统一 Forge 界面、发布 APK/依赖清单及无缓存环境复现。
11. **验收**：Spec 所列全流程、失败恢复与安全用例；主应用可见 UI、真实 Agent、离线构建、设备矩阵和同工程 AIDE 对照。

## 后续能力（保留原 Spec 范围）

- Kotlin 与混编、多个模块、ViewBinding/注解处理、Compose/KSP/KAPT。
- Kotlin 1.9.24 已接正式工程模型/原生服务：显式 kotlin 源码目录、标准库校验、双向混编、缓存失效、结构化失败，以及混编 APK 安装运行通过。Kotlin DSL/更多版本/Compose 等仍未完成，详见 P12。
- NDK/CMake、release 签名与优化、AAB、多 APK、语言服务与更完整编辑体验。
- 这些能力逐项验证后才开放，不将暂时明确拒绝描述为最终已实现。

## 当前环境与下一步

aapt2 构建更新：新增可选 FORGE_BUILD_AAPT2 和 aapt2.cmake，严格核对 protoc 3.21.12，
在构建目录生成 protobuf 文件，不修改固定源码。修正 Expat/PNG 生成头、Windows 符号链接对应的
Binder 真实 include 目录，以及 protobuf Android config.h。C++ 编译推进至最终链接；
链接仍缺 libincfs/SELinux/packagelistparser/libcutils/crypto/ssl/pcre 等目标，尚无可用 aapt2。
证据 `docs/progress/aapt2-source-configure.txt`、`aapt2-source-link-attempt.txt`。
下一步补齐实际静态依赖，不能以配置或对象文件编译成功代替可运行工具验收。

aapt2 重建准备：frameworks/base 及新增九个依赖仓库均下载完成，连同 zipalign 依赖共 19 个
源码提交已写入 sources.lock.json；新增 fetch-sources.ps1，验证已有 checkout 的固定提交，
不覆盖版本不符的本地目录，已在当前 checkout 执行通过。
匹配 protobuf 3.21.12 的官方 Windows protoc v21.12 已下载并执行版本检查；归档 SHA-256 为
`71852a30cf62975358edfcbbff93086e8857a079c8e4d6904881aa968d65c7f9`。
aapt2 的 CMake 依赖接入、源码适配和编译仍未完成，正式二进制保持原版本。

zipalign 源码重建已成功：NDK 27/API 28/ARM64，LOAD 均为 0x4000，动态依赖仅 Android 系统库。
适配包含 libbase 新日志 API 的弱符号/上游可用性检查、低 API fdsan 普通 close 分支、
Windows checkout 下 Binder 头文件真实目录，以及仅链接 zipalign 所需容器实现。
手机通过 ADB shell 执行新工具，对实际混编 APK 重新对齐并校验成功。
证据 `docs/progress/zipalign-source-{build,elf,device,sha256}.txt`。
尚未替换应用内工具，尚无 Android 28 或 16 KB 页设备运行证据；aapt2 源码重建仍待完成。
对签名 APK 重新对齐只用于工具测试，不能把该输出当作已验签可安装产物。

工具源码重建进展：九个 zipalign 依赖仓库已取回，具体提交固定于
`mobile/native-tools-build/sources.lock.json`；新增独立 NDK CMake 入口，包含 16 KB 链接参数，
不影响正式工具包。NDK 27/API 28 配置通过，首次编译因 libbase 的 fdsan 和 Android 30 日志 API
兼容性失败，尚未产出可用 zipalign。配置与失败证据为 `docs/progress/sdk-tools-source-*.txt`。
后续需完成静态日志实现与低 API 兼容适配，并构建、设备运行及 ELF 检查后才替换正式工具。

16 KB 工具替换调查：已检出 lzhiyong/android-sdk-tools 固定提交
`50713285d4de73dd36735928217523817ad16988`（research 下），其中有 aapt2/zipalign 的 NDK/CMake
构建脚本，源码依赖需按 AOSP 标签取回；不是可直接在当前目录构建的完整源码包。
下载并审计其 35.0.2 ARM64 静态发布包，两个工具仍有 0x1000 LOAD 段，因此未替换正式工具。
候选来源、归档/工具摘要和 ELF 段证据保存于 `docs/progress/sdk35-alignment-candidate.json`。
下一步是固定 AOSP 依赖并重建，而不是把工具版本升级等同于 16 KB 兼容。

16 KB ELF 审计：当前应用仅打包 ARM64，zstd-jni ARM64 LOAD 对齐为 64 KB；Lint 的 x86_64
警告不代表它进入当前 APK。直接检查还发现预编译 aapt2/zipalign 与自建 prootloader 的 4 KB 对齐。
已给 prootloader 实际目标补充 16 KB 链接参数，并修复载体复制的增量依赖（否则仍会打包旧 loader）。
重建后 loader 的全部 LOAD 对齐为 0x4000，证据 `docs/progress/loader-alignment-fixed.txt`；
原始审计为 `docs/progress/native-alignment-2026-10-01.json`。aapt2/zipalign 仍须更换或重建，
尚未验证 16 KB 实机运行，也不能以 loader 对齐代表整个 Agent 环境或原生编译链兼容。

2026-10-01 综合检查：native-build-core 86、native-compiler 17、app 70 项 JVM 测试均无失败
（共 173 项，未变化任务允许 Gradle 复用已有结果）。`:app:lintOnlineDebug` 成功，XML 中无 Error，
有 67 Warning / 1 Hint；包括 zstd-jni 1.5.6-9 的 x86_64 native 库 16 KB 对齐警告，
尚不能声明所有 ABI/页大小兼容。其余主要为 UseKtx、ObsoleteSdkInt、依赖版本提示。
证据：`docs/progress/regression-2026-10-01.txt`、`docs/progress/lint-2026-10-01.xml`。

产物恢复更新：成功构建后按项目保存 APK/buildId/摘要/源码快照；打开项目时重新验证输出路径、
摘要、包名、大小和工程归属，再恢复产物卡片与相符的安装结果。切换工程清空旧卡片，
构建运行期间不切换活动工程。恢复历史产物不意味着源码仍相同，安装前仍执行既有快照检查。
主应用/测试 APK 构建安装通过；实际离线编译模板后验证重新读取、错误工程拒绝及 APK 篡改拒绝，
1 项设备测试通过（3.216 秒），证据 `docs/progress/artifact-store-device-test.txt`。
尚未完成真实进程重启后可见产物卡片的 UI 验收；本测试重新创建存储对象，没有模拟进程死亡。

安装回调异常验证：缺少确认 Intent 时记录安装失败并拒绝迟到成功，未知会话直接忽略；
旧 Android 启动已安装应用失败时提示从桌面打开，避免回调崩溃。主 APK/测试 APK 重建安装通过，
接收器及会话存储 5 项设备测试通过（0.048 秒），证据为 `docs/progress/install-receiver-device-test.txt`。
这是直接调用接收器的验证，不是用户操作系统弹窗的全流程证据。

2026-10-01：安装结果加入持久化会话关联；未知会话、包名不符、重复终态、同一产物旧安装请求
均不能覆盖新请求。界面只接收当前 buildId 的结果，打开安装器失败仍保留构建成功产物。
主 APK/测试 APK 构建与安装通过，3 项设备测试通过（0.092 秒），日志为
`docs/progress/install-results-device-test.txt`。测试直接驱动结果存储层，不替代系统安装弹窗验收；
MIUI 分支仍使用外部安装 Activity，尚未有对应结果回调。

安装结果恢复补充：应用启动时读取持久化的最近安装结果并提示包名与状态；新安装请求清除旧提示，
完整产物卡片恢复仍未实现。主 APK/测试 APK 构建安装通过，三次独立 instrumentation 进程之间
显式 force-stop 主应用，验证会话保存、重启后记录取消、再次重启读取终态和拒绝迟到成功回调。
三步均通过，证据为 `docs/progress/install-recovery-*.txt`。这仍是存储/进程恢复验证，
不代替真实系统安装器回调或可见 UI 验收。测试需 `-e installRecovery true` 并按
prepareSession、receiveAfterRestart、readAfterSecondRestart 顺序分进程执行；普通套件会跳过。

2026-10-01：修复 Git 忽略规则误排除 `com/jarves/mh/build` 源码包的问题。
构建输出按模块目录定位，主应用原生构建源码和设备测试现可正常纳入 Git；
`app/build`、`native-compiler/build` 等生成目录仍被排除。

设备已连接并授权；可执行非可见的编译、安装与 Activity 验证。可见 UI 曾被锁屏阻挡。
桌面原来的 GraalVM 21 在 Android JDK image 转换失败，已使用经摘要验证的 Temurin 17。

共享编译模块已接入主应用；Java/JAR/AAR、官方 Manifest 合并、Agent guest 工具桥接、
模板、文本编辑/草稿、诊断定位和阶段缓存均已有分项证据，详见 P0–P8 报告。
下一步继续真实模型与可见 UI 验收、其余语言/模块能力、存储管理及交付可复现性。
目标保持 active，未做完成声明。

## 用户追加的收尾要求

2026-10-01：用户明确取消关机要求（“不需要关机了”）。此后完成、失败或阻塞均不执行关机；继续开发与验收。

历史要求（已撤销）：2026-09-27 用户要求“执行完成后关机”。按当时桌面上下文，关机对象为本机 Windows 电脑。
只有完整项目完成且验收证据满足目标后，才执行关机；不能在阶段性进展、失败、等待或阻塞时关机。
关机前保存代码、交付 APK、测试结果和最终报告，并确认本任务的构建/下载/测试进程已结束。
该要求已获用户授权，不需在真正完成时再次索取确认。若用户后续更改要求，以最新指示为准。
