package com.jarves.mh.build

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.forge.build.AndroidProjectTemplate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidTemplateBuildTest {
    @Test fun buildsUnmodifiedTemplateOfflineWithEscapedName() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "workspaces/template-${UUID.randomUUID()}")
        AndroidProjectTemplate.create(root, "Alice's \"A&B\" <你好>", "dev.forge.template")
        val result = NativeBuildClient(context).build(root, offline = true) {}
        assertEquals(result.toString(), "SUCCEEDED", result.optString("state"))
        assertFalse(result.getBoolean("cacheHit"))
        val apk = dev.forge.build.VerifiedBuildArtifact.resolve(File(context.filesDir, "native-builds"),
            result.getString("buildId"), result.getString("artifact"), result.getString("sha256"))
        val info = context.packageManager.getPackageArchiveInfo(apk.path, 0)
        assertNotNull(info)
        assertEquals("dev.forge.template", info!!.packageName)
        File(context.filesDir, "template-build-result.json").writeText(result.toString(2))
    }
}
