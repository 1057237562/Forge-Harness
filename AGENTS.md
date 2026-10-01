# Repository Guidelines

## Project Structure & Module Organization

- `mobile/app/`: Android application, Kotlin/Compose UI, and native-build integration. Resources live in `src/main/res/`; assets in `src/main/assets/` and `mobile/assets/`.
- `mobile/native-build-core/`, `native-compiler/`, and `native-kotlin/`: shared project/build models and Java/Kotlin compilation engines. `mobile/buildSrc/` contains desktop dependency transforms.
- `mobile/native-probe/` and `probes/native-build/`: device validation host, fixtures, and PowerShell regression scripts.
- Module `src/test/` directories contain JVM tests; `mobile/app/src/androidTest/` contains instrumentation tests.
- `docs/`: specifications, build instructions, implementation checklist, and progress evidence. `mobile/native/` holds pinned native submodules.

## Build, Test, and Development Commands

Use PowerShell 7 and JDK 17. Configure the Android SDK through `mobile/local.properties` or `ANDROID_HOME`; follow `docs/BUILDING.md` for SDK, NDK, and CMake requirements.

From the repository root:

```powershell
git submodule update --init --recursive
./mobile/scripts/prepare-native-tools.ps1
```

These initialize native dependencies and download checksum-verified compiler tools. From `mobile/`:

```powershell
./gradlew.bat :native-build-core:test :native-compiler:test :app:testOnlineDebugUnitTest
./gradlew.bat :app:assembleOnlineDebug :app:assembleOnlineDebugAndroidTest '-PmhNdkVersion=27.2.12479018'
```

These run JVM tests and build the application/test APKs. For device regression, run from the root:

```powershell
./probes/native-build/build.ps1 -Jdk $env:JAVA_HOME
./probes/native-build/test-device.ps1 -Serial <device-serial>
```

## Coding Style & Naming Conventions

Use four-space indentation and Kotlin's configured official style. Preserve Java 8 compatibility in shared Java modules. Use `PascalCase` classes, `camelCase` methods/properties, and existing `dev.forge.*` or `com.jarves.mh.*` packages. Match surrounding formatting. No dedicated formatter or coverage gate is configured. Run Android lint with `./gradlew.bat :app:lintOnlineDebug` from `mobile/`.

## Testing Guidelines

JVM tests use JUnit 4; instrumentation uses AndroidX Test. Name files `*Test.java` or `*Test.kt` and mirror production packages. Add regression coverage for changed compiler, cache, archive, or dependency behavior. Device checks require an authorized ARM64 Android device. `-BuildOnly` skips visible UI verification; report that limitation. Run optional Agent runtime tests separately.

## Commit & Pull Request Guidelines

The root repository has no commit history yet. Use short imperative subjects, such as `Fix archive path validation`. Describe the change, affected modules, linked issues, and validation results in PRs; include screenshots for UI changes and device details for compiler changes. Update relevant implementation/progress documentation.

## Architecture & Configuration

Keep on-device compilation independent of Termux, PRoot, and Gradle; reuse shared engines rather than duplicating probe logic. Keep credentials, signing keys, SDK paths, caches, and generated builds out of commits. Preserve tool checksums and third-party notices.
