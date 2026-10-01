package com.jarves.mh.runtime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class DshSdkSessionTest {
    @Test
    fun initializationErrorTerminatesWithoutIdleOrProcessExit() {
        val fixture = Fixture("""{"id":1,"error":{"message":"Initialization failed"}}""")
        val failure = fixture.failure()
        assertEquals("Initialization failed", failure.message)
        assertTrue(fixture.process.forced)
        assertEquals(0, fixture.time)
    }

    @Test
    fun terminalTurnErrorTerminatesWithoutIdle() {
        val fixture = Fixture(handshake + event("turn/end", JSONObject().put("reason", JSONObject()
            .put("kind", "error").put("failure", JSONObject().put("message", "Retries exhausted")))))
        assertEquals("Retries exhausted", fixture.failure().message)
        assertTrue(fixture.process.forced)
        assertFalse(fixture.process.input.toString().contains("shutdown"))
    }

    @Test
    fun successfulTurnEndsEvenWithoutIdleAndUncooperativeProcessIsKilled() {
        val fixture = Fixture(handshake + event("turn/end", JSONObject().put("reason", JSONObject().put("kind", "completed"))))
        assertTrue(fixture.run().completed)
        assertTrue(fixture.process.input.toString().contains("shutdown"))
        assertTrue(fixture.process.terminated)
        assertTrue(fixture.process.forced)
        assertEquals(137, fixture.process.exitValue())
        assertTrue(fixture.time in 3_500L..3_550L)
    }

    @Test
    fun closedStdinAfterSuccessfulTurnPreservesCompletion() {
        val fixture = Fixture(handshake + event("turn/end", JSONObject().put("reason", JSONObject().put("kind", "completed"))))
        fixture.process.failShutdownWrite = true
        assertTrue(fixture.run().completed)
        assertTrue(fixture.process.forced)
    }

    @Test
    fun retryNotificationIsDeliveredAndRecoveryCanComplete() {
        val retry = event("llm/retry", JSONObject().put("retry", 1).put("maxRetries", 5)
            .put("failure", JSONObject().put("message", "Transport failed")))
        val fixture = Fixture(handshake + retry + event("turn/end", JSONObject().put("reason", JSONObject().put("kind", "completed"))))
        assertTrue(fixture.run().completed)
        assertTrue(fixture.events.contains(DshSdkProtocolEvent.Retry(1, 5, "Transport failed")))
    }

    @Test
    fun silenceDuringHandshakePromptAndResponseHasBoundedWait() {
        for ((frames, expectedTime, stage) in listOf(
            Triple("", 30_000L, "initializing"),
            Triple("{\"id\":1,\"result\":{}}\n", 60_000L, "accepting"),
            Triple(handshake, 360_000L, "model response"),
        )) {
            val fixture = Fixture(frames)
            assertTrue(fixture.failure().message!!.contains(stage))
            assertEquals(expectedTime, fixture.time)
            assertTrue(fixture.process.forced)
        }
    }

    @Test
    fun processThatSurvivesKillCannotKeepSessionWaiting() {
        val fixture = Fixture(handshake + event("turn/end", JSONObject().put("reason", JSONObject().put("kind", "completed"))))
        fixture.process.ignoreKill = true
        assertTrue(fixture.failure().message!!.contains("did not exit"))
        assertEquals(4_500, fixture.time)
    }

    private class Fixture(frames: String) {
        val process = FakeProcess()
        var time = 0L
        val events = mutableListOf<DshSdkProtocolEvent>()
        private val output = File.createTempFile("dsh-session-test", ".jsonl").apply {
            writeText(frames + if (frames.isNotEmpty() && !frames.endsWith('\n')) "\n" else "")
        }

        fun run(): DshSdkRunResult = try {
            runBlocking {
                runDshSdkSession(process, output, "session-1", "deepseek-official", "model", "/workspace/test", "prompt",
                    now = { time }, onEvent = { events += it }, pause = { time += it })
            }
        } finally {
            output.delete()
        }

        fun failure(): Throwable = runCatching { run() }.exceptionOrNull() ?: error("Expected session failure")
    }

    private class FakeProcess : Process() {
        val input = ByteArrayOutputStream()
        var terminated = false
        var forced = false
        var ignoreKill = false
        var failShutdownWrite = false
        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(value: Int) = input.write(value)
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (failShutdownWrite && bytes.decodeToString(offset, offset + length).contains("shutdown")) {
                    throw IOException("Broken pipe")
                }
                input.write(bytes, offset, length)
            }
        }
        override fun getInputStream() = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
        override fun isAlive() = !forced || ignoreKill
        override fun waitFor(): Int = error("Session must not wait for process exit")
        override fun exitValue(): Int = if (isAlive) throw IllegalThreadStateException() else 137
        override fun destroy() { terminated = true }
        override fun destroyForcibly(): Process { forced = true; return this }
    }

    private companion object {
        val handshake = "{\"id\":1,\"result\":{}}\n{\"id\":2,\"result\":{}}\n"
        fun event(type: String, data: JSONObject): String = JSONObject().put("method", "session.event")
            .put("params", JSONObject().put("sessionId", "session-1")
                .put("event", JSONObject().put("type", type).put("data", data))).toString() + "\n"
    }
}
