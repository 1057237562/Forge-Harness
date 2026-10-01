package com.jarves.mh.build

import android.content.*
import android.os.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** One request per client; Binder death is a failure, never a successful/stale APK result. */
class NativeBuildClient(private val context: Context) {
    @Volatile private var remote: Messenger? = null
    @Volatile private var cancelRequested = false
    private var inFlight = false
    @Volatile private var requestId: String? = null
    fun cancel() {
        cancelRequested = true
        val id = requestId ?: return
        runCatching { remote?.send(Message.obtain(null, NativeBuildProtocol.CANCEL).apply {
            data = Bundle().apply { putString("requestId", id) }
        }) }
    }

    suspend fun build(project: File, offline: Boolean = false, onEvent: (JSONObject) -> Unit): JSONObject = withContext(Dispatchers.Main) {
        check(!inFlight) { "This build client is already running" }
        inFlight = true
        requestId = java.util.UUID.randomUUID().toString()
        cancelRequested = false
        val completion = CompletableDeferred<JSONObject>()
        var finished = false
        var bound = false
        val responses = Messenger(Handler(Looper.getMainLooper()) { message ->
            runCatching {
                val json = JSONObject(message.data.getString("json") ?: "{}")
                if (message.what == NativeBuildProtocol.RESULT) {
                    finished = true
                    completion.complete(json)
                } else onEvent(json)
            }.onFailure { completion.completeExceptionally(it) }
            true
        })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (cancelRequested) {
                    finished = true
                    completion.complete(JSONObject().put("state", "CANCELLED").put("error", "Build cancelled before starting"))
                    return
                }
                remote = Messenger(service)
                val request = Message.obtain(null, NativeBuildProtocol.BUILD).apply {
                    data = Bundle().apply { putString("projectPath", project.absolutePath); putBoolean("offline", offline); putString("requestId", requestId) }
                    replyTo = responses
                }
                runCatching { remote!!.send(request) }.onFailure { completion.completeExceptionally(it) }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                completion.completeExceptionally(IOException("Native builder process was interrupted"))
            }
            override fun onNullBinding(name: ComponentName) { completion.completeExceptionally(IOException("Native builder did not bind")) }
            override fun onBindingDied(name: ComponentName) { completion.completeExceptionally(IOException("Native builder binding died")) }
        }
        try {
            bound = context.bindService(Intent(context, NativeBuildService::class.java), connection, Context.BIND_AUTO_CREATE)
            check(bound) { "Unable to bind the native builder" }
            withTimeout(10 * 60 * 1000L) { completion.await() }
        } finally {
            if (!finished) cancel()
            if (bound) context.unbindService(connection)
            remote = null
            requestId = null
            inFlight = false
        }
    }
}
