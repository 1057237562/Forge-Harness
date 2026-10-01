package com.jarves.mh.runtimevalidation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jarves.mh.build.NativeToolchainProvider
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.cli.common.ExitCode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.UUID
import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.OutputMode
import dalvik.system.InMemoryDexClassLoader
import java.nio.ByteBuffer

/** ART capability probe; never launches a Linux compiler or JVM process. */
@RunWith(AndroidJUnit4::class)
class KotlinCompilerProbeTest {
    @Test fun compilesKotlinOnAndroidArt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File(context.cacheDir, "kotlin-probe-${UUID.randomUUID()}").apply { mkdirs() }
        val stdlib = File(root, "kotlin-stdlib.jar")
        instrumentation.context.assets.open("kotlin-tools/kotlin-stdlib-1.9.24.jar").use { input -> stdlib.outputStream().use { input.copyTo(it) } }
        val sdk = NativeToolchainProvider.prepare(context).androidJar
        val extension = File(root, "META-INF/extensions/compiler.xml").apply { parentFile!!.mkdirs() }
        K2JVMCompiler::class.java.classLoader!!.getResourceAsStream("META-INF/extensions/compiler.xml")!!.use { input -> extension.outputStream().use { input.copyTo(it) } }
        val source = File(root, "Answer.kt").apply { writeText("package dev.forge.kotlinprobe\nclass Answer { fun answer(): Int = listOf(20, 22).sum() }\n") }
        val output = File(root, "classes").apply { mkdirs() }
        val log = ByteArrayOutputStream()
        try {
            val result = K2JVMCompiler().exec(PrintStream(log), "-kotlin-home", root.absolutePath, "-Xintellij-plugin-root=${root.absolutePath}", "-no-jdk", "-no-stdlib", "-no-reflect", "-jvm-target", "1.8",
                "-classpath", sdk.absolutePath + File.pathSeparator + stdlib.absolutePath, "-d", output.absolutePath, source.absolutePath)
            assertEquals(log.toString("UTF-8"), ExitCode.OK, result)
            assertTrue("Kotlin compiler did not generate class output", File(output, "dev/forge/kotlinprobe/Answer.class").isFile)
            val dex = File(root, "dex").apply { mkdirs() }
            D8.run(D8Command.builder()
                .addProgramFiles(File(output, "dev/forge/kotlinprobe/Answer.class").toPath(), stdlib.toPath())
                .addLibraryFiles(sdk.toPath()).setMinApiLevel(28).setOutput(dex.toPath(), OutputMode.DexIndexed).build())
            val buffers = dex.listFiles()!!.filter { it.extension == "dex" }.sortedBy { it.name }.map { ByteBuffer.wrap(it.readBytes()) }.toTypedArray()
            assertTrue("D8 did not emit dex", buffers.isNotEmpty())
            val loader = InMemoryDexClassLoader(buffers, ClassLoader.getSystemClassLoader().parent)
            assertSame("Execution must use the D8-converted stdlib, not the host app's Kotlin runtime", loader, loader.loadClass("kotlin.collections.CollectionsKt").classLoader)
            val answerClass = loader.loadClass("dev.forge.kotlinprobe.Answer")
            assertEquals(42, answerClass.getMethod("answer").invoke(answerClass.getConstructor().newInstance()))
            log.write("\nPASS: Kotlin class compiled on ART, converted by D8 and executed answer() == 42\n".toByteArray())
            val broken = File(root, "Broken.kt").apply { writeText("package dev.forge.kotlinprobe\nclass Broken { val value: MissingType? = null }\n") }
            val rejected = K2JVMCompiler().exec(PrintStream(log), "-kotlin-home", root.absolutePath, "-Xintellij-plugin-root=${root.absolutePath}",
                "-no-jdk", "-no-stdlib", "-no-reflect", "-jvm-target", "1.8", "-classpath", sdk.absolutePath + File.pathSeparator + stdlib.absolutePath,
                "-d", File(root, "broken-classes").absolutePath, broken.absolutePath)
            assertEquals(log.toString("UTF-8"), ExitCode.COMPILATION_ERROR, rejected)
            assertFalse(File(root, "broken-classes/dev/forge/kotlinprobe/Broken.class").exists())
        } finally { File(context.filesDir, "kotlin-compiler-probe.log").writeText(log.toString("UTF-8")) }
    }
}
