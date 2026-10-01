package com.jarves.mh.ui

import android.content.Context
import dev.forge.build.VerifiedBuildArtifact
import org.json.JSONObject
import java.io.File

/** A remembered artifact is always revalidated before it is offered for installation. */
class AndroidArtifactStore(private val context: Context) {
    private val preferences get() = context.getSharedPreferences("native-build-artifacts", Context.MODE_PRIVATE)
    fun save(projectId: String, artifact: AndroidBuildArtifact) {
        val record = JSONObject().put("path", artifact.path).put("sha256", artifact.sha256)
            .put("buildId", artifact.buildId).put("packageName", artifact.packageName)
            .put("versionName", artifact.versionName).put("bytes", artifact.bytes)
            .put("projectPath", artifact.projectPath).put("sourceSnapshotId", artifact.sourceSnapshotId)
        check(preferences.edit().putString(projectId, record.toString()).commit()) { "Could not save build artifact record" }
    }

    fun load(projectId: String, workspace: File): AndroidBuildArtifact? {
        val text = preferences.getString(projectId, null) ?: return null
        return runCatching {
            val record = JSONObject(text)
            val root = File(record.getString("projectPath")).canonicalFile
            check(root.isDirectory && root.toPath().startsWith(workspace.canonicalFile.toPath())) { "Artifact belongs to another workspace" }
            val apk = VerifiedBuildArtifact.resolve(File(context.filesDir, "native-builds"), record.getString("buildId"),
                record.getString("path"), record.getString("sha256"))
            val info = checkNotNull(context.packageManager.getPackageArchiveInfo(apk.path, 0)) { "Unreadable APK" }
            check(info.packageName == record.getString("packageName") && apk.length() == record.getLong("bytes"))
            AndroidBuildArtifact(apk.path, record.getString("sha256"), record.getString("buildId"), info.packageName,
                info.versionName.orEmpty(), apk.length(), root.path, record.getString("sourceSnapshotId"))
        }.getOrNull()
    }
}
