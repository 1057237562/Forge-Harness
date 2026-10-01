package com.jarves.mh.build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NativeKotlinBuildTest {
    @Test fun buildsMixedApkAndInvalidatesKotlinChanges() = runBlocking {
        verifyMixedProject(false)
    }
    @Test fun buildsWithExplicitMatchingMavenStandardLibrary() = runBlocking {
        verifyMixedProject(true)
    }
    private suspend fun verifyMixedProject(explicitStdlib: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "workspaces/kotlin-apk-${UUID.randomUUID()}").apply { mkdirs() }
        fun write(path: String, text: String) = File(root, path).apply { parentFile!!.mkdirs(); writeText(text) }
        val dependencies = if (explicitStdlib) """[{"coordinate":"org.jetbrains.kotlin:kotlin-stdlib:1.9.24"}]""" else "[]"
        write(".forge/project.json", """{"schemaVersion":1,"applicationId":"dev.forge.integration","compileSdk":29,"minSdk":28,"targetSdk":29,"manifest":"AndroidManifest.xml","java":["src/java"],"kotlin":["src/kotlin"],"resources":[],"repositories":["https://repo.maven.apache.org/maven2/"],"dependencies":$dependencies}""")
        write("AndroidManifest.xml", """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.forge.integration"><application><activity android:name=".MainActivity" android:exported="true"/></application></manifest>""")
        write("src/java/dev/forge/integration/JavaBridge.java", "package dev.forge.integration; public class JavaBridge { public static int base() { return 40; } }")
        write("src/java/dev/forge/integration/MainActivity.java", """package dev.forge.integration; public class MainActivity extends android.app.Activity {
            public void onCreate(android.os.Bundle b) { super.onCreate(b); String text=new KotlinValue().message();
            android.widget.TextView view=new android.widget.TextView(this); view.setText(text); setContentView(view);
            try(java.io.FileOutputStream out=openFileOutput("kotlin-result.txt",0)) { out.write(text.getBytes("UTF-8")); } catch(Exception e) { throw new RuntimeException(e); }
            }}""")
        val source = write("src/kotlin/dev/forge/integration/KotlinValue.kt", "package dev.forge.integration\nclass KotlinValue { fun count(): Int = listOf(JavaBridge.base(), 2).sum(); fun message(): String = \"kotlin-apk:\" + count() }\n")
        val first = NativeBuildClient(context).build(root, offline = !explicitStdlib) {}
        assertEquals(first.toString(), "SUCCEEDED", first.getString("state"))
        source.writeText(source.readText().replace(", 2)", ", 3)"))
        val next = NativeBuildClient(context).build(root, offline = true) {}
        assertEquals(next.toString(), "SUCCEEDED", next.getString("state"))
        assertFalse(next.getBoolean("cacheHit")); assertNotEquals(first.getString("sha256"), next.getString("sha256"))
        assertEquals(0, next.getJSONObject("stageCacheHits").optInt("java"))
        assertEquals(next.toString(), 1, next.getJSONObject("stageCacheHits").optInt("dependency-dex"))
        java.util.zip.ZipFile(next.getString("artifact")).use { apk ->
            val metadata = apk.getEntry("META-INF/main.kotlin_module")
            assertNotNull("Compiled Kotlin module metadata must be packaged", metadata)
            assertTrue(apk.getInputStream(metadata).use { it.readBytes() }.isNotEmpty())
            assertFalse(apk.entries().asSequence().any { it.name.endsWith(".class") })
        }
        File(context.filesDir, "kotlin-integration-result.json").writeText(next.toString(2))
        val valid = source.readText()
        source.appendText("\nclass Broken { val value: MissingType? = null }\n")
        val broken = NativeBuildClient(context).build(root, offline = true) {}
        assertEquals(broken.toString(), "FAILED", broken.getString("state")); assertFalse(broken.has("artifact"))
        assertTrue(broken.getJSONArray("diagnostics").toString().contains("KotlinValue.kt"))
        source.writeText(valid)
    }
}
