package com.jarves.mh.build

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NativeBuildGatewayTest {
    @Test fun authenticatesScopesBuildsAndEnforcesSessionBudget() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "workspaces/gateway-${UUID.randomUUID()}").apply { mkdirs() }
        File(root, "AndroidManifest.xml").writeText("""<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.forge.gateway"><uses-sdk android:minSdkVersion="28" android:targetSdkVersion="29"/><application/></manifest>""")
        File(root, "src/dev/forge/gateway/Thing.java").apply { parentFile!!.mkdirs(); writeText("package dev.forge.gateway; public class Thing {}") }
        NativeBuildGateway(context, root, maxBuilds = 1).use { gateway ->
            fun request(body: String, token: String = gateway.environment.getValue("FORGE_BUILD_TOKEN")): Pair<Int, JSONObject> {
                val connection = URL(gateway.environment.getValue("FORGE_BUILD_URL")).openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"; connection.doOutput = true
                    connection.connectTimeout = 3000; connection.readTimeout = 60000
                    connection.setRequestProperty("Authorization", "Bearer $token")
                    val bytes = body.toByteArray(); connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { it.write(bytes) }
                    val status = connection.responseCode
                    val text = (if (status == 200) connection.inputStream else connection.errorStream).bufferedReader().use { it.readText() }
                    return status to JSONObject(text)
                } finally { connection.disconnect() }
            }
            assertEquals(401, request("{}", "invalid").first)
            assertEquals(400, request("""{"project":"../other"}""").first)
            assertEquals(400, request("""{"offline":"true"}""").first)
            val result = request("""{"offline":true}""")
            assertEquals(result.toString(), 200, result.first)
            assertEquals(result.toString(), "SUCCEEDED", result.second.getString("state"))
            assertEquals(0, result.second.getInt("remainingBuilds"))
            assertEquals("/workspace", result.second.getString("projectPath"))
            assertEquals(429, request("{}").first)
        }
    }
}
