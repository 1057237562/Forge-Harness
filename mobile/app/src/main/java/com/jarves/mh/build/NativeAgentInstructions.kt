package com.jarves.mh.build

/** Actual currently shipped capabilities, shared by all CLI drivers. */
object NativeAgentInstructions {
    val text = """
        Android projects compile with Forge's Android-native ECJ/aapt2/D8 engine, not in this Linux environment.
        Never run or install Gradle, a Linux JDK/Android SDK, Termux or a PRoot build toolchain to compile an APK.
        The current native profile supports Java 8, Kotlin 1.9.24 (JVM target 1.8), mixed Java/Kotlin and XML, compileSdk 29, minSdk 28, targetSdk 29, local JAR/AAR libraries and fixed-version Maven dependencies with transitive resolution and caching.
        Basic AAR resources, generated R classes, assets and official Manifest merging are connected. Broader AndroidX and native-library compatibility is still under validation; use actual build results as evidence.
        Enable Kotlin using an explicit kotlin:["src/kotlin"] array in .forge/project.json. java may be [] for Kotlin-only projects. Keep Kotlin runtime dependencies aligned to version 1.9.24.
        Kotlin scripts/Gradle Kotlin DSL execution, Compose, compileOnly AARs, Prefab and custom Gradle plugins are not available in the native engine yet; explain unsupported requirements rather than pretending they built.
        Pin conflicting Maven versions explicitly in the project. Forge does not silently guess between conflicting transitive versions.
        Prefer a .forge/project.json file with schemaVersion:1, applicationId, namespace, compileSdk:29, minSdk:28, targetSdk:29, manifest:"AndroidManifest.xml", java:["src"], resources:["res"], assets:["assets"].
        Put Java packages under src/, XML resources under res/, and the manifest at the project root.
        Build with: node /pocket-bridge/forge-build.cjs --project . (or a workspace-relative Android project subdirectory). Add --offline to forbid repository network access.
        The command returns JSON containing state, buildId, artifact hash, diagnostics and remainingBuilds. Each session allows at most 4 build attempts; inspect diagnostics and make targeted fixes within that budget.
        Finish file writes before calling the build tool, wait for its result, and do not run builds in the background. Never print or persist FORGE_BUILD_TOKEN.
        A successful build does not mean installation or UI testing passed. Installation remains a user-facing workspace action; only report verification supported by actual results.
    """.trimIndent()
}
