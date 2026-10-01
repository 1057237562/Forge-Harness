package com.jarves.mh.build

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jarves.mh.ui.AndroidArtifactStore
import com.jarves.mh.ui.AndroidBuildArtifact
import dev.forge.build.AndroidProjectTemplate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidArtifactStoreTest {
    @Test fun restoresOnlyVerifiedArtifactForItsWorkspace(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val projectId = UUID.randomUUID().toString()
        val root = File(context.filesDir, "workspaces/artifact-$projectId")
        AndroidProjectTemplate.create(root, "Artifact recovery", "dev.forge.artifact")
        val result = NativeBuildClient(context).build(root, offline = true) {}
        assertEquals(result.toString(), "SUCCEEDED", result.getString("state"))
        val apk = File(result.getString("artifact"))
        val info = context.packageManager.getPackageArchiveInfo(apk.path, 0)!!
        val artifact = AndroidBuildArtifact(apk.path, result.getString("sha256"), result.getString("buildId"),
            info.packageName, info.versionName.orEmpty(), apk.length(), root.path, result.getString("sourceSnapshotId"))
        AndroidArtifactStore(context).save(projectId, artifact)
        val reopened = AndroidArtifactStore(context)
        assertEquals(artifact, reopened.load(projectId, root))
        assertNull(reopened.load("other-project", root))
        assertNull(reopened.load(projectId, File(context.filesDir, "workspaces/other")))
        apk.appendBytes(byteArrayOf(1))
        assertNull(reopened.load(projectId, root))
        context.getSharedPreferences("native-build-artifacts", 0).edit().remove(projectId).commit()
        Unit
    }
}
