# 构建和验证 Forge Mobile

当前主路径为 Windows PowerShell 7。桌面构建使用 JDK 17 与仓库 Gradle Wrapper；
手机编译用户工程使用 ECJ/aapt2/D8，不依赖桌面进程或 Linux 编译环境。

## 依赖准备

1. 安装 JDK 17，设置 JAVA_HOME。不要使用缺少必要模块的裁剪 JRE。
2. 安装 Android SDK Platform 29、36 和 Build Tools 30.0.3，并准备 AGP 要求的桌面 Build Tools。
   主应用原生 Agent launcher 还需要 NDK 27.2.12479018 与 CMake 3.22.1。
3. 在 mobile/local.properties 写入 `sdk.dir=你的SDK绝对路径`（Windows 建议使用 `/`），或设置 ANDROID_HOME。
4. 执行 `git submodule update --init --recursive`，获取 mobile/native 下固定提交的 Agent launcher 依赖。
5. 执行 `./mobile/scripts/prepare-native-tools.ps1`（也可显式传入 `-Sdk E:/Android_SDK`）。
   主应用默认使用从固定 AOSP 提交和本仓库适配源码重建的 aapt2/zipalign，输出位于
   `.cache/native-tools/forge-source-candidate`。脚本验证源码 HEAD/受跟踪文件修改与 protoc 下载摘要，
   使用 NDK 27/CMake 3.22.1 构建 ARM64、API 28、16 KB ELF 对齐的工具。
   独立探针复用主应用生成的同一组工具；工具 ELF 对齐不等同于已通过 16 KB 设备执行验收。

若企业/本机代理的证书已安装在 Windows 信任库，而 JDK 默认信任库不包含，可为当前会话设置：

```powershell
$env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE'
```

这使用系统信任库，不关闭 TLS 证书校验。普通环境不必设置。

## 主应用

```powershell
cd mobile
./gradlew.bat :native-build-core:test :native-compiler:test :app:assembleOnlineDebug :app:assembleOnlineDebugAndroidTest '-PmhNdkVersion=27.2.12479018' --console=plain
```

APK：mobile/app/build/outputs/apk/online/debug/app-online-debug.apk。
安装主 APK 和 androidTest APK 后，可用 ADB 运行 `com.jarves.mh.build` 仪器测试。
`com.jarves.mh.runtimevalidation.AgentNativeBuildTest` 会额外下载可选 Agent runtime，需显式单独运行。
模型驱动端到端验收还需要在应用中配置服务商凭证；不要把密钥写入仓库或测试日志。

## 独立原生探针

```powershell
./probes/native-build/build.ps1 -Jdk $env:JAVA_HOME
./probes/native-build/test-device.ps1 -Adb adb -BuildOnly
```

build.ps1 通过 :native-probe 构建宿主 APK，复用共享编译模块及其完整依赖。
测试设备必须是已授权的 ARM64 Android；BuildOnly 跳过可见 UI 校验，不代表界面通过。
该测试 APK 内置独立的开发测试密钥，只供探针样例签名；主应用使用 AndroidKeyStore。

这份说明与脚本已在当前环境验证，尚未声明空白机器/无缓存 CI 复现验收已通过。
各能力与未完成项见 IMPLEMENTATION.md，不要将调试 APK 当成完整发布版本。
