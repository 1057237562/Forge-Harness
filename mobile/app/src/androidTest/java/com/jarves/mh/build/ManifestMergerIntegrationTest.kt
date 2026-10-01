package com.jarves.mh.build

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.forge.compiler.LibraryManifestMerger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ManifestMergerIntegrationTest {
    @Test fun mergesLibraryComponentsAndHonorsToolsRulesOnAndroid() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "merge-${UUID.randomUUID()}").apply { mkdirs() }
        val app = File(root, "app.xml").apply { writeText("""<manifest xmlns:android="http://schemas.android.com/apk/res/android" xmlns:tools="http://schemas.android.com/tools" package="dev.forge.app"><application android:label="App" tools:replace="android:label"><service android:name="dev.lib.Removed" tools:node="remove"/></application></manifest>""") }
        val lib = File(root, "lib.xml").apply { writeText("""<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.lib"><uses-sdk android:minSdkVersion="21"/><uses-permission android:name="android.permission.INTERNET"/><application android:label="Library"><service android:name=".Kept"/><service android:name=".Removed"/><provider android:name=".Provider" android:authorities="${'$'}{applicationId}.provider"/></application></manifest>""") }
        val output = File(root, "merged.xml")
        LibraryManifestMerger.merge(app, listOf(lib), output, "dev.forge.app", 28, 29) {}
        val merged = output.readText()
        assertTrue(merged, merged.contains("dev.lib.Kept"))
        assertTrue(merged, merged.contains("dev.forge.app.provider"))
        assertTrue(merged, merged.contains("android.permission.INTERNET"))
        assertFalse(merged, merged.contains("dev.lib.Removed"))
        assertFalse(merged, merged.contains("tools:replace"))
        lib.writeText(lib.readText().replace("minSdkVersion=\"21\"", "minSdkVersion=\"30\""))
        try {
            LibraryManifestMerger.merge(app, listOf(lib), File(root, "bad.xml"), "dev.forge.app", 28, 29) {}
            fail("Library minSdk conflict must fail")
        } catch (expected: IOException) {
            assertTrue(expected.message, expected.message!!.contains("Manifest merge failed"))
        }
    }
}
