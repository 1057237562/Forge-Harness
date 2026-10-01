package com.jarves.mh.build

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.forge.build.WorkspaceFiles
import dev.forge.compiler.NativeToolchain
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Calendar
import javax.security.auth.x500.X500Principal

/** Pinned APK assets/native components; no downloaded executable or Linux build environment. */
object NativeToolchainProvider {
    fun bundled(context: Context): Boolean = runCatching {
        context.assets.open("forge-native/profile.json").use { it.read() >= 0 }
    }.getOrDefault(false)

    @Synchronized
    fun prepare(context: Context, onProgress: (String) -> Unit = {}): NativeToolchain {
        val profile = context.assets.open("forge-native/profile.json").bufferedReader().use {
            JSONObject(it.readText())
        }
        check(profile.getInt("schemaVersion") == 1) { "Unsupported native toolchain profile" }
        val sdk = profile.getInt("sdk")
        val expected = profile.getString("androidJarSha256")
        check(expected.matches(Regex("[a-f0-9]{64}"))) { "Invalid SDK checksum" }
        val home = File(context.filesDir, "native-toolchains/$expected").apply { mkdirs() }
        val androidJar = File(home, "android.jar")
        if (!androidJar.isFile || WorkspaceFiles.sha256(androidJar) != expected) {
            onProgress("Preparing Android SDK $sdk API stubs")
            val temporary = File.createTempFile("android-", ".tmp", home)
            try {
                context.assets.open("forge-native/android-$sdk.jar").use { input ->
                    temporary.outputStream().use { input.copyTo(it) }
                }
                check(WorkspaceFiles.sha256(temporary) == expected) { "Bundled SDK checksum mismatch" }
                Files.move(temporary.toPath(), androidJar.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally { temporary.delete() }
        }
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val kotlinHash = profile.getString("kotlinStdlibSha256")
        check(kotlinHash.matches(Regex("[a-f0-9]{64}"))) { "Invalid Kotlin standard library checksum" }
        val kotlinStdlib = File(home, "kotlin-stdlib-$kotlinHash.jar")
        if (!kotlinStdlib.isFile || WorkspaceFiles.sha256(kotlinStdlib) != kotlinHash) {
            val temporary = File.createTempFile("kotlin-", ".tmp", home)
            try {
                context.assets.open("forge-native/kotlin-stdlib-1.9.24.jar").use { input -> temporary.outputStream().use { input.copyTo(it) } }
                check(WorkspaceFiles.sha256(temporary) == kotlinHash) { "Kotlin standard library checksum mismatch" }
                Files.move(temporary.toPath(), kotlinStdlib.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally { temporary.delete() }
        }
        val nativeFiles = profile.getJSONObject("nativeFiles")
        nativeFiles.keys().forEach { name ->
            check(name in setOf("libforge_aapt2.so", "libforge_zipalign.so")) { "Unknown native tool $name" }
            val file = File(nativeDir, name)
            check(file.isFile && WorkspaceFiles.sha256(file) == nativeFiles.getString(name)) {
                "Native tool checksum mismatch: $name. Reinstall a verified Forge APK."
            }
        }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "forge-native-debug-v1"
        if (!store.containsAlias(alias)) {
            onProgress("Creating this installation's debug signing identity")
            val start = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.time
            val end = Calendar.getInstance().apply { add(Calendar.YEAR, 30) }.time
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                initialize(
                    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                        .setKeySize(2048)
                        .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                        .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                        .setCertificateSubject(X500Principal("CN=Forge Local Debug"))
                        .setCertificateNotBefore(start).setCertificateNotAfter(end)
                        .build(),
                )
                generateKeyPair()
            }
        }
        val entry = store.getEntry(alias, null) as KeyStore.PrivateKeyEntry
        return NativeToolchain(
            sdk, androidJar, File(nativeDir, "libforge_aapt2.so"), File(nativeDir, "libforge_zipalign.so"),
            mapOf("LD_LIBRARY_PATH" to nativeDir.absolutePath), entry.privateKey,
            entry.certificate as X509Certificate, profile.getString("compilerIdentity"),
            dev.forge.kotlin.KotlinBackendAdapter(), kotlinStdlib,
        )
    }
}
