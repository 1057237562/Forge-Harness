package com.jarves.mh.build

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.forge.build.WorkspaceFiles
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import org.json.JSONObject
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class NativeBuildIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun project(): File {
        val root = File(context.filesDir, "workspaces/native-test-${UUID.randomUUID()}").apply { mkdirs() }
        fun write(path: String, text: String) {
            val file = File(root, path); file.parentFile!!.mkdirs(); file.writeText(text)
        }
        write("AndroidManifest.xml", """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.forge.integration"><uses-sdk android:minSdkVersion="28" android:targetSdkVersion="29"/><application android:label="@string/app_name"><activity android:name=".MainActivity" android:exported="true"/></application></manifest>""")
        write("res/values/strings.xml", """<resources><string name="app_name">Native Integration</string></resources>""")
        write("src/dev/forge/integration/MainActivity.java", """package dev.forge.integration; public class MainActivity extends android.app.Activity { public void onCreate(android.os.Bundle b) { super.onCreate(b); android.widget.TextView v=new android.widget.TextView(this); v.setText(R.string.app_name); setContentView(v); } }""")
        return root
    }
    @Test fun buildsInSeparateProcessWithKeystoreAndReusesVerifiedArtifact() = runBlocking {
        val root = project()
        val first = NativeBuildClient(context).build(root) {}
        assertEquals(first.toString(), "SUCCEEDED", first.optString("state"))
        assertNotEquals(Process.myPid(), first.getInt("pid"))
        val apk = File(first.getString("artifact"))
        assertTrue(apk.isFile)
        assertEquals(first.getString("sha256"), WorkspaceFiles.sha256(apk))
        val next = NativeBuildClient(context).build(root) {}
        assertEquals(next.toString(), "SUCCEEDED", next.optString("state"))
        assertTrue(next.getBoolean("cacheHit"))
        assertEquals(first.getString("sha256"), next.getString("sha256"))
    }
    @Test fun returnsStructuredFailureWithoutArtifact() = runBlocking {
        val root = project()
        File(root, "src/dev/forge/integration/MainActivity.java").appendText("\nclass Broken { MissingType value; }")
        val result = NativeBuildClient(context).build(root) {}
        assertEquals(result.toString(), "FAILED", result.optString("state"))
        assertFalse(result.has("artifact"))
        val diagnostics = result.getJSONArray("diagnostics")
        assertTrue(diagnostics.length() > 0)
        assertTrue(diagnostics.getJSONObject(0).getString("file").startsWith(root.canonicalPath))
    }
    @Test fun rejectsBuildRootsOutsideManagedWorkspaces() = runBlocking {
        val result = NativeBuildClient(context).build(context.cacheDir) {}
        assertEquals("FAILED", result.optString("state"))
        assertTrue(result.getString("error").contains("outside"))
    }
    @Test fun compilesRealMavenLibraryThenBuildsOffline() = runBlocking {
        verifyLibrary("com.google.code.gson:gson:2.10.1", "new com.google.gson.Gson().toJson(\"maven-on-android\")", "maven")
    }
    @Test fun compilesModuleMetadataLibraryThenBuildsOffline() = runBlocking {
        verifyLibrary("com.squareup.okio:okio:2.10.0", "new okio.Buffer().writeUtf8(\"module-on-android\").readUtf8()", "module")
    }
    @Test fun compilesRealAndroidxAarThenBuildsOffline() = runBlocking {
        verifyLibrary("androidx.cardview:cardview:1.0.0", "Math.abs(new androidx.cardview.widget.CardView(this).getRadius() / getResources().getDisplayMetrics().density - 17f) < 0.1f ? \"androidx-on-android\" : \"invalid-radius\"", "androidx")
    }
    @Test fun compilesAarResourcesManifestAndAssets() = runBlocking {
        val root = project()
        val aar = File(root, "libs/library.aar").apply { parentFile!!.mkdirs() }
        ZipOutputStream(aar.outputStream()).use { zip ->
            fun entry(path: String, text: String) {
                zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry()
            }
            entry("AndroidManifest.xml", """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.forge.library"><uses-sdk android:minSdkVersion="21"/><application><meta-data android:name="library-marker" android:value="${'$'}{applicationId}.merged"/></application></manifest>""")
            entry("res/values/strings.xml", """<resources><string name="library_text">aar-on-android</string></resources>""")
            entry("assets/library.txt", "asset-from-aar")
        }
        File(root, ".forge/project.json").apply { parentFile!!.mkdirs() }.writeText("""{"schemaVersion":1,"applicationId":"dev.forge.integration","compileSdk":29,"minSdk":28,"targetSdk":29,"manifest":"AndroidManifest.xml","java":["src"],"resources":["res"],"dependencies":[{"path":"libs/library.aar"}]}""")
        File(root, "src/dev/forge/integration/MainActivity.java").writeText("""package dev.forge.integration; public class MainActivity extends android.app.Activity {
            public void onCreate(android.os.Bundle b) { super.onCreate(b);
                try {
                    String value=getString(dev.forge.library.R.string.library_text);
                    byte[] bytes=new byte[64]; int count; String asset;
                    try(java.io.InputStream input=getAssets().open("library.txt")) { count=input.read(bytes); asset=new String(bytes,0,count,"UTF-8"); }
                    String metadata=getPackageManager().getApplicationInfo(getPackageName(),128).metaData.getString("library-marker");
                    String text=value+"|"+asset+"|"+metadata;
                    android.widget.TextView v=new android.widget.TextView(this); v.setText(text); setContentView(v);
                    try(java.io.FileOutputStream out=openFileOutput("aar-result.txt",0)) { out.write(text.getBytes("UTF-8")); }
                } catch(Exception e) { throw new RuntimeException(e); }
            }}""")
        val result = NativeBuildClient(context).build(root, offline = true) {}
        assertEquals(result.toString(), "SUCCEEDED", result.optString("state"))
        assertFalse(result.getBoolean("cacheHit"))
        File(context.filesDir, "aar-integration-result.json").writeText(result.toString(2))
    }
    private suspend fun verifyLibrary(coordinate: String, expression: String, marker: String) {
        val root = project()
        if (marker == "androidx") {
            File(root, "res/values/forge_attrs.xml").writeText("""<resources>
                <declare-styleable name="CardView"><attr name="aaa_forge_extra" format="integer"/></declare-styleable>
                <style name="ForgeCardTheme" parent="@android:style/Theme.Material.Light"><item name="cardViewStyle">@style/ForgeCard</item></style>
                <style name="ForgeCard" parent="CardView"><item name="cardCornerRadius">17dp</item></style>
                </resources>""")
            val manifest = File(root, "AndroidManifest.xml")
            manifest.writeText(manifest.readText().replace("<application ", "<application android:theme=\"@style/ForgeCardTheme\" "))
        }
        val config = File(root, ".forge/project.json").apply { parentFile!!.mkdirs() }
        config.writeText("""{"schemaVersion":1,"applicationId":"dev.forge.integration","compileSdk":29,"minSdk":28,"targetSdk":29,"manifest":"AndroidManifest.xml","java":["src"],"resources":["res"],"repositories":["https://repo.maven.apache.org/maven2/","https://dl.google.com/dl/android/maven2/"],"dependencies":[{"coordinate":"$coordinate"}]}""")
        val source = File(root, "src/dev/forge/integration/MainActivity.java")
        source.writeText("""package dev.forge.integration; public class MainActivity extends android.app.Activity {
            public void onCreate(android.os.Bundle b) { super.onCreate(b);
                String text = $expression;
                android.widget.TextView v = new android.widget.TextView(this); v.setText(text); setContentView(v);
                try (java.io.FileOutputStream f=openFileOutput("$marker-result.txt",MODE_PRIVATE)) { f.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
                catch(java.io.IOException e) { throw new RuntimeException(e); }
            }}""")
        val first = NativeBuildClient(context).build(root) {}
        assertEquals(first.toString(), "SUCCEEDED", first.optString("state"))
        val report = JSONObject(File(context.filesDir, "native-builds/${first.getString("buildId")}/dependencies.json").readText())
        val artifacts = report.getJSONArray("artifacts")
        assertTrue((0 until artifacts.length()).any { artifacts.getJSONObject(it).getString("id").startsWith(coordinate) })
        if (marker == "module") {
            assertTrue((0 until artifacts.length()).any { artifacts.getJSONObject(it).getString("id").startsWith("org.jetbrains.kotlin:kotlin-stdlib:") })
        }
        // Change an input so offline mode must really compile, not merely return an existing APK.
        source.appendText("\nclass OfflineSentinel {}\n")
        val offline = NativeBuildClient(context).build(root, offline = true) {}
        assertEquals(offline.toString(), "SUCCEEDED", offline.optString("state"))
        assertFalse(offline.getBoolean("cacheHit"))
        assertEquals(first.getString("dependencyFingerprint"), offline.getString("dependencyFingerprint"))
        File(context.filesDir, "$marker-integration-result.json").writeText(offline.toString(2))
    }
}
