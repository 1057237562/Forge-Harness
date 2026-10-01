package com.jarves.mh.build

import android.app.*
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat
import com.jarves.mh.MainActivity
import com.jarves.mh.R
import dev.forge.build.InputSnapshot
import dev.forge.build.ProjectInspector
import dev.forge.build.WorkspaceFiles
import dev.forge.compiler.BuildListener
import dev.forge.compiler.Cancellation
import dev.forge.compiler.NativeCompiler
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

object NativeBuildProtocol {
    const val BUILD = 1
    const val CANCEL = 2
    const val EVENT = 3
    const val RESULT = 4
}

/** Own process, private Binder endpoint, one build at a time, and persistent journals. */
class NativeBuildService : Service() {
    private val running = AtomicBoolean(false)
    private var cancellation: Cancellation? = null
    private var activeRequestId: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val endpoint = Messenger(Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            NativeBuildProtocol.CANCEL -> {
                val accepted = activeRequestId != null && message.data.getString("requestId") == activeRequestId
                if (accepted) cancellation?.cancel()
                send(message.replyTo, NativeBuildProtocol.EVENT, JSONObject().put("type", "cancellation").put("accepted", accepted))
                true
            }
            NativeBuildProtocol.BUILD -> {
                val reply = message.replyTo
                val requestId = message.data.getString("requestId")
                if (requestId == null || !requestId.matches(Regex("[a-f0-9-]{36}"))) {
                    send(reply, NativeBuildProtocol.RESULT, JSONObject().put("state", "FAILED").put("error", "Build request identity is required"))
                } else if (!running.compareAndSet(false, true)) {
                    send(reply, NativeBuildProtocol.RESULT, JSONObject().put("state", "FAILED").put("error", "Another native build is running"))
                } else {
                    val root = message.data.getString("projectPath").orEmpty()
                    val offline = message.data.getBoolean("offline", false)
                    val token = Cancellation().also { cancellation = it }
                    activeRequestId = requestId
                    runCatching {
                        foreground()
                        Thread({ build(root, reply, token, offline) }, "forge-native-build").start()
                    }.onFailure {
                        running.set(false)
                        activeRequestId = null
                        cancellation = null
                        send(reply, NativeBuildProtocol.RESULT, JSONObject().put("state", "FAILED").put("error", it.message))
                    }
                }
                true
            }
            else -> false
        }
    })

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Android builds", NotificationManager.IMPORTANCE_LOW),
        )
        // A new service process cannot resume a previous compiler invocation.
        File(filesDir, "native-builds").listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            runCatching {
                val file = File(dir, "result.json")
                val state = JSONObject(WorkspaceFiles.readUtf8(file, 1024 * 1024))
                if (state.optString("state") in setOf("RUNNING", "CANCELLING")) {
                    state.put("state", "INTERRUPTED").put("error", "The previous build process ended before completion")
                    WorkspaceFiles.atomicWrite(file, state.toString(2).toByteArray())
                }
            }
        }
    }
    override fun onBind(intent: Intent?): IBinder = endpoint.binder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL && activeRequestId != null && intent.getStringExtra("requestId") == activeRequestId) cancellation?.cancel()
        if (!running.get()) stopSelf()
        return START_NOT_STICKY
    }

    private fun foreground() {
        val cancel = PendingIntent.getService(this, 301,
            Intent(this, NativeBuildService::class.java).setAction(ACTION_CANCEL)
                .setData(android.net.Uri.parse("forge://build-cancel/$activeRequestId")).putExtra("requestId", activeRequestId), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 302, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(NOTIFICATION, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("Forge — Android build")
            .setContentText("Compiling on this device").setContentIntent(open).setOngoing(true)
            .addAction(0, "Cancel build", cancel).build())
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dev.forge.mobile:native-build")
            .apply { acquire(10 * 60 * 1000L) }
    }

    private fun build(path: String, reply: Messenger?, token: Cancellation, offline: Boolean) {
        val id = UUID.randomUUID().toString()
        val directory = File(filesDir, "native-builds/$id")
        val journal = File(directory, "result.json")
        val result = JSONObject().put("buildId", id).put("state", "RUNNING").put("backend", "android-native").put("pid", Process.myPid()).put("offline", offline)
        val diagnostics = JSONArray()
        val gate = Any()
        val started = SystemClock.elapsedRealtime()
        fun persist() = synchronized(gate) { WorkspaceFiles.atomicWrite(journal, result.toString(2).toByteArray()) }
        fun event(kind: String, text: String) = send(reply, NativeBuildProtocol.EVENT,
            JSONObject().put("buildId", id).put("type", kind).put("text", text.take(12_000)))
        try {
            check(directory.mkdirs()) { "Cannot create build output directory" }
            val workspace = File(filesDir, "workspaces").canonicalFile
            val root = File(path).canonicalFile
            check(root.isDirectory && root.toPath().startsWith(workspace.toPath()) && root != workspace) { "Build root is outside this application's workspaces" }
            result.put("projectPath", root.path).put("startedAt", System.currentTimeMillis())
            persist()
            event("state", "Preparing native build")
            val tools = NativeToolchainProvider.prepare(this) { event("progress", it) }
            PrintWriter(File(directory, "build.log"), "UTF-8").use { log ->
                val outcome = NativeCompiler().build(root, File(directory, "output"), File(filesDir, "native-cache"), tools, token, object : BuildListener {
                    override fun log(line: String) { log.println(line); log.flush(); event("log", line) }
                    override fun stage(stage: String, starting: Boolean, durationMs: Long) {
                        if (starting) {
                            synchronized(gate) { result.put("stage", stage) }
                            persist(); event("stage", stage)
                        }
                    }
                    override fun diagnostic(severity: String, file: String?, line: Int, column: Int, message: String) {
                        val diagnostic = JSONObject().put("severity", severity).put("file", file?.take(1500)).put("line", line).put("column", column).put("message", message.take(2000))
                        synchronized(gate) { if (diagnostics.length() < 100) diagnostics.put(diagnostic) }
                        send(reply, NativeBuildProtocol.EVENT, JSONObject().put("buildId", id).put("type", "diagnostic").put("diagnostic", diagnostic))
                    }
                }, offline)
                check(InputSnapshot.digest(InputSnapshot.hashInputs(ProjectInspector().inspect(root))) == outcome.sourceSnapshotId) {
                    "Project changed after compilation; build again before installing"
                }
                result.put("state", "SUCCEEDED").put("artifact", outcome.apk.path).put("sha256", outcome.sha256)
                    .put("sourceSnapshotId", outcome.sourceSnapshotId).put("cacheHit", outcome.cacheHit)
                    .put("stagesMs", JSONObject(outcome.stagesMs)).put("dependencyFingerprint", outcome.dependencies.fingerprint)
                    .put("stageCacheHits", JSONObject(outcome.stageCacheHits))
                val dependencyReport = JSONObject().put("fingerprint", outcome.dependencies.fingerprint)
                    .put("metadata", JSONObject(outcome.dependencies.metadata))
                    .put("artifacts", JSONArray().apply {
                        outcome.dependencies.artifacts.forEach { a -> put(JSONObject().put("id", a.id).put("type", a.type)
                            .put("sha256", a.sha256).put("repository", a.repository).put("compile", a.compile).put("runtime", a.runtime)) }
                    })
                WorkspaceFiles.atomicWrite(File(directory, "dependencies.json"), dependencyReport.toString(2).toByteArray())
            }
        } catch (error: Throwable) {
            result.put("state", if (token.isCancelled) "CANCELLED" else "FAILED")
                .put("error", error.message ?: error.javaClass.simpleName)
            runCatching { File(directory, "error.log").writeText(android.util.Log.getStackTraceString(error)) }
        } finally {
            result.put("durationMs", SystemClock.elapsedRealtime() - started).put("diagnostics", diagnostics)
            runCatching { persist() }
            Handler(Looper.getMainLooper()).post {
                wakeLock?.takeIf { it.isHeld }?.release(); wakeLock = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                running.set(false)
                activeRequestId = null
                send(reply, NativeBuildProtocol.RESULT, result)
                stopSelf()
            }
        }
    }
    override fun onDestroy() {
        cancellation?.cancel()
        wakeLock?.takeIf { it.isHeld }?.release(); wakeLock = null
        super.onDestroy()
    }
    private fun send(reply: Messenger?, kind: Int, payload: JSONObject) {
        if (reply == null) return
        val message = Message.obtain(null, kind).apply { data = Bundle().apply { putString("json", payload.toString()) } }
        runCatching { reply.send(message) }
    }
    companion object {
        private const val CHANNEL = "forge-native-build"
        private const val NOTIFICATION = 7102
        private const val ACTION_CANCEL = "dev.forge.mobile.CANCEL_NATIVE_BUILD"
    }
}
