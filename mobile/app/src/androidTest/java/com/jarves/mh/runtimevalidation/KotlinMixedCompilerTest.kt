package com.jarves.mh.runtimevalidation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jarves.mh.build.NativeToolchainProvider
import dev.forge.kotlin.NativeKotlinCompiler
import dev.forge.compiler.BuildListener
import dev.forge.compiler.Cancellation
import com.android.tools.r8.*
import org.eclipse.jdt.internal.compiler.batch.Main
import dalvik.system.InMemoryDexClassLoader
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.*
import java.nio.ByteBuffer
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class KotlinMixedCompilerTest {
    @Test fun kotlinAndJavaCanCallEachOtherOnAndroid() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File(context.cacheDir, "kotlin-mixed-${UUID.randomUUID()}").apply { mkdirs() }
        val stdlib = File(root, "stdlib.jar")
        instrumentation.context.assets.open("kotlin-tools/kotlin-stdlib-1.9.24.jar").use { input -> stdlib.outputStream().use { input.copyTo(it) } }
        val sdk = NativeToolchainProvider.prepare(context).androidJar
        val java = File(root, "JavaBridge.java").apply { writeText("package dev.forge.mixed; public class JavaBridge { public static int base() { return 40; } public static int callKotlin() { return new KotlinBridge().value(); } }") }
        val kotlin = File(root, "KotlinBridge.kt").apply { writeText("package dev.forge.mixed\nclass KotlinBridge { fun value(): Int = JavaBridge.base() + 2 }\n") }
        val classes = File(root, "classes")
        val events = StringBuilder()
        val listener = object : BuildListener {
            override fun log(text: String) { events.append(text).append('\n') }
            override fun diagnostic(severity: String, file: String?, line: Int, column: Int, message: String) { events.append("$severity:$file:$line:$column:$message\n") }
        }
        NativeKotlinCompiler.compile(sdk, stdlib, emptyList(), listOf(kotlin), listOf(java), classes, File(root, "environment"), Cancellation(), listener)
        assertTrue(File(classes, "dev/forge/mixed/KotlinBridge.class").isFile)
        assertFalse("Kotlin must not claim to compile Java", File(classes, "dev/forge/mixed/JavaBridge.class").exists())
        val javaLog = StringWriter()
        val writer = PrintWriter(javaLog)
        val compiled = Main(writer, writer, false, null, null).compile(arrayOf("-source", "1.8", "-target", "1.8", "-proc:none", "-bootclasspath", sdk.path,
            "-classpath", listOf(sdk.path, stdlib.path, classes.path).joinToString(File.pathSeparator), "-d", classes.path, java.path))
        writer.flush(); assertTrue(javaLog.toString(), compiled)
        val dex = File(root, "dex").apply { mkdirs() }
        val program = classes.walkTopDown().filter { it.isFile && it.extension == "class" }.map { it.toPath() }.toList() + listOf(stdlib.toPath())
        D8.run(D8Command.builder().addProgramFiles(program).addLibraryFiles(sdk.toPath()).setMinApiLevel(28).setOutput(dex.toPath(), OutputMode.DexIndexed).build())
        val buffers = dex.listFiles()!!.filter { it.extension == "dex" }.sortedBy { it.name }.map { ByteBuffer.wrap(it.readBytes()) }.toTypedArray()
        val loader = InMemoryDexClassLoader(buffers, ClassLoader.getSystemClassLoader().parent)
        assertEquals(42, loader.loadClass("dev.forge.mixed.JavaBridge").getMethod("callKotlin").invoke(null))
        File(context.filesDir, "kotlin-mixed-result.txt").writeText(events.toString() + "PASS: Java -> Kotlin -> Java returned 42 using ART compilation and D8\n")
    }
}
