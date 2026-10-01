package com.jarves.mh.build

import android.content.Context
import dev.forge.build.WorkspaceFiles
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.io.File

/** Session-scoped loopback API. A token grants builds only inside this session's workspace. */
class NativeBuildGateway(context: Context, workspace: File, private val maxBuilds: Int = 4, private val guestWorkspace: String = "/workspace") : Closeable {
    private val root = workspace.canonicalFile
    private val client = NativeBuildClient(context.applicationContext)
    private val closed = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)
    private val attempts = AtomicInteger(0)
    private val connections = Semaphore(2)
    private val workers = Executors.newFixedThreadPool(2)
    private val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
    private val server: ServerSocket
    val environment: Map<String, String>
    init {
        require(maxBuilds in 1..20)
        require(guestWorkspace == "/workspace" || Regex("^/workspace/[a-z0-9][a-z0-9-]{0,63}$").matches(guestWorkspace))
        val managed = File(context.filesDir, "workspaces").canonicalFile
        require(root.toPath().startsWith(managed.toPath()) && root != managed && root.isDirectory) { "Build gateway workspace is outside managed projects" }
        WorkspaceFiles.resolve(managed, managed.toPath().relativize(workspace.absoluteFile.toPath()).toString().replace(File.separatorChar, '/'))
        context.assets.open("forge/forge-build.cjs").use { input ->
            WorkspaceFiles.atomicWrite(File(context.filesDir, "runtime-bridge/forge-build.cjs"), input.readBytes())
        }
        server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        environment = mapOf("FORGE_BUILD_URL" to "http://127.0.0.1:${server.localPort}/v1/build", "FORGE_BUILD_TOKEN" to token)
        Thread({
            while (!closed.get()) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                if (!connections.tryAcquire()) { socket.close(); continue }
                try {
                    workers.execute { try { socket.use(::handle) } finally { connections.release() } }
                } catch (_: java.util.concurrent.RejectedExecutionException) { connections.release(); socket.close() }
            }
        }, "forge-build-api").apply { isDaemon = true; start() }
    }
    private fun handle(socket: Socket) {
        socket.soTimeout = 3000
        try {
            val input = BufferedInputStream(socket.getInputStream())
            if (line(input) != "POST /v1/build HTTP/1.1") { reply(socket, 404, error("Unknown build endpoint")); return }
            val headers = mutableMapOf<String, String>()
            var total = 0
            while (true) {
                val value = line(input); total += value.length
                if (total > 8192) throw IOException("Headers exceed limit")
                if (value.isEmpty()) break
                val split = value.indexOf(':'); if (split <= 0) throw IOException("Invalid header")
                val name = value.substring(0, split).lowercase()
                if (headers.put(name, value.substring(split + 1).trim()) != null) throw IOException("Duplicate header")
            }
            val supplied = headers["authorization"].orEmpty().toByteArray(StandardCharsets.UTF_8)
            if (!MessageDigest.isEqual(supplied, "Bearer $token".toByteArray(StandardCharsets.UTF_8))) {
                reply(socket, 401, error("Invalid session credential")); return
            }
            if (headers.containsKey("transfer-encoding")) throw IOException("Chunked requests are unsupported")
            val length = headers["content-length"]?.toIntOrNull() ?: throw IOException("Content-Length required")
            if (length !in 2..4096) throw IOException("Request size exceeds limit")
            val bytes = ByteArray(length); var offset = 0
            while (offset < length) { val n = input.read(bytes, offset, length - offset); if (n < 0) throw IOException("Truncated request"); offset += n }
            val request = JSONObject(bytes.toString(StandardCharsets.UTF_8))
            require(request.keys().asSequence().all { it == "project" || it == "offline" }) { "Unknown build option" }
            require(!request.has("project") || request.get("project") is String) { "project must be a relative path" }
            require(!request.has("offline") || request.get("offline") is Boolean) { "offline must be boolean" }
            val project = WorkspaceFiles.resolve(root, request.optString("project", "."))
            if (!project.isDirectory) throw IOException("Project directory does not exist")
            if (closed.get()) { reply(socket, 410, error("Session ended")); return }
            if (!busy.compareAndSet(false, true)) { reply(socket, 409, error("A session build is already running")); return }
            try {
                if (attempts.get() >= maxBuilds) { reply(socket, 429, error("Build budget exhausted; return diagnostics to the user")); return }
                val attempt = attempts.incrementAndGet()
                val result = runBlocking { client.build(project, request.optBoolean("offline", false)) {} }
                result.put("attempt", attempt).put("remainingBuilds", maxBuilds - attempt)
                // Guest paths are actionable; Android private paths are not mounted in the Agent runtime.
                val guestResult = JSONObject(result.toString().replace(root.absolutePath, guestWorkspace).replace(root.absolutePath.replace("/", "\\/"), guestWorkspace.replace("/", "\\/")))
                reply(socket, 200, guestResult)
            } finally { busy.set(false) }
        } catch (failure: Exception) {
            runCatching { reply(socket, 400, error(failure.message ?: "Build request failed")) }
        }
    }
    private fun line(input: BufferedInputStream): String {
        val text = StringBuilder()
        while (text.length <= 4096) {
            val next = input.read(); if (next < 0) throw IOException("Truncated HTTP request")
            if (next == 10) return text.toString().removeSuffix("\r")
            if (next > 127 || next == 0) throw IOException("Invalid HTTP header")
            text.append(next.toChar())
        }
        throw IOException("HTTP line exceeds limit")
    }
    private fun error(message: String) = JSONObject().put("state", "FAILED").put("error", message)
    private fun reply(socket: Socket, status: Int, result: JSONObject) {
        val bytes = result.toString().toByteArray(StandardCharsets.UTF_8)
        val header = "HTTP/1.1 $status Result\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply { write(header.toByteArray(StandardCharsets.US_ASCII)); write(bytes); flush() }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        client.cancel(); server.close(); workers.shutdownNow()
    }
}
