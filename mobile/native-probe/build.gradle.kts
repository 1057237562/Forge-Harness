import java.util.Properties
plugins { id("com.android.application") }
val probe = rootProject.file("../probes/native-build")
val cache = rootProject.file("../.cache/native-tools")
val keyStore = File(cache, "probe-debug.jks")
val sdk = Properties().apply { rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) } }
    .getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME") ?: "missing-sdk"
val generatedAssets = layout.buildDirectory.dir("generated/probe-assets")
val generatedSigning = layout.buildDirectory.dir("generated/probe-signing")
val mainTools = rootProject.file("app/build/generated/forge-native")
val prepareKey = tasks.register<Exec>("prepareProbeKey") {
    outputs.file(keyStore)
    onlyIf { !keyStore.isFile }
    doFirst { cache.mkdirs() }
    commandLine(File(System.getProperty("java.home"), "bin/keytool.exe"), "-genkeypair", "-keystore", keyStore,
        "-storetype", "JKS", "-storepass", "android", "-keypass", "android", "-alias", "forge-probe",
        "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650", "-dname", "CN=Forge Development Probe")
}
val helperClasses = layout.buildDirectory.dir("helper-classes")
val compileHelpers = tasks.register<JavaCompile>("compileProbeHelpers") {
    source(fileTree(File(probe, "tools")) { include("*.java") })
    classpath = files(); destinationDirectory.set(helperClasses); options.release.set(8)
}
val exportKey = tasks.register<JavaExec>("exportProbeKey") {
    dependsOn(prepareKey, compileHelpers)
    inputs.file(keyStore); outputs.dir(generatedSigning)
    classpath = files(helperClasses); mainClass.set("ExportDebugKey")
    doFirst {
        val folder = generatedSigning.get().dir("toolchain").asFile.apply { mkdirs() }
        setArgs(listOf(keyStore.absolutePath, folder.absolutePath))
    }
}
val libraryClasses = layout.buildDirectory.dir("fixture-library-classes")
val compileLibrary = tasks.register<JavaCompile>("compileProbeLibrary") {
    source(fileTree(File(probe, "test-libs/src")) { include("**/*.java") })
    classpath = files(); destinationDirectory.set(libraryClasses); options.release.set(8)
}
val libraryJar = tasks.register<Jar>("probeLibraryJar") {
    dependsOn(compileLibrary); from(libraryClasses); archiveFileName.set("helper.jar")
    destinationDirectory.set(layout.buildDirectory.dir("fixture-library"))
}
val prepareAssets = tasks.register<Sync>("prepareProbeAssets") {
    dependsOn(":app:prepareForgeNativeTools", libraryJar)
    into(generatedAssets)
    from(File(probe, "fixture")) { into("fixture") }
    from("$sdk/platforms/android-29/android.jar") { into("toolchain") }
    from(File(mainTools, "assets/forge-native/profile.json")) { into("toolchain"); rename { "compiler-identity.txt" } }
    from(libraryJar) { into("test-libs") }
    from(File(probe, "THIRD_PARTY.md")) { into("licenses") }
    from(File(mainTools, "assets/forge-native/NOTICE.txt")) { into("licenses") }
}
android {
    namespace = "dev.forge.nativeprobe"; compileSdk = 36
    defaultConfig { applicationId = "dev.forge.nativeprobe"; minSdk = 28; targetSdk = 36; versionCode = 1; versionName = "0.2" }
    signingConfigs { create("probe") { storeFile = keyStore; storePassword = "android"; keyAlias = "forge-probe"; keyPassword = "android" } }
    buildTypes { getByName("debug") { signingConfig = signingConfigs.getByName("probe") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
    sourceSets.getByName("main") {
        manifest.srcFile(File(probe, "AndroidManifest.xml"))
        java.srcDir(File(probe, "src")); assets.srcDirs(generatedAssets, generatedSigning)
        jniLibs.srcDir(File(mainTools, "jniLibs"))
    }
    packaging.jniLibs.useLegacyPackaging = true
    packaging.jniLibs.keepDebugSymbols += "**/libforge_*.so"
    packaging.resources.merges += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/AL2.0", "META-INF/LGPL2.1")
    packaging.resources.excludes += "xsd/catalog.xml"
}
dependencies { implementation(project(":native-compiler")) }
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(prepareAssets, exportKey) }
tasks.matching { it.name.startsWith("validateSigning") }.configureEach { dependsOn(prepareKey) }
