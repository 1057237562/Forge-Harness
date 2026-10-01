package com.jarves.mh.ui

data class AndroidBuildArtifact(val path: String, val sha256: String, val buildId: String,
    val packageName: String, val versionName: String, val bytes: Long,
    val projectPath: String, val sourceSnapshotId: String)
