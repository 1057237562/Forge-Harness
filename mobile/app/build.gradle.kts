import java.util.Properties
import java.security.MessageDigest
import org.gradle.api.tasks.Sync

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val testSecrets = Properties().apply {
    val secretsFile = rootProject.file("test-secrets.properties")
    if (secretsFile.isFile) secretsFile.inputStream().use(::load)
}
val playBuild = providers.gradleProperty("playBuild").orNull?.toBoolean() == true ||
    providers.gradleProperty("playFeasibility").orNull?.toBoolean() == true
val privacyPolicyUrl = providers.gradleProperty("privacyPolicyUrl").orNull
    ?: "https://github.com/techjarves/Mobile-Harness/blob/main/PRIVACY.md"
val uploadStorePath = providers.environmentVariable("MH_UPLOAD_STORE_FILE").orNull
val uploadStorePassword = providers.environmentVariable("MH_UPLOAD_STORE_PASSWORD").orNull
val uploadKeyAlias = providers.environmentVariable("MH_UPLOAD_KEY_ALIAS").orNull
val uploadKeyPassword = providers.environmentVariable("MH_UPLOAD_KEY_PASSWORD").orNull
val hasUploadSigning = listOf(
    uploadStorePath,
    uploadStorePassword,
    uploadKeyAlias,
    uploadKeyPassword,
).all { !it.isNullOrBlank() }
val runtimeReleaseBaseUrl =
    "https://github.com/techjarves/Mobile-Harness/releases/download/runtime-2026.09.4"
val appUpdateManifestUrl =
    "" // Forge must never install an upstream Mobile-Harness update over its own build.
