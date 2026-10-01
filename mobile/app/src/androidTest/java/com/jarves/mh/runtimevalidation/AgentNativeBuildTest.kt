package com.jarves.mh.runtimevalidation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jarves.mh.build.NativeBuildGateway
import com.jarves.mh.model.AgentKind
import com.jarves.mh.runtime.RuntimeInstaller
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Explicit setup test: downloads the optional Agent runtime, never the Android Gradle stack. */
@RunWith(AndroidJUnit4::class)
class AgentNativeBuildTest {
    @Test fun guestNodeCallsNativeCompilerAndReceivesRepairDiagnostics() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val progress = File(context.filesDir, "agent-runtime-test-progress.txt")
        progress.writeText("Preparing Agent runtime\n")
        val installer = RuntimeInstaller(context)
        val runtime = installer.ensureInstalled(emptySet(), AgentKind.DEEPSEEK_HARNESS) {
            progress.appendText(it.message + "\n")
        }
        val root = File(context.filesDir, "workspaces/guest-build-${UUID.randomUUID()}").apply { mkdirs() }
        File(root, "AndroidManifest.xml").writeText("""<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.forge.guest"><uses-sdk android:minSdkVersion="28" android:targetSdkVersion="29"/><application/></manifest>""")
        val source = File(root, "src/dev/forge/guest/Thing.java").apply { parentFile!!.mkdirs(); writeText("package dev.forge.guest; public class Thing { MissingType value; }") }
        NativeBuildGateway(context, root, maxBuilds = 2, guestWorkspace = "/workspace/guest-test").use { gateway ->
            suspend fun invoke(): Pair<Int, JSONObject> {
                val output = File(context.cacheDir, "guest-build-${UUID.randomUUID()}.log")
                val process = installer.process(runtime.proot, runtime.rootfs, root, gateway.environment,
                    listOf("/usr/local/bin/node", "/pocket-bridge/forge-build.cjs", "--offline"),
                    guestWorkspacePath = "/workspace/guest-test", outputFile = output)
                try {
                    process.outputStream.close()
                    withTimeout(12 * 60 * 1000L) { while (process.isAlive) delay(100) }
                    val text = output.readText()
                    assertTrue(text, text.trimStart().startsWith("{"))
                    return process.waitFor() to JSONObject(text)
                } finally { if (process.isAlive) process.destroyForcibly() }
            }
            progress.appendText("Testing guest Node to native compiler\n")
            val broken = invoke()
            assertEquals(broken.toString(), 1, broken.first)
            assertEquals("FAILED", broken.second.getString("state"))
            assertTrue(broken.second.getJSONArray("diagnostics").getJSONObject(0).getString("file").startsWith("/workspace/guest-test/"))
            // This is a scripted correction, not evidence of a model deciding how to repair code.
            source.writeText("package dev.forge.guest; public class Thing { String value; }")
            val repaired = invoke()
            assertEquals(repaired.toString(), 0, repaired.first)
            assertEquals("SUCCEEDED", repaired.second.getString("state"))
            assertEquals(0, repaired.second.getInt("remainingBuilds"))
            File(context.filesDir, "guest-native-build-result.json").writeText(JSONObject().put("broken", broken.second).put("repaired", repaired.second).toString(2))
        }
        progress.appendText("PASS: guest Node called Android native compiler; scripted repair succeeded\n")
    }
}
