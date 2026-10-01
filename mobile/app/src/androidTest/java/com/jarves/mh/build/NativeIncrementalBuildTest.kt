package com.jarves.mh.build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.forge.build.AndroidProjectTemplate
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NativeIncrementalBuildTest {
    @Test fun reusesOnlyStagesWhoseInputsAreUnchanged() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "workspaces/incremental-${UUID.randomUUID()}")
        AndroidProjectTemplate.create(root, "Incremental ${UUID.randomUUID()}", "dev.forge.incremental")
        val evidence = JSONArray()
        suspend fun build() = NativeBuildClient(context).build(root, offline = true) {}.also {
            assertEquals(it.toString(), "SUCCEEDED", it.getString("state")); assertFalse(it.getBoolean("cacheHit")); evidence.put(it)
        }
        build()
        File(root, "assets/message.txt").apply { parentFile!!.mkdirs(); writeText("asset-only change") }
        val assets = build().getJSONObject("stageCacheHits")
        assertTrue(assets.toString(), assets.optInt("resources") > 0)
        assertEquals(1, assets.optInt("java")); assertEquals(1, assets.optInt("dex"))
        File(root, "src/dev/forge/incremental/Added.java").writeText("package dev.forge.incremental; public class Added { public int value() { return 42; } }")
        val java = build().getJSONObject("stageCacheHits")
        assertTrue(java.optInt("resources") > 0); assertEquals(0, java.optInt("java")); assertEquals(0, java.optInt("dex"))
        File(root, "res/values/strings.xml").appendText("\n<!-- resource-only change -->\n")
        val resources = build().getJSONObject("stageCacheHits")
        assertEquals(0, resources.optInt("resources")); assertEquals(1, resources.optInt("java")); assertEquals(1, resources.optInt("dex"))
        File(context.filesDir, "incremental-build-results.json").writeText(evidence.toString(2))
    }
}