val runtimeBundleDir = rootProject.layout.projectDirectory.dir("dist/runtime-bundles")
val generatedRuntimeAssets = layout.buildDirectory.dir("generated/runtime-assets")
val kotlinProbeStdlib = configurations.create("kotlinProbeStdlib") { isTransitive = false }
dependencies.add(kotlinProbeStdlib.name, "org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
val kotlinProbeAssets = layout.buildDirectory.dir("generated/kotlin-probe-assets")
val prepareKotlinProbeAssets = tasks.register<Sync>("prepareKotlinProbeAssets") {
    from(kotlinProbeStdlib); into(kotlinProbeAssets.map { it.dir("kotlin-tools") })
}
val desugarFixtureClasses = layout.buildDirectory.dir("desugar-fixture-classes")
val compileDesugarFixture = tasks.register<JavaCompile>("compileDesugarFixture") {
    source(fileTree("src/androidTestFixtures/java") { include("**/*.java") })
    classpath = files(); destinationDirectory.set(desugarFixtureClasses); options.release.set(8)
}
val desugarFixtureAssets = layout.buildDirectory.dir("generated/desugar-fixture-assets")
val packageDesugarFixture = tasks.register<Jar>("packageDesugarFixture") {
    dependsOn(compileDesugarFixture); from(desugarFixtureClasses); archiveFileName.set("default-methods.jar")
    destinationDirectory.set(desugarFixtureAssets.map { it.dir("desugar-fixtures") })
}
val forgeTools = rootProject.file(providers.gradleProperty("forgeNativeToolsDir")
    .getOrElse("../.cache/native-tools/forge-source-candidate"))
val forgeGenerated = layout.buildDirectory.dir("generated/forge-native")
val localSdk = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME") ?: "missing-sdk"
val forgeSdkJar = file("$localSdk/platforms/android-29/android.jar")
val forgeD8 = file("$localSdk/build-tools/30.0.3/lib/d8.jar")
val forgeApksig = file("$localSdk/build-tools/30.0.3/lib/apksigner.jar")
val forgeSources = files(
    rootProject.fileTree("native-build-core/src/main/java"),
    rootProject.fileTree("native-compiler/src/main/java"),
    rootProject.fileTree("native-kotlin/src/main/java"),
    rootProject.file("native-kotlin/build.gradle.kts"),
    rootProject.fileTree("buildSrc/src/main/java"),
    rootProject.file("native-compiler/build.gradle.kts"),
    rootProject.file("build.gradle.kts"),
    fileTree("src/main/java/com/jarves/mh/build"),
)
fun forgeSha(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(32 * 1024)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}
val prepareForgeNativeTools = tasks.register("prepareForgeNativeTools") {
    inputs.files(forgeSdkJar, forgeD8, forgeApksig, forgeSources, kotlinProbeStdlib)
    inputs.files(listOf("aapt2", "zipalign", "NOTICE.txt", "notice-inventory.json", "provenance.json").map { File(forgeTools, it) })
    outputs.dir(forgeGenerated)
    doLast {
        check(forgeSdkJar.isFile && File(forgeTools, "aapt2").isFile) {
            "Run mobile/scripts/prepare-native-tools.ps1 and install SDK Platform 29 / Build Tools 30.0.3."
        }
        val base = forgeGenerated.get().asFile
        val provenanceFile = File(forgeTools, "provenance.json")
        val provenance = groovy.json.JsonSlurper().parse(provenanceFile) as Map<*, *>
        check(provenance["schemaVersion"] == 1 && provenance["abi"] == "arm64-v8a") { "Unsupported native tool provenance" }
        val recordedTools = provenance["tools"] as Map<*, *>
        listOf("aapt2", "zipalign").forEach { name ->
            check(recordedTools[name] == forgeSha(File(forgeTools, name))) { "Native tool differs from build provenance: $name" }
        }
        check(provenance["noticeSha256"] == forgeSha(File(forgeTools, "NOTICE.txt"))) { "Native tool notices differ from provenance" }
        check(provenance["noticeInventorySha256"] == forgeSha(File(forgeTools, "notice-inventory.json"))) { "Native notice inventory differs from provenance" }
        val assets = File(base, "assets/forge-native").apply { mkdirs() }
        val jni = File(base, "jniLibs/arm64-v8a").apply { mkdirs() }
        provenanceFile.copyTo(File(assets, "provenance.json"), overwrite = true)
        File(forgeTools, "notice-inventory.json").copyTo(File(assets, "notice-inventory.json"), overwrite = true)
        forgeSdkJar.copyTo(File(assets, "android-29.jar"), overwrite = true)
        val kotlinStdlib = kotlinProbeStdlib.singleFile
        kotlinStdlib.copyTo(File(assets, "kotlin-stdlib-1.9.24.jar"), overwrite = true)
        val nativeFiles = linkedMapOf(
            "libforge_aapt2.so" to File(forgeTools, "aapt2"),
            "libforge_zipalign.so" to File(forgeTools, "zipalign"),
        )
        // The upstream archive contains an unrelated x86_64 host libc++. The tools are static ARM64 ELF.
        File(jni, "libc++.so").takeIf { it.exists() }?.let { check(it.delete()) }
        nativeFiles.forEach { (name, source) ->
            val header = source.inputStream().use { it.readNBytes(20) }
            check(header.size == 20 && header[0] == 0x7f.toByte() && header[1] == 69.toByte() &&
                header[2] == 76.toByte() && header[3] == 70.toByte() && header[4] == 2.toByte() &&
                header[5] == 1.toByte() && (header[18].toInt() and 255) == 183 && header[19] == 0.toByte()) {
                "Expected ARM64 ELF tool: $source"
            }
            source.copyTo(File(jni, name), overwrite = true)
        }
        File(forgeTools, "NOTICE.txt").copyTo(File(assets, "NOTICE.txt"), overwrite = true)
        val identity = (forgeSources.files.sortedBy { it.path }.map { it.relativeTo(rootProject.projectDir).invariantSeparatorsPath + "=" + forgeSha(it) } +
            listOf("ecj=3.18.0", "d8=" + forgeSha(forgeD8), "apksig=" + forgeSha(forgeApksig))).joinToString("\n")
        val json = groovy.json.JsonOutput.toJson(mapOf(
            "schemaVersion" to 1, "sdk" to 29, "compilerIdentity" to identity,
            "androidJarSha256" to forgeSha(forgeSdkJar),
            "kotlinStdlibSha256" to forgeSha(kotlinStdlib),
            "nativeFiles" to nativeFiles.mapValues { forgeSha(it.value) },
        ))
        File(assets, "profile.json").writeText(json)
    }
}

val prepareBundledAgentAssets = tasks.register<Sync>("prepareBundledAgentAssets") {
    from(runtimeBundleDir.file("pocketdev-agy-arm64-2026.09.1.tar.zst"))
    into(generatedRuntimeAssets.map { it.dir("shared/runtime") })
}

val prepareOfflineRuntimeAssets = tasks.register<Sync>("prepareOfflineRuntimeAssets") {
    from(
        runtimeBundleDir.file("pocketdev-core-arm64-2026.09.5.tar.zst"),
        runtimeBundleDir.file("pocketdev-claude-arm64-2026.09.1.tar.zst"),
        runtimeBundleDir.file("pocketdev-python-arm64-2026.09.2.tar.zst"),
        runtimeBundleDir.file("pocketdev-dsh-arm64-2026.09.1.tar.zst"),
    )
    into(generatedRuntimeAssets.map { it.dir("offline/runtime") })
}

fun buildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    sourceSets.getByName("androidTest").assets.srcDir(kotlinProbeAssets)
    sourceSets.getByName("androidTest").assets.srcDir(desugarFixtureAssets)
    namespace = "com.jarves.mh"
    compileSdk = 36
    // F-Droid's r26b recipe installs 26.1.10909125. Keep AGP from selecting
    // its newer default NDK; local developers may override this explicitly.
    ndkVersion = providers.gradleProperty("mhNdkVersion").orNull ?: "26.1.10909125"

    signingConfigs {
        if (hasUploadSigning) {
            create("upload") {
                storeFile = rootProject.file(checkNotNull(uploadStorePath))
                storePassword = checkNotNull(uploadStorePassword)
                keyAlias = checkNotNull(uploadKeyAlias)
                keyPassword = checkNotNull(uploadKeyPassword)
            }
        }
    }

    defaultConfig {
        applicationId = "dev.forge.mobile"
        minSdk = 28
        // The direct APK retains the proven target-28 PRoot execution path. The
        // Play build targets current Android while its runtime path is validated.
        targetSdk = if (playBuild) 36 else 28
        // Keep literal defaults so F-Droid's static manifest parser can detect
        // the tagged release. Gradle properties may still override Play builds.
        versionCode = 6
        versionName = "0.0.1-alpha.1"
        providers.gradleProperty("appVersionCode").orNull?.toIntOrNull()?.let { versionCode = it }
        providers.gradleProperty("appVersionName").orNull?.let { versionName = it }

        ndk.abiFilters += "arm64-v8a"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        buildConfigField("boolean", "IS_PLAY_BUILD", playBuild.toString())
        buildConfigField("String", "PRIVACY_POLICY_URL", buildConfigString(privacyPolicyUrl))

        buildConfigField(
            "String",
            "TEST_OPENROUTER_API_KEY",
            "\"\"",
        )
    }

    flavorDimensions += "runtimeDelivery"
    productFlavors {
        create("online") {
            dimension = "runtimeDelivery"
            buildConfigField("boolean", "OFFLINE_RUNTIME_BUNDLES", "false")
            buildConfigField("String", "RUNTIME_RELEASE_BASE_URL", buildConfigString(runtimeReleaseBaseUrl))
            buildConfigField("String", "APP_UPDATE_MANIFEST_URL", buildConfigString(appUpdateManifestUrl))
            buildConfigField("String", "APP_VARIANT", "\"online\"")
        }
        create("offline") {
            dimension = "runtimeDelivery"
            buildConfigField("boolean", "OFFLINE_RUNTIME_BUNDLES", "true")
            buildConfigField("String", "RUNTIME_RELEASE_BASE_URL", buildConfigString(runtimeReleaseBaseUrl))
            buildConfigField("String", "APP_UPDATE_MANIFEST_URL", buildConfigString(appUpdateManifestUrl))
            buildConfigField("String", "APP_VARIANT", "\"offline\"")
        }
    }

    sourceSets.getByName("offline").assets.srcDir(generatedRuntimeAssets.map { it.dir("offline") })
    sourceSets.getByName("main").assets.srcDir(generatedRuntimeAssets.map { it.dir("shared") })
    sourceSets.getByName("main").assets.srcDir(forgeGenerated.map { it.dir("assets") })
    sourceSets.getByName("main").jniLibs.srcDir(forgeGenerated.map { it.dir("jniLibs") })

    buildTypes {
        debug {
            buildConfigField(
                "String",
                "TEST_OPENROUTER_API_KEY",
                buildConfigString(testSecrets.getProperty("openrouter.apiKey", "")),
            )
        }
        release {
            isMinifyEnabled = false
            if (hasUploadSigning) {
                signingConfig = signingConfigs.getByName("upload")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions.jvmTarget = "17"
    buildFeatures {
        compose = true
        buildConfig = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    packaging.resources.merges += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    // Desktop SDK repository catalogs are unrelated to manifest merging or bundled tool provisioning.
    packaging.resources.excludes += "xsd/catalog.xml"
    packaging.jniLibs.useLegacyPackaging = true
    packaging.jniLibs.keepDebugSymbols += "**/libforge_*.so"
    androidResources.noCompress += "zst"
}

tasks.matching { it.name.startsWith("mergeOffline") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(prepareOfflineRuntimeAssets) }

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(prepareBundledAgentAssets) }
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(prepareForgeNativeTools) }
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("AndroidTestAssets") }.configureEach { dependsOn(prepareKotlinProbeAssets) }
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("AndroidTestAssets") }.configureEach { dependsOn(packageDesugarFixture) }

tasks.matching { it.name.contains("lint", ignoreCase = true) }
    .configureEach { dependsOn(prepareBundledAgentAssets) }

tasks.matching { it.name.contains("Offline") && it.name.contains("lint", ignoreCase = true) }
    .configureEach { dependsOn(prepareOfflineRuntimeAssets) }

tasks.register("playReadinessCheck") {
    group = "verification"
    description = "Checks configuration required before uploading a Mobile Harness Play bundle."
    doLast {
        check(playBuild) { "Run with -PplayBuild=true." }
        check(privacyPolicyUrl.startsWith("https://")) {
            "privacyPolicyUrl must be a public HTTPS URL."
        }
        check(hasUploadSigning) {
            "Set MH_UPLOAD_STORE_FILE, MH_UPLOAD_STORE_PASSWORD, MH_UPLOAD_KEY_ALIAS, and MH_UPLOAD_KEY_PASSWORD."
        }
    }
}

dependencies {
    implementation(project(":native-kotlin"))
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("com.github.luben:zstd-jni:1.5.6-9@aar")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250107")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:1.9.24")
    androidTestImplementation(files(forgeD8))
    androidTestImplementation(project(":native-kotlin"))
    androidTestImplementation("org.eclipse.jdt:ecj:3.18.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
