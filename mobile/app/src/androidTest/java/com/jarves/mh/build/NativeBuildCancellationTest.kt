package com.jarves.mh.build

import android.content.*
import android.os.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.forge.build.AndroidProjectTemplate
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NativeBuildCancellationTest {
    @Test fun ignoresForeignCancellationButHonorsOwnerCancellation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun project(): File = File(context.filesDir, "workspaces/cancel-${UUID.randomUUID()}").also {
            AndroidProjectTemplate.create(it, "Cancellation ${UUID.randomUUID()}", "dev.forge.cancellation")
        }
        val connected = CompletableDeferred<Messenger>()
        val rejected = CompletableDeferred<Boolean>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { connected.complete(Messenger(binder)) }
            override fun onServiceDisconnected(name: ComponentName) { }
        }
        val response = Messenger(Handler(Looper.getMainLooper()) {
            val value = JSONObject(it.data.getString("json")!!)
            if (value.optString("type") == "cancellation") rejected.complete(!value.getBoolean("accepted"))
            true
        })
        assertTrue(context.bindService(Intent(context, NativeBuildService::class.java), connection, Context.BIND_AUTO_CREATE))
        try {
            val endpoint = withTimeout(5000) { connected.await() }
            var sent = false
            val valid = NativeBuildClient(context).build(project(), offline = true) { event ->
                if (!sent && event.optString("type") == "stage") {
                    sent = true
                    endpoint.send(Message.obtain(null, NativeBuildProtocol.CANCEL).apply {
                        data = Bundle().apply { putString("requestId", UUID.randomUUID().toString()) }
                        replyTo = response
                    })
                }
            }
            assertTrue(sent)
            assertTrue(withTimeout(5000) { rejected.await() })
            assertEquals(valid.toString(), "SUCCEEDED", valid.getString("state"))
            val owner = NativeBuildClient(context)
            val cancelled = owner.build(project(), offline = true) { event ->
                if (event.optString("type") == "stage" && event.optString("text") == "resources") owner.cancel()
            }
            assertEquals(cancelled.toString(), "CANCELLED", cancelled.getString("state"))
            assertFalse(cancelled.has("artifact"))
        } finally { context.unbindService(connection) }
    }
}
