package com.jarves.mh.build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.forge.build.NativeCompatibility
import dev.forge.build.WorkspaceFiles
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NativeMigrationTest {
    @Test fun appliesReviewedLegacyMigrationAndBuildsOnAndroid() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "workspaces/migration-${UUID.randomUUID()}").apply { mkdirs() }
        File(root, "AndroidManifest.xml").writeText("""<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.forge.migration"><uses-sdk android:minSdkVersion="28" android:targetSdkVersion="29"/><application><activity android:name=".MainActivity" android:exported="true"/></application></manifest>""")
        val source = File(root, "src/dev/forge/migration/MainActivity.java").apply { parentFile!!.mkdirs(); writeText("package dev.forge.migration; public class MainActivity extends android.app.Activity {}") }
        val originalHash = WorkspaceFiles.sha256(source)
        val report = NativeCompatibility.inspect(root, 29)
        assertTrue(report.summary, report.configurationSupported)
        assertNotNull(report.migrationJson)
        NativeCompatibility.apply(report)
        assertEquals(originalHash, WorkspaceFiles.sha256(source))
        assertEquals(report.migrationJson, File(root, ".forge/project.json").readText())
        val result = NativeBuildClient(context).build(root, offline = true) {}
        assertEquals(result.toString(), "SUCCEEDED", result.optString("state"))
        File(context.filesDir, "migration-build-result.json").writeText(result.toString(2))
    }
}
