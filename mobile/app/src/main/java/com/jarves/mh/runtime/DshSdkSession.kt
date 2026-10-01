package com.jarves.mh.runtime

import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

internal data class DshSdkRunResult(val completed: Boolean, val failure: String)

/** SDK lifecycle and process cleanup, independent of Android for fault-injection tests. */
internal suspend fun runDshSdkSession(
    process: Process,
    outputFile: File,
    sessionId: String,
    provider: String,
    model: String,
    guestWorkspacePath: String,
    prompt: String,
    now: () -> Long,
    onEvent: suspend (DshSdkProtocolEvent) -> Unit,
    pause: suspend (Long) -> Unit = { delay(it) },
): DshSdkRunResult {
    val writer = process.outputStream.bufferedWriter()
    val parser = DshSdkProtocolParser(sessionId)
    val watchdog = DshSessionWatchdog(now())
    var offset = 0L
    val pending = StringBuilder()
    var promptSent = false
    var sawRunning = false
    var sawActivity = false
    var completed = false
    var shutdownSentAt: Long? = null
    var terminateSentAt: Long? = null
    var inputClosed = false

    fun closeInput() {
        if (inputClosed) return
        inputClosed = true
        runCatching { writer.close() }
    }

    fun send(method: String, id: Int, params: JSONObject? = null) {
        val frame = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method)
        if (params != null) frame.put("params", params)
        writer.write(frame.toString())
        writer.newLine()
        writer.flush()
    }

    fun requestShutdown() {
        if (shutdownSentAt != null) return
        shutdownSentAt = now()
        // The child can exit between turn/end and our shutdown write.
        // Preserve its successful terminal result if stdin is already closed.
        runCatching { send("shutdown", 3) }.onFailure {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    suspend fun handle(event: DshSdkProtocolEvent) {
        if (shutdownSentAt != null) {
            if (event == DshSdkProtocolEvent.ShutdownAcknowledged) closeInput()
            return
        }
        watchdog.observe(event, now())
        when (event) {
            DshSdkProtocolEvent.Initialized -> if (!promptSent) {
                send("session/prompt", 2, JSONObject()
                    .put("sessionId", sessionId)
                    .put("contentBlocks", JSONArray().put(JSONObject().put("type", "text").put("text", prompt))))
                promptSent = true
            }
            is DshSdkProtocolEvent.Status -> {
                if (event.running) {
                    sawRunning = true
                } else if (sawRunning) {
                    if (!sawActivity) error("DeepSeek Harness stopped before processing the prompt")
                    completed = true
                    requestShutdown()
                }
            }
            is DshSdkProtocolEvent.Failed -> error(event.message)
            DshSdkProtocolEvent.TurnCompleted -> {
                // turn/end is authoritative even if session.status:idle is lost.
                completed = true
                requestShutdown()
            }
            is DshSdkProtocolEvent.Reasoning, is DshSdkProtocolEvent.ToolStarted,
            is DshSdkProtocolEvent.ToolCompleted -> sawActivity = true
            is DshSdkProtocolEvent.AssistantText -> if (event.text.isNotEmpty()) sawActivity = true
            else -> Unit
        }
        onEvent(event)
    }

    try {
        send("initialize", 1, JSONObject().put("cwd", guestWorkspacePath).put("provider", provider).put("model", model))
        while (process.isAlive || outputFile.length() > offset) {
            val time = now()
            val shuttingDownAt = shutdownSentAt
            if (shuttingDownAt == null) {
                watchdog.timeoutReason(time)?.let { error(it) }
            } else if (process.isAlive && time - shuttingDownAt >= 3_000L) {
                val terminatedAt = terminateSentAt
                if (terminatedAt == null) {
                    process.destroy()
                    terminateSentAt = time
                } else if (time - terminatedAt >= 500L) {
                    process.destroyForcibly()
                    if (time - terminatedAt >= 1_500L) error("DeepSeek Harness process did not exit after shutdown")
                }
            }
            val available = outputFile.length() - offset
            if (available <= 0) {
                pause(50)
                continue
            }
            val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
            val count = RandomAccessFile(outputFile, "r").use { file ->
                file.seek(offset)
                file.read(bytes)
            }
            if (count <= 0) {
                pause(50)
                continue
            }
            offset += count
            pending.append(bytes.decodeToString(0, count))
            var newline = pending.indexOf("\n")
            while (newline >= 0) {
                val line = pending.substring(0, newline).trimEnd('\r')
                pending.delete(0, newline + 1)
                if (line.isNotBlank()) handle(parser.parseLine(line))
                newline = pending.indexOf("\n")
            }
        }
        pending.toString().trim().takeIf(String::isNotBlank)?.let { handle(parser.parseLine(it)) }
        return DshSdkRunResult(completed, "")
    } finally {
        // Never await an idle event or waitFor() after a terminal failure.
        if (process.isAlive) process.destroyForcibly()
        closeInput()
    }
}
