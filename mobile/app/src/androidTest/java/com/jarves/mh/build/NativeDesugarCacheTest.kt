package com.jarves.mh.build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import java.nio.ByteBuffer
import dalvik.system.InMemoryDexClassLoader

@RunWith(AndroidJUnit4::class)
class NativeDesugarCacheTest {
    @Test fun cachesLibraryDefaultsAndLambdasWithoutKeepingOldApplicationCode() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File(context.filesDir, "workspaces/desugar-${UUID.randomUUID()}").apply { mkdirs() }
        fun write(path: String, text: String) = File(root, path).apply { parentFile!!.mkdirs(); writeText(text) }
        val library = File(root, "libs/default-methods.jar").apply { parentFile!!.mkdirs() }
        instrumentation.context.assets.open("desugar-fixtures/default-methods.jar").use { input -> library.outputStream().use { input.copyTo(it) } }
        write(".forge/project.json", """{"schemaVersion":1,"applicationId":"dev.forge.integration","compileSdk":29,"minSdk":21,"targetSdk":29,"manifest":"AndroidManifest.xml","java":["src"],"dependencies":[{"path":"libs/default-methods.jar"}]}""")
        write("AndroidManifest.xml", """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.forge.integration"><application><activity android:name=".MainActivity" android:exported="true"/></application></manifest>""")
        val source = write("src/dev/forge/integration/MainActivity.java", """package dev.forge.integration; public class MainActivity extends android.app.Activity {
            public static class Computation implements dev.forge.fixture.Rule { public int apply(int value) { return value * 2; } }
            public static int lambdaValue() { return dev.forge.fixture.Rule.identity().evaluate(40); }
            public void onCreate(android.os.Bundle state) { super.onCreate(state);
                String text="desugar:"+new Computation().evaluate(20)+":"+lambdaValue();
                android.widget.TextView view=new android.widget.TextView(this); view.setText(text); setContentView(view);
                try(java.io.FileOutputStream out=openFileOutput("desugar-result.txt",0)) { out.write(text.getBytes("UTF-8")); } catch(Exception error) { throw new RuntimeException(error); }
            }}""")
        val first = NativeBuildClient(context).build(root, offline = true) {}
        assertEquals(first.toString(), "SUCCEEDED", first.getString("state"))
        source.writeText(source.readText().replace("value * 2;", "value * 2 + 1;"))
        val changed = NativeBuildClient(context).build(root, offline = true) {}
        assertEquals(changed.toString(), "SUCCEEDED", changed.getString("state"))
        assertFalse(changed.getBoolean("cacheHit"))
        assertEquals(changed.toString(), 1, changed.getJSONObject("stageCacheHits").optInt("dependency-dex"))
        assertNotEquals(first.getString("sha256"), changed.getString("sha256"))
        val buffers = ZipFile(changed.getString("artifact")).use { zip ->
            zip.entries().asSequence().filter { it.name.matches(Regex("classes[0-9]*\\.dex")) }.sortedBy { it.name }
                .map { entry -> ByteBuffer.wrap(zip.getInputStream(entry).use { it.readBytes() }) }.toList().toTypedArray()
        }
        val loader = InMemoryDexClassLoader(buffers, ClassLoader.getSystemClassLoader().parent)
        val computation = loader.loadClass("dev.forge.integration.MainActivity${'$'}Computation")
        assertEquals(42, computation.getMethod("evaluate", Int::class.javaPrimitiveType).invoke(computation.getConstructor().newInstance(), 20))
        assertEquals(41, loader.loadClass("dev.forge.integration.MainActivity").getMethod("lambdaValue").invoke(null))
        File(context.filesDir, "desugar-integration-result.json").writeText(changed.toString(2))
    }
}
