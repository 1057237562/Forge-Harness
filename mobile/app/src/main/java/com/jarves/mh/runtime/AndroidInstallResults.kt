package com.jarves.mh.runtime

import android.content.Context
import android.content.pm.PackageInstaller
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Session ownership survives the UI process being recreated by the system installer. */
object AndroidInstallResults {
    data class Result(val sessionId: Int, val buildId: String?, val packageName: String, val status: Int, val message: String)
    private val latest = MutableStateFlow<Result?>(null)
    val updates = latest.asStateFlow()
    private fun preferences(context: Context) = context.getSharedPreferences("native-install-results", Context.MODE_PRIVATE)

    @Synchronized fun lastResult(context: Context): Result? {
        val stored = preferences(context).getString("latest", null) ?: return null
        return runCatching {
            val record = JSONObject(stored)
            Result(record.getInt("sessionId"), if (record.isNull("buildId")) null else record.getString("buildId"),
                record.getString("packageName"), record.getInt("status"), record.getString("message"))
        }.getOrNull()
    }

    @Synchronized fun register(context: Context, sessionId: Int, buildId: String?, packageName: String) {
        val record = JSONObject().put("buildId", buildId ?: JSONObject.NULL).put("packageName", packageName)
        val edit = preferences(context).edit().putString("session-$sessionId", record.toString())
        // A new attempt supersedes the previous status, including after process recreation.
        edit.remove("latest")
        if (buildId != null) edit.putInt("build-$buildId", sessionId)
        check(edit.commit()) {
            "Could not persist installation session"
        }
        latest.value = null
    }

    @Synchronized fun record(context: Context, sessionId: Int, status: Int, message: String, packageName: String? = null): Result? {
        val prefs = preferences(context)
        val key = "session-$sessionId"
        val stored = prefs.getString(key, null) ?: return null
        val record = JSONObject(stored)
        val expectedPackage = record.getString("packageName")
        if (packageName != null && packageName != expectedPackage) return null
        val result = Result(sessionId, if (record.isNull("buildId")) null else record.getString("buildId"), expectedPackage, status, message)
        if (result.buildId != null && prefs.getInt("build-${result.buildId}", -1) != sessionId) {
            if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) prefs.edit().remove(key).commit()
            return null
        }
        record.put("sessionId", sessionId).put("status", status).put("message", message)
        val edit = prefs.edit().putString("latest", record.toString())
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) {
            edit.remove(key)
            if (result.buildId != null) edit.remove("build-${result.buildId}")
        }
        check(edit.commit()) { "Could not persist installation result" }
        latest.value = result
        return result
    }
}
